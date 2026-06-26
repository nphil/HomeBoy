package com.homeboy.app.ai

import android.content.Context
import com.homeboy.app.api.HBItem
import kotlinx.coroutines.Dispatchers
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

    private const val MODEL_ID = "minilm-l6-v2"

    private var engine: EmbeddingEngine? = null
    private var initFailed = false

    /** Cached item vectors: id -> (contentHash, vector). */
    private val vectorCache = HashMap<String, Pair<Int, FloatArray>>()

    /** True if a usable engine exists or can be built right now. */
    fun isAvailable(context: Context): Boolean = engineOrNull(context) != null

    /** Forget the engine + caches, e.g. after the model is (re)downloaded or deleted. */
    @Synchronized
    fun invalidate() {
        runCatching { engine?.close() }
        engine = null
        initFailed = false
        vectorCache.clear()
    }

    @Synchronized
    private fun engineOrNull(context: Context): EmbeddingEngine? {
        engine?.let { return it }
        if (initFailed) return null
        if (!ModelRepository.isReady(context, MODEL_ID)) return null
        val model = ModelRepository.fileFor(context, MODEL_ID, "model.onnx")
        val vocab = ModelRepository.fileFor(context, MODEL_ID, "vocab.txt")
        if (model == null || vocab == null) return null
        val built = EmbeddingEngine.create(model, vocab)
        if (built == null) { initFailed = true; return null }
        engine = built
        return built
    }

    private fun itemText(item: HBItem): String =
        (item.name + " " + (item.description ?: "")).trim()

    @Synchronized
    private fun vectorFor(eng: EmbeddingEngine, item: HBItem): FloatArray? {
        val text = itemText(item)
        val hash = text.hashCode()
        vectorCache[item.id]?.let { (h, v) -> if (h == hash) return v }
        val v = eng.embed(text) ?: return null
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
            val q = eng.embed(query) ?: return@withContext null
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
