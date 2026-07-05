package com.homeboy.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.ai.AiBackend
import com.homeboy.app.ai.EmbeddingService
import com.homeboy.app.ai.HuggingFaceRepository
import com.homeboy.app.ai.LlmEngineManager
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
    /** Live language-model lifecycle for the management screen status row. */
    val llmState: StateFlow<LlmEngineManager.State> = LlmEngineManager.state
    /** Backend that actually engaged the last time a generation model was loaded (survives unload). */
    val llmLastBackend: StateFlow<AiBackend?> = LlmEngineManager.lastBackend
    /** Model id that [llmLastBackend] applies to (survives unload). */
    val llmLastModelId: StateFlow<String?> = LlmEngineManager.lastModelId
    val customModels: StateFlow<List<ModelRepository.ModelSpec>> = ModelRepository.customModels
    val embedModelId = prefs.aiEmbedModelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesRepository.DEFAULT_EMBED_MODEL_ID)
    val genModelId = prefs.aiGenModelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val hfToken = prefs.hfToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val unloadMinutes = prefs.aiUnloadMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesRepository.DEFAULT_UNLOAD_MINUTES)
    /** Per-model backend override (modelId -> "NPU"/"GPU"/"CPU"). */
    val modelBackends: StateFlow<Map<String, String>> = prefs.aiModelBackends
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * Acceleration tiers offered in the override menu. We intentionally do NOT probe the native
     * runtime here — enumerating ggml devices must happen off the main thread with the library path
     * initialised, and is unnecessary for the UI: the engine already falls back gracefully when a
     * requested tier isn't present (the live badge then shows the tier that actually engaged).
     */
    val deviceBackends: Set<AiBackend> = setOf(AiBackend.NPU, AiBackend.GPU, AiBackend.CPU)

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

    /** Override where a model runs (NPU/GPU/CPU), or null to reset to the smart default. */
    fun setModelBackend(id: String, backend: AiBackend?) {
        viewModelScope.launch { prefs.setAiModelBackend(id, backend?.name) }
    }

    /** Manually unload (eject) the embedding engine from memory. */
    fun unloadEmbed() = EmbeddingService.invalidate()

    /** Fetch a model's HuggingFace card (README) for the detail sheet. */
    suspend fun hfModelCard(id: String): String? =
        HuggingFaceRepository.modelCard(id, prefs.getHfToken())

    // ---- HuggingFace in-app search -----------------------------------------
    private val _hfResults = MutableStateFlow<List<HuggingFaceRepository.HfModel>>(emptyList())
    val hfResults: StateFlow<List<HuggingFaceRepository.HfModel>> = _hfResults.asStateFlow()

    private val _hfLoading = MutableStateFlow(false)
    val hfLoading: StateFlow<Boolean> = _hfLoading.asStateFlow()

    /** Sort order for HF results (downloads / trending / recent / likes). */
    private val _hfSort = MutableStateFlow(HuggingFaceRepository.Sort.DOWNLOADS)
    val hfSort: StateFlow<HuggingFaceRepository.Sort> = _hfSort.asStateFlow()

    fun setHfSort(sort: HuggingFaceRepository.Sort) { _hfSort.value = sort }

    fun searchHuggingFace(query: String, purpose: ModelRepository.Purpose) {
        viewModelScope.launch {
            _hfLoading.value = true
            try {
                _hfResults.value =
                    HuggingFaceRepository.search(query, purpose, prefs.getHfToken(), _hfSort.value)
            } catch (_: Exception) {
                _hfResults.value = emptyList()
            } finally {
                _hfLoading.value = false
            }
        }
    }

    /** Manually unload the language model from memory (frees RAM immediately). */
    fun unloadLlm() = LlmEngineManager.unload()

    fun clearHfResults() { _hfResults.value = emptyList() }

    /** File list (with sizes) for a model — used by the detail sheet to show total size. */
    suspend fun hfFiles(id: String): List<HuggingFaceRepository.HfFile> =
        HuggingFaceRepository.files(id, prefs.getHfToken())

    /**
     * Add an embedding model discovered on HuggingFace and start downloading it. [ggufPath] is the
     * exact GGUF the detail sheet chose, so display and download agree.
     */
    fun addHfEmbeddingModel(model: HuggingFaceRepository.HfModel, ggufPath: String) {
        val url = HuggingFaceRepository.resolveUrl(model.id, ggufPath)
        val id = ModelRepository.addCustomModel(appContext, model.name, url)
        if (id == null) { _snackbar.value = "Couldn't add model"; return }
        viewModelScope.launch {
            prefs.setAiCustomModelsJson(ModelRepository.serializeCustomModels())
            ModelRepository.download(viewModelScope, appContext, id, prefs.getHfToken())
        }
        _snackbar.value = "Downloading ${model.name}"
    }

    /** Add a generative GGUF model; [ggufPath] is the file the sheet chose. */
    fun addHfGenModel(model: HuggingFaceRepository.HfModel, ggufPath: String) {
        val url = HuggingFaceRepository.resolveUrl(model.id, ggufPath)
        val id = ModelRepository.addCustomGenModel(appContext, model.name, url)
        if (id == null) { _snackbar.value = "Couldn't add model"; return }
        viewModelScope.launch {
            prefs.setAiCustomModelsJson(ModelRepository.serializeCustomModels())
            ModelRepository.download(viewModelScope, appContext, id, prefs.getHfToken())
        }
        _snackbar.value = "Downloading ${model.name}"
    }

    /** Pick which downloaded embedding model semantic search uses. */
    fun setDefaultEmbedModel(id: String) {
        viewModelScope.launch { prefs.setAiEmbedModelId(id) }
        EmbeddingService.setModel(id)
    }

    fun addCustomModel(name: String, ggufUrl: String) {
        val id = ModelRepository.addCustomModel(appContext, name, ggufUrl)
        if (id == null) {
            _snackbar.value = "Invalid model URL"
            return
        }
        viewModelScope.launch { prefs.setAiCustomModelsJson(ModelRepository.serializeCustomModels()) }
    }

    fun downloadModel(id: String) {
        viewModelScope.launch { ModelRepository.download(viewModelScope, appContext, id, prefs.getHfToken()) }
    }
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
