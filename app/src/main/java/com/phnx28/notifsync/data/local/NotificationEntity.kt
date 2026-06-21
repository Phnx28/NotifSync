package com.phnx28.notifsync.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notifications",
    indices = [
        Index("status"),
        Index("timestamp"),
        Index("archived_at")
    ]
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "app_name") val appName: String,
    val sender: String?,
    val title: String,
    val body: String,
    val timestamp: Long,
    val type: String,
    val status: String = STATUS_ACTIVE,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_ARCHIVED = "ARCHIVED"
    }
}
