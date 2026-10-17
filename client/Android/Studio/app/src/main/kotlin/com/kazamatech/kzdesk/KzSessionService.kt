package com.kazamatech.kzdesk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import com.kazamatech.kzdesk.ui.SpotifyMediaBridge
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kazamatech.kzdesk.ui.kzNow
import com.kazamatech.kzdesk.ui.KzDeskHomeActivity
import com.kazamatech.kzdesk.ui.KzSessionRegistry

/**
 * Aksi notifikasi diarahkan ke registry sesi dalam proses ini.
 */
/**
 * Foreground service sesi KzDesk.
 *
 * Kenapa ada: Android mematikan proses di latar kalau tidak ada service yang
 * berjalan di depan. Sesi RDP adalah pekerjaan yang sedang dilihat user, jadi
 * statusnya dinaikkan ke foreground + notifikasi yang jelas (bukan tersembunyi
 * — user harus bisa memutuskan sesi dari notifikasi).
 *
 * Tipe `specialUse`: sesuai definisi Android, untuk kasus yang tidak masuk
 * kategori bawaan (sesi remote desktop interaktif yang jalan terus di latar).
 * Aplikasi ini di-sideload, jadi tidak ada ketergantungan kebijakan store.
 */
class KzSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /** Kapan sesi dimulai; dipakai kronometer di notifikasi. */
    private var startedAt = 0L

    override fun onCreate() {
        super.onCreate()
        startedAt = System.currentTimeMillis()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (KzSessionRegistry.count() == 0) stopSelf()
                else KzSessionRegistry.disconnectAll()
                return START_NOT_STICKY
            }

            ACTION_PING -> {
                // Sudah jalan: cukup perbarui teksnya.
                startForeground(NOTIF_ID, buildNotification(intent.getStringExtra(EXTRA_LABEL)))
                return START_NOT_STICKY
            }

            // Tombol notifikasi tambahan (permintaan pemilik 2026-10-10):
            // Beranda tanpa memutus sesi + jeda/putar musik dari notifikasi.
            ACTION_HOME -> {
                runCatching {
                    startActivity(
                        Intent(this, KzDeskHomeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                return START_NOT_STICKY
            }

            ACTION_MUSIC -> {
                runCatching { SpotifyMediaBridge.playPause() }
                return START_NOT_STICKY
            }

            else -> {
                startForeground(NOTIF_ID, buildNotification(intent?.getStringExtra(EXTRA_LABEL)))
                return START_NOT_STICKY
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            kzNow("Sesi aktif", "Active session"),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = kzNow(
                "Notifikasi selama sesi remote berjalan di latar",
                "Notification while a remote session runs in the background",
            )
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(label: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, KzDeskHomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, KzSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val home = PendingIntent.getService(
            this,
            2,
            Intent(this, KzSessionService::class.java).setAction(ACTION_HOME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val music = PendingIntent.getService(
            this,
            3,
            Intent(this, KzSessionService::class.java).setAction(ACTION_MUSIC),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val host = label ?: "KzDesk Remote"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            // Ikon app sendiri (mark monokrom) — dulu pakai
            // android.R.drawable.stat_sys_upload_done, ikon sistem panah
            // upload yang tidak ada hubungannya dengan remote desktop.
            .setSmallIcon(com.kazamatech.kzdesk.R.drawable.ic_launcher_monochrome)
            .setContentTitle(kzNow("Sesi remote aktif", "Remote session active"))
            .setContentText(host)
            .setSubText(versionLabel())
            .setContentIntent(open)
            .addAction(0, kzNow("Buka", "Open"), open)
            .addAction(0, kzNow("Beranda", "Home"), home)
            .addAction(0, kzNow("Musik", "Music"), music)
            .addAction(0, kzNow("Putuskan", "Disconnect"), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(host))

        // Tampilan penuh warna: hanya berlaku untuk foreground service, dan
        // service ini memang foreground. Tanpa ini notifikasi memakai kartu
        // putih/abu bawaan Android yang tidak mirip app sama sekali.
        builder.setColor(NOTIF_ACCENT)
            .setColorized(true)

        // Kronometer = lama sesi berjalan, tanpa perlu memperbarui notifikasi.
        if (startedAt > 0L) {
            builder.setWhen(startedAt).setUsesChronometer(true)
        }

        // Ikon besar di sisi kiri. Dipakai PNG (bukan vektor) karena
        // BitmapFactory tidak membaca drawable vektor.
        runCatching {
            BitmapFactory.decodeResource(resources, com.kazamatech.kzdesk.R.drawable.kz_mark_white)
        }.getOrNull()?.let { builder.setLargeIcon(it) }

        // Layar kunci tidak menampilkan host yang sedang disambungkan.
        builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(com.kazamatech.kzdesk.R.drawable.ic_launcher_monochrome)
                    .setContentTitle(kzNow("Sesi remote aktif", "Remote session active"))
                    .setContentText("KzDesk Remote")
                    .setColor(NOTIF_ACCENT)
                    .setColorized(true)
                    .build(),
            )

        return builder.build()
    }

    /** `v1.1.1 (63)` untuk subteks notifikasi; fallback kalau tidak terbaca. */
    private fun versionLabel(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "v${info.versionName} ($code)"
    }.getOrDefault("KzDesk Remote")

    companion object {
        /** Warna aksen monokrom app, dipakai notifikasi berwarna. */
        private const val NOTIF_ACCENT = 0xFF111114.toInt()

        private const val CHANNEL_ID = "kzdesk_sesi_aktif"
        private const val NOTIF_ID = 4711
        const val ACTION_STOP = "com.kazamatech.kzdesk.action.STOP_SESSION"
        const val ACTION_PING = "com.kazamatech.kzdesk.action.PING_SESSION"
        const val ACTION_HOME = "com.kazamatech.kzdesk.action.GO_HOME"
        const val ACTION_MUSIC = "com.kazamatech.kzdesk.action.MUSIC_TOGGLE"
        private const val EXTRA_LABEL = "label"

        /** Mulai/perbarui service. Aman dipanggil berulang. */
        fun start(context: Context, label: String?, ping: Boolean = false) {
            val intent = Intent(context, KzSessionService::class.java).apply {
                putExtra(EXTRA_LABEL, label)
                if (ping) action = ACTION_PING
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, KzSessionService::class.java))
            }
        }
    }
}
