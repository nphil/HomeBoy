package com.homeboy.app.ai

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Thin HuggingFace Hub API client for the in-app model browser. Searches for GGUF models (one
 * format serves both embeddings and generation under llama.cpp), inspects their file lists, and
 * classifies how each would run on this device — including a human-readable [Compatibility.warning]
 * for models that will run but aren't ideal (no Q4_0 → no NPU, unusual architecture, very large).
 *
 * All calls are best-effort: any failure surfaces as an empty list / null so the UI shows a
 * friendly empty state rather than crashing.
 */
object HuggingFaceRepository {

    private const val API = "https://huggingface.co/api"
    private const val RESOLVE = "https://huggingface.co"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // ---- Wire types --------------------------------------------------------

    private data class ApiModel(
        @SerializedName("id") val id: String? = null,
        @SerializedName("downloads") val downloads: Long = 0,
        @SerializedName("likes") val likes: Long = 0,
        @SerializedName("pipeline_tag") val pipelineTag: String? = null,
        @SerializedName("tags") val tags: List<String>? = null,
        @SerializedName("siblings") val siblings: List<ApiSibling>? = null
    )

    private data class ApiSibling(@SerializedName("rfilename") val rfilename: String? = null)

    private data class ApiTreeEntry(
        @SerializedName("path") val path: String? = null,
        @SerializedName("size") val size: Long = 0,
        @SerializedName("type") val type: String? = null
    )

    // ---- Public model ------------------------------------------------------

    /**
     * How a GGUF model runs on this device, derived from its files + name.
     *
     * The Hexagon NPU only accelerates Q4_0/Q8_0/MXFP4 (and standard transformer architectures);
     * everything else still runs, just on GPU/CPU. [warning] is non-null when the model is runnable
     * but sub-optimal, and is shown next to the model name so the user knows before downloading.
     */
    data class Compatibility(
        /** Highest tier the model can engage, or null when it has no GGUF. */
        val best: AiBackend?,
        /** GGUF files in an NPU-friendly quant (Q4_0/Q8_0/MXFP4). */
        val ggufNpu: List<String>,
        /** Other GGUF files (Q4_K_M, f16, …) — GPU/CPU only. */
        val ggufOther: List<String>,
        val recommended: Boolean,
        val warning: String?
    ) {
        val hasGguf: Boolean get() = ggufNpu.isNotEmpty() || ggufOther.isNotEmpty()
        val isRunnable: Boolean get() = hasGguf
    }

    data class HfModel(
        val id: String,
        val author: String,
        val name: String,
        val downloads: Long,
        val likes: Long,
        val pipelineTag: String?,
        val files: List<String>,
        val compat: Compatibility
    )

    data class HfFile(val path: String, val size: Long)

    /** A file we will actually download for a model, with its resolved size. */
    data class PlannedFile(val remotePath: String, val localName: String, val size: Long)

    /** Sort orders accepted by the HuggingFace models API. */
    enum class Sort(val apiValue: String, val label: String) {
        DOWNLOADS("downloads", "Downloads"),
        TRENDING("trendingScore", "Trending"),
        RECENT("lastModified", "Recent"),
        LIKES("likes", "Likes")
    }

    // ---- Search ------------------------------------------------------------

    /**
     * Search GGUF models for [purpose] (embedding = feature-extraction, generation =
     * text-generation). Only models with a runnable GGUF are returned; sub-optimal ones come back
     * with a [Compatibility.warning]. Keeps the caller's [sort]; results carry their file list +
     * computed [Compatibility] so the UI can badge + warn each one.
     */
    suspend fun search(
        query: String,
        purpose: ModelRepository.Purpose,
        token: String,
        sort: Sort = Sort.DOWNLOADS
    ): List<HfModel> = withContext(Dispatchers.IO) {
        val q = query.trim()
        val pipeline = when (purpose) {
            ModelRepository.Purpose.EMBEDDING -> "feature-extraction"
            ModelRepository.Purpose.GENERATION -> "text-generation"
        }
        val url = buildString {
            append("$API/models?filter=gguf&pipeline_tag=$pipeline")
            append("&sort=${sort.apiValue}&direction=-1&limit=100&full=true")
            if (q.isNotEmpty()) append("&search=${enc(q)}")
        }
        val json = get(url, token) ?: return@withContext emptyList()
        val type = object : TypeToken<List<ApiModel>>() {}.type
        val parsed: List<ApiModel> = runCatching { gson.fromJson<List<ApiModel>>(json, type) }
            .getOrNull() ?: return@withContext emptyList()

        parsed.mapNotNull { m ->
            val id = m.id ?: return@mapNotNull null
            val files = m.siblings?.mapNotNull { it.rfilename } ?: emptyList()
            val compat = classify(id, files)
            // Only surface models we can actually run; non-ideal ones carry a warning instead.
            if (!compat.isRunnable) return@mapNotNull null
            HfModel(
                id = id,
                author = id.substringBefore('/', ""),
                name = id.substringAfter('/'),
                downloads = m.downloads,
                likes = m.likes,
                pipelineTag = m.pipelineTag,
                files = files,
                compat = compat
            )
        }
    }

