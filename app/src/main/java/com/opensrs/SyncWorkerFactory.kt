package com.opensrs

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.opensrs.sync.SyncWorker

/** Bridges the manual DI container into WorkManager worker construction. */
class SyncWorkerFactory(private val app: OpenSrsApp) : WorkerFactory() {
    override fun createWorker(
        context: Context,
        workerClassName: String,
        params: WorkerParameters,
    ): ListenableWorker? =
        if (workerClassName == SyncWorker::class.java.name) {
            SyncWorker(context, params, app.container)
        } else {
            null
        }
}
