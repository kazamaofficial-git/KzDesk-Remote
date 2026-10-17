'use strict';
const { safeEqual } = require('../lib/util');

const wrap = (fn) => (req, res, next) => Promise.resolve(fn(req, res)).catch(next);
const send = (res, r) => res.status(r.status).json(r.body);

/**
 * Semua endpoint JSON. Dipasang pada `app` Express (atau objek tiruan di tes).
 * @param {{releases, devices, sessions, community, config, store}} deps
 */
function registerApi(app, deps) {
  const { releases, devices, sessions, community, config, store, push, crash } = deps;

  app.get('/api/health', (req, res) => res.json({ status: 'ok', time: new Date().toISOString() }));

  app.get('/api/status', wrap(async (req, res) => {
    const m = await releases.getManifest();
    if (!m) return res.status(503).json({ status: 'degraded', error: 'release metadata unavailable' });
    res.json({
      app: config.appName, vendor: config.developer,
      version: m.version, qaBuild: '', qaVersionCode: m.version_code,
      kzdeskHostBuild: m.host ? m.version : '',
      status: 'stable', launchIso: m.published_at || '', launchWib: '',
      storePortal: '', releaseUrl: m.release_url, devicesOnline: devices.count(),
    });
  }));

  app.get('/api/releases', wrap(async (req, res) => {
    const m = await releases.getManifest();
    if (!m) return res.status(503).json({ status: 'unavailable' });
    res.json(m);
  }));

  // ---- Auto-update --------------------------------------------------------
  app.get('/api/update/app', wrap(async (req, res) => {
    const out = await releases.appUpdate({
      versionCode: req.query.versionCode,
      abis: req.query.abi ?? req.query.abis,
    });
    res.status(out.status === 'ok' ? 200 : 503).json(out);
  }));

  app.get('/api/update/host', wrap(async (req, res) => {
    const out = await releases.hostUpdate({ version: String(req.query.version || '') });
    res.status(out.status === 'ok' ? 200 : 503).json(out);
  }));

  // ---- Direktori Device ID + Password -------------------------------------
  app.post('/api/device/register', (req, res) => send(res, devices.register(req.body || {}, req.ip)));
  app.post('/api/device/heartbeat', (req, res) => send(res, devices.heartbeat(req.body || {}, req.ip)));
  app.post('/api/device/unregister', (req, res) => send(res, devices.unregister(req.body || {})));
  app.post('/api/device/resolve', wrap(async (req, res) => send(res, await devices.resolve(req.body || {}, req.ip))));

  // ---- Push notification ---------------------------------------------------
  app.post('/api/push/register', (req, res) => send(res, push.register(req.body || {}, req.ip)));
  app.post('/api/push/unregister', (req, res) => send(res, push.unregister(req.body || {})));
  app.post('/api/push/subscribe-device', wrap(async (req, res) => send(res, await push.subscribeDevice(req.body || {}, req.ip))));
  app.post('/api/push/unsubscribe-device', (req, res) => send(res, push.unsubscribeDevice(req.body || {})));
  app.post('/api/admin/push', wrap(async (req, res) => {
    const header = String(req.get('authorization') || '');
    const token = header.startsWith('Bearer ') ? header.slice(7) : '';
    if (!config.adminToken || !safeEqual(token, config.adminToken)) return res.status(403).json({ status: 'forbidden' });
    return send(res, await push.broadcast(req.body || {}));
  }));

  // ---- Laporan crash (opt-in dari aplikasi) --------------------------------
  app.post('/api/crash', (req, res) => send(res, crash.submit(req.body || {}, req.ip)));
  app.get('/api/admin/crashes', (req, res) => {
    const header = String(req.get('authorization') || '');
    const token = header.startsWith('Bearer ') ? header.slice(7) : '';
    if (!config.adminToken || !safeEqual(token, config.adminToken)) return res.status(403).json({ status: 'forbidden' });
    return res.json({ status: 'ok', items: crash.list() });
  });

  // ---- Sesi aktif anonim ---------------------------------------------------
  app.get('/api/active-sessions', (req, res) => res.json({
    status: 'ok',
    active_session_count: sessions.count(),
    scope: 'client-reported-anonymous-rdp-sessions',
    checked_at: new Date().toISOString(),
  }));
  app.post('/api/session', (req, res) => send(res, sessions.report(req.body || {})));

  // ---- Komunitas -----------------------------------------------------------
  app.get('/api/jokes', (req, res) => send(res, community.feed(req.get('x-kzdesk-client-id') || '')));
  app.post('/api/jokes', (req, res) => send(res, community.submit(req.body || {})));
  app.get('/api/jokes/me', (req, res) => send(res, community.me(req.get('x-kzdesk-client-id') || '')));
  app.put('/api/jokes/me', (req, res) => send(res, community.setMe(req.body || {})));
  app.post('/api/jokes/:id/reaction', (req, res) => send(res, community.react(req.params.id, req.body || {})));
  app.post('/api/jokes/:id/report', (req, res) => send(res, community.report(req.params.id, req.body || {})));
  app.post('/api/jokes/:id/comment', (req, res) => send(res, community.comment(req.params.id, req.body || {})));

  // ---- Admin: metadata rilis cadangan bila GitHub tidak terjangkau --------
  app.put('/api/admin/release-state', (req, res) => {
    const header = String(req.get('authorization') || '');
    const token = header.startsWith('Bearer ') ? header.slice(7) : '';
    if (!config.adminToken || !safeEqual(token, config.adminToken)) return res.status(403).json({ status: 'forbidden' });
    const b = req.body || {};
    if (typeof b.version !== 'string' || !Number.isInteger(b.version_code)) {
      return res.status(400).json({ status: 'invalid_release_state' });
    }
    store.set('release_state_override', b);
    res.json({ status: 'ok' });
  });

  app.all('/api/*', (req, res) => res.status(404).json({ status: 'not_found' }));
}

module.exports = { registerApi };
