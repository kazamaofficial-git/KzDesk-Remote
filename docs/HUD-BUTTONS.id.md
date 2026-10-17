# Overlay kontrol, mengetik, dan pas-layar

KzDesk Remote tidak punya toolbar dan tidak punya keyboard virtual bawaan. Semua
kontrol adalah tombol overlay yang kal tempatkan sendiri, dan mengetik memakai
keyboard HP (IME). Dokumen ini menjelaskan model di v0.5.2.

## 1. Satu tombol = satu aksi

Tiap kontrol di layar adalah satu tombol bulat (`CircleShape`, diameter dalam
dp). Tidak ada yang digabung: klik kiri, klik kanan, klik tengah, scroll naik,
scroll turun, keyboard, dan ganti mode input adalah tujuh tombol terpisah —
masing-masing punya ikon, posisi, ukuran, dan aksi sendiri.

| Properti | Rentang | Cara mengubah |
|---|---|---|
| Posisi | 0..1 dari area sesi | geser tombolnya di layar (kapan saja, bukan cuma mode atur posisi) |
| Ukuran | 24..140 dp (diameter, radius = setengah) | tahan lama tombolnya, atau daftar tombol di panel kanan |
| Aksi | Sekali klik / Tahan / Toggle | editor tombol |
| Jenis | entri katalog (aksi mouse, modifier, kombinasi, tombol, numpad, huruf, simbol) | "Ganti jenis aksi" di editor, atau "Tambah tombol" |
| Kombinasi | Ctrl+C, Ctrl+Shift+Esc, Alt+Tab, Win+R, ... | grup katalog "Kombinasi siap pakai" |

Arti aksi:

- **Sekali klik** – tekan lalu lepas (mis. huruf biasa, Enter, kombinasi).
- **Tahan** – aktif selama ditahan (drag pakai klik kiri, scroll terus-menerus).
- **Toggle** – klik pertama nyala, klik kedua mati (mis. Ctrl sebagai modifier
  lengket).

## 2. Akses ke sana

- **Di layar** – tombolnya sendiri. Geser untuk pindah, tahan lama untuk membuka
  editornya.
- **Mode atur posisi** – panel kanan → Tombol → "Atur posisi". Grid muncul,
  tombol tetap bisa digeser, dan ada banner di atas dengan "+ Tombol" dan
  "Selesai".
- **Tambah tombol** – chip "+ Tombol" di banner atur posisi, atau "Tambah
  tombol" di panel kanan. Keduanya membuka katalog.
- **Kembalikan bawaan** – "Kembalikan tombol bawaan" di panel kanan.

Posisi dan ukuran disimpan per perangkat (`$deviceId.hudkeys`), jadi layout yang
pas di satu layar tidak mengganggu perangkat lain.

## 3. Mengetik

Tidak ada board QWERTY, F1..F12, atau numpad di layar, dan tidak ada baris tombol
di atas keyboard. Mengetik memakai keyboard HP:

- Tombol keyboard di HUD (atau Input → "Keyboard HP (IME)" di panel kanan)
  membuka/menutup IME.
- Karakter dari IME diteruskan ke sesi sebagai `KeyEvent`, jadi KeyboardMapper
  inti yang menentukan scancode, modifier, dan layout. Karakter tanpa keycode
  (emoji, huruf beraksen) lewat jalur unicode.
- "Kirim teks ke remote" di panel kanan menempel teks clipboard lalu mengirimnya
  sebagai unicode.

## 4. Taskbar dan pas-layar

Desktop remote harus masuk layar — kalau tidak, taskbar Windows dan tepi bawah
desktop jatuh di luar area gambar dan kelihatan seperti tenggelam.

- **Muat seluruh desktop (fit)** (panel kiri → Layar, juga di setelan Umum)
  menyala secara default. Sesudah menyambung, sesudah ganti resolusi, sesudah
  rotasi, dan setiap kali ukuran area gambar berubah, tampilan dimuat ulang.
- **Resolusi otomatis** mengirim ukuran area gambar yang benar-benar terlihat
  (bukan seluruh jendela), jadi tidak ada bagian desktop yang terpotong system
  bar.
- **Preset resolusi** dikelompokkan per rasio (16:9, 16:10, 21:9, 4:3, potret)
  dan "16:9 pas layar" memilih ukuran standar 16:9 terbesar yang masih muat.
  Setelah resolusi berubah, seluruh desktop langsung dimuat ulang.

## 5. Kursor

Pointer memakai bentuk kursor yang dikirim server (panah, tangan, I-beam,
resize, ...) lengkap dengan hotspot-nya. Kalau server tidak mengirim apa-apa,
panah/titik bawaan app dipakai. Ukuran dan gaya pointer ada di panel kanan
bagian Pointer.


## Perubahan ronde 6 (v0.5.4)

- Tombol **terkunci** selama sesi dipakai; memindahkan tombol hanya bisa lewat
  mode **Atur posisi & ukuran** (panel kanan) atau tahan lama satu tombol.
- Di mode atur posisi: geser = pindah, ketuk = buka editor tombol.
- **Latar tombol** per app: Gelap tipis (default), Terang tipis, Transparan.
- **Rail kanan bawah** tetap: keyboard HP buka/tutup + keluar dua kali ketuk.
