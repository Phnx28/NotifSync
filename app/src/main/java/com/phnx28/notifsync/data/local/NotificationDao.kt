package com.phnx28.notifsync.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    @Query("SELECT * FROM notifications WHERE status = 'ACTIVE' ORDER BY timestamp DESC")
    fun getActiveNotifications(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE status = 'ARCHIVED' ORDER BY archived_at DESC")
    fun getArchivedNotifications(): Flow<List<NotificationEntity>>

    @Insert
    suspend fun insert(notification: NotificationEntity): Long

    @Query("UPDATE notifications SET status = 'ARCHIVED', archived_at = :archivedAt WHERE id = :id")
    suspend fun archive(id: Long, archivedAt: Long)

    @Query("UPDATE notifications SET status = 'ACTIVE', archived_at = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("DELETE FROM notifications WHERE status = 'ARCHIVED' AND archived_at < :cutoff")
    suspend fun deleteOldArchived(cutoff: Long)

    @Query("SELECT COUNT(*) FROM notifications WHERE status = 'ACTIVE'")
    fun getActiveCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM notifications WHERE status = 'ARCHIVED'")
    fun getArchivedCount(): Flow<Int>

    // v0.2.1 — fix for AUDIT.md I-08: the sender screen was showing the
    // archived count under the "SMS Relayed" label. Add a real SMS count.
    @Query("SELECT COUNT(*) FROM notifications WHERE type = 'SMS'")
    fun getSmsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM notifications WHERE type = 'NOTIFICATION'")
    fun getNotificationCount(): Flow<Int>
}
