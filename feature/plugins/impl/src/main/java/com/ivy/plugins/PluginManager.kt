package com.ivy.plugins

import com.ivy.plugins.api.IvyPlugin
import com.ivy.plugins.api.PluginId
import com.ivy.plugins.api.PluginPreferences
import com.ivy.plugins.api.SyncPlugin
import com.ivy.plugins.api.SyncResult
import com.ivy.plugins.sync.PluginSyncScheduler
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginManager @Inject constructor(
    plugins: Set<@JvmSuppressWildcards IvyPlugin>,
    private val preferences: PluginPreferences,
    private val syncScheduler: PluginSyncScheduler,
) {
    val plugins: List<IvyPlugin> = plugins.sortedBy { it.metadata.name }

    fun enabledFlow(pluginId: PluginId): Flow<Boolean> =
        preferences.booleanFlow(pluginId, KEY_ENABLED, default = false)

    suspend fun isEnabled(pluginId: PluginId): Boolean =
        preferences.getBoolean(pluginId, KEY_ENABLED, default = false)

    suspend fun setEnabled(pluginId: PluginId, enabled: Boolean) {
        val plugin = plugins.find { it.metadata.id == pluginId } ?: return
        preferences.setBoolean(pluginId, KEY_ENABLED, enabled)
        if (enabled) plugin.onEnabled() else plugin.onDisabled()
        updateSyncSchedule()
    }

    suspend fun enabledSyncPlugins(): List<SyncPlugin> = plugins
        .filterIsInstance<SyncPlugin>()
        .filter { isEnabled(it.metadata.id) }

    /** Runs every enabled and connected sync plugin. */
    suspend fun syncAll(): Map<PluginId, SyncResult> = enabledSyncPlugins()
        .filter { it.isConnected() }
        .associate { it.metadata.id to it.sync() }

    suspend fun updateSyncSchedule() {
        if (enabledSyncPlugins().isNotEmpty()) {
            syncScheduler.schedule()
        } else {
            syncScheduler.cancel()
        }
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
    }
}