    /** Fetch the full file tree (with sizes) for a model — used to total a download's size. */
    suspend fun files(id: String, token: String): List<HfFile> = withContext(Dispatchers.IO) {
        val url = "$API/models/$id/tree/main?recursive=true"
        val json = get(url, token) ?: return@withContext emptyList()
        val type = object : TypeToken<List<ApiTreeEntry>>() {}.type
        val entries: List<ApiTreeEntry> = runCatching { gson.fromJson<List<ApiTreeEntry>>(json, type) }
            .getOrNull() ?: return@withContext emptyList()
        entries.filter { it.type == "file" && it.path != null }
            .map { HfFile(it.path!!, it.size) }
    }

    /** A direct download URL for a file within a repo. */
    fun resolveUrl(id: String, path: String): String = "$RESOLVE/$id/resolve/main/$path"

    /**
     * Fetch the model's README (its HuggingFace "model card") as plain markdown, with the YAML
     * frontmatter stripped, for display on the detail sheet. Returns null if there's no card.
     */
    suspend fun modelCard(id: String, token: String): String? = withContext(Dispatchers.IO) {
        val md = get("$RESOLVE/$id/raw/main/README.md", token) ?: return@withContext null
        var body = md
        if (body.trimStart().startsWith("---")) {
            // Drop the leading "--- … ---" metadata block.
            val after = body.trimStart().removePrefix("---")
            val end = after.indexOf("\n---")
            body = if (end >= 0) after.substring(end + 4) else after
        }
        body.trim().take(8000).ifBlank { null }
    }

    /**
     * Decide which single GGUF to download for [model]. NOT the whole repo — just the one file we
     * run, so the displayed size matches the download. Prefers an NPU-friendly Q4_0/Q8_0/MXFP4
     * quant; otherwise the smallest GGUF.
     */
    fun planDownload(
        model: HfModel,
        @Suppress("UNUSED_PARAMETER") purpose: ModelRepository.Purpose,
        tree: List<HfFile>
    ): List<PlannedFile> {
        fun sizeOf(path: String): Long = tree.firstOrNull { it.path == path }?.size ?: 0L
        fun smallest(paths: List<String>): String? {
            if (paths.isEmpty()) return null
            val sized = paths.map { it to sizeOf(it) }
            return sized.filter { it.second > 0 }.minByOrNull { it.second }?.first
                ?: sized.first().first
        }
        val candidates = model.compat.ggufNpu.ifEmpty { model.compat.ggufOther }
        val gguf = smallest(candidates) ?: return emptyList()
        return listOf(PlannedFile(gguf, "model.gguf", sizeOf(gguf)))
    }

    // ---- Compatibility classification --------------------------------------

    /** Quant tokens the Hexagon NPU backend repacks/accelerates. */
    private val NPU_QUANTS = listOf("q4_0", "q8_0", "mxfp4")

    /** Architecture hints llama.cpp's Hexagon backend has no kernels for → CPU fallback only. */
    private val EXOTIC_ARCH = listOf("mamba", "rwkv", "deltanet", "qwen3.5", "qwen-3.5", "jamba", "ssm")

    /** Param-count hints that signal a model is too big to be snappy for on-device tagging. */
    private val LARGE_HINTS = listOf("70b", "72b", "65b", "34b", "32b", "30b", "27b", "20b", "13b", "14b")

    private fun classify(modelId: String, files: List<String>): Compatibility {
        val lowerId = modelId.lowercase()
        val gguf = files.filter { it.endsWith(".gguf", ignoreCase = true) }
        val npu = gguf.filter { f -> NPU_QUANTS.any { f.lowercase().contains(it) } }
        val other = gguf - npu.toSet()

        val exotic = EXOTIC_ARCH.any { lowerId.contains(it) } || gguf.any { f ->
            EXOTIC_ARCH.any { f.lowercase().contains(it) }
        }
        val large = LARGE_HINTS.any { lowerId.contains(it) }

        val best = when {
            gguf.isEmpty() -> null
            npu.isNotEmpty() && !exotic -> AiBackend.NPU
            else -> AiBackend.GPU // float / K-quant runs on GPU (→ CPU) but not the NPU
        }

        val warning: String? = when {
            gguf.isEmpty() -> null
            exotic -> "Unusual architecture — the NPU has no kernels for it; will fall back to CPU and may be slow"
            large -> "Large model — high memory use and slow generation on-device; a 0.5–1.5B model is recommended for tags"
            npu.isEmpty() -> "No Q4_0 build — runs on GPU/CPU only, not the NPU. Pick a Q4_0 quant for NPU acceleration"
            else -> null
        }
        val recommended = warning == null && best == AiBackend.NPU

        return Compatibility(
            best = best,
            ggufNpu = npu,
            ggufOther = other,
            recommended = recommended,
            warning = warning
        )
    }

    // ---- HTTP --------------------------------------------------------------

    private fun get(url: String, token: String): String? = runCatching {
        val builder = Request.Builder().url(url).get()
        if (token.isNotBlank()) builder.header("Authorization", "Bearer ${token.trim()}")
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }.getOrNull()

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}
