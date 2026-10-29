package com.slopus.happy.ui.stories

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.exifinterface.media.ExifInterface
import com.slopus.happy.R
import com.slopus.happy.direct.ClientId
import com.slopus.happy.stories.StoryDraft
import com.slopus.happy.stories.StoryFrame
import com.slopus.happy.ui.components.Alerts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A story's frames from the photo library (happy-ios `StoriesMediaChooser`,
 * happy-agent `API.md` "Making a story by hand"), through the system photo
 * picker: photos and videos only, picked in order. Each is readied for its
 * upload here: a HEIC photo becomes a JPEG, and each is measured and given a
 * background from its edges.
 */
object StoriesMediaChooser {
    class Picked(val frame: StoryDraft.Frame, val local: StoryLocalMedia?)

    const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
    const val MAX_VIDEO_BYTES = 256 * 1024 * 1024

    /** Opens the picker; [done] gets what was picked, readied, and isn't called when nothing was. */
    fun choose(context: Context, limit: Int, done: (List<Picked>) -> Unit) {
        if (limit <= 0) return
        val activity = activity(context) ?: return
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        val handle: (List<Uri>) -> Unit = { uris ->
            if (uris.isNotEmpty()) {
                MainScope().launch {
                    val picked = uris.mapNotNull { ready(context, it) }
                    if (picked.isEmpty()) Alerts.show(Alerts.dark(context), context.getString(R.string.stories_couldnt_open), null) else done(picked)
                }
            }
        }
        // Registered for this one pick, as the limit moves with the frames.
        var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null
        launcher = if (limit == 1) {
            activity.activityResultRegistry.register("stories-frame", ActivityResultContracts.PickVisualMedia()) { uri ->
                launcher?.unregister()
                handle(listOfNotNull(uri))
            }
        } else {
            activity.activityResultRegistry.register("stories-frames", ActivityResultContracts.PickMultipleVisualMedia(limit)) { uris ->
                launcher?.unregister()
                handle(uris)
            }
        }
        launcher.launch(request)
    }

    /** One pick as a frame: an image kept or drawn into a JPEG, a video as it is. */
    suspend fun ready(context: Context, uri: Uri): Picked? = try {
        val type = context.contentResolver.getType(uri) ?: ""
        when {
            type.startsWith("video/") -> video(context, uri, type)
            else -> withContext(Dispatchers.IO) {
                val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
                image(context, data, type)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    fun uploads(context: Context): File = File(context.cacheDir, "StoriesUploads").also { it.mkdirs() }

    /**
     * A photo readied for its upload: kept as it is when the daemon reads
     * it, otherwise (HEIC, or anything else) drawn upright into a JPEG.
     * Off the main thread.
     */
    fun image(context: Context, data: ByteArray, type: String): Picked? {
        val kept = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        // A preview no larger than 2048 on its long edge, upright.
        val (preview, upright) = decode(data, 2048)
        var width = upright.width
        var height = upright.height
        var bytes = data
        var ext = when (type) { "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "jpg" }
        if (type !in kept || data.size > MAX_IMAGE_BYTES) {
            val fit = min(1.0, 4096.0 / max(1, max(width, height)))
            width = (width * fit).roundToInt()
            height = (height * fit).roundToInt()
            val full = decode(data, 4096).first
            val out = ByteArrayOutputStream()
            full.compress(Bitmap.CompressFormat.JPEG, 90, out)
            full.recycle()
            bytes = out.toByteArray()
            if (bytes.size > MAX_IMAGE_BYTES) return null
            ext = "jpg"
        }
        val id = ClientId.make()
        val file = File(uploads(context), "$id.$ext")
        file.writeBytes(bytes)
        val frame = StoryDraft.Frame(kind = StoryFrame.Kind.Image, width = width, height = height, media = StoryDraft.Media.Local(file, "$id.$ext"), background = edges(preview))
        return Picked(frame, StoryLocalMedia.Image(preview))
    }

    /**
     * The picture upright, no larger than [longest] on its long edge, and
     * its upright size in full. ImageDecoder turns it by its orientation;
     * before Android 9 the EXIF orientation is applied here.
     */
    private fun decode(data: ByteArray, longest: Int): Pair<Bitmap, android.util.Size> {
        if (Build.VERSION.SDK_INT >= 28) {
            var natural = android.util.Size(0, 0)
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(data))) { decoder, info, _ ->
                natural = info.size
                val edge = max(info.size.width, info.size.height)
                if (edge > longest) {
                    val fit = longest.toDouble() / edge
                    decoder.setTargetSize(max(1, (info.size.width * fit).roundToInt()), max(1, (info.size.height * fit).roundToInt()))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val turned = (bitmap.width > bitmap.height) != (natural.width > natural.height) && natural.width != natural.height
            return bitmap to if (turned) android.util.Size(natural.height, natural.width) else natural
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val rotation = runCatching { ExifInterface(ByteArrayInputStream(data)).rotationDegrees }.getOrDefault(0)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("undecodable picture")
        val edge = max(bitmap.width, bitmap.height)
        if (edge > longest || rotation != 0) {
            val fit = min(1f, longest.toFloat() / edge)
            val turn = Matrix().apply { postScale(fit, fit); postRotate(rotation.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, turn, true)
        }
        val sideways = rotation % 180 != 0
        return bitmap to if (sideways) android.util.Size(bounds.outHeight, bounds.outWidth) else android.util.Size(bounds.outWidth, bounds.outHeight)
    }

    /** A recording as it is, measured, with its first frame to show. */
    private suspend fun video(context: Context, uri: Uri, type: String): Picked? = withContext(Dispatchers.IO) {
        val ext = when (type) { "video/quicktime" -> "mov"; "video/webm" -> "webm"; else -> "mp4" }
        val name = "${ClientId.make()}.$ext"
        val file = File(uploads(context), name)
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return@withContext null
        if (file.length() <= 0 || file.length() > MAX_VIDEO_BYTES) {
            file.delete()
            return@withContext null
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (w == null || h == null) {
                file.delete()
                return@withContext null
            }
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val sideways = abs(rotation) % 180 == 90
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.toInt()
            val poster = if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280, 1280)
            } else {
                retriever.getFrameAtTime(0)
            }
            val frame = StoryDraft.Frame(
                kind = StoryFrame.Kind.Video, width = if (sideways) h else w, height = if (sideways) w else h, durationMs = duration,
                media = StoryDraft.Media.Local(file, name), background = listOf("#000000", "#000000"),
            )
            Picked(frame, StoryLocalMedia.Video(file.path, poster))
        } finally {
            retriever.release()
        }
    }

    /** Two colours from the picture's top and bottom edges, as the daemon picks them when a client leaves the background out. */
    fun edges(image: Bitmap): List<String> {
        val column = Bitmap.createScaledBitmap(image, 1, 16, true)
        // getPixel reads them unpremultiplied.
        val top = StoryOverlayView.hex(column.getPixel(0, 0))
        val bottom = StoryOverlayView.hex(column.getPixel(0, 15))
        if (column !== image) column.recycle()
        return listOf(top, bottom)
    }

    private fun activity(context: Context): ComponentActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is ComponentActivity) return current
            current = current.baseContext
        }
        return null
    }
}
