'use strict';

const DICT = {
  id: {
    nav_features: 'Fitur', nav_how: 'Cara kerja', nav_host: 'Host Windows', nav_download: 'Unduh', nav_donate: 'Donasi',
    cta_download: 'Unduh untuk Android', cta_host: 'Pasang Host di PC', badge: 'Remote Desktop • RDP • Auto-update',
    hero_title: 'Kendalikan PC Windows-mu dari mana saja.',
    hero_sub: 'KzDesk Remote adalah aplikasi remote desktop untuk Android, iOS, dan desktop. Sambung lewat IP + akun RDP, atau cukup ID Device + password. Host cukup dipasang sekali, lalu memperbarui dirinya sendiri.',
    stat_modes: '2 mode koneksi', stat_update: 'Auto-update', stat_input: 'Keyboard & mouse fisik', stat_tpl: 'Template JSON',
    f_title: 'Semua yang kamu butuhkan untuk remote PC',
    f1_t: 'Dua cara terhubung', f1_d: 'RDP langsung via IP, username & password. Atau ID Device + password tanpa perlu hafal IP.',
    f2_t: 'Keyboard & mouse fisik', f2_d: 'Pakai keyboard/mouse Bluetooth atau USB. Kontrol layar otomatis menyingkir saat perangkat fisik dipakai.',
    f3_t: 'Template Controller (JSON)', f3_d: 'Susun tombol & joystick, simpan sebagai template, lalu impor/ekspor via file JSON atau clipboard.',
    f4_t: 'Joystick di controller', f4_d: 'Joystick analog untuk WASD, panah, kursor, atau kamera. Atur sensitivitas dan deadzone per joystick.',
    f5_t: 'Auto-update di mana-mana', f5_d: 'Aplikasi Android mengecek rilis baru, mengunduh, dan memverifikasi SHA-256. Host Windows ikut diperbarui otomatis.',
    f6_t: 'Install sekali, beres', f6_d: 'Satu installer memasang agen host, aturan firewall, akun KzDesk, dan updater. Tanpa konfigurasi manual.',
    how_title: 'Mulai dalam tiga langkah',
    s1_t: 'Pasang Host', s1_d: 'Jalankan installer di PC Windows. ID Device dan password tampil di akhir instalasi.',
    s2_t: 'Pasang aplikasi', s2_d: 'Unduh APK KzDesk Remote di Android, lalu tambahkan perangkat.',
    s3_t: 'Sambungkan', s3_d: 'Masukkan ID + password, atau IP + username + password. Selesai.',
    host_title: 'KzDesk Host untuk Windows', host_sub: 'Satu installer. Memasang semuanya, lalu mendeteksi pembaruan sendiri.',
    host_install: 'Unduh installer (.exe)', host_ps: 'Atau jalankan satu baris di PowerShell (Administrator):',
    host_includes: 'Yang dipasang', host_i1: 'Agen host KzDesk (autostart)', host_i2: 'Akun Windows khusus “KzDesk” untuk mode ID Device',
    host_i3: 'Aturan firewall & Remote Desktop', host_i4: 'Updater otomatis (cek berkala, verifikasi SHA-256)',
    dl_title: 'Unduh KzDesk Remote', dl_latest: 'Versi terbaru', dl_unavail: 'Informasi rilis belum tersedia.',
    dl_abi: 'Pilih sesuai arsitektur HP-mu (kebanyakan: arm64-v8a).', dl_sums: 'Checksum SHA-256',
    donate_title: 'Dukung pengembangan', donate_sub: 'KzDesk Remote gratis. Donasimu membantu rilis dan server tetap hidup.', donate_btn: 'Donasi sekarang',
    status_title: 'Status layanan', status_ok: 'Operasional', status_devices: 'Perangkat online', status_sessions: 'Sesi aktif', status_version: 'Versi rilis',
    privacy_title: 'Kebijakan Privasi', terms_title: 'Syarat Penggunaan', licenses_title: 'Lisensi pihak ketiga',
    footer_by: 'Dikembangkan oleh', footer_rights: 'Seluruh hak dilindungi.', not_found: 'Halaman tidak ditemukan.', back_home: 'Kembali ke beranda',
  },
  en: {
    nav_features: 'Features', nav_how: 'How it works', nav_host: 'Windows Host', nav_download: 'Download', nav_donate: 'Donate',
    cta_download: 'Download for Android', cta_host: 'Install Host on PC', badge: 'Remote Desktop • RDP • Auto-update',
    hero_title: 'Control your Windows PC from anywhere.',
    hero_sub: 'KzDesk Remote is a remote desktop app for Android, iOS, and desktop. Connect with IP + RDP account, or just a Device ID + password. Install the host once and it keeps itself up to date.',
    stat_modes: '2 connection modes', stat_update: 'Auto-update', stat_input: 'Physical keyboard & mouse', stat_tpl: 'JSON templates',
    f_title: 'Everything you need to remote into a PC',
    f1_t: 'Two ways to connect', f1_d: 'Direct RDP with IP, username & password. Or Device ID + password, no IP to memorize.',
    f2_t: 'Physical keyboard & mouse', f2_d: 'Use a Bluetooth or USB keyboard/mouse. On-screen controls hide automatically while physical input is in use.',
    f3_t: 'Controller Templates (JSON)', f3_d: 'Build buttons and joysticks, save them as templates, and import/export through JSON files or the clipboard.',
    f4_t: 'Joystick controller', f4_d: 'Analog joystick for WASD, arrows, cursor or camera. Tune sensitivity and deadzone per joystick.',
    f5_t: 'Auto-update everywhere', f5_d: 'The Android app checks for releases, downloads and verifies SHA-256. The Windows host updates itself too.',
    f6_t: 'Install once, done', f6_d: 'One installer sets up the host agent, firewall rules, the KzDesk account and the updater. No manual setup.',
    how_title: 'Get going in three steps',
    s1_t: 'Install the Host', s1_d: 'Run the installer on your Windows PC. Your Device ID and password appear when it finishes.',
    s2_t: 'Install the app', s2_d: 'Download the KzDesk Remote APK on Android and add a device.',
    s3_t: 'Connect', s3_d: 'Enter ID + password, or IP + username + password. Done.',
    host_title: 'KzDesk Host for Windows', host_sub: 'One installer. It sets everything up, then detects updates by itself.',
    host_install: 'Download installer (.exe)', host_ps: 'Or run one line in PowerShell (Administrator):',
    host_includes: 'What gets installed', host_i1: 'KzDesk host agent (autostart)', host_i2: 'Dedicated “KzDesk” Windows account for Device ID mode',
    host_i3: 'Firewall rules & Remote Desktop', host_i4: 'Automatic updater (periodic check, SHA-256 verified)',
    dl_title: 'Download KzDesk Remote', dl_latest: 'Latest version', dl_unavail: 'Release information is not available yet.',
    dl_abi: 'Pick the build for your phone architecture (most phones: arm64-v8a).', dl_sums: 'SHA-256 checksums',
    donate_title: 'Support development', donate_sub: 'KzDesk Remote is free. Your donation keeps releases and servers running.', donate_btn: 'Donate now',
    status_title: 'Service status', status_ok: 'Operational', status_devices: 'Devices online', status_sessions: 'Active sessions', status_version: 'Release version',
    privacy_title: 'Privacy Policy', terms_title: 'Terms of Use', licenses_title: 'Third-party licenses',
    footer_by: 'Developed by', footer_rights: 'All rights reserved.', not_found: 'Page not found.', back_home: 'Back to home',
  },
};

const LANGS = Object.keys(DICT);

function pickLang(req) {
  const q = String(req.query?.lang || '').toLowerCase();
  if (LANGS.includes(q)) return q;
  const cookie = /(?:^|;\s*)lang=(id|en)\b/.exec(req.headers?.cookie || '');
  if (cookie) return cookie[1];
  return /^en\b/i.test(String(req.headers?.['accept-language'] || '')) ? 'en' : 'id';
}

const translator = (lang) => (key) => DICT[lang]?.[key] ?? DICT.id[key] ?? key;

module.exports = { DICT, LANGS, pickLang, translator };
