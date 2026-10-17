#ifndef KZDESK_HOST_CORE_DLL_H
#define KZDESK_HOST_CORE_DLL_H

#include "kzdesk_quic.h"

#ifdef _WIN32
#ifdef KZDESK_HOST_CORE_EXPORTS
#define KZDESK_CORE_API __declspec(dllexport)
#else
#define KZDESK_CORE_API __declspec(dllimport)
#endif
#else
#define KZDESK_CORE_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

/** Returns the native Host Core DLL version string. */
KZDESK_CORE_API const char* kzdesk_host_core_version(void);

/**
 * Inspects the Windows Host (DXGI GPU adapter, primary LAN/Tailscale IPv4,
 * hostname, and 10-digit KzDesk PC ID) and populates out_meta.
 */
KZDESK_CORE_API int kzdesk_host_inspect_system(KzQuicHostMetadata* out_meta);

/**
 * Applies Windows Terminal Services / RDS policies for KzDesk Remote:
 *  - Enables RDP & Multi-User Concurrent Sessions (fDenyTSConnections=0, fSingleSessionPerUser=0)
 *  - Enables Hardware GPU H.264 AVC 4:4:4 Full-Chroma encoding (AVC444ModePreferred=1, VGAdapter=1)
 *  - Enables ClearType Font Smoothing & DWM Desktop Composition
 *  - Unlocks RDP Audio Redirection (fDisableAudio=0, fDisableAudioCapture=0)
 *  - Opens Windows Firewall for TCP 3389, UDP 3389, and UDP 4433 (QUIC)
 */
KZDESK_CORE_API int kzdesk_host_apply_windows_policies(void);

#ifdef __cplusplus
}
#endif

#endif /* KZDESK_HOST_CORE_DLL_H */
