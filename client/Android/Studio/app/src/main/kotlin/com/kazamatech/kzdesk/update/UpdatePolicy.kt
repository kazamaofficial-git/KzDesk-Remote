package com.kazamatech.kzdesk.update

import com.kazamatech.kzdesk.KzConfig
import org.json.JSONObject
import java.net.URI

/** Hasil `GET /api/update/app`. */
data class UpdateInfo(
    val available: Boolean,
    val mandatory: Boolean,
    val version: String,
    val versionCode: Long,
    val notes: String,
    val releaseUrl: String,
    val abi: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** Logika murni (tanpa Android) supaya bisa diuji di JVM. */
object UpdatePolicy {
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    const val MAX_APK_BYTES = 300L * 1024 * 1024

    private val SHA256_RE = Regex("^[0-9a-f]{64}$")

    fun parse(body: String): UpdateInfo? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (o.optString("status") != "ok") return null
        val asset = o.optJSONObject("asset")
        val info = UpdateInfo(
            available = o.optBoolean("update_available", false) && asset != null,
            mandatory = o.optBoolean("mandatory", false),
            version = o.optString("version", ""),
            versionCode = o.optLong("version_code", 0L),
            notes = o.optString("notes", ""),
            releaseUrl = o.optString("release_url", ""),
            abi = asset?.optString("abi", "").orEmpty(),
            url = asset?.optString("url", "").orEmpty(),
            sha256 = asset?.optString("sha256", "").orEmpty().lowercase(),
            sizeBytes = asset?.optLong("size", 0L) ?: 0L,
        )
        // Update yang tidak bisa diverifikasi tidak pernah dianggap tersedia.
        if (info.available && (!isTrustedDownloadUrl(info.url) || !isValidSha256(info.sha256))) {
            return info.copy(available = false)
        }
        return info
    }

    fun isValidSha256(value: String): Boolean = SHA256_RE.matches(value.lowercase())

    /** Hanya HTTPS dari GitHub (release asset) atau server KzDesk sendiri. */
    fun isTrustedDownloadUrl(url: String, apiBase: String = KzConfig.API_BASE): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        if (uri.userInfo != null) return false
        val own = runCatching { URI(apiBase).host?.lowercase() }.getOrNull()
        return host == own ||
            host == "github.com" || host.endsWith(".github.com") ||
            host == "githubusercontent.com" || host.endsWith(".githubusercontent.com")
    }

    fun shouldCheck(lastCheckMs: Long, nowMs: Long, force: Boolean): Boolean =
        force || lastCheckMs <= 0L || nowMs - lastCheckMs >= CHECK_INTERVAL_MS || nowMs < lastCheckMs

    /** Urutan ABI preferensi perangkat untuk query `abi=`. */
    fun abiQuery(supportedAbis: Array<String>): String =
        supportedAbis.filter { it == "arm64-v8a" || it == "armeabi-v7a" || it == "x86_64" }.joinToString(",")
}
