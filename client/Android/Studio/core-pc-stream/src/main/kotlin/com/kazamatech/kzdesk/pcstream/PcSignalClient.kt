package com.kazamatech.kzdesk.pcstream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Participant role is used only for redacted diagnostics; both roles use bearer auth. */
enum class PcSignalRole {
    HOST,
    VIEWER,
}

/**
 * Ephemeral credentials returned by the signaling service.
 * Do not persist or log [bearerToken]. It is intentionally omitted from toString().
 */
class PcSignalCredentials(
    val roomId: String,
    val bearerToken: String,
    val role: PcSignalRole,
) {
    override fun toString(): String =
        "PcSignalCredentials(roomId=$roomId, role=$role, bearerToken=[redacted])"
}

/** Host-side room result. The pairing code is single-use and omitted from toString(). */
class PcHostRoom(
    val credentials: PcSignalCredentials,
    val pairingCode: String,
    val expiresAtEpochMs: Long,
) {
    override fun toString(): String =
        "PcHostRoom(credentials=${credentials.roomId}, pairingCode=[redacted], expiresAtEpochMs=$expiresAtEpochMs)"
}

/** One SDP/ICE signaling message from the other peer. */
class PcSignalMessage(
    val sequence: Long,
    val from: String,
    val type: String,
    val payload: JSONObject?,
    val createdAtEpochMs: Long,
) {
    override fun toString(): String =
        "PcSignalMessage(sequence=$sequence, from=$from, type=$type, payload=[omitted])"
}

class PcSignalBatch(
    val cursor: Long,
    val latestSequence: Long,
    val events: List<PcSignalMessage>,
) {
    override fun toString(): String =
        "PcSignalBatch(cursor=$cursor, latestSequence=$latestSequence, eventCount=${events.size})"
}

class PcSignalHttpException(
    val httpStatus: Int,
    val serviceStatus: String?,
) : IOException("PC signaling request failed (HTTP $httpStatus${serviceStatus?.let { ", $it" } ?: ""})")

class PcSignalProtocolException(message: String) : IOException(message)

/**
 * HTTPS signaling client for the independent PC/Game Stream path.
 *
 * This module deliberately has no dependency on :core-rdp or FreeRDP. It only
 * exchanges bounded SDP/ICE JSON; media and remote input must use a separate
 * WebRTC PeerConnection/data channel and never pass through this service.
 */
