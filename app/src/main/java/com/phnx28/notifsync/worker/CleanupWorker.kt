package com.phnx28.notifsync.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.phnx28.notifsync.ServiceLocator
import com.phnx28.notifsync.util.AppLog

class CleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "CleanupWorker"

    override suspend fun doWork(): Result {
        return try {
            ServiceLocator.notificationRepository.deleteOldArchived()
            AppLog.i(TAG, "Cleanup completed successfully")
            Result.success()
        } catch (e: Exception) {
            AppLog.e(TAG, "Cleanup failed", e)
            Result.retry()
        }
    }
}
