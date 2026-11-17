package com.ivy.data.workspace

import android.content.Context
import androidx.annotation.Keep
import com.ivy.data.db.IvyRoomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A workspace is a fully independent wallet (its own accounts, categories,
 * transactions, budgets...) stored in its own Room database file.
 * E.g. "Monthly budget" and "Family vacation".
 */
@Keep
@Serializable
data class Workspace(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("dbName")
    val dbName: String,
)

@Keep
@Serializable
data class WorkspacesConfig(
    @SerialName("activeId")
    val activeId: String = WorkspaceRegistry.DEFAULT_WORKSPACE_ID,
    @SerialName("workspaces")
    val workspaces: List<Workspace> = listOf(WorkspaceRegistry.defaultWorkspace()),
)

/**
 * Keeps the list of workspaces and which one is active.
 *
 * It is read synchronously when the Room database is created, so it is backed
 * by a small JSON file instead of DataStore.
 * The default workspace uses the legacy database file, so existing users keep their data.
 */
@Singleton
class WorkspaceRegistry @Inject constructor(
    @ApplicationContext
    private val context: Context,
) {
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun config(): WorkspacesConfig {
        val config = try {
            if (file.exists()) {
                json.decodeFromString(WorkspacesConfig.serializer(), file.readText())
            } else {
                WorkspacesConfig()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read workspaces config")
            WorkspacesConfig()
        }
        return config.sanitized()
    }

    fun all(): List<Workspace> = config().workspaces

    fun active(): Workspace {
        val config = config()
        return config.workspaces.first { it.id == config.activeId }
    }

    fun find(id: String): Workspace? = all().firstOrNull { it.id == id }

    @Synchronized
    fun create(name: String): Workspace {
        val id = UUID.randomUUID().toString()
        val workspace = Workspace(
            id = id,
            name = name.trim().ifBlank { "Workspace" },
            dbName = "ivywallet_$id.db",
        )
        val config = config()
        save(config.copy(workspaces = config.workspaces + workspace))
        return workspace
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val config = config()
        save(
            config.copy(
                workspaces = config.workspaces.map {
                    if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it
                }
            )
        )
    }

    @Synchronized
    fun setActive(id: String) {
        val config = config()
        require(config.workspaces.any { it.id == id }) { "Unknown workspace $id" }
        save(config.copy(activeId = id))
    }

    /**
     * Deletes a workspace and its database. The active and the default workspace can't be deleted.
     */
    @Synchronized
    fun delete(id: String) {
        val config = config()
        require(id != config.activeId) { "Can't delete the active workspace" }
        require(id != DEFAULT_WORKSPACE_ID) { "Can't delete the default workspace" }
        val workspace = config.workspaces.firstOrNull { it.id == id } ?: return
        save(config.copy(workspaces = config.workspaces - workspace))
        context.deleteDatabase(workspace.dbName)
    }

    private fun save(config: WorkspacesConfig) {
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(WorkspacesConfig.serializer(), config.sanitized()))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun WorkspacesConfig.sanitized(): WorkspacesConfig {
        val list = if (workspaces.none { it.id == DEFAULT_WORKSPACE_ID }) {
            listOf(defaultWorkspace()) + workspaces
        } else {
            workspaces
        }
        val active = activeId.takeIf { id -> list.any { it.id == id } } ?: DEFAULT_WORKSPACE_ID
        return WorkspacesConfig(activeId = active, workspaces = list)
    }

    companion object {
        const val DEFAULT_WORKSPACE_ID = "default"
        private const val FILE_NAME = "workspaces.json"

        fun defaultWorkspace() = Workspace(
            id = DEFAULT_WORKSPACE_ID,
            name = "Personal",
            dbName = IvyRoomDatabase.DB_NAME,
        )
    }
}
