package com.homeboy.app.ui.items

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.ai.LlmEngineManager
import com.homeboy.app.ai.ModelRepository
import com.homeboy.app.ai.TagSuggestionService
import com.homeboy.app.api.*
import com.homeboy.app.data.HomeboxRepository
import com.homeboy.app.data.PreferencesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AddEditItemViewModel(
    private val appContext: Context,
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

    // ---- AI tag suggestions ------------------------------------------------
    /** Live LLM lifecycle (Unloaded / Loading / Ready / Generating) for on-screen feedback. */
    val llmState: StateFlow<LlmEngineManager.State> = LlmEngineManager.state

    private val _tagSuggestions = MutableStateFlow<TagSuggestionService.Suggestions?>(null)
    val tagSuggestions = _tagSuggestions.asStateFlow()

    /** True only when AI tag suggestions are enabled AND the chosen model is downloaded. */
    val aiSuggestionsOn: StateFlow<Boolean> =
        combine(prefs.aiTagsEnabled, prefs.aiGenModelId, ModelRepository.states) { enabled, id, states ->
            enabled && id != null && states[id] is ModelRepository.State.Ready
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var genModelId: String? = null
    private var unloadMinutes = PreferencesRepository.DEFAULT_UNLOAD_MINUTES
    private var aiTagsEnabled = false
    private var suggestJob: Job? = null

    /** Debounced request: regenerate suggestions ~0.8s after the user stops typing. */
    fun requestTagSuggestions(name: String, description: String) {
        suggestJob?.cancel()
        val id = genModelId
        if (!aiTagsEnabled || id == null || name.isBlank()) { _tagSuggestions.value = null; return }
        if (!ModelRepository.isReady(appContext, id)) return
        suggestJob = viewModelScope.launch {
            delay(800)
            val file = ModelRepository.fileFor(appContext, id, "model.task") ?: return@launch
            val modelName = ModelRepository.spec(id)?.displayName ?: "Language model"
            _tagSuggestions.value = TagSuggestionService.suggest(
                appContext, id, modelName, file, name, description, _tags.value, unloadMinutes
            )
        }
    }

    /** Create a brand-new tag the model proposed, add it to the list, and report it back. */
    fun createNovelTag(name: String, onCreated: (HBTag) -> Unit) {
        viewModelScope.launch {
            try {
                val tag = repo.createTag(HBTagCreate(name = name))
                _tags.value = _tags.value + tag
                onCreated(tag)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    init {
        viewModelScope.launch { prefs.aiTagsEnabled.collect { aiTagsEnabled = it } }
        viewModelScope.launch { prefs.aiGenModelId.collect { genModelId = it } }
        viewModelScope.launch { prefs.aiUnloadMinutes.collect { unloadMinutes = it } }
        // Register any custom (HF-added) models so isReady/fileFor resolve before Settings is opened.
        viewModelScope.launch { prefs.aiCustomModelsJson.collect { ModelRepository.loadCustomModels(appContext, it) } }
    }

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
                AddEditItemViewModel(app, app.repository, app.prefs) as T
        }
    }
}
