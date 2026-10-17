package com.kazamatech.kzdesk.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Musik online bawaan: pencarian SoundCloud tanpa akun.
 *
 * Tekniknya mengikuti cara pemutar sumber terbuka mengambil audio publik:
 * halaman soundcloud.com memuat berkas JS yang berisi `client_id` API web,
 * lalu endpoint pencarian `api-v2` dipanggil dengan id itu dan URL stream
 * progresif (MP3) diambil dari metadata trek. Tidak ada API key rahasia,
 * tidak ada login — hanya konten yang memang dipublikasikan untuk didengar.
 *
 * Semua fungsi parsing murni (String masuk, data keluar) supaya bisa diuji
 * unit tanpa jaringan; bagian jaringan hidup di [SoundCloudClient].
 */
data class ScTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val permalinkUrl: String,
)

/** Parser murni untuk HTML/JSON SoundCloud. Tanpa jaringan, tanpa Android. */
object SoundCloudParse {
    private val CLIENT_ID_RE = Regex("""client_id["']?\s*[=:]\s*["']?([A-Za-z0-9]{32})""")
    private val SCRIPT_SRC_RE = Regex("""<script[^>]+src="([^"]+)"""")

    /** Ambil client_id 32 karakter dari HTML halaman atau isi berkas JS. */
    fun extractClientId(text: String): String? =
        CLIENT_ID_RE.find(text)?.groupValues?.get(1)

    /** Daftar src <script> di halaman — kandidat tempat client_id bersembunyi. */
    fun scriptUrls(html: String): List<String> =
        SCRIPT_SRC_RE.findAll(html).map { it.groupValues[1] }.toList()

    /** Ubah JSON absolut jadi URL siap unduh. */
    fun absoluteUrl(src: String): String = when {
        src.startsWith("http") -> src
        src.startsWith("//") -> "https:$src"
        src.startsWith("/") -> "https://soundcloud.com$src"
        else -> "https://soundcloud.com/$src"
    }

    /**
     * Parsing hasil `/search/tracks`. Tiap entri koleksi membungkus trek di
     * objek `track`; entri rusak dilewati tanpa menggagalkan seluruh daftar.
     */
    fun parseSearch(json: String): List<ScTrack> = runCatching {
        val collection = JSONObject(json).optJSONArray("collection")
            ?: return@runCatching emptyList()
        (0 until collection.length()).mapNotNull { i ->
            val item = collection.optJSONObject(i) ?: return@mapNotNull null
            val track = item.optJSONObject("track") ?: item
            val id = track.optLong("id").takeIf { it > 0L } ?: return@mapNotNull null
            val title = track.optString("title").ifBlank { return@mapNotNull null }
            ScTrack(
                id = id,
                title = title,
                artist = track.optJSONObject("user")?.optString("username").orEmpty(),
                durationMs = track.optLong("duration"),
                permalinkUrl = track.optString("permalink_url"),
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Pilih URL transcoder dari metadata trek. Format progresif MP3
     * didahulukan karena langsung bisa diputar MediaPlayer tanpa HLS.
     */
    fun parseTranscodingUrl(json: String): String? = runCatching {
        val transcodings = JSONObject(json)
            .optJSONObject("media")
            ?.optJSONArray("transcodings")
            ?: return@runCatching null
        val items = (0 until transcodings.length())
            .mapNotNull { transcodings.optJSONObject(it) }
        val progressive = items.filter { t ->
            t.optJSONObject("format")?.optString("protocol") == "progressive"
        }
        val chosen = progressive.firstOrNull { t ->
            t.optJSONObject("format")?.optString("mime_type") == "audio/mpeg"
        } ?: progressive.firstOrNull()
        chosen?.optString("url")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Jawaban endpoint transcoder: JSON berisi satu field `url` CDN. */
    fun parseCdnUrl(json: String): String? = runCatching {
        JSONObject(json).optString("url").takeIf { it.isNotBlank() }
    }.getOrNull()
}

/** Bagian jaringan: HTTP polos lewat [HttpURLConnection], tanpa dependensi baru. */
object SoundCloudClient {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; KzDesk) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    @Volatile
    private var cachedClientId: String? = null

    private fun httpGet(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 12_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** client_id di-cache per proses; kalau gagal, dicoba ulang panggilan berikutnya. */
    suspend fun ensureClientId(): String? = withContext(Dispatchers.IO) {
        cachedClientId?.let { return@withContext it }
        val html = httpGet("https://soundcloud.com/") ?: return@withContext null
        val direct = SoundCloudParse.extractClientId(html)
        if (direct != null) {
            cachedClientId = direct
            return@withContext direct
        }
        for (src in SoundCloudParse.scriptUrls(html).take(12)) {
            val js = httpGet(SoundCloudParse.absoluteUrl(src)) ?: continue
            val found = SoundCloudParse.extractClientId(js)
            if (found != null) {
                cachedClientId = found
                return@withContext found
            }
        }
        null
    }

    /** Cari trek publik. Daftar kosong = jaringan gagal atau tak ada hasil. */
    suspend fun search(query: String): List<ScTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val cid = ensureClientId() ?: return@withContext emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = httpGet(
            "https://api-v2.soundcloud.com/search/tracks" +
                "?q=$q&client_id=$cid&limit=20&offset=0",
        ) ?: return@withContext emptyList()
        SoundCloudParse.parseSearch(json)
    }

    /** URL MP3 siap setel untuk satu trek, atau null kalau tidak tersedia. */
    suspend fun streamUrl(track: ScTrack): String? = withContext(Dispatchers.IO) {
        val cid = ensureClientId() ?: return@withContext null
        val meta = httpGet(
            "https://api-v2.soundcloud.com/tracks/${track.id}?client_id=$cid",
        ) ?: return@withContext null
        val transcoder = SoundCloudParse.parseTranscodingUrl(meta) ?: return@withContext null
        val sep = if (transcoder.contains('?')) '&' else '?'
        val cdnJson = httpGet("$transcoder${sep}client_id=$cid") ?: return@withContext null
        SoundCloudParse.parseCdnUrl(cdnJson)
    }
}
