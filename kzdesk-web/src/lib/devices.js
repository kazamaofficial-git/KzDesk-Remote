'use strict';
const crypto = require('node:crypto');
const net = require('node:net');
const { promisify } = require('node:util');
const { sha256Hex, safeEqual, randomToken } = require('./util');
const { createLimiter } = require('./ratelimit');

const pbkdf2 = promisify(crypto.pbkdf2);

const RDP_USERNAME = 'KzDesk';
const MIN_ITER = 100_000;
const MAX_ITER = 1_000_000;
const HASH_BYTES = 32;
const MAX_LAN = 8;

/** "123-456-789" / "123456789" -> "123456789" atau null. */
function normalizeId(input) {
  const digits = String(input ?? '').replace(/[\s-]/g, '');
  return /^[1-9]\d{8}$/.test(digits) ? digits : null;
}

const formatId = (id) => `${id.slice(0, 3)}-${id.slice(3, 6)}-${id.slice(6)}`;

function validVerifier(v) {
  if (!v || typeof v !== 'object') return null;
  const iter = Number(v.iter);
  if (!Number.isInteger(iter) || iter < MIN_ITER || iter > MAX_ITER) return null;
  const salt = typeof v.salt === 'string' ? Buffer.from(v.salt, 'base64') : null;
  const hash = typeof v.hash === 'string' ? Buffer.from(v.hash, 'base64') : null;
  if (!salt || salt.length < 8 || salt.length > 64) return null;
  if (!hash || hash.length !== HASH_BYTES) return null;
  return { iter, salt: salt.toString('base64'), hash: hash.toString('base64') };
}

function cleanAddresses(list) {
  if (!Array.isArray(list)) return [];
  const seen = new Set();
  const out = [];
  for (const raw of list) {
    const ip = String(raw ?? '').trim();
    if (net.isIP(ip) && !seen.has(ip) && !ip.startsWith('127.') && !ip.startsWith('169.254.') && ip !== '::1') {
      seen.add(ip);
      out.push(ip);
      if (out.length >= MAX_LAN) break;
    }
  }
  return out;
}

/** 100.64.0.0/10 = CGNAT / Tailscale. */
function isTailnet(ip) {
  const m = /^100\.(\d+)\./.exec(ip);
  return Boolean(m && Number(m[1]) >= 64 && Number(m[1]) <= 127);
}

const cleanText = (v, max) => String(v ?? '').replace(/[\u0000-\u001F\u007F]/g, '').trim().slice(0, max);

function validPort(v) {
  const n = Number(v);
  return Number.isInteger(n) && n >= 1 && n <= 65535 ? n : 3389;
}

class DeviceDirectory {
  constructor({ config, store, now = () => Date.now() }) {
    this.config = config;
    this.store = store;
    this.now = now;
    this.registerLimit = createLimiter({ windowMs: 3600_000, max: 10, now });
    this.resolveIpLimit = createLimiter({ windowMs: 60_000, max: 10, now });
    this.resolveIdLimit = createLimiter({ windowMs: 600_000, max: 20, now });
    // Verifier palsu: waktu PBKDF2 tetap sama untuk ID yang tidak ada (anti-enumerasi).
    this.dummy = { iter: MIN_ITER, salt: crypto.randomBytes(16) };
  }

