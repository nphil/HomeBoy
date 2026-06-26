package com.homeboy.app.ui

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class IconMeta(
    val name: String = "",
    val tags: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val popularity: Int = 0
)

private data class GoogleIconsRoot(val icons: List<IconMeta> = emptyList())

/**
 * Fetches the full Material Design icon catalog (~2,500 icons) from the Google Fonts
 * metadata API — the same dataset powering mui.com/material-ui/material-icons.
 * Results are cached in memory for the process lifetime; one HTTP call per session.
 */
object IconSearchRepository {

    private val _icons = MutableStateFlow<List<IconMeta>>(emptyList())
    val icons: StateFlow<List<IconMeta>> = _icons.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private var fetchStarted = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    fun fetchIfNeeded(scope: CoroutineScope) {
        if (fetchStarted) return
        fetchStarted = true
        scope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val request = Request.Builder()
                    .url("https://fonts.google.com/metadata/icons?incomplete=true")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val body = client.newCall(request).execute().use { it.body?.string() }
                    ?: throw Exception("empty response")
                // Strip Google's XSSI protection prefix ")]}'" if present
                val json = if (body.trimStart().startsWith(")]}'")) body.substringAfter('\n') else body
                val parsed = gson.fromJson(json, GoogleIconsRoot::class.java)
                if (parsed.icons.isNotEmpty()) {
                    _icons.value = parsed.icons.sortedByDescending { it.popularity }
                } else {
                    _isOffline.value = true
                }
            } catch (_: Exception) {
                _isOffline.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }
}
