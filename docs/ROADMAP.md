# Roadmap & status fitur

Status tiap butir dari daftar rencana fitur terhadap KzDesk Remote 1.0.0. "Sudah ada" berarti fitur memang
sudah ada di kode sebelum daftar ini dikerjakan dan **tidak** ditambahkan ulang.

| Fitur | Status | Catatan |
|---|---|---|
| PC Stream (WebRTC, 60 fps, gamepad virtual) | **Belum ada** | Butuh agen host Windows yang menangkap layar (DXGI), meng-encode (hardware), dan mengirim lewat WebRTC. Sisi klien/penyelamat sinyal belum lengkap dan agen host belum ada. Gamepad virtual di layar sudah ada (HUD). |
| Gamepad Bluetooth fisik | Sudah ada | Gamepad fisik dipetakan ke aksi dan stik. |
| Virtual Display Adapter (driver monitor virtual) | **Belum ada** | Driver mode-kernel (IDD) memerlukan Windows Driver Kit dan penandatanganan driver; tidak bisa dibuat sebagai skrip. |
| Transfer file 2 arah | Sudah ada | Kirim ke PC dan simpan kiriman PC ke `Download/KzDesk`. |
| Kirim file dari Android ke PC (Share) | Sudah ada | Penerima Share di aplikasi. |
| Widget layar utama | **Baru** | Sampai 4 perangkat, satu ketukan sambung (tetap melewati kunci sidik jari). |
| Laporan crash otomatis (opt-in) | **Baru** | Stack trace disamarkan; tidak ada yang dikirim bila mati. |
| Multi monitor | Sudah ada | Pemilih monitor di sesi. |
| Notifikasi koneksi putus | **Baru** | Muncul bila sesi putus sendiri saat app tidak dilihat. Butuh izin notifikasi. |
| Picture-in-Picture | **Baru** | Otomatis saat menekan Home dalam sesi; bisa dimatikan di Pengaturan. |
| Makro 1 tombol | Sudah ada | Tombol makro dan kombinasi; kini juga lewat Template Controller. |
| Kunci sidik jari | Sudah ada | Otentikasi sebelum menyambung. |
| Stylus / S-Pen | **Baru** | Hover menggerakkan kursor; tombol pena = klik kanan, tombol kedua = klik tengah. Belum diuji di perangkat pena sungguhan. |
| QR code | **Baru** | Host menampilkan QR pairing; aplikasi memindainya. Encoder QR diverifikasi dengan decoder. |
| Kontrol audio per sesi | Sebagian | Kontrol volume sudah ada; ikon tombol Volume/Mute tersedia untuk HUD. Pemetaan tombol media ke PC belum diuji. |
| Kecepatan scroll & sensitivitas mouse | Sudah ada | |
| Laporan bug keyboard / papan saran | Tidak dikerjakan | Butuh reproduksi di perangkat yang terdampak. |
| Optimasi baterai/kuota, anti layar hitam | Tidak dikerjakan | Butuh profiling di perangkat. |
| Panduan singkat pemula | **Baru** | Muncul sekali; juga di menu "Panduan". |

## Belum diverifikasi

Kode Kotlin/Java/Objective-C dan PowerShell tidak dapat dikompilasi atau dijalankan di lingkungan pengembangan
ini. Yang telah dijalankan: server Node (tes), encoder QR (decoder), skrip desktop (server tiruan), validator template.
Build pertama di CI adalah verifikasi nyata untuk Android, iOS, installer Windows, dan klien desktop.
