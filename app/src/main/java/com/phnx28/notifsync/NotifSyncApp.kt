package com.phnx28.notifsync

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.phnx28.notifsync.data.local.AppDatabase
import com.phnx28.notifsync.data.repository.NotificationRepository
import com.phnx28.notifsync.worker.CleanupWorker
import java.util.concurrent.TimeUnit

class NotifSyncApp : Application() {

    val database by lazy { AppDatabase.getInstance(this) }
    val repository by lazy { NotificationRepository(database) }

    override fun onCreate() {
        super.onCreate()
        scheduleCleanupWork()
    }

    private fun scheduleCleanupWork() {
        val cleanupWork = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "notifsync_cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupWork
        )
    }
}
