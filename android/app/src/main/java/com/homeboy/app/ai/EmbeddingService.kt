package com.homeboy.app.ai

import android.content.Context
import com.homeboy.app.api.HBItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * App-wide façade for semantic item search. Lazily builds an [EmbeddingEngine] from the
 * downloaded MiniLM model, caches per-item vectors (keyed by content hash so edits
 * re-embed), and re-ranks a candidate list by cosine similarity to the query.
 *
 * Everything degrades to null when the model isn't present or inference fails, so callers
 * simply fall back to ordinary keyword search.
 */
object EmbeddingService {

    /** The active embedding model id; updated from preferences. */
    @Volatile
    var selectedModelId: String = "nomic-embed-v1.5"
        private set

    /** Switch the active model. Rebuilds the engine on next use if it changed. A blank or
     *  unknown non-custom id falls back to the default so search never points at a dead model. */
    fun setModel(id: String) {
        val resolved = when {
            id.isBlank() -> return
            ModelRepository.spec(id) != null -> id
            id.startsWith("custom") -> id
            else -> "nomic-embed-v1.5"
        }
        if (resolved != selectedModelId) {
            selectedModelId = resolved
            invalidate()
        }
    }

    private var engine: EmbeddingEngine? = null
    private var initFailed = false

    /** User backend override for the active model (null = smart default NPU → CPU). */
    @Volatile
    private var preferredBackend: AiBackend? = null

    /** Set the backend override; rebuilds the engine on next use if it changed. */
    fun setPreferredBackend(backend: AiBackend?) {
        if (backend != preferredBackend) {
            preferredBackend = backend
            invalidate()
        }
    }

    /** Cached item vectors: id -> (contentHash, vector). */
    private val vectorCache = HashMap<String, Pair<Int, FloatArray>>()

    /**
     * Which hardware tier the active engine is running on, or null until one is built.
     */
    private val _backend = MutableStateFlow<AiBackend?>(null)
    val backend: StateFlow<AiBackend?> = _backend.asStateFlow()

    /** True if a usable engine exists or can be built right now. */
    fun isAvailable(context: Context): Boolean = engineOrNull(context) != null

    /** Forget the engine + caches, e.g. after the model is (re)downloaded or deleted. */
    @Synchronized
    fun invalidate() {
        runCatching { engine?.close() }
        engine = null
        initFailed = false
        _backend.value = null
        vectorCache.clear()
    }

    @Synchronized
    private fun engineOrNull(context: Context): EmbeddingEngine? {
        engine?.let { return it }
        if (initFailed) return null
        val id = selectedModelId
        if (!ModelRepository.isReady(context, id)) return null
        val model = ModelRepository.fileFor(context, id, "model.gguf")
        if (model == null) { initFailed = true; return null }
        val built = EmbeddingEngine.create(model, context.applicationInfo.nativeLibraryDir, preferredBackend)
        if (built == null) { initFailed = true; return null }
        engine = built
        _backend.value = built.backend
        return built
    }

    private fun itemText(item: HBItem): String =
        (item.name + " " + (item.description ?: "")).trim()

    /**
     * Query-time instruction prefix required by each model family. Without it, retrieval models
     * treat the query as a document and similarity degrades significantly for synonym matching.
     */
    private fun queryPrefix(modelId: String): String = when {
        modelId.startsWith("nomic-embed") -> "search_query: "
        modelId.startsWith("bge-") -> "Represent this sentence for searching relevant passages: "
        modelId.startsWith("embeddinggemma") -> "task: search result | query: "
        else -> ""
    }

    /**
     * Document-time instruction prefix. Nomic and EmbeddingGemma require one; bge and custom models
     * use symmetric encoding (no prefix on the document side).
     */
    private fun docPrefix(modelId: String): String = when {
        modelId.startsWith("nomic-embed") -> "search_document: "
        modelId.startsWith("embeddinggemma") -> "title: none | text: "
        else -> ""
    }

    @Synchronized
    private fun vectorFor(eng: EmbeddingEngine, item: HBItem): FloatArray? {
        val text = itemText(item)
        val hash = text.hashCode()
        vectorCache[item.id]?.let { (h, v) -> if (h == hash) return v }
        val prefix = docPrefix(selectedModelId)
        val v = eng.embed(prefix + text) ?: return null
        vectorCache[item.id] = hash to v
        return v
    }

    /**
     * Re-rank [items] by semantic similarity to [query]. Literal keyword hits get a boost so
     * exact matches still float to the top. Returns null if semantic search is unavailable,
     * signalling the caller to keep its existing ordering.
     */
    suspend fun rank(context: Context, query: String, items: List<HBItem>): List<HBItem>? {
        val eng = engineOrNull(context) ?: return null
        if (query.isBlank() || items.isEmpty()) return null
        return withContext(Dispatchers.Default) {
            val prefix = queryPrefix(selectedModelId)
            val q = eng.embed(prefix + query) ?: return@withContext null
            val needle = query.trim()
            items
                .map { item ->
                    val vec = vectorFor(eng, item)
                    val sim = if (vec != null) EmbeddingEngine.cosine(q, vec) else 0f
                    val literal = item.name.contains(needle, ignoreCase = true) ||
                        (item.description?.contains(needle, ignoreCase = true) == true)
                    item to (sim + if (literal) 0.5f else 0f)
                }
                .sortedByDescending { it.second }
                .map { it.first }
        }
    }
}
