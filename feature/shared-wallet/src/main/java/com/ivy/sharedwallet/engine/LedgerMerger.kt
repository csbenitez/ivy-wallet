package com.ivy.sharedwallet.engine

import kotlinx.serialization.json.JsonElement

/**
 * Pure functions of the sync algorithm (a state-based last-writer-wins CRDT).
 *
 * 1. [stampLocalChanges]: compare the local database with the state of the last sync
 *    and give every row that changed locally a new timestamp (deleted rows become tombstones).
 * 2. [merge]: union of the local and remote records, keeping the newest version of each row.
 * 3. [changesToApply]: what must be written to the local database to match the merged state.
 *
 * Merging is commutative, associative and idempotent, so any number of members
 * converge to the same data no matter in which order they sync.
 */
object LedgerMerger {

    fun stampLocalChanges(
        localSnapshot: Map<RecordKey, JsonElement>,
        previous: Map<RecordKey, SyncRecord>,
        deviceId: String,
        now: Long,
    ): Map<RecordKey, SyncRecord> {
        val result = HashMap<RecordKey, SyncRecord>(localSnapshot.size + previous.size)

        for ((key, data) in localSnapshot) {
            val prev = previous[key]
            result[key] = if (prev != null && !prev.isTombstone && prev.data == data) {
                prev
            } else {
                SyncRecord(
                    type = key.type,
                    id = key.id,
                    updatedAt = nextTimestamp(prev, now),
                    deviceId = deviceId,
                    data = data,
                )
            }
        }

        for ((key, prev) in previous) {
            if (key in localSnapshot) continue
            result[key] = if (prev.isTombstone) {
                prev
            } else {
                // Deleted locally since the last sync
                SyncRecord(
                    type = key.type,
                    id = key.id,
                    updatedAt = nextTimestamp(prev, now),
                    deviceId = deviceId,
                    data = null,
                )
            }
        }
        return result
    }

    fun merge(vararg sources: Collection<SyncRecord>): Map<RecordKey, SyncRecord> {
        val result = HashMap<RecordKey, SyncRecord>()
        for (source in sources) {
            for (record in source) {
                val current = result[record.key]
                if (current == null || record.winsOver(current)) {
                    result[record.key] = record
                }
            }
        }
        return result
    }

    fun changesToApply(
        localSnapshot: Map<RecordKey, JsonElement>,
        merged: Map<RecordKey, SyncRecord>,
        supportedTypes: Set<String>,
    ): LedgerChanges {
        val upserts = mutableListOf<SyncRecord>()
        val deletes = mutableListOf<RecordKey>()
        for ((key, record) in merged) {
            if (key.type !in supportedTypes) continue
            val local = localSnapshot[key]
            if (record.isTombstone) {
                if (local != null) deletes += key
            } else if (local != record.data) {
                upserts += record
            }
        }
        return LedgerChanges(upserts = upserts, deletes = deletes)
    }

    /**
     * A local change must beat the version it was based on, even if this
     * device's clock is behind the clock of the member that wrote that version.
     */
    private fun nextTimestamp(prev: SyncRecord?, now: Long): Long =
        if (prev == null) now else maxOf(now, prev.updatedAt + 1)
}
