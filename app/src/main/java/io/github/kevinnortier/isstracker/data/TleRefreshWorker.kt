package io.github.kevinnortier.isstracker.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.kevinnortier.isstracker.IssTrackerApp
import java.util.concurrent.TimeUnit

/** Refreshes the cached TLE in the background about once a day. */
class TleRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as IssTrackerApp).container
        return try {
            val result = container.tleRepository.getTle(container.satellite.noradId)
            if (result.refreshError != null) Result.retry() else Result.success()
        } catch (e: NoTleAvailableException) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "tle-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TleRefreshWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
