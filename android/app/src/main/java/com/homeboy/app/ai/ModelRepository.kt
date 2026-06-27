package com.homeboy.app.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads and tracks the on-device AI models. Each model is one or more files fetched
 * to `filesDir/models/<id>/` and reused across launches. Modeled on IconSearchRepository's
 * network+disk pattern, with per-model progress so the AI Models settings screen can show it.
 *
 * Models are downloaded on demand only — nothing ships in the APK, so the AI features stay
 * fully dormant (and the app behaves exactly as before) until the user opts in.
 */
object ModelRepository {

    enum class Purpose { EMBEDDING, GENERATION }

    /** A downloadable file within a model bundle. */
    data class ModelFile(val url: String, val name: String)

    /** A catalog entry the user can download and select. */
    data class ModelSpec(
        val id: String,
        val displayName: String,
        val description: String,
        val purpose: Purpose,
        val approxBytes: Long,
        val files: List<ModelFile>,
        /** The primary ONNX graph file name (within the bundle dir). */
        val onnxFileName: String,
        /** The tokenizer vocab file name, if this model needs WordPiece tokenization. */
        val vocabFileName: String? = null
    )

    sealed interface State {
        data object NotDownloaded : State
        data class Downloading(val progress: Float) : State // 0f..1f (or -1f if size unknown)
        data object Ready : State
        data class Failed(val message: String) : State
    }

    // ---- Catalog -----------------------------------------------------------

    /**
     * Curated registry. URLs point at well-known, stable HuggingFace ONNX exports.
     * Sizes are approximate (used only for UI display / progress fallback).
     */
    val CATALOG: List<ModelSpec> = listOf(
        ModelSpec(
            id = "minilm-l6-v2",
            displayName = "MiniLM-L6 (semantic search)",
            description = "Lightweight sentence embeddings for smarter search. ~23 MB.",
            purpose = Purpose.EMBEDDING,
            approxBytes = 23_000_000,
            files = listOf(
                ModelFile(
                    "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx",
                    "model.onnx"
                ),
                ModelFile(
                    "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/vocab.txt",
                    "vocab.txt"
                )
            ),
            onnxFileName = "model.onnx",
            vocabFileName = "vocab.txt"
        ),
        ModelSpec(
            id = "bge-small-en-v1.5",
            displayName = "BGE-small EN (retrieval)",
            description = "Stronger retrieval-tuned embeddings. ~33 MB.",
            purpose = Purpose.EMBEDDING,
            approxBytes = 33_000_000,
            files = listOf(
                ModelFile(
                    "https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/main/onnx/model_quantized.onnx",
                    "model.onnx"
                ),
                ModelFile(
                    "https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/main/vocab.txt",
                    "vocab.txt"
                )
            ),
            onnxFileName = "model.onnx",
            vocabFileName = "vocab.txt"
        ),
        ModelSpec(
            id = "gte-small",
            displayName = "GTE-small (general)",
            description = "Well-rounded general-purpose embeddings. ~33 MB.",
            purpose = Purpose.EMBEDDING,
            approxBytes = 33_000_000,
            files = listOf(
                ModelFile(
                    "https://huggingface.co/Xenova/gte-small/resolve/main/onnx/model_quantized.onnx",
                    "model.onnx"
                ),
                ModelFile(
                    "https://huggingface.co/Xenova/gte-small/resolve/main/vocab.txt",
                    "vocab.txt"
                )
            ),
            onnxFileName = "model.onnx",
            vocabFileName = "vocab.txt"
        )
        // Generative models (e.g. Llama 3.2 1B for tag suggestions) are added in a later
        // phase alongside their inference engine, so we don't offer a large download that
        // nothing consumes yet.
    )

    /** User-added models (custom HuggingFace URLs), persisted across launches. */
    private val _customModels = MutableStateFlow<List<ModelSpec>>(emptyList())
    val customModels: StateFlow<List<ModelSpec>> = _customModels.asStateFlow()

    /** Curated catalog plus any user-added models. */
    fun allSpecs(): List<ModelSpec> = CATALOG + _customModels.value

    fun spec(id: String): ModelSpec? = allSpecs().firstOrNull { it.id == id }

