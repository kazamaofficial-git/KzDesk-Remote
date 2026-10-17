'use strict';
const crypto = require('node:crypto');

const sha256Hex = (value) => crypto.createHash('sha256').update(value).digest('hex');

/** Perbandingan waktu-konstan untuk string; false bila panjang berbeda. */
function safeEqual(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

const randomToken = (bytes = 32) => crypto.randomBytes(bytes).toString('base64url');

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const uuid = () => crypto.randomUUID();

module.exports = { sha256Hex, safeEqual, randomToken, UUID_RE, uuid };
