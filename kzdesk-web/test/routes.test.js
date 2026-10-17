'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { FakeApp, renderEjs } = require('./helpers');
const { registerApi } = require('../src/routes/api');
const { registerPages } = require('../src/routes/pages');
const { registerDownloads } = require('../src/routes/downloads');
const { ReleaseService, ABIS } = require('../src/lib/releases');
const { DeviceDirectory } = require('../src/lib/devices');
const { ActiveSessions } = require('../src/lib/sessions');
const { Community } = require('../src/lib/community');
const { JsonStore } = require('../src/lib/kvstore');
const { loadConfig } = require('../src/config');
const { translator, DICT } = require('../src/lib/i18n');

const H = 'c'.repeat(64);
const GH = {
  tag_name: 'v1.3.0', html_url: 'https://github.com/kazamadev/KzDesk-Remote/releases/tag/v1.3.0', published_at: '2026-10-11T00:00:00Z', body: 'Rilis uji',
  assets: [
    { name: 'KzDesk-Remote-arm64-v8a.apk', size: 5_000_000, browser_download_url: 'https://dl.example/arm64.apk' },
    { name: 'KzDesk-Host-Setup.exe', size: 9_000_000, browser_download_url: 'https://dl.example/host.exe' },
    { name: 'SHA256SUMS.txt', size: 200, browser_download_url: 'https://dl.example/SUMS' },
  ],
};
const fetchImpl = async (url) => (String(url).includes('api.github.com')
  ? { ok: true, json: async () => GH }
  : { ok: true, text: async () => `${H}  KzDesk-Remote-arm64-v8a.apk\n${H}  KzDesk-Host-Setup.exe\n` });

function build(configEnv = {}) {
  const config = loadConfig({ ADMIN_TOKEN: 'adm-secret', BASE_URL: 'https://kz.example', ...configEnv });
  const store = new JsonStore(null);
  const deps = {
    config, store,
    releases: new ReleaseService({ config, store, fetchImpl }),
    devices: new DeviceDirectory({ config, store }),
    sessions: new ActiveSessions(),
    community: new Community({ store }),
  };
  const app = new FakeApp();
  registerApi(app, deps); registerDownloads(app, deps); registerPages(app, deps);
  return { app, deps };
}

test('GET /api/update/app answers with a verified asset', async () => {
  const { app } = build();
  const res = await app.call('GET', '/api/update/app', { query: { versionCode: '10000', abi: 'arm64-v8a' } });
  assert.equal(res.statusCode, 200);
  assert.equal(res.body.update_available, true);
  assert.equal(res.body.version_code, 10300);
  assert.equal(res.body.asset.sha256, H);
});

test('GET /api/update/host', async () => {
  const { app } = build();
  const res = await app.call('GET', '/api/update/host', { query: { version: '1.0.0' } });
  assert.equal(res.body.update_available, true);
  assert.equal(res.body.asset.file, 'KzDesk-Host-Setup.exe');
});

test('device flow through HTTP handlers', async () => {
  const { app } = build();
  const salt = crypto.randomBytes(16);
  const hash = crypto.pbkdf2Sync('pass-1234', salt, 100000, 32, 'sha256');
  const reg = await app.call('POST', '/api/device/register', { body: { salt: salt.toString('base64'), hash: hash.toString('base64'), iter: 100000, lan: ['10.0.0.5'], hostname: 'PC' } });
  assert.equal(reg.statusCode, 201);
  const ok = await app.call('POST', '/api/device/resolve', { body: { id: reg.body.display_id, password: 'pass-1234' }, ip: '1.2.3.4' });
  assert.equal(ok.statusCode, 200);
  assert.equal(ok.body.candidates[0].host, '10.0.0.5');
  const bad = await app.call('POST', '/api/device/resolve', { body: { id: reg.body.id, password: 'nope-nope' }, ip: '1.2.3.5' });
  assert.equal(bad.statusCode, 404);
  const garbage = await app.call('POST', '/api/device/resolve', { body: undefined, ip: '1.2.3.6' });
  assert.equal(garbage.statusCode, 400);
});

