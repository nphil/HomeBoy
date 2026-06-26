package com.homeboy.app.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Provides the searchable Material Icons name catalog.
 *
 * Source: Google's official material-design-icons repo "codepoints" file — a plain-text
 * list of `name codepoint` lines (the exact classic Outlined set the bundled
 * material-icons-extended library can render). This is far more robust than the
 * XSSI-prefixed fonts.google.com/metadata JSON, which is what made it report "offline".
 *
 * The catalog is cached to disk on first successful fetch, so search keeps working
 * offline afterwards — there is no hand-maintained offline icon list.
 */
object IconSearchRepository {

    enum class Status { IDLE, LOADING, READY, OFFLINE }

    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/google/material-design-icons/master/font/MaterialIconsOutlined-Regular.codepoints"
    private const val CACHE_FILE = "icon_catalog.txt"

    private val _names = MutableStateFlow<List<String>>(emptyList())
    val names: StateFlow<List<String>> = _names.asStateFlow()

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var started = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Loads the catalog: instantly from disk cache if present, then refreshes from the
     * network in the background. Safe to call repeatedly; only acts once per process.
     */
    fun ensureLoaded(scope: CoroutineScope, context: Context) {
        if (started) return
        started = true
        val cacheFile = File(context.filesDir, CACHE_FILE)

        scope.launch(Dispatchers.IO) {
            // 1. Seed from disk cache for instant offline availability.
            if (_names.value.isEmpty() && cacheFile.exists()) {
                runCatching { parse(cacheFile.readText()) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        _names.value = it
                        _status.value = Status.READY
                    }
            }

            // 2. Refresh from network.
            if (_names.value.isEmpty()) _status.value = Status.LOADING
            try {
                val request = Request.Builder().url(CATALOG_URL).build()
                val body = client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                    resp.body?.string()
                } ?: throw Exception("empty response")

                val parsed = parse(body)
                if (parsed.isNotEmpty()) {
                    _names.value = parsed
                    _status.value = Status.READY
                    runCatching { cacheFile.writeText(body) }
                } else if (_names.value.isEmpty()) {
                    _status.value = Status.OFFLINE
                }
            } catch (_: Exception) {
                // Network failed — keep any disk-cached catalog; only flag offline if empty.
                if (_names.value.isEmpty()) _status.value = Status.OFFLINE
            }
        }
    }

    /** Force a fresh network fetch (e.g. user taps "retry" while offline). */
    fun retry(scope: CoroutineScope, context: Context) {
        started = false
        ensureLoaded(scope, context)
    }

    /** Parse the codepoints file: one `name codepoint` per line; keep the name. */
    private fun parse(text: String): List<String> =
        text.lineSequence()
            .mapNotNull { line -> line.trim().substringBefore(' ').takeIf { it.isNotBlank() } }
            .distinct()
            .toList()

    /**
     * Filter the catalog by a query. Matches that start with the query rank first,
     * then word-boundary matches, then any substring. Returns at most [limit] names.
     */
    suspend fun search(query: String, limit: Int = 90): List<String> = withContext(Dispatchers.Default) {
        val q = query.lowercase().trim()
        if (q.isBlank()) return@withContext emptyList()
        val all = _names.value
        val starts = ArrayList<String>()
        val word = ArrayList<String>()
        val contains = ArrayList<String>()
        for (name in all) {
            when {
                name.startsWith(q) -> starts += name
                name.split('_').any { it.startsWith(q) } -> word += name
                name.contains(q) -> contains += name
            }
            if (starts.size >= limit) break
        }
        (starts + word + contains).take(limit)
    }
}
