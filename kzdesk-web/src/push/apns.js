'use strict';
const crypto = require('node:crypto');
const http2 = require('node:http2');

const b64url = (buf) => Buffer.from(buf).toString('base64url');

/** Pengirim APNs (token .p8, HTTP/2) tanpa dependensi. Mengirim ke token iOS. */
class ApnsSender {
  /**
   * @param {{keyId:string, teamId:string, privateKey:string, bundleId:string, production?:boolean,
   *          connect?:Function, now?:()=>number}} o
   */
  constructor({ keyId, teamId, privateKey, bundleId, production = true, connect = http2.connect, now = () => Date.now() }) {
    this.keyId = keyId;
    this.teamId = teamId;
    this.privateKey = String(privateKey || '').replace(/\\n/g, '\n');
    this.bundleId = bundleId;
    this.origin = production ? 'https://api.push.apple.com' : 'https://api.sandbox.push.apple.com';
    this.connectFn = connect;
    this.now = now;
    this.session = null;
    this.jwt = null; // {token, at}
  }

  static fromConfig(c, connect) {
    if (!c.keyId || !c.teamId || !c.privateKey || !c.bundleId) return null;
    return new ApnsSender({ ...c, connect });
  }

  /** JWT ES256; Apple meminta token baru paling cepat tiap 20 menit dan paling lama 60 menit. */
  providerToken() {
    if (this.jwt && this.now() - this.jwt.at < 50 * 60_000) return this.jwt.token;
    const head = b64url(JSON.stringify({ alg: 'ES256', kid: this.keyId }));
    const body = b64url(JSON.stringify({ iss: this.teamId, iat: Math.floor(this.now() / 1000) }));
    const sig = crypto.sign('sha256', Buffer.from(`${head}.${body}`), { key: this.privateKey, dsaEncoding: 'ieee-p1363' });
    this.jwt = { token: `${head}.${body}.${b64url(sig)}`, at: this.now() };
    return this.jwt.token;
  }

  #client() {
    if (this.session && !this.session.closed && !this.session.destroyed) return this.session;
    const s = this.connectFn(this.origin);
    s.on('error', () => { if (this.session === s) this.session = null; });
    s.on('close', () => { if (this.session === s) this.session = null; });
    this.session = s;
    return s;
  }

  close() { try { this.session?.close(); } catch { /* sudah tertutup */ } this.session = null; }

  /** @returns {Promise<{ok:boolean, invalid?:boolean, error?:string}>} */
  send(token, msg) {
    return new Promise((resolve) => {
      let req;
      try {
        req = this.#client().request({
          ':method': 'POST',
          ':path': `/3/device/${token}`,
          authorization: `bearer ${this.providerToken()}`,
          'apns-topic': this.bundleId,
          'apns-push-type': 'alert',
          'apns-priority': '10',
          'content-type': 'application/json',
        });
      } catch (e) { return resolve({ ok: false, error: e.message }); }
      let status = 0;
      let body = '';
      const timer = setTimeout(() => { try { req.close(); } catch { /* ok */ } resolve({ ok: false, error: 'APNs timeout' }); }, 10_000);
      timer.unref?.();
      req.setEncoding('utf8');
      req.on('response', (h) => { status = Number(h[':status']) || 0; });
      req.on('data', (c) => { body += c; });
      req.on('error', (e) => { clearTimeout(timer); resolve({ ok: false, error: e.message }); });
      req.on('end', () => {
        clearTimeout(timer);
        if (status === 200) return resolve({ ok: true });
        let reason = '';
        try { reason = JSON.parse(body).reason || ''; } catch { /* abaikan */ }
        const invalid = status === 410 || reason === 'BadDeviceToken' || reason === 'Unregistered' || reason === 'DeviceTokenNotForTopic';
        resolve({ ok: false, invalid, error: `APNs ${status} ${reason}`.trim() });
      });
      req.end(JSON.stringify({
        aps: { alert: { title: msg.title, body: msg.body }, sound: 'default' },
        kind: msg.kind,
        ...(msg.data || {}),
      }));
    });
  }
}

module.exports = { ApnsSender };
