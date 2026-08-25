package com.opensrs.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opensrs.OpenSrsApp
import java.util.concurrent.TimeUnit

/**
 * Periodic background backup. Runs only on unmetered networks; exponential
 * backoff on failure. The engine's mutex makes this safe alongside manual syncs.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as OpenSrsApp).container
        return when (container.syncEngine.syncNow()) {
            is DriveSyncEngine.SyncResult.Failed -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "drive_appdata_backup"

        /** Called once at app start; re-enqueue keeps existing schedule. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/** Manual pull-to-refresh / button path. */
suspend fun triggerImmediateSync(context: Context): DriveSyncEngine.SyncResult =
    (context.applicationContext as OpenSrsApp).container.syncEngine.syncNow()
