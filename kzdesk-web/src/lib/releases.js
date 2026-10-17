'use strict';
const fs = require('node:fs');
const path = require('node:path');

const ABIS = Object.freeze(['arm64-v8a', 'armeabi-v7a', 'x86_64']);
const APK_RE = /^KzDesk-Remote-(arm64-v8a|armeabi-v7a|x86_64)\.apk$/;
const HOST_FILE = 'KzDesk-Host-Setup.exe';
const SUMS_FILE = 'SHA256SUMS.txt';

/** "v1.2.3" / "1.2.3-beta" -> [1,2,3] atau null. */
function parseVersion(tag) {
  const m = /^v?(\d{1,4})\.(\d{1,4})\.(\d{1,4})(?:[-+].*)?$/.exec(String(tag || '').trim());
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
}

/**
 * Konvensi versionCode Android: major*10000 + minor*100 + patch.
 * HARUS sama dengan VERSION_CODE di client/Android/Studio/release.properties.
 */
function versionCodeOf(version) {
  const v = Array.isArray(version) ? version : parseVersion(version);
  if (!v || v[1] > 99 || v[2] > 99) return null;
  return v[0] * 10000 + v[1] * 100 + v[2];
}

function compareVersions(a, b) {
  const x = parseVersion(a);
  const y = parseVersion(b);
  if (!x || !y) return 0;
  for (let i = 0; i < 3; i += 1) if (x[i] !== y[i]) return x[i] < y[i] ? -1 : 1;
  return 0;
}

/** Parse isi SHA256SUMS.txt -> { namaFile: hashHex }. */
function parseChecksums(text) {
  const out = {};
  for (const line of String(text || '').split(/\r?\n/)) {
    const m = /^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$/.exec(line);
    if (m) out[m[2].trim()] = m[1].toLowerCase();
  }
  return out;
}

/** Bangun manifest dari JSON release GitHub + tabel checksum. */
function manifestFromGithub(release, sums, repo) {
  const version = String(release.tag_name || '').replace(/^v/, '');
  const parsed = parseVersion(version);
  const code = versionCodeOf(parsed);
  if (!parsed || code === null) return null;
  const assets = Array.isArray(release.assets) ? release.assets : [];
  const describe = (a) => ({
    file: a.name,
    url: a.browser_download_url,
    size: Number(a.size) || 0,
    sha256: sums[a.name] || null,
  });
  const abis = {};
  let host = null;
  let checksumsUrl = null;
  for (const a of assets) {
    if (!a || typeof a.name !== 'string' || typeof a.browser_download_url !== 'string') continue;
    const apk = APK_RE.exec(a.name);
    if (apk) abis[apk[1]] = describe(a);
    else if (a.name === HOST_FILE) host = describe(a);
    else if (a.name === SUMS_FILE) checksumsUrl = a.browser_download_url;
  }
  const notes = String(release.body || '');
  const minMatch = /^\s*min[_ -]?version[_ -]?code\s*[:=]\s*(\d+)/im.exec(notes);
  return {
    status: 'ok',
    source: 'github',
    repo,
    tag: release.tag_name,
    version,
    version_code: code,
    min_version_code: minMatch ? Number(minMatch[1]) : 0,
    published_at: release.published_at || null,
    notes: notes.slice(0, 2000),
    release_url: release.html_url || `https://github.com/${repo}/releases`,
    app: { abis },
    host,
    checksums_url: checksumsUrl,
  };
}

/** Pilih asset APK dari daftar ABI preferensi perangkat. */
function pickApk(manifest, abis) {
  const available = manifest?.app?.abis || {};
  for (const abi of abis) {
    if (ABIS.includes(abi) && available[abi]?.url) return { abi, ...available[abi] };
  }
  return null;
}

function normalizeAbis(input) {
  const list = Array.isArray(input) ? input : String(input || '').split(',');
  return list.map((s) => String(s).trim()).filter((s) => ABIS.includes(s));
}

class ReleaseService {
  /**
   * @param {{config: object, store: {get:Function,set:Function}, fetchImpl?: Function, now?: () => number}} deps
   */
  constructor({ config, store, fetchImpl = globalThis.fetch, now = () => Date.now() }) {
    this.config = config;
    this.store = store;
    this.fetch = fetchImpl;
    this.now = now;
    this.cache = null; // {at, manifest}
    this.inflight = null;
  }

