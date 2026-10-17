'use strict';
const path = require('node:path');

/** Baca konfigurasi dari environment. Fungsi murni supaya mudah diuji. */
function loadConfig(env = process.env) {
  const baseUrl = String(env.BASE_URL || 'https://kzdesk.kazamasite.biz.id').replace(/\/+$/, '');
  const trust = env.TRUST_PROXY === undefined || env.TRUST_PROXY === '' ? 0 : Number(env.TRUST_PROXY);
  return Object.freeze({
    appName: 'KzDesk Remote',
    developer: 'KazamaDev',
    port: Number(env.PORT) || 3000,
    baseUrl,
    dataDir: path.resolve(env.DATA_DIR || path.join(__dirname, '..', 'data')),
    githubRepo: env.GITHUB_REPO || 'kazamadev/KzDesk-Remote',
    githubToken: env.GITHUB_TOKEN || '',
    releaseTtlMs: 5 * 60_000,
    donateUrl: env.DONATE_URL || 'https://www.kazamasite.biz.id/donate',
    adminToken: env.ADMIN_TOKEN || '',
    trustProxy: Number.isFinite(trust) ? trust : 0,
    deviceOnlineTtlMs: 3 * 60_000,
    deviceForgetMs: 30 * 24 * 3600_000,
    // Push (opsional). FCM_PRIVATE_KEY / APNS_KEY boleh berisi "\n" literal; atau pakai *_FILE.
    fcm: {
      projectId: env.FCM_PROJECT_ID || '',
      clientEmail: env.FCM_CLIENT_EMAIL || '',
      privateKey: env.FCM_PRIVATE_KEY || '',
      serviceAccountFile: env.FCM_SERVICE_ACCOUNT_FILE || '',
    },
    apns: {
      keyId: env.APNS_KEY_ID || '',
      teamId: env.APNS_TEAM_ID || '',
      privateKey: env.APNS_KEY || '',
      keyFile: env.APNS_KEY_FILE || '',
      bundleId: env.APNS_BUNDLE_ID || 'com.kazamatech.kzdesk',
      production: env.APNS_PRODUCTION !== 'false',
    },
  });
}

module.exports = { loadConfig };
