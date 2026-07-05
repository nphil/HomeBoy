package com.homeboy.app.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
        /** The on-device GGUF file name (within the bundle dir). Always "model.gguf". */
        val modelFileName: String = "model.gguf"
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
        // ---- Embedding models (semantic search) --------------------------------
        ModelSpec(
            id = "nomic-embed-v1.5",
            displayName = "Nomic Embed v1.5 (semantic search)",
            description = "Balanced sentence embeddings, proven on-device. Q8_0 — runs on NPU/CPU. ~145 MB.",
            purpose = Purpose.EMBEDDING,
            approxBytes = 145_000_000,
            files = listOf(
                ModelFile(
                    "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q8_0.gguf",
                    "model.gguf"
                )
            )
        ),
        ModelSpec(
            id = "embeddinggemma-300m",
            displayName = "EmbeddingGemma 300M (semantic search)",
            description = "Google's 2026 embedder — highest retrieval quality on-device. Q8_0 — runs on NPU/CPU. ~334 MB.",
            purpose = Purpose.EMBEDDING,
            approxBytes = 333_590_944,
            files = listOf(
                ModelFile(
                    "https://huggingface.co/ggml-org/embeddinggemma-300M-GGUF/resolve/main/embeddinggemma-300M-Q8_0.gguf",
                    "model.gguf"
                )
            )
        ),
        ModelSpec(
            id = "bge-small-en-v1.5",
            displayName = "BGE Small EN v1.5 (semantic search)",
            description = "Lightweight, fast English embeddings — best quality per MB. Q8_0 — runs on NPU/CPU. ~35 MB.",
            purpose = Purpose.EMBEDDING,
            approxBytes = 35_000_000,
            files = listOf(
                ModelFile(
                    "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q8_0.gguf",
                    "model.gguf"
                )
            )
        ),
        ModelSpec(
            id = "bge-large-en-v1.5",
            displayName = "BGE Large EN v1.5 (semantic search)",
            description = "Larger English BERT embedder. Q8_0 — runs on NPU/CPU. ~358 MB.",
            purpose = Purpose.EMBEDDING,
            approxBytes = 358_235_712,
            files = listOf(
                ModelFile(
                    "https://huggingface.co/CompendiumLabs/bge-large-en-v1.5-gguf/resolve/main/bge-large-en-v1.5-q8_0.gguf",
                    "model.gguf"
                )
            )
        ),
        // ---- Generation models (tag suggestions) -------------------------------
        // Straight instruct models (no thinking mode) are more reliable at "output only tags" and
        // faster than a hybrid-thinking model. Q4_0 is the universal quant — accelerated on NPU, GPU
        // and CPU (the Hexagon backend only repacks Q4_0/Q8_0/MXFP4); tag generation runs on the CPU.
        ModelSpec(
            id = "qwen2.5-1.5b-instruct",
            displayName = "Qwen2.5 1.5B Instruct (tag suggestions)",
            description = "Recommended. Reliable instruct model for tag suggestions. Q4_0 — runs on CPU. ~0.9 GB.",
            purpose = Purpose.GENERATION,
            approxBytes = 937_535_744,
            files = listOf(
                ModelFile(
                    "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_0.gguf",
                    "model.gguf"
                )
            )
        ),
        ModelSpec(
            id = "llama3.2-1b-instruct",
            displayName = "Llama 3.2 1B Instruct (tag suggestions)",
            description = "Smallest & fastest tag generator. Q4_0 — runs on CPU. ~0.77 GB.",
            purpose = Purpose.GENERATION,
            approxBytes = 773_025_920,
            files = listOf(
                ModelFile(
                    "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_0.gguf",
                    "model.gguf"
                )
            )
        ),
        ModelSpec(
            id = "qwen3-1.7b-instruct",
            displayName = "Qwen3 1.7B (tag suggestions)",
            description = "Larger hybrid-thinking model (uses /no_think for tags). Q4_K_M — runs on CPU. ~1.3 GB.",
            purpose = Purpose.GENERATION,
            approxBytes = 1_282_439_584,
            files = listOf(
                ModelFile(
                    "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf",
                    "model.gguf"
                )
            )
        )
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

    private const val MAX_DOWNLOAD_ATTEMPTS = 6

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // readTimeout is the max gap between data packets — a stalled connection trips it, which we
        // catch and resume from the .part file rather than failing the whole download.
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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
        val gen = purpose == "GENERATION"
        return ModelSpec(
            id = id,
            displayName = name,
            description = (if (gen) "Language model • " else "Embedding model • ") + repoSlug,
            purpose = if (gen) Purpose.GENERATION else Purpose.EMBEDDING,
            approxBytes = if (gen) 1_000_000_000 else 140_000_000,
            files = listOf(ModelFile(modelUrl, "model.gguf"))
        )
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
     * Add a custom embedding model from a HuggingFace GGUF URL. Returns the new id, or null if the
     * URL looks invalid. Caller should persist [serializeCustomModels] afterwards.
     */
    fun addCustomModel(context: Context, name: String, ggufUrl: String): String? {
        val m = ggufUrl.trim()
        if (!m.startsWith("http")) return null
        val id = "custom-" + kotlin.math.abs(m.hashCode()).toString(16)
        if (allSpecs().any { it.id == id }) return id // already present
        val entry = CustomEntry(id, name.ifBlank { "Custom embedder" }, m, "", "EMBEDDING")
        _customModels.value = _customModels.value + entry.toSpec()
        refreshStates(context)
        return id
    }

    /**
     * Add a custom generative model from a HuggingFace GGUF URL. Returns the new id, or null if
     * the URL is invalid. Caller should persist [serializeCustomModels] afterwards.
     */
    fun addCustomGenModel(context: Context, name: String, ggufUrl: String): String? {
        val m = ggufUrl.trim()
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

    /**
     * Start (or restart) downloading a model bundle. No-op if already downloading. [token] is the
     * user's HuggingFace access token (used as a Bearer header for huggingface.co URLs — raises rate
     * limits and reaches gated repos). Downloads are **resumable**: each file streams to a `.part`
     * file and, on any network error/stall, retries with backoff and an HTTP Range request that
     * picks up where it left off instead of restarting from zero.
     */
    fun download(scope: CoroutineScope, context: Context, id: String, token: String? = null) {
        val spec = spec(id) ?: return
        if (_states.value[id] is State.Downloading) return
        if (isReady(context, id)) { setState(id, State.Ready); return }

        setState(id, State.Downloading(-1f))
        val job = scope.launch(Dispatchers.IO) {
            val dir = modelDir(context, id).apply { mkdirs() }
            try {
                // Prefer real content-lengths (so progress is accurate + resumes are correct); fall
                // back to the catalog estimate if the server won't answer a HEAD.
                val sizes = spec.files.map { resolveContentLength(it.url, token) }
                val totalBytes = sizes.filterNotNull()
                    .takeIf { it.size == spec.files.size && it.sum() > 0 }?.sum()
                    ?: spec.approxBytes
                var completedBase = 0L
                for (mf in spec.files) {
                    val dest = File(dir, mf.name)
                    if (dest.exists() && dest.length() > 0) { completedBase += dest.length(); continue }
                    downloadWithResume(mf.url, dest, token) { fileBytes ->
                        val p = ((completedBase + fileBytes).toFloat() / totalBytes).coerceIn(0f, 1f)
                        setState(id, State.Downloading(p))
                    }
                    completedBase += dest.length()
                }
                val ready = isReady(context, id)
                setState(id, if (ready) State.Ready else State.Failed("incomplete"))
                // A freshly downloaded embedding model means the engine must be (re)built.
                if (ready && spec.purpose == Purpose.EMBEDDING) EmbeddingService.invalidate()
            } catch (e: CancellationException) {
                throw e // user cancelled — keep the .part file so a later retry resumes
            } catch (e: Exception) {
                // Keep any .part file on disk so the next attempt resumes instead of restarting.
                setState(id, State.Failed(e.message ?: "download failed"))
            } finally {
                activeJobs.remove(id)
            }
        }
        activeJobs[id] = job
    }

    /**
     * Stream [url] into [dest] with resume + retry. Writes to `<dest>.part`; on a network error or
     * stall it waits (exponential backoff) and re-requests with `Range: bytes=<have>-` so only the
     * missing tail is fetched. [onFileBytes] reports the running on-disk byte count for progress.
     */
    private suspend fun downloadWithResume(
        url: String,
        dest: File,
        token: String?,
        onFileBytes: (Long) -> Unit,
    ) {
        val part = File(dest.parentFile, "${dest.name}.part")
        var attempt = 0
        while (true) {
            val have = if (part.exists()) part.length() else 0L
            try {
                val reqB = Request.Builder().url(url)
                if (have > 0) reqB.header("Range", "bytes=$have-")
                addAuth(reqB, url, token)
                client.newCall(reqB.build()).execute().use { resp ->
                    when (resp.code) {
                        // Range past EOF → the .part is already the whole file.
                        416 -> Unit
                        200, 206 -> {
                            // A 200 means the server ignored our Range — restart the file from zero.
                            val append = resp.code == 206 && have > 0
                            if (!append && have > 0) part.delete()
                            var written = if (append) have else 0L
                            val body = resp.body ?: throw IOException("empty response body")
                            FileOutputStream(part, append).use { out ->
                                val src = body.byteStream()
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
                                    val n = src.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    written += n
                                    onFileBytes(written)
                                }
                            }
                        }
                        else -> throw IOException("HTTP ${resp.code}")
                    }
                }
                if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
                return
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                attempt++
                if (attempt >= MAX_DOWNLOAD_ATTEMPTS) throw e
                // 1s, 2s, 4s, 8s, 16s — the .part file is kept so the next try resumes.
                delay(1000L shl (attempt - 1))
            }
        }
    }

    /** Best-effort total size via HEAD; null if the server won't say (we fall back to the estimate). */
    private fun resolveContentLength(url: String, token: String?): Long? = runCatching {
        val b = Request.Builder().url(url).head()
        addAuth(b, url, token)
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) null else r.header("Content-Length")?.toLongOrNull()
        }
    }.getOrNull()

    /** Attach the HF bearer token for huggingface.co requests (dropped automatically on CDN redirect). */
    private fun addAuth(builder: Request.Builder, url: String, token: String?) {
        if (!token.isNullOrBlank() && url.contains("huggingface.co")) {
            builder.header("Authorization", "Bearer $token")
        }
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

}
