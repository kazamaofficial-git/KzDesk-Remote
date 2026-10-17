'use strict';
/**
 * Alat bantu tes. Sandbox pengembangan tidak punya `express`/`ejs` (tanpa jaringan),
 * jadi di sini ada:
 *  - FakeApp: merekam route & memanggil handler dengan req/res tiruan.
 *  - renderEjs: renderer EJS sangat kecil (<% %>, <%= %>, <%- %>, <%# %>, include)
 *    HANYA untuk smoke-test template. Produksi memakai paket `ejs` asli.
 */
const fs = require('node:fs');
const path = require('node:path');

const VIEWS = path.join(__dirname, '..', 'views');
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function compile(src) {
  let code = "let __o='';\nwith(locals){\n";
  const re = /<%([=\-#]?)([\s\S]*?)%>/g;
  let last = 0;
  let m;
  while ((m = re.exec(src))) {
    code += `__o+=${JSON.stringify(src.slice(last, m.index))};\n`;
    const [, kind, body] = m;
    if (kind === '=') code += `__o+=__esc(${body});\n`;
    else if (kind === '-') code += `__o+=(${body});\n`;
    else if (kind === '#') { /* komentar */ } else code += `${body}\n`;
    last = re.lastIndex;
  }
  code += `__o+=${JSON.stringify(src.slice(last))};\n}\nreturn __o;`;
  return new Function('locals', 'include', '__esc', code); // eslint-disable-line no-new-func
}

function renderEjs(name, locals = {}) {
  const file = path.join(VIEWS, name.endsWith('.ejs') ? name : `${name}.ejs`);
  const fn = compile(fs.readFileSync(file, 'utf8'));
  const include = (inc, data) => renderEjs(inc, { ...locals, ...(data || {}) });
  return fn({ ...locals, locals }, include, esc);
}

class FakeApp {
  constructor() { this.routes = []; }
  #add(method, p, handlers) { for (const pat of [].concat(p)) this.routes.push({ method, pat, handlers }); }
  get(p, ...h) { this.#add('GET', p, h); }
  post(p, ...h) { this.#add('POST', p, h); }
  put(p, ...h) { this.#add('PUT', p, h); }
  all(p, ...h) { this.#add('*', p, h); }

  #match(pat, url) {
    if (pat.endsWith('/*')) return url.startsWith(pat.slice(0, -1)) ? {} : null;
    const names = [];
    const rx = new RegExp(`^${pat.replace(/:([A-Za-z]+)/g, (_, n) => { names.push(n); return '([^/]+)'; })}$`);
    const m = rx.exec(url);
    return m ? Object.fromEntries(names.map((n, i) => [n, m[i + 1]])) : null;
  }

  async call(method, url, { body, query = {}, headers = {}, ip = '203.0.113.7' } = {}) {
    const lower = Object.fromEntries(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v]));
    for (const r of this.routes) {
      if (r.method !== '*' && r.method !== method) continue;
      const params = this.#match(r.pat, url);
      if (!params) continue;
      const res = new FakeRes();
      const req = { method, url, path: url, ip, query, params, body, headers: lower, get: (h) => lower[String(h).toLowerCase()] };
      await new Promise((resolve, reject) => {
        res.onDone = resolve;
        Promise.resolve(r.handlers[r.handlers.length - 1](req, res, reject)).catch(reject);
      });
      return res;
    }
    return null;
  }
}

class FakeRes {
  constructor() { this.statusCode = 200; this.headers = {}; this.body = undefined; this.location = null; this.view = null; this.locals = null; }
  status(n) { this.statusCode = n; return this; }
  set(k, v) { if (typeof k === 'object') Object.assign(this.headers, k); else this.headers[k] = v; return this; }
  type(t) { this.headers['content-type'] = t; return this; }
  json(b) { this.body = b; this.onDone(); return this; }
  send(b) { this.body = b; this.onDone(); return this; }
  sendStatus(n) { this.statusCode = n; this.onDone(); return this; }
  redirect(a, b) { if (typeof a === 'number') { this.statusCode = a; this.location = b; } else { this.statusCode = 302; this.location = a; } this.onDone(); return this; }
  render(view, locals) { this.view = view; this.locals = locals; this.onDone(); return this; }
}

module.exports = { FakeApp, renderEjs };
