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
 * Thin HuggingFace Hub API client used by the in-app model browser. Searches for ONNX models,
 * inspects their file lists, and classifies how each would run on this device (NPU / GPU / CPU)
 * from the ONNX export's filenames. An optional access token raises rate limits and unlocks
 * gated repos.
 *
 * All calls are best-effort: any failure surfaces as an empty list / null so the UI can show a
 * friendly empty state rather than crash.
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

    /** How a model can run on this device, derived from its files. */
    data class Compatibility(
        /** Highest tier the model can engage, or null when it has no usable export. */
        val best: AiBackend?,
        val quantizedOnnx: List<String>,
        val floatOnnx: List<String>,
        /** MediaPipe LiteRT bundles (.task / .litertlm) for generative models. */
        val mediaPipeFiles: List<String>,
        val hasVocab: Boolean,
        val hasTokenizerJson: Boolean,
        val hasGenaiConfig: Boolean
    ) {
        val hasOnnx: Boolean get() = quantizedOnnx.isNotEmpty() || floatOnnx.isNotEmpty()
        /** MediaPipe runs on GPU or CPU (never the Hexagon NPU). */
        val hasMediaPipe: Boolean get() = mediaPipeFiles.isNotEmpty()
        val isRunnable: Boolean get() = hasOnnx || hasMediaPipe
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
     * Search models for [purpose]. Embedding = ONNX exports; generation = MediaPipe LiteRT
     * (.task/.litertlm) bundles, which live in the `litert-community` org — so generation search
     * is always scoped there (even with a query) to return models we can actually run. Results
     * carry their file list + computed [Compatibility] so the UI can badge each one.
     */
    suspend fun search(
        query: String,
        purpose: ModelRepository.Purpose,
        token: String,
        sort: Sort = Sort.DOWNLOADS
    ): List<HfModel> = withContext(Dispatchers.IO) {
        val q = query.trim()
        val url = buildString {
            when (purpose) {
                ModelRepository.Purpose.EMBEDDING ->
                    append("$API/models?filter=onnx&pipeline_tag=feature-extraction")
                ModelRepository.Purpose.GENERATION ->
                    append("$API/models?pipeline_tag=text-generation&author=litert-community")
            }
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
            val compat = classify(files)
            // Only surface models we can actually run for this purpose.
            val runnable = when (purpose) {
                ModelRepository.Purpose.EMBEDDING -> compat.hasOnnx
                ModelRepository.Purpose.GENERATION -> compat.hasMediaPipe
            }
            if (!runnable) return@mapNotNull null
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
     * Decide exactly which file(s) to download for [model] given [purpose] and the repo [tree]
     * (with sizes). Crucially this is NOT the whole repo — only the one bundle we run, so the
     * displayed size and the actual download match. For models that ship several variants we
     * pick the smallest (most device-friendly) one.
     *
     * - GENERATION → the single smallest MediaPipe `.task`/`.litertlm` bundle.
     * - EMBEDDING  → the smallest ONNX (preferring a quantized export) + the repo's vocab.txt.
     */
    fun planDownload(
        model: HfModel,
        purpose: ModelRepository.Purpose,
        tree: List<HfFile>
    ): List<PlannedFile> {
        fun sizeOf(path: String): Long = tree.firstOrNull { it.path == path }?.size ?: 0L
        fun smallest(paths: List<String>): String? {
            if (paths.isEmpty()) return null
            val sized = paths.map { it to sizeOf(it) }
            return sized.filter { it.second > 0 }.minByOrNull { it.second }?.first
                ?: sized.first().first
        }
        return when (purpose) {
            ModelRepository.Purpose.GENERATION -> {
                val task = smallest(model.compat.mediaPipeFiles) ?: return emptyList()
                listOf(PlannedFile(task, "model.task", sizeOf(task)))
            }
            ModelRepository.Purpose.EMBEDDING -> {
                val onnxCandidates = model.compat.quantizedOnnx.ifEmpty { model.compat.floatOnnx }
                val onnx = smallest(onnxCandidates) ?: return emptyList()
                val vocab = model.files.firstOrNull {
                    it.substringAfterLast('/').equals("vocab.txt", ignoreCase = true)
                }
                buildList {
                    add(PlannedFile(onnx, "model.onnx", sizeOf(onnx)))
                    if (vocab != null) add(PlannedFile(vocab, "vocab.txt", sizeOf(vocab)))
                }
            }
        }
    }

    // ---- Compatibility classification --------------------------------------

    private val QUANT_HINTS = listOf("quant", "int8", "uint8", "qdq", "_q4", "q4f", "_i8", "_u8")

    private fun classify(files: List<String>): Compatibility {
        val onnx = files.filter { it.endsWith(".onnx", ignoreCase = true) }
        val quant = onnx.filter { f -> QUANT_HINTS.any { f.lowercase().contains(it) } }
        val float = onnx - quant.toSet()
        val mediaPipe = files.filter {
            it.endsWith(".task", ignoreCase = true) || it.endsWith(".litertlm", ignoreCase = true)
        }
        val best = when {
            quant.isNotEmpty() -> AiBackend.NPU          // quantized ONNX → Hexagon NPU
            mediaPipe.isNotEmpty() -> AiBackend.GPU       // MediaPipe LiteRT → Adreno GPU
            float.isNotEmpty() -> AiBackend.GPU           // float ONNX → GPU
            else -> null
        }
        fun has(name: String) = files.any { it.substringAfterLast('/').equals(name, ignoreCase = true) }
        return Compatibility(
            best = best,
            quantizedOnnx = quant,
            floatOnnx = float,
            mediaPipeFiles = mediaPipe,
            hasVocab = has("vocab.txt"),
            hasTokenizerJson = has("tokenizer.json"),
            hasGenaiConfig = has("genai_config.json")
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
