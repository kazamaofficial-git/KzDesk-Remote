'use strict';
const { sha256Hex } = require('../lib/util');
const { createLimiter } = require('../lib/ratelimit');
const { normalizeId, formatId } = require('../lib/devices');

const PLATFORMS = ['android', 'ios'];
const TOPICS = ['updates', 'announcements'];
const MAX_TOKENS = 50_000;
const MAX_DEVICES_PER_TOKEN = 10;
const TOKEN_RE = /^[A-Za-z0-9:_\-.]{32,4096}$/;

const TEXT = {
  id: {
    update: (v) => ({ title: 'Update KzDesk Remote', body: `Versi ${v} sudah tersedia. Buka aplikasi untuk memperbarui.` }),
    online: (n) => ({ title: 'PC online', body: `${n} kini online dan siap diremote.` }),
    offline: (n) => ({ title: 'PC offline', body: `${n} tidak lagi terhubung.` }),
  },
  en: {
    update: (v) => ({ title: 'KzDesk Remote update', body: `Version ${v} is available. Open the app to update.` }),
    online: (n) => ({ title: 'PC online', body: `${n} is online and ready to control.` }),
    offline: (n) => ({ title: 'PC offline', body: `${n} is no longer connected.` }),
  },
};

/**
 * Pendaftaran token push + pengiriman. Pengirim (FCM/APNs) disuntik sehingga mudah diuji.
 * Data di store: { push: { tokens: { <sha256(token)>: record } } }
 */
class PushService {
  constructor({ config, store, devices, senders = {}, now = () => Date.now() }) {
    this.config = config;
    this.store = store;
    this.devices = devices;
    this.senders = senders; // { android: {send}, ios: {send} }
    this.now = now;
    this.registerLimit = createLimiter({ windowMs: 3600_000, max: 30, now });
    this.subscribeLimit = createLimiter({ windowMs: 600_000, max: 20, now });
  }

