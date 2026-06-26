package com.homeboy.app.ui.items

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.api.*
import com.homeboy.app.data.HomeboxRepository
import com.homeboy.app.data.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AddEditItemViewModel(
    private val repo: HomeboxRepository,
    private val prefs: PreferencesRepository
) : ViewModel() {

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

    // Sticky-field state
    private val _keepLocation = MutableStateFlow(false)
    val keepLocation = _keepLocation.asStateFlow()
    private val _keepTags = MutableStateFlow(false)
    val keepTags = _keepTags.asStateFlow()
    private val _stickyLocationId = MutableStateFlow<String?>(null)
    val stickyLocationId = _stickyLocationId.asStateFlow()
    private val _stickyTagIds = MutableStateFlow<List<String>>(emptyList())
    val stickyTagIds = _stickyTagIds.asStateFlow()

    fun setKeepLocation(v: Boolean) { _keepLocation.value = v; viewModelScope.launch { prefs.setKeepLocation(v) } }
    fun setKeepTags(v: Boolean) { _keepTags.value = v; viewModelScope.launch { prefs.setKeepTags(v) } }

    init {
        viewModelScope.launch {
            _keepLocation.value = prefs.getKeepLocation()
            _keepTags.value = prefs.getKeepTags()
            if (_keepLocation.value) _stickyLocationId.value = prefs.getLastLocation()
            if (_keepTags.value) _stickyTagIds.value = prefs.getLastTags()
        }
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
        existingId: String?,
        photos: List<ByteArray> = emptyList(),
        fields: List<HBEntityField> = emptyList()
    ) {
        _saving.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                // Entities API merges location + sub-item parent into one `parentId`.
                val effectiveParent = parentId ?: locationId
                // Drop blank-named custom fields so we never persist empty rows.
                val cleanFields = fields.filter { it.name.isNotBlank() }
                val itemId: String = if (existingId != null) {
                    val current = _existingItem.value
                    val base = if (current != null) {
                        HBItemUpdate.from(current).copy(
                            name = name,
                            description = description,
                            quantity = quantity,
                            tagIds = tagIds,
                            parentId = effectiveParent
                        )
                    } else {
                        HBItemUpdate(
                            name = name, description = description, quantity = quantity,
                            tagIds = tagIds, parentId = effectiveParent
                        )
                    }
                    repo.updateItem(existingId, base.copy(fields = cleanFields)).id
                } else {
                    // EntityCreate has no fields array — create first, then patch fields in.
                    val created = repo.createItem(HBItemCreate(
                        name = name, description = description, quantity = quantity,
                        tagIds = tagIds, parentId = effectiveParent
                    ))
                    if (cleanFields.isNotEmpty()) {
                        repo.updateItem(created.id, HBItemUpdate.from(created).copy(
                            name = name, description = description, quantity = quantity,
                            tagIds = tagIds, parentId = effectiveParent, fields = cleanFields
                        ))
                    }
                    created.id
                }

                // Upload any picked photos (first becomes primary).
                photos.forEachIndexed { i, bytes ->
                    runCatching {
                        repo.uploadAttachment(itemId, bytes, "photo_${System.currentTimeMillis()}_$i.jpg", primary = i == 0)
                    }
                }

                // Persist sticky fields for the next quick-add.
                if (_keepLocation.value) prefs.setLastLocation(locationId)
                if (_keepTags.value) prefs.setLastTags(tagIds)

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
                AddEditItemViewModel(app.repository, app.prefs) as T
        }
    }
}
