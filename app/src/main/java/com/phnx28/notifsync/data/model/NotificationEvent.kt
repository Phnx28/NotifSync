package com.phnx28.notifsync.data.model

import com.google.gson.annotations.SerializedName

/**
 * Wire format for a single notification or SMS event.
 *
 * All fields default to empty values to survive Gson deserialization of
 * incomplete/malformed JSON from an untrusted peer (see AUDIT.md H-11 / L-07).
 * Gson does not respect Kotlin nullability, so non-nullable types alone are
 * not enough — defaults give us a safe degradation path.
 */
data class NotificationEvent(
    @SerializedName("app_name") val appName: String = "",
    val sender: String = "",
    val title: String = "",
    val body: String = "",
    val timestamp: Long = 0L,
    val type: String = TYPE_NOTIFICATION
) {
    companion object {
        const val TYPE_NOTIFICATION = "NOTIFICATION"
        const val TYPE_SMS = "SMS"
    }
}
