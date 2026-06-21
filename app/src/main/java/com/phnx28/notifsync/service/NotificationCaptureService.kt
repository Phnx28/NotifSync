package com.phnx28.notifsync.service

import android.app.Notification
import android.content.Intent
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.google.gson.Gson
import com.phnx28.notifsync.data.model.NotificationEvent
import com.phnx28.notifsync.network.WebSocketServer

class NotificationCaptureService : NotificationListenerService() {

    private val TAG = "NotificationCaptureService"
    private var webSocketServer: WebSocketServer? = null
    private val gson = Gson()

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NotificationCaptureService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "NotificationCaptureService destroyed")
    }

    // Track recently sent events to deduplicate rapid updates
    private val recentEvents = LinkedHashMap<String, Long>(100, 0.75f, true)
    private val DEDUP_WINDOW_MS = 2000L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { notification ->
            try {
                val packageName = notification.packageName

                // Skip our own notifications to prevent loops
                if (packageName == this.packageName) return

                // Skip group summary notifications (e.g., "3 new messages")
                val flags = notification.notification.flags
                if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return

                // Skip ongoing notifications (media players, navigation, downloads)
                if (flags and Notification.FLAG_ONGOING_EVENT != 0) return

                val appLabel = getApplicationLabel(packageName)
                val extras = notification.notification.extras

                val title = extras.getCharSequence("android.title")?.toString() ?: ""
                val body = extras.getCharSequence("android.text")?.toString() ?: ""
                val bigText = extras.getCharSequence("android.bigText")?.toString()
                val displayText = bigText ?: body

                if (displayText.isBlank() && title.isBlank()) return

                // Deduplicate rapid updates with same content from same app
                val dedupKey = "$packageName|$title|$displayText"
                val now = System.currentTimeMillis()
                val lastSent = recentEvents[dedupKey]
                if (lastSent != null && now - lastSent < DEDUP_WINDOW_MS) return
                recentEvents[dedupKey] = now

                // Prune old entries to prevent memory growth
                if (recentEvents.size > 200) {
                    val cutoff = now - DEDUP_WINDOW_MS * 5
                    recentEvents.entries.removeAll { it.value < cutoff }
                }

                val event = NotificationEvent(
                    appName = appLabel,
                    sender = "",
                    title = title,
                    body = displayText,
                    timestamp = notification.postTime,
                    type = NotificationEvent.TYPE_NOTIFICATION
                )

                val json = gson.toJson(event)
                broadcastEvent(json)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing notification", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not needed - we keep notifications in history
    }

    private fun broadcastEvent(json: String) {
        val intent = Intent(ACTION_BROADCAST_EVENT).apply {
            putExtra(EXTRA_EVENT_JSON, json)
            `package` = packageName
        }
        sendBroadcast(intent)
    }

    private fun getApplicationLabel(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    fun setWebSocketServer(server: WebSocketServer) {
        webSocketServer = server
    }

    companion object {
        const val ACTION_BROADCAST_EVENT = "com.phnx28.notifsync.BROADCAST_EVENT"
        const val EXTRA_EVENT_JSON = "event_json"
    }
}