  #db() {
    let db = this.store.get('push');
    if (!db) { db = { tokens: {}, lastAnnouncedVersion: null }; this.store.set('push', db); }
    return db;
  }

  enabled() { return Boolean(this.senders.android || this.senders.ios); }

  count() { return Object.keys(this.#db().tokens).length; }

  // -------------------------------------------------------- pendaftaran
  register(body, ip) {
    const rl = this.registerLimit.hit(ip);
    if (!rl.allowed) return { status: 429, body: { status: 'rate_limited', retry_after_ms: rl.retryAfterMs } };
    const token = typeof body?.token === 'string' ? body.token.trim() : '';
    if (!TOKEN_RE.test(token)) return { status: 400, body: { status: 'invalid_token' } };
    if (!PLATFORMS.includes(body.platform)) return { status: 400, body: { status: 'invalid_platform' } };
    const db = this.#db();
    const key = sha256Hex(token);
    if (!db.tokens[key] && this.count() >= MAX_TOKENS) return { status: 503, body: { status: 'capacity_reached' } };
    const prev = db.tokens[key];
    const topics = {};
    for (const t of TOPICS) topics[t] = body.topics && typeof body.topics === 'object' ? body.topics[t] !== false : true;
    db.tokens[key] = {
      token,
      platform: body.platform,
      lang: body.lang === 'en' ? 'en' : 'id',
      appVersion: String(body.app_version || '').slice(0, 24),
      topics,
      devices: prev?.devices || [],
      createdAt: prev?.createdAt || this.now(),
      lastSeen: this.now(),
    };
    this.store.touch();
    return { status: 200, body: { status: 'ok', push_enabled_on_server: this.enabled() } };
  }

  unregister(body) {
    const token = typeof body?.token === 'string' ? body.token.trim() : '';
    if (!TOKEN_RE.test(token)) return { status: 400, body: { status: 'invalid_token' } };
    delete this.#db().tokens[sha256Hex(token)];
    this.store.touch();
    return { status: 200, body: { status: 'ok' } };
  }

  /** Langganan status satu PC (online/offline). Wajib ID + password yang benar. */
  async subscribeDevice(body, ip) {
    const rl = this.subscribeLimit.hit(ip);
    if (!rl.allowed) return { status: 429, body: { status: 'rate_limited', retry_after_ms: rl.retryAfterMs } };
    const token = typeof body?.token === 'string' ? body.token.trim() : '';
    const rec = TOKEN_RE.test(token) ? this.#db().tokens[sha256Hex(token)] : null;
    if (!rec) return { status: 404, body: { status: 'token_not_registered' } };
    const id = normalizeId(body.id);
    if (!id) return { status: 400, body: { status: 'invalid_id' } };
    const ok = await this.devices.verifyPassword(id, String(body.password || ''), ip);
    if (!ok) return { status: 404, body: { status: 'not_found_or_wrong_password' } };
    if (!rec.devices.includes(id)) {
      if (rec.devices.length >= MAX_DEVICES_PER_TOKEN) return { status: 400, body: { status: 'too_many_devices' } };
      rec.devices.push(id);
      this.store.touch();
    }
    return { status: 200, body: { status: 'ok', display_id: formatId(id) } };
  }

  unsubscribeDevice(body) {
    const token = typeof body?.token === 'string' ? body.token.trim() : '';
    const rec = TOKEN_RE.test(token) ? this.#db().tokens[sha256Hex(token)] : null;
    const id = normalizeId(body?.id);
    if (!rec || !id) return { status: 400, body: { status: 'invalid_request' } };
    rec.devices = rec.devices.filter((d) => d !== id);
    this.store.touch();
    return { status: 200, body: { status: 'ok' } };
  }

  // ----------------------------------------------------------- kirim
  /** Kirim ke token yang lolos filter. Token tidak valid otomatis dihapus. */
  async sendTo(records, build) {
    const db = this.#db();
    const result = { sent: 0, failed: 0, removed: 0, skipped: 0 };
    for (const rec of records) {
      const sender = this.senders[rec.platform];
      if (!sender) { result.skipped++; continue; }
      let r;
      try { r = await sender.send(rec.token, build(rec)); } catch (e) { r = { ok: false, error: e.message }; }
      if (r.ok) result.sent++;
      else {
        result.failed++;
        if (r.invalid) { delete db.tokens[sha256Hex(rec.token)]; result.removed++; }
      }
    }
    if (result.removed) this.store.touch();
    return result;
  }

  #records(filter) { return Object.values(this.#db().tokens).filter(filter); }

  /** Pengumuman manual dari admin. */
  async broadcast({ title, body, topic = 'announcements', platform, lang }) {
    const t = String(title || '').slice(0, 80);
    const b = String(body || '').slice(0, 240);
    if (!t || !b) return { status: 400, body: { status: 'invalid_message' } };
    if (!TOPICS.includes(topic)) return { status: 400, body: { status: 'invalid_topic' } };
    const recs = this.#records((r) => r.topics[topic] && (!platform || r.platform === platform) && (!lang || r.lang === lang));
    const result = await this.sendTo(recs, () => ({ title: t, body: b, kind: topic === 'updates' ? 'update' : 'announcement', channel: topic === 'updates' ? 'kz_updates' : 'kz_announcements' }));
    return { status: 200, body: { status: 'ok', targeted: recs.length, ...result } };
  }

  /** Dipanggil saat manifest rilis berubah. Notifikasi sekali per versi; run pertama hanya mencatat. */
  async onRelease(version) {
    const db = this.#db();
    if (!version || db.lastAnnouncedVersion === version) return null;
    const first = db.lastAnnouncedVersion === null;
    db.lastAnnouncedVersion = version;
    this.store.touch();
    if (first) return null;
    const recs = this.#records((r) => r.topics.updates);
    return this.sendTo(recs, (r) => ({ ...TEXT[r.lang].update(version), kind: 'update', channel: 'kz_updates', data: { version } }));
  }

  /**
   * Sapu status PC (jalankan tiap ~60 dtk). Memberi tahu pelanggan saat PC kembali online,
   * atau saat PC offline lebih lama dari batas online. Tidak mengirim apa-apa bila tak ada pelanggan.
   */
  async sweepPresence() {
    const devices = this.store.get('devices') || {};
    const subs = new Map(); // deviceId -> records[]
    for (const rec of Object.values(this.#db().tokens)) {
      for (const id of rec.devices) {
        if (!subs.has(id)) subs.set(id, []);
        subs.get(id).push(rec);
      }
    }
    const out = { online: 0, offline: 0 };
    const t = this.now();
    for (const [id, recs] of subs) {
      const d = devices[id];
      if (!d) continue;
      const isOnline = t - d.lastSeen <= this.config.deviceOnlineTtlMs;
      const state = d.presence || (isOnline ? 'online' : 'offline');
      if (isOnline && state === 'offline') {
        d.presence = 'online'; this.store.touch();
        await this.sendTo(recs, (r) => ({ ...TEXT[r.lang].online(d.hostname || formatId(id)), kind: 'device', channel: 'kz_devices', data: { device_id: id, state: 'online' } }));
        out.online++;
      } else if (!isOnline && state === 'online') {
        d.presence = 'offline'; this.store.touch();
        await this.sendTo(recs, (r) => ({ ...TEXT[r.lang].offline(d.hostname || formatId(id)), kind: 'device', channel: 'kz_devices', data: { device_id: id, state: 'offline' } }));
        out.offline++;
      } else if (d.presence === undefined) {
        d.presence = state; this.store.touch();
      }
    }
    return out;
  }
}

module.exports = { PushService, PLATFORMS, TOPICS, TEXT };
