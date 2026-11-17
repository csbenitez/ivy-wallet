package com.ivy.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.lifecycle.viewModelScope
import com.ivy.plugins.api.IvyPlugin
import com.ivy.plugins.api.PluginId
import com.ivy.plugins.api.PluginMetadata
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class PluginsUiState(
    val plugins: ImmutableList<PluginItemViewState>,
)

@Immutable
data class PluginItemViewState(
    val metadata: PluginMetadata,
    val enabled: Boolean,
    val plugin: IvyPlugin,
)

sealed interface PluginsUiEvent {
    data class TogglePlugin(val pluginId: PluginId) : PluginsUiEvent
}

@Stable
@HiltViewModel
class PluginsViewModel @Inject constructor(
    private val pluginManager: PluginManager,
) : ComposeViewModel<PluginsUiState, PluginsUiEvent>() {

    private val pluginsFlow = pluginManager.plugins.let { plugins ->
        if (plugins.isEmpty()) {
            flowOf(emptyList<PluginItemViewState>())
        } else {
            combine(
                plugins.map { plugin ->
                    pluginManager.enabledFlow(plugin.metadata.id)
                }
            ) { enabled ->
                plugins.mapIndexed { index, plugin ->
                    PluginItemViewState(
                        metadata = plugin.metadata,
                        enabled = enabled[index],
                        plugin = plugin,
                    )
                }
            }
        }
    }

    @Composable
    override fun uiState(): PluginsUiState {
        val plugins = remember { pluginsFlow }
            .collectAsState(initial = emptyList()).value
        return PluginsUiState(plugins = plugins.toImmutableList())
    }

    override fun onEvent(event: PluginsUiEvent) {
        when (event) {
            is PluginsUiEvent.TogglePlugin -> viewModelScope.launch {
                val enabled = pluginManager.isEnabled(event.pluginId)
                pluginManager.setEnabled(event.pluginId, !enabled)
            }
        }
    }
}
