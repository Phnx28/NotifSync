package com.phnx28.notifsync.data.repository

import com.phnx28.notifsync.Constants
import com.phnx28.notifsync.data.local.AppDatabase
import com.phnx28.notifsync.data.local.NotificationEntity
import com.phnx28.notifsync.data.model.NotificationEvent
import kotlinx.coroutines.flow.Flow

class NotificationRepository(private val database: AppDatabase) {

    private val dao = database.notificationDao()

    fun getActiveNotifications(): Flow<List<NotificationEntity>> =
        dao.getActiveNotifications()

    fun getArchivedNotifications(): Flow<List<NotificationEntity>> =
        dao.getArchivedNotifications()

    fun getActiveCount(): Flow<Int> = dao.getActiveCount()

    fun getArchivedCount(): Flow<Int> = dao.getArchivedCount()

    /** Total SMS events captured (used by the sender dashboard — AUDIT.md I-08). */
    fun getSmsCount(): Flow<Int> = dao.getSmsCount()

    /** Total notification events captured. */
    fun getNotificationCount(): Flow<Int> = dao.getNotificationCount()

    suspend fun insertEvent(event: NotificationEvent): Long {
        val entity = NotificationEntity(
            appName = event.appName.take(Constants.MAX_SENDER_LENGTH),
            sender = event.sender.take(Constants.MAX_SENDER_LENGTH),
            title = event.title.take(Constants.MAX_TITLE_LENGTH),
            body = event.body.take(Constants.MAX_BODY_LENGTH),
            timestamp = event.timestamp,
            type = event.type
        )
        return dao.insert(entity)
    }

    suspend fun archive(id: Long) {
        dao.archive(id, System.currentTimeMillis())
    }

    suspend fun restore(id: Long) {
        dao.restore(id)
    }

    suspend fun deleteOldArchived() {
        val retentionMs: Long = Constants.ARCHIVE_RETENTION_DAYS.toLong() * 24L * 60L * 60L * 1000L
        val cutoff: Long = System.currentTimeMillis() - retentionMs
        dao.deleteOldArchived(cutoff)
    }
}
