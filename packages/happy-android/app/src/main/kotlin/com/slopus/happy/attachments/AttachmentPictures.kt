package com.slopus.happy.attachments

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * useAttachmentImage's cache, for one account (happy-ios
 * `AttachmentPictures`): the session pictures it has decoded, by session and
 * ref, kept while they fit in [maxCount] pictures and [maxCost] bytes, the
 * least recently used let go first, and the thumbhashes it has decoded. One
 * load per picture at a time; a failed load is not kept, so the next screen
 * tries again.
 *
 * [AttachmentServices] keeps one per signed-in account, so pictures are never
 * shared between accounts and go with the account when it changes.
 */
class AttachmentPictures(val maxCount: Int = DEFAULT_MAX_COUNT, val maxCost: Int = DEFAULT_MAX_COST) {
    data class Key(val sessionId: String, val ref: String)

    private val entries = LinkedHashMap<Key, Bitmap>(16, 0.75f, true)
    private val inFlight = HashMap<Key, CompletableDeferred<Bitmap?>>()
    private var totalCost = 0
    private val placeholders = object : android.util.LruCache<String, Bitmap>(64) {}

    /** The kept picture, which counts as a use. */
    @Synchronized
    fun cached(key: Key): Bitmap? = entries[key]

    /** The picture, kept or loaded; null when [load] couldn't make it. A load already on its way for [key] is shared. */
    suspend fun picture(key: Key, load: suspend () -> Bitmap?): Bitmap? {
        var owner = false
        val deferred = synchronized(this) {
            entries[key]?.let { return it }
            inFlight[key] ?: CompletableDeferred<Bitmap?>().also {
                inFlight[key] = it
                owner = true
            }
        }
        if (!owner) return deferred.await()
        val loaded = try {
            runCatching { load() }.getOrNull()
        } finally {
            synchronized(this) { inFlight.remove(key) }
        }
        if (loaded != null) remember(key, loaded)
        deferred.complete(loaded)
        return loaded
    }

    /** A thumbhash already decoded, or null. */
    fun cachedPlaceholder(thumbhash: String): Bitmap? = placeholders.get(thumbhash)

    /** The thumbhash as a picture, decoded off the main thread. */
    suspend fun placeholder(thumbhash: String): Bitmap? {
        placeholders.get(thumbhash)?.let { return it }
        val bitmap = withContext(Dispatchers.Default) { thumbhashBitmap(thumbhash) }
        bitmap ?: return null
        placeholders.put(thumbhash, bitmap)
        return bitmap
    }

    /** Lets every picture go (memory pressure). */
    @Synchronized
    fun removeAll() {
        entries.clear()
        totalCost = 0
        placeholders.evictAll()
    }

    @Synchronized
    private fun remember(key: Key, picture: Bitmap) {
        entries.remove(key)?.let { totalCost -= it.allocationByteCount }
        val cost = picture.allocationByteCount
        // One picture past the whole budget is shown, not kept.
        if (cost > maxCost) return
        entries[key] = picture
        totalCost += cost
        val iterator = entries.entries.iterator()
        while ((entries.size > maxCount || totalCost > maxCost) && iterator.hasNext()) {
            totalCost -= iterator.next().value.allocationByteCount
            iterator.remove()
        }
    }

    companion object {
        /** `MAX_CACHE_ENTRIES`. */
        const val DEFAULT_MAX_COUNT = 50
        /** Decoded, a picture can take a few megabytes where useAttachmentImage keeps a data URI, so this bounds bytes as well. */
        const val DEFAULT_MAX_COST = 48 * 1024 * 1024

        /** A thumbhash decoded to its small picture, or null when it is not one. */
        fun thumbhashBitmap(thumbhash: String): Bitmap? {
            val image = com.slopus.happy.images.ThumbHash.decode(thumbhash) ?: return null
            val colors = IntArray(image.width * image.height) { i ->
                val r = image.rgba[i * 4].toInt() and 0xFF
                val g = image.rgba[i * 4 + 1].toInt() and 0xFF
                val b = image.rgba[i * 4 + 2].toInt() and 0xFF
                val a = image.rgba[i * 4 + 3].toInt() and 0xFF
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            return Bitmap.createBitmap(colors, image.width, image.height, Bitmap.Config.ARGB_8888)
        }
    }
}
