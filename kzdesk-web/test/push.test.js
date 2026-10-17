'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { EventEmitter } = require('node:events');
const { PushService } = require('../src/push/service');
const { FcmSender } = require('../src/push/fcm');
const { ApnsSender } = require('../src/push/apns');
const { DeviceDirectory } = require('../src/lib/devices');
const { JsonStore } = require('../src/lib/kvstore');
const { loadConfig } = require('../src/config');
const { buildSenders } = require('../src/push/senders');
const { FakeApp } = require('./helpers');
const { registerApi } = require('../src/routes/api');

const FCM_TOKEN = 'f'.repeat(152);
const APNS_TOKEN = 'a'.repeat(64);
const ITER = 100_000;
const verifier = (pw) => { const salt = crypto.randomBytes(16); return { salt: salt.toString('base64'), hash: crypto.pbkdf2Sync(pw, salt, ITER, 32, 'sha256').toString('base64'), iter: ITER }; };

function make() {
  let t = 10_000_000;
  const config = loadConfig({});
  const store = new JsonStore(null);
  const devices = new DeviceDirectory({ config, store, now: () => t });
  const sent = [];
  const mk = (platform, result) => ({ send: async (token, msg) => { sent.push({ platform, token, msg }); return typeof result === 'function' ? result(token) : result; } });
  const senders = { android: mk('android', { ok: true }), ios: mk('ios', { ok: true }) };
  const push = new PushService({ config, store, devices, senders, now: () => t });
  return { push, devices, store, sent, config, advance: (ms) => { t += ms; }, senders };
}

test('register validates token/platform and stores per-token topics', () => {
  const { push } = make();
  assert.equal(push.register({ token: 'short', platform: 'android' }, '1.1.1.1').status, 400);
  assert.equal(push.register({ token: FCM_TOKEN, platform: 'windows' }, '1.1.1.1').status, 400);
  const ok = push.register({ token: FCM_TOKEN, platform: 'android', lang: 'en', topics: { announcements: false } }, '1.1.1.1');
  assert.equal(ok.status, 200);
  assert.equal(push.count(), 1);
  push.register({ token: FCM_TOKEN, platform: 'android' }, '1.1.1.1'); // idempotent
  assert.equal(push.count(), 1);
  assert.equal(push.unregister({ token: FCM_TOKEN }).status, 200);
  assert.equal(push.count(), 0);
});

test('register is rate limited per IP', () => {
  const { push } = make();
  let last;
  for (let i = 0; i < 31; i += 1) last = push.register({ token: `${i}`.padStart(40, 'x'), platform: 'android' }, '9.9.9.9');
  assert.equal(last.status, 429);
});

test('broadcast respects topic, platform and language; removes dead tokens', async () => {
  const { push, sent, senders } = make();
  push.register({ token: FCM_TOKEN, platform: 'android', lang: 'id' }, '1.1.1.1');
  push.register({ token: APNS_TOKEN, platform: 'ios', lang: 'en', topics: { announcements: false } }, '1.1.1.2');
  const r = await push.broadcast({ title: 'Halo', body: 'Pengumuman', topic: 'announcements' });
  assert.equal(r.body.targeted, 1);
  assert.deepEqual(sent.map((s) => s.platform), ['android']);
  assert.equal((await push.broadcast({ title: '', body: 'x' })).status, 400);
  assert.equal((await push.broadcast({ title: 'a', body: 'b', topic: 'nope' })).status, 400);
  senders.android.send = async () => ({ ok: false, invalid: true });
  const dead = await push.broadcast({ title: 'a', body: 'b' });
  assert.equal(dead.body.removed, 1);
  assert.equal(push.count(), 1, 'dead token removed');
});

test('onRelease: first run only records, next new version notifies "updates" subscribers in their language', async () => {
  const { push, sent } = make();
  push.register({ token: FCM_TOKEN, platform: 'android', lang: 'en' }, '1.1.1.1');
  push.register({ token: APNS_TOKEN, platform: 'ios', lang: 'id', topics: { updates: false } }, '1.1.1.2');
  assert.equal(await push.onRelease('1.0.0'), null);
  assert.equal(sent.length, 0);
  assert.equal(await push.onRelease('1.0.0'), null, 'same version never repeats');
  const r = await push.onRelease('1.1.0');
  assert.equal(r.sent, 1);
  assert.match(sent[0].msg.body, /Version 1\.1\.0 is available/);
  assert.equal(sent[0].msg.channel, 'kz_updates');
});

