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

    /** How a model can run on this device, derived from its ONNX files. */
    data class Compatibility(
        /** Highest tier the model can engage, or null when it has no usable ONNX export. */
        val best: AiBackend?,
        val quantizedOnnx: List<String>,
        val floatOnnx: List<String>,
        val hasVocab: Boolean,
        val hasTokenizerJson: Boolean,
        val hasGenaiConfig: Boolean
    ) {
        val hasOnnx: Boolean get() = quantizedOnnx.isNotEmpty() || floatOnnx.isNotEmpty()
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

    // ---- Search ------------------------------------------------------------

    /**
     * Search ONNX models for [purpose]. Results carry their file list + computed [Compatibility]
     * so the UI can badge each one and filter by hardware tier without extra round-trips.
     */
    suspend fun search(
        query: String,
        purpose: ModelRepository.Purpose,
        token: String
    ): List<HfModel> = withContext(Dispatchers.IO) {
        val pipeline = when (purpose) {
            ModelRepository.Purpose.EMBEDDING -> "feature-extraction"
            ModelRepository.Purpose.GENERATION -> "text-generation"
        }
        val q = query.trim()
        // `filter=onnx` (the ONNX tag) is applied broadly across ONNX repos; combined with the
        // pipeline tag it scopes results to runnable models for this purpose. We still verify the
        // actual file list per-result below, so a missed tag just means fewer noise hits.
        val url = buildString {
            append("$API/models?filter=onnx&pipeline_tag=$pipeline")
            append("&sort=downloads&limit=30&full=true")
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
            // Only surface models we could actually run (have some ONNX export).
            if (!compat.hasOnnx) return@mapNotNull null
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

    // ---- Compatibility classification --------------------------------------

    private val QUANT_HINTS = listOf("quant", "int8", "uint8", "qdq", "_q4", "q4f", "_i8", "_u8")

    private fun classify(files: List<String>): Compatibility {
        val onnx = files.filter { it.endsWith(".onnx", ignoreCase = true) }
        val quant = onnx.filter { f -> QUANT_HINTS.any { f.lowercase().contains(it) } }
        val float = onnx - quant.toSet()
        val best = when {
            quant.isNotEmpty() -> AiBackend.NPU
            float.isNotEmpty() -> AiBackend.GPU
            else -> null
        }
        fun has(name: String) = files.any { it.substringAfterLast('/').equals(name, ignoreCase = true) }
        return Compatibility(
            best = best,
            quantizedOnnx = quant,
            floatOnnx = float,
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
