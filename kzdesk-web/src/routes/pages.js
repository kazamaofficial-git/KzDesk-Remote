'use strict';
const { ABIS } = require('../lib/releases');

const wrap = (fn) => (req, res, next) => Promise.resolve(fn(req, res)).catch(next);

function registerPages(app, deps) {
  const { releases, devices, sessions, config } = deps;
  const manifest = async () => { try { return await releases.getManifest(); } catch { return null; } };

  app.get('/', wrap(async (req, res) => res.render('index', { page: 'home', release: await manifest() })));
  app.get('/host', wrap(async (req, res) => res.render('host', { page: 'host', release: await manifest() })));
  app.get('/download', wrap(async (req, res) => res.render('download', { page: 'download', release: await manifest(), abis: ABIS })));
  app.get('/status', wrap(async (req, res) => res.render('status', {
    page: 'status', release: await manifest(), devicesOnline: devices.count(), activeSessions: sessions.count(),
  })));
  app.get('/donate', (req, res) => res.redirect(302, config.donateUrl));
  app.get('/privacy', (req, res) => res.render('legal', { page: 'legal', docKey: 'privacy' }));
  app.get('/terms', (req, res) => res.render('legal', { page: 'legal', docKey: 'terms' }));
  app.get('/licenses', (req, res) => res.render('legal', { page: 'legal', docKey: 'licenses' }));
  app.get('/robots.txt', (req, res) => res.type('text/plain').send(`User-agent: *\nAllow: /\nDisallow: /api/\nSitemap: ${config.baseUrl}/sitemap.xml\n`));
  app.get('/sitemap.xml', (req, res) => {
    const urls = ['/', '/host', '/download', '/status', '/privacy', '/terms'];
    res.type('application/xml').send(
      `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls.map((u) => `  <url><loc>${config.baseUrl}${u}</loc></url>`).join('\n')}\n</urlset>\n`,
    );
  });
}

module.exports = { registerPages };
