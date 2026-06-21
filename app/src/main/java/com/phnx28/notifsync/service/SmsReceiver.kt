package com.phnx28.notifsync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.google.gson.Gson
import com.phnx28.notifsync.data.model.NotificationEvent

class SmsReceiver : BroadcastReceiver() {

    private val TAG = "SmsReceiver"
    private val gson = Gson()

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            messages?.forEach { smsMessage ->
                try {
                    val sender = smsMessage.displayOriginatingAddress ?: "Unknown"
                    val body = smsMessage.messageBody ?: ""
                    val timestamp = smsMessage.timestampMillis

                    val event = NotificationEvent(
                        app_name = "SMS",
                        sender = sender,
                        title = sender,
                        body = body,
                        timestamp = timestamp,
                        type = NotificationEvent.TYPE_SMS
                    )

                    val json = gson.toJson(event)
                    broadcastEvent(context, json)

                } catch (e: Exception) {
                    Log.e(TAG, "Error processing SMS", e)
                }
            }
        }
    }

    private fun broadcastEvent(context: Context?, json: String) {
        val intent = Intent(NotificationCaptureService.ACTION_BROADCAST_EVENT).apply {
            putExtra(NotificationCaptureService.EXTRA_EVENT_JSON, json)
        }
        context?.sendBroadcast(intent)
    }
}
