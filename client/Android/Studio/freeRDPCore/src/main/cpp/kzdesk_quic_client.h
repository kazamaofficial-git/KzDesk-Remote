#ifndef KZDESK_QUIC_CLIENT_H
#define KZDESK_QUIC_CLIENT_H

#include <jni.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Probes a KzDesk Remote Host Agent over native UDP/QUIC (RFC 9000 Initial
 * packet with ALPN "kzdesk-pc/1" on UDP port 4433 / target port) and falls
 * back to low-latency UDP/TCP RTT measurement.
 * Returns a heap-allocated UTF-8 JSON string (caller frees with free()).
 */
char* kzdesk_quic_probe_host_json(const char* host, int port, int timeout_ms);

/**
 * Returns static engine descriptor string for diagnostics and telemetry.
 */
const char* kzdesk_quic_engine_version(void);

#ifdef __cplusplus
}
#endif

#endif /* KZDESK_QUIC_CLIENT_H */
