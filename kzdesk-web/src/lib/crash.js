'use strict';
const { sha256Hex } = require('./util');
const { createLimiter } = require('./ratelimit');

const MAX_REPORTS = 300;
const MAX_STACK = 8000;
const CONTROL = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g;
// Pengaman tambahan di sisi server (aplikasi sudah menyamarkan, tapi jangan percaya klien).
const IPV4 = /\b(?:\d{1,3}\.){3}\d{1,3}\b/g;
const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;

const clean = (v, max) => String(v ?? '').replace(CONTROL, ' ').trim().slice(0, max);
const scrub = (s) => s.replace(IPV4, '<ip>').replace(EMAIL, '<email>');

/** Laporan crash opt-in dari aplikasi. Hanya teks stack trace + info versi/perangkat umum. */
class CrashReports {
  constructor({ store, now = () => Date.now() }) {
    this.store = store;
    this.now = now;
    this.limit = createLimiter({ windowMs: 3600_000, max: 5, now });
  }

  #db() {
    let db = this.store.get('crashes');
    if (!db) { db = { items: [] }; this.store.set('crashes', db); }
    return db;
  }

  submit(body, ip) {
    const rl = this.limit.hit(ip);
    if (!rl.allowed) return { status: 429, body: { status: 'rate_limited', retry_after_ms: rl.retryAfterMs } };
    const stack = scrub(clean(body?.stack, MAX_STACK));
    if (stack.length < 20) return { status: 400, body: { status: 'invalid_report' } };
    const report = {
      appVersion: clean(body.app_version, 24),
      android: Number.isInteger(body.android) ? body.android : null,
      device: scrub(clean(body.device, 60)),
      stack,
    };
    // Sidik jari: 4 baris pertama stack tanpa angka (nomor baris/alamat) -> crash serupa digabung.
    const fingerprint = sha256Hex(stack.split('\n').slice(0, 4).join('\n').replace(/\d+/g, '#')).slice(0, 16);
    const db = this.#db();
    const t = this.now();
    const existing = db.items.find((r) => r.fingerprint === fingerprint);
    if (existing) {
      existing.count += 1;
      existing.lastSeen = t;
      if (report.appVersion && !existing.versions.includes(report.appVersion) && existing.versions.length < 10) existing.versions.push(report.appVersion);
    } else {
      db.items.push({ fingerprint, count: 1, firstSeen: t, lastSeen: t, versions: report.appVersion ? [report.appVersion] : [], android: report.android, device: report.device, stack: report.stack });
      while (db.items.length > MAX_REPORTS) db.items.shift();
    }
    this.store.touch();
    return { status: 202, body: { status: 'accepted', fingerprint } };
  }

  list() {
    return [...this.#db().items].sort((a, b) => b.lastSeen - a.lastSeen);
  }
}

module.exports = { CrashReports, MAX_STACK, MAX_REPORTS };
