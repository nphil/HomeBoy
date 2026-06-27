package com.homeboy.app.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the single on-device generative LLM (MediaPipe LLM Inference). The model is loaded only
 * when first needed, and unloaded after an idle timeout so it isn't holding ~1 GB of RAM while
 * the user isn't adding items. All lifecycle transitions are published via [state] so the UI can
 * tell the user exactly what's happening ("Loading model…", "Generating…", "NPU/GPU/CPU").
 *
 * MediaPipe runs on the Adreno GPU or the CPU — never the Hexagon NPU — so [AiBackend] here is
 * only ever GPU or CPU. We try GPU first and fall back to CPU.
 */
object LlmEngineManager {

    sealed interface State {
        data object Unloaded : State
        data class Loading(val modelId: String) : State
        data class Ready(val modelId: String, val backend: AiBackend) : State
        data object Generating : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Unloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var llm: LlmInference? = null
    private var loadedModelId: String? = null
    private var backend: AiBackend = AiBackend.CPU
    private var unloadJob: Job? = null

    /** True if [modelId] is currently resident in memory. */
    fun isLoaded(modelId: String): Boolean = llm != null && loadedModelId == modelId

    /**
     * Ensure [modelId] (backed by [modelFile], a MediaPipe `.task` bundle) is loaded. Returns
     * true once an engine is ready. Loading can take several seconds the first time.
     */
    suspend fun ensureLoaded(context: Context, modelId: String, modelFile: File): Boolean =
        mutex.withLock {
            cancelUnload()
            if (llm != null && loadedModelId == modelId) return true
            closeLocked()
            _state.value = State.Loading(modelId)
            val built = withContext(Dispatchers.IO) { buildEngine(context, modelFile) }
            if (built == null) {
                _state.value = State.Error("Couldn't load the language model")
                return false
            }
            llm = built.first
            backend = built.second
            loadedModelId = modelId
            _state.value = State.Ready(modelId, backend)
            true
        }

    /** Generate a completion for [prompt]. Returns null if no engine is loaded or it fails. */
    suspend fun generate(prompt: String): String? = mutex.withLock {
        val engine = llm ?: return null
        cancelUnload()
        _state.value = State.Generating
        val out = withContext(Dispatchers.IO) {
            runCatching { engine.generateResponse(prompt) }.getOrNull()
        }
        _state.value = State.Ready(loadedModelId ?: "", backend)
        out
    }

    /** Schedule an idle unload [minutes] from now (0 = keep loaded indefinitely). */
    fun scheduleUnload(minutes: Int) {
        cancelUnload()
        if (minutes <= 0) return
        unloadJob = scope.launch {
            delay(minutes * 60_000L)
            mutex.withLock {
                withContext(Dispatchers.IO) { closeLocked() }
                _state.value = State.Unloaded
            }
        }
    }

    /** Unload immediately and free memory (e.g. the model was deleted or replaced). */
    fun unload() {
        scope.launch {
            mutex.withLock {
                withContext(Dispatchers.IO) { closeLocked() }
                _state.value = State.Unloaded
            }
        }
    }

    /** Try the GPU first (faster), then CPU. Returns the engine + the tier that succeeded. */
    private fun buildEngine(context: Context, modelFile: File): Pair<LlmInference, AiBackend>? {
        if (!modelFile.exists() || modelFile.length() == 0L) return null
        val tiers = listOf(
            LlmInference.Backend.GPU to AiBackend.GPU,
            LlmInference.Backend.CPU to AiBackend.CPU
        )
        for ((mpBackend, tier) in tiers) {
            val engine = runCatching {
                val opts = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(512)
                    .setPreferredBackend(mpBackend)
                    .build()
                LlmInference.createFromOptions(context, opts)
            }.getOrNull()
            if (engine != null) return engine to tier
        }
        return null
    }

    private fun closeLocked() {
        runCatching { llm?.close() }
        llm = null
        loadedModelId = null
    }

    private fun cancelUnload() {
        unloadJob?.cancel()
        unloadJob = null
    }
}
