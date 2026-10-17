'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { DeviceDirectory, normalizeId, formatId, validVerifier } = require('../src/lib/devices');
const { loadConfig } = require('../src/config');
const { JsonStore } = require('../src/lib/kvstore');

const ITER = 100_000;
function verifier(password) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.pbkdf2Sync(password, salt, ITER, 32, 'sha256');
  return { salt: salt.toString('base64'), hash: hash.toString('base64'), iter: ITER };
}
function make() {
  let t = 1_000_000;
  const dir = new DeviceDirectory({ config: loadConfig({}), store: new JsonStore(null), now: () => t });
  return { dir, advance: (ms) => { t += ms; } };
}

test('id normalization / formatting', () => {
  assert.equal(normalizeId('123-456-789'), '123456789');
  assert.equal(normalizeId('012345678'), null);
  assert.equal(normalizeId('12345'), null);
  assert.equal(formatId('123456789'), '123-456-789');
});

test('verifier validation rejects weak/odd parameters', () => {
  const v = verifier('secret');
  assert.ok(validVerifier(v));
  assert.equal(validVerifier({ ...v, iter: 1000 }), null);
  assert.equal(validVerifier({ ...v, salt: 'AA==' }), null);
  assert.equal(validVerifier({ ...v, hash: Buffer.alloc(8).toString('base64') }), null);
});

test('register -> heartbeat -> resolve happy path', async () => {
  const { dir } = make();
  const r = dir.register({ ...verifier('pw-1234'), hostname: 'DESKTOP-A', lan: ['192.168.1.20', '127.0.0.1', 'bogus'], port: 3389, version: '1.0.0' }, '198.51.100.9');
  assert.equal(r.status, 201);
  assert.match(r.body.id, /^[1-9]\d{8}$/);
  assert.ok(r.body.host_secret.length >= 40);
  const res = await dir.resolve({ id: r.body.display_id, password: 'pw-1234' }, '203.0.113.1');
  assert.equal(res.status, 200);
  assert.equal(res.body.username, 'KzDesk');
  assert.deepEqual(res.body.candidates.map((c) => [c.kind, c.host]), [['lan', '192.168.1.20'], ['public', '198.51.100.9']]);
});

test('wrong password and unknown id are indistinguishable', async () => {
  const { dir } = make();
  const r = dir.register({ ...verifier('right-pass') }, '1.1.1.1');
  const wrong = await dir.resolve({ id: r.body.id, password: 'wrong-pass' }, '2.2.2.2');
  const unknown = await dir.resolve({ id: '999999999', password: 'wrong-pass' }, '2.2.2.3');
  assert.equal(wrong.status, 404);
  assert.deepEqual(wrong, unknown);
});

test('resolve is rate limited per IP and per ID', async () => {
  const { dir } = make();
  const r = dir.register({ ...verifier('right-pass') }, '1.1.1.1');
  let last;
  for (let i = 0; i < 11; i += 1) last = await dir.resolve({ id: r.body.id, password: 'nope-nope' }, '9.9.9.9');
  assert.equal(last.status, 429);
  let byId;
  for (let i = 0; i < 21; i += 1) byId = await dir.resolve({ id: r.body.id, password: 'nope-nope' }, `10.0.0.${i}`);
  assert.equal(byId.status, 429);
});

test('offline device only reveals state after correct password', async () => {
  const { dir, advance } = make();
  const r = dir.register({ ...verifier('right-pass') }, '1.1.1.1');
  advance(4 * 60_000);
  assert.equal((await dir.resolve({ id: r.body.id, password: 'right-pass' }, '5.5.5.5')).status, 409);
  assert.equal((await dir.resolve({ id: r.body.id, password: 'wrong-pass' }, '5.5.5.6')).status, 404);
  const hb = dir.heartbeat({ id: r.body.id, host_secret: r.body.host_secret, lan: ['100.100.1.2'] }, '1.1.1.2');
  assert.equal(hb.status, 200);
  const again = await dir.resolve({ id: r.body.id, password: 'right-pass' }, '5.5.5.7');
  assert.equal(again.status, 200);
  assert.equal(again.body.candidates[0].kind, 'vpn');
});

test('heartbeat needs the host secret; password can be rotated', async () => {
  const { dir } = make();
  const r = dir.register({ ...verifier('old-pass') }, '1.1.1.1');
  assert.equal(dir.heartbeat({ id: r.body.id, host_secret: 'x'.repeat(43) }, '1.1.1.1').status, 403);
  assert.equal(dir.heartbeat({ id: '100000000', host_secret: 'x' }, '1.1.1.1').status, 404);
  assert.equal(dir.heartbeat({ id: r.body.id, host_secret: r.body.host_secret, ...verifier('new-pass') }, '1.1.1.1').status, 200);
  assert.equal((await dir.resolve({ id: r.body.id, password: 'old-pass' }, '7.7.7.1')).status, 404);
  assert.equal((await dir.resolve({ id: r.body.id, password: 'new-pass' }, '7.7.7.2')).status, 200);
});

test('unregister removes the device; duplicate chosen id is rejected', async () => {
  const { dir } = make();
  const r = dir.register({ ...verifier('pw-1234'), id: '123456789' }, '1.1.1.1');
  assert.equal(r.body.id, '123456789');
  assert.equal(dir.register({ ...verifier('pw-1234'), id: '123456789' }, '1.1.1.2').status, 409);
  assert.equal(dir.unregister({ id: '123456789', host_secret: r.body.host_secret }).status, 200);
  assert.equal((await dir.resolve({ id: '123456789', password: 'pw-1234' }, '8.8.8.8')).status, 404);
});

test('registration is rate limited per IP', () => {
  const { dir } = make();
  let last;
  for (let i = 0; i < 11; i += 1) last = dir.register({ ...verifier('pw-1234') }, '6.6.6.6');
  assert.equal(last.status, 429);
});
