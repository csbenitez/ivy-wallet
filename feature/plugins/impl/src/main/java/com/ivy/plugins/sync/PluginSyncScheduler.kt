package com.ivy.plugins.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class PluginSyncScheduler @Inject constructor(
    @ApplicationContext
    private val context: Context,
) {
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<PluginSyncWorker>(
            SYNC_INTERVAL_HOURS,
            TimeUnit.HOURS
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "ivy_plugins_sync"
        private const val SYNC_INTERVAL_HOURS = 6L
    }
}
