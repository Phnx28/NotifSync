package com.phnx28.notifsync.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.phnx28.notifsync.NotifSyncApp

class CleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "CleanupWorker"

    override suspend fun doWork(): Result {
        return try {
            val app = applicationContext as NotifSyncApp
            app.repository.deleteOldArchived()
            Log.d(TAG, "Cleanup completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed", e)
            Result.retry()
        }
    }
}
