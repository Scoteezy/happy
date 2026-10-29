package com.slopus.happy.ui.stemcells

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.core.content.FileProvider
import com.slopus.happy.R
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Rect
import com.slopus.happy.stemcells.StemCellMedia
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * How a conversation and its detail open what was shared (happy-ios: the
 * picture viewer, AVPlayerViewController, Quick Look): a picture in the
 * viewer, paging through every picture given; a recording in the player;
 * a file in an app for it. The viewer and the player cover the host
 * through [cover], and [uncovered] says when they have gone.
 */
class StemCellMediaOpener(
    private val context: Context,
    private val loader: StemCellMediaLoader,
    private val scope: CoroutineScope,
    private val cover: (View) -> Unit,
    private val uncovered: () -> Unit,
) {
    /** The picture viewer or the player while one is up. */
    var shown: View? = null
        private set

    var safeArea = LayoutInsets.Zero
        set(value) {
            field = value
            (shown as? StemCellPictureViewer)?.safeArea = value
            (shown as? StemCellVideoPlayer)?.safeArea = value
        }

    /** What the harness reads: the picture in view, the player, or nothing. */
    val probe: String
        get() = (shown as? StemCellPictureViewer)?.probe ?: if (shown is StemCellVideoPlayer) "video" else "none"

    /** `key` as what it is; a picture pages through `pictures`, or itself alone when they don't hold it. */
    fun open(key: String, pictures: List<StemCellPicture>) {
        if (shown != null) return
        val file = loader.media(key)
        if (file != null && !file.isVisual) return openFile(file)
        if (file != null && file.isVideo) return playVideo(file)
        val all = if (pictures.any { it.key == key }) pictures
            else listOf(StemCellPicture(key, file?.item?.thumbhash, file?.item?.name ?: context.getString(R.string.stem_picture)))
        val viewer = StemCellPictureViewer(context, loader, all, all.indexOfFirst { it.key == key }.coerceAtLeast(0), scope)
        viewer.safeArea = safeArea
        show(viewer)
        viewer.onClose = { gone(viewer) }
        viewer.present()
    }

    /** Back closes what is up; false when nothing is. */
    fun dismiss(): Boolean = when (val view = shown) {
        is StemCellPictureViewer -> true.also { view.dismiss() }
        is StemCellVideoPlayer -> true.also { view.dismiss() }
        else -> false
    }

    fun place(frame: Rect) {
        shown?.setFrame(frame)
    }

    private fun show(view: View) {
        shown = view
        cover(view)
    }

    private fun gone(view: View) {
        if (shown === view) shown = null
        uncovered()
    }

    /** The bytes of what was shared, kept where another app or the player may read them. */
    private suspend fun keep(file: StemCellMedia): File? {
        val data = loader.data(file) ?: return null
        return withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, "media").apply { mkdirs() }
            val name = file.item.name.replace('/', '_').ifEmpty { file.key.replace('/', '_') }
            File(folder, name).apply { writeBytes(data) }
        }
    }

    private fun openFile(file: StemCellMedia) {
        scope.launch {
            val kept = keep(file) ?: return@launch unavailable()
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", kept)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, file.item.mimeType.ifEmpty { "application/octet-stream" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(intent, file.item.name)) }.onFailure { unavailable() }
        }
    }

    private fun playVideo(file: StemCellMedia) {
        scope.launch {
            val kept = keep(file) ?: return@launch unavailable()
            if (shown != null) return@launch
            val player = StemCellVideoPlayer(context, kept)
            player.safeArea = safeArea
            show(player)
            player.onClose = { gone(player) }
            player.present()
        }
    }

    private fun unavailable() = Alerts.show(context, context.getString(R.string.stem_file_unavailable))
}
