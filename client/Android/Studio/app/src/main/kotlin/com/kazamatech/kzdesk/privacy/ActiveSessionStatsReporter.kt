package com.kazamatech.kzdesk.privacy

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Best-effort, anonymous lease reporter for the default-on, user-disableable session counter.
 * The only application payload is an action and a per-session random UUID.
 * Host/profile/account details are deliberately not accepted by this API.
 */
internal object ActiveSessionStatsReporter {
    private const val ENDPOINT = com.kazamatech.kzdesk.KzConfig.API_BASE + "/api/session"

    fun heartbeat(sessionId: UUID): Boolean = send("heartbeat", sessionId)

    fun end(sessionId: UUID): Boolean = send("end", sessionId)

    private fun send(action: String, sessionId: UUID): Boolean {
        val connection = try {
            URL(ENDPOINT).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return false
        }

        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject()
                .put("action", action)
                .put("session_id", sessionId.toString())
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            status in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}
