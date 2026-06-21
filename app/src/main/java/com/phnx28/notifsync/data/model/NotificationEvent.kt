package com.phnx28.notifsync.data.model

import com.google.gson.annotations.SerializedName

data class NotificationEvent(
    @SerializedName("app_name") val appName: String,
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
