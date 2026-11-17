package com.ivy.plugins.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier

/**
 * A plugin is a self-contained, optional piece of functionality that the user
 * can turn on and off from the "Plugins" screen.
 *
 * Plugins are regular Gradle modules compiled into the app. To ship a new one:
 * 1. Create a module that depends on `:feature:plugins:api`.
 * 2. Implement [IvyPlugin] (or a specialised contract such as [SyncPlugin]).
 * 3. Contribute it to the plugin registry with a Hilt `@Binds @IntoSet` binding.
 * 4. Add the module as a dependency of `:app`.
 *
 * Plugins are disabled by default. Their enabled state is persisted by the
 * plugin manager, so implementations don't need to track it themselves.
 */
interface IvyPlugin {
    val metadata: PluginMetadata

    /**
     * Configuration UI shown under the plugin in the "Plugins" screen
     * while the plugin is enabled (e.g. connect an account, sync now).
     */
    @Composable
    fun SettingsContent(modifier: Modifier)

    /** Called after the user enables the plugin. */
    suspend fun onEnabled() {}

    /** Called after the user disables the plugin. */
    suspend fun onDisabled() {}
}

@Immutable
data class PluginMetadata(
    /** Stable, unique id. Used as a storage key, so never change it once released. */
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val category: PluginCategory,
)

enum class PluginCategory {
    /** Keeps Ivy Wallet data in sync with an external service. */
    SYNC,

    /** Brings data into Ivy Wallet from an external source. */
    IMPORT,
    OTHER,
}
