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
        )
        // Generative models (e.g. Llama 3.2 1B for tag suggestions) are added in a later
        // phase alongside their inference engine, so we don't offer a large download that
        // nothing consumes yet.
    )

    fun spec(id: String): ModelSpec? = CATALOG.firstOrNull { it.id == id }

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
        for (spec in CATALOG) {
            map[spec.id] = if (isReady(context, spec.id)) State.Ready else State.NotDownloaded
        }
        _states.value = map
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
        if (spec(id)?.purpose == Purpose.EMBEDDING) EmbeddingService.invalidate()
    }

    private fun ModelFile.expectedOrZero(): Long = 0L // content-length resolved at runtime
}
