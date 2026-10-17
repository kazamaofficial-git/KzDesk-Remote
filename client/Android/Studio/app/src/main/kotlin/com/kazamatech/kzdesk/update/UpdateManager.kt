package com.kazamatech.kzdesk.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import com.kazamatech.kzdesk.KzConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Cek -> unduh -> verifikasi (SHA-256, nama paket, versionCode) -> pasang.
 *
 * Android tidak mengizinkan aplikasi biasa memasang APK tanpa persetujuan
 * pengguna, jadi "auto" berarti: deteksi otomatis, unduh otomatis (Wi-Fi/non-metered),
 * lalu satu ketukan untuk memasang. Pada Android 12+ pemasangan update dari
 * installer yang sama dapat berjalan tanpa dialog tambahan bila sistem mengizinkan.
 */
object UpdateManager {
    private const val PREFS = "kzdesk_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_AUTO = "auto_update"

    fun isAutoEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    fun currentVersionCode(context: Context): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    }.getOrDefault(0L)

    fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return !cm.isActiveNetworkMetered
    }

    // ------------------------------------------------------------ cek
    /** Memeriksa server. [force] mengabaikan jeda 6 jam. Aman dipanggil dari coroutine mana pun. */
    suspend fun check(context: Context, force: Boolean): UpdateInfo? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!UpdatePolicy.shouldCheck(prefs.getLong(KEY_LAST_CHECK, 0L), now, force)) return@withContext UpdateState.info
        UpdateState.phase = UpdatePhase.CHECKING
        try {
            val url = KzConfig.API_BASE + "/api/update/app?versionCode=" + currentVersionCode(app) +
                "&abi=" + Uri.encode(UpdatePolicy.abiQuery(Build.SUPPORTED_ABIS))
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "KzDeskRemote/${currentVersionCode(app)}")
            }
            val body = try {
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                conn.inputStream.bufferedReader().use { it.readText().take(64 * 1024) }
            } finally {
                conn.disconnect()
            }
            val info = UpdatePolicy.parse(body) ?: throw IOException("Respons server tidak valid")
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            UpdateState.info = info
            UpdateState.error = null
            UpdateState.phase = if (info.available) {
                // Sudah terunduh sebelumnya?
                val cached = cachedFile(app, info)
                if (cached != null) { UpdateState.readyFile = cached; UpdatePhase.READY } else UpdatePhase.AVAILABLE
            } else UpdatePhase.UP_TO_DATE
            info
        } catch (e: Exception) {
            UpdateState.error = e.message ?: e.javaClass.simpleName
            UpdateState.phase = UpdatePhase.ERROR
            null
        }
    }

    // --------------------------------------------------------- unduh
    private fun updatesDir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    private fun targetFile(context: Context, info: UpdateInfo) =
        File(updatesDir(context), "KzDesk-${info.version.filter { it.isLetterOrDigit() || it == '.' }}-${info.abi}.apk")

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cachedFile(context: Context, info: UpdateInfo): File? {
        val f = targetFile(context, info)
        return if (f.isFile && sha256Of(f) == info.sha256) f else null
    }

    /** Unduh APK, verifikasi hash, lalu pastikan itu memang update aplikasi ini. */
    suspend fun download(context: Context): File? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val info = UpdateState.info
        if (info == null || !info.available) return@withContext null
        try {
            UpdateState.phase = UpdatePhase.DOWNLOADING
            UpdateState.progress = 0f
            cachedFile(app, info)?.let {
                UpdateState.readyFile = it; UpdateState.phase = UpdatePhase.READY; return@withContext it
            }
            // Bersihkan APK lama sebelum menulis yang baru.
            updatesDir(app).listFiles()?.forEach { it.delete() }
            val target = targetFile(app, info)
            val part = File(target.path + ".part")
            val conn = (URL(info.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "KzDeskRemote")
            }
            val md = MessageDigest.getInstance("SHA-256")
            try {
                if (conn.responseCode != 200) throw IOException("Unduhan gagal: HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                if (total > UpdatePolicy.MAX_APK_BYTES) throw IOException("Ukuran berkas tidak wajar")
                var done = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n); out.write(buf, 0, n); done += n
                            if (done > UpdatePolicy.MAX_APK_BYTES) throw IOException("Ukuran berkas tidak wajar")
                            if (total > 0) UpdateState.progress = (done.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                }
                if (info.sizeBytes > 0 && done != info.sizeBytes) throw IOException("Ukuran berkas tidak cocok")
            } finally {
                conn.disconnect()
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (actual != info.sha256) { part.delete(); throw IOException("SHA-256 tidak cocok — unduhan dibuang") }
            if (!part.renameTo(target)) throw IOException("Gagal menyimpan APK")
            verifyApk(app, target, info)
            UpdateState.readyFile = target
            UpdateState.progress = 1f
            UpdateState.phase = UpdatePhase.READY
            target
        } catch (e: Exception) {
            UpdateState.error = e.message ?: e.javaClass.simpleName
            UpdateState.phase = UpdatePhase.ERROR
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, apk: File, info: UpdateInfo) {
        val pkg = context.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw IOException("APK tidak dapat dibaca")
        if (pkg.packageName != context.packageName) { apk.delete(); throw IOException("APK bukan untuk aplikasi ini") }
        if (PackageInfoCompat.getLongVersionCode(pkg) <= currentVersionCode(context)) {
            apk.delete(); throw IOException("APK bukan versi yang lebih baru")
        }
        if (info.versionCode > 0 && PackageInfoCompat.getLongVersionCode(pkg) != info.versionCode) {
            apk.delete(); throw IOException("Versi APK tidak sesuai manifest")
        }
    }

    /** Dipakai JobService: cek + unduh bila Wi-Fi/non-metered, lalu beri tahu pengguna. */
    suspend fun backgroundCheckAndDownload(context: Context): Boolean {
        val app = context.applicationContext
        if (!isAutoEnabled(app)) return false
        val info = check(app, force = false) ?: return false
        if (!info.available) return false
        if (UpdateState.phase != UpdatePhase.READY) {
            if (!isUnmetered(app)) return false
            download(app) ?: return false
        }
        UpdateNotifier.notifyReady(app, info)
        return true
    }

    // --------------------------------------------------------- pasang
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Pasang APK yang sudah terverifikasi lewat PackageInstaller. Hasil datang ke [UpdateInstallReceiver]. */
    fun install(context: Context, apk: File) {
        val app = context.applicationContext
        try {
            UpdateState.phase = UpdatePhase.INSTALLING
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("kzdesk.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(app, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
                session.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            UpdateState.error = e.message ?: e.javaClass.simpleName
            UpdateState.phase = UpdatePhase.ERROR
        }
    }
}

/** Menerima status dari PackageInstaller dan menampilkan dialog sistem bila perlu. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateState.phase = UpdatePhase.IDLE
            else -> {
                UpdateState.error = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Pemasangan gagal"
                UpdateState.phase = UpdatePhase.ERROR
            }
        }
    }

    companion object {
        const val ACTION = "com.kazamatech.kzdesk.UPDATE_INSTALL_RESULT"
    }
}
