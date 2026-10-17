package com.kazamatech.kzdesk.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.kazamatech.kzdesk.R
import com.kazamatech.kzdesk.core.ConnectionProfile
import com.kazamatech.kzdesk.directory.DeviceDirectoryParser
import com.kazamatech.kzdesk.sessions.SessionsRepository
import com.kazamatech.kzdesk.ui.KzDeskHomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Widget layar utama: sampai 4 perangkat tersimpan, satu ketukan = sambung.
 * Ketukan membuka app dengan [EXTRA_PROFILE_ID]; app lalu menjalankan alur sambung biasa
 * (termasuk kunci sidik jari bila diaktifkan).
 */
class KzFavoritesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val profiles = runCatching { SessionsRepository(context.applicationContext).favorites().first() }
                    .getOrDefault(emptyList())
                    .take(SLOTS.size)
                val views = build(context, profiles)
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "com.kazamatech.kzdesk.extra.CONNECT_PROFILE_ID"
        const val ACTION_CONNECT = "com.kazamatech.kzdesk.CONNECT"

        private val SLOTS = intArrayOf(R.id.kz_w_1, R.id.kz_w_2, R.id.kz_w_3, R.id.kz_w_4)

        /** Nama yang ramah: label, atau ID Device berformat untuk perangkat mode ID. */
        fun displayName(p: ConnectionProfile): String {
            p.label?.takeIf { it.isNotBlank() }?.let { return it }
            val m = Regex("^id-(\\d{9})\\.kzdesk\\.invalid$").find(p.host)
            return if (m != null) "PC " + DeviceDirectoryParser.formatId(m.groupValues[1]) else p.host
        }

        fun build(context: Context, profiles: List<ConnectionProfile>): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_favorites)
            rv.setOnClickPendingIntent(R.id.kz_w_title, openApp(context, null))
            SLOTS.forEachIndexed { i, viewId ->
                val p = profiles.getOrNull(i)
                if (p == null) {
                    rv.setViewVisibility(viewId, View.GONE)
                } else {
                    rv.setViewVisibility(viewId, View.VISIBLE)
                    rv.setTextViewText(viewId, displayName(p))
                    rv.setOnClickPendingIntent(viewId, openApp(context, p.id))
                }
            }
            rv.setViewVisibility(R.id.kz_w_empty, if (profiles.isEmpty()) View.VISIBLE else View.GONE)
            return rv
        }

        private fun openApp(context: Context, profileId: String?): PendingIntent {
            val intent = Intent(context, KzDeskHomeActivity::class.java)
                .setAction(ACTION_CONNECT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (profileId != null) {
                intent.putExtra(EXTRA_PROFILE_ID, profileId)
                intent.data = Uri.parse("kzdesk://connect/" + Uri.encode(profileId))   // membuat PendingIntent unik per perangkat
            }
            return PendingIntent.getActivity(
                context, profileId?.hashCode() ?: 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Minta widget menggambar ulang (dipanggil saat daftar perangkat berubah). */
        fun requestUpdate(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, KzFavoritesWidget::class.java))
            if (ids.isEmpty()) return
            app.sendBroadcast(
                Intent(app, KzFavoritesWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}
