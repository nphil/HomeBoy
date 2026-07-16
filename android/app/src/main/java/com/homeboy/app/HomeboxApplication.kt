package com.homeboy.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.homeboy.app.data.ConnectionMonitor
import com.homeboy.app.data.ConnectionState
import com.homeboy.app.data.HomeboxRepository
import com.homeboy.app.data.PreferencesRepository
import com.homeboy.app.data.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class HomeboxApplication : Application(), ImageLoaderFactory {
    val prefs: PreferencesRepository by lazy { PreferencesRepository(this) }
    val repository: HomeboxRepository by lazy { HomeboxRepository(this, prefs) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        registerConnectivityCallback()
        // Seed the pending-changes badge from disk off the main thread.
        appScope.launch { repository.seedPendingCount() }
        // Heartbeat: while the SERVER (not the network) is unreachable, no system
        // callback will ever fire when it comes back — probe every 30s so queued
        // offline changes sync and screens refresh without user interaction. Also
        // fires when we're nominally online but changes are still queued (e.g. a
        // replay pass aborted mid-way on a flapping connection). Deliberately does
        // NOT gate on networkAvailable — during wifi↔cellular handover onLost can
        // observe a transiently-null active network and leave the flag stuck false,
        // which would silence the heartbeat forever.
        appScope.launch {
            while (true) {
                delay(30_000)
                val state = ConnectionMonitor.state.value
                val pending = ConnectionMonitor.pendingCount.value
                if (state == ConnectionState.OFFLINE || (state == ConnectionState.ONLINE && pending > 0)) {
                    repository.syncNow()
                }
            }
        }
    }

    /**
     * Kick off a sync that survives navigation — UI code must call this instead of
     * launching syncNow in a composable scope, which dies (and cancels the sync)
     * as soon as the dialog/screen that created it leaves composition.
     */
    fun requestSync() {
        appScope.launch { repository.syncNow() }
    }

    private fun registerConnectivityCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        ConnectionMonitor.networkAvailable = cm.activeNetwork != null
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ConnectionMonitor.networkAvailable = true
                // Connectivity is back — replay queued changes and refresh caches.
                appScope.launch { repository.syncNow() }
            }

            override fun onLost(network: Network) {
                // Only offline when NO network remains (wifi→cellular handoff fires onLost too).
                if (cm.activeNetwork == null) {
                    ConnectionMonitor.networkAvailable = false
                    ConnectionMonitor.reportOffline()
                }
            }
        })
    }

    /**
     * Coil loader that attaches the raw Homebox token + X-Tenant header to every
     * image request, so authenticated attachment URLs load. Reads the live session
     * snapshot synchronously from SessionHolder.
     */
    override fun newImageLoader(): ImageLoader {
        val okHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                SessionHolder.token.takeIf { it.isNotBlank() }?.let {
                    builder.header("Authorization", it) // raw token, no "Bearer " prefix
                }
                SessionHolder.tenant?.takeIf { it.isNotBlank() }?.let {
                    builder.header("X-Tenant", it)
                }
                chain.proceed(builder.build())
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttp)
            .crossfade(180)
            // Homebox attachment responses carry no cache-control headers, so by
            // default Coil would refuse to persist them. Ignoring those headers makes
            // every fetched image land in the disk cache and reload instantly offline.
            .respectCacheHeaders(false)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.30).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
