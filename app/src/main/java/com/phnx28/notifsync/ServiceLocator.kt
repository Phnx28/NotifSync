package com.phnx28.notifsync

import android.app.Application
import com.phnx28.notifsync.data.ConnectionRepository
import com.phnx28.notifsync.data.local.AppDatabase
import com.phnx28.notifsync.data.repository.NotificationRepository

/**
 * Lightweight service locator. Replaces `application as NotifSyncApp` casts.
 *
 * Initialized once in [NotifSyncApp.onCreate]. Access from anywhere via
 * `ServiceLocator.notificationRepository` etc.
 */
object ServiceLocator {

    lateinit var notificationRepository: NotificationRepository
        private set

    lateinit var connectionRepository: ConnectionRepository
        private set

    fun init(app: Application) {
        val database = AppDatabase.getInstance(app)
        notificationRepository = NotificationRepository(database)
        connectionRepository = ConnectionRepository()
    }
}
