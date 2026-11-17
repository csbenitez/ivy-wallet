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
    fun booleanFlow(pluginId: PluginId, key: String, default: Boolean): Flow<Boolean> =
        dataStore.data.map { it[booleanKey(pluginId, key)] ?: default }

    suspend fun getBoolean(pluginId: PluginId, key: String, default: Boolean): Boolean =
        booleanFlow(pluginId, key, default).first()

    suspend fun setBoolean(pluginId: PluginId, key: String, value: Boolean) {
        dataStore.edit { it[booleanKey(pluginId, key)] = value }
    }

    fun stringFlow(pluginId: PluginId, key: String): Flow<String?> =
        dataStore.data.map { it[stringKey(pluginId, key)] }

    suspend fun getString(pluginId: PluginId, key: String): String? =
        stringFlow(pluginId, key).first()

    /** Stores [value], or removes the key when [value] is null. */
    suspend fun setString(pluginId: PluginId, key: String, value: String?) {
        dataStore.edit {
            if (value == null) {
                it.remove(stringKey(pluginId, key))
            } else {
                it[stringKey(pluginId, key)] = value
            }
        }
    }

    fun longFlow(pluginId: PluginId, key: String): Flow<Long?> =
        dataStore.data.map { it[longKey(pluginId, key)] }

    suspend fun setLong(pluginId: PluginId, key: String, value: Long?) {
        dataStore.edit {
            if (value == null) {
                it.remove(longKey(pluginId, key))
            } else {
                it[longKey(pluginId, key)] = value
            }
        }
    }

    private fun name(pluginId: PluginId, key: String): String = "plugin.${pluginId.value}.$key"
    private fun booleanKey(pluginId: PluginId, key: String): Preferences.Key<Boolean> =
        booleanPreferencesKey(name(pluginId, key))

    private fun stringKey(pluginId: PluginId, key: String): Preferences.Key<String> =
        stringPreferencesKey(name(pluginId, key))

    private fun longKey(pluginId: PluginId, key: String): Preferences.Key<Long> =
        longPreferencesKey(name(pluginId, key))
}
