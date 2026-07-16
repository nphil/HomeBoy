package com.homeboy.app.ui.items

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.ai.EmbeddingService
import com.homeboy.app.ai.ModelRepository
import com.homeboy.app.api.HBItem
import com.homeboy.app.api.HBItemCreate
import com.homeboy.app.api.HBItemUpdate
import com.homeboy.app.api.HBLocation
import com.homeboy.app.api.HBTag
import com.homeboy.app.data.ConnectionMonitor
import com.homeboy.app.data.HomeboxRepository
import com.homeboy.app.data.PreferencesRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortMode(val label: String) {
    NAME_ASC("Name A→Z"),
    NAME_DESC("Name Z→A"),
    NEWEST("Newest"),
    OLDEST("Oldest"),
    QTY_HIGH("Qty High→Low"),
    QTY_LOW("Qty Low→High")
}

class ItemsViewModel(
    private val appContext: Context,
    private val repo: HomeboxRepository,
    private val prefs: PreferencesRepository
) : ViewModel() {

    /** Snapshot of the AI-search preference, kept current so load() can read it synchronously. */
    private var aiSearchEnabled = false

    private val _items = MutableStateFlow<List<HBItem>>(emptyList())
    val items = _items.asStateFlow()

    private val _locations = MutableStateFlow<List<HBLocation>>(emptyList())
    val locations = _locations.asStateFlow()

    private val _tags = MutableStateFlow<List<HBTag>>(emptyList())
    val tags = _tags.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _filterLocationId = MutableStateFlow<String?>(null)
    val filterLocationId = _filterLocationId.asStateFlow()

    private val _filterTagId = MutableStateFlow<String?>(null)
    val filterTagId = _filterTagId.asStateFlow()

    private val _showArchived = MutableStateFlow(false)
    val showArchived = _showArchived.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.NAME_ASC)
    val sortMode = _sortMode.asStateFlow()

    private val _viewMode = MutableStateFlow("list")
    val viewMode = _viewMode.asStateFlow()

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar = _snackbar.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.itemsSort.collect { saved ->
                _sortMode.value = SortMode.entries.find { it.name == saved } ?: SortMode.NAME_ASC
            }
        }
        viewModelScope.launch {
            prefs.itemsViewMode.collect { _viewMode.value = it }
        }
        viewModelScope.launch {
            prefs.aiSearchEnabled.collect { enabled ->
                val changed = aiSearchEnabled != enabled
                aiSearchEnabled = enabled
                // Re-run an active search so toggling AI reorders results immediately.
                if (changed && _query.value.isNotBlank()) load()
            }
        }
        // Keep the embedding model registry + active selection in sync with preferences.
        viewModelScope.launch {
            prefs.aiCustomModelsJson.collect { ModelRepository.loadCustomModels(appContext, it) }
        }
        viewModelScope.launch {
            prefs.aiEmbedModelId.collect { id ->
                // Migrate a stored id that no longer exists (e.g. the pre-GGUF "minilm-l6-v2")
                // to the current default, so semantic search isn't silently stuck on a dead model.
                // Custom ids ("custom…") are kept even if their spec hasn't loaded yet.
                val resolved = when {
                    ModelRepository.spec(id) != null -> id
                    id.startsWith("custom") -> id
                    else -> PreferencesRepository.DEFAULT_EMBED_MODEL_ID
                }
                if (resolved != id) prefs.setAiEmbedModelId(resolved)
                EmbeddingService.setModel(resolved)
                if (aiSearchEnabled && _query.value.isNotBlank()) load()
            }
        }
        // Reload with fresh server data after a reconnect sync completes.
        viewModelScope.launch {
            ConnectionMonitor.refreshTicks.collect { load() }
        }
        // Apply the selected embedding model's backend override (NPU/CPU) to the engine.
        viewModelScope.launch {
            combine(prefs.aiEmbedModelId, prefs.aiModelBackends) { id, backends ->
                com.homeboy.app.ai.AiBackend.fromToken(backends[id])
            }.collect {
                EmbeddingService.setPreferredBackend(it)
                if (aiSearchEnabled && _query.value.isNotBlank()) load()
            }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val query = _query.value.takeIf { it.isNotBlank() }
                // When semantic search is on we fetch the full candidate set (no server-side
                // keyword filter) and rank locally by meaning; otherwise let the server filter.
                // Gate on the cheap file-existence check — the engine is built off the main
                // thread inside rank().
                val useSemantic = aiSearchEnabled && query != null &&
                    ModelRepository.isReady(appContext, EmbeddingService.selectedModelId)

                val resp = repo.listItems(
                    query = if (useSemantic) null else query,
                    locationIds = listOfNotNull(_filterLocationId.value),
                    labelIds = listOfNotNull(_filterTagId.value),
                    includeArchived = _showArchived.value,
                    pageSize = 1000
                )

                _items.value = if (useSemantic) {
                    val base = withContext(Dispatchers.Default) { applyArchivedFilter(resp.items) }
                    // If the engine can't build/run, fall back to a local keyword filter so we
                    // don't show the entire (unfiltered) catalog.
                    EmbeddingService.rank(appContext, query!!, base)
                        ?: withContext(Dispatchers.Default) { applySortFilter(keywordFilter(base, query)) }
                } else {
                    withContext(Dispatchers.Default) { applySortFilter(resp.items) }
                }
                if (_locations.value.isEmpty()) _locations.value = repo.listLocations()
                if (_tags.value.isEmpty()) _tags.value = repo.listTags()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
        load()
    }

    fun setFilterLocation(id: String?) {
        _filterLocationId.value = id
        load()
    }

    fun setFilterTag(id: String?) {
        _filterTagId.value = id
        load()
    }

    fun setShowArchived(show: Boolean) {
        _showArchived.value = show
        load()
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        _items.value = applySortFilter(_items.value)
        viewModelScope.launch { prefs.setItemsSort(mode.name) }
    }

    fun toggleViewMode() {
        val next = if (_viewMode.value == "list") "grid" else "list"
        _viewMode.value = next
        viewModelScope.launch { prefs.setItemsViewMode(next) }
    }

    fun deleteItem(id: String) {
        viewModelScope.launch {
            try {
                repo.deleteItem(id)
                _items.value = _items.value.filter { it.id != id }
                _snackbar.value = "Item deleted"
            } catch (e: Exception) {
                _snackbar.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun bulkDelete(ids: Set<String>) {
        viewModelScope.launch {
            ids.forEach { id -> try { repo.deleteItem(id) } catch (_: Exception) {} }
            _items.value = _items.value.filter { it.id !in ids }
            _snackbar.value = "Deleted ${ids.size} item${if (ids.size > 1) "s" else ""}"
        }
    }

    fun bulkArchive(ids: Set<String>) {
        viewModelScope.launch {
            ids.forEach { id ->
                val item = _items.value.find { it.id == id } ?: return@forEach
                try {
                    repo.updateItem(id, HBItemUpdate(
                        name = item.name,
                        quantity = item.quantity,
                        description = item.description ?: "",
                        archived = true,
                        parentId = item.effectiveLocation?.id
                    ))
                } catch (_: Exception) {}
            }
            load()
            _snackbar.value = "Archived ${ids.size} item${if (ids.size > 1) "s" else ""}"
        }
    }

    fun clearSnackbar() { _snackbar.value = null }
    fun clearError() { _error.value = null }
    fun clearFilters() {
        _filterLocationId.value = null
        _filterTagId.value = null
        load()
    }

    /** Archived visibility only — used before semantic ranking replaces the sort order. */
    private fun applyArchivedFilter(list: List<HBItem>): List<HBItem> =
        if (_showArchived.value) list else list.filter { !it.archived }

    /** Local name/description substring filter — fallback when the embedding engine is unavailable. */
    private fun keywordFilter(list: List<HBItem>, query: String): List<HBItem> =
        list.filter { item ->
            item.name.contains(query, ignoreCase = true) ||
                (item.description?.contains(query, ignoreCase = true) == true)
        }

    private fun applySortFilter(list: List<HBItem>): List<HBItem> {
        val filtered = if (_showArchived.value) list else list.filter { !it.archived }
        return when (_sortMode.value) {
            SortMode.NAME_ASC  -> filtered.sortedBy { it.name.lowercase() }
            SortMode.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
            SortMode.NEWEST    -> filtered.sortedByDescending { it.createdAt ?: "" }
            SortMode.OLDEST    -> filtered.sortedBy { it.createdAt ?: "" }
            SortMode.QTY_HIGH  -> filtered.sortedByDescending { it.quantity }
            SortMode.QTY_LOW   -> filtered.sortedBy { it.quantity }
        }
    }

    companion object {
        fun factory(app: HomeboxApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(cls: Class<T>) =
                ItemsViewModel(app, app.repository, app.prefs) as T
        }
    }
}
