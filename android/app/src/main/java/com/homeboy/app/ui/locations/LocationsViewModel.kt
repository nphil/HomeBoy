package com.homeboy.app.ui.locations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBLocation
import com.homeboy.app.api.HBLocationCreate
import com.homeboy.app.api.HBLocationTreeItem
import com.homeboy.app.api.HBLocationUpdate
import com.homeboy.app.data.HomeboxRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LocationsViewModel(private val repo: HomeboxRepository) : ViewModel() {

    private val _tree = MutableStateFlow<List<HBLocationTreeItem>>(emptyList())
    val tree = _tree.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar = _snackbar.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _tree.value = repo.getLocationTree()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun createLocation(name: String, description: String, parentId: String?) {
        viewModelScope.launch {
            try {
                repo.createLocation(HBLocationCreate(name.trim(), description.trim(), parentId))
                _snackbar.value = "Location created"
                load()
            } catch (e: Exception) {
                _snackbar.value = "Failed: ${e.message}"
            }
        }
    }

    fun updateLocation(id: String, name: String, description: String) {
        viewModelScope.launch {
            try {
                repo.updateLocation(id, HBLocationUpdate(name.trim(), description.trim()))
                _snackbar.value = "Location updated"
                load()
            } catch (e: Exception) {
                _snackbar.value = "Failed: ${e.message}"
            }
        }
    }

    fun deleteLocation(id: String) {
        viewModelScope.launch {
            try {
                repo.deleteLocation(id)
                _snackbar.value = "Location deleted"
                load()
            } catch (e: Exception) {
                _snackbar.value = "Failed: ${e.message}"
            }
        }
    }

    fun clearSnackbar() { _snackbar.value = null }

    companion object {
        fun factory(app: HomeboxApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(cls: Class<T>) =
                LocationsViewModel(app.repository) as T
        }
    }
}
