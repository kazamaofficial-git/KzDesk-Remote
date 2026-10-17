package com.kazamatech.kzdesk.core

/**
 * Info sertifikat yang diminta inti FreeRDP saat handshake TLS
 * (`OnVerifiyCertificateEx` — typo-nya memang dari upstream).
 *
 * UI menampilkan dialog trust dengan [fingerprint] SHA-256;
 * policy default KzDesk = selalu tanya (PLAN §5.2).
 */
data class CertificateInfo(
    val host: String,
    val port: Int,
    val commonName: String,
    val subject: String,
    val issuer: String,
    val fingerprint: String,
    val flags: Long,
) {
    val isChanged: Boolean get() = (flags and FLAG_CHANGED) != 0L
    val isMismatch: Boolean get() = (flags and FLAG_MISMATCH) != 0L
    val isGateway: Boolean get() = (flags and FLAG_GATEWAY) != 0L
    val isRedirect: Boolean get() = (flags and FLAG_REDIRECT) != 0L

    companion object {
        // Kontrak inti (SessionDialogs.showVerifyDialog -> native
        // android_verify_certificate_ex): 1 = diterima, 0 = DITOLAK.
        // (BUG M1.1: konstanta ini tadinya terbalik — diperbaiki di M2.)
        const val VERIFY_ACCEPT = 1
        const val VERIFY_DENY = 0

        // Flags dari inti (subset yang relevan untuk UI):
        const val FLAG_LEGACY = 0x02L
        const val FLAG_REDIRECT = 0x10L
        const val FLAG_GATEWAY = 0x20L
        const val FLAG_CHANGED = 0x40L
        const val FLAG_MISMATCH = 0x80L
    }
}
