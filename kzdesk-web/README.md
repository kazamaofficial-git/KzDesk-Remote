# KzDesk Remote — Web & API (Node.js + Express + EJS)

Situs resmi + backend ringan untuk **KzDesk Remote** (developer: KazamaDev).

| Fungsi | Endpoint |
|---|---|
| Halaman web (EJS) | `/`, `/host`, `/download`, `/status`, `/privacy`, `/terms`, `/licenses`, `/donate` → redirect |
| Auto-update Android | `GET /api/update/app?versionCode=10000&abi=arm64-v8a,armeabi-v7a` |
| Auto-update Host Windows | `GET /api/update/host?version=1.0.0` |
| Direktori Device ID + password | `POST /api/device/{register,heartbeat,resolve,unregister}` |
| Sesi aktif anonim | `POST /api/session`, `GET /api/active-sessions` |
| Komunitas (jokes) | `/api/jokes…` |
| Unduhan | `/dl/app/:abi`, `/dl/host`, `/SHA256SUMS.txt`, `/install.ps1` |

## Menjalankan

```bash
cd kzdesk-web
npm install
cp .env.example .env     # sesuaikan BASE_URL, GITHUB_REPO, dll.
node --env-file=.env src/index.js
npm test                 # 35 tes (node:test, tanpa dependensi tambahan)
```

Taruh di belakang reverse proxy HTTPS (Caddy/nginx/Cloudflare) dan set `TRUST_PROXY=1`
agar IP klien dibaca benar (dipakai rate-limit dan direktori perangkat).

## Alur rilis → auto-update

1. Buat GitHub Release bertag `vMAJOR.MINOR.PATCH` di repo `GITHUB_REPO`.
2. Lampirkan aset **dengan nama persis**:
   `KzDesk-Remote-arm64-v8a.apk`, `KzDesk-Remote-armeabi-v7a.apk`, `KzDesk-Remote-x86_64.apk`,
   `KzDesk-Host-Setup.exe`, dan `SHA256SUMS.txt` (keluaran `sha256sum`).
3. Server membaca rilis terbaru (cache 5 menit). Aplikasi/host yang mengecek akan mendapat
   URL + SHA-256 dan memverifikasi sebelum memasang. Aset **tanpa** entri di `SHA256SUMS.txt`
   tidak pernah ditawarkan sebagai update.
4. Opsional: tulis `min_version_code: 10100` di catatan rilis untuk memaksa update.

`versionCode` = `major*10000 + minor*100 + patch` (1.2.3 → 10203). Nilai ini harus sama dengan
`VERSION_CODE` di `client/Android/Studio/release.properties`.

Jika GitHub tidak terjangkau server memakai cache lama, lalu `data/release-state.json` atau
`PUT /api/admin/release-state` (Bearer `ADMIN_TOKEN`).

## Device ID + password

Host mendaftar dengan **verifier PBKDF2** (salt+hash), bukan password. Aplikasi mengirim ID + password,
server memverifikasi dan mengembalikan alamat (LAN/VPN/publik) hanya bila benar. ID salah dan password
salah memberi respons identik; ada rate-limit per IP dan per ID.

> Batasan jujur: server ini **direktori**, bukan relay. Koneksi RDP tetap langsung aplikasi → PC.
> Dari internet, PC butuh port 3389 yang terbuka/di-forward, atau jaringan VPN (mis. Tailscale/WireGuard).

## Catatan keamanan

- CSP ketat (tanpa inline script/style); semua aset dari origin sendiri.
- Data disimpan di `data/store.json` (atomic write). Untuk skala besar ganti `JsonStore` dengan SQLite/Postgres.
- Fitur signaling WebRTC lama (`/api/pc/rooms`) tidak dipindahkan.
