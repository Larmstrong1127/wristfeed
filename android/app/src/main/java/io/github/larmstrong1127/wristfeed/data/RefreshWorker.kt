package io.github.larmstrong1127.wristfeed.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * SPEC Refresh: every 30 minutes with network; plus one-shot refreshes (tile visible).
 *
 * Concurrency: the one-shot work has its own unique name ([ONESHOT]) with [ExistingWorkPolicy.KEEP],
 * so a request while one is queued or running is dropped, and it never replaces the periodic work.
 * WorkManager may still run the periodic and the one-shot work at the same moment; that is safe
 * because [Refresher] serializes every refresh behind one process-wide mutex.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Refresher.refreshAll(applicationContext)
        // Per-feed failures are recorded in the store; the work itself succeeded.
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "wristfeed-periodic"
        private const val ONESHOT = "wristfeed-now"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun refreshNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.KEEP, req)
        }
    }
}
