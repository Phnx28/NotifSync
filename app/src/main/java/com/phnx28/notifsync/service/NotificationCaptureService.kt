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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { notification ->
            try {
                val packageName = notification.packageName
                val appLabel = getApplicationLabel(packageName)
                val extras = notification.notification.extras

                val title = extras.getCharSequence("android.title")?.toString() ?: ""
                val body = extras.getCharSequence("android.text")?.toString() ?: ""
                val bigText = extras.getCharSequence("android.bigText")?.toString()
                val displayText = bigText ?: body

                if (displayText.isBlank() && title.isBlank()) return

                val event = NotificationEvent(
                    app_name = appLabel,
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
