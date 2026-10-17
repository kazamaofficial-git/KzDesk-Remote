# Bukti Integritas & Pemindaian Keamanan Rilis

Tujuan: memberi cara verifikasi yang bisa diulang siapa pun (termasuk reviewer
store) tanpa harus mempercayai klaim kami. Tidak ada klaim "100% aman" di sini —
hanya prosedur dan bukti yang bisa dicek.

## 1. Arti status

- `PRD`/`AUDIT`/`UNVERIFIED` mengikuti definisi di dokumen kerja internal.
- Hasil pemindaian pihak ketiga baru berlaku setelah tanggal pemindaian dicatat
  di tabel bagian 4. Jangan tulis "sudah lolos VirusTotal" sebelum ada angkanya.

## 2. Sumber kebenaran integritas: `SHA256SUMS.txt`

Setiap rilis (sejak v0.5.29) menerbitkan `SHA256SUMS.txt` yang dibuat di CI dari
file yang benar-benar diunggah. Cara cek di Windows (PowerShell):

```powershell
$base = "https://kzdesk.kazamasite.biz.id"
iwr -UseBasicParsing "$base/arm64-v8a.apk" -OutFile .\xy-apk
iwr -UseBasicParsing "$base/agent.ps1" -OutFile .\agent.ps1
Get-FileHash .\xy-apk -Algorithm SHA256
```

Bandingkan hasilnya dengan baris `app-arm64-v8a-release.apk` di `SHA256SUMS.txt`
(ambil dari halaman rilis GitHub `kazamadev/KzDesk-Remote`, tag rilis terbaru).

Catatan: `kzdesk.kazamasite.biz.id` mengarahkan (HTTP 302) ke URL aset resmi GitHub.
Kalau hash berbeda, hentikan pemasangan dan laporkan.

## 3. Pemindaian VirusTotal (otomatis per rilis)

Setiap tag rilis menjalankan job `scan-virustotal` di workflow `build-apk.yml`
(setelah rilis terbit). Job itu memanggil `scripts/virustotal_scan.py` yang:

1. mengunduh aset rilis yang benar-benar sudah diunggah (bukan hasil build lokal),
2. menghitung SHA-256 tiap berkas,
3. mengunggah ke VirusTotal lewat API v3 (berkas > 32 MB memakai endpoint bigfiles),
4. menulis hasilnya apa adanya ke `VIRUSTOTAL.txt` dan menempelkannya sebagai aset rilis.

Kunci API disimpan sebagai repository secret `VT_API_KEY` dan tidak pernah masuk
ke repo maupun ke berkas laporan. Kalau secret itu kosong, job hanya mencatat
`notice` dan dilewati — rilis tidak pernah tertahan karena VirusTotal.

Manual (kalau perlu ulang): buka `https://www.virustotal.com/gui/home/upload`,
unggah berkas yang mau diperiksa, lalu cocokkan SHA-256 di tab Details dengan
`SHA256SUMS.txt` rilis tersebut. Satu unggahan per rilis sudah cukup.

### Deteksi palsu yang lazim pada klien RDP

- APK RDP memuat pustaka native (FreeRDP, OpenSSL, FFmpeg, OpenH264). Beberapa
  engine memberi label generik `Riskware`/`PUA` karena pola tersebut, bukan karena
  perilaku berbahaya. Baca "Behavior" dan "Relations" sebelum menyimpulkan.
- Aplikasi belum terdaftar di Play Protect (belum di Play Store) sehingga
  pemasangan manual bisa memunculkan peringatan "aplikasi tidak dikenal".
- Deteksi 1-3 dari 70+ engine dengan label generik: kirim tautan hasil ke kami,
  jangan langsung menganggap bersih.

## 4. Catatan hasil pemindaian

Pemindaian v0.5.29 (2026-10-01, API VirusTotal v3). Rincian penuh + tautan per
berkas ada di aset rilis `VIRUSTOTAL.txt`; hash semuanya cocok dengan
`SHA256SUMS.txt` (diverifikasi ulang dengan `sha256sum -c`).

| Berkas | Segar | Deteksi | Catatan |
| --- | --- | --- | --- |
| app-arm64-v8a-release.apk | ya (unggah baru) | 0 malicious dari 75 | undetected 67, type-unsupported 8 |
| app-armeabi-v7a-release.apk | ya (unggah baru) | 0 malicious dari 75 | undetected 68, type-unsupported 7 |
| app-x86_64-release.apk | ya (unggah baru) | 0 malicious dari 75 | undetected 68, type-unsupported 7 |
| KzDeskRemoteHost.exe | hasil tersimpan | 4 malicious dari 71 | Bkav, Elastic, VirIT, Lionic — label generik |
| kzdesk_host_core.dll | hasil tersimpan | 2 malicious dari 71 | Elastic + Microsoft `Trojan:Win32/Wacatac.C!ml` |
| kzdesk_quic.dll | hasil tersimpan | 0 malicious dari 75 | bersih |
| KzDesk-Remote-Host-Agent-win64.zip | ya (unggah baru) | 3 malicious dari 68 | isi zip = exe + 2 DLL di atas |

Tiga APK rilis bersih di semua engine. Penandaan yang tersisa ada di biner
Windows tanpa tanda tangan digital dan bersifat heuristik/ML generik
(`Wacatac`, `Genus`, `Malware.<kode>` adalah nama yang lazim muncul untuk biner
baru tanpa code signing). Yang bisa dipastikan: hash cocok, jadi berkas yang
dipindai = berkas yang diunduh. Cara menghilangkan penandaan sepenuhnya:
tandatangani biner Windows dengan sertifikat code signing, dan/atau ajukan
koreksi deteksi palsu ke vendor masing-masing (Microsoft menyediakan portal
submisi gratis).

## 5. Bahan cek yang bisa dilakukan sendiri tanpa alat pihak ketiga

- `unzip -l app-arm64-v8a-release.apk | grep '\.so$'` → daftar pustaka native;
  semua harus berasal dari proyek ini/upstream yang tercatat di `LICENSES`.
- Tidak ada SDK iklan/analitik di `app/build.gradle` (hanya AndroidX + Compose +
  modul internal). Kalau ini berubah, perbarui juga
  `docs/STORE_DESCRIPTION.md` bagian "Fitur utama".
- Izin di `AndroidManifest.xml` harus tetap minimal: network (dari modul
  `freeRDPCore`), `RECORD_AUDIO` (diminta saat dipakai, bukan saat pasang),
  `FOREGROUND_SERVICE*` untuk sesi.
- Kredensial disimpan lokal lewat vault AES-256-GCM (`features-security`), tidak
  ada unggahan ke server kami. Server yang disentuh app hanya host RDP pilihan
  pengguna dan portal rilis `kzdesk.kazamasite.biz.id` (layar Status).

## 6. Untuk reviewer store (ringkas)

| Pertanyaan umum | Jawaban |
| --- | --- |
| Data apa yang dikumpulkan? | Tidak ada ke server kami. Kredensial tersimpan lokal terenkripsi. |
| Iklan / analitik? | Tidak ada. |
| Izin sensitif? | Mikrofon (opsional, untuk mic RDP), penyimpanan (opsional, drive `KzDesk`), kamera (opsional, webcam remote). |
| Kontak keamanan | Lapor lewat halaman `Feedback` di app atau portal. |
| Cara verifikasi build | `SHA256SUMS.txt` + hasil VirusTotal pada tabel bagian 4. |
