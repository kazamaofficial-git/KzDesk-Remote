package com.kazamatech.kzdesk.ui

import android.content.Context

/**
 * M2.5 — "Percaya & ingat" sertifikat server.
 *
 * Menyimpan fingerprint SHA-256 per `host:port` di SharedPreferences.
 *  - Fingerprint sama pada koneksi berikutnya = auto-approve (tanpa dialog)
 *  - Fingerprint BERBEDA dengan yang tersimpan = dialog dengan warning
 *    kuat (kemungkinan MITM / server ganti cert)
 *
 * Safe default tetap terjaga: TIDAK ada auto-approve tanpa fingerprint
 * yang pernah disimpan pengguna secara eksplisit.
 */
class CertificateTrustStore(context: Context) {

    private val sp =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Fingerprint tersimpan untuk host:port, null jika belum pernah dipercaya. */
    fun fingerprint(host: String, port: Int): String? = sp.getString(key(host, port), null)

    fun trust(host: String, port: Int, fingerprint: String) {
        sp.edit().putString(key(host, port), fingerprint).apply()
    }

    fun remove(host: String, port: Int) {
        sp.edit().remove(key(host, port)).apply()
    }

    /** Semua entri yang pernah dipercaya: (host:port) -> fingerprint. */
    fun entries(): List<Pair<String, String>> {
        val prefix = "fp."
        return sp.all.entries
            .filter { it.key.startsWith(prefix) }
            .map { it.key.removePrefix(prefix) to (it.value as String) }
            .sortedBy { it.first }
    }

    /**
     * Hapus satu entri memakai kunci persis seperti balikan [entries]
     * ("host:port" — string mentah dipakai apa adanya supaya IPv6 benar).
     */
    fun removeKey(hostPort: String) {
        sp.edit().remove("fp.$hostPort").apply()
    }

    fun clear() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("fp.") }.forEach { e.remove(it) }
        e.apply()
    }

    companion object {
        private const val NAME = "kzdesk.certtrust"
        private fun key(host: String, port: Int) = "fp.$host:$port"
    }
}
