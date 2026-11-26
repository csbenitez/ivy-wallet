package com.ivy.sharedwallet.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ivy.sharedwallet.data.SharedWorkspacesManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltWorker
class SharedLedgerSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val manager: SharedWorkspacesManager,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val success = manager.syncAll()
        return when {
            success -> Result.success()
            runAttemptCount < MAX_RETRIES -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
    }
}

/**
 * Keeps shared workspaces in sync in the background:
 * every 15 minutes (the WorkManager minimum) and every time the app starts.
 */
class SharedLedgerSyncScheduler @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val manager: SharedWorkspacesManager,
) {
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun onAppStart() {
        if (!manager.hasSharedWorkspaces()) return
        schedulePeriodic()
        syncSoon()
    }

    fun schedulePeriodic() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SharedLedgerSyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        )
    }

    fun syncSoon() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SharedLedgerSyncWorker>()
                .setConstraints(constraints)
                .build()
        )
    }

    companion object {
        private const val PERIODIC_WORK = "shared_ledger_sync_periodic"
        private const val ONE_TIME_WORK = "shared_ledger_sync_now"
        private const val PERIOD_MINUTES = 15L
    }
}
