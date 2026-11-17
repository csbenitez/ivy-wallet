package com.ivy.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivy.navigation.navigation
import com.ivy.navigation.screenScopedViewModel
import com.ivy.plugins.api.PluginCategory

@Composable
fun PluginsScreenImpl(
    viewModel: PluginsViewModel = screenScopedViewModel()
) {
    PluginsUi(
        uiState = viewModel.uiState(),
        onEvent = viewModel::onEvent
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginsUi(
    uiState: PluginsUiState,
    onEvent: (PluginsUiEvent) -> Unit,
) {
    val nav = navigation()
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.plugins_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
            )
        },
        content = { innerPadding ->
            LazyColumn(
                modifier = Modifier.padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.plugins_intro),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (uiState.plugins.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.plugins_empty),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(uiState.plugins, key = { it.metadata.id }) { item ->
                    PluginCard(
                        item = item,
                        onToggle = { onEvent(PluginsUiEvent.TogglePlugin(item.metadata.id)) }
                    )
                }
            }
        }
    )
}

@Composable
private fun PluginCard(
    item: PluginItemViewState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.metadata.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = categoryLabel(item.metadata.category) + " · " + stringResource(
                        R.string.plugins_version_author,
                        item.metadata.version,
                        item.metadata.author
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.metadata.description,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = item.enabled, onCheckedChange = { onToggle() })
        }
        if (item.enabled) {
            HorizontalDivider()
            item.plugin.SettingsContent(
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
private fun categoryLabel(category: PluginCategory): String = stringResource(
    when (category) {
        PluginCategory.SYNC -> R.string.plugins_category_sync
        PluginCategory.IMPORT -> R.string.plugins_category_import
        PluginCategory.OTHER -> R.string.plugins_category_other
    }
)
