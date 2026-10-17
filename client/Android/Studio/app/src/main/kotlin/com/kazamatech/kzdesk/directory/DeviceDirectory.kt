package com.kazamatech.kzdesk.directory

import com.kazamatech.kzdesk.KzConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** Satu alamat yang mungkin menuju PC (urutan dicoba: lan → vpn → public). */
data class DirectoryCandidate(val kind: String, val host: String, val port: Int)

enum class DirectoryError { WRONG_ID_OR_PASSWORD, OFFLINE, RATE_LIMITED, INVALID_INPUT, NETWORK, SERVER, UNREACHABLE }

sealed interface DirectoryResult {
    data class Found(
        val displayId: String,
        val hostname: String,
        val username: String,
        val candidates: List<DirectoryCandidate>,
    ) : DirectoryResult

    data class Failed(val error: DirectoryError, val retryAfterMs: Long = 0L) : DirectoryResult
}

/** Alamat yang siap dipakai untuk menyambung RDP. */
data class DirectoryRoute(val host: String, val port: Int, val username: String, val kind: String, val displayId: String)

/** Logika murni (tanpa Android/jaringan) supaya mudah diuji di JVM. */
object DeviceDirectoryParser {
    private val KIND_ORDER = listOf("lan", "vpn", "public")

    /** "123-456-789" / "123456789" -> "123456789", atau null. Digit pertama tidak boleh 0. */
    fun normalizeId(input: String?): String? {
        val digits = input.orEmpty().filter { !it.isWhitespace() && it != '-' }
        return if (digits.length == 9 && digits.all { it in '0'..'9' } && digits[0] != '0') digits else null
    }

    fun formatId(id: String): String = if (id.length == 9) "${id.substring(0, 3)}-${id.substring(3, 6)}-${id.substring(6)}" else id

    fun parse(httpStatus: Int, body: String): DirectoryResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
        return when (httpStatus) {
            200 -> {
                if (json == null || json.optString("status") != "ok") return DirectoryResult.Failed(DirectoryError.SERVER)
                val arr = json.optJSONArray("candidates")
                val list = buildList {
                    if (arr != null) for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val host = o.optString("host")
                        val port = o.optInt("port", 3389)
                        if (host.isNotBlank() && port in 1..65535) add(DirectoryCandidate(o.optString("kind", "public"), host, port))
                    }
                }
                if (list.isEmpty()) DirectoryResult.Failed(DirectoryError.OFFLINE)
                else DirectoryResult.Found(
                    displayId = json.optString("display_id"),
                    hostname = json.optString("hostname"),
                    username = json.optString("username").ifBlank { KzConfig.DEVICE_USERNAME },
                    candidates = order(list),
                )
            }
            400 -> DirectoryResult.Failed(DirectoryError.INVALID_INPUT)
            404 -> DirectoryResult.Failed(DirectoryError.WRONG_ID_OR_PASSWORD)
            409 -> DirectoryResult.Failed(DirectoryError.OFFLINE)
            429 -> DirectoryResult.Failed(DirectoryError.RATE_LIMITED, json?.optLong("retry_after_ms", 0L) ?: 0L)
            else -> DirectoryResult.Failed(DirectoryError.SERVER)
        }
    }

    /** LAN dulu (paling cepat), lalu VPN, terakhir IP publik; urutan relatif tetap dipertahankan. */
    fun order(list: List<DirectoryCandidate>): List<DirectoryCandidate> =
        list.sortedBy { KIND_ORDER.indexOf(it.kind).let { i -> if (i < 0) KIND_ORDER.size else i } }

    fun message(error: DirectoryError, retryAfterMs: Long = 0L): Pair<String, String> = when (error) {
        DirectoryError.WRONG_ID_OR_PASSWORD -> "ID Device atau password salah." to "Wrong Device ID or password."
        DirectoryError.OFFLINE -> "PC sedang offline. Pastikan KzDesk Host menyala dan terhubung internet." to
            "The PC is offline. Make sure KzDesk Host is running and online."
        DirectoryError.RATE_LIMITED -> "Terlalu banyak percobaan. Coba lagi dalam ${(retryAfterMs / 1000).coerceAtLeast(1)} detik." to
            "Too many attempts. Try again in ${(retryAfterMs / 1000).coerceAtLeast(1)} s."
        DirectoryError.INVALID_INPUT -> "ID Device (9 digit) atau password tidak valid." to "Invalid Device ID (9 digits) or password."
        DirectoryError.NETWORK -> "Tidak bisa menghubungi server KzDesk. Periksa koneksi internet." to
            "Could not reach the KzDesk server. Check your internet connection."
        DirectoryError.SERVER -> "Server KzDesk sedang bermasalah. Coba lagi nanti." to "KzDesk server error. Try again later."
        DirectoryError.UNREACHABLE -> "PC ditemukan tetapi tidak bisa dijangkau (port RDP tertutup?). Coba lewat VPN atau buka port 3389." to
            "PC found but unreachable (RDP port closed?). Try a VPN or open port 3389."
    }
}

object DeviceDirectoryClient {
    /** Tanya server: ID + password -> daftar alamat. */
    suspend fun resolve(id: String, password: String): DirectoryResult = withContext(Dispatchers.IO) {
        val normalized = DeviceDirectoryParser.normalizeId(id) ?: return@withContext DirectoryResult.Failed(DirectoryError.INVALID_INPUT)
        if (password.length !in 4..128) return@withContext DirectoryResult.Failed(DirectoryError.INVALID_INPUT)
        try {
            val conn = (URL(KzConfig.API_BASE + "/api/device/resolve").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 8000; readTimeout = 12000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(JSONObject().put("id", normalized).put("password", password).toString().toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText().take(64 * 1024) }.orEmpty()
                DeviceDirectoryParser.parse(code, text)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            DirectoryResult.Failed(DirectoryError.NETWORK)
        }
    }

    /** Coba tiap kandidat secara berurutan dengan TCP connect singkat; ambil yang pertama terjangkau. */
    suspend fun pickReachable(found: DirectoryResult.Found, perTryMs: Int = 1500): DirectoryRoute? = withContext(Dispatchers.IO) {
        for (c in found.candidates) {
            val ok = runCatching { Socket().use { it.connect(InetSocketAddress(c.host, c.port), perTryMs) } }.isSuccess
            if (ok) return@withContext DirectoryRoute(c.host, c.port, found.username, c.kind, found.displayId)
        }
        null
    }

    /** resolve + pickReachable. Gagal dijangkau -> UNREACHABLE. */
    suspend fun resolveRoute(id: String, password: String): Pair<DirectoryRoute?, DirectoryResult.Failed?> {
        return when (val r = resolve(id, password)) {
            is DirectoryResult.Failed -> null to r
            is DirectoryResult.Found -> pickReachable(r)?.let { it to null } ?: (null to DirectoryResult.Failed(DirectoryError.UNREACHABLE))
        }
    }
}