    // ---- State -------------------------------------------------------------

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    private val activeJobs = HashMap<String, Job>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun modelDir(context: Context, id: String) =
        File(File(context.filesDir, "models"), id)

    fun stateOf(id: String): State = _states.value[id] ?: State.NotDownloaded

    private fun setState(id: String, state: State) {
        _states.value = _states.value.toMutableMap().apply { put(id, state) }
    }

    /** True if every file in the bundle is present on disk. */
    fun isReady(context: Context, id: String): Boolean {
        val spec = spec(id) ?: return false
        val dir = modelDir(context, id)
        return spec.files.all { File(dir, it.name).let { f -> f.exists() && f.length() > 0 } }
    }

    /** Absolute path to a downloaded file within a model bundle, or null if missing. */
    fun fileFor(context: Context, id: String, name: String): File? =
        File(modelDir(context, id), name).takeIf { it.exists() && it.length() > 0 }

    /** Reconcile in-memory state with what's actually on disk. Safe to call on startup. */
    fun refreshStates(context: Context) {
        val map = HashMap<String, State>()
        for (spec in allSpecs()) {
            map[spec.id] = if (isReady(context, spec.id)) State.Ready else State.NotDownloaded
        }
        _states.value = map
    }

    // ---- Custom models -----------------------------------------------------

    /**
     * A user-added model, persisted as JSON. Embedding models save model.onnx + vocab.txt;
     * generation (MediaPipe) models save a single model.task. [purpose] defaults to EMBEDDING so
     * entries written before generation support deserialize correctly.
     */
    private data class CustomEntry(
        @com.google.gson.annotations.SerializedName("id") val id: String,
        @com.google.gson.annotations.SerializedName("name") val name: String,
        @com.google.gson.annotations.SerializedName("modelUrl") val modelUrl: String,
        @com.google.gson.annotations.SerializedName("vocabUrl") val vocabUrl: String,
        @com.google.gson.annotations.SerializedName("purpose") val purpose: String = "EMBEDDING"
    )

    private fun CustomEntry.toSpec(): ModelSpec {
        val repoSlug = modelUrl.substringAfter("huggingface.co/").substringBefore("/resolve")
        return if (purpose == "GENERATION") {
            ModelSpec(
                id = id,
                displayName = name,
                description = "Language model • $repoSlug",
                purpose = Purpose.GENERATION,
                approxBytes = 1_000_000_000,
                files = listOf(ModelFile(modelUrl, "model.task")),
                onnxFileName = "model.task",
                vocabFileName = null
            )
        } else {
            ModelSpec(
                id = id,
                displayName = name,
                description = "Embedding model • $repoSlug",
                purpose = Purpose.EMBEDDING,
                approxBytes = 30_000_000,
                files = listOf(ModelFile(modelUrl, "model.onnx"), ModelFile(vocabUrl, "vocab.txt")),
                onnxFileName = "model.onnx",
                vocabFileName = "vocab.txt"
            )
        }
    }

    /** Load persisted custom models from the JSON blob produced by [serializeCustomModels]. */
    fun loadCustomModels(context: Context, json: String) {
        val entries = runCatching {
            val type = object : com.google.gson.reflect.TypeToken<List<CustomEntry>>() {}.type
            com.google.gson.Gson().fromJson<List<CustomEntry>>(json, type)
        }.getOrNull() ?: emptyList()
        _customModels.value = entries.map { it.toSpec() }
        refreshStates(context)
    }

    /** Serialize current custom models so the caller can persist them. */
    fun serializeCustomModels(): String {
        val entries = _customModels.value.map {
            CustomEntry(
                it.id, it.displayName, it.files[0].url, it.files.getOrNull(1)?.url ?: "",
                if (it.purpose == Purpose.GENERATION) "GENERATION" else "EMBEDDING"
            )
        }
        return com.google.gson.Gson().toJson(entries)
    }

