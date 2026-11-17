package com.ivy.sharedwallet.engine

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class LedgerSynchronizerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class FakeFile : LedgerTransport {
        var content: String? = null
        override suspend fun read(): String? = content
        override suspend fun write(content: String) {
            this.content = content
        }
    }

    private class FakeDb : LocalLedgerStore {
        val rows = linkedMapOf<RecordKey, JsonElement>()
        override val supportedTypes = setOf("transactions", "accounts")
        override suspend fun snapshot(): Map<RecordKey, JsonElement> = rows.toMap()
        override suspend fun apply(changes: LedgerChanges) {
            changes.deletes.forEach { rows.remove(it) }
            changes.upserts.forEach { rows[it.key] = it.data!! }
        }

        override fun normalize(record: SyncRecord) = record
    }

    private class FakeState(var state: SharingState?) : SharingStateStore {
        override suspend fun load() = state
        override suspend fun save(state: SharingState) {
            this.state = state
        }
    }

    private class Clock(var now: Long = 1_000) {
        fun tick(): Long = now++
    }

    private inner class Device(
        val name: String,
        file: LedgerTransport,
        clock: Clock,
        ledgerId: String = "ledger-1",
    ) {
        val db = FakeDb()
        val state = FakeState(
            SharingState(
                ledgerId = ledgerId,
                ledgerName = "Family",
                location = "file",
                deviceId = "device-$name",
                memberName = name,
            )
        )
        val synchronizer = LedgerSynchronizer(file, db, state, json, clock::tick)

        suspend fun sync() = synchronizer.sync()

        fun put(id: String, amount: Int, type: String = "transactions") {
            db.rows[RecordKey(type, id)] = trn(amount)
        }

        fun remove(id: String, type: String = "transactions") {
            db.rows.remove(RecordKey(type, id))
        }

        fun amount(id: String): Int? =
            db.rows[RecordKey("transactions", id)]?.let { (it as kotlinx.serialization.json.JsonObject)["amount"] }
                ?.toString()?.toInt()
    }

    private fun trn(amount: Int) = buildJsonObject { put("amount", JsonPrimitive(amount)) }

    @Test
    fun `changes from one member reach the others`() = runTest {
        val file = FakeFile()
        val clock = Clock()
        val ana = Device("Ana", file, clock)
        val luis = Device("Luis", file, clock)

        ana.put("t1", 10)
        ana.sync()
        luis.sync()

        luis.amount("t1") shouldBe 10
    }

    @Test
    fun `three members converge`() = runTest {
        val file = FakeFile()
        val clock = Clock()
        val devices = listOf(Device("Ana", file, clock), Device("Luis", file, clock), Device("Sofi", file, clock))

        devices[0].put("a", 1)
        devices[1].put("b", 2)
        devices[2].put("c", 3)
        repeat(2) { devices.forEach { it.sync() } }

        devices.forEach { device ->
            device.db.rows.keys.map { it.id }.toSet() shouldBe setOf("a", "b", "c")
        }
        (devices[0].sync().members) shouldHaveSize 3
    }

    @Test
    fun `edits propagate and the latest edit wins`() = runTest {
        val file = FakeFile()
        val clock = Clock()
        val ana = Device("Ana", file, clock)
        val luis = Device("Luis", file, clock)
        ana.put("t1", 10)
        ana.sync()
        luis.sync()

        // Both edit the same transaction offline, Luis edits last
        ana.put("t1", 20)
        clock.now += 100
        luis.put("t1", 30)
        ana.sync()
        luis.sync()
        ana.sync()

        ana.amount("t1") shouldBe 30
        luis.amount("t1") shouldBe 30
    }

    @Test
    fun `deletions propagate`() = runTest {
        val file = FakeFile()
        val clock = Clock()
        val ana = Device("Ana", file, clock)
        val luis = Device("Luis", file, clock)
        ana.put("t1", 10)
        ana.put("t2", 5)
        ana.sync()
        luis.sync()

        luis.remove("t1")
        luis.sync()
        ana.sync()

        ana.amount("t1") shouldBe null
        ana.amount("t2") shouldBe 5
    }

    @Test
    fun `a lost upload doesn't lose data or delete rows`() = runTest {
        val file = FakeFile()
        val clock = Clock()
        val ana = Device("Ana", file, clock)
        val luis = Device("Luis", file, clock)
        ana.put("t1", 10)
        ana.sync()
        luis.sync()

        // Ana and Luis sync at the same time: Luis overwrites Ana's upload
        val before = file.content
        ana.put("t2", 7)
        ana.sync()
        file.content = before
        luis.put("t3", 9)
        luis.sync()

        ana.sync()
        luis.sync()

        ana.db.rows.keys.map { it.id }.toSet() shouldBe setOf("t1", "t2", "t3")
        luis.db.rows.keys.map { it.id }.toSet() shouldBe setOf("t1", "t2", "t3")
    }

    @Test
    fun `local edit beats an older remote version even with a slow clock`() = runTest {
        val file = FakeFile()
        val fastClock = Clock(now = 10_000)
        val slowClock = Clock(now = 1_000)
        val ana = Device("Ana", file, fastClock)
        val luis = Device("Luis", file, slowClock)
        ana.put("t1", 10)
        ana.sync()
        luis.sync()

        luis.put("t1", 99)
        luis.sync()
        ana.sync()

        ana.amount("t1") shouldBe 99
    }

    @Test
    fun `unchanged data isn't re-sent`() = runTest {
        val file = FakeFile()
        val clock = Clock()
        val ana = Device("Ana", file, clock)
        ana.put("t1", 10)
        ana.sync().sent shouldBe 1
        ana.sync().sent shouldBe 0
    }

    @Test
    fun `refuses a file from another shared workspace`() = runTest {
        val file = FakeFile()
        val clock = Clock()
        Device("Ana", file, clock, ledgerId = "vacations").sync()

        val error = runCatching { Device("Luis", file, clock, ledgerId = "monthly").sync() }.exceptionOrNull()
        error.shouldBeInstanceOf<SharedLedgerException.WrongLedger>()
    }

    @Test
    fun `refuses a file that isn't a shared workspace`() = runTest {
        val file = FakeFile().apply { content = "{\"hello\": 1}" }
        val error = runCatching { Device("Ana", file, Clock()).sync() }.exceptionOrNull()
        error.shouldBeInstanceOf<SharedLedgerException.InvalidDocument>()
    }

    @Test
    fun `unknown record types are kept but not applied`() = runTest {
        val file = FakeFile()
        val clock = Clock()
        val ana = Device("Ana", file, clock)
        ana.sync()
        val doc = json.decodeFromString(SharedLedgerDocument.serializer(), file.content!!)
        file.content = json.encodeToString(
            SharedLedgerDocument.serializer(),
            doc.copy(records = doc.records + SyncRecord("future_type", "x", 5, "device-z", trn(1)))
        )

        ana.sync()

        ana.db.rows.keys.none { it.type == "future_type" } shouldBe true
        json.decodeFromString(SharedLedgerDocument.serializer(), file.content!!)
            .records.any { it.type == "future_type" } shouldBe true
    }
}
