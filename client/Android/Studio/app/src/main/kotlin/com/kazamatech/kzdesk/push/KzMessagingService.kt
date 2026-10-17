package com.kazamatech.kzdesk.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Menerima token baru dan pesan FCM. Pesan saat latar belakang ditampilkan sistem sendiri. */
class KzMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { PushManager.onNewToken(applicationContext, token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Hanya dipanggil bila aplikasi di latar depan (payload `notification`), atau pesan data-only.
        val n = message.notification
        val title = n?.title ?: message.data["title"] ?: return
        val body = n?.body ?: message.data["body"] ?: return
        val channel = n?.channelId ?: when (message.data["kind"]) {
            "update" -> PushManager.CHANNEL_UPDATES
            "device" -> PushManager.CHANNEL_DEVICES
            else -> PushManager.CHANNEL_ANNOUNCEMENTS
        }
        PushManager.show(applicationContext, title, body, channel)
    }
}
