package com.slopus.happy.ui.stemcells

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.slopus.happy.attachments.AttachmentPictures
import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.stemcells.StemCellsOfflineFile
import com.slopus.happy.stemcells.image
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Pictures the daemon serves (bot avatars, media; happy-ios
 * `StemCellsImages`), read once over the tunnel and decoded off the main
 * thread at the size they are drawn. A row's picture is kept on disk with
 * the computer's offline copy, by its path and hash, and drawn from there
 * before (or without) the tunnel. Main thread only.
 */
class StemCellsImages(private val client: DirectAgentClient, private val offline: StemCellsOfflineFile? = null) {
    private val cache = LruCache<String, Bitmap>(64)
    private val loading = HashMap<String, CompletableDeferred<Bitmap?>>()

    /** A row's picture: kept bytes at once, then the computer's. */
    suspend fun picture(path: String, thumbhash: String?, pixels: Int): Bitmap? {
        val key = key(path, thumbhash, pixels)
        cache.get(key)?.let { return it }
        loading[key]?.let { return it.await() }
        val pending = CompletableDeferred<Bitmap?>()
        loading[key] = pending
        var image: Bitmap? = null
        try {
            image = withContext(Dispatchers.IO) {
                offline?.file?.picture(path, thumbhash)?.let { kept -> decode(kept, pixels)?.let { return@withContext it } }
                val data = runCatching { client.image(path) }.getOrNull() ?: return@withContext null
                offline?.file?.keepPicture(data, path, thumbhash)
                decode(data, pixels)
            }
            image?.let { cache.put(key, it) }
        } finally {
            loading.remove(key)
            pending.complete(image)
        }
        return image
    }

    fun cachedPicture(path: String, thumbhash: String?, pixels: Int): Bitmap? = cache.get(key(path, thumbhash, pixels))

    private fun key(path: String, thumbhash: String?, pixels: Int) = "$pixels:$path#${thumbhash ?: ""}"

    companion object {
        /** Decoded no larger than needed for `maxPixels` on its longer side. */
        fun decode(data: ByteArray, maxPixels: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            val longer = max(bounds.outWidth, bounds.outHeight)
            if (longer <= 0) return null
            var sample = 1
            while (longer / (sample * 2) >= maxPixels) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val scale = maxPixels.toDouble() / max(decoded.width, decoded.height)
            if (scale >= 1) return decoded
            return Bitmap.createScaledBitmap(decoded, max(1, (decoded.width * scale).toInt()), max(1, (decoded.height * scale).toInt()), true)
        }

        fun placeholder(thumbhash: String?): Bitmap? = thumbhash?.let { AttachmentPictures.thumbhashBitmap(it) }
    }
}
