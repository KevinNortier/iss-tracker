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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Refreshes the cached TLEs of all tracked satellites in the background about once a day. */
class TleRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as IssTrackerApp).container
        var anyFailed = false
        for (satellite in container.settingsRepository.trackedSatellites.first()) {
            try {
                val result = container.tleRepository.getTle(satellite.noradId)
                if (result.refreshError != null) anyFailed = true
                // Be gentle with CelesTrak: one request at a time, with a short pause after each download.
                if (result.downloaded) delay(PAUSE_BETWEEN_DOWNLOADS_MS)
            } catch (e: NoTleAvailableException) {
                anyFailed = true
            }
        }
        // Retrying re-runs everything, but satellites refreshed this time are skipped (their cache is fresh).
        return if (anyFailed) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "tle-refresh"
        private const val PAUSE_BETWEEN_DOWNLOADS_MS = 1_000L

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
