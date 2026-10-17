package com.kazamatech.kzdesk.core

/**
 * State machine sesi KzDesk (M1). Satu-satunya sumber kebenaran status sesi;
 * HUD (M2) dan UI (M1.2) hanya mengkonsumsi [SessionManager.state].
 *
 * Transisi legal:
 * ```
 * Idle -> Connecting -> Authenticating? -> Connected
 * Connecting/Authenticating -> Error
 * Connected -> Disconnecting -> Disconnected
 * Error/Disconnected -> Connecting  (connect() berikutnya)
 * ```
 */
sealed class SessionState {
    /** Tidak ada sesi. */
    data object Idle : SessionState()

    /** TCP/TLS handshake + NLA berjalan di thread native. */
    data object Connecting : SessionState()

    /** Server minta credential interaktif (NLA prompt). */
    data object Authenticating : SessionState()

    /** Sesi aktif — frame mengalir. */
    data object Connected : SessionState()

    /** Request disconnect (app atau remote). */
    data object Disconnecting : SessionState()

    /** Sesi selesai (normal/remote). Detail native opsional, bukan selalu sebab pasti. */
    data class Disconnected(val detail: String? = null) : SessionState()

    /**
     * Sesi gagal. [code] stabil (bisa di-`when`), [message] manusia-readable
     * (dari FreeRDP — boleh berubah antar versi, jangan di-hardcode).
     */
    data class Error(
        val code: String,
        val message: String,
        /**
         * Kategori kegagalan menurut [RdpFailure], untuk ditampilkan sebagai
         * `0x…`. Nol berarti tidak diklasifikasikan.
         */
        val errorCode: Int = 0,
        /**
         * Handle instance native FreeRDP saat gagal — "ID koneksi" untuk
         * mencocokkan dialog dengan baris log. Nol berarti tidak tersedia.
         */
        val connectionId: Long = 0L,
    ) : SessionState()
}
