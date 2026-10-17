# KzDesk Audio Bridge — kontrak HP ⇄ PC (UDP 4433)

Dokumen ini adalah sumber kebenaran bersama antara **KzDesk-Remote** (APK Android +
`KzDeskRemoteHost.exe` / `kzdesk_quic.dll`) dan **KzDesk** (host Rust/NSIS,
`kazamadev/KzDesk`). Jangan ubah magic atau layout paket tanpa memperbarui sisi lain.

## 1. Kenapa ada jembatan ini

Kanal audio RDP (`rdpsnd`) saling berebut dengan mic virtual:

- Setelah VB-CABLE / *KzDesk Virtual Microphone* dipasang, `Remote Audio` sering
  dimatikan (atau `UmRdpService` distop). Efeknya kanal audio RDP mati → suara PC
  tidak sampai ke HP.
- Mic HP tidak bisa lewat RDP kalau endpoint input virtual tidak dijadikan target.

Jembatan UDP 4433 dibuat independen dari kanal RDP: suara PC → HP (WASAPI
**loopback**) dan mic HP → PC (render ke endpoint input virtual). Karena tidak
terikat m-line SDP, kedua arah tetap hidup apa pun mode audio RDP yang dipilih.

## 2. Layout paket (little-endian, PCM16)

| Arah | Magic | Isi |
| --- | --- | --- |
| HP → PC | `KZDESK_QUIC_AUDIO_SUB_V1` (ASCII, 25 B) | subscribe; dikirim tiap 2 detik; host menganggap langganan hidup 8 detik |
| PC → HP | `XYA1` | `[4]=seq u16` `[6]=frames u16` `[8..]=PCM16 stereo 24 kHz` (240 frame/paket, 100 paket/detik) |
| HP → PC | `XYM1` | `[4]=seq u16` `[6]=frames u16` `[8..]=PCM16 mono 24 kHz` (240 frame/paket) |
| PC → HP | `XYST1` | `[4]='1'` `[5]=flags u8` `[6]=peak mic u8` `[7]=0` `[8..11]=audioPkt u32` `[12..15]=micFrame u32` `[16..]=teks "audio=<endpoint>\|mic=<endpoint>"` |

Flags `XYST1`:

- bit0 `0x01` — loopback suara PC aktif & sudah menemukan sinyal
- bit1 `0x02` — mic HP sedang dirender ke endpoint di PC
- bit2 `0x04` — target mic = endpoint virtual (CABLE Input / KzDesk Virtual Microphone)

## 3. Aturan pemilihan endpoint (host Windows)

Loopback suara PC (`AudioLoopbackThread`):

1. Kumpulkan endpoint render aktif; default lebih dulu, lalu fisik, lalu virtual.
2. Endpoint `Remote Audio` **dibuang** kalau ada endpoint lain (kalau tidak, suara
   Device-mode dobel: RDP + jembatan). Dipakai hanya sebagai cadangan terakhir.
3. Uji tiap kandidat 4 detik; kalau tidak ada sinyal (> 0.002), ganti ke kandidat
   berikutnya. Endpoint pertama yang bersuara **dikunci** (`locked_device`) untuk
   sesi itu; subscriber baru (HP reconnect) mereset kunci.

Mic HP (`MicRenderThread`):

1. Pilih endpoint render dengan nama mengandung `cable input` (VB-CABLE) atau
   `kzdesk` (KzDesk Virtual Microphone); alternatif `voicemeeter`,
   `virtual audio cable`.
2. Kalau tidak ada, fallback ke default render endpoint.
3. Stream tetap hidup (render senyap) sampai 30 detik idle, lalu endpoint dipilih
   ulang — supaya VB-CABLE yang baru dipasang langsung terpakai tanpa restart.
4. Gain render 1.4x sebagai pengaman; DSP utama (gain, gate, NS, AGC) di sisi HP.

## 4. Sisi Android (`QuicAudioBridge.kt`)

- Subscribe tiap 2 detik; status host ditampilkan di log sesi (`ConnectionLog`
  + `SessionManager.audioBridgeState`).
- Pemutaran pakai `AudioTrack` stream 24 kHz stereo; mic pakai `AudioRecord`
  (`VOICE_COMMUNICATION`, fallback `MIC`) 24 kHz mono, frame 10 ms.
- DSP inline: high-pass ~110 Hz, noise gate (ambang dari setelan sesi), AGC,
  gain pre-amp (`micGainDb`), soft limiter.
- Setelan sesi (`RdpOptions`): `quicAudio`, `micGainDb`, `micGateDb`,
  `micNoiseSuppression`, `micAgc`.

## 5. Checklist troubleshooting

1. `KzDeskRemoteHost.exe` jalan di PC (admin) → firewall UDP 4433 dibuka oleh
   `kzdesk_host_core` saat start (`netsh advfirewall ... UDP 4433`).
2. Log sesi HP harus muncul `AUDIO: host ok · audio:terkunci · mic:render/virtual-mic`.
   Kalau tertulis *host-agent tidak menjawab UDP :4433*, berarti paket balik tidak
   ada: cek firewall / proses host / koneksi UDP di jalur NAT.
3. Suara PC senyap tapi status `audio:cari endpoint`: endpoint default sedang
   `Remote Audio` (di-buang dari kandidat) atau audio PC memang tidak diputar.
4. Mic tidak masuk di aplikasi PC: pastikan aplikasi memilih input
   `CABLE Output (VB-Audio Virtual Cable)` atau `KzDesk Virtual Microphone`, dan
   status menunjukkan `mic:render/virtual-mic`.
