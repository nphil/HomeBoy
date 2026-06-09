package com.homeboy.app.ui.items

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBItem
import com.homeboy.app.api.HBItemCreate
import com.homeboy.app.api.HBLocation
import com.homeboy.app.api.HBTag
import com.homeboy.app.data.HomeboxRepository
import com.homeboy.app.data.PreferencesRepository
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
    private val repo: HomeboxRepository,
    private val prefs: PreferencesRepository
) : ViewModel() {

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
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val resp = repo.listItems(
                    query = _query.value.takeIf { it.isNotBlank() },
                    locationIds = listOfNotNull(_filterLocationId.value),
                    labelIds = listOfNotNull(_filterTagId.value),
                    includeArchived = _showArchived.value,
                    pageSize = 1000
                )
                _items.value = withContext(Dispatchers.Default) { applySortFilter(resp.items) }
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

    fun clearSnackbar() { _snackbar.value = null }
    fun clearError() { _error.value = null }
    fun clearFilters() {
        _filterLocationId.value = null
        _filterTagId.value = null
        load()
    }

    private fun applySortFilter(list: List<HBItem>): List<HBItem> {
        return when (_sortMode.value) {
            SortMode.NAME_ASC  -> list.sortedBy { it.name.lowercase() }
            SortMode.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
            SortMode.NEWEST    -> list.sortedByDescending { it.createdAt ?: "" }
            SortMode.OLDEST    -> list.sortedBy { it.createdAt ?: "" }
            SortMode.QTY_HIGH  -> list.sortedByDescending { it.quantity }
            SortMode.QTY_LOW   -> list.sortedBy { it.quantity }
        }
    }

    companion object {
        fun factory(app: HomeboxApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(cls: Class<T>) =
                ItemsViewModel(app.repository, app.prefs) as T
        }
    }
}
