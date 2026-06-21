package com.phnx28.notifsync.data.repository

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

    suspend fun insertEvent(event: NotificationEvent): Long {
        val entity = NotificationEntity(
            appName = event.appName,
            sender = event.sender,
            title = event.title,
            body = event.body.take(5000),
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
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        val cutoff = System.currentTimeMillis() - thirtyDaysMs
        dao.deleteOldArchived(cutoff)
    }
}
