package com.homeboy.app.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBTag
import com.homeboy.app.api.HBTagCreate
import com.homeboy.app.api.HBTagUpdate
import com.homeboy.app.data.HomeboxRepository
import com.homeboy.app.data.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TagsViewModel(
    private val repo: HomeboxRepository,
    private val prefs: PreferencesRepository
) : ViewModel() {

    private val _tags = MutableStateFlow<List<HBTag>>(emptyList())
    val tags = _tags.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar = _snackbar.asStateFlow()

    private val _viewMode = MutableStateFlow("list")
    val viewMode = _viewMode.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.tagsViewMode.collect { _viewMode.value = it }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            try {
                _tags.value = repo.listTags().sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                _snackbar.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun createTag(name: String, color: String) {
        viewModelScope.launch {
            try {
                repo.createTag(HBTagCreate(name = name.trim(), color = color))
                _snackbar.value = "Tag created"
                load()
            } catch (e: Exception) {
                _snackbar.value = "Failed: ${e.message}"
            }
        }
    }

    fun updateTag(id: String, name: String, color: String) {
        viewModelScope.launch {
            try {
                repo.updateTag(id, HBTagUpdate(name = name.trim(), color = color))
                _snackbar.value = "Tag updated"
                load()
            } catch (e: Exception) {
                _snackbar.value = "Failed: ${e.message}"
            }
        }
    }

    fun deleteTag(id: String) {
        viewModelScope.launch {
            try {
                repo.deleteTag(id)
                _tags.value = _tags.value.filter { it.id != id }
                _snackbar.value = "Tag deleted"
            } catch (e: Exception) {
                _snackbar.value = "Failed: ${e.message}"
            }
        }
    }

    fun toggleViewMode() {
        val next = if (_viewMode.value == "list") "grid" else "list"
        viewModelScope.launch { prefs.setTagsViewMode(next) }
    }

    fun clearSnackbar() { _snackbar.value = null }

    companion object {
        fun factory(app: HomeboxApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(cls: Class<T>) =
                TagsViewModel(app.repository, app.prefs) as T
        }
    }
}
