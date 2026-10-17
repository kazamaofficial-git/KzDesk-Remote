'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { CrashReports } = require('../src/lib/crash');
const { JsonStore } = require('../src/lib/kvstore');
const { FakeApp } = require('./helpers');
const { registerApi } = require('../src/routes/api');
const { loadConfig } = require('../src/config');

const STACK = 'java.lang.IllegalStateException: boom\n\tat com.kazamatech.kzdesk.ui.Foo.bar(Foo.kt:42)\n\tat com.kazamatech.kzdesk.ui.Baz.qux(Baz.kt:7)';
const make = () => { let t = 1_000; const c = new CrashReports({ store: new JsonStore(null), now: () => t }); return { c, advance: (ms) => { t += ms; } }; };

test('accepts a report and groups the same crash by fingerprint', () => {
  const { c } = make();
  assert.equal(c.submit({ stack: STACK, app_version: '1.0.0', android: 34, device: 'Google Pixel 8' }, '1.1.1.1').status, 202);
  // same crash, different line numbers / version
  const again = STACK.replace('Foo.kt:42', 'Foo.kt:99');
  assert.equal(c.submit({ stack: again, app_version: '1.0.1' }, '1.1.1.2').status, 202);
  const list = c.list();
  assert.equal(list.length, 1);
  assert.equal(list[0].count, 2);
  assert.deepEqual(list[0].versions, ['1.0.0', '1.0.1']);
});

test('different crashes stay separate; list is newest first', () => {
  const { c, advance } = make();
  c.submit({ stack: STACK }, '1.1.1.1');
  advance(10);
  c.submit({ stack: 'java.lang.NullPointerException: x\n\tat a.b.C.d(C.kt:1)\n\tat a.b.E.f(E.kt:2)' }, '1.1.1.2');
  const list = c.list();
  assert.equal(list.length, 2);
  assert.match(list[0].stack, /NullPointerException/);
});

test('scrubs IPs and e-mails server-side and strips control characters', () => {
  const { c } = make();
  c.submit({ stack: `java.net.ConnectException: failed to 192.168.1.20:3389 for user@example.com\u0007\n\tat x.Y.z(Y.kt:1)`, device: 'Dev 10.0.0.1' }, '1.1.1.1');
  const [r] = c.list();
  assert.ok(!r.stack.includes('192.168.1.20'));
  assert.ok(!r.stack.includes('user@example.com'));
  assert.ok(!/\u0007/.test(r.stack));
  assert.match(r.stack, /<ip>/);
  assert.match(r.device, /<ip>/);
});

test('rejects tiny/missing stacks, truncates huge ones, rate limits per IP', () => {
  const { c } = make();
  assert.equal(c.submit({ stack: 'x' }, '1.1.1.1').status, 400);
  assert.equal(c.submit({}, '1.1.1.1').status, 400);
  assert.equal(c.submit({ stack: 'A'.repeat(50_000) }, '2.2.2.2').status, 202);
  assert.ok(c.list()[0].stack.length <= 8000);
  let last;
  for (let i = 0; i < 6; i += 1) last = c.submit({ stack: `${i}`.repeat(30) }, '9.9.9.9');
  assert.equal(last.status, 429);
});

test('HTTP: POST /api/crash and admin listing needs the bearer token', async () => {
  const config = loadConfig({ ADMIN_TOKEN: 'adm' });
  const store = new JsonStore(null);
  const crash = new CrashReports({ store });
  const app = new FakeApp();
  registerApi(app, { config, store, releases: {}, devices: {}, sessions: {}, community: {}, push: {}, crash });
  assert.equal((await app.call('POST', '/api/crash', { body: { stack: STACK } })).statusCode, 202);
  assert.equal((await app.call('GET', '/api/admin/crashes')).statusCode, 403);
  const ok = await app.call('GET', '/api/admin/crashes', { headers: { Authorization: 'Bearer adm' } });
  assert.equal(ok.statusCode, 200);
  assert.equal(ok.body.items.length, 1);
});
