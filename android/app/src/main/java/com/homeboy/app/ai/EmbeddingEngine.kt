package com.homeboy.app.ai

import com.homeboy.llmkit.Backend
import com.homeboy.llmkit.LlmKit
import java.io.File

/**
 * Sentence-embedding engine backed by llama.cpp/GGUF via [LlmKit]. One GGUF model serves
 * embeddings; the native side tokenizes, runs a pooled forward pass, and L2-normalizes, so a
 * cosine similarity here is a plain dot product.
 *
 * Embeddings are pure prompt-processing, where the Hexagon NPU wins by a wide margin, so
 * [create] prefers NPU → GPU (Adreno) → CPU and reports which tier engaged.
 *
 * Thread-safety: [embed] is synchronized; create one instance and reuse it.
 */
class EmbeddingEngine private constructor(
    private val handle: Long,
    /** Which hardware tier the engine actually engaged. */
    val backend: AiBackend
) {

    /** Embed [text] into a unit-length vector. Returns null on any inference failure. */
    @Synchronized
    fun embed(text: String): FloatArray? {
        if (text.isBlank() || handle == 0L) return null
        val v = runCatching { LlmKit.embed(handle, text) }.getOrNull() ?: return null
        // Reject empty or degenerate (NaN) vectors so a misbehaving backend can't poison ranking.
        if (v.isEmpty() || v[0].isNaN()) return null
        return v
    }

    @Synchronized
    fun close() {
        runCatching { LlmKit.free(handle) }
    }

    companion object {
        /** Cosine similarity of two L2-normalized vectors (= dot product). */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot
        }

        /**
         * Build an engine from a single GGUF [modelFile]. Tries NPU → GPU → CPU and returns the
         * first tier that loads, or null if none do. [nativeLibDir] (the app's nativeLibraryDir)
         * lets FastRPC locate the Hexagon HTP skel. Fully fault-tolerant: catches everything,
         * including UnsatisfiedLinkError, so a device without the native libs degrades to keyword
         * search instead of crashing.
         */
        fun create(modelFile: File, nativeLibDir: String, preferred: AiBackend? = null): EmbeddingEngine? {
            return try {
                if (!modelFile.exists() || modelFile.length() == 0L) return null
                LlmKit.setLibraryDir(nativeLibDir)
                val path = modelFile.absolutePath
                // Honor a user override first, then the smart default (NPU → CPU). The OpenCL/GPU
                // backend computes NaN for BERT-style embedding graphs — the probe below rejects it
                // and falls through, so even a stray GPU request degrades safely.
                val order = buildList {
                    preferred?.let { add(it.toKitBackend()) }
                    add(Backend.NPU); add(Backend.CPU)
                }.distinct()
                for (req in order) {
                    val loaded = LlmKit.loadModel(path, embeddings = true, requested = req) ?: continue
                    // Validate the backend actually produces a usable vector before committing.
                    val probe = LlmKit.embed(loaded.handle, "ok")
                    if (probe.isEmpty() || probe[0].isNaN()) { LlmKit.free(loaded.handle); continue }
                    return EmbeddingEngine(loaded.handle, loaded.backend.toAiBackend())
                }
                null
            } catch (_: Throwable) {
                null
            }
        }

        private fun Backend.toAiBackend(): AiBackend = when (this) {
            Backend.NPU -> AiBackend.NPU
            Backend.GPU -> AiBackend.GPU
            else -> AiBackend.CPU
        }
    }
}
