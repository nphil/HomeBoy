package com.homeboy.app.ai

import com.homeboy.app.ai.ModelRepository.ModelSpec
import com.homeboy.llmkit.LlmKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A single scored candidate (for the embedder ranking output). */
data class ScoredText(val text: String, val score: Float)

/**
 * Runs head-to-head model benchmarks. Each model is loaded fresh on a SPECIFIC backend
 * (no silent fallback in the request, so NPU vs GPU vs CPU are measured honestly), timed,
 * then freed. Mirrors the iOS `BenchmarkRunner`.
 *
 * All work is native + blocking, so every method hops onto [Dispatchers.Default]. The native
 * layer doesn't expose token counts, so LLM throughput is estimated from output length
 * (`genTokensEstimated = true`) — good enough for a same-device head-to-head comparison.
 */
class BenchmarkRunner(private val nativeLibDir: String) {

    data class EmbedRow(
        val id: String,          // "modelId|backend" — unique per model+backend
        val modelName: String,
        val backend: String,
        val dim: Int,
        val loadMs: Double,
        val msPerItem: Double,
        val embedsPerSec: Double,
        val count: Int,
        val top: List<ScoredText>,
        val failed: Boolean,
        val error: String?
    )

    data class LLMRow(
        val id: String,
        val modelName: String,
        val backend: String,
        val loadMs: Double,
        val genTokens: Int,
        val tokensPerSec: Double,
        /** True when [genTokens]/[tokensPerSec] are estimated from output length, not exact. */
        val genTokensEstimated: Boolean,
        val output: String,
        val failed: Boolean,
        val error: String?
    )

    suspend fun benchmarkEmbedder(
        spec: ModelSpec,
        path: String,
        backend: AiBackend,
        query: String,
        candidates: List<String>,
        topN: Int = 12
    ): EmbedRow = withContext(Dispatchers.Default) {
        val rowId = "${spec.id}|${backend.name}"
        val (handle, loadMs, err) = load(path, embeddings = true, backend = backend, probe = true)
        if (handle == null) {
            return@withContext EmbedRow(
                rowId, spec.displayName, backend.shortLabel, dim = 0, loadMs = loadMs,
                msPerItem = 0.0, embedsPerSec = 0.0, count = 0, top = emptyList(),
                failed = true, error = err
            )
        }
        try {
            val pq = prefix(spec.id, isQuery = true)
            val pd = prefix(spec.id, isQuery = false)
            val qv = LlmKit.embed(handle, pq + query)
            val dim = qv.size

            val t0 = System.nanoTime()
            val scored = ArrayList<ScoredText>(candidates.size)
            for (c in candidates) {
                if (c.isBlank()) continue
                val v = LlmKit.embed(handle, pd + c)
                scored.add(ScoredText(c, dot(qv, v)))
            }
            val elapsedSec = (System.nanoTime() - t0) / 1e9
            val count = scored.size
            scored.sortByDescending { it.score }

            EmbedRow(
                id = rowId, modelName = spec.displayName, backend = backend.shortLabel, dim = dim,
                loadMs = loadMs,
                msPerItem = if (count > 0) elapsedSec * 1000 / count else 0.0,
                embedsPerSec = if (elapsedSec > 0) count / elapsedSec else 0.0,
                count = count,
                top = scored.take(topN),
                failed = false, error = null
            )
        } finally {
            LlmKit.free(handle)
        }
    }

