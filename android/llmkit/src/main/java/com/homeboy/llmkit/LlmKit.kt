package com.homeboy.llmkit

/**
 * Thin Kotlin façade over the llama.cpp JNI bridge (`libllmkit.so`).
 *
 * One engine serves both **generation** (tag suggestions) and **embeddings** (semantic search),
 * backed by a single GGUF model. The native side owns tokenization, sampling, the KV-cache and
 * backend (NPU/GPU/CPU) selection; this object is only the marshalling layer plus a couple of
 * Kotlin-friendly wrappers.
 *
 * Lifecycle:
 * ```
 * LlmKit.init()                                  // once per process — registers ggml backends
 * val (handle, tier) = LlmKit.loadModel(path)    // load a GGUF; learn which backend engaged
 * val text  = LlmKit.generate(handle, prompt)    // generation
 * val vec   = LlmKit.embed(handle, text)         // embeddings (L2-normalized)
 * LlmKit.free(handle)                            // release the model + context
 * ```
 *
 * All native methods are blocking; callers run them off the main thread (the host app already
 * does this via its engine managers).
 */
object LlmKit {

    init {
        System.loadLibrary("llmkit")
    }

    /** Result of a model load: the opaque native handle plus the [Backend] that actually engaged. */
    data class Loaded(val handle: Long, val backend: Backend)

    @Volatile private var initialized = false
    @Volatile private var libDirSet = false

    /**
     * Point the native runtime at the app's `nativeLibraryDir` so FastRPC can find the Hexagon
     * HTP skel and ggml backend libs. Call once (e.g. from Application.onCreate) before loading a
     * model. Idempotent.
     */
    @Synchronized
    fun setLibraryDir(dir: String) {
        if (libDirSet) return
        nativeSetLibraryPath(dir)
        libDirSet = true
    }

    /** Register ggml backends. Idempotent and safe to call repeatedly. */
    @Synchronized
    fun init() {
        if (initialized) return
        nativeInit()
        initialized = true
    }

    /**
     * Load a GGUF model and create a context.
     *
     * @param path absolute path to the `.gguf` file.
     * @param embeddings true to create an embeddings context (pooled, generation disabled).
     * @param requested backend hint; [Backend.AUTO] tries NPU → GPU → CPU.
     * @return [Loaded] handle + engaged tier, or null if the model failed to load on every backend.
     */
    fun loadModel(
        path: String,
        embeddings: Boolean = false,
        requested: Backend = Backend.AUTO,
    ): Loaded? {
        init()
        val nGpuLayers = when (requested) {
            Backend.CPU -> 0
            else -> 99 // offload everything; native side clamps if the backend can't take it
        }
        val handle = nativeLoadModel(path, nGpuLayers, requested.ordinalHint, embeddings)
        if (handle == 0L) return null
        val tier = Backend.fromNative(nativeEngagedBackend(handle))
        return Loaded(handle, tier)
    }

