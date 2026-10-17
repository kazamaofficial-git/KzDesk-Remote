#ifndef KZDESK_QUIC_DLL_H
#define KZDESK_QUIC_DLL_H

#include <stdint.h>

#ifdef _WIN32
#ifdef KZDESK_QUIC_EXPORTS
#define KZDESK_QUIC_API __declspec(dllexport)
#else
#define KZDESK_QUIC_API __declspec(dllimport)
#endif
#else
#define KZDESK_QUIC_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct KzQuicHostMetadata {
    char pc_id[32];
    char hostname[128];
    char primary_ip[64];
    char gpu_name[128];
    char gpu_encoder[32];
    uint16_t rdp_port;
    uint16_t quic_port;
    uint32_t max_fps;
} KzQuicHostMetadata;

/*
 * Kontrak jembatan audio/mic (lihat docs/AUDIO_BRIDGE.md):
 *  - HP kirim "KZDESK_QUIC_AUDIO_SUB_V1" ke UDP :4433 tiap 2 detik (subscribe).
 *  - Host kirim "XYA1" + seq:u16 + frames:u16 + PCM16 stereo 24 kHz (suara PC
 *    hasil WASAPI loopback; endpoint dipilih otomatis + rotasi bila senyap).
 *  - HP kirim "XYM1" + seq:u16 + frames:u16 + PCM16 mono 24 kHz (mic HP);
 *    host merender ke endpoint "CABLE Input"/"KzDesk Virtual Microphone"
 *    (fallback: default render) supaya jadi input mic virtual di PC.
 *  - Host kirim "XYST1" (16 B + teks) tiap 1 detik: flags, level mic, jumlah
 *    paket, lalu "audio=<endpoint>|mic=<endpoint>".
 */

/** Returns the native QUIC DLL version & protocol summary. */
KZDESK_QUIC_API const char* kzdesk_quic_dll_version(void);

/** Starts the background UDP/QUIC v1 responder thread on quic_port (default 4433). */
KZDESK_QUIC_API int kzdesk_quic_server_start(const KzQuicHostMetadata* meta);

/** Stops the background UDP/QUIC v1 responder thread. */
KZDESK_QUIC_API void kzdesk_quic_server_stop(void);

/** Returns total number of QUIC Initial/Datagram probes served. */
KZDESK_QUIC_API uint64_t kzdesk_quic_packets_served(void);

#ifdef __cplusplus
}
#endif

#endif /* KZDESK_QUIC_DLL_H */
