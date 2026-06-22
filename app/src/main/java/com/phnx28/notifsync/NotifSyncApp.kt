package com.phnx28.notifsync

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.phnx28.notifsync.worker.CleanupWorker
import java.util.concurrent.TimeUnit

class NotifSyncApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        scheduleCleanupWork()
    }

    private fun scheduleCleanupWork() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val cleanupWork = PeriodicWorkRequestBuilder<CleanupWorker>(
            1, TimeUnit.DAYS,
            2, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "notifsync_cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupWork
        )
    }
}
