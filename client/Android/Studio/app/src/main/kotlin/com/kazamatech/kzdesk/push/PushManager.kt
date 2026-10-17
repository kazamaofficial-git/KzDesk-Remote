package com.kazamatech.kzdesk.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.PackageInfoCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.kazamatech.kzdesk.KzConfig
import com.kazamatech.kzdesk.R
import com.kazamatech.kzdesk.ui.KzDeskHomeActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Push notification (FCM). Opt-in: tidak ada token yang dikirim ke server sebelum pengguna
 * menyalakannya. Build tanpa `google-services.json` tetap berjalan; push hanya tidak tersedia.
 */
object PushManager {
    const val CHANNEL_UPDATES = "kz_updates"
    const val CHANNEL_ANNOUNCEMENTS = "kz_announcements"
    const val CHANNEL_DEVICES = "kz_devices"

    private const val PREFS = "kzdesk_push"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TOKEN = "token"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** Firebase hanya terinisialisasi bila build memuat google-services.json. */
    fun isAvailable(context: Context): Boolean =
        runCatching { FirebaseApp.getApps(context.applicationContext).isNotEmpty() }.getOrDefault(false)

    fun canShowNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_UPDATES, "Pembaruan", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ANNOUNCEMENTS, "Pengumuman", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DEVICES, "Status PC", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** @return status akhir (true = push aktif dan terdaftar di server). */
    suspend fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val app = context.applicationContext
        if (!enabled) {
            val token = prefs(app).getString(KEY_TOKEN, null)
            prefs(app).edit().putBoolean(KEY_ENABLED, false).remove(KEY_TOKEN).apply()
            if (token != null) post("/api/push/unregister", JSONObject().put("token", token))
            if (isAvailable(app)) runCatching { fetchOrDeleteToken(delete = true) }
            return false
        }
        if (!isAvailable(app)) return false
        ensureChannels(app)
        val token = runCatching { fetchOrDeleteToken(delete = false) }.getOrNull() ?: return false
        prefs(app).edit().putBoolean(KEY_ENABLED, true).putString(KEY_TOKEN, token).apply()
        return register(app, token)
    }

    /** Dipanggil dari FirebaseMessagingService.onNewToken. */
    suspend fun onNewToken(context: Context, token: String) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        prefs(app).edit().putString(KEY_TOKEN, token).apply()
        register(app, token)
    }

    /** Daftar ulang diam-diam saat aplikasi dibuka (token bisa berganti). */
    suspend fun refreshIfEnabled(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app) || !isAvailable(app)) return
        ensureChannels(app)
        val token = runCatching { fetchOrDeleteToken(delete = false) }.getOrNull() ?: return
        prefs(app).edit().putString(KEY_TOKEN, token).apply()
        register(app, token)
    }

    /** Langganan status satu PC (butuh ID + password yang benar). Dipanggil setelah koneksi mode ID berhasil. */
    suspend fun subscribeDevice(context: Context, deviceId: String, password: String): Boolean {
        val app = context.applicationContext
        val token = prefs(app).getString(KEY_TOKEN, null) ?: return false
        if (!isEnabled(app)) return false
        return post(
            "/api/push/subscribe-device",
            JSONObject().put("token", token).put("id", deviceId).put("password", password),
        ) == 200
    }

    private suspend fun register(app: Context, token: String): Boolean {
        val version = runCatching {
            PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0)).toString()
        }.getOrDefault("")
        val lang = if (Locale.getDefault().language == "en") "en" else "id"
        val body = JSONObject()
            .put("token", token).put("platform", "android").put("lang", lang).put("app_version", version)
            .put("topics", JSONObject().put("updates", true).put("announcements", true))
        return post("/api/push/register", body) == 200
    }

    private suspend fun fetchOrDeleteToken(delete: Boolean): String? = suspendCancellableCoroutine { cont ->
        val fm = FirebaseMessaging.getInstance()
        if (delete) {
            fm.deleteToken().addOnCompleteListener { cont.resume(null) }
        } else {
            fm.token.addOnCompleteListener { t -> cont.resume(if (t.isSuccessful) t.result else null) }
        }
    }

    private suspend fun post(path: String, json: JSONObject): Int = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(KzConfig.API_BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 10_000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(-1)
    }

    /** Tampilkan notifikasi sendiri (dipakai saat pesan datang ketika aplikasi di latar depan). */
    fun show(context: Context, title: String, body: String, channel: String) {
        if (!canShowNotifications(context)) return
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, KzDeskHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n) }
    }
}
