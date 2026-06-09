package com.homeboy.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "homeboy_settings")

class PreferencesRepository(private val context: Context) {

    companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_TENANT = stringPreferencesKey("tenant")
        val KEY_TENANT_NAME = stringPreferencesKey("tenant_name")
        val KEY_THEME_INDEX = intPreferencesKey("theme_index")
        val KEY_ITEMS_SORT = stringPreferencesKey("items_sort")
        val KEY_ITEMS_VIEW_MODE = stringPreferencesKey("items_view_mode")
        val KEY_SHOW_ARCHIVED = booleanPreferencesKey("show_archived")
        val KEY_KEEP_LOCATION = booleanPreferencesKey("keep_location")
        val KEY_KEEP_TAGS = booleanPreferencesKey("keep_tags")
        val KEY_LAST_LOCATION = stringPreferencesKey("last_location")
        val KEY_LAST_TAGS = stringPreferencesKey("last_tags")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { it[KEY_SERVER_URL] ?: "" }
    val token: Flow<String> = context.dataStore.data.map { it[KEY_TOKEN] ?: "" }
    val tenant: Flow<String?> = context.dataStore.data.map { it[KEY_TENANT] }
    val tenantName: Flow<String> = context.dataStore.data.map { it[KEY_TENANT_NAME] ?: "" }
    val themeIndex: Flow<Int> = context.dataStore.data.map { it[KEY_THEME_INDEX] ?: 0 }
    val itemsSort: Flow<String> = context.dataStore.data.map { it[KEY_ITEMS_SORT] ?: "name_asc" }
    val itemsViewMode: Flow<String> = context.dataStore.data.map { it[KEY_ITEMS_VIEW_MODE] ?: "list" }
    val keepLocation: Flow<Boolean> = context.dataStore.data.map { it[KEY_KEEP_LOCATION] ?: false }
    val keepTags: Flow<Boolean> = context.dataStore.data.map { it[KEY_KEEP_TAGS] ?: false }

    suspend fun setServerUrl(url: String) = context.dataStore.edit { it[KEY_SERVER_URL] = url }
    suspend fun setToken(t: String) = context.dataStore.edit { it[KEY_TOKEN] = t }

    suspend fun setKeepLocation(v: Boolean) = context.dataStore.edit { it[KEY_KEEP_LOCATION] = v }
    suspend fun setKeepTags(v: Boolean) = context.dataStore.edit { it[KEY_KEEP_TAGS] = v }
    suspend fun setLastLocation(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(KEY_LAST_LOCATION) else it[KEY_LAST_LOCATION] = id
    }
    suspend fun setLastTags(ids: List<String>) = context.dataStore.edit {
        it[KEY_LAST_TAGS] = ids.joinToString(",")
    }
    suspend fun getKeepLocation() = keepLocation.first()
    suspend fun getKeepTags() = keepTags.first()
    suspend fun getLastLocation() = context.dataStore.data.first()[KEY_LAST_LOCATION]
    suspend fun getLastTags(): List<String> =
        (context.dataStore.data.first()[KEY_LAST_TAGS] ?: "").split(",").filter { it.isNotBlank() }

    suspend fun setTenant(id: String?, name: String) {
        context.dataStore.edit {
            if (id == null) it.remove(KEY_TENANT) else it[KEY_TENANT] = id
            it[KEY_TENANT_NAME] = name
        }
    }

    suspend fun setThemeIndex(i: Int) = context.dataStore.edit { it[KEY_THEME_INDEX] = i }
    suspend fun setItemsSort(s: String) = context.dataStore.edit { it[KEY_ITEMS_SORT] = s }
    suspend fun setItemsViewMode(m: String) = context.dataStore.edit { it[KEY_ITEMS_VIEW_MODE] = m }

    suspend fun getServerUrl() = serverUrl.first()
    suspend fun getToken() = token.first()
    suspend fun getTenant() = tenant.first()
    suspend fun getThemeIndex() = themeIndex.first()
    suspend fun isLoggedIn() = getToken().isNotBlank() && getServerUrl().isNotBlank()

    suspend fun logout() {
        context.dataStore.edit {
            it.remove(KEY_TOKEN)
            it.remove(KEY_TENANT)
            it.remove(KEY_TENANT_NAME)
        }
    }
}
