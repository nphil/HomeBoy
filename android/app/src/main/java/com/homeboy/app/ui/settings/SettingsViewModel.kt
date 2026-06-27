package com.homeboy.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.ai.AiBackend
import com.homeboy.app.ai.EmbeddingService
import com.homeboy.app.ai.HuggingFaceRepository
import com.homeboy.app.ai.ModelRepository
import com.homeboy.app.api.HBGroup
import com.homeboy.app.api.HBUserInfo
import com.homeboy.app.data.HomeboxRepository
import com.homeboy.app.data.PreferencesRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val appContext: Context,
    private val repo: HomeboxRepository,
    private val prefs: PreferencesRepository
) : ViewModel() {

    val serverUrl = prefs.serverUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val tenantName = prefs.tenantName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val themeIndex = prefs.themeIndex.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // ---- AI / on-device models --------------------------------------------
    val aiSearchEnabled = prefs.aiSearchEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val aiTagsEnabled = prefs.aiTagsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val modelStates: StateFlow<Map<String, ModelRepository.State>> = ModelRepository.states
    /** Which hardware tier semantic search runs on (NPU/GPU/CPU), or null until built. */
    val embedBackend: StateFlow<AiBackend?> = EmbeddingService.backend
    val customModels: StateFlow<List<ModelRepository.ModelSpec>> = ModelRepository.customModels
    val embedModelId = prefs.aiEmbedModelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesRepository.DEFAULT_EMBED_MODEL_ID)
    val genModelId = prefs.aiGenModelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val hfToken = prefs.hfToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val unloadMinutes = prefs.aiUnloadMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesRepository.DEFAULT_UNLOAD_MINUTES)

    init {
        // Restore any custom models, then reconcile download state with disk.
        viewModelScope.launch {
            ModelRepository.loadCustomModels(appContext, prefs.aiCustomModelsJson.first())
            ModelRepository.refreshStates(appContext)
        }
    }

    fun setAiSearchEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setAiSearchEnabled(enabled) }
    }

    fun setAiTagsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setAiTagsEnabled(enabled) }
    }

    fun setHfToken(token: String) {
        viewModelScope.launch { prefs.setHfToken(token) }
    }

    fun setUnloadMinutes(minutes: Int) {
        viewModelScope.launch { prefs.setAiUnloadMinutes(minutes) }
    }

    /** Pick which downloaded generative model is used for tag suggestions (null = none). */
    fun setDefaultGenModel(id: String?) {
        viewModelScope.launch { prefs.setAiGenModelId(id) }
    }

    // ---- HuggingFace in-app search -----------------------------------------
    private val _hfResults = MutableStateFlow<List<HuggingFaceRepository.HfModel>>(emptyList())
    val hfResults: StateFlow<List<HuggingFaceRepository.HfModel>> = _hfResults.asStateFlow()

    private val _hfLoading = MutableStateFlow(false)
    val hfLoading: StateFlow<Boolean> = _hfLoading.asStateFlow()

    /** Hardware-tier filter for HF results; null = show everything. */
    private val _hfFilter = MutableStateFlow<AiBackend?>(null)
    val hfFilter: StateFlow<AiBackend?> = _hfFilter.asStateFlow()

    fun setHfFilter(backend: AiBackend?) { _hfFilter.value = backend }

    fun searchHuggingFace(query: String, purpose: ModelRepository.Purpose) {
        viewModelScope.launch {
            _hfLoading.value = true
            try {
                _hfResults.value =
                    HuggingFaceRepository.search(query, purpose, prefs.getHfToken())
            } catch (_: Exception) {
                _hfResults.value = emptyList()
            } finally {
                _hfLoading.value = false
            }
        }
    }

    fun clearHfResults() { _hfResults.value = emptyList() }

    /** File list (with sizes) for a model — used by the detail sheet to show total size. */
    suspend fun hfFiles(id: String): List<HuggingFaceRepository.HfFile> =
        HuggingFaceRepository.files(id, prefs.getHfToken())

    /**
     * Add an embedding model discovered on HuggingFace and start downloading it. Picks the
     * best ONNX export (quantized → NPU-capable, else float) and the repo's vocab.txt.
     */
    fun addHfEmbeddingModel(model: HuggingFaceRepository.HfModel) {
        val onnxPath = model.compat.quantizedOnnx.firstOrNull()
            ?: model.compat.floatOnnx.firstOrNull()
        if (onnxPath == null) { _snackbar.value = "No ONNX file in this model"; return }
        val vocabPath = model.files.firstOrNull {
            it.substringAfterLast('/').equals("vocab.txt", ignoreCase = true)
        }
        if (vocabPath == null) { _snackbar.value = "This model has no vocab.txt"; return }
        val modelUrl = HuggingFaceRepository.resolveUrl(model.id, onnxPath)
        val vocabUrl = HuggingFaceRepository.resolveUrl(model.id, vocabPath)
        val id = ModelRepository.addCustomModel(appContext, model.name, modelUrl, vocabUrl)
        if (id == null) { _snackbar.value = "Couldn't add model"; return }
        viewModelScope.launch { prefs.setAiCustomModelsJson(ModelRepository.serializeCustomModels()) }
        ModelRepository.download(viewModelScope, appContext, id)
        _snackbar.value = "Downloading ${model.name}"
    }

    /** Pick which downloaded embedding model semantic search uses. */
    fun setDefaultEmbedModel(id: String) {
        viewModelScope.launch { prefs.setAiEmbedModelId(id) }
        EmbeddingService.setModel(id)
    }

    fun addCustomModel(name: String, modelUrl: String, vocabUrl: String) {
        val id = ModelRepository.addCustomModel(appContext, name, modelUrl, vocabUrl)
        if (id == null) {
            _snackbar.value = "Invalid model URL"
            return
        }
        viewModelScope.launch { prefs.setAiCustomModelsJson(ModelRepository.serializeCustomModels()) }
    }

    fun downloadModel(id: String) = ModelRepository.download(viewModelScope, appContext, id)
    fun cancelModelDownload(id: String) = ModelRepository.cancel(id)
    fun deleteModel(id: String) {
        val isCustom = ModelRepository.customModels.value.any { it.id == id }
        if (isCustom) {
            ModelRepository.removeCustomModel(appContext, id)
            viewModelScope.launch { prefs.setAiCustomModelsJson(ModelRepository.serializeCustomModels()) }
        } else {
            ModelRepository.delete(appContext, id)
        }
        // If the active model is gone, the feature it powers can't run — turn it off.
        if (id == embedModelId.value) setAiSearchEnabled(false)
        if (id == genModelId.value) {
            setAiTagsEnabled(false)
            setDefaultGenModel(null)
        }
    }

    private val _userInfo = MutableStateFlow<HBUserInfo?>(null)
    val userInfo = _userInfo.asStateFlow()

    private val _groups = MutableStateFlow<List<HBGroup>>(emptyList())
    val groups = _groups.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar = _snackbar.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _userInfo.value = repo.getMe()
            } catch (_: Exception) {
                // Surface *something* rather than a perpetual "Loading…"
                _userInfo.value = HBUserInfo()
            }
        }
        viewModelScope.launch {
            try {
                _groups.value = repo.listGroups()
            } catch (_: Exception) {}
        }
    }

    fun switchGroup(group: HBGroup) {
        viewModelScope.launch {
            repo.setActiveGroup(group.id, group.name)
            repo.invalidate()
            _snackbar.value = "Switched to ${group.name}"
            try { _userInfo.value = repo.getMe() } catch (_: Exception) {}
        }
    }

    fun setTheme(index: Int) {
        viewModelScope.launch { prefs.setThemeIndex(index) }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            repo.logout()
            onLoggedOut()
        }
    }

    fun clearSnackbar() { _snackbar.value = null }

    companion object {
        fun factory(app: HomeboxApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(cls: Class<T>) =
                SettingsViewModel(app, app.repository, app.prefs) as T
        }
    }
}
