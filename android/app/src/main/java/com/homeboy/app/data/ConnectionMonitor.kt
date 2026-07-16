package com.homeboy.app.data

import com.homeboy.app.api.HomeboxHttpException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

enum class ConnectionState { ONLINE, OFFLINE, SYNCING }

/**
 * True for failures that mean "the server can't be reached right now" (no network,
 * DNS/connect/timeout, or the server answering 5xx/429 mid-restart) as opposed to
 * a permanent rejection of the request (validation 4xx). Only transient failures
 * trigger the offline cache fallback and mutation queueing — queueing a request
 * the server has permanently rejected would retry it forever.
 */
fun Throwable.isTransient(): Boolean = when (this) {
    is HomeboxHttpException -> code == 408 || code == 425 || code == 429 || code >= 500
    is IOException -> true
    else -> false
}

/**
 * App-wide connection/sync status, readable from anywhere (UI indicator, ViewModels)
 * and reported into by HomeboxRepository. Same spirit as SessionHolder: a tiny
 * process-wide singleton instead of threading state through constructors.
 */
object ConnectionMonitor {

    private val _state = MutableStateFlow(ConnectionState.ONLINE)
    val state = _state.asStateFlow()

    /** Number of offline mutations waiting to be replayed against the server. */
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount = _pendingCount.asStateFlow()

    /**
     * Emitted after a reconnect sync completes so every screen reloads fresh
     * server data. ViewModels collect this and call their load().
     */
    private val _refreshTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshTicks = _refreshTicks.asSharedFlow()

    /** System-level connectivity, maintained by HomeboxApplication's network callback. */
    @Volatile
    var networkAvailable: Boolean = true

    /** Called on every successful server round-trip. Never interrupts an active sync. */
    fun reportOnline() {
        if (_state.value != ConnectionState.SYNCING) _state.value = ConnectionState.ONLINE
    }

    /** Called when a request fails transiently. Never interrupts an active sync. */
    fun reportOffline() {
        if (_state.value != ConnectionState.SYNCING) _state.value = ConnectionState.OFFLINE
    }

    /** Sync lifecycle — only HomeboxRepository.syncNow() drives these. */
    fun syncStarted() { _state.value = ConnectionState.SYNCING }
    fun syncFinished(online: Boolean) {
        _state.value = if (online) ConnectionState.ONLINE else ConnectionState.OFFLINE
    }

    fun setPendingCount(n: Int) { _pendingCount.value = n }

    fun requestRefresh() { _refreshTicks.tryEmit(Unit) }
}
