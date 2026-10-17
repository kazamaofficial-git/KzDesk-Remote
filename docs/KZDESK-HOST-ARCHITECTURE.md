# KzDeskHost.exe — Native Windows Agent Architecture (Tahap Pengembangan)

Dokumen spesifikasi teknis untuk agen host native Windows (`KzDeskHost.exe` + `kzdesk_capture.dll`) yang mendampingi mode **Koneksi PC (ID & Password)** pada KzDesk Remote oleh **KazamaDev**.

## 1. Tujuan Utama
1. **Kemudahan Akses (ID PC & Password):** Menyediakan pairing instan berbasis 10-Digit ID PC (`XXX-XXX-XXXX`) dan kredensial terisolasi tanpa konfigurasi port-forwarding rumit.
2. **Keamanan End-to-End (E2EE):** Seluruh aliran frame, audio, dan input dienkripsi ujung-ke-ujung antara perangkat Android dan PC target menggunakan **TLS 1.3 / QUIC (X25519 + ChaCha20-Poly1305 / AES-256-GCM)** dengan *certificate fingerprint pinning* di dalam **Android Keystore**.
3. **Ultra-Low Latency untuk Game FPS (<8 ms LAN / <20 ms Tailnet):** Mengatasi keterbatasan RDP standar pada game 3D/FPS melalui tangkapan layar zero-copy di VRAM GPU, transport QUIC Unreliable Datagram, dan injeksi mouse relatif (`RawInput`).

## 2. Komponen Native Windows (`KzDeskHost.exe` + `.dll`)
- **`kzdesk_dxgi.dll` (Zero-Copy Frame Capture):**
  - Menggunakan **DXGI Desktop Duplication API** (`IDXGIOutputDuplication::AcquireNextFrame`) untuk mengambil tekstur `ID3D11Texture2D` langsung di memori GPU tanpa menyalin ke RAM CPU.
- **Hardware GPU Encoder Spesifik Vendor:**
  - **NVIDIA GeForce / RTX (`nvEncodeAPI64.dll`):** Preset `P1` (Ultra-Low Latency), Tuning Info `ULTRA_LOW_LATENCY`, `Zero-Reorder Delay`, `0 B-Frames`, `Intra-Refresh` periodik, H.264/HEVC `YUV444` atau `NV12`.
  - **AMD Radeon RX (`amfrt64.dll`):** Usage `AMF_VIDEO_ENCODER_USAGE_ULTRA_LOW_LATENCY`, `Pre-Analysis = false`, `B-Pictures = 0`, `Slice-based encoding`.
  - **Intel Arc / Iris Xe (`libmfx64-gen.dll` / OneVPL):** Target Usage `TU7` (Fastest), `LowPower = ON` (VDENC hardware fixed-function block), `AsyncDepth = 1`.
- **`kzdesk_quic.dll` (Transport QUIC Datagram + Adaptive FEC):**
  - Berbasis **MsQuic** (UDP port `3389` / `4433`).
  - Paket kontrol, autentikasi, dan clipboard dikirim via *QUIC Reliable Stream*.
  - Paket video dan audio real-time dikirim via *QUIC Unreliable Datagram* (RFC 9221) dengan *Forward Error Correction (Reed-Solomon / XOR FEC)* sehingga kehilangan 1 paket tidak menahan frame berikutnya (*Zero Head-of-Line Blocking*).
- **`kzdesk_input.dll` (FPS Relative Mouse & Virtual Gamepad):**
  - Mengirim pergerakan mouse sebagai delta relatif (`SendInput` dengan `MOUSEEVENTF_MOVE` tanpa `MOUSEEVENTF_ABSOLUTE`) agar kamera 360 derajat pada game FPS berfungsi normal.
  - Mendukung emulasi kontroler XInput/DualShock melalui driver *ViGEmBus*.
