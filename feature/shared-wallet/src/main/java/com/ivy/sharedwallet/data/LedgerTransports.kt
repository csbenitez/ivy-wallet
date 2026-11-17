package com.ivy.sharedwallet.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ivy.sharedwallet.engine.LedgerTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.OutputStream
import javax.inject.Inject

/**
 * Knows how to reach a shared ledger file given its location string.
 *
 * Bound into a Hilt set, so other backends (e.g. a Google Drive API plugin)
 * can contribute their own provider without changing the sync engine.
 */
interface LedgerTransportProvider {
    fun supports(location: String): Boolean

    fun create(location: String): LedgerTransport

    /**
     * Human readable name of the location, e.g. the file name.
     */
    fun displayName(location: String): String?
}

class LedgerTransportFactory @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards LedgerTransportProvider>,
) {
    fun create(location: String): LedgerTransport = provider(location).create(location)

    fun displayName(location: String): String? = try {
        provider(location).displayName(location)
    } catch (e: Exception) {
        null
    }

    private fun provider(location: String): LedgerTransportProvider =
        providers.firstOrNull { it.supports(location) }
            ?: error("No backend can open $location")
}

/**
 * A file picked with the Android file picker (Storage Access Framework).
 * Works with Google Drive, OneDrive, Dropbox, Nextcloud or local storage,
 * and doesn't need any API key: the cloud app does the syncing.
 */
class DocumentFileTransportProvider @Inject constructor(
    @ApplicationContext
    private val context: Context,
) : LedgerTransportProvider {
    override fun supports(location: String): Boolean = location.startsWith("content://")

    override fun create(location: String): LedgerTransport =
        DocumentFileTransport(context, Uri.parse(location))

    override fun displayName(location: String): String? =
        context.contentResolver.query(
            Uri.parse(location),
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}

class DocumentFileTransport(
    private val context: Context,
    private val uri: Uri,
) : LedgerTransport {
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException(uri.toString())
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override suspend fun write(content: String): Unit = withContext(Dispatchers.IO) {
        // "wt" truncates the file; some providers only support "w".
        val stream = openForWriting() ?: throw FileNotFoundException(uri.toString())
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
    }

    private fun openForWriting(): OutputStream? = try {
        context.contentResolver.openOutputStream(uri, "wt")
    } catch (e: IllegalArgumentException) {
        context.contentResolver.openOutputStream(uri, "w")
    } catch (e: FileNotFoundException) {
        context.contentResolver.openOutputStream(uri, "w")
    }
}
