package com.kazamatech.kzdesk.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Public, account-free community jokes API. Only a random install ID is sent for spam/reaction limits. */
internal class CommunityJokesApi(context: Context) {
    private val appContext = context.applicationContext
    private val clientId: String by lazy { loadOrCreateClientId() }

    suspend fun latest(): List<CommunityJoke> = withContext(Dispatchers.IO) {
        val body = request(
            method = "GET",
            path = "/api/jokes",
            headers = mapOf("X-KzDesk-Client-ID" to clientId),
        )
        val items = body.optJSONArray("items") ?: JSONArray()
        buildList(items.length()) {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let { add(parseJoke(it)) }
            }
        }
    }

    suspend fun submit(text: String): CommunityJoke = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("client_id", clientId).put("text", text)
        displayName()?.let { payload.put("display_name", it) }
        val body = request(method = "POST", path = "/api/jokes", payload = payload)
        parseJoke(body.optJSONObject("joke") ?: error("Missing submitted joke"))
    }

    suspend fun comment(jokeId: String, text: String): JokeComment = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("client_id", clientId).put("text", text)
        displayName()?.let { payload.put("display_name", it) }
        val body = request(method = "POST", path = "/api/jokes/$jokeId/comment", payload = payload)
        val c = body.optJSONObject("comment") ?: error("Missing comment")
        JokeComment(
            id = c.optString("id"),
            name = c.optString("name").takeIf { it.isNotBlank() },
            text = c.optString("text"),
            createdAtMillis = c.optLong("created_at", 0L),
        )
    }

    /** Nama tampilan server (dipulihkan setelah pasang ulang bila ID dipulihkan). */
    suspend fun fetchServerDisplayName(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = request(method = "GET", path = "/api/jokes/me", headers = mapOf("X-KzDesk-Client-ID" to clientId))
            body.optString("display_name").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    suspend fun react(jokeId: String, emoji: String): CommunityJoke = withContext(Dispatchers.IO) {
        val body = request(
            method = "POST",
            path = "/api/jokes/$jokeId/reaction",
            payload = JSONObject().put("client_id", clientId).put("emoji", emoji),
        )
        parseJoke(body.optJSONObject("joke") ?: error("Missing reacted joke"))
    }

    suspend fun report(jokeId: String): Boolean = withContext(Dispatchers.IO) {
        val body = request(
            method = "POST",
            path = "/api/jokes/$jokeId/report",
            payload = JSONObject().put("client_id", clientId),
        )
        body.optBoolean("hidden", false)
    }

    private fun request(
        method: String,
        path: String,
        payload: JSONObject? = null,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val connection = try {
            (URL("$ENDPOINT$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
        } catch (_: Exception) {
            throw CommunityJokesException("network")
        }

        return try {
            if (payload != null) {
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val body = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (statusCode !in 200..299) {
                throw CommunityJokesException(body.optString("status").ifBlank { "http_$statusCode" })
            }
            body
        } catch (error: CommunityJokesException) {
            throw error
        } catch (_: Exception) {
            throw CommunityJokesException("network")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJoke(value: JSONObject): CommunityJoke {
        val rawReactions = value.optJSONObject("reactions") ?: JSONObject()
        val reactions = buildMap {
            REACTIONS.forEach { emoji -> put(emoji, rawReactions.optInt(emoji, 0).coerceAtLeast(0)) }
            // Emoji di luar daftar (mis. dari klien masa depan) tetap ditampilkan.
            rawReactions.keys().forEach { emoji ->
                if (emoji !in this) put(emoji, rawReactions.optInt(emoji, 0).coerceAtLeast(0))
            }
        }
        val rawComments = value.optJSONArray("comments") ?: JSONArray()
        val comments = buildList(rawComments.length()) {
            for (i in 0 until rawComments.length()) {
                rawComments.optJSONObject(i)?.let { c ->
                    add(
                        JokeComment(
                            id = c.optString("id"),
                            name = c.optString("name").takeIf { it.isNotBlank() },
                            text = c.optString("text"),
                            createdAtMillis = c.optLong("created_at", 0L),
                        ),
                    )
                }
            }
        }
        return CommunityJoke(
            id = value.optString("id"),
            text = value.optString("text"),
            createdAtMillis = value.optLong("created_at", 0L),
            reactions = reactions,
            viewerReaction = value.optString("viewer_reaction").takeIf { it in REACTIONS },
            authorName = value.optString("author_name").takeIf { it.isNotBlank() },
            commentsTotal = value.optInt("comments_total", 0),
            comments = comments,
        )
    }

    // ---- akun komunitas ringan (guest) -------------------------------------
    private val prefs by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /** ID ramah pengguna, contoh: XY-7F3A9C. Turunan stabil dari client id. */
    fun friendlyAccountId(): String = "XY-" + clientId.replace("-", "").take(6).uppercase()

    fun displayName(): String? = prefs.getString(KEY_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() }

    fun setDisplayNameLocally(name: String?) {
        prefs.edit().putString(KEY_DISPLAY_NAME, name?.trim()?.takeIf { it.isNotBlank() }).apply()
    }

    suspend fun setDisplayName(name: String): Boolean = withContext(Dispatchers.IO) {
        val clean = name.trim().take(24)
        if (clean.isEmpty()) return@withContext false
        val ok = runCatching {
            request(
                method = "PUT",
                path = "/api/jokes/me",
                payload = JSONObject().put("client_id", clientId).put("display_name", clean),
            )
        }.isSuccess
        if (ok) setDisplayNameLocally(clean)
        ok
    }

    /** Kode pemulihan = client id penuh; dipakai setelah pasang ulang. */
    fun restoreCode(): String = clientId

    fun restoreClientId(code: String): Boolean {
        val clean = code.trim()
        if (runCatching { UUID.fromString(clean) }.isFailure) return false
        prefs.edit().putString(KEY_CLIENT_ID, clean).apply()
        return true
    }

    // ---- cache feed: buka tab tanpa layar "Memuat…" ------------------------
    fun cachedFeed(): List<CommunityJoke> = runCatching {
        val raw = prefs.getString(KEY_FEED_CACHE, null) ?: return@runCatching emptyList()
        val arr = JSONArray(raw)
        buildList(arr.length()) {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(parseJoke(it)) }
        }
    }.getOrDefault(emptyList())

    fun cacheFeed(items: List<CommunityJoke>) {
        val arr = JSONArray()
        items.take(20).forEach { j ->
            arr.put(
                JSONObject().apply {
                    put("id", j.id)
                    put("text", j.text)
                    put("created_at", j.createdAtMillis)
                    put("author_name", j.authorName)
                    put("comments_total", j.commentsTotal)
                    val reactions = JSONObject()
                    j.reactions.forEach { (e, n) -> reactions.put(e, n) }
                    put("reactions", reactions)
                    j.viewerReaction?.let { put("viewer_reaction", it) }
                    val comments = JSONArray()
                    j.comments.forEach { c ->
                        comments.put(JSONObject().apply {
                            put("id", c.id); put("name", c.name); put("text", c.text); put("created_at", c.createdAtMillis)
                        })
                    }
                    put("comments", comments)
                },
            )
        }
        prefs.edit().putString(KEY_FEED_CACHE, arr.toString()).apply()
    }

    private fun loadOrCreateClientId(): String {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_CLIENT_ID, null)?.let { existing ->
            if (runCatching { UUID.fromString(existing) }.isSuccess) return existing
        }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_CLIENT_ID, generated).apply()
        return generated
    }

    companion object {
        private const val ENDPOINT = com.kazamatech.kzdesk.KzConfig.API_BASE
        private const val PREFS_NAME = "kzdesk.community.jokes"
        private const val KEY_CLIENT_ID = "anonymous_client_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_FEED_CACHE = "feed_cache_v1"
        // Harus sama dengan COMMUNITY_JOKE_REACTIONS di worker.
        val REACTIONS = listOf(
            "😂", "😭", "💀", "🔥", "👍", "👎", "❤️", "🎉",
            "🤔", "😍", "🥳", "😎", "🙏", "👏", "💯", "🤣",
            "😡", "😱", "🥶", "🤯", "😴", "🍿", "⚡", "🏆",
        )
        val QUICK_REACTIONS = listOf("😂", "💀", "🔥", "👍", "❤️", "🎉")
    }
}

internal data class JokeComment(
    val id: String,
    val name: String?,
    val text: String,
    val createdAtMillis: Long,
)

internal data class CommunityJoke(
    val id: String,
    val text: String,
    val createdAtMillis: Long,
    val reactions: Map<String, Int>,
    val viewerReaction: String?,
    val authorName: String? = null,
    val commentsTotal: Int = 0,
    val comments: List<JokeComment> = emptyList(),
)

internal class CommunityJokesException(val reason: String) : Exception(reason)
