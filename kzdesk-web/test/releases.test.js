'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const R = require('../src/lib/releases');
const { loadConfig } = require('../src/config');
const { JsonStore } = require('../src/lib/kvstore');

const H = 'a'.repeat(64);
const release = (tag, extra = {}) => ({
  tag_name: tag, html_url: 'https://github.com/kazamadev/KzDesk-Remote/releases/tag/' + tag,
  published_at: '2026-10-11T00:00:00Z', body: 'Catatan rilis',
  assets: [
    { name: 'KzDesk-Remote-arm64-v8a.apk', size: 1000, browser_download_url: 'https://dl/arm64.apk' },
    { name: 'KzDesk-Remote-x86_64.apk', size: 1000, browser_download_url: 'https://dl/x64.apk' },
    { name: 'KzDesk-Host-Setup.exe', size: 2000, browser_download_url: 'https://dl/host.exe' },
    { name: 'SHA256SUMS.txt', size: 300, browser_download_url: 'https://dl/SUMS' },
    { name: 'notes.txt', size: 1, browser_download_url: 'https://dl/notes' },
  ],
  ...extra,
});
const sums = `${H}  KzDesk-Remote-arm64-v8a.apk\n${'b'.repeat(64)} *KzDesk-Host-Setup.exe\n`;

function service(fetchImpl, now = () => 1_000_000) {
  return new R.ReleaseService({ config: loadConfig({ DATA_DIR: '/nonexistent' }), store: new JsonStore(null), fetchImpl, now });
}
const okFetch = (rel) => async (url) => (String(url).includes('api.github.com')
  ? { ok: true, status: 200, json: async () => rel }
  : { ok: true, status: 200, text: async () => sums });

test('semver + versionCode convention', () => {
  assert.deepEqual(R.parseVersion('v1.2.3'), [1, 2, 3]);
  assert.equal(R.parseVersion('banana'), null);
  assert.equal(R.versionCodeOf('1.0.0'), 10000);
  assert.equal(R.versionCodeOf('1.2.3'), 10203);
  assert.equal(R.versionCodeOf('1.100.0'), null);
  assert.equal(R.compareVersions('1.2.0', '1.10.0'), -1);
  assert.equal(R.compareVersions('2.0.0', '1.99.99'), 1);
});

test('parseChecksums handles both sha256sum formats', () => {
  const t = R.parseChecksums(sums);
  assert.equal(t['KzDesk-Remote-arm64-v8a.apk'], H);
  assert.equal(t['KzDesk-Host-Setup.exe'], 'b'.repeat(64));
});

test('manifest maps assets, hashes and min_version_code', () => {
  const m = R.manifestFromGithub(release('v1.2.0', { body: 'min_version_code: 10100\nfix' }), R.parseChecksums(sums), 'x/y');
  assert.equal(m.version, '1.2.0');
  assert.equal(m.version_code, 10200);
  assert.equal(m.min_version_code, 10100);
  assert.equal(m.app.abis['arm64-v8a'].sha256, H);
  assert.equal(m.app.abis['x86_64'].sha256, null);
  assert.equal(m.host.file, 'KzDesk-Host-Setup.exe');
  assert.equal(R.manifestFromGithub(release('nightly'), {}, 'x/y'), null);
});

test('appUpdate: newer + hashed asset => update, picks first supported ABI', async () => {
  const s = service(okFetch(release('v1.2.0')));
  const r = await s.appUpdate({ versionCode: '10000', abis: 'armeabi-v7a,arm64-v8a' });
  assert.equal(r.update_available, true);
  assert.equal(r.asset.abi, 'arm64-v8a');
  assert.equal(r.mandatory, false);
});

test('appUpdate: same version, or asset without sha256 => no update', async () => {
  const s = service(okFetch(release('v1.2.0')));
  assert.equal((await s.appUpdate({ versionCode: '10200', abis: 'arm64-v8a' })).update_available, false);
  // x86_64 has no checksum in SHA256SUMS -> must never be offered
  assert.equal((await s.appUpdate({ versionCode: '10000', abis: 'x86_64' })).update_available, false);
});

test('appUpdate: mandatory when below min_version_code', async () => {
  const s = service(okFetch(release('v1.2.0', { body: 'min_version_code: 10100' })));
  const r = await s.appUpdate({ versionCode: '10000', abis: 'arm64-v8a' });
  assert.equal(r.mandatory, true);
});

test('hostUpdate compares semver', async () => {
  const s = service(okFetch(release('v1.2.0')));
  assert.equal((await s.hostUpdate({ version: '1.1.9' })).update_available, true);
  assert.equal((await s.hostUpdate({ version: '1.2.0' })).update_available, false);
  assert.equal((await s.hostUpdate({ version: '' })).update_available, true);
});

test('cache is reused, stale cache survives GitHub outage, unavailable without any source', async () => {
  let calls = 0; let t = 0;
  const f = async (url) => { calls += 1; return okFetch(release('v1.0.0'))(url); };
  const s = service(f, () => t);
  await s.getManifest(); const first = calls; await s.getManifest();
  assert.equal(calls, first, 'second call served from cache');
  t += 10 * 60_000;
  s.fetch = async () => { throw new Error('offline'); };
  assert.equal((await s.getManifest()).version, '1.0.0', 'stale cache used');
  const empty = service(async () => { throw new Error('offline'); });
  assert.equal(await empty.getManifest(), null);
  assert.equal((await empty.appUpdate({ versionCode: 1, abis: 'arm64-v8a' })).status, 'unavailable');
});
