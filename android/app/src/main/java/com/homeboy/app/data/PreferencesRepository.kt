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
        val KEY_LOCATIONS_VIEW_MODE = stringPreferencesKey("locations_view_mode")
        val KEY_TAGS_VIEW_MODE = stringPreferencesKey("tags_view_mode")
        val KEY_SHOW_ARCHIVED = booleanPreferencesKey("show_archived")
        val KEY_KEEP_LOCATION = booleanPreferencesKey("keep_location")
        val KEY_KEEP_TAGS = booleanPreferencesKey("keep_tags")
        val KEY_LAST_LOCATION = stringPreferencesKey("last_location")
        val KEY_LAST_TAGS = stringPreferencesKey("last_tags")
        val KEY_RECENT_ICONS = stringPreferencesKey("recent_tag_icons")
        const val MAX_RECENT_ICONS = 24
        val KEY_AI_SEARCH_ENABLED = booleanPreferencesKey("ai_search_enabled")
        val KEY_AI_GEN_MODEL_ID = stringPreferencesKey("ai_gen_model_id")
        val KEY_AI_EMBED_MODEL_ID = stringPreferencesKey("ai_embed_model_id")
        val KEY_AI_CUSTOM_MODELS = stringPreferencesKey("ai_custom_models")
        val KEY_HF_TOKEN = stringPreferencesKey("hf_token")
        val KEY_AI_TAGS_ENABLED = booleanPreferencesKey("ai_tags_enabled")
        val KEY_AI_UNLOAD_MINUTES = intPreferencesKey("ai_unload_minutes")
        val KEY_AI_MODEL_BACKENDS = stringPreferencesKey("ai_model_backends")
        const val DEFAULT_EMBED_MODEL_ID = "nomic-embed-v1.5"
        const val DEFAULT_UNLOAD_MINUTES = 5
    }

    /** Whether semantic (embedding-based) search is enabled. Off until a model is downloaded. */
    val aiSearchEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_AI_SEARCH_ENABLED] ?: false }
    suspend fun setAiSearchEnabled(v: Boolean) = context.dataStore.edit { it[KEY_AI_SEARCH_ENABLED] = v }

    /** The selected generative model id for tag suggestions (null = none chosen). */
    val aiGenModelId: Flow<String?> = context.dataStore.data.map { it[KEY_AI_GEN_MODEL_ID] }
    suspend fun setAiGenModelId(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(KEY_AI_GEN_MODEL_ID) else it[KEY_AI_GEN_MODEL_ID] = id
    }

    /** The active embedding model used for semantic search. */
    val aiEmbedModelId: Flow<String> =
        context.dataStore.data.map { it[KEY_AI_EMBED_MODEL_ID] ?: DEFAULT_EMBED_MODEL_ID }
    suspend fun setAiEmbedModelId(id: String) = context.dataStore.edit { it[KEY_AI_EMBED_MODEL_ID] = id }

    /** Raw JSON for user-added custom models (serialized by ModelRepository). */
    val aiCustomModelsJson: Flow<String> = context.dataStore.data.map { it[KEY_AI_CUSTOM_MODELS] ?: "" }
    suspend fun setAiCustomModelsJson(json: String) =
        context.dataStore.edit { it[KEY_AI_CUSTOM_MODELS] = json }

    /** HuggingFace access token (optional) — higher rate limits + gated-model downloads. */
    val hfToken: Flow<String> = context.dataStore.data.map { it[KEY_HF_TOKEN] ?: "" }
    suspend fun setHfToken(t: String) = context.dataStore.edit {
        if (t.isBlank()) it.remove(KEY_HF_TOKEN) else it[KEY_HF_TOKEN] = t.trim()
    }
    suspend fun getHfToken(): String = hfToken.first()

    /** Whether AI tag suggestions (generative LLM) are enabled in the Add/Edit Item screen. */
    val aiTagsEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_AI_TAGS_ENABLED] ?: false }
    suspend fun setAiTagsEnabled(v: Boolean) = context.dataStore.edit { it[KEY_AI_TAGS_ENABLED] = v }

    /** Minutes of idle time before an LLM is unloaded from memory (0 = keep loaded). */
    val aiUnloadMinutes: Flow<Int> =
        context.dataStore.data.map { it[KEY_AI_UNLOAD_MINUTES] ?: DEFAULT_UNLOAD_MINUTES }
    suspend fun setAiUnloadMinutes(m: Int) = context.dataStore.edit { it[KEY_AI_UNLOAD_MINUTES] = m }

    /**
     * Per-model backend override (modelId -> "NPU"/"GPU"/"CPU"). A model with no entry uses the
     * smart default (embeddings → NPU, generation → CPU). Stored as a compact JSON object.
     */
    val aiModelBackends: Flow<Map<String, String>> =
        context.dataStore.data.map { parseBackends(it[KEY_AI_MODEL_BACKENDS]) }

    suspend fun setAiModelBackend(modelId: String, backend: String?) =
        context.dataStore.edit { prefs ->
            val map = parseBackends(prefs[KEY_AI_MODEL_BACKENDS]).toMutableMap()
            if (backend == null) map.remove(modelId) else map[modelId] = backend
            prefs[KEY_AI_MODEL_BACKENDS] =
                map.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }
        }

    private fun parseBackends(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
            com.google.gson.Gson().fromJson<Map<String, String>>(raw, type) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { it[KEY_SERVER_URL] ?: "" }
    val token: Flow<String> = context.dataStore.data.map { it[KEY_TOKEN] ?: "" }
    val tenant: Flow<String?> = context.dataStore.data.map { it[KEY_TENANT] }
    val tenantName: Flow<String> = context.dataStore.data.map { it[KEY_TENANT_NAME] ?: "" }
    val themeIndex: Flow<Int> = context.dataStore.data.map { it[KEY_THEME_INDEX] ?: 0 }
    val itemsSort: Flow<String> = context.dataStore.data.map { it[KEY_ITEMS_SORT] ?: "name_asc" }
    val itemsViewMode: Flow<String> = context.dataStore.data.map { it[KEY_ITEMS_VIEW_MODE] ?: "list" }
    val locationsViewMode: Flow<String> = context.dataStore.data.map { it[KEY_LOCATIONS_VIEW_MODE] ?: "list" }
    val tagsViewMode: Flow<String> = context.dataStore.data.map { it[KEY_TAGS_VIEW_MODE] ?: "list" }
    val keepLocation: Flow<Boolean> = context.dataStore.data.map { it[KEY_KEEP_LOCATION] ?: false }
    val keepTags: Flow<Boolean> = context.dataStore.data.map { it[KEY_KEEP_TAGS] ?: false }

    /** Most-recently-used tag icon keys, newest first. Acts as the local icon cache. */
    val recentIcons: Flow<List<String>> = context.dataStore.data.map { prefs ->
        (prefs[KEY_RECENT_ICONS] ?: "").split(",").filter { it.isNotBlank() }
    }

    /** Promote [key] to the front of the recents list, de-duped and capped. */
    suspend fun addRecentIcon(key: String) {
        if (key.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = (prefs[KEY_RECENT_ICONS] ?: "").split(",").filter { it.isNotBlank() }
            val updated = (listOf(key) + current.filter { it != key }).take(MAX_RECENT_ICONS)
            prefs[KEY_RECENT_ICONS] = updated.joinToString(",")
        }
    }

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
    suspend fun setLocationsViewMode(m: String) = context.dataStore.edit { it[KEY_LOCATIONS_VIEW_MODE] = m }
    suspend fun setTagsViewMode(m: String) = context.dataStore.edit { it[KEY_TAGS_VIEW_MODE] = m }

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
