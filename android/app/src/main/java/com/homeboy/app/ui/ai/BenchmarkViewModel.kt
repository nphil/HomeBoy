package com.homeboy.app.ui.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.homeboy.app.HomeboxApplication
import com.homeboy.app.ai.AiBackend
import com.homeboy.app.ai.BenchmarkRunner
import com.homeboy.app.ai.ModelRepository
import com.homeboy.app.data.LocalCacheManager
import com.homeboy.app.data.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the head-to-head benchmarking screen. Compares the downloaded models the user selects,
 * on the backend(s) they pick (NPU / GPU / CPU / All), over their real items (embedder) or a
 * sample item (tag generator). Mirrors the iOS `AIBenchmarkView` state + `run()` flow.
 */
class BenchmarkViewModel(
    private val appContext: Context,
    private val prefs: PreferencesRepository
) : ViewModel() {

    enum class Mode(val label: String) { EMBEDDER("Embedder"), TAGS("Tag generator") }

    /** Backends to test. [ALL] runs each selected model on NPU, GPU and CPU in turn. */
    enum class BenchBackend(val label: String) { NPU("NPU"), GPU("GPU"), CPU("CPU"), ALL("All") }

    data class SavedRun(
        val id: String,
        val date: String,
        val mode: String,
        val input: String,
        val source: String,
        val backendMode: String,
        val lines: List<String>
    )

    private val gson = Gson()
    private val cache = LocalCacheManager(appContext)
    private val runner = BenchmarkRunner(appContext.applicationInfo.nativeLibraryDir)

    // ---- Selection / inputs ------------------------------------------------

    private val _mode = MutableStateFlow(Mode.EMBEDDER)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    /**
     * Downloaded models for the current mode's purpose — reactive so the chip list is always in
     * sync with the mode and with downloads finishing. Derived from [ModelRepository.states]
     * (never a stale `.value`/disk read in composition, which was flipping embedder/LLM lists).
     */
    val availableModels: StateFlow<List<ModelRepository.ModelSpec>> =
        combine(_mode, ModelRepository.states, ModelRepository.customModels) { mode, states, custom ->
            val purpose = if (mode == Mode.EMBEDDER) ModelRepository.Purpose.EMBEDDING
            else ModelRepository.Purpose.GENERATION
            (ModelRepository.CATALOG + custom)
                .filter { it.purpose == purpose && states[it.id] is ModelRepository.State.Ready }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _backend = MutableStateFlow(BenchBackend.NPU)
    val backend: StateFlow<BenchBackend> = _backend.asStateFlow()

    // Embedder input
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _useMyItems = MutableStateFlow(true)
    val useMyItems: StateFlow<Boolean> = _useMyItems.asStateFlow()
    private val _customText = MutableStateFlow("")
    val customText: StateFlow<String> = _customText.asStateFlow()

    // Tag input
    private val _tagName = MutableStateFlow("")
    val tagName: StateFlow<String> = _tagName.asStateFlow()
    private val _tagDesc = MutableStateFlow("")
    val tagDesc: StateFlow<String> = _tagDesc.asStateFlow()

    // ---- Results -----------------------------------------------------------

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()
    private val _embedRows = MutableStateFlow<List<BenchmarkRunner.EmbedRow>>(emptyList())
    val embedRows: StateFlow<List<BenchmarkRunner.EmbedRow>> = _embedRows.asStateFlow()
    private val _llmRows = MutableStateFlow<List<BenchmarkRunner.LLMRow>>(emptyList())
    val llmRows: StateFlow<List<BenchmarkRunner.LLMRow>> = _llmRows.asStateFlow()

    /** Item count available for the "use my items" toggle. */
    val myItemCount: Int get() = cache.getCachedItems().size

    // ---- Saved runs (persisted) -------------------------------------------

    val savedRuns: StateFlow<List<SavedRun>> = prefs.benchmarkRunsJson
        .map { json -> parseRuns(json) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Models are loaded into ModelRepository by the Settings flow before this screen opens;
        // reconcile with disk once more so a fresh process still sees downloaded models, then
        // default to comparing all of them for the current mode.
        viewModelScope.launch {
            ModelRepository.refreshStates(appContext)
            _selected.value = readyIdsFor(_mode.value)
        }
    }

    /** Ready model ids for [mode]'s purpose (read once, off-composition — safe to use here). */
    private fun readyIdsFor(mode: Mode): Set<String> {
        val purpose = if (mode == Mode.EMBEDDER) ModelRepository.Purpose.EMBEDDING
        else ModelRepository.Purpose.GENERATION
        return (ModelRepository.CATALOG + ModelRepository.customModels.value)
            .filter { it.purpose == purpose && ModelRepository.stateOf(it.id) is ModelRepository.State.Ready }
            .map { it.id }.toSet()
    }

    fun setMode(m: Mode) {
        if (m == _mode.value) return
        _mode.value = m
        // New purpose → clear stale results and default to all of the new mode's models.
        _embedRows.value = emptyList()
        _llmRows.value = emptyList()
        _note.value = null
        _selected.value = readyIdsFor(m)
    }

    fun toggleModel(id: String) {
        _selected.value = _selected.value.toMutableSet().apply { if (!add(id)) remove(id) }
    }

    /** Select every available model for the current mode. */
    fun selectAll() { _selected.value = readyIdsFor(_mode.value) }

    /** Deselect all models. */
    fun clearSelection() { _selected.value = emptySet() }

    fun setBackend(b: BenchBackend) { _backend.value = b }
    fun setQuery(q: String) { _query.value = q }
    fun setUseMyItems(v: Boolean) { _useMyItems.value = v }
    fun setCustomText(t: String) { _customText.value = t }
    fun setTagName(n: String) { _tagName.value = n }
    fun setTagDesc(d: String) { _tagDesc.value = d }

    private fun inputValid(): Boolean = if (_mode.value == Mode.EMBEDDER) {
        _query.value.trim().isNotEmpty()
    } else {
        _tagName.value.trim().length >= 3
    }

    // ---- Run ---------------------------------------------------------------

    fun run() {
        val specs = availableModels.value.filter { it.id in _selected.value }
        if (_running.value || specs.isEmpty() || !inputValid()) return

        _running.value = true
        _note.value = null
        _embedRows.value = emptyList()
        _llmRows.value = emptyList()

        val backends = when (_backend.value) {
            BenchBackend.ALL -> listOf(AiBackend.NPU, AiBackend.GPU, AiBackend.CPU)
            BenchBackend.NPU -> listOf(AiBackend.NPU)
            BenchBackend.GPU -> listOf(AiBackend.GPU)
            BenchBackend.CPU -> listOf(AiBackend.CPU)
        }
        val paths = specs.associate { it.id to (ModelRepository.fileFor(appContext, it.id, "model.gguf")?.absolutePath ?: "") }
        val isEmbedder = _mode.value == Mode.EMBEDDER

        viewModelScope.launch {
            if (isEmbedder) {
                val q = _query.value.trim()
                val candidates: List<String> = if (_useMyItems.value) {
                    withContext(Dispatchers.Default) { cache.getCachedItems().take(200).map(::candidateText) }
                } else {
                    _customText.value.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                }
                for (spec in specs) {
                    for (b in backends) {
                        val row = runner.benchmarkEmbedder(spec, paths[spec.id] ?: "", b, q, candidates)
                        _embedRows.value = _embedRows.value + row
                    }
                }
                if (candidates.isEmpty()) _note.value = "No candidates to test."
            } else {
                val name = _tagName.value.trim()
                val desc = _tagDesc.value
                for (spec in specs) {
                    for (b in backends) {
                        val row = runner.benchmarkLLM(spec, paths[spec.id] ?: "", b, TAG_SYSTEM, tagUserPrompt(name, desc))
                        _llmRows.value = _llmRows.value + row
                    }
                }
            }
            _running.value = false
        }
    }

    private fun candidateText(item: com.homeboy.app.api.HBItem): String {
        val d = item.description
        return if (!d.isNullOrEmpty()) "${item.name}. $d" else item.name
    }

    private fun tagUserPrompt(name: String, desc: String): String {
        val sb = StringBuilder("Item: ").append(name)
        val d = desc.trim()
        if (d.isNotEmpty()) sb.append("\nDescription: ").append(d)
        sb.append("\nTags:")
        return sb.toString()
    }

    // ---- Saved runs --------------------------------------------------------

    fun saveCurrentRun(dateStr: String) {
        val lines = ArrayList<String>()
        val input: String
        val source: String
        if (_mode.value == Mode.EMBEDDER) {
            input = "Query: ${_query.value}"
            source = if (_useMyItems.value) "My items (${minOf(myItemCount, 200)})" else "Custom text"
            for (r in _embedRows.value) {
                lines += if (r.failed) {
                    "${r.modelName} [${r.backend}] — ${r.error ?: "failed"}"
                } else {
                    val top = r.top.take(3).joinToString(", ") { "${it.text.take(18)} ${"%.2f".format(it.score)}" }
                    "${r.modelName} [${r.backend}] — ${"%.0f".format(r.embedsPerSec)} emb/s, load ${r.loadMs.toInt()}ms · $top"
                }
            }
        } else {
            input = "Item: ${_tagName.value}" + if (_tagDesc.value.isBlank()) "" else " / ${_tagDesc.value}"
            source = "—"
            for (r in _llmRows.value) {
                lines += if (r.failed) {
                    "${r.modelName} [${r.backend}] — ${r.error ?: "failed"}"
                } else {
                    val approx = if (r.genTokensEstimated) "~" else ""
                    "${r.modelName} [${r.backend}] — $approx${"%.1f".format(r.tokensPerSec)} tok/s, load ${r.loadMs.toInt()}ms · ${r.output.take(40)}"
                }
            }
        }
        val run = SavedRun(
            id = java.util.UUID.randomUUID().toString(),
            date = dateStr, mode = _mode.value.label, input = input, source = source,
            backendMode = _backend.value.label, lines = lines
        )
        persist(listOf(run) + savedRuns.value)
    }

    fun deleteRun(id: String) {
        persist(savedRuns.value.filterNot { it.id == id })
    }

    private fun persist(runs: List<SavedRun>) {
        viewModelScope.launch { prefs.setBenchmarkRunsJson(gson.toJson(runs)) }
    }

    private fun parseRuns(json: String): List<SavedRun> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<SavedRun>>() {}.type
            gson.fromJson<List<SavedRun>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG_SYSTEM =
            "/no_think\nYou label home-inventory items with short tags. Reply with ONLY a comma-separated " +
                "list of 3 to 6 tags, each 1 or 2 words, and nothing else."

        fun factory(app: HomeboxApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(cls: Class<T>) =
                BenchmarkViewModel(app, app.prefs) as T
        }
    }
}