    suspend fun benchmarkLLM(
        spec: ModelSpec,
        path: String,
        backend: AiBackend,
        system: String,
        user: String,
        maxTokens: Int = 96
    ): LLMRow = withContext(Dispatchers.Default) {
        val rowId = "${spec.id}|${backend.name}"
        val (handle, loadMs, err) = load(path, embeddings = false, backend = backend, probe = false)
        if (handle == null) {
            return@withContext LLMRow(
                rowId, spec.displayName, backend.shortLabel, loadMs = loadMs,
                genTokens = 0, tokensPerSec = 0.0, genTokensEstimated = false,
                output = "", failed = true, error = err
            )
        }
        try {
            val t0 = System.nanoTime()
            val raw = runCatching {
                LlmKit.generateChat(handle, system, user, maxTokens = maxTokens, temperature = 0.4f, topK = 20)
            }.getOrNull().orEmpty()
            val genSec = (System.nanoTime() - t0) / 1e9
            val text = stripThink(raw)
            // The native bridge returns only the completion text, not a token count, so estimate
            // it from output length (~4 chars/token for these BPE vocabularies).
            val genTokens = estimateTokens(raw)
            val failed = raw.isBlank()
            LLMRow(
                id = rowId, modelName = spec.displayName, backend = backend.shortLabel,
                loadMs = loadMs,
                genTokens = genTokens,
                tokensPerSec = if (genSec > 0) genTokens / genSec else 0.0,
                genTokensEstimated = true,
                output = text,
                failed = failed,
                error = if (failed) "Loaded but generated no output (decode failed)." else null
            )
        } finally {
            LlmKit.free(handle)
        }
    }

    // MARK: - Helpers

    /**
     * Strict single-backend load with wall-clock timing. Returns a null handle + a diagnostic
     * message on failure. For embedders a [probe] embed guards against a backend that loads but
     * produces NaN/empty vectors (the OpenCL/GPU path does this for BERT-style graphs).
     */
    private fun load(
        path: String,
        embeddings: Boolean,
        backend: AiBackend,
        probe: Boolean
    ): Triple<Long?, Double, String?> {
        val t0 = System.nanoTime()
        val loaded = runCatching {
            LlmKit.setLibraryDir(nativeLibDir)
            LlmKit.loadModel(path, embeddings = embeddings, requested = backend.toKitBackend())
        }.getOrNull()
        val loadMs = (System.nanoTime() - t0) / 1e6
        val handle = loaded?.handle
        if (handle == null || handle == 0L) {
            return Triple(null, loadMs, loadFailureMessage(backend))
        }
        if (probe) {
            val p = runCatching { LlmKit.embed(handle, "ok") }.getOrNull()
            if (p == null || p.isEmpty() || p[0].isNaN()) {
                LlmKit.free(handle)
                return Triple(
                    null, loadMs,
                    "Produced invalid (NaN/empty) embeddings on ${backend.shortLabel} — try CPU or NPU."
                )
            }
        }
        return Triple(handle, loadMs, null)
    }

    private fun loadFailureMessage(backend: AiBackend): String = when (backend) {
        AiBackend.NPU -> "Couldn't load on the NPU — the model may be too large for the Hexagon cDSP, " +
            "or its architecture isn't NPU-supported. Try GPU or CPU."
        AiBackend.GPU -> "Couldn't load on the GPU (Adreno/OpenCL) — try CPU."
        AiBackend.CPU -> "Failed to load — likely an unsupported architecture, the wrong GGUF file " +
            "(e.g. an mmproj projector or one shard of a split model), or out of memory."
    }

    /** Rough token count from output length; matches the ~4 chars/token BPE ratio. */
    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return kotlin.math.ceil(text.length / 4.0).toInt().coerceAtLeast(1)
    }

    private fun stripThink(s: String): String {
        var out = s
        while (true) {
            val start = out.indexOf("<think>")
            if (start < 0) break
            val end = out.indexOf("</think>", start)
            out = if (end < 0) out.substring(0, start)
            else out.substring(0, start) + out.substring(end + "</think>".length)
        }
        return out.trim()
    }

    /** Instruction prefix each embedding family needs, on the query vs document side. */
    private fun prefix(id: String, isQuery: Boolean): String {
        val l = id.lowercase()
        return when {
            l.contains("nomic") -> if (isQuery) "search_query: " else "search_document: "
            l.contains("bge") -> if (isQuery) "Represent this sentence for searching relevant passages: " else ""
            else -> ""
        }
    }

    /** Dot product of two L2-normalized vectors (= cosine similarity). */
    private fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
