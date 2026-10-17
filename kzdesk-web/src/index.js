'use strict';
const { loadConfig } = require('./config');
const { createApp } = require('./app');

const config = loadConfig();
const { app, deps } = createApp({ config });
const server = app.listen(config.port, () => {
  console.log(`KzDesk Remote web listening on :${config.port} (${config.baseUrl})`);
});

// Sapu status PC tiap menit (hanya bekerja bila ada pelanggan push).
const sweeper = setInterval(() => {
  deps.push.sweepPresence().catch((e) => console.warn('[push] sweep:', e.message));
}, 60_000);
sweeper.unref();
if (!deps.push.enabled()) console.log('Push nonaktif (isi FCM_* dan/atau APNS_* di .env untuk mengaktifkan).');

function shutdown() {
  clearInterval(sweeper);
  deps.push.senders.ios?.close?.();
  console.log('Menutup server…');
  deps.store.flush();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 5000).unref();
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