test('community + session routes (client contract used by the Android app)', async () => {
  const { app } = build();
  const client = 'client-id-aaaaaaaa';
  const post = await app.call('POST', '/api/jokes', { body: { client_id: client, text: 'halo komunitas' } });
  assert.equal(post.statusCode, 201);
  const feed = await app.call('GET', '/api/jokes', { headers: { 'X-KzDesk-Client-ID': client } });
  assert.equal(feed.body.items.length, 1);
  const react = await app.call('POST', `/api/jokes/${post.body.joke.id}/reaction`, { body: { client_id: client, emoji: '🔥' } });
  assert.equal(react.statusCode, 200);
  const me = await app.call('GET', '/api/jokes/me', { headers: { 'X-KzDesk-Client-ID': client } });
  assert.equal(me.statusCode, 200);
  const sess = await app.call('POST', '/api/session', { body: { session_id: '123e4567-e89b-42d3-a456-426614174000', action: 'heartbeat' } });
  assert.equal(sess.body.active_session_count, 1);
});

test('admin release-state needs the bearer token', async () => {
  const { app, deps } = build();
  const body = { version: '9.9.9', version_code: 90909 };
  assert.equal((await app.call('PUT', '/api/admin/release-state', { body })).statusCode, 403);
  assert.equal((await app.call('PUT', '/api/admin/release-state', { body, headers: { Authorization: 'Bearer wrong' } })).statusCode, 403);
  assert.equal((await app.call('PUT', '/api/admin/release-state', { body, headers: { Authorization: 'Bearer adm-secret' } })).statusCode, 200);
  assert.equal(deps.store.get('release_state_override').version, '9.9.9');
  const noToken = build({ ADMIN_TOKEN: '' });
  assert.equal((await noToken.app.call('PUT', '/api/admin/release-state', { body, headers: { Authorization: 'Bearer ' } })).statusCode, 403);
});

test('unknown /api path returns JSON 404', async () => {
  const { app } = build();
  const res = await app.call('GET', '/api/nope');
  assert.equal(res.statusCode, 404);
  assert.equal(res.body.status, 'not_found');
});

test('downloads redirect to release assets and reject unknown ABI', async () => {
  const { app } = build();
  assert.equal((await app.call('GET', '/dl/app/arm64-v8a')).location, 'https://dl.example/arm64.apk');
  assert.equal((await app.call('GET', '/dl/app/mips')).statusCode, 404);
  assert.equal((await app.call('GET', '/dl/app/x86_64')).statusCode, 404, 'ABI without asset');
  assert.equal((await app.call('GET', '/dl/host')).location, 'https://dl.example/host.exe');
  assert.equal((await app.call('GET', '/SHA256SUMS.txt')).location, 'https://dl.example/SUMS');
});

test('donate redirects to the configured URL', async () => {
  const { app } = build();
  const res = await app.call('GET', '/donate');
  assert.equal(res.statusCode, 302);
  assert.equal(res.location, 'https://www.kazamasite.biz.id/donate');
});

test('every page template renders (EJS-lite stand-in) in both languages', async () => {
  const { app, deps } = build();
  for (const lang of ['id', 'en']) {
    const base = { lang, t: translator(lang), config: deps.config, year: 2026, path: '/' };
    for (const url of ['/', '/host', '/download', '/status', '/privacy', '/terms', '/licenses']) {
      const res = await app.call('GET', url);
      assert.ok(res.view, `${url} should render a view`);
      const html = renderEjs(res.view, { ...base, ...res.locals });
      assert.match(html, /<!doctype html>/i);
      assert.match(html, /KzDesk Remote/);
      assert.ok(!/\bundefined\b/.test(html.replace(/<style[\s\S]*?<\/style>/g, '')), `${lang} ${url} leaked "undefined"`);
      assert.ok(!/style="/.test(html), `${url} has inline style (blocked by CSP)`);
    }
  }
});

test('page with a release lists every ABI that has an asset, and the host installer', async () => {
  const { app, deps } = build();
  const res = await app.call('GET', '/download');
  const html = renderEjs(res.view, { lang: 'id', t: translator('id'), config: deps.config, year: 2026, path: '/download', ...res.locals });
  assert.match(html, /arm64-v8a/);
  assert.match(html, /\/dl\/app\/arm64-v8a/);
  assert.match(html, /KzDesk-Host-Setup\.exe/);
  assert.match(html, new RegExp(H));
});

test('install.ps1 template embeds the configured base URL and escapes nothing dangerous', async () => {
  const { app, deps } = build();
  const res = await app.call('GET', '/install.ps1');
  assert.equal(res.view, 'scripts/install.ps1.ejs');
  const text = renderEjs(res.view, res.locals);
  assert.match(text, /\$Base = 'https:\/\/kz\.example'/);
  assert.ok(!text.includes('<%'));
  assert.match(text, /Get-FileHash/);
});

test('i18n: every key exists in both languages', () => {
  assert.deepEqual(Object.keys(DICT.id).sort(), Object.keys(DICT.en).sort());
  assert.ok(ABIS.length === 3);
});
