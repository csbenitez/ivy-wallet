package com.ivy.sharedwallet.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Reads and writes the shared ledger file wherever it lives
 * (a file picked through the Android file picker, e.g. in Google Drive,
 * or any other backend provided by a plugin).
 */
interface LedgerTransport {
    /**
     * @return the file content, or null/blank if the file is empty.
     */
    suspend fun read(): String?

    suspend fun write(content: String)
}

/**
 * The local data of one workspace.
 */
interface LocalLedgerStore {
    val supportedTypes: Set<String>

    suspend fun snapshot(): Map<RecordKey, JsonElement>

    suspend fun apply(changes: LedgerChanges)

    /**
     * Re-encodes a record coming from another member the way this device
     * encodes it, so that equal rows compare equal. Unknown types are returned as is.
     */
    fun normalize(record: SyncRecord): SyncRecord
}

interface SharingStateStore {
    suspend fun load(): SharingState?

    suspend fun save(state: SharingState)
}

class LedgerSynchronizer(
    private val transport: LedgerTransport,
    private val store: LocalLedgerStore,
    private val stateStore: SharingStateStore,
    private val json: Json,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun sync(): SyncReport {
        val state = stateStore.load() ?: throw SharedLedgerException.NotShared()
        val now = clock()

        val remote = readDocument(transport, json)
        if (remote != null && remote.ledgerId != state.ledgerId) {
            throw SharedLedgerException.WrongLedger()
        }

        val local = store.snapshot()
        val stamped = LedgerMerger.stampLocalChanges(
            localSnapshot = local,
            previous = state.records.associateBy { it.key },
            deviceId = state.deviceId,
            now = now,
        )
        val remoteRecords = remote?.records.orEmpty().map(store::normalize)
        val remoteByKey = remoteRecords.associateBy { it.key }
        val merged = LedgerMerger.merge(stamped.values, remoteRecords)

        val changes = LedgerMerger.changesToApply(local, merged, store.supportedTypes)
        if (!changes.isEmpty) {
            store.apply(changes)
        }

        val members = (
            remote?.members.orEmpty().filter { it.id != state.deviceId } +
                LedgerMember(id = state.deviceId, name = state.memberName, lastSyncAt = now)
            ).sortedBy { it.name.lowercase() }
        val records = merged.values.sortedWith(compareBy({ it.type }, { it.id }))

        // Save the local state before uploading: if the upload fails, the next sync
        // still knows about everything and will upload it again.
        stateStore.save(
            state.copy(
                records = records,
                members = members,
                lastSyncAt = now,
                lastError = null,
            )
        )

        transport.write(
            json.encodeToString(
                SharedLedgerDocument.serializer(),
                SharedLedgerDocument(
                    ledgerId = state.ledgerId,
                    name = remote?.name ?: state.ledgerName,
                    members = members,
                    records = records,
                )
            )
        )

        val sent = records.count { record ->
            val remoteRecord = remoteByKey[record.key]
            remoteRecord == null || record.winsOver(remoteRecord)
        }
        return SyncReport(
            received = changes.upserts.size,
            deleted = changes.deletes.size,
            sent = sent,
            members = members,
        )
    }

    companion object {
        /**
         * @return the shared ledger stored in the file, or null if the file is empty.
         */
        suspend fun readDocument(transport: LedgerTransport, json: Json): SharedLedgerDocument? {
            val content = transport.read()
            if (content.isNullOrBlank()) return null
            val document = try {
                json.decodeFromString(SharedLedgerDocument.serializer(), content.trim().removePrefix("﻿"))
            } catch (e: Exception) {
                throw SharedLedgerException.InvalidDocument(e.message ?: e.javaClass.simpleName)
            }
            if (document.format != SharedLedgerDocument.FORMAT) {
                throw SharedLedgerException.InvalidDocument("unknown format '${document.format}'")
            }
            if (document.version > SharedLedgerDocument.VERSION) {
                throw SharedLedgerException.UnsupportedVersion(document.version)
            }
            return document
        }
    }
}
