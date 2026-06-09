package com.homeboy.app.ui.items

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.*
import com.homeboy.app.data.HomeboxRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AddEditItemViewModel(private val repo: HomeboxRepository) : ViewModel() {

    private val _locations = MutableStateFlow<List<HBLocation>>(emptyList())
    val locations = _locations.asStateFlow()

    private val _tags = MutableStateFlow<List<HBTag>>(emptyList())
    val tags = _tags.asStateFlow()

    private val _existingItem = MutableStateFlow<HBItemDetail?>(null)
    val existingItem = _existingItem.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            _loading.value = true
            try {
                _locations.value = repo.listLocations()
                _tags.value = repo.listTags()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun loadExisting(itemId: String) {
        viewModelScope.launch {
            _loading.value = true
            try {
                _existingItem.value = repo.getItem(itemId)
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun save(
        name: String,
        description: String,
        quantity: Int,
        locationId: String?,
        tagIds: List<String>,
        parentId: String?,
        existingId: String?
    ) {
        _saving.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                if (existingId != null) {
                    val current = _existingItem.value
                    val update = if (current != null) {
                        HBItemUpdate.from(current).copy(
                            name = name,
                            description = description,
                            quantity = quantity,
                            locationId = locationId,
                            labelIds = tagIds,
                            parentId = parentId
                        )
                    } else {
                        HBItemUpdate(
                            name = name, description = description, quantity = quantity,
                            locationId = locationId, labelIds = tagIds, parentId = parentId
                        )
                    }
                    repo.updateItem(existingId, update)
                } else {
                    repo.createItem(HBItemCreate(
                        name = name, description = description, quantity = quantity,
                        locationId = locationId, labelIds = tagIds, parentId = parentId
                    ))
                }
                _saved.value = true
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _saving.value = false
            }
        }
    }

    fun clearError() { _error.value = null }

    companion object {
        fun factory(app: HomeboxApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(cls: Class<T>) =
                AddEditItemViewModel(app.repository) as T
        }
    }
}
