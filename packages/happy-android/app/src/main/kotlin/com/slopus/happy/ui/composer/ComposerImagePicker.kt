package com.slopus.happy.ui.composer

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.slopus.happy.R
import com.slopus.happy.attachments.AttachmentPreview
import com.slopus.happy.attachments.ComposerAttachmentStaging
import com.slopus.happy.attachments.ImageNormalizer
import com.slopus.happy.attachments.MAX_IMAGES_PER_MESSAGE
import com.slopus.happy.attachments.MAX_IMAGE_FILE_SIZE
import com.slopus.happy.attachments.MAX_NORMALIZED_FILE_SIZE
import com.slopus.happy.attachments.NormalizedImage
import com.slopus.happy.text.plural
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** A picked image waiting in the composer. */
data class ComposerPickedImage(val id: String, val image: NormalizedImage) {
    /** What the send uploads. */
    val preview: AttachmentPreview
        get() = AttachmentPreview(id, Uri.fromFile(image.file).toString(), image.width, image.height, image.mimeType, image.size, image.name)
}

/**
 * useImagePicker.ts on Android (happy-ios `ComposerImagePicker`): the add
 * button asks whether to paste or to pick, each image is converted to a
 * JPEG (long edge at most 3072, quality 0.92), measured, and held in the
 * staging folder until sent. The library is the system photo picker, which
 * needs no storage permission, so the permission alert never shows.
 */
class ComposerImagePicker(private val context: Context, private val scope: CoroutineScope) {
    var images: List<ComposerPickedImage> = emptyList()
        private set(value) {
            if (field == value) return
            field = value
            onChange?.invoke()
        }

    var onChange: (() -> Unit)? = null

    private val staging = ComposerAttachmentStaging(context)

    /** `attachImages`: asks, every time, whether to paste or to pick. */
    fun attach() {
        AlertDialog.Builder(themed())
            .setTitle(R.string.image_upload_attach_title)
            .setItems(arrayOf(context.getString(R.string.image_upload_paste_from_clipboard), context.getString(R.string.image_upload_choose_from_library))) { _, which ->
                if (which == 0) paste() else pickImages()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /**
     * `pasteImages`: the clipboard's image, through the same conversion and
     * limits as a picked one. An empty clipboard is answered rather than
     * passed over.
     */
    fun paste() {
        if (remaining() == 0) return limitAlert()
        val uri = clipboardImage() ?: return Alerts.show(
            context,
            context.getString(R.string.image_upload_nothing_to_paste_title),
            context.getString(R.string.image_upload_nothing_to_paste_message),
        )
        add(listOf(uri), pasted = true)
    }

    /** Pictures that came some other way (the keyboard's image insert, a paste into the text), converted and held as a paste's are. */
    fun receive(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (remaining() == 0) return limitAlert()
        add(uris, pasted = true)
    }

    fun remove(id: String) {
        val image = images.firstOrNull { it.id == id } ?: return
        staging.remove(image.image.file)
        images = images - image
    }

    /** Sent: the upload has read them. */
    fun removeSent(ids: List<String>) = ids.forEach(::remove)

    fun clear() {
        images.forEach { staging.remove(it.image.file) }
        images = emptyList()
    }

    /** The composer is going: the folder goes with it. */
    fun close() {
        onChange = null
        images = emptyList()
        staging.close()
    }

    // Library

    private fun pickImages() {
        val remaining = remaining()
        if (remaining == 0) return limitAlert()
        val activity = activity() ?: return
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        // Registered for this one pick, as the limit moves with the selection.
        var launcher: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>? = null
        if (remaining == 1) {
            launcher = activity.activityResultRegistry.register("composer-photo", ActivityResultContracts.PickVisualMedia()) { uri ->
                launcher?.unregister()
                add(listOfNotNull(uri), pasted = false)
            }
        } else {
            launcher = activity.activityResultRegistry.register("composer-photos", ActivityResultContracts.PickMultipleVisualMedia(remaining)) { uris ->
                launcher?.unregister()
                add(uris, pasted = false)
            }
        }
        launcher.launch(request)
    }

    private fun activity(): ComponentActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is ComponentActivity) return current
            current = current.baseContext
        }
        return null
    }

    // Clipboard

    private fun clipboardImage(): Uri? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = clipboard.primaryClip ?: return null
        for (index in 0 until clip.itemCount) {
            val uri = clip.getItemAt(index).uri ?: continue
            val type = context.contentResolver.getType(uri) ?: clip.description.takeIf { it.hasMimeType("image/*") }?.getMimeType(0)
            if (type?.startsWith("image/") == true) return uri
        }
        return null
    }

    // Admission

    /** Converts each off the main thread, one after another so they wait in the order they came, and holds it. */
    private fun add(uris: List<Uri>, pasted: Boolean) {
        if (uris.isEmpty()) return
        scope.launch {
            // The limit may have moved while picking.
            for (uri in uris.take(remaining())) {
                val now = System.currentTimeMillis()
                val name = if (pasted) "pasted_$now.png" else displayName(uri)
                try {
                    val image = withContext(Dispatchers.IO) {
                        ImageNormalizer.normalize({ context.contentResolver.openInputStream(uri) ?: throw IOException("Unreadable") }, name, staging.folder(), now)
                    }
                    admit(image, name)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "image conversion failed: $e")
                    alert(context.getString(R.string.image_upload_upload_failed_title), context.resources.plural(R.plurals.image_upload_upload_failed_message, 1, 1))
                }
            }
        }
    }

    /** `previewForAsset`'s size check, then onto the end of the list. */
    private fun admit(image: NormalizedImage, name: String?) {
        if (image.size > MAX_NORMALIZED_FILE_SIZE) {
            staging.remove(image.file)
            alert(
                context.getString(R.string.image_upload_file_too_large_title),
                context.getString(R.string.image_upload_file_too_large_message, name ?: "image", MAX_IMAGE_FILE_SIZE / 1024 / 1024),
            )
            return
        }
        if (images.size >= MAX_IMAGES_PER_MESSAGE) {
            staging.remove(image.file)
            return
        }
        images = images + ComposerPickedImage(previewId(), image)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun remaining() = maxOf(0, MAX_IMAGES_PER_MESSAGE - images.size)

    // Alerts

    private fun limitAlert() = alert(
        context.getString(R.string.image_upload_limit_title),
        context.getString(R.string.image_upload_limit_message, MAX_IMAGES_PER_MESSAGE),
    )

    private fun alert(title: String, message: String) = Alerts.show(context, title, message)

    private fun themed(): Context = ContextThemeWrapper(
        context,
        if (Theme.isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
    )

    private companion object {
        const val TAG = "ComposerImagePicker"
        private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz"

        /** `${Date.now()}_${Math.random().toString(36).slice(2)}`. */
        fun previewId(): String = "${System.currentTimeMillis()}_" + (1..11).map { DIGITS[(Math.random() * DIGITS.length).toInt()] }.joinToString("")
    }
}
