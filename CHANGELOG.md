# Changelog

Semua perubahan penting pada **KzDesk Remote** dicatat di sini.
Format mengikuti [Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/) dan penomoran
versi mengikuti [Semantic Versioning](https://semver.org/lang/id/).

Pengembang: **KazamaDev** · Package Android: `com.kazamatech.kzdesk`

## [1.0.0] — 2026-10-12

Rilis perdana KzDesk Remote: aplikasi remote desktop untuk mengendalikan PC Windows dari
Android, iOS, dan desktop.

### Ditambahkan

**Koneksi**
- **RDP via IP**: username dan password Windows, dengan daftar perangkat tersimpan, favorit, dan
  cadangan/pemulihan profil.
- **ID Device + Password**: tanpa menghafal IP. Aplikasi mencari alamat PC lewat server (LAN → VPN →
  IP publik), lalu menyambung RDP ke akun khusus `KzDesk` di PC.
- **Mikrofon HP → PC**: suara dari mikrofon HP menjadi input suara di PC; aktif secara bawaan untuk perangkat baru.
- **Scan QR** dari KzDesk Host untuk mengisi ID Device dan password sekaligus.
- Sesi layar penuh dengan kontrol sentuh, papan tombol, clipboard, transfer file dua arah, multi-monitor,
  audio, dan kunci sidik jari.

**Kontrol**
- **Controller HUD** yang bisa disusun sendiri: tombol, kombinasi, makro, D-pad, dan joystick, dengan
  ikon spesifik untuk aksi umum (salin, tempel, simpan, pilih semua, refresh, Task Manager, zoom, volume, dll.).
- **Template Controller (JSON)**: simpan tata letak sebagai template, terapkan template bawaan (termasuk
  *FPS Joystick*), **impor/ekspor** lewat berkas `.json`, clipboard, atau tempel teks; mendukung bundel.
  Impor divalidasi (maks 256 KB, 36 tombol, nilai di-*clamp*); makro dari impor tidak pernah menekan Enter
  otomatis dan meminta konfirmasi. Contoh di `templates/`, panduan di `docs/CONTROLLER-TEMPLATES.md`.
- **Joystick per kontrol**: mode **WASD**, **Panah**, atau **Kursor/Kamera**, dengan sensitivitas dan
  deadzone yang bisa diatur dan tersimpan di template.
- **Keyboard dan mouse fisik** (USB/Bluetooth) dipakai langsung; kontrol layar menyingkir otomatis.
- **Stylus / S-Pen**: kursor mengikuti ujung pena, tombol pena menjadi klik kanan/tengah.
- **Picture-in-Picture** otomatis saat menekan Home dalam sesi (bisa dimatikan).
- **Widget layar utama**: sambung ke PC favorit dengan satu ketukan.

**Pembaruan dan notifikasi**
- **Auto-update aplikasi**: cek otomatis (maks. tiap 6 jam, plus pengecekan berkala di latar), unduh saat
  Wi-Fi/non-metered, verifikasi **SHA-256**, nama paket, dan versi, lalu pasang dengan satu ketukan.
- **Push notification** (opsional): rilis baru, pengumuman, dan status PC online/offline (Android lewat FCM, iOS lewat APNs).
- **Notifikasi koneksi putus** saat sesi terputus ketika aplikasi tidak sedang dilihat.
- **Laporan crash** (opt-in): hanya stack trace yang disamarkan (tanpa IP, e-mail, atau ID).

**KzDesk Host (Windows)**
- **Satu installer** (`KzDesk-Host-Setup.exe`) yang memasang semuanya: layanan latar, akun `KzDesk`, Remote Desktop
  dengan NLA, aturan firewall, izin audio dan mikrofon redirect, serta ID Device dan password acak.
- Halaman pairing lokal dengan **QR** (Start Menu → KzDesk Host).
- **Auto-update host**: cek berkala, unduh, verifikasi SHA-256, pasang senyap tanpa kehilangan ID dan password.
- Opsi mengizinkan koneksi dari internet (profil jaringan Publik), nonaktif secara bawaan.

**Platform lain**
- **iOS**: klien RDP dengan audio, mikrofon, dan push (build tanpa tanda tangan).
- **Desktop Windows/macOS/Linux**: klien RDP SDL beserta `kzdesk-connect` untuk mode ID Device + password.

**Situs dan API (Node.js + Express + EJS)**
- Situs dua bahasa: beranda, Host Windows, unduhan, status layanan, kebijakan privasi, syarat, dan lisensi.
- API update (aplikasi dan host) berbasis GitHub Releases dengan cache, cadangan, dan opsi update wajib.
- Direktori **ID Device + password**: verifier PBKDF2 (password tidak disimpan), pembatasan laju per IP dan per ID,
  respons identik untuk ID salah dan password salah.
- Layanan push (FCM HTTP v1 dan APNs), penerima laporan crash, statistik sesi anonim, dan fitur komunitas.
- Skrip pemasangan satu baris (`/install.ps1`) yang memverifikasi SHA-256 sebelum menjalankan installer.
- Header keamanan ketat (CSP tanpa skrip/gaya inline), 50 tes otomatis, dan `Dockerfile`.

**Antarmuka dan rilis**
- Antarmuka bergaya **SaaS modern** bertema hitam dan ungu, mode gelap/terang, dua bahasa (Indonesia/Inggris),
  serta panduan singkat untuk pemula.
- Ikon dan logo KzDesk untuk semua platform dan situs.
- Pipeline rilis: satu workflow membangun semua platform dan memublikasikannya ke satu GitHub Release beserta `SHA256SUMS.txt`.

### Keamanan
- Pembaruan hanya ditawarkan jika memiliki checksum SHA-256 dan diunduh lewat HTTPS dari GitHub atau server KzDesk.
- Aplikasi tanpa iklan dan tanpa SDK pelacak; push dan laporan crash opt-in.

### Persyaratan
- Android 7.0 (API 24) atau lebih baru; iOS 15 atau lebih baru.
- Host: Windows 10 (1809) / 11 atau Windows Server 2019+, edisi Pro/Enterprise/Server (bukan Home).
