package com.homeboy.app.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.HBTag
import com.homeboy.app.api.HBTagCreate
import com.homeboy.app.api.HBTagTreeItem
import com.homeboy.app.api.HBTagUpdate
import com.homeboy.app.api.buildTagTree
import com.homeboy.app.data.HomeboxRepository
import com.homeboy.app.data.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TagsViewModel(
    private val repo: HomeboxRepository,
    private val prefs: PreferencesRepository
) : ViewModel() {

    private val _tree = MutableStateFlow<List<HBTagTreeItem>>(emptyList())
    val tree = _tree.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar = _snackbar.asStateFlow()

    private val _viewMode = MutableStateFlow("list")
    val viewMode = _viewMode.asStateFlow()

    private var flatTags: List<HBTag> = emptyList()

    init {
        viewModelScope.launch { prefs.tagsViewMode.collect { _viewMode.value = it } }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            try {
                flatTags = repo.listTags()
                _tree.value = buildTagTree(flatTags)
            } catch (e: Exception) {
                _snackbar.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun toggleViewMode() {
        val next = if (_viewMode.value == "list") "grid" else "list"
        viewModelScope.launch { prefs.setTagsViewMode(next) }
    }

    fun createTag(name: String, description: String, color: String, icon: String, parentId: String?) {
        viewModelScope.launch {
            try {
                repo.createTag(HBTagCreate(name.trim(), description.trim(), color, icon, parentId))
                _snackbar.value = "Tag created"
                load()
            } catch (e: Exception) {
                _snackbar.value = "Failed: ${e.message}"
            }
        }
    }

    fun updateTag(id: String, name: String, description: String, color: String, icon: String) {
        viewModelScope.launch {
            try {
                // Preserve the tag's existing parent so editing never reparents it to root.
                val parentId = currentParentId(id)
                repo.updateTag(id, HBTagUpdate(name.trim(), description.trim(), color, icon, parentId))
                _snackbar.value = "Tag updated"
                load()
            } catch (e: Exception) {
                _snackbar.value = "Failed: ${e.message}"
            }
        }
    }

    private fun currentParentId(id: String): String? {
        val p = flatTags.firstOrNull { it.id == id }?.parentId ?: return null
        return if (p.isBlank() || p.all { it == '0' || it == '-' }) null else p
    }

    fun deleteTag(id: String) {
        viewModelScope.launch {
            try {
                repo.deleteTag(id)
                _snackbar.value = "Tag deleted"
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
                TagsViewModel(app.repository, app.prefs) as T
        }
    }
}
