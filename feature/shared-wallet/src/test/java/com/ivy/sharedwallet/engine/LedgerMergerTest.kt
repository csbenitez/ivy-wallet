package com.ivy.sharedwallet.engine

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class LedgerMergerTest {
    private val key = RecordKey("transactions", "1")

    private fun record(updatedAt: Long, device: String, value: Int?) =
        SyncRecord("transactions", "1", updatedAt, device, value?.let { JsonPrimitive(it) })

    @Test
    fun `merge is order independent`() {
        val a = record(5, "a", 1)
        val b = record(5, "b", 2)
        val c = record(3, "c", null)

        LedgerMerger.merge(listOf(a, b, c))[key] shouldBe b
        LedgerMerger.merge(listOf(c), listOf(b), listOf(a))[key] shouldBe b
    }

    @Test
    fun `unchanged rows keep their previous record`() {
        val prev = record(5, "a", 1)
        val stamped = LedgerMerger.stampLocalChanges(mapOf(key to JsonPrimitive(1)), mapOf(key to prev), "me", 100)
        stamped[key] shouldBe prev
    }

    @Test
    fun `local deletions become tombstones`() {
        val prev = record(5, "a", 1)
        val stamped = LedgerMerger.stampLocalChanges(emptyMap(), mapOf(key to prev), "me", 100)
        stamped[key] shouldBe record(100, "me", null)
    }

    @Test
    fun `recreated rows win over their tombstone`() {
        val prev = record(5, "a", null)
        val stamped = LedgerMerger.stampLocalChanges(mapOf(key to JsonPrimitive(3)), mapOf(key to prev), "me", 1)
        stamped[key] shouldBe record(6, "me", 3)
    }

    @Test
    fun `changes to apply`() {
        val merged = mapOf(
            key to record(5, "a", 2),
            RecordKey("transactions", "2") to SyncRecord("transactions", "2", 1, "a", null),
        )
        val local = mapOf(
            key to JsonPrimitive(1),
            RecordKey("transactions", "2") to JsonPrimitive(1),
        )
        val changes = LedgerMerger.changesToApply(local, merged, setOf("transactions"))
        changes.upserts shouldBe listOf(record(5, "a", 2))
        changes.deletes shouldBe listOf(RecordKey("transactions", "2"))
    }
}
