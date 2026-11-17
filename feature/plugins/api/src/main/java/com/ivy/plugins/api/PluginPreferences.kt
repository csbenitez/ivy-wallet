package com.ivy.plugins.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Small key-value storage scoped per plugin, so plugins can persist
 * their own settings without clashing with each other or with the app.
 */
class PluginPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    fun booleanFlow(pluginId: String, key: String, default: Boolean): Flow<Boolean> =
        dataStore.data.map { it[booleanKey(pluginId, key)] ?: default }

    suspend fun getBoolean(pluginId: String, key: String, default: Boolean): Boolean =
        booleanFlow(pluginId, key, default).first()

    suspend fun setBoolean(pluginId: String, key: String, value: Boolean) {
        dataStore.edit { it[booleanKey(pluginId, key)] = value }
    }

    fun stringFlow(pluginId: String, key: String): Flow<String?> =
        dataStore.data.map { it[stringKey(pluginId, key)] }

    suspend fun getString(pluginId: String, key: String): String? =
        stringFlow(pluginId, key).first()

    /** Stores [value], or removes the key when [value] is null. */
    suspend fun setString(pluginId: String, key: String, value: String?) {
        dataStore.edit {
            if (value == null) {
                it.remove(stringKey(pluginId, key))
            } else {
                it[stringKey(pluginId, key)] = value
            }
        }
    }

    fun longFlow(pluginId: String, key: String): Flow<Long?> =
        dataStore.data.map { it[longKey(pluginId, key)] }

    suspend fun setLong(pluginId: String, key: String, value: Long?) {
        dataStore.edit {
            if (value == null) {
                it.remove(longKey(pluginId, key))
            } else {
                it[longKey(pluginId, key)] = value
            }
        }
    }

    private fun name(pluginId: String, key: String) = "plugin.$pluginId.$key"
    private fun booleanKey(pluginId: String, key: String) =
        booleanPreferencesKey(name(pluginId, key))

    private fun stringKey(pluginId: String, key: String) =
        stringPreferencesKey(name(pluginId, key))

    private fun longKey(pluginId: String, key: String) =
        longPreferencesKey(name(pluginId, key))
}
