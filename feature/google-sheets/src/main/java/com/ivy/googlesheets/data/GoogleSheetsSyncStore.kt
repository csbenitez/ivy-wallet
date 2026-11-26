package com.ivy.googlesheets.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.ivy.googlesheets.sync.SheetMapping
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** The linked spreadsheet, tab and column mapping. */
@Serializable
data class SheetLink(
    val spreadsheetId: String,
    val spreadsheetTitle: String,
    val tab: String,
    val mapping: SheetMapping,
)

/** What has already been synced, so each sync only moves new rows/transactions. */
data class SyncLedger(
    /** Fingerprints of sheet rows that are already in Ivy (imported or written by Ivy). */
    val knownRowFingerprints: Set<String>,
    /** Ids of Ivy transactions that are already in the sheet (or came from it). */
    val knownTransactionIds: Set<String>,
    val lastSyncEpochMillis: Long?,
)

class GoogleSheetsSyncStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val link: Flow<SheetLink?> = dataStore.data.map { prefs ->
        prefs[LinkKey]?.let { raw ->
            runCatching { json.decodeFromString(SheetLink.serializer(), raw) }.getOrNull()
        }
    }

    val lastSync: Flow<Long?> = dataStore.data.map { it[LastSyncKey] }

    suspend fun currentLink(): SheetLink? = link.first()

    suspend fun ledger(): SyncLedger {
        val prefs = dataStore.data.first()
        return SyncLedger(
            knownRowFingerprints = prefs[RowFingerprintsKey].orEmpty(),
            knownTransactionIds = prefs[TransactionIdsKey].orEmpty(),
            lastSyncEpochMillis = prefs[LastSyncKey],
        )
    }

    /**
     * Links a sheet. Ivy transactions that exist at link time are marked as known,
     * so linking never dumps the whole Ivy history into the user's sheet.
     */
    suspend fun saveLink(link: SheetLink, existingTransactionIds: Set<String>) {
        dataStore.edit { prefs ->
            val previous = prefs[LinkKey]?.let {
                runCatching { json.decodeFromString(SheetLink.serializer(), it) }.getOrNull()
            }
            val sameSheet = previous != null &&
                previous.spreadsheetId == link.spreadsheetId && previous.tab == link.tab
            prefs[LinkKey] = json.encodeToString(SheetLink.serializer(), link)
            if (!sameSheet) {
                prefs[RowFingerprintsKey] = emptySet()
                prefs[TransactionIdsKey] = existingTransactionIds
                prefs.remove(LastSyncKey)
            }
        }
    }

    suspend fun recordSync(
        newRowFingerprints: Collection<String>,
        newTransactionIds: Collection<String>,
        epochMillis: Long,
    ) {
        dataStore.edit { prefs ->
            prefs[RowFingerprintsKey] = prefs[RowFingerprintsKey].orEmpty() + newRowFingerprints
            prefs[TransactionIdsKey] = prefs[TransactionIdsKey].orEmpty() + newTransactionIds
            prefs[LastSyncKey] = epochMillis
        }
    }

    suspend fun replaceRowFingerprints(fingerprints: Set<String>) {
        dataStore.edit { prefs -> prefs[RowFingerprintsKey] = fingerprints }
    }

    suspend fun unlink() {
        dataStore.edit { prefs ->
            prefs.remove(LinkKey)
            prefs.remove(RowFingerprintsKey)
            prefs.remove(TransactionIdsKey)
            prefs.remove(LastSyncKey)
        }
    }

    companion object {
        private val LinkKey = stringPreferencesKey("google_sheets_link")
        private val RowFingerprintsKey = stringSetPreferencesKey("google_sheets_known_rows")
        private val TransactionIdsKey = stringSetPreferencesKey("google_sheets_known_trn_ids")
        private val LastSyncKey = longPreferencesKey("google_sheets_last_sync")
    }
}
