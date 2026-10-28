package com.slopus.happy.images

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

/**
 * The app's one image loader (Coil 3), for every picture that isn't
 * bundled: profile pictures by URL, markdown images, decrypted
 * attachments. A memory cache (a fifth of the app's memory budget) and a
 * 128 MB disk cache for URLs, as expo-image keeps; downloads on its own
 * OkHttp client with no Happy headers, since these URLs are third-party.
 */
object HappyImageLoader {
    @Volatile private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: build(context.applicationContext).also { instance = it }
    }

    private fun build(context: Context): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache { DiskCache.Builder().directory(imageCacheDir(context).toOkioPath()).maxSizeBytes(128L * 1024 * 1024).build() }
            .crossfade(FADE_MS)
            .build()
    }

    /** Logout: both caches emptied, or the disk one's directory removed if no loader was built yet. */
    fun clear(context: Context) {
        val loader = synchronized(this) { instance }
        if (loader != null) {
            loader.memoryCache?.clear()
            // Through the cache itself: it keeps a journal in that directory.
            loader.diskCache?.clear()
        } else {
            imageCacheDir(context.applicationContext ?: context).deleteRecursively()
        }
    }

    private fun imageCacheDir(context: Context) = context.cacheDir.resolve("images")

    /** expo-image's `transition` in happy-app's image views. */
    const val FADE_MS = 150
}

