package com.slopus.happy.ui.stories

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.slopus.happy.attachments.AttachmentPictures
import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectHttpResponse
import com.slopus.happy.stories.Sticker
import com.slopus.happy.stories.StickerFormat
import com.slopus.happy.stories.StickerPackContents
import com.slopus.happy.stories.StickerRef
import com.slopus.happy.stories.StoryAsset
import com.slopus.happy.stories.StoryFrame
import com.slopus.happy.stories.asset
import com.slopus.happy.stories.stickerFile
import com.slopus.happy.stories.stickerPack
import com.slopus.happy.stories.storyAssetPath
import com.slopus.happy.stories.storyFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One computer's story pictures, recordings and stickers (happy-ios
 * `StoriesMedia`), read over the paired connection and decoded off the main
 * thread at no more than they are drawn. A frame's bytes never change for
 * its URL, so they are kept while memory allows; sticker files are kept on
 * disk by their digest. Main thread only.
 */
class StoriesMedia(private val client: DirectAgentClient, private val stickerDirectory: File?) {
    private class Decoded(val pixels: Int, val bitmap: Bitmap)

    private val images = object : LruCache<String, Decoded>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Decoded) = value.bitmap.allocationByteCount
    }
    private val placeholders = LruCache<String, Bitmap>(64)
    private val loading = HashMap<String, Deferred<Bitmap?>>()
    private val packs = HashMap<String, Deferred<Map<String, Sticker>>>()
    private val stickerFiles = HashMap<String, Deferred<ByteArray?>>()
    private val streams = HashMap<String, StoriesByteStream>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Memory is short: decoded pictures go, a playing recording keeps its own stream. */
    fun trim() {
        images.evictAll()
        streams.clear()
    }

    fun close() = scope.cancel()

    // Frames

    fun cachedFrame(storyId: String, frameId: String, pixels: Int): Bitmap? =
        images.get(key(storyId, frameId))?.takeIf { it.pixels >= pixels }?.bitmap

    /** A frame's picture, or a recording's first frame. */
    suspend fun frame(storyId: String, frame: StoryFrame, pixels: Int): Bitmap? {
        cachedFrame(storyId, frame.id, pixels)?.let { return it }
        val key = key(storyId, frame.id)
        val job = "$key@$pixels"
        loading[job]?.let { return it.await() }
        val task = if (frame.kind == StoryFrame.Kind.Video) {
            val stream = stream(storyId, frame)
            scope.async(Dispatchers.IO) { poster(stream, pixels) }
        } else {
            scope.async(Dispatchers.IO) {
                val body = runCatching { client.storyFrame(storyId, frame.id).body }.getOrNull() ?: return@async null
                decode(body, pixels)
            }
        }
        loading[job] = task
        val image = task.await()
        loading.remove(job)
        store(image, key, pixels)
        return image
    }

    private fun store(image: Bitmap?, key: String, pixels: Int) {
        if (image == null || pixels <= (images.get(key)?.pixels ?: 0)) return
        images.put(key, Decoded(pixels, image))
    }

    /** A recorded frame's bytes, streamed a range at a time, for a player. */
    fun source(storyId: String, frame: StoryFrame): MediaDataSource = stream(storyId, frame).copy()

    /** A conversation's picture, before a story holds it. */
    suspend fun assetImage(asset: StoryAsset, pixels: Int): Bitmap? =
        picture("asset/${storyAssetPath(asset)}", pixels) { runCatching { client.asset(asset).body }.getOrNull() }

    /** A conversation's recording, before a story holds it. */
    fun source(asset: StoryAsset): MediaDataSource = StoriesByteStream { range -> client.asset(asset, range) }

    private fun stream(storyId: String, frame: StoryFrame): StoriesByteStream {
        val key = key(storyId, frame.id)
        return streams.getOrPut(key) { StoriesByteStream { range -> client.storyFrame(storyId, frame.id, range) } }
    }

    suspend fun placeholder(thumbhash: String?): Bitmap? {
        thumbhash ?: return null
        placeholders.get(thumbhash)?.let { return it }
        val image = withContext(Dispatchers.Default) { AttachmentPictures.thumbhashBitmap(thumbhash) } ?: return null
        placeholders.put(thumbhash, image)
        return image
    }

    /** Any picture under a key of the caller's, from bytes the caller reads. */
    suspend fun picture(key: String, pixels: Int, read: suspend () -> ByteArray?): Bitmap? {
        images.get(key)?.takeIf { it.pixels >= pixels }?.let { return it.bitmap }
        val job = "$key@$pixels"
        loading[job]?.let { return it.await() }
        val task = scope.async(Dispatchers.IO) { read()?.let { decode(it, pixels) } }
        loading[job] = task
        val image = task.await()
        loading.remove(job)
        store(image, key, pixels)
        return image
    }

    // Stickers

    /** A sticker's description, from its pack read once. */
    suspend fun sticker(ref: StickerRef): Sticker? = pack(ref.packId)[ref.stickerId]

    suspend fun pack(packId: String): Map<String, Sticker> {
        packs[packId]?.let { return it.await() }
        val task = scope.async {
            val contents = runCatching { client.stickerPack(packId) }.getOrNull() ?: return@async emptyMap()
            contents.stickers.associateBy { it.id }
        }
        packs[packId] = task
        val stickers = task.await()
        if (stickers.isEmpty()) packs.remove(packId)
        return stickers
    }

    /** Tells the cache what a pack holds, read elsewhere (the picker). */
    fun remember(contents: StickerPackContents) {
        val stickers = contents.stickers.associateBy { it.id }
        packs[contents.pack.id] = scope.async { stickers }
    }

    /**
     * A sticker's file, as served, or its Lottie JSON for a `tgs` one.
     * Kept on disk by digest: a sticker's file never changes under it.
     */
    suspend fun stickerFile(sticker: Sticker): ByteArray? {
        val lottie = sticker.format == StickerFormat.Tgs
        val name = sticker.sha256 + if (lottie) ".json" else ""
        stickerFiles[name]?.let { return it.await() }
        val directory = stickerDirectory
        val task = scope.async(Dispatchers.IO) {
            val file = directory?.let { File(it, name) }
            file?.takeIf { it.isFile }?.let { kept -> runCatching { kept.readBytes() }.getOrNull()?.let { return@async it } }
            val data = runCatching { client.stickerFile(sticker.ref, lottie) }.getOrNull() ?: return@async null
            if (file != null) runCatching {
                directory.mkdirs()
                val temporary = File(directory, "$name.tmp")
                temporary.writeBytes(data)
                temporary.renameTo(file)
            }
            data
        }
        stickerFiles[name] = task
        val data = task.await()
        if (data == null) stickerFiles.remove(name)
        return data
    }

    /** A still sticker's picture (a `webp`), at no more than [pixels]. */
    suspend fun stickerImage(sticker: Sticker, pixels: Int): Bitmap? {
        if (sticker.format != StickerFormat.Webp) return null
        return picture("sticker/${sticker.sha256}", pixels) { stickerFile(sticker) }
    }

    companion object {
        fun key(storyId: String, frameId: String) = "$storyId/$frameId"

        /** A picture whose longer side is at most [maxPixels], as ImageIO's thumbnails are read. */
        fun decode(data: ByteArray, maxPixels: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            val longer = max(bounds.outWidth, bounds.outHeight)
            if (longer <= 0) return null
            var sample = 1
            while (longer / (sample * 2) >= maxPixels) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val side = max(decoded.width, decoded.height)
            if (side <= maxPixels) return decoded
            val scale = maxPixels.toDouble() / side
            return Bitmap.createScaledBitmap(decoded, max(1, (decoded.width * scale).roundToInt()), max(1, (decoded.height * scale).roundToInt()), true)
        }

        private fun poster(stream: StoriesByteStream, pixels: Int): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(stream.copy())
                val frame = retriever.getFrameAtTime(0) ?: return null
                val side = max(frame.width, frame.height)
                if (side <= pixels) frame
                else Bitmap.createScaledBitmap(frame, max(1, frame.width * pixels / side), max(1, frame.height * pixels / side), true)
            } catch (_: RuntimeException) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}

