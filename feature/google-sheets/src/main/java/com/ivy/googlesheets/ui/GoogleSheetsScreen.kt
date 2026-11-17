package com.ivy.googlesheets.ui

import android.app.Activity
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ivy.googlesheets.R
import com.ivy.googlesheets.sync.SheetMapping
import com.ivy.googlesheets.sync.SpreadsheetRef
import com.ivy.googlesheets.sync.columnOf
import com.ivy.navigation.navigation
import com.ivy.navigation.screenScopedViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Composable
fun GoogleSheetsScreenImpl(modifier: Modifier = Modifier) {
    val viewModel: GoogleSheetsViewModel = screenScopedViewModel()
    val state = viewModel.uiState()

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onEvent(
            GoogleSheetsEvent.ConsentResult(
                granted = result.resultCode == Activity.RESULT_OK,
                data = result.data,
            )
        )
    }
    LaunchedEffect(state.consentRequest) {
        val pendingIntent = state.consentRequest ?: return@LaunchedEffect
        viewModel.onEvent(GoogleSheetsEvent.ConsentLaunched)
        consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
    }

    Box(modifier = modifier) {
        GoogleSheetsUi(state = state, onEvent = viewModel::onEvent)
        state.pickerHtml?.let { html ->
            GooglePickerDialog(
                html = html,
                onPick = { id, name -> viewModel.onEvent(GoogleSheetsEvent.SheetPicked(id, name)) },
                onCancel = { viewModel.onEvent(GoogleSheetsEvent.PickerCancelled) },
                onError = { viewModel.onEvent(GoogleSheetsEvent.PickerFailed(it)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoogleSheetsUi(
    state: GoogleSheetsState,
    onEvent: (GoogleSheetsEvent) -> Unit,
) {
    val nav = navigation()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gs_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
            state.error?.let { error -> item { ErrorCard(error) { onEvent(GoogleSheetsEvent.DismissError) } } }
            when (state.step) {
                GoogleSheetsState.Step.PICK_SHEET -> pickSheet(state, onEvent)
                GoogleSheetsState.Step.MAPPING -> mapping(state, onEvent)
                GoogleSheetsState.Step.LINKED -> linked(state, onEvent)
            }
            item { Text(stringResource(R.string.gs_how_it_works), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun LazyListScope.pickSheet(state: GoogleSheetsState, onEvent: (GoogleSheetsEvent) -> Unit) {
    item { Text(stringResource(R.string.gs_intro)) }
    item {
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.loading,
            onClick = { onEvent(GoogleSheetsEvent.OpenPicker) },
        ) { Text(stringResource(R.string.gs_pick_sheet)) }
    }
    item {
        Text(stringResource(R.string.gs_pick_privacy), style = MaterialTheme.typography.bodySmall)
    }
}

private fun LazyListScope.mapping(state: GoogleSheetsState, onEvent: (GoogleSheetsEvent) -> Unit) {
    val mapping = state.mapping
    item {
        Text(
            text = state.spreadsheetTitle.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    item {
        Dropdown(
            label = stringResource(R.string.gs_tab),
            options = state.tabs,
            selected = state.tabs.indexOf(state.selectedTab),
            noneLabel = null,
            onSelect = { index -> onEvent(GoogleSheetsEvent.TabSelected(state.tabs[index])) },
        )
    }
    if (state.header.isEmpty()) return
    item {
        Text(stringResource(R.string.gs_mapping_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.gs_mapping_help), style = MaterialTheme.typography.bodySmall)
    }
    item { FieldRow(SheetMapping.Field.DATE, state, onEvent, required = true) }
    item {
        RadioGroup(
            title = stringResource(R.string.gs_date_order),
            options = persistentListOf(
                SheetMapping.DateOrder.DAY_FIRST to stringResource(R.string.gs_date_day_first),
                SheetMapping.DateOrder.MONTH_FIRST to stringResource(R.string.gs_date_month_first),
            ),
            selected = mapping.dateOrder,
            onSelect = { onEvent(GoogleSheetsEvent.DateOrderChanged(it)) },
        )
    }
    item { FieldRow(SheetMapping.Field.AMOUNT, state, onEvent, required = true) }
    item {
        RadioGroup(
            title = stringResource(R.string.gs_type_mode),
            options = persistentListOf(
                SheetMapping.TypeMode.COLUMN to stringResource(R.string.gs_type_mode_column),
                SheetMapping.TypeMode.AMOUNT_SIGN to stringResource(R.string.gs_type_mode_sign),
                SheetMapping.TypeMode.ALL_EXPENSES to stringResource(R.string.gs_type_mode_expenses),
                SheetMapping.TypeMode.ALL_INCOME to stringResource(R.string.gs_type_mode_income),
            ),
            selected = mapping.typeMode,
            onSelect = { onEvent(GoogleSheetsEvent.TypeModeChanged(it)) },
        )
    }
    if (mapping.typeMode == SheetMapping.TypeMode.COLUMN) {
        item { FieldRow(SheetMapping.Field.TYPE, state, onEvent, required = true) }
        item { Keywords(mapping, onEvent) }
    }
    item { FieldRow(SheetMapping.Field.ACCOUNT, state, onEvent, required = false) }
    if (mapping.account < 0) {
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = mapping.fixedAccountName,
                onValueChange = { onEvent(GoogleSheetsEvent.FixedAccountChanged(it)) },
                label = { Text(stringResource(R.string.gs_fixed_account)) },
                singleLine = true,
            )
        }
    }
    listOf(
        SheetMapping.Field.CATEGORY,
        SheetMapping.Field.TITLE,
        SheetMapping.Field.DESCRIPTION,
        SheetMapping.Field.CURRENCY,
        SheetMapping.Field.TO_ACCOUNT,
        SheetMapping.Field.TO_AMOUNT,
    ).forEach { field -> item { FieldRow(field, state, onEvent, required = false) } }

    item {
        if (state.missingFields.isNotEmpty()) {
            val names = state.missingFields.map { stringResource(fieldLabelRes(it)) }.joinToString()
            Text(
                text = stringResource(R.string.gs_missing_fields, names),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.loading && state.missingFields.isEmpty(),
            onClick = { onEvent(GoogleSheetsEvent.SaveMappingAndSync) },
        ) { Text(stringResource(R.string.gs_save_and_sync)) }
    }
}

private fun LazyListScope.linked(state: GoogleSheetsState, onEvent: (GoogleSheetsEvent) -> Unit) {
    val link = state.link ?: return
    item {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.gs_linked_to, link.spreadsheetTitle, link.tab))
                Text(
                    text = state.lastSyncEpochMillis?.let {
                        stringResource(
                            R.string.gs_last_sync,
                            DateUtils.getRelativeTimeSpanString(it).toString()
                        )
                    } ?: stringResource(R.string.gs_never_synced),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    state.lastReport?.let { report ->
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.gs_report, report.importedToIvy, report.writtenToSheet))
                if (report.failedSheetRows.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.gs_report_failed,
                            report.failedSheetRows.take(MaxFailedRowsShown).joinToString() +
                                if (report.failedSheetRows.size > MaxFailedRowsShown) "…" else ""
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (report.notRepresentable > 0) {
                    Text(
                        text = stringResource(R.string.gs_report_skipped, report.notRepresentable),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    item {
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.loading,
            onClick = { onEvent(GoogleSheetsEvent.SyncNow) },
        ) { Text(stringResource(R.string.gs_sync_now)) }
    }
    item {
        val uriHandler = LocalUriHandler.current
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !state.loading,
                onClick = { onEvent(GoogleSheetsEvent.EditMapping) },
            ) { Text(stringResource(R.string.gs_edit_mapping)) }
            OutlinedButton(onClick = { uriHandler.openUri(SpreadsheetRef.url(link.spreadsheetId)) }) {
                Text(stringResource(R.string.gs_open_sheet))
            }
        }
    }
    item {
        TextButton(onClick = { onEvent(GoogleSheetsEvent.Unlink) }) {
            Text(stringResource(R.string.gs_unlink), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun FieldRow(
    field: SheetMapping.Field,
    state: GoogleSheetsState,
    onEvent: (GoogleSheetsEvent) -> Unit,
    required: Boolean,
) {
    val column = state.mapping.columnOf(field)
    val requiredSuffix = if (required) " (${stringResource(R.string.gs_required)})" else ""
    val label = stringResource(fieldLabelRes(field)) + requiredSuffix
    Column {
        Dropdown(
            label = label,
            options = state.header,
            selected = column,
            noneLabel = stringResource(R.string.gs_column_none),
            onSelect = { index -> onEvent(GoogleSheetsEvent.FieldMapped(field, index)) },
        )
        if (column >= 0) {
            val samples = state.sampleRows.mapNotNull { row -> row.getOrNull(column)?.takeIf { it.isNotBlank() } }
                .take(SamplesShown)
            if (samples.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.gs_sample, samples.joinToString(" · ")),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** @param selected index into [options], or -1 for none. Selecting "none" reports -1. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(
    label: String,
    options: ImmutableList<String>,
    selected: Int,
    noneLabel: String?,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            readOnly = true,
            value = options.getOrNull(selected) ?: noneLabel.orEmpty(),
            onValueChange = {},
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (noneLabel != null) {
                DropdownMenuItem(
                    text = { Text(noneLabel) },
                    onClick = {
                        expanded = false
                        onSelect(SheetMapping.NONE)
                    }
                )
            }
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    }
                )
            }
        }
    }
}

@Composable
private fun <T> RadioGroup(
    title: String,
    options: ImmutableList<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        options.forEach { (value, text) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = value == selected,
                        onClick = { onSelect(value) },
                        role = Role.RadioButton,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = null)
                Text(text, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun Keywords(mapping: SheetMapping, onEvent: (GoogleSheetsEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.gs_keywords), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val modifier = Modifier.weight(1f)
            OutlinedTextField(
                modifier = modifier,
                value = mapping.incomeKeyword,
                onValueChange = {
                    onEvent(GoogleSheetsEvent.KeywordsChanged(it, mapping.expenseKeyword, mapping.transferKeyword))
                },
                label = { Text(stringResource(R.string.gs_keyword_income)) },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = modifier,
                value = mapping.expenseKeyword,
                onValueChange = {
                    onEvent(GoogleSheetsEvent.KeywordsChanged(mapping.incomeKeyword, it, mapping.transferKeyword))
                },
                label = { Text(stringResource(R.string.gs_keyword_expense)) },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = modifier,
                value = mapping.transferKeyword,
                onValueChange = {
                    onEvent(GoogleSheetsEvent.KeywordsChanged(mapping.incomeKeyword, mapping.expenseKeyword, it))
                },
                label = { Text(stringResource(R.string.gs_keyword_transfer)) },
                singleLine = true,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ErrorCard(error: SheetsError, onDismiss: () -> Unit) {
    val text = stringResource(
        when (error) {
            SheetsError.PICKER_NOT_CONFIGURED -> R.string.gs_error_picker_not_configured
            SheetsError.PICKER_FAILED -> R.string.gs_error_picker_failed
            SheetsError.NOT_FOUND -> R.string.gs_error_not_found
            SheetsError.PERMISSION_DENIED -> R.string.gs_error_permission
            SheetsError.NETWORK -> R.string.gs_error_network
            SheetsError.CONSENT_DENIED -> R.string.gs_error_consent
            SheetsError.EMPTY_SHEET -> R.string.gs_error_empty
            SheetsError.UNKNOWN -> R.string.gs_error_unknown
        }
    )
    Card(modifier = Modifier.fillMaxWidth(), onClick = onDismiss) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@StringRes
private fun fieldLabelRes(field: SheetMapping.Field): Int = when (field) {
    SheetMapping.Field.DATE -> R.string.gs_field_date
    SheetMapping.Field.AMOUNT -> R.string.gs_field_amount
    SheetMapping.Field.TYPE -> R.string.gs_field_type
    SheetMapping.Field.ACCOUNT -> R.string.gs_field_account
    SheetMapping.Field.CURRENCY -> R.string.gs_field_currency
    SheetMapping.Field.CATEGORY -> R.string.gs_field_category
    SheetMapping.Field.TITLE -> R.string.gs_field_title
    SheetMapping.Field.DESCRIPTION -> R.string.gs_field_description
    SheetMapping.Field.TO_ACCOUNT -> R.string.gs_field_to_account
    SheetMapping.Field.TO_AMOUNT -> R.string.gs_field_to_amount
}

private const val SamplesShown = 3
private const val MaxFailedRowsShown = 10