    /** Generate text from [prompt]. Returns the decoded completion (prompt not echoed). */
    fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int = 128,
        temperature: Float = 0.7f,
        topK: Int = 40,
    ): String = nativeGenerate(handle, prompt, maxTokens, temperature, topK)

    /**
     * Generate a chat completion using the model's built-in chat template (read from GGUF
     * metadata via llama_chat_apply_template). Automatically wraps [system] and [user] in the
     * correct special tokens (ChatML for Qwen, Llama-3 format for Llama, etc.) and adds the
     * assistant turn prefix so the model completes rather than echoes.
     *
     * Falls back to a naive concatenation if the native library hasn't been rebuilt yet (the
     * new JNI symbol won't exist until the next PC build), so the app stays usable in the
     * meantime — just without proper token formatting.
     */
    fun generateChat(
        handle: Long,
        system: String,
        user: String,
        maxTokens: Int = 128,
        temperature: Float = 0.7f,
        topK: Int = 40,
    ): String = try {
        nativeGenerateChat(handle, system, user, maxTokens, temperature, topK)
    } catch (_: UnsatisfiedLinkError) {
        // Native lib predates this symbol — degrade gracefully until PC rebuild.
        val fallback = if (system.isBlank()) user else "$system\n$user"
        nativeGenerate(handle, fallback, maxTokens, temperature, topK)
    }

    /** Exact generation metrics from [chatBenchmark]. Times are wall-clock on the native side. */
    data class ChatBenchmark(
        val text: String,
        val promptTokens: Int,
        val genTokens: Int,
        val prefillMs: Double,
        val genMs: Double,
        /** True when counts/timings are exact (native benchmark path), false when estimated. */
        val exact: Boolean,
    ) {
        /** Generated tokens per second, based on the generation-loop time only. */
        val tokensPerSec: Double get() = if (genMs > 0) genTokens / (genMs / 1000.0) else 0.0
    }

    /**
     * Chat completion instrumented for benchmarking: returns the text plus exact prompt/generated
     * token counts and separate prefill/generation timings (measured natively). Falls back to a
     * plain [generateChat] with an output-length token estimate if the native library predates the
     * benchmark symbol, so the app keeps working on an older engine build.
     */
    fun chatBenchmark(
        handle: Long,
        system: String,
        user: String,
        maxTokens: Int = 96,
        temperature: Float = 0.4f,
        topK: Int = 20,
    ): ChatBenchmark = try {
        val raw = nativeGenerateChatBenchmark(handle, system, user, maxTokens, temperature, topK)
        // "promptTokens\tgenTokens\tprefillMs\tgenMs\ttext" — text is last, may contain tabs.
        val parts = raw.split('\t', limit = 5)
        if (parts.size == 5) {
            ChatBenchmark(
                text = parts[4],
                promptTokens = parts[0].toIntOrNull() ?: 0,
                genTokens = parts[1].toIntOrNull() ?: 0,
                prefillMs = parts[2].toDoubleOrNull() ?: 0.0,
                genMs = parts[3].toDoubleOrNull() ?: 0.0,
                exact = true,
            )
        } else {
            ChatBenchmark(raw, 0, 0, 0.0, 0.0, exact = false)
        }
    } catch (_: UnsatisfiedLinkError) {
        estimatedBenchmark(handle, system, user, maxTokens, temperature, topK)
    }

    private fun estimatedBenchmark(
        handle: Long, system: String, user: String, maxTokens: Int, temperature: Float, topK: Int,
    ): ChatBenchmark {
        val t0 = System.nanoTime()
        val text = generateChat(handle, system, user, maxTokens, temperature, topK)
        val genMs = (System.nanoTime() - t0) / 1e6
        val estTokens = if (text.isBlank()) 0 else kotlin.math.ceil(text.length / 4.0).toInt()
        return ChatBenchmark(text, promptTokens = 0, genTokens = estTokens, prefillMs = 0.0, genMs = genMs, exact = false)
    }

    /** Embed [text]. Returns a mean-pooled, L2-normalized vector. */
    fun embed(handle: Long, text: String): FloatArray = nativeEmbed(handle, text)

    /** Names of the ggml backend devices the runtime sees (for reporting NPU/GPU availability). */
    fun probeBackends(): Array<String> {
        init()
        return nativeProbeBackends()
    }

    /** Release a model + context. Safe to call with a stale/zero handle. */
    fun free(handle: Long) {
        if (handle != 0L) nativeFree(handle)
    }

    // --- native declarations (implemented in src/main/cpp/llmkit.cpp) ---

    private external fun nativeSetLibraryPath(dir: String)
    private external fun nativeInit()
    private external fun nativeLoadModel(path: String, nGpuLayers: Int, backendHint: Int, embeddings: Boolean): Long
    private external fun nativeEngagedBackend(handle: Long): Int
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, topK: Int): String
    private external fun nativeGenerateChat(handle: Long, system: String, user: String, maxTokens: Int, temperature: Float, topK: Int): String
    private external fun nativeGenerateChatBenchmark(handle: Long, system: String, user: String, maxTokens: Int, temperature: Float, topK: Int): String
    private external fun nativeEmbed(handle: Long, text: String): FloatArray
    private external fun nativeProbeBackends(): Array<String>
    private external fun nativeFree(handle: Long)
}
