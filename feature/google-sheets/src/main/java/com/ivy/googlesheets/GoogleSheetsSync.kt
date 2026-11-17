package com.ivy.googlesheets

import com.ivy.base.threading.DispatchersProvider
import com.ivy.base.time.TimeConverter
import com.ivy.data.model.AccountId
import com.ivy.data.model.CategoryId
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.Transaction
import com.ivy.data.model.Transfer
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.googlesheets.data.GoogleSheetsSyncStore
import com.ivy.googlesheets.data.SheetLink
import com.ivy.googlesheets.remote.SheetsApi
import com.ivy.googlesheets.remote.SpreadsheetInfo
import com.ivy.googlesheets.sync.ColumnAutoMapper
import com.ivy.googlesheets.sync.ImportRowsPreparer
import com.ivy.googlesheets.sync.OutgoingTransaction
import com.ivy.googlesheets.sync.RowFingerprint
import com.ivy.googlesheets.sync.SheetMapping
import com.ivy.googlesheets.sync.SheetRowWriter
import com.ivy.googlesheets.sync.SheetTable
import com.ivy.importdata.csv.domain.CSVImporterV2
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Two-way sync between Ivy Wallet and a tab of the user's own Google Sheet:
 * 1. Sheet -> Ivy: rows that weren't synced before are imported with the saved [SheetMapping].
 * 2. Ivy -> Sheet: Ivy transactions created since the sheet was linked are appended
 *    to the same tab, written in the sheet's own format.
 *
 * Only new data moves: edits and deletions on either side are not propagated (yet).
 * This class is the single entry point, so it can later be wrapped by a sync plugin.
 */
