package com.slopus.happy.ui.stemcells

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.stemcells.StemCellPictureViewerLayout
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.setFrame
import java.io.File
import kotlin.math.min

/**
 * A recording a conversation presented, playing over it (happy-ios plays
 * it in the system's player): the platform's `VideoView` fitted on black
 * with its transport controls, the close button where the picture viewer
 * has it; a tap shows the controls, back or the button closes it.
 */
class StemCellVideoPlayer(context: Context, file: File) : ManualLayout(context) {
    var onClose: (() -> Unit)? = null
    var safeArea = LayoutInsets.Zero
        set(value) { if (field != value) { field = value; requestLayout() } }

    private val video = VideoView(context)
    private val close = IconView(context, Icon.ionicons("close"), 26.0, StemCellsPalette.stemCellsViewerControl)
    private val controls = MediaController(context)
    private var natural = Size.Zero
    private var closing = false

    init {
        tag = "stem-video-player"
        setBackgroundColor(Theme.color(StemCellsPalette.stemCellsViewerGround))
        isClickable = true
        addView(video)
        controls.setAnchorView(video)
        video.setMediaController(controls)
        video.setOnPreparedListener { player: MediaPlayer ->
            natural = Size(player.videoWidth.toDouble(), player.videoHeight.toDouble())
            requestLayout()
            video.start()
            controls.show(2500)
        }
        video.setVideoURI(Uri.fromFile(file))
        close.contentDescription = context.getString(R.string.stem_close)
        close.isClickable = true
        close.isFocusable = true
        close.setOnClickListener { dismiss() }
        addView(close)
        alpha = 0f
    }

    fun present() {
        animate().alpha(1f).setDuration(200).start()
    }

    // The status bar goes while it is up, as iOS's viewer hides it.
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        androidx.core.view.ViewCompat.getWindowInsetsController(this)?.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
    }

    override fun onDetachedFromWindow() {
        androidx.core.view.ViewCompat.getWindowInsetsController(this)?.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        super.onDetachedFromWindow()
    }

    fun dismiss() {
        if (closing) return
        closing = true
        video.stopPlayback()
        controls.hide()
        animate().alpha(0f).setDuration(200).withEndAction {
            (parent as? android.view.ViewGroup)?.removeView(this)
            onClose?.invoke()
            onClose = null
        }.start()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = StemCellPictureViewerLayout(Size(width, height), safeArea, Size.Zero)
        // Fitted inside the safe area, as large as it goes: a recording is watched whole.
        val area = layout.picture
        val frame = if (natural.width > 0 && natural.height > 0) {
            val scale = min(area.width / natural.width, area.height / natural.height)
            val w = natural.width * scale
            val h = natural.height * scale
            Rect(area.midX - w / 2, area.midY - h / 2, w, h)
        } else {
            area
        }
        video.setFrame(frame)
        close.setFrame(Rect(layout.close.minX + 9, layout.close.minY + 9, 26.0, 26.0))
    }
}
