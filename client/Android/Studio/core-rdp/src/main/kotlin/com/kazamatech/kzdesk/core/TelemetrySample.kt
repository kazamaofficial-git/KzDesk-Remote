package com.kazamatech.kzdesk.core

/**
 * Sampel telemetri sesi (M2+) — dasar panel Stats HUD & Live Telemetry Pill.
 *
 * Dipancarkan [SessionManager.telemetry] tiap 500ms. `fps` = jumlah
 * invalidasi grafik yang sudah dikoaleskan per detik (ekstrapolasi 2x dari
 * window 500ms), bukan FPS yang dijanjikan host RDP.
 * `rttMs` = RTT TCP live ke host:port layanan (probe handshake tiap 5 detik,
 * dihaluskan EMA lewat [RttSmoother]; TCP juga dipakai sekali saat konek),
 * bukan latensi input-ke-layar; `-1` bila belum diukur.
 */
data class TelemetrySample(
    val state: SessionState,
    val width: Int,
    val height: Int,
    val fps: Int,
    val rttMs: Int = -1,
    val codecLabel: String = "H.264 AVC444",
    val udpActive: Boolean = true,
    val colorDepth: Int = 32,
    val relayLabel: String = "RDP",
    val networkLabel: String = "Wi-Fi",
) {
    companion object {
        val EMPTY = TelemetrySample(SessionState.Idle, 0, 0, 0)
    }
}
