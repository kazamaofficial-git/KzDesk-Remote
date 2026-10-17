'use strict';
const { sha256Hex, UUID_RE, uuid } = require('./util');

const REACTIONS = ['😂', '🔥', '👍', '💀', '❤️'];
const CLIENT_ID_RE = /^[A-Za-z0-9_-]{16,64}$/;
const L = {
  jokeMax: 280, commentMax: 140, nameMax: 24,
  feedSize: 30, maxItems: 200, commentFeed: 3, commentPerJoke: 60,
  postIntervalMs: 60_000, commentIntervalMs: 15_000, reportThreshold: 3,
};
const URL_RE = /(?:https?:\/\/|www\.)/i;
const CTRL_RE = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g;

const cleanName = (v) => {
  if (typeof v !== 'string') return null;
  const c = v.replace(/[\u0000-\u001F\u007F]/g, '').replace(/\s+/g, ' ').trim();
  const n = [...c].length;
  return n < 1 || n > L.nameMax || URL_RE.test(c) ? null : c;
};
const cleanBody = (v) => v.replace(/\r\n?/g, '\n').replace(CTRL_RE, '').trim();

/** Komentar publik. */
const commentView = (c) => ({ id: c.id, name: c.name || null, text: c.text, created_at: c.created_at });

const jokeView = (j, viewer = null, comments = []) => ({
  id: j.id, text: j.text, created_at: j.created_at, author_name: j.author_name || null,
  reactions: Object.fromEntries(REACTIONS.map((e) => [e, Math.max(0, Number(j.reactions?.[e]) || 0)])),
  viewer_reaction: viewer, comments_total: Math.max(0, Number(j.comment_count) || 0), comments,
});

class Community {
  constructor({ store, now = () => Date.now() }) {
    this.store = store;
    this.now = now;
  }

