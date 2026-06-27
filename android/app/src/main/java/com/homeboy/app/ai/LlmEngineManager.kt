package com.homeboy.app.ai

import android.content.Context
import com.homeboy.llmkit.Backend
import com.homeboy.llmkit.LlmKit
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
 * Owns the single on-device generative LLM (llama.cpp/GGUF via [LlmKit]). The model is loaded
 * only when first needed, and unloaded after an idle timeout so it isn't holding ~1 GB of RAM
 * while the user isn't adding items. All lifecycle transitions are published via [state] so the
 * UI can tell the user exactly what's happening ("Loading model…", "Generating…", tier).
 *
 * Token generation is memory-bandwidth-bound, where the Snapdragon CPU is fastest (verified on a
 * Snapdragon 8 Elite: CPU 69 t/s vs NPU 34, GPU 45 for a 1B model), so generation runs on the
 * **CPU**. The NPU is reserved for embeddings (see [EmbeddingEngine]); the GPU stays available as
 * a fallback tier. [AiBackend] here reports whichever tier actually engaged.
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

    private var handle: Long = 0L
    private var loadedModelId: String? = null
    private var backend: AiBackend = AiBackend.CPU
    private var unloadJob: Job? = null

    /** True if [modelId] is currently resident in memory. */
    fun isLoaded(modelId: String): Boolean = handle != 0L && loadedModelId == modelId

    /**
     * Ensure [modelId] (backed by [modelFile], a GGUF model) is loaded. Returns true once an
     * engine is ready. Loading can take several seconds the first time.
     */
    suspend fun ensureLoaded(
        context: Context,
        modelId: String,
        modelFile: File,
        preferred: AiBackend? = null
    ): Boolean =
        mutex.withLock {
            cancelUnload()
            if (handle != 0L && loadedModelId == modelId) return true
            closeLocked()
            _state.value = State.Loading(modelId)
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val built = withContext(Dispatchers.IO) { buildEngine(nativeLibDir, modelFile, preferred) }
            if (built == null) {
                _state.value = State.Error("Couldn't load the language model")
                return false
            }
            handle = built.first
            backend = built.second
            loadedModelId = modelId
            _state.value = State.Ready(modelId, backend)
            true
        }

    /** Generate a completion for [prompt]. Returns null if no engine is loaded or it fails. */
    suspend fun generate(prompt: String): String? = mutex.withLock {
        if (handle == 0L) return null
        cancelUnload()
        _state.value = State.Generating
        val out = withContext(Dispatchers.IO) {
            runCatching {
                LlmKit.generate(handle, prompt, maxTokens = 64, temperature = 0.4f, topK = 20)
            }.getOrNull()?.takeIf { it.isNotBlank() }
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

    /** Unload immediately and free memory (e.g. the model was deleted or replaced, or by user). */
    fun unload() {
        scope.launch {
            mutex.withLock {
                withContext(Dispatchers.IO) { closeLocked() }
                _state.value = State.Unloaded
            }
        }
    }

    /**
     * Load the GGUF on the CPU (fastest tier for token generation on Snapdragon). Returns the
     * native handle + the tier that engaged, or null if the model can't be loaded at all.
     */
    private fun buildEngine(nativeLibDir: String, modelFile: File, preferred: AiBackend?): Pair<Long, AiBackend>? {
        if (!modelFile.exists() || modelFile.length() == 0L) return null
        return try {
            LlmKit.setLibraryDir(nativeLibDir)
            // Honor the user override first, then CPU (fastest for generation + universal fallback).
            val order = buildList {
                preferred?.let { add(it.toKitBackend()) }
                add(Backend.CPU)
            }.distinct()
            for (req in order) {
                val loaded = LlmKit.loadModel(modelFile.absolutePath, embeddings = false, requested = req)
                    ?: continue
                return loaded.handle to loaded.backend.toAiBackend()
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun closeLocked() {
        if (handle != 0L) runCatching { LlmKit.free(handle) }
        handle = 0L
        loadedModelId = null
    }

    private fun cancelUnload() {
        unloadJob?.cancel()
        unloadJob = null
    }

    private fun Backend.toAiBackend(): AiBackend = when (this) {
        Backend.NPU -> AiBackend.NPU
        Backend.GPU -> AiBackend.GPU
        else -> AiBackend.CPU
    }
}
