'use strict';
const { ABIS } = require('../lib/releases');

const wrap = (fn) => (req, res, next) => Promise.resolve(fn(req, res)).catch(next);

/** Unduhan selalu diarahkan ke aset rilis GitHub; server tidak menyimpan binary. */
function registerDownloads(app, deps) {
  const { releases, config } = deps;
  const notFound = (res) => res.status(404).type('text/plain').send('Berkas rilis belum tersedia.');

  app.get('/dl/app/:abi', wrap(async (req, res) => {
    if (!ABIS.includes(req.params.abi)) return notFound(res);
    const m = await releases.getManifest();
    const url = m?.app?.abis?.[req.params.abi]?.url;
    return url ? res.redirect(302, url) : notFound(res);
  }));

  app.get('/dl/host', wrap(async (req, res) => {
    const m = await releases.getManifest();
    return m?.host?.url ? res.redirect(302, m.host.url) : notFound(res);
  }));

  app.get('/SHA256SUMS.txt', wrap(async (req, res) => {
    const m = await releases.getManifest();
    return m?.checksums_url ? res.redirect(302, m.checksums_url) : notFound(res);
  }));

  // Bootstrap PowerShell: diisi dari template EJS agar BASE_URL ikut konfigurasi server.
  app.get(['/install.ps1', '/install'], (req, res) => {
    res.type('text/plain; charset=utf-8').render('scripts/install.ps1.ejs', { baseUrl: config.baseUrl });
  });
}

module.exports = { registerDownloads };
