package com.homeboy.app.ui.items

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.*
import com.homeboy.app.data.HomeboxRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ItemDetailViewModel(private val repo: HomeboxRepository) : ViewModel() {

    private val _item = MutableStateFlow<HBItemDetail?>(null)
    val item = _item.asStateFlow()

    private val _children = MutableStateFlow<List<HBItem>>(emptyList())
    val children = _children.asStateFlow()

    private val _maintenance = MutableStateFlow<List<HBMaintenanceEntry>>(emptyList())
    val maintenance = _maintenance.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar = _snackbar.asStateFlow()

    private var currentItemId: String? = null

    fun load(itemId: String) {
        currentItemId = itemId
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val itemDeferred = async { repo.getItem(itemId) }
                val childDeferred = async { runCatching { repo.listItems(parentIds = listOf(itemId), pageSize = 500) } }
                val maintDeferred = async { runCatching { repo.listMaintenance(itemId) } }

                _item.value = itemDeferred.await()
                _children.value = childDeferred.await().getOrNull()?.items ?: emptyList()
                _maintenance.value = maintDeferred.await().getOrNull() ?: emptyList()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun reload() { currentItemId?.let { load(it) } }

    fun toggleArchive() {
        val current = _item.value ?: return
        viewModelScope.launch {
            try {
                val update = HBItemUpdate.from(current).copy(archived = !current.archived)
                val updated = repo.updateItem(current.id, update)
                _item.value = updated
                _snackbar.value = if (updated.archived) "Archived" else "Unarchived"
            } catch (e: Exception) {
                _snackbar.value = "Failed: ${e.message}"
            }
        }
    }

    fun deleteItem(onDeleted: () -> Unit) {
        val id = _item.value?.id ?: return
        viewModelScope.launch {
            try {
                repo.deleteItem(id)
                onDeleted()
            } catch (e: Exception) {
                _snackbar.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun deleteMaintenance(id: String) {
        viewModelScope.launch {
            try {
                repo.deleteMaintenance(id)
                _maintenance.value = _maintenance.value.filter { it.id != id }
                _snackbar.value = "Entry deleted"
            } catch (e: Exception) {
                _snackbar.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun clearSnackbar() { _snackbar.value = null }
    fun clearError() { _error.value = null }

    companion object {
        fun factory(app: HomeboxApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(cls: Class<T>) =
                ItemDetailViewModel(app.repository) as T
        }
    }
}
