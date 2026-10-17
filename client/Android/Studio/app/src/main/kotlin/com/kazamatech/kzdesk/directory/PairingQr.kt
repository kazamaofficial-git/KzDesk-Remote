package com.kazamatech.kzdesk.directory

import java.net.URLDecoder
import java.net.URLEncoder

data class PairingPayload(val id: String, val password: String)

/**
 * Isi QR dari KzDesk Host: `kzdesk://pair?v=1&id=123456789&pw=<password>`.
 * Hanya format ini yang diterima; selain itu ditolak agar QR sembarang tidak mengisi form.
 */
object PairingQr {
    private const val PREFIX = "kzdesk://pair?"

    fun parse(raw: String?): PairingPayload? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > 512 || !text.startsWith(PREFIX, ignoreCase = true)) return null
        val params = text.substring(PREFIX.length).split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to runCatching { URLDecoder.decode(part.substring(i + 1), "UTF-8") }.getOrDefault("")
        }.toMap()
        if ((params["v"] ?: "1") != "1") return null
        val id = DeviceDirectoryParser.normalizeId(params["id"]) ?: return null
        val pw = params["pw"].orEmpty()
        if (pw.length !in 4..128) return null
        return PairingPayload(id, pw)
    }

    fun build(id: String, password: String): String =
        PREFIX + "v=1&id=" + id + "&pw=" + URLEncoder.encode(password, "UTF-8")
}
