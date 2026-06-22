package com.phnx28.notifsync.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.phnx28.notifsync.R

object NotificationHelper {

    private const val TAG = "NotificationHelper"
    private const val CHANNEL_ID_SENDER = "sender_service"
    private const val CHANNEL_ID_RECEIVER = "receiver_service"
    private const val CHANNEL_ID_MIRRORED = "mirrored_notifications"

    fun createSenderChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID_SENDER,
            context.getString(R.string.sender_fg_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Foreground service for notification broadcasting"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun createReceiverChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID_RECEIVER,
            context.getString(R.string.receiver_fg_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Foreground service for receiving notifications"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun createMirroredChannel(context: Context, appName: String): String {
        val channelId = "mirrored_${appName.lowercase().replace(" ", "_")}"
        val channel = NotificationChannel(
            channelId,
            appName,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Mirrored notifications from $appName"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
        return channelId
    }

    fun buildSenderNotification(context: Context): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_ID_SENDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.sender_fg_notification_title))
            .setContentText(context.getString(R.string.sender_fg_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
    }

    fun buildReceiverNotification(context: Context): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_ID_RECEIVER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.receiver_fg_notification_title))
            .setContentText(context.getString(R.string.receiver_fg_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
    }

    fun postMirroredNotification(
        context: Context,
        appName: String,
        sender: String,
        title: String,
        body: String,
        notificationId: Int
    ) {
        val channelId = createMirroredChannel(context, appName)

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$appName - $sender")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(appName)
            .build()

        // POST_NOTIFICATIONS may have been denied on Android 13+ — don't
        // crash, just log (AUDIT.md M-03).
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS denied — mirrored notification suppressed", e)
        }
    }
}