class PcSignalClient(
    baseUrl: String = DEFAULT_BASE_URL,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {
    private val baseUrl: String = normalizeBaseUrl(baseUrl)

    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be positive" }
    }

    /** Create a host room and return its one-time pairing code and host bearer token. */
    suspend fun createRoom(): PcHostRoom = withContext(Dispatchers.IO) {
        val response = request("POST", "/api/pc/rooms", body = JSONObject())
        val roomId = requireRoomId(response.requiredString("room_id"))
        val hostToken = requireToken(response.requiredString("host_token"))
        val pairingCode = requirePairingCode(response.requiredString("pairing_code"))
        val expiresAt = response.requiredLong("expires_at")
        PcHostRoom(
            credentials = PcSignalCredentials(roomId, hostToken, PcSignalRole.HOST),
            pairingCode = pairingCode,
            expiresAtEpochMs = expiresAt,
        )
    }

    /** Join a host room with its out-of-band, single-use pairing code. */
    suspend fun joinRoom(roomId: String, pairingCode: String): PcSignalCredentials =
        withContext(Dispatchers.IO) {
            val safeRoomId = requireRoomId(roomId)
            val safeCode = requirePairingCode(pairingCode)
            val response = request(
                method = "POST",
                path = "/api/pc/rooms/$safeRoomId/join",
                body = JSONObject().put("pairing_code", safeCode),
            )
            PcSignalCredentials(
                roomId = safeRoomId,
                bearerToken = requireToken(response.requiredString("viewer_token")),
                role = PcSignalRole.VIEWER,
            )
        }

    /** Send one validated offer, answer, candidate, or bye message. */
    suspend fun postSignal(
        credentials: PcSignalCredentials,
        type: String,
        payload: JSONObject? = null,
    ): Long = withContext(Dispatchers.IO) {
        require(type in SIGNAL_TYPES) { "Unsupported signaling type" }
        val body = JSONObject()
            .put("type", type)
            .put("payload", payload ?: JSONObject.NULL)
        val response = request(
            method = "POST",
            path = "/api/pc/rooms/${requireRoomId(credentials.roomId)}/signal",
            body = body,
            bearerToken = requireToken(credentials.bearerToken),
        )
        response.requiredLong("seq").also { require(it > 0) { "Invalid sequence number" } }
    }

    /**
     * Fetch messages sent by the other participant after [afterSequence]. Polling
     * is for negotiation only; media packets must remain on the WebRTC peer path.
     */
    suspend fun pollSignals(
        credentials: PcSignalCredentials,
        afterSequence: Long = 0,
    ): PcSignalBatch = withContext(Dispatchers.IO) {
        require(afterSequence >= 0) { "afterSequence must not be negative" }
        val roomId = requireRoomId(credentials.roomId)
        val token = requireToken(credentials.bearerToken)
        val response = request(
            method = "GET",
            path = "/api/pc/rooms/$roomId/signal?after=$afterSequence",
            bearerToken = token,
        )
        val cursor = response.requiredLong("cursor")
        val latest = response.requiredLong("latest_seq")
        if (cursor < afterSequence || latest < 0) {
            throw PcSignalProtocolException("Invalid signaling cursor")
        }
        val rawEvents = response.optJSONArray("events")
            ?: throw PcSignalProtocolException("Missing signaling events")
        val events = rawEvents.toSignalMessages()
        PcSignalBatch(cursor, latest, events)
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        bearerToken: String? = null,
    ): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            if (bearerToken != null) setRequestProperty("Authorization", "Bearer $bearerToken")
        }

        try {
            if (body != null) {
                val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                if (bytes.size > MAX_REQUEST_BYTES) {
                    throw IllegalArgumentException("Signaling request exceeds the service limit")
                }
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBytes = stream?.use { readBounded(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
            val response = try {
                JSONObject(String(responseBytes, StandardCharsets.UTF_8))
            } catch (_: Exception) {
                throw PcSignalProtocolException("Signaling service returned invalid JSON")
            }
            if (status !in 200..299) {
                throw PcSignalHttpException(status, response.optString("status").takeIf { it.isNotBlank() })
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray.toSignalMessages(): List<PcSignalMessage> = buildList(length()) {
        for (index in 0 until length()) {
            val event = optJSONObject(index)
                ?: throw PcSignalProtocolException("Invalid signaling event")
            val sequence = event.optLong("seq", -1)
            val from = event.optString("from")
            val type = event.optString("type")
            val createdAt = event.optLong("created_at", -1)
            if (sequence <= 0 || from !in ROLES || type !in SIGNAL_TYPES || createdAt <= 0) {
                throw PcSignalProtocolException("Invalid signaling event fields")
            }
            val rawPayload = event.opt("payload")
            val payload = when (rawPayload) {
                null, JSONObject.NULL -> null
                is JSONObject -> rawPayload
                else -> throw PcSignalProtocolException("Invalid signaling payload")
            }
            add(PcSignalMessage(sequence, from, type, payload, createdAt))
        }
    }

    private fun JSONObject.requiredString(key: String): String =
        optString(key).takeIf { it.isNotBlank() && it != "null" }
            ?: throw PcSignalProtocolException("Missing signaling field: $key")

    private fun JSONObject.requiredLong(key: String): Long {
        val value = opt(key)
        val parsed = when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
        return parsed ?: throw PcSignalProtocolException("Invalid signaling field: $key")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://kzdesk.kazamasite.biz.id"
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000
        private const val DEFAULT_READ_TIMEOUT_MS = 8_000
        private const val MAX_REQUEST_BYTES = 24_576
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private val SIGNAL_TYPES = setOf("offer", "answer", "candidate", "bye")
        private val ROLES = setOf("host", "viewer")
        private val ROOM_ID_PATTERN =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
        private val PAIRING_CODE_PATTERN = Regex("^[A-Za-z0-9_-]{32}$")

        private fun normalizeBaseUrl(value: String): String {
            val parsed = try {
                URI(value.trim().trimEnd('/'))
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid signaling base URL")
            }
            val host = parsed.host?.removeSurrounding("[", "]")?.lowercase()
            val isLoopbackHttp = parsed.scheme.equals("http", ignoreCase = true) &&
                host in setOf("localhost", "127.0.0.1", "::1")
            require(parsed.scheme.equals("https", ignoreCase = true) || isLoopbackHttp) {
                "Signaling requires HTTPS (HTTP is allowed only for local tests)"
            }
            require(parsed.rawUserInfo == null) { "Base URL must not contain user-info" }
            require(parsed.rawQuery == null && parsed.rawFragment == null) {
                "Base URL must not contain a query or fragment"
            }
            require(parsed.path.isNullOrEmpty() || parsed.path == "/") {
                "Base URL must be an origin without a path"
            }
            require(host != null) { "Base URL must include a host" }
            return "${parsed.scheme}://${parsed.rawAuthority}"
        }

        private fun requireRoomId(value: String): String {
            require(ROOM_ID_PATTERN.matches(value)) { "Invalid PC signaling room ID" }
            return value.lowercase()
        }

        private fun requireToken(value: String): String {
            require(TOKEN_PATTERN.matches(value)) { "Invalid PC signaling bearer token" }
            return value
        }

        private fun requirePairingCode(value: String): String {
            require(PAIRING_CODE_PATTERN.matches(value)) { "Invalid PC pairing code" }
            return value
        }

        private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
            val output = ByteArrayOutputStream(minOf(limit, 8 * 1024))
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw PcSignalProtocolException("Signaling response exceeds the size limit")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