test('device subscription requires the right password; presence sweep notifies on transitions', async () => {
  const { push, devices, sent, advance } = make();
  const reg = devices.register({ ...verifier('pw-secret'), hostname: 'MY-PC' }, '5.5.5.5');
  push.register({ token: FCM_TOKEN, platform: 'android', lang: 'id' }, '1.1.1.1');
  assert.equal((await push.subscribeDevice({ token: FCM_TOKEN, id: reg.body.id, password: 'wrong-pw' }, '1.1.1.1')).status, 404);
  assert.equal((await push.subscribeDevice({ token: 'z'.repeat(60), id: reg.body.id, password: 'pw-secret' }, '1.1.1.1')).status, 404);
  assert.equal((await push.subscribeDevice({ token: FCM_TOKEN, id: reg.body.id, password: 'pw-secret' }, '1.1.1.1')).status, 200);

  await push.sweepPresence();                       // baseline: online, nothing sent
  assert.equal(sent.length, 0);
  advance(4 * 60_000);                              // host berhenti heartbeat
  assert.deepEqual(await push.sweepPresence(), { online: 0, offline: 1 });
  assert.match(sent.at(-1).msg.body, /MY-PC tidak lagi terhubung/);
  await push.sweepPresence();
  assert.equal(sent.length, 1, 'no duplicate offline notice');
  devices.heartbeat({ id: reg.body.id, host_secret: reg.body.host_secret }, '5.5.5.5');
  assert.deepEqual(await push.sweepPresence(), { online: 1, offline: 0 });
  assert.match(sent.at(-1).msg.body, /MY-PC kini online/);
  assert.equal(sent.at(-1).msg.data.state, 'online');
});

test('sweep does nothing without subscribers; unsubscribe stops notices', async () => {
  const { push, devices, sent, advance } = make();
  const reg = devices.register({ ...verifier('pw-secret') }, '5.5.5.5');
  push.register({ token: FCM_TOKEN, platform: 'android' }, '1.1.1.1');
  advance(10 * 60_000);
  await push.sweepPresence();
  assert.equal(sent.length, 0);
  await push.subscribeDevice({ token: FCM_TOKEN, id: reg.body.id, password: 'pw-secret' }, '1.1.1.1');
  push.unsubscribeDevice({ token: FCM_TOKEN, id: reg.body.id });
  advance(1);
  await push.sweepPresence();
  assert.equal(sent.length, 0);
});

test('FCM sender: OAuth assertion is a valid RS256 JWT; send maps invalid tokens', async () => {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const pem = privateKey.export({ type: 'pkcs8', format: 'pem' });
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    if (String(url).includes('oauth2')) return { ok: true, json: async () => ({ access_token: 'AT', expires_in: 3600 }) };
    const body = JSON.parse(init.body);
    if (body.message.token === 'dead') return { ok: false, status: 404, json: async () => ({ error: { details: [{ errorCode: 'UNREGISTERED' }] } }) };
    return { ok: true, status: 200 };
  };
  const f = new FcmSender({ projectId: 'proj', clientEmail: 'sa@proj.iam', privateKey: pem, fetchImpl, now: () => 1_700_000_000_000 });
  const [h, b, s] = f.assertion().split('.');
  assert.equal(JSON.parse(Buffer.from(h, 'base64url')).alg, 'RS256');
  const claims = JSON.parse(Buffer.from(b, 'base64url'));
  assert.equal(claims.iss, 'sa@proj.iam'); assert.equal(claims.exp - claims.iat, 3600);
  assert.ok(crypto.verify('RSA-SHA256', Buffer.from(`${h}.${b}`), publicKey, Buffer.from(s, 'base64url')), 'signature verifies');

  assert.deepEqual(await f.send('good', { title: 'T', body: 'B', kind: 'update', data: { version: '1.2.0' }, channel: 'kz_updates' }), { ok: true });
  const send = calls.find((c) => c.url.includes('messages:send'));
  const sent = JSON.parse(send.init.body).message;
  assert.equal(send.init.headers.Authorization, 'Bearer AT');
  assert.equal(sent.data.version, '1.2.0'); assert.equal(sent.data.kind, 'update');
  assert.equal(sent.android.notification.channel_id, 'kz_updates');
  const dead = await f.send('dead', { title: 'T', body: 'B' });
  assert.equal(dead.ok, false); assert.equal(dead.invalid, true);
  assert.equal(calls.filter((c) => c.url.includes('oauth2')).length, 1, 'access token cached');
});