  async getManifest() {
    const t = this.now();
    if (this.cache && t - this.cache.at < this.config.releaseTtlMs) return this.cache.manifest;
    if (!this.inflight) {
      this.inflight = this.#refresh().finally(() => { this.inflight = null; });
    }
    return this.inflight;
  }

  async #refresh() {
    let manifest = null;
    try {
      manifest = await this.#fromGithub();
    } catch (err) {
      console.warn('[releases] GitHub gagal:', err.message);
    }
    if (manifest) {
      this.cache = { at: this.now(), manifest };
      // Pemicu push "rilis baru"; kegagalan notifikasi tidak boleh mengganggu respons.
      try { Promise.resolve(this.onManifest?.(manifest)).catch(() => {}); } catch { /* abaikan */ }
      return manifest;
    }
    // Gagal: pakai cache lama (basi) dulu, lalu file cadangan.
    if (this.cache) return this.cache.manifest;
    const fallback = this.#fromFile();
    if (fallback) {
      // Cache singkat supaya GitHub tidak dipukul tiap request saat sedang down.
      this.cache = { at: this.now() - this.config.releaseTtlMs + 30_000, manifest: fallback };
    }
    return fallback;
  }

  async #fromGithub() {
    const { githubRepo: repo, githubToken: token } = this.config;
    const headers = { Accept: 'application/vnd.github+json', 'User-Agent': 'KzDesk-Remote-Server' };
    if (token) headers.Authorization = `Bearer ${token}`;
    const res = await this.fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
      headers,
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const release = await res.json();
    let sums = {};
    const sumsAsset = (release.assets || []).find((a) => a?.name === SUMS_FILE);
    if (sumsAsset?.browser_download_url) {
      try {
        const r = await this.fetch(sumsAsset.browser_download_url, { signal: AbortSignal.timeout(8000) });
        if (r.ok) sums = parseChecksums((await r.text()).slice(0, 65536));
      } catch { /* checksum opsional di sisi server; klien menolak update tanpa hash */ }
    }
    return manifestFromGithub(release, sums, repo);
  }

  #fromFile() {
    const override = this.store.get('release_state_override');
    if (override && override.version) return { ...override, source: 'override' };
    try {
      const file = path.join(this.config.dataDir, 'release-state.json');
      const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (parsed && parsed.version) return { ...parsed, source: 'file' };
    } catch { /* tidak ada file cadangan */ }
    return null;
  }

  /** Keputusan update untuk aplikasi Android. */
  async appUpdate({ versionCode, abis }) {
    const manifest = await this.getManifest();
    if (!manifest) return { status: 'unavailable' };
    const current = Number.parseInt(versionCode, 10) || 0;
    const apk = pickApk(manifest, normalizeAbis(abis));
    const newer = manifest.version_code > current;
    // Tanpa SHA-256 klien tidak boleh memasang; sampaikan sebagai tidak tersedia.
    const installable = Boolean(apk && apk.sha256);
    return {
      status: 'ok',
      update_available: newer && installable,
      mandatory: newer && installable && current < (manifest.min_version_code || 0),
      version: manifest.version,
      version_code: manifest.version_code,
      published_at: manifest.published_at,
      notes: manifest.notes,
      release_url: manifest.release_url,
      asset: apk ? { abi: apk.abi, file: apk.file, url: apk.url, size: apk.size, sha256: apk.sha256 } : null,
      checked_at: new Date(this.now()).toISOString(),
    };
  }

  /** Keputusan update untuk Host Windows (dibandingkan lewat semver). */
  async hostUpdate({ version }) {
    const manifest = await this.getManifest();
    if (!manifest) return { status: 'unavailable' };
    const host = manifest.host;
    const installable = Boolean(host && host.url && host.sha256);
    const newer = parseVersion(version) ? compareVersions(version, manifest.version) < 0 : true;
    return {
      status: 'ok',
      update_available: newer && installable,
      version: manifest.version,
      published_at: manifest.published_at,
      notes: manifest.notes,
      release_url: manifest.release_url,
      asset: installable ? { file: host.file, url: host.url, size: host.size, sha256: host.sha256 } : null,
      checked_at: new Date(this.now()).toISOString(),
    };
  }
}

module.exports = {
  ABIS, HOST_FILE, SUMS_FILE, ReleaseService,
  parseVersion, versionCodeOf, compareVersions, parseChecksums,
  manifestFromGithub, pickApk, normalizeAbis,
};
