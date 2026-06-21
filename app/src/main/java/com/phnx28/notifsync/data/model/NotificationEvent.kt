package com.phnx28.notifsync.data.model

data class NotificationEvent(
    val app_name: String,
    val sender: String,
    val title: String,
    val body: String,
    val timestamp: Long,
    val type: String
) {
    companion object {
        const val TYPE_NOTIFICATION = "NOTIFICATION"
        const val TYPE_SMS = "SMS"
    }
}