test('APNs sender: ES256 provider token verifies; request headers/body; invalid-token mapping', async () => {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const pem = privateKey.export({ type: 'pkcs8', format: 'pem' });
  let status = 200; let reason = ''; let captured;
  const connect = () => {
    const s = new EventEmitter(); s.closed = false; s.destroyed = false; s.close = () => { s.closed = true; };
    s.request = (headers) => {
      const req = new EventEmitter(); captured = { headers };
      req.setEncoding = () => {}; req.close = () => {};
      req.end = (body) => { captured.body = JSON.parse(body); setImmediate(() => { req.emit('response', { ':status': status }); if (reason) req.emit('data', JSON.stringify({ reason })); req.emit('end'); }); };
      return req;
    };
    return s;
  };
  const a = new ApnsSender({ keyId: 'KEY123', teamId: 'TEAM123', privateKey: pem, bundleId: 'com.kazamatech.kzdesk', production: true, connect, now: () => 1_700_000_000_000 });
  assert.equal(a.origin, 'https://api.push.apple.com');
  assert.equal(new ApnsSender({ keyId: 'k', teamId: 't', privateKey: pem, bundleId: 'b', production: false, connect }).origin, 'https://api.sandbox.push.apple.com');

  assert.deepEqual(await a.send(APNS_TOKEN, { title: 'T', body: 'B', kind: 'update', data: { version: '1.2.0' } }), { ok: true });
  assert.equal(captured.headers[':path'], `/3/device/${APNS_TOKEN}`);
  assert.equal(captured.headers['apns-topic'], 'com.kazamatech.kzdesk');
  assert.equal(captured.body.aps.alert.title, 'T'); assert.equal(captured.body.version, '1.2.0');
  const [h, b, s] = captured.headers.authorization.replace('bearer ', '').split('.');
  assert.equal(JSON.parse(Buffer.from(h, 'base64url')).kid, 'KEY123');
  assert.ok(crypto.verify('sha256', Buffer.from(`${h}.${b}`), { key: publicKey, dsaEncoding: 'ieee-p1363' }, Buffer.from(s, 'base64url')), 'ES256 verifies');

  status = 400; reason = 'BadDeviceToken';
  const bad = await a.send(APNS_TOKEN, { title: 'T', body: 'B' });
  assert.equal(bad.ok, false); assert.equal(bad.invalid, true);
  status = 429; reason = 'TooManyRequests';
  assert.equal((await a.send(APNS_TOKEN, { title: 'T', body: 'B' })).invalid, false);
});

test('senders are only built when credentials exist', () => {
  assert.deepEqual(Object.keys(buildSenders(loadConfig({}))), []);
  const { privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const env = { APNS_KEY_ID: 'K', APNS_TEAM_ID: 'T', APNS_KEY: privateKey.export({ type: 'pkcs8', format: 'pem' }).replace(/\n/g, '\\n') };
  assert.deepEqual(Object.keys(buildSenders(loadConfig(env))), ['ios']);
});

test('HTTP routes: register/subscribe and admin broadcast auth', async () => {
  const { push, devices, store, config } = make();
  const app = new FakeApp();
  const cfg = loadConfig({ ADMIN_TOKEN: 'adm' });
  registerApi(app, { config: cfg, store, releases: {}, devices, sessions: {}, community: {}, push });
  assert.equal((await app.call('POST', '/api/push/register', { body: { token: FCM_TOKEN, platform: 'android' } })).statusCode, 200);
  assert.equal((await app.call('POST', '/api/push/register', { body: { token: 'x', platform: 'android' } })).statusCode, 400);
  assert.equal((await app.call('POST', '/api/admin/push', { body: { title: 'a', body: 'b' } })).statusCode, 403);
  const ok = await app.call('POST', '/api/admin/push', { body: { title: 'Halo', body: 'Dunia' }, headers: { Authorization: 'Bearer adm' } });
  assert.equal(ok.statusCode, 200);
  assert.equal(ok.body.targeted, 1);
  assert.ok(config);
});
