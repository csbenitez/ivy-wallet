package com.ivy.plugins.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ivy.plugins.PluginManager
import com.ivy.plugins.api.SyncResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Periodically runs every enabled sync plugin in the background.
 * Uses a Hilt entry point so it works with WorkManager's default factory.
 */
class PluginSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun pluginManager(): PluginManager
    }

    override suspend fun doWork(): Result {
        val pluginManager = EntryPointAccessors
            .fromApplication(applicationContext, Dependencies::class.java)
            .pluginManager()

        val results = pluginManager.syncAll()
        return if (results.values.any { it is SyncResult.Error }) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
