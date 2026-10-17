'use strict';
const fs = require('node:fs');
const path = require('node:path');

/**
 * Penyimpanan JSON sederhana: seluruh data di memori, ditulis atomik
 * (tulis file sementara lalu rename) dengan debounce. Cukup untuk satu
 * proses server kecil; ganti dengan SQLite/Postgres bila perlu skala besar.
 */
class JsonStore {
  /** @param {string|null} file  null = hanya memori (untuk tes) */
  constructor(file = null, { debounceMs = 400 } = {}) {
    this.file = file;
    this.debounceMs = debounceMs;
    this.data = {};
    this.timer = null;
    if (file) {
      try {
        const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) this.data = parsed;
      } catch { /* file belum ada / rusak -> mulai kosong */ }
    }
  }

  get(key, fallback = undefined) {
    return Object.prototype.hasOwnProperty.call(this.data, key) ? this.data[key] : fallback;
  }

  set(key, value) {
    this.data[key] = value;
    this.touch();
  }

  /** Beri tahu store bahwa objek di dalam data dimutasi langsung. */
  touch() {
    if (!this.file || this.timer) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      try { this.flush(); } catch (e) { console.error('[store] gagal menyimpan:', e.message); }
    }, this.debounceMs);
    this.timer.unref?.();
  }

  delete(key) {
    delete this.data[key];
    this.touch();
  }

  flush() {
    if (this.timer) { clearTimeout(this.timer); this.timer = null; }
    if (!this.file) return;
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.data), { mode: 0o600 });
    fs.renameSync(tmp, this.file);
  }
}

module.exports = { JsonStore };