@Singleton
class GoogleSheetsSync @Inject constructor(
    private val api: SheetsApi,
    private val store: GoogleSheetsSyncStore,
    private val csvImporter: Provider<CSVImporterV2>,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val timeConverter: TimeConverter,
    private val dispatchers: DispatchersProvider,
) {
    private val mutex = Mutex()

    data class Preview(
        val info: SpreadsheetInfo,
        val tab: String,
        val table: SheetTable,
        val suggestedMapping: SheetMapping,
    )

    data class Report(
        val importedToIvy: Int,
        val failedSheetRows: List<Int>,
        val writtenToSheet: Int,
        val notRepresentable: Int,
    )

    suspend fun spreadsheetInfo(spreadsheetId: String): SpreadsheetInfo =
        withContext(dispatchers.io) { api.spreadsheetInfo(spreadsheetId) }

    suspend fun preview(spreadsheetId: String, tab: String?): Preview = withContext(dispatchers.io) {
        val info = api.spreadsheetInfo(spreadsheetId)
        val selectedTab = tab?.takeIf { it in info.tabs } ?: info.tabs.first()
        val table = api.readTable(spreadsheetId, selectedTab)
        Preview(
            info = info,
            tab = selectedTab,
            table = table,
            suggestedMapping = ColumnAutoMapper.guess(table),
        )
    }

    suspend fun link(link: SheetLink) = withContext(dispatchers.io) {
        val previous = store.currentLink()
        val existingIds = transactionRepository.findAll().map { it.id.value.toString() }.toSet()
        store.saveLink(link, existingTransactionIds = existingIds)

        val sameTab = previous != null && previous.spreadsheetId == link.spreadsheetId &&
            previous.tab == link.tab
        val oldColumns = previous?.mapping?.mappedColumns
        val newColumns = link.mapping.mappedColumns
        if (sameTab && oldColumns != null && oldColumns != newColumns) {
            // Row fingerprints depend on the mapped columns: re-key the already synced rows,
            // otherwise changing the mapping would import the whole sheet again.
            val table = api.readTable(link.spreadsheetId, link.tab)
            val known = store.ledger().knownRowFingerprints
            val oldFingerprints = RowFingerprint.fingerprints(table.rows, oldColumns)
            val newFingerprints = RowFingerprint.fingerprints(table.rows, newColumns)
            store.replaceRowFingerprints(
                newFingerprints.filterIndexed { index, _ -> oldFingerprints[index] in known }.toSet()
            )
        }
    }

    suspend fun unlink() = store.unlink()

    suspend fun sync(): Report = mutex.withLock {
        withContext(dispatchers.io) { syncInternal() }
    }

    private suspend fun syncInternal(): Report {
        val link = store.currentLink() ?: error("No Google Sheet linked")
        val mapping = link.mapping
        val ledger = store.ledger()
        val table = api.readTable(link.spreadsheetId, link.tab)
        val fingerprints = RowFingerprint.fingerprints(table.rows, mapping.mappedColumns)

        // 1. Sheet -> Ivy
        val idsBefore = transactionIds()
        val newRowIndexes = fingerprints.indices.filter { fingerprints[it] !in ledger.knownRowFingerprints }
        val failedRowIndexes = if (newRowIndexes.isNotEmpty()) {
            importRows(table, newRowIndexes, mapping)
        } else {
            emptySet()
        }
        val importedRowFingerprints = newRowIndexes
            .filter { it !in failedRowIndexes }
            .map { fingerprints[it] }
        val importedIds = transactionIds() - idsBefore

        // 2. Ivy -> Sheet
        val knownIds = ledger.knownTransactionIds + importedIds
        val outgoing = transactionRepository.findAll()
            .filter { it.settled && it.id.value.toString() !in knownIds }
            .sortedBy { it.time }
        val accounts = accountRepository.findAll().associate { it.id to it.name.value }
        val categories = categoryRepository.findAll().associate { it.id to it.name.value }
        val written = outgoing.mapNotNull { trn ->
            val row = SheetRowWriter.toRow(
                trn = trn.toOutgoing(accounts, categories) ?: return@mapNotNull null,
                mapping = mapping,
                width = table.width,
            ) ?: return@mapNotNull null
            trn.id.value.toString() to row
        }
        var writtenRowFingerprints = emptyList<String>()
        if (written.isNotEmpty()) {
            api.appendRows(link.spreadsheetId, link.tab, written.map { it.second })
            // Read back the rows as Sheets stored them, so they're never imported back.
            val after = api.readTable(link.spreadsheetId, link.tab)
            val before = fingerprints.toSet()
            writtenRowFingerprints = RowFingerprint.fingerprints(after.rows, mapping.mappedColumns)
                .filter { it !in before }
        }

        store.recordSync(
            newRowFingerprints = importedRowFingerprints + writtenRowFingerprints,
            newTransactionIds = importedIds + written.map { it.first },
            epochMillis = System.currentTimeMillis(),
        )
        return Report(
            importedToIvy = importedIds.size,
            // +2: 1-based and the header row, so it matches the row number the user sees in Sheets
            failedSheetRows = failedRowIndexes.sorted().map { it + 2 },
            writtenToSheet = written.size,
            notRepresentable = outgoing.size - written.size,
        )
    }

    /** @return indexes (into [SheetTable.rows]) of the rows that failed to import. */
    private suspend fun importRows(
        table: SheetTable,
        rowIndexes: List<Int>,
        mapping: SheetMapping,
    ): Set<Int> {
        val prepared = ImportRowsPreparer.prepare(table, rowIndexes.map { table.rows[it] }, mapping)
        val result = csvImporter.get().import(
            csv = prepared.csv,
            importantFields = prepared.important,
            transferFields = prepared.transfer,
            optionalFields = prepared.optional,
            onProgress = {},
        )
        // CSVImporterV2 reports failed rows as (index in rows without header) + 2
        return result.failedRows.mapNotNull { rowIndexes.getOrNull(it.index - 2) }.toSet()
    }

    private suspend fun transactionIds(): Set<String> =
        transactionRepository.findAll().map { it.id.value.toString() }.toSet()

    private fun Transaction.toOutgoing(
        accounts: Map<AccountId, String>,
        categories: Map<CategoryId, String>,
    ): OutgoingTransaction? {
        val (type, value, accountId) = when (this) {
            is Expense -> Triple(OutgoingTransaction.Type.EXPENSE, value, account)
            is Income -> Triple(OutgoingTransaction.Type.INCOME, value, account)
            is Transfer -> Triple(OutgoingTransaction.Type.TRANSFER, fromValue, fromAccount)
        }
        val transfer = this as? Transfer
        return accounts[accountId]?.let { accountName ->
            OutgoingTransaction(
                type = type,
                dateTime = with(timeConverter) { time.toLocalDateTime() },
                amount = value.amount.value,
                currency = value.asset.code,
                account = accountName,
                category = category?.let(categories::get),
                title = title?.value,
                description = description?.value,
                toAccount = transfer?.toAccount?.let(accounts::get),
                toAmount = transfer?.toValue?.amount?.value,
            )
        }
    }
}
