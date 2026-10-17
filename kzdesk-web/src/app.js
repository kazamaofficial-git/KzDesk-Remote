'use strict';
const path = require('node:path');
const express = require('express');
const { loadConfig } = require('./config');
const { JsonStore } = require('./lib/kvstore');
const { ReleaseService } = require('./lib/releases');
const { DeviceDirectory } = require('./lib/devices');
const { ActiveSessions } = require('./lib/sessions');
const { Community } = require('./lib/community');
const { pickLang, translator, LANGS } = require('./lib/i18n');
const { CrashReports } = require('./lib/crash');
const { PushService } = require('./push/service');
const { buildSenders } = require('./push/senders');
const { registerApi } = require('./routes/api');
const { registerPages } = require('./routes/pages');
const { registerDownloads } = require('./routes/downloads');

/** Rakit aplikasi Express. Semua dependensi bisa disuntik (dipakai tes). */
function createApp({ config = loadConfig(), store, releases } = {}) {
  const dataStore = store || new JsonStore(path.join(config.dataDir, 'store.json'));
  const deps = {
    config,
    store: dataStore,
    releases: releases || new ReleaseService({ config, store: dataStore }),
    devices: new DeviceDirectory({ config, store: dataStore }),
    sessions: new ActiveSessions(),
    community: new Community({ store: dataStore }),
  };
  deps.crash = new CrashReports({ store: dataStore });
  deps.push = new PushService({ config, store: dataStore, devices: deps.devices, senders: buildSenders(config) });
  deps.releases.onManifest = (m) => deps.push.onRelease(m.version).catch((e) => console.warn('[push] onRelease:', e.message));

  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', config.trustProxy);
  app.set('view engine', 'ejs');
  app.set('views', path.join(__dirname, '..', 'views'));

  // Header keamanan dasar (CSP ketat: tanpa skrip inline, semua aset dari origin sendiri).
  app.use((req, res, next) => {
    res.set({
      'X-Content-Type-Options': 'nosniff',
      'X-Frame-Options': 'DENY',
      'Referrer-Policy': 'strict-origin-when-cross-origin',
      'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
      'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
    });
    next();
  });

  // CORS terbuka hanya untuk API (klien Android + host PowerShell tidak butuh, web lain boleh baca).
  app.use('/api', (req, res, next) => {
    res.set({ 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'content-type, authorization, x-kzdesk-client-id', 'Access-Control-Allow-Methods': 'GET, POST, PUT, OPTIONS', 'Cache-Control': 'no-store' });
    if (req.method === 'OPTIONS') return res.sendStatus(204);
    return next();
  });
  app.use('/api', express.json({ limit: '4kb' }));

  app.use(express.static(path.join(__dirname, '..', 'public'), { maxAge: '7d', index: false }));

  app.use((req, res, next) => {
    const lang = pickLang(req);
    if (req.query.lang && LANGS.includes(lang)) res.cookie('lang', lang, { maxAge: 365 * 86400_000, sameSite: 'lax', path: '/' });
    res.locals.lang = lang;
    res.locals.t = translator(lang);
    res.locals.config = config;
    res.locals.year = new Date().getFullYear();
    res.locals.path = req.path;
    next();
  });

  registerApi(app, deps);
  registerDownloads(app, deps);
  registerPages(app, deps);

  app.use((req, res) => res.status(404).render('404', { page: 'none' }));
  // eslint-disable-next-line no-unused-vars
  app.use((err, req, res, next) => {
    const isApi = req.path.startsWith('/api/');
    if (err?.type === 'entity.parse.failed') return res.status(400).json({ status: 'invalid_json' });
    if (err?.type === 'entity.too.large') return res.status(413).json({ status: 'payload_too_large' });
    console.error('[error]', err);
    if (isApi) return res.status(500).json({ status: 'error' });
    return res.status(500).type('text/plain').send('Terjadi kesalahan pada server.');
  });

  return { app, deps };
}

module.exports = { createApp };