/**
 * A recording's bytes for the platform's players, which can't send the
 * computer's token themselves (happy-ios `StoriesByteStream`): every read
 * is answered with `Range` requests over the paired connection, half a
 * megabyte at a time. Copies share the length and the chunks read.
 */
class StoriesByteStream private constructor(
    private val fetch: suspend (IntRange?) -> DirectHttpResponse,
    private val shared: Shared,
) : MediaDataSource() {
    constructor(fetch: suspend (IntRange?) -> DirectHttpResponse) : this(fetch, Shared())

    private class Shared {
        var total: Long? = null
        val chunks = object : LruCache<Long, ByteArray>(16) {}
    }

    /** Another source over the same bytes: a player closes the source it is given. */
    fun copy() = StoriesByteStream(fetch, shared)

    override fun getSize(): Long = synchronized(shared) {
        shared.total?.let { return it }
        val response = runCatching { runBlocking(Dispatchers.IO) { fetch(0..1) } }.getOrNull() ?: return -1
        val range = response.headers.entries.firstOrNull { it.key.equals("content-range", ignoreCase = true) }?.value
        val size = range?.substringAfterLast('/')?.toLongOrNull() ?: if (response.status == 200) response.body.size.toLong() else null
        shared.total = size
        size ?: -1
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val total = getSize()
        if (total < 0) throw IOException("no length")
        if (position >= total) return -1
        val start = position / CHUNK * CHUNK
        val chunk = synchronized(shared) { shared.chunks.get(start) } ?: run {
            val end = min(total, start + CHUNK) - 1
            val response = runCatching { runBlocking(Dispatchers.IO) { fetch(start.toInt()..end.toInt()) } }.getOrNull() ?: throw IOException("read failed")
            val body = if (response.status == 200) response.body.copyOfRange(start.toInt(), min(response.body.size.toLong(), end + 1).toInt()) else response.body
            if (body.isEmpty()) throw IOException("connection lost")
            synchronized(shared) { shared.chunks.put(start, body) }
            body
        }
        val inside = (position - start).toInt()
        val count = min(size, chunk.size - inside)
        if (count <= 0) return -1
        System.arraycopy(chunk, inside, buffer, offset, count)
        return count
    }

    override fun close() {}

    companion object {
        const val CHUNK = 512 * 1024L
    }
}
