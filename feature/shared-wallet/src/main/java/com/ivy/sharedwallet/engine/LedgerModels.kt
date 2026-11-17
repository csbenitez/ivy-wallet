@file:Suppress("DataClassTypedIDs")

package com.ivy.sharedwallet.engine

import androidx.annotation.Keep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Identifies one synced row, e.g. ("transactions", "<uuid>").
 */
data class RecordKey(
    val type: String,
    val id: String,
)

/**
 * The last known state of one row in a shared ledger.
 *
 * Every record carries a timestamp and the device that produced it, and the
 * newest one wins (last-writer-wins register). Deleted rows are kept as
 * tombstones ([data] == null) so that deletions propagate to every member.
 */
@Keep
@Serializable
@Suppress("DataClassDefaultValues")
data class SyncRecord(
    @SerialName("type")
    val type: String,
    @SerialName("id")
    val id: String,
    @SerialName("updatedAt")
    val updatedAt: Long,
    @SerialName("deviceId")
    val deviceId: String,
    @SerialName("data")
    val data: JsonElement? = null,
) {
    val key: RecordKey get() = RecordKey(type, id)

    val isTombstone: Boolean get() = data == null || data is JsonNull
}

/**
 * Total order between two versions of the same row, so that every member
 * picks the same winner no matter in which order they see the versions.
 */
fun SyncRecord.winsOver(other: SyncRecord): Boolean = when {
    updatedAt != other.updatedAt -> updatedAt > other.updatedAt
    deviceId != other.deviceId -> deviceId > other.deviceId
    else -> payloadString() > other.payloadString()
}

private fun SyncRecord.payloadString(): String = if (isTombstone) "" else data.toString()

/**
 * A person/device that takes part in a shared ledger.
 */
@Keep
@Serializable
data class LedgerMember(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("lastSyncAt")
    val lastSyncAt: Long,
)

/**
 * The file that every member of a shared workspace reads and writes.
 */
@Keep
@Serializable
@Suppress("DataClassDefaultValues")
data class SharedLedgerDocument(
    @SerialName("format")
    val format: String = FORMAT,
    @SerialName("version")
    val version: Int = VERSION,
    @SerialName("ledgerId")
    val ledgerId: String,
    @SerialName("name")
    val name: String,
    @SerialName("members")
    val members: List<LedgerMember> = emptyList(),
    @SerialName("records")
    val records: List<SyncRecord> = emptyList(),
) {
    companion object {
        const val FORMAT = "ivy-wallet-shared-ledger"
        const val VERSION = 1
    }
}

/**
 * What this device knows about the shared ledger a workspace is linked to.
 * [records] is the merged state after the last successful sync.
 */
@Keep
@Serializable
@Suppress("DataClassDefaultValues")
data class SharingState(
    @SerialName("ledgerId")
    val ledgerId: String,
    @SerialName("ledgerName")
    val ledgerName: String,
    @SerialName("location")
    val location: String,
    @SerialName("deviceId")
    val deviceId: String,
    @SerialName("memberName")
    val memberName: String,
    @SerialName("records")
    val records: List<SyncRecord> = emptyList(),
    @SerialName("members")
    val members: List<LedgerMember> = emptyList(),
    @SerialName("lastSyncAt")
    val lastSyncAt: Long? = null,
    @SerialName("lastError")
    val lastError: String? = null,
)

/**
 * Changes that must be written to the local database to match the shared ledger.
 */
data class LedgerChanges(
    val upserts: List<SyncRecord>,
    val deletes: List<RecordKey>,
) {
    val isEmpty: Boolean get() = upserts.isEmpty() && deletes.isEmpty()
}

data class SyncReport(
    val received: Int,
    val deleted: Int,
    val sent: Int,
    val members: List<LedgerMember>,
)

sealed class SharedLedgerException(message: String) : Exception(message) {
    class NotShared : SharedLedgerException("This workspace isn't shared")
    class InvalidDocument(cause: String) :
        SharedLedgerException("The file isn't an Ivy Wallet shared workspace: $cause")
    class WrongLedger : SharedLedgerException("The file belongs to another shared workspace")
    class AlreadyShared : SharedLedgerException("The file already contains a shared workspace. Join it instead.")
    class UnsupportedVersion(version: Int) :
        SharedLedgerException("The shared workspace uses a newer format ($version). Update the app.")
}
