package com.phnx28.notifsync

import android.app.Application
import androidx.work.Constraints
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
        // Daily cleanup with a 2-hour flex window + battery-not-low constraint
        // so the system can batch it with other work (AUDIT.md L-08).
        val cleanupWork = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setFlex(2, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "notifsync_cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupWork
        )
    }
}
