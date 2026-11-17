package com.ivy.plugins.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.ivy.data.DataObserver
import com.ivy.data.backup.BackupDataUseCase
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SnapshotSyncEngineTest {
    private val backupDataUseCase = mockk<BackupDataUseCase>(relaxed = true)
    private lateinit var store: FakeRemoteSnapshotStore
    private lateinit var engine: SnapshotSyncEngine

    @Before
    fun setup() {
        coEvery { backupDataUseCase.generateJsonBackup() } returns LOCAL_JSON
        store = FakeRemoteSnapshotStore()
        engine = SnapshotSyncEngine(
            backupDataUseCase = backupDataUseCase,
            dataObserver = DataObserver(),
            preferences = PluginPreferences(FakeDataStore()),
        )
    }

    @Test
    fun `two-way sync without remote copy only uploads`() = runTest {
        // when
        val result = engine.sync(PLUGIN_ID, store, SyncMode.TWO_WAY)

        // then
        result shouldBe SyncResult.Success(pulledRemoteChanges = false, pushedLocalChanges = true)
        store.content shouldBe LOCAL_JSON
        coVerify(exactly = 0) { backupDataUseCase.importJson(any(), any(), any()) }
    }

    @Test
    fun `two-way sync merges remote changes made by another device`() = runTest {
        // given
        store.content = REMOTE_JSON
        store.revision = 7

        // when
        val result = engine.sync(PLUGIN_ID, store, SyncMode.TWO_WAY)

        // then
        result shouldBe SyncResult.Success(pulledRemoteChanges = true, pushedLocalChanges = true)
        coVerify(exactly = 1) { backupDataUseCase.importJson(REMOTE_JSON, any(), any()) }
        store.content shouldBe LOCAL_JSON
    }

    @Test
    fun `two-way sync doesn't re-import its own upload`() = runTest {
        // given
        engine.sync(PLUGIN_ID, store, SyncMode.TWO_WAY)

        // when
        val result = engine.sync(PLUGIN_ID, store, SyncMode.TWO_WAY)

        // then
        result shouldBe SyncResult.Success(pulledRemoteChanges = false, pushedLocalChanges = true)
        coVerify(exactly = 0) { backupDataUseCase.importJson(any(), any(), any()) }
    }

    @Test
    fun `restore without remote copy reports nothing to restore`() = runTest {
        // when
        val result = engine.sync(PLUGIN_ID, store, SyncMode.PULL_ONLY)

        // then
        result shouldBe SyncResult.NothingToRestore
    }

    @Test
    fun `errors are reported instead of thrown`() = runTest {
        // given
        store.failWith = IllegalStateException("offline")

        // when
        val result = engine.sync(PLUGIN_ID, store, SyncMode.TWO_WAY)

        // then
        result shouldBe SyncResult.Error("offline")
    }

    private class FakeRemoteSnapshotStore : RemoteSnapshotStore {
        var content: String? = null
        var revision = 0
        var failWith: Exception? = null

        override suspend fun current(): RemoteSnapshot? {
            failWith?.let { throw it }
            return content?.let { RemoteSnapshot(id = FILE_ID, revision = revision.toString()) }
        }

        override suspend fun download(snapshot: RemoteSnapshot): String = content!!

        override suspend fun upload(content: String, existing: RemoteSnapshot?): RemoteSnapshot {
            this.content = content
            revision++
            return RemoteSnapshot(id = FILE_ID, revision = revision.toString())
        }
    }

    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences
        ): Preferences = transform(state.value).also { state.value = it }
    }

    companion object {
        private val PLUGIN_ID = PluginId("test")
        private val FILE_ID = RemoteSnapshotId("file")
        private const val LOCAL_JSON = "{\"local\":true}"
        private const val REMOTE_JSON = "{\"remote\":true}"
    }
}
