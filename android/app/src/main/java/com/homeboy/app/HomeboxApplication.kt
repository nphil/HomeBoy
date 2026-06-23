package com.homeboy.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.homeboy.app.data.HomeboxRepository
import com.homeboy.app.data.PreferencesRepository
import com.homeboy.app.data.SessionHolder
import okhttp3.OkHttpClient

class HomeboxApplication : Application(), ImageLoaderFactory {
    val prefs: PreferencesRepository by lazy { PreferencesRepository(this) }
    val repository: HomeboxRepository by lazy { HomeboxRepository(prefs) }

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
