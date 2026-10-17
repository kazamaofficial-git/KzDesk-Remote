package com.kazamatech.kzdesk.security

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Laporan crash OPT-IN. Tidak mengirim apa pun kecuali pengguna menyalakannya.
 * Yang dikirim: versi app, versi Android, merek+model HP, dan stack trace terakhir yang
 * sudah disamarkan (IP, e-mail, dan angka 9 digit seperti ID Device diganti penanda).
 * Panggil [sendPending] dari thread latar.
 */
object CrashReporter {
    private const val PREFS = "kzdesk_crash_reporter"
    private const val KEY_OPT_IN = "opt_in"
    private const val KEY_LAST = "last_sent_hash"
    private const val MAX_STACK = 8000

    private val IPV4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val NINE_DIGITS = Regex("\\b\\d{9}\\b")
    private val INVALID_HOST = Regex("id-<id>\\.kzdesk\\.invalid")

    fun isOptedIn(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OPT_IN, false)

    fun setOptedIn(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OPT_IN, value).apply()
    }

    /** Samarkan data yang bisa mengidentifikasi sebelum keluar dari perangkat. */
    fun redact(text: String): String =
        text.replace(EMAIL, "<email>").replace(IPV4, "<ip>").replace(NINE_DIGITS, "<id>").replace(INVALID_HOST, "id-<id>.invalid")
            .take(MAX_STACK)

    /** @return true bila ada laporan baru yang terkirim. */
    fun sendPending(context: Context, apiBase: String, appVersion: String): Boolean {
        val app = context.applicationContext
        if (!isOptedIn(app)) return false
        val raw = CrashLog.last(app)?.takeIf { it.isNotBlank() } ?: return false
        val body = redact(raw)
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST, null) == hash) return false   // sudah pernah dikirim
        val json = JSONObject()
            .put("app_version", appVersion)
            .put("android", Build.VERSION.SDK_INT)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("stack", body)
        val conn = (URL(apiBase.trimEnd('/') + "/api/crash").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 8000; readTimeout = 8000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            val ok = conn.responseCode in 200..299
            if (ok) prefs.edit().putString(KEY_LAST, hash).apply()
            ok
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
}
