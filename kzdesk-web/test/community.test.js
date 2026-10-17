'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { Community } = require('../src/lib/community');
const { ActiveSessions } = require('../src/lib/sessions');
const { JsonStore } = require('../src/lib/kvstore');

const CLIENT = 'client-id-aaaaaaaa';
const CLIENT2 = 'client-id-bbbbbbbb';
function make() { let t = 5_000_000; const c = new Community({ store: new JsonStore(null), now: () => t }); return { c, advance: (ms) => { t += ms; } }; }

test('submit validates text, blocks links, rate limits', () => {
  const { c, advance } = make();
  assert.equal(c.submit({ client_id: 'short', text: 'hello there' }).status, 400);
  assert.equal(c.submit({ client_id: CLIENT, text: 'hi' }).status, 400);
  assert.equal(c.submit({ client_id: CLIENT, text: 'visit https://spam.example' }).status, 400);
  const ok = c.submit({ client_id: CLIENT, text: 'lelucon pertama', display_name: 'Kaz' });
  assert.equal(ok.status, 201);
  assert.equal(ok.body.joke.author_name, 'Kaz');
  assert.equal(c.submit({ client_id: CLIENT, text: 'lelucon kedua' }).status, 429);
  advance(61_000);
  assert.equal(c.submit({ client_id: CLIENT, text: 'lelucon kedua' }).status, 201);
});

test('reaction toggles and switches, feed shows viewer reaction', () => {
  const { c } = make();
  const id = c.submit({ client_id: CLIENT, text: 'bahan reaksi' }).body.joke.id;
  assert.equal(c.react(id, { client_id: CLIENT2, emoji: '🔥' }).body.joke.reactions['🔥'], 1);
  assert.equal(c.react(id, { client_id: CLIENT2, emoji: '😂' }).body.joke.reactions['🔥'], 0);
  const feed = c.feed(CLIENT2).body.items[0];
  assert.equal(feed.viewer_reaction, '😂');
  assert.equal(c.react(id, { client_id: CLIENT2, emoji: '😂' }).body.joke.viewer_reaction, null);
  assert.equal(c.react(id, { client_id: CLIENT2, emoji: '🙂' }).status, 400);
  assert.equal(c.react('not-a-uuid', { client_id: CLIENT2, emoji: '🔥' }).status, 400);
});

test('three distinct reports hide a post; same client counts once', () => {
  const { c } = make();
  const id = c.submit({ client_id: CLIENT, text: 'konten buruk' }).body.joke.id;
  assert.equal(c.report(id, { client_id: CLIENT2 }).body.status, 'reported');
  assert.equal(c.report(id, { client_id: CLIENT2 }).body.status, 'already_reported');
  c.report(id, { client_id: 'client-id-cccccccc' });
  assert.equal(c.report(id, { client_id: 'client-id-dddddddd' }).body.hidden, true);
  assert.equal(c.feed(CLIENT).body.items.length, 0);
});

test('comments are stored, rate limited and counted', () => {
  const { c, advance } = make();
  const id = c.submit({ client_id: CLIENT, text: 'ada komentar' }).body.joke.id;
  const r = c.comment(id, { client_id: CLIENT2, text: 'haha' });
  assert.equal(r.status, 201);
  assert.equal(r.body.comments_total, 1);
  assert.equal(c.comment(id, { client_id: CLIENT2, text: 'lagi' }).status, 429);
  advance(16_000);
  assert.equal(c.comment(id, { client_id: CLIENT2, text: 'lagi' }).body.comments_total, 2);
  assert.equal(c.feed(CLIENT).body.items[0].comments.length, 2);
});

test('display name roundtrip via /me', () => {
  const { c } = make();
  assert.equal(c.me(CLIENT).body.display_name, null);
  assert.equal(c.setMe({ client_id: CLIENT, display_name: 'https://x.y' }).status, 400);
  assert.equal(c.setMe({ client_id: CLIENT, display_name: 'Kazama' }).status, 200);
  assert.equal(c.me(CLIENT).body.display_name, 'Kazama');
});

test('active sessions: heartbeat, end, expiry', () => {
  let t = 1000; const s = new ActiveSessions({ now: () => t });
  const id = '123e4567-e89b-42d3-a456-426614174000';
  assert.equal(s.report({ session_id: 'bad', action: 'heartbeat' }).status, 400);
  assert.equal(s.report({ session_id: id, action: 'heartbeat' }).body.active_session_count, 1);
  assert.equal(s.report({ session_id: id, action: 'heartbeat' }).body.active_session_count, 1);
  t += 91_000;
  assert.equal(s.count(), 0);
  s.report({ session_id: id, action: 'heartbeat' });
  assert.equal(s.report({ session_id: id, action: 'end' }).body.active_session_count, 0);
});
