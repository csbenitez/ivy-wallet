package com.ivy.sharedwallet.data

import com.ivy.sharedwallet.engine.SharingState
import com.ivy.sharedwallet.engine.SharingStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Stores the sharing state of one workspace in a private JSON file.
 */
class FileSharingStateStore(
    private val file: File,
    private val json: Json,
) : SharingStateStore {

    fun exists(): Boolean = file.exists()

    fun loadBlocking(): SharingState? = try {
        if (file.exists()) json.decodeFromString(SharingState.serializer(), file.readText()) else null
    } catch (e: Exception) {
        Timber.e(e, "Corrupted sharing state ${file.name}")
        null
    }

    override suspend fun load(): SharingState? = withContext(Dispatchers.IO) { loadBlocking() }

    override suspend fun save(state: SharingState) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(SharingState.serializer(), state))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        Unit
    }
}
