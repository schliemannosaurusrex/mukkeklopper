package de.schliemannosaurusrex.kaniamp.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodischer Hintergrund-Sync (PLAN.md Phase 4, Scheduler). Läuft nicht
 * interaktiv: Löschungen werden nie automatisch ausgeführt, sondern nur als
 * „pending" gezählt und beim nächsten manuellen Sync bestätigt.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!SyncManager.isRunning) {
            SyncManager.runSync(applicationContext, interactive = false)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "kaniamp_periodic_sync"

        fun setEnabled(context: Context, enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