  #devices() {
    let d = this.store.get('devices');
    if (!d) { d = {}; this.store.set('devices', d); }
    return d;
  }

  #prune() {
    const devices = this.#devices();
    const cutoff = this.now() - this.config.deviceForgetMs;
    let changed = false;
    for (const [id, rec] of Object.entries(devices)) {
      if (rec.lastSeen < cutoff) { delete devices[id]; changed = true; }
    }
    if (changed) this.store.touch();
  }

  #allocateId(devices) {
    for (let i = 0; i < 20; i += 1) {
      const id = String(crypto.randomInt(100_000_000, 1_000_000_000));
      if (!devices[id]) return id;
    }
    return null;
  }

  /** Host baru mendaftar. Balasan membawa host_secret SEKALI SAJA. */
  register(body, ip) {
    const rl = this.registerLimit.hit(ip);
    if (!rl.allowed) return { status: 429, body: { status: 'rate_limited', retry_after_ms: rl.retryAfterMs } };
    const verifier = validVerifier(body);
    if (!verifier) return { status: 400, body: { status: 'invalid_verifier' } };
    const devices = this.#devices();
    let id;
    if (body.id !== undefined && body.id !== null && body.id !== '') {
      id = normalizeId(body.id);
      if (!id) return { status: 400, body: { status: 'invalid_id' } };
      if (devices[id]) return { status: 409, body: { status: 'id_taken' } };
    } else {
      id = this.#allocateId(devices);
      if (!id) return { status: 503, body: { status: 'unavailable' } };
    }
    const secret = randomToken(32);
    const t = this.now();
    devices[id] = {
      id,
      secretHash: sha256Hex(secret),
      verifier,
      hostname: cleanText(body.hostname, 64),
      lan: cleanAddresses(body.lan),
      port: validPort(body.port),
      version: cleanText(body.version, 24),
      publicIp: String(ip || ''),
      createdAt: t,
      lastSeen: t,
    };
    this.store.touch();
    this.#prune();
    return { status: 201, body: { status: 'ok', id, display_id: formatId(id), host_secret: secret } };
  }

  #authHost(body) {
    const id = normalizeId(body?.id);
    const rec = id ? this.#devices()[id] : null;
    if (!rec) return { error: { status: 404, body: { status: 'not_found' } } };
    if (typeof body.host_secret !== 'string' || !safeEqual(sha256Hex(body.host_secret), rec.secretHash)) {
      return { error: { status: 403, body: { status: 'forbidden' } } };
    }
    return { rec };
  }

  heartbeat(body, ip) {
    const auth = this.#authHost(body);
    if (auth.error) return auth.error;
    const { rec } = auth;
    if (body.lan !== undefined) rec.lan = cleanAddresses(body.lan);
    if (body.port !== undefined) rec.port = validPort(body.port);
    if (body.hostname !== undefined) rec.hostname = cleanText(body.hostname, 64);
    if (body.version !== undefined) rec.version = cleanText(body.version, 24);
    if (body.hash !== undefined) {
      const verifier = validVerifier(body);
      if (!verifier) return { status: 400, body: { status: 'invalid_verifier' } };
      rec.verifier = verifier; // password diganti dari sisi host
    }
    rec.publicIp = String(ip || rec.publicIp || '');
    rec.lastSeen = this.now();
    this.store.touch();
    return { status: 200, body: { status: 'ok', online_ttl_s: Math.round(this.config.deviceOnlineTtlMs / 1000) } };
  }

  unregister(body) {
    const auth = this.#authHost(body);
    if (auth.error) return auth.error;
    delete this.#devices()[auth.rec.id];
    this.store.touch();
    return { status: 200, body: { status: 'ok' } };
  }

  /** Aplikasi memberi ID + password; balasan hanya bila keduanya benar. */
  async resolve(body, ip) {
    const ipRl = this.resolveIpLimit.hit(ip);
    if (!ipRl.allowed) return { status: 429, body: { status: 'rate_limited', retry_after_ms: ipRl.retryAfterMs } };
    const id = normalizeId(body?.id);
    const password = typeof body?.password === 'string' ? body.password : '';
    if (!id || password.length < 4 || password.length > 128) {
      return { status: 400, body: { status: 'invalid_request' } };
    }
    const idRl = this.resolveIdLimit.hit(id);
    if (!idRl.allowed) return { status: 429, body: { status: 'rate_limited', retry_after_ms: idRl.retryAfterMs } };

    const rec = this.#devices()[id];
    const iter = rec ? rec.verifier.iter : this.dummy.iter;
    const salt = rec ? Buffer.from(rec.verifier.salt, 'base64') : this.dummy.salt;
    const derived = await pbkdf2(password, salt, iter, HASH_BYTES, 'sha256');
    const expected = rec ? Buffer.from(rec.verifier.hash, 'base64') : Buffer.alloc(HASH_BYTES);
    const ok = rec && derived.length === expected.length && crypto.timingSafeEqual(derived, expected);
    // Pesan sama untuk ID salah dan password salah.
    if (!ok) return { status: 404, body: { status: 'not_found_or_wrong_password' } };

    const online = this.now() - rec.lastSeen <= this.config.deviceOnlineTtlMs;
    if (!online) {
      return { status: 409, body: { status: 'offline', last_seen: new Date(rec.lastSeen).toISOString() } };
    }
    const candidates = [];
    for (const addr of rec.lan) candidates.push({ kind: isTailnet(addr) ? 'vpn' : 'lan', host: addr, port: rec.port });
    if (rec.publicIp && net.isIP(rec.publicIp)) candidates.push({ kind: 'public', host: rec.publicIp, port: rec.port });
    return {
      status: 200,
      body: {
        status: 'ok',
        id,
        display_id: formatId(id),
        hostname: rec.hostname,
        username: RDP_USERNAME,
        host_version: rec.version,
        candidates,
      },
    };
  }

  /** Verifikasi ID + password (berbagi pembatas laju dengan resolve). Mengembalikan boolean. */
  async verifyPassword(id, password, ip) {
    if (!this.resolveIpLimit.hit(ip).allowed || !this.resolveIdLimit.hit(id).allowed) return false;
    if (typeof password !== 'string' || password.length < 4 || password.length > 128) return false;
    const rec = this.#devices()[id];
    const iter = rec ? rec.verifier.iter : this.dummy.iter;
    const salt = rec ? Buffer.from(rec.verifier.salt, 'base64') : this.dummy.salt;
    const derived = await pbkdf2(password, salt, iter, HASH_BYTES, 'sha256');
    const expected = rec ? Buffer.from(rec.verifier.hash, 'base64') : Buffer.alloc(HASH_BYTES);
    return Boolean(rec) && derived.length === expected.length && crypto.timingSafeEqual(derived, expected);
  }

  count() {
    const cutoff = this.now() - this.config.deviceOnlineTtlMs;
    return Object.values(this.#devices()).filter((d) => d.lastSeen >= cutoff).length;
  }
}

module.exports = { DeviceDirectory, normalizeId, formatId, validVerifier, cleanAddresses, isTailnet, RDP_USERNAME };