    /**
     * Add a custom model from a HuggingFace ONNX URL + vocab URL. Returns the new id, or null
     * if the URL looks invalid. Caller should persist [serializeCustomModels] afterwards.
     */
    fun addCustomModel(context: Context, name: String, modelUrl: String, vocabUrl: String): String? {
        val m = modelUrl.trim()
        val v = vocabUrl.trim()
        if (!m.startsWith("http") || !v.startsWith("http")) return null
        val id = "custom-" + kotlin.math.abs((m + v).hashCode()).toString(16)
        if (allSpecs().any { it.id == id }) return id // already present
        val entry = CustomEntry(id, name.ifBlank { "Custom model" }, m, v)
        _customModels.value = _customModels.value + entry.toSpec()
        refreshStates(context)
        return id
    }

    /**
     * Add a custom generative (MediaPipe `.task`) model from a HuggingFace URL. Returns the new
     * id, or null if the URL is invalid. Caller should persist [serializeCustomModels] afterwards.
     */
    fun addCustomGenModel(context: Context, name: String, taskUrl: String): String? {
        val m = taskUrl.trim()
        if (!m.startsWith("http")) return null
        val id = "customgen-" + kotlin.math.abs(m.hashCode()).toString(16)
        if (allSpecs().any { it.id == id }) return id
        val entry = CustomEntry(id, name.ifBlank { "Custom LLM" }, m, "", "GENERATION")
        _customModels.value = _customModels.value + entry.toSpec()
        refreshStates(context)
        return id
    }

    /** Remove a custom model (and its files). Caller should re-persist afterwards. */
    fun removeCustomModel(context: Context, id: String) {
        delete(context, id)
        _customModels.value = _customModels.value.filterNot { it.id == id }
    }

    // ---- Download / delete -------------------------------------------------

    /** Start (or restart) downloading a model bundle. No-op if already downloading. */
    fun download(scope: CoroutineScope, context: Context, id: String) {
        val spec = spec(id) ?: return
        if (_states.value[id] is State.Downloading) return
        if (isReady(context, id)) { setState(id, State.Ready); return }

        setState(id, State.Downloading(-1f))
        val job = scope.launch(Dispatchers.IO) {
            val dir = modelDir(context, id).apply { mkdirs() }
            try {
                val totalBytes = spec.files.sumOf { it.expectedOrZero() }.takeIf { it > 0 }
                    ?: spec.approxBytes
                var downloadedSoFar = 0L

                for (mf in spec.files) {
                    val dest = File(dir, mf.name)
                    val tmp = File(dir, "${mf.name}.part")
                    val req = Request.Builder().url(mf.url).build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} for ${mf.name}")
                        val body = resp.body ?: throw Exception("empty body for ${mf.name}")
                        tmp.outputStream().use { out ->
                            val src = body.byteStream()
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (!isActive) throw Exception("cancelled")
                                val n = src.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                downloadedSoFar += n
                                val p = (downloadedSoFar.toFloat() / totalBytes).coerceIn(0f, 1f)
                                setState(id, State.Downloading(p))
                            }
                        }
                    }
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true); tmp.delete()
                    }
                }
                val ready = isReady(context, id)
                setState(id, if (ready) State.Ready else State.Failed("incomplete"))
                // A freshly downloaded embedding model means the engine must be (re)built.
                if (ready && spec.purpose == Purpose.EMBEDDING) EmbeddingService.invalidate()
            } catch (e: Exception) {
                // Clean partial files so a retry starts fresh.
                runCatching { dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() } }
                setState(id, State.Failed(e.message ?: "download failed"))
            } finally {
                activeJobs.remove(id)
            }
        }
        activeJobs[id] = job
    }

    /** Cancel an in-flight download. */
    fun cancel(id: String) {
        activeJobs.remove(id)?.cancel()
        if (_states.value[id] is State.Downloading) setState(id, State.NotDownloaded)
    }

    /** Delete a downloaded model bundle from disk. */
    fun delete(context: Context, id: String) {
        cancel(id)
        runCatching { modelDir(context, id).deleteRecursively() }
        setState(id, State.NotDownloaded)
        when (spec(id)?.purpose) {
            Purpose.EMBEDDING -> EmbeddingService.invalidate()
            Purpose.GENERATION -> LlmEngineManager.unload()
            else -> {}
        }
    }

    private fun ModelFile.expectedOrZero(): Long = 0L // content-length resolved at runtime
}
