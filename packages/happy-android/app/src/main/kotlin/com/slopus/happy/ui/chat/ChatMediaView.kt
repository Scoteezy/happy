package com.slopus.happy.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.stemcells.StemCellMediaFlow
import com.slopus.happy.layout.surfacePressed
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.viewScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max

/**
 * A conversation's pictures and recordings by [ChatMedia.key] (happy-ios
 * `ChatMediaSource`), decoded off the main thread at no more than `pixels`
 * on their longer side. A recording's picture is its first frame.
 *
 * The screen showing a conversation puts one on its view under
 * `R.id.chat_media`; a [ChatMediaView] finds it up its parents.
 */
interface ChatMediaSource {
    fun cachedImage(key: String, pixels: Int): Bitmap?
    suspend fun image(key: String, pixels: Int): Bitmap?
    suspend fun placeholder(thumbhash: String): Bitmap?
    /** Bumped when pictures that failed should be asked for again (the computer is back). */
    val retries: Int

    companion object {
        fun of(view: View): ChatMediaSource? {
            var current: View? = view
            while (current != null) {
                (current.getTag(R.id.chat_media) as? ChatMediaSource)?.let { return it }
                current = current.parent as? View
            }
            return null
        }
    }
}

/**
 * Desktop's MediaImageTile and MediaVideoTile (happy-ios `ChatMediaView`):
 * the thumbhash on `surface-pressed`, the picture faded in over it (160 ms),
 * a hairline over the whole tile and corners rounded 8. A recording shows
 * its first frame under a play button. A picture that failed keeps its
 * placeholder under a quiet glyph and is asked for again when the source
 * says the computer is back.
 */
class ChatMediaView(context: Context) : ViewGroup(context), ThemeAware {
    private val placeholder = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val picture = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val outline = View(context)
    private val play = View(context)
    private val playIcon = IconView(context, Icon.ionicons("play"), PLAY_ICON, StemCellsPalette.stemCellsPlayGlyph)
    private val failure = IconView(context, Icon.ionicons("image-outline"), PLAY_ICON - 2, StemCellsPalette.stemCellsMediaFailed)
    var media: ChatMedia? = null
        private set
    private var loadedPixels = 0
    private var failedAt = -1
    private var job: Job? = null
    private var placeholderJob: Job? = null

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(RADIUS))
            }
        }
        clipToOutline = true
        tag = "stem-media"
        addView(placeholder)
        addView(picture)
        addView(play)
        addView(playIcon)
        failure.visibility = GONE
        addView(failure)
        addView(outline)
        applyTheme()
    }

    /** Null lets go of the picture. */
    fun apply(media: ChatMedia?) {
        if (media == this.media) return
        job?.cancel()
        job = null
        placeholderJob?.cancel()
        placeholderJob = null
        this.media = media
        loadedPixels = 0
        setFailed(false)
        placeholder.setImageDrawable(null)
        picture.setImageDrawable(null)
        picture.animate().cancel()
        picture.alpha = 1f
        val video = media?.isVideo == true
        play.visibility = if (video) VISIBLE else GONE
        playIcon.visibility = play.visibility
        contentDescription = media?.label
        stateDescription = context.getString(R.string.stem_media_loading)
        requestLayout()
        loadIfNeeded()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadIfNeeded()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val bounds = Rect(0.0, 0.0, px(width.toFloat()), px(height.toFloat()))
        placeholder.setFrame(bounds)
        picture.setFrame(bounds)
        outline.setFrame(bounds)
        val button = StemCellMediaFlow.playButton(Size(bounds.width, bounds.height))
        play.setFrame(button)
        playIcon.setFrame(button)
        failure.setFrame(button)
        loadIfNeeded()
    }

    override fun applyTheme() {
        setBackgroundColor(Theme.color(Palette.surfacePressed))
        outline.background = GradientDrawable().apply {
            cornerRadius = dp(RADIUS)
            setStroke(dp(1), Theme.color(StemCellsPalette.stemCellsDivider))
        }
        play.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Theme.color(StemCellsPalette.stemCellsPlayFill))
        }
        playIcon.applyTheme()
        failure.applyTheme()
    }

    private val pixels: Int get() = ceil(max(width, height).toDouble()).toInt()

    private fun loadIfNeeded() {
        val media = media ?: return
        if (!isAttachedToWindow || width <= 0) return
        val source = ChatMediaSource.of(this) ?: return
        // Failed: asked again only once the source says the computer is back.
        if (failedAt >= 0 && failedAt == source.retries) return
        if (failedAt >= 0) setFailed(false)
        val thumbhash = media.thumbhash
        if (placeholderJob == null && placeholder.drawable == null && picture.drawable == null && thumbhash != null) {
            placeholderJob = viewScope.launch {
                val image = source.placeholder(thumbhash)
                if (this@ChatMediaView.media == media && picture.drawable == null && image != null) placeholder.setImageBitmap(image)
            }
        }
        val pixels = pixels
        if (job != null || pixels <= loadedPixels) return
        source.cachedImage(media.key, pixels)?.let {
            show(it, pixels, fade = false)
            return
        }
        val retries = source.retries
        job = viewScope.launch {
            val image = source.image(media.key, pixels)
            if (this@ChatMediaView.media != media) return@launch
            job = null
            if (image == null) {
                loadedPixels = pixels
                if (picture.drawable == null) {
                    setFailed(true)
                    failedAt = retries
                }
                return@launch
            }
            show(image, pixels, fade = true)
        }
    }

    /** The source's retries moved on: a picture that failed is asked for again. */
    fun retry() {
        if (failedAt < 0) return
        loadedPixels = 0
        loadIfNeeded()
    }

    private fun setFailed(value: Boolean) {
        if (!value) failedAt = -1
        failure.visibility = if (value && media?.isVideo != true) VISIBLE else GONE
        stateDescription = context.getString(
            when {
                value -> R.string.stem_media_failed
                picture.drawable == null -> R.string.stem_media_loading
                else -> R.string.stem_media_loaded
            },
        )
    }

    private fun show(image: Bitmap, pixels: Int, fade: Boolean) {
        setFailed(false)
        val first = picture.drawable == null
        loadedPixels = pixels
        picture.setImageBitmap(image)
        stateDescription = context.getString(R.string.stem_media_loaded)
        if (!first || !fade) {
            placeholder.setImageDrawable(null)
            return
        }
        picture.alpha = 0f
        picture.animate().alpha(1f).setDuration(FADE_MS).withEndAction {
            if (picture.drawable != null) placeholder.setImageDrawable(null)
        }.start()
    }

    companion object {
        const val RADIUS = 8.0
        const val FADE_MS = 160L
        const val PLAY_ICON = 22.0
    }
}
