package com.slopus.happy.ui.stemcells

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.stemcells.StemCellChatItem
import com.slopus.happy.stemcells.StemCellMedia
import com.slopus.happy.stemcells.media
import com.slopus.happy.ui.chat.ChatMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * One conversation's pictures and recordings over the paired computer
 * (happy-ios `StemCellMediaLoader`): a tool call's from
 * `GET /v0/agents/:id/tool-calls/:tcid/media/:index`, a photo the person
 * sent from the bytes they sent. Decoded off the main thread at no more
 * than they are drawn, and kept while memory allows. A recording's picture
 * is its first frame. Main thread only.
 */
class StemCellMediaLoader(private val client: DirectAgentClient, agentId: String) : ChatMediaSource {
    /** New work's is the daemon's ID once it has made it. */
    var agentId: String = agentId

    private val tools = HashMap<String, StemCellMedia>()
    private val sent = HashMap<String, ByteArray>()

    private class Decoded(val pixels: Int, val image: Bitmap)

    /** Each key's largest decode, so eviction drops the size with the picture. */
    private val images = object : LruCache<String, Decoded>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Decoded) = value.image.allocationByteCount
    }

    /** A tool's bytes, so a larger decode doesn't fetch them again. */
    private val bytes = object : LruCache<String, ByteArray>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val placeholders = LruCache<String, Bitmap>(64)

    override var retries = 0
        private set

    /** What the conversation shows now, by the keys its plans draw. */
    fun register(items: List<StemCellChatItem>) {
        for (item in items) {
            for (piece in item.pieces) {
                when (piece) {
                    is StemCellChatItem.Piece.Picture -> sent[sentKey(piece.id)] = piece.data
                    is StemCellChatItem.Piece.Gallery -> for (one in piece.media) tools[one.key] = one
                    is StemCellChatItem.Piece.Tree -> for (one in piece.media) tools[one.key] = one
                    is StemCellChatItem.Piece.File -> tools[piece.media.key] = piece.media
                    else -> Unit
                }
            }
        }
    }

    fun media(key: String): StemCellMedia? = tools[key]

    override fun cachedImage(key: String, pixels: Int): Bitmap? = images.get(key)?.takeIf { it.pixels >= pixels }?.image

    override suspend fun image(key: String, pixels: Int): Bitmap? {
        cachedImage(key, pixels)?.let { return it }
        val data = sent[key]
        val media = tools[key]
        val image = when {
            data != null -> withContext(Dispatchers.Default) { StemCellsImages.decode(data, pixels) }
            media != null -> withContext(Dispatchers.IO) { if (media.isVideo) poster(media, pixels) else picture(media, pixels) }
            else -> null
        }
        if (image != null && pixels > (images.get(key)?.pixels ?: 0)) images.put(key, Decoded(pixels, image))
        return image
    }

    override suspend fun placeholder(thumbhash: String): Bitmap? {
        placeholders.get(thumbhash)?.let { return it }
        val image = withContext(Dispatchers.Default) { StemCellsImages.placeholder(thumbhash) } ?: return null
        placeholders.put(thumbhash, image)
        return image
    }

    /** Asks every picture that failed to load to try again, as when the computer is reachable again. */
    fun retryFailed() {
        retries += 1
    }

    /** The whole of a presented item's bytes, read once. */
    suspend fun data(media: StemCellMedia): ByteArray? {
        synchronized(bytes) { bytes.get(media.key) }?.let { return it }
        val response = try {
            client.media(agentId, media.toolCallId, media.index)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (response.status !in 200..299 || response.body.isEmpty()) return null
        synchronized(bytes) { bytes.put(media.key, response.body) }
        return response.body
    }

    private suspend fun picture(media: StemCellMedia, pixels: Int): Bitmap? {
        val data = data(media) ?: return null
        return StemCellsImages.decode(data, pixels)
    }

    /** A recording's first frame. */
    private suspend fun poster(media: StemCellMedia, pixels: Int): Bitmap? {
        val data = data(media) ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(BytesSource(data))
            val frame = retriever.getFrameAtTime(0) ?: return null
            val scale = pixels.toDouble() / max(frame.width, frame.height)
            if (scale >= 1) frame else Bitmap.createScaledBitmap(frame, max(1, (frame.width * scale).toInt()), max(1, (frame.height * scale).toInt()), true)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private class BytesSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val count = minOf(size, data.size - position.toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize() = data.size.toLong()
        override fun close() {}
    }

    companion object {
        fun sentKey(id: String) = "sent/$id"
    }
}