  #db() {
    let db = this.store.get('community');
    if (!db) {
      db = { jokes: [], reactions: {}, reports: {}, comments: {}, names: {}, rates: {} };
      this.store.set('community', db);
    }
    return db;
  }

  #validClient(id) { return CLIENT_ID_RE.test(String(id || '')); }
  #find(db, jokeId) { return db.jokes.find((j) => j.id === jokeId && !j.hidden); }

  feed(clientId) {
    if (clientId && !this.#validClient(clientId)) return { status: 400, body: { status: 'invalid_client_id' } };
    const db = this.#db();
    const h = clientId ? sha256Hex(clientId) : null;
    const items = [];
    for (const j of [...db.jokes].reverse()) {
      if (j.hidden) continue;
      const mine = h ? db.reactions[j.id]?.[h] || null : null;
      const comments = (db.comments[j.id] || []).slice(-L.commentFeed).reverse().map(commentView);
      items.push(jokeView(j, mine, comments));
      if (items.length >= L.feedSize) break;
    }
    return { status: 200, body: { status: 'ok', items } };
  }

  submit(body) {
    if (!this.#validClient(body?.client_id)) return { status: 400, body: { status: 'invalid_client_id' } };
    if (typeof body.text !== 'string') return { status: 400, body: { status: 'invalid_text' } };
    const text = cleanBody(body.text);
    const n = [...text].length;
    if (n < 3 || n > L.jokeMax || URL_RE.test(text)) {
      return { status: 400, body: { status: 'invalid_text', max_chars: L.jokeMax } };
    }
    const db = this.#db();
    const h = sha256Hex(body.client_id);
    const t = this.now();
    const last = db.rates[`joke:${h}`] || 0;
    if (t - last < L.postIntervalMs) {
      return { status: 429, body: { status: 'rate_limited', retry_after_seconds: Math.ceil((L.postIntervalMs - (t - last)) / 1000) } };
    }
    const name = cleanName(body.display_name);
    if (name) db.names[h] = name;
    const joke = {
      id: uuid(), text, created_at: t, author_name: name || db.names[h] || null,
      reactions: Object.fromEntries(REACTIONS.map((e) => [e, 0])), comment_count: 0, report_count: 0, hidden: false,
    };
    db.jokes.push(joke);
    db.rates[`joke:${h}`] = t;
    while (db.jokes.length > L.maxItems) {
      const old = db.jokes.shift();
      delete db.reactions[old.id]; delete db.reports[old.id]; delete db.comments[old.id];
    }
    this.store.touch();
    return { status: 201, body: { status: 'created', joke: jokeView(joke) } };
  }

  react(jokeId, body) {
    if (!UUID_RE.test(String(jokeId))) return { status: 400, body: { status: 'invalid_joke_id' } };
    if (!this.#validClient(body?.client_id) || !REACTIONS.includes(body?.emoji)) {
      return { status: 400, body: { status: 'invalid_reaction' } };
    }
    const db = this.#db();
    const joke = this.#find(db, jokeId);
    if (!joke) return { status: 404, body: { status: 'not_found' } };
    const h = sha256Hex(body.client_id);
    const mine = (db.reactions[jokeId] ||= {});
    const prev = mine[h];
    const dec = (e) => { joke.reactions[e] = Math.max(0, (Number(joke.reactions[e]) || 0) - 1); };
    if (prev === body.emoji) {
      dec(body.emoji); delete mine[h];
      this.store.touch();
      return { status: 200, body: { status: 'ok', joke: jokeView(joke, null) } };
    }
    if (prev && REACTIONS.includes(prev)) dec(prev);
    joke.reactions[body.emoji] = (Number(joke.reactions[body.emoji]) || 0) + 1;
    mine[h] = body.emoji;
    this.store.touch();
    return { status: 200, body: { status: 'ok', joke: jokeView(joke, body.emoji) } };
  }

  comment(jokeId, body) {
    if (!UUID_RE.test(String(jokeId))) return { status: 400, body: { status: 'invalid_joke_id' } };
    if (!this.#validClient(body?.client_id)) return { status: 400, body: { status: 'invalid_client_id' } };
    if (typeof body.text !== 'string') return { status: 400, body: { status: 'invalid_text' } };
    const text = cleanBody(body.text);
    const n = [...text].length;
    if (n < 1 || n > L.commentMax || URL_RE.test(text)) {
      return { status: 400, body: { status: 'invalid_text', max_chars: L.commentMax } };
    }
    const db = this.#db();
    const h = sha256Hex(body.client_id);
    const t = this.now();
    const last = db.rates[`comment:${h}`] || 0;
    if (t - last < L.commentIntervalMs) {
      return { status: 429, body: { status: 'rate_limited', retry_after_seconds: Math.ceil((L.commentIntervalMs - (t - last)) / 1000) } };
    }
    const joke = this.#find(db, jokeId);
    if (!joke) return { status: 404, body: { status: 'not_found' } };
    const name = cleanName(body.display_name);
    if (name) db.names[h] = name;
    const c = { id: uuid(), joke_id: jokeId, name: name || db.names[h] || null, text, created_at: t };
    const list = (db.comments[jokeId] ||= []);
    list.push(c);
    while (list.length > L.commentPerJoke) list.shift();
    joke.comment_count = list.length;
    db.rates[`comment:${h}`] = t;
    this.store.touch();
    return { status: 201, body: { status: 'ok', comment: commentView(c), comments_total: joke.comment_count } };
  }

  report(jokeId, body) {
    if (!UUID_RE.test(String(jokeId))) return { status: 400, body: { status: 'invalid_joke_id' } };
    if (!this.#validClient(body?.client_id)) return { status: 400, body: { status: 'invalid_client_id' } };
    const db = this.#db();
    const joke = db.jokes.find((j) => j.id === jokeId);
    if (!joke) return { status: 404, body: { status: 'not_found', hidden: false } };
    if (joke.hidden) return { status: 404, body: { status: 'not_found', hidden: true } };
    const h = sha256Hex(body.client_id);
    const reports = (db.reports[jokeId] ||= {});
    if (reports[h]) return { status: 200, body: { status: 'already_reported', hidden: joke.hidden } };
    reports[h] = this.now();
    joke.report_count = (Number(joke.report_count) || 0) + 1;
    if (joke.report_count >= L.reportThreshold) joke.hidden = true;
    this.store.touch();
    return { status: 200, body: { status: 'reported', hidden: joke.hidden } };
  }

  me(clientId) {
    if (!this.#validClient(clientId)) return { status: 400, body: { status: 'invalid_client_id' } };
    return { status: 200, body: { status: 'ok', display_name: this.#db().names[sha256Hex(clientId)] || null } };
  }

  setMe(body) {
    if (!this.#validClient(body?.client_id)) return { status: 400, body: { status: 'invalid_client_id' } };
    const name = cleanName(body.display_name);
    if (!name) return { status: 400, body: { status: 'invalid_display_name', max_chars: L.nameMax } };
    this.#db().names[sha256Hex(body.client_id)] = name;
    this.store.touch();
    return { status: 200, body: { status: 'ok', display_name: name } };
  }
}

module.exports = { Community, REACTIONS, CLIENT_ID_RE };
