'use strict';
const { sha256Hex } = require('./util');

const SESSION_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const TTL_MS = 90_000;
const MAX_ACTIVE = 5000;

/** Hitung sesi aplikasi aktif secara anonim (id di-hash, tanpa IP). */
class ActiveSessions {
  constructor({ now = () => Date.now() } = {}) {
    this.now = now;
    this.leases = new Map(); // sha256(id) -> lastSeen
  }

  #prune() {
    const t = this.now();
    for (const [k, seen] of this.leases) if (t - seen > TTL_MS) this.leases.delete(k);
  }

  count() {
    this.#prune();
    return this.leases.size;
  }

  report(body) {
    const id = String(body?.session_id || '');
    const action = body?.action;
    if (!SESSION_ID_RE.test(id) || !['heartbeat', 'end'].includes(action)) {
      return { status: 400, body: { status: 'invalid_session_report' } };
    }
    const key = sha256Hex(id);
    if (action === 'end') {
      this.leases.delete(key);
      return { status: 200, body: { status: 'ok', active_session_count: this.count() } };
    }
    this.#prune();
    if (!this.leases.has(key) && this.leases.size >= MAX_ACTIVE) {
      return { status: 429, body: { status: 'capacity_reached' } };
    }
    this.leases.set(key, this.now());
    return { status: 200, body: { status: 'ok', active_session_count: this.leases.size } };
  }
}

module.exports = { ActiveSessions, SESSION_ID_RE };
