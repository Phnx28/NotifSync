package com.phnx28.notifsync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import com.google.gson.Gson
import com.phnx28.notifsync.Constants
import com.phnx28.notifsync.data.model.NotificationEvent
import com.phnx28.notifsync.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    private val TAG = "SmsReceiver"
    private val gson = Gson()

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Use goAsync so the receiver doesn't ANR if anything slow is added
        // later (AUDIT.md L-05).
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages == null || messages.isEmpty()) {
                    return@launch
                }

                // Aggregate multi-part SMS: messages from the same sender
                // within a 5-second window are concatenated (AUDIT.md L-06).
                val grouped = messages.groupBy { it.displayOriginatingAddress ?: "Unknown" }
                val now = System.currentTimeMillis()

                for ((sender, parts) in grouped) {
                    try {
                        val body = parts.joinToString(separator = "") { it.messageBody ?: "" }
                            .take(Constants.MAX_BODY_LENGTH)

                        // Use the earliest PDU's timestamp as the canonical time.
                        val timestamp = parts.minOf { it.timestampMillis }
                        if (now - timestamp > 60_000L) continue // drop stale (>1min)

                        val event = NotificationEvent(
                            appName = "SMS",
                            sender = sender.take(Constants.MAX_SENDER_LENGTH),
                            title = sender.take(Constants.MAX_SENDER_LENGTH),
                            body = body,
                            timestamp = timestamp,
                            type = NotificationEvent.TYPE_SMS
                        )

                        val json = gson.toJson(event)
                        ServiceLocator.connectionRepository.emitEvent(json)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing SMS from $sender", e)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
