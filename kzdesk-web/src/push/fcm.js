'use strict';
const crypto = require('node:crypto');

const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

const b64url = (buf) => Buffer.from(buf).toString('base64url');

/** Pengirim FCM HTTP v1 dengan akun layanan (tanpa SDK). Mengirim ke token Android. */
class FcmSender {
  /**
   * @param {{projectId:string, clientEmail:string, privateKey:string, fetchImpl?:Function, now?:()=>number}} o
   */
  constructor({ projectId, clientEmail, privateKey, fetchImpl = globalThis.fetch, now = () => Date.now() }) {
    this.projectId = projectId;
    this.clientEmail = clientEmail;
    this.privateKey = String(privateKey || '').replace(/\\n/g, '\n');
    this.fetch = fetchImpl;
    this.now = now;
    this.cached = null; // {token, exp}
  }

  static fromConfig(c, fetchImpl) {
    if (!c.projectId || !c.clientEmail || !c.privateKey) return null;
    return new FcmSender({ ...c, fetchImpl });
  }

  /** JWT RS256 untuk menukar akses OAuth2. */
  assertion() {
    const iat = Math.floor(this.now() / 1000);
    const head = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
    const body = b64url(JSON.stringify({ iss: this.clientEmail, scope: SCOPE, aud: TOKEN_URL, iat, exp: iat + 3600 }));
    const sig = crypto.sign('RSA-SHA256', Buffer.from(`${head}.${body}`), this.privateKey);
    return `${head}.${body}.${b64url(sig)}`;
  }

  async accessToken() {
    if (this.cached && this.cached.exp - 60_000 > this.now()) return this.cached.token;
    const res = await this.fetch(TOKEN_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: this.assertion() }),
      signal: AbortSignal.timeout(10_000),
    });
    if (!res.ok) throw new Error(`FCM OAuth HTTP ${res.status}`);
    const j = await res.json();
    this.cached = { token: j.access_token, exp: this.now() + (Number(j.expires_in) || 3600) * 1000 };
    return this.cached.token;
  }

  /** @returns {Promise<{ok:boolean, invalid?:boolean, error?:string}>} */
  async send(token, msg) {
    const access = await this.accessToken();
    const data = Object.fromEntries(Object.entries({ kind: msg.kind, ...(msg.data || {}) }).map(([k, v]) => [k, String(v)]));
    const res = await this.fetch(`https://fcm.googleapis.com/v1/projects/${this.projectId}/messages:send`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${access}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: {
          token,
          notification: { title: msg.title, body: msg.body },
          data,
          android: { priority: 'HIGH', notification: { channel_id: msg.channel || 'kz_announcements' } },
        },
      }),
      signal: AbortSignal.timeout(10_000),
    });
    if (res.ok) return { ok: true };
    let detail = '';
    try { detail = JSON.stringify(await res.json()); } catch { /* abaikan */ }
    const invalid = res.status === 404 || (res.status === 400 && /INVALID_ARGUMENT|UNREGISTERED/.test(detail)) || /UNREGISTERED/.test(detail);
    return { ok: false, invalid, error: `FCM HTTP ${res.status}` };
  }
}

module.exports = { FcmSender };
