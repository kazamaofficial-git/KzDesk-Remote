# KzDesk Remote — NOTICE

**KzDesk Remote** © 2026 KazamaDev. Seluruh hak dilindungi untuk kode dan materi yang dibuat
khusus untuk KzDesk (aplikasi Android `client/Android/Studio/app/`, situs `kzdesk-web/`,
host Windows `host-agent/windows/`, skrip `scripts/`, dokumen, ikon, dan logo).

## Komponen pihak ketiga

### FreeRDP
Repositori ini memuat pohon sumber **FreeRDP 3.32.0** sebagai fondasi klien RDP.

- Lisensi: **Apache License 2.0** (lihat `LICENSE`).
- Hak cipta © 2012–2026 para kontributor FreeRDP — <https://www.freerdp.com> · <https://github.com/FreeRDP/FreeRDP>
- Berkas klien iOS di `client/iOS/` berlisensi **Mozilla Public License 2.0** (header pada tiap berkas
  dipertahankan); perubahan pada berkas tersebut juga dirilis di bawah MPL-2.0.

### Lainnya
| Komponen | Lisensi |
|---|---|
| Inter (font) | SIL Open Font License 1.1 |
| Space Grotesk (font) | SIL Open Font License 1.1 |
| zxing-android-embedded / ZXing | Apache License 2.0 |
| Firebase Cloud Messaging SDK | Apache License 2.0 |
| Express | MIT |
| EJS | Apache License 2.0 |
| SDL3 (klien desktop) | zlib License |

## Perubahan terhadap berkas FreeRDP (Apache-2.0 §4 / MPL-2.0 §3)

Berkas-berkas berikut berasal dari FreeRDP dan **telah dimodifikasi** oleh KazamaDev. Masing-masing
diberi catatan di bagian atas berkasnya.

| Berkas | Perubahan |
|---|---|
| `client/Android/Studio/freeRDPCore/.../presentation/SessionView.java` | Dukungan stylus/S-Pen: hover pena menggerakkan kursor, tombol pena = klik kanan/tengah |
| `client/iOS/AppDelegate.m` | Pendaftaran push notification (APNs) |
| `client/iOS/Models/RDPSession.m` | Argumen audio (`/sound`) dan mikrofon (`/microphone`), permintaan izin mikrofon |
| `client/iOS/Views/RDPSessionView.m` | Penamaan pustaka shader Metal |
| `client/iOS/Controllers/AppSettingsController.m` | Saklar "Microphone to PC" dan "Push Notifications" |
| `client/iOS/Controllers/BookmarkListController.m` | Nama berkas data bookmark |

Perubahan konfigurasi build (tanpa mengubah logika FreeRDP):

- `client/Android/Studio/build.gradle`, `settings.gradle`, `release.properties` — konfigurasi proyek KzDesk;
  modul `:aFreeRDP` digantikan modul `:app`.
- `client/iOS/CMakeLists.txt`, `KzDeskRemote.plist.in`, `KzDeskRemote.entitlements`,
  `cmake/ExternalFreeRDP.cmake` (`WITH_IOSAUDIO=ON`), `ModuleOptions.cmake`, `Resources/` (ikon, halaman About).
- `README.md` — README KzDesk; README upstream diarsipkan sebagai `FreeRDP-README.md`.

Berkas FreeRDP lainnya tidak diubah.

## Nama dan merek
"KzDesk", "KzDesk Remote", dan logonya adalah merek KazamaDev. "FreeRDP" adalah merek proyek FreeRDP;
KzDesk Remote bukan produk resmi FreeRDP dan tidak didukung olehnya.
