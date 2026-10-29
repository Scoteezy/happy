package com.slopus.happy.ui.stories

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.slopus.happy.R
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.stories.StoryCanvasLayout
import com.slopus.happy.stories.StoryAsset
import com.slopus.happy.stories.StoryCanvas
import com.slopus.happy.stories.StoryFrame
import com.slopus.happy.stories.StoryOverlay
import com.slopus.happy.stories.StoryReactionCount
import com.slopus.happy.stories.StoryStatus
import com.slopus.happy.stories.Story
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.density
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.sizeDp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One frame drawn on the story canvas at any size (happy-ios
 * `StoryCanvasView`): the background's gradient, the media placed by its
 * transform, the overlays from their data, and the caption under its fade.
 * The viewer, the editor and the inline story all draw a frame with it.
 */
class StoryCanvasView(context: Context) : ManualLayout(context) {
    /** The frame's picture is on screen (or it has none to wait for). */
    var onReady: (() -> Unit)? = null
    /** A recording reached its end. */
    var onVideoEnd: (() -> Unit)? = null
    /** Whether the recording shown has sound, once that is known. */
    var onAudioKnown: ((Boolean) -> Unit)? = null
    var hasAudio: Boolean? = null
        private set
    var isMuted = false
        set(value) { field = value; player?.muted = value }

    /**
     * The caption opened in full or folded again. Set, a caption too long
     * for its lines folds with "… Show more" and opens on a tap, as
     * Telegram's do; unset (the editor, the inline story) it shows whole.
     */
    var onCaptionExpanded: ((Boolean) -> Unit)? = null
        set(value) {
            val changed = (value == null) != (field == null)
            field = value
            if (changed) applyCaption()
        }

    private var media: StoriesMedia? = null
    private val backgroundPaint = Paint()
    private var backgroundColors: IntArray = intArrayOf(Color.BLACK, Color.BLACK)
    private var backgroundShaderHeight = -1
    private val mediaView = StoryImageView(context)
    private val playerView = StoryPlayerView(context)
    private val overlayLayer = OverlayLayer(context)
    private val captionFade = GradientView(context, intArrayOf(0x00000000, 0x8C000000.toInt()), null)
    private val captionLabel = StoryTextView(context)
    /** Over a folded caption: a tap opens it. */
    private val captionMore = View(context)
    /** The open caption over the darkened frame; a tap anywhere folds it. */
    private val captionOpen = CaptionPanel(context)
    /** Telegram's shadow behind an open caption: clear above, 0.8 at the foot. */
    private val captionDim = GradientView(context, intArrayOf(0x00000000, 0x40000000, 0x8C000000.toInt(), 0xCC000000.toInt()), floatArrayOf(0f, 0.3f, 0.6f, 1f))
    private val captionScroll = ScrollView(context)
    private val captionFull = StoryTextView(context)
    var captionExpanded = false
        private set
    private val unavailable = StoryTextView(context)
    private val overlayViews = HashMap<String, StoryOverlayView>()
    private var overlayOrder: List<String> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loading: Job? = null

    var storyId: String? = null
        private set
    var shownFrame: StoryFrame? = null
        private set
    private var caption: String? = null
    private var reactions: List<StoryReactionCount> = emptyList()
    private var myReaction: String? = null
    private var showsCounts = false
    private var folded: CharSequence? = null

    /** Whether the picture (or a recording's first frame) has been drawn. */
    var isReady = false
        private set
    var player: StoryPlayer? = null
        private set

    /**
     * Media the editor holds on the device, by frame: drawn from here, not
     * read from the computer, which doesn't have it yet.
     */
    var local: Map<String, StoryLocalMedia> = emptyMap()

    /** Room kept under the caption (the inline story's buttons). */
    var captionInset: Double = 16.0
        set(value) { if (field != value) { field = value; requestLayout() } }

    init {
        setWillNotDraw(false)
        clipChildren = true
        addView(mediaView)
        playerView.visibility = GONE
        addView(playerView)
        unavailable.color = 0xCCFFFFFF.toInt()
        unavailable.alignment = Layout.Alignment.ALIGN_CENTER
        unavailable.visibility = GONE
        addView(unavailable)
        addView(overlayLayer)
        addView(captionFade)
        captionLabel.color = Color.WHITE
        captionLabel.contentDescription = null
        addView(captionLabel)
        captionMore.visibility = GONE
        captionMore.isClickable = true
        captionMore.setOnClickListener { setCaptionExpanded(true) }
        captionMore.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(captionMore)
        captionOpen.addView(captionDim)
        captionScroll.isVerticalScrollBarEnabled = false
        captionScroll.overScrollMode = OVER_SCROLL_NEVER
        captionScroll.isFillViewport = false
        captionFull.color = Color.WHITE
        captionScroll.addView(captionFull)
        captionOpen.addView(captionScroll)
        captionOpen.visibility = GONE
        captionOpen.alpha = 0f
        captionOpen.isClickable = true
        captionOpen.setOnClickListener { setCaptionExpanded(false) }
        addView(captionOpen)
    }

    /** Shows [frame] of [story]. The same frame again only takes what changed. */
    fun show(story: Story, frame: StoryFrame, media: StoriesMedia?, playsVideo: Boolean = true) {
        this.media = media
        val sameFrame = storyId == story.id && shownFrame?.id == frame.id
        storyId = story.id
        val previous = shownFrame
        shownFrame = frame
        if (caption != story.caption || !sameFrame) setCaptionExpanded(false, animated = false)
        caption = story.caption
        reactions = story.reactions
        myReaction = story.myReaction
        showsCounts = story.status == StoryStatus.Posted
        backgroundColors = frame.background.mapNotNull { StoryOverlayView.color(it) }.let { if (it.size >= 2) it.toIntArray() else if (it.size == 1) intArrayOf(it[0], it[0]) else intArrayOf(Color.BLACK, Color.BLACK) }
        backgroundShaderHeight = -1
        invalidate()
        if (!sameFrame || previous?.available != frame.available) loadMedia(story.id, frame, playsVideo)
        applyOverlays(frame.overlays)
        applyCaption()
    }

    // Caption

    private fun applyCaption() {
        captionLabel.text = caption ?: ""
        captionFull.text = caption ?: ""
        captionLabel.visibility = if (caption.isNullOrEmpty()) GONE else VISIBLE
        if (captionLabel.visibility == GONE || onCaptionExpanded == null) setCaptionExpanded(false, animated = false)
        requestLayout()
    }

    /**
     * Whether a drag that starts at [x], [y] (px in this view) still closes
     * the viewer with the full caption open: everywhere but where its text
     * scrolls.
     */
    fun passesDrag(x: Float, y: Float): Boolean {
        if (!captionExpanded) return false
        val scrolls = captionFull.height > captionScroll.height + 1
        val inScroll = x >= captionScroll.left && x < captionScroll.right && y >= captionScroll.top && y < captionScroll.bottom
        return !(scrolls && inScroll)
    }

    /** Whether [view] is the open caption's panel or in it. */
    fun ownsCaptionPanel(view: View): Boolean {
        var current: View? = view
        while (current != null && current !== this) {
            if (current === captionOpen) return true
            current = current.parent as? View
        }
        return false
    }

    /** Opens the caption in full over the darkened frame, or folds it. */
    fun setCaptionExpanded(expanded: Boolean, animated: Boolean = true) {
        if (expanded == captionExpanded) return
        if (expanded && (onCaptionExpanded == null || captionMore.visibility != VISIBLE)) return
        captionExpanded = expanded
        if (expanded) {
            captionOpen.visibility = VISIBLE
            captionScroll.post { captionScroll.scrollTo(0, max(0, captionFull.height - captionScroll.height)) }
        } else {
            captionScroll.scrollTo(0, 0)
        }
        captionMore.importantForAccessibility = if (expanded) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
        val open = if (expanded) 1f else 0f
        val label = if (expanded || chromeHidden) 0f else 1f
        val more = if (expanded) 0f else 1f
        val done = { if (!captionExpanded) captionOpen.visibility = GONE }
        if (animated) {
            captionOpen.animate().alpha(open).setDuration(300).withEndAction(done).start()
            captionLabel.animate().alpha(label).setDuration(300).start()
            captionMore.animate().alpha(more).setDuration(300).start()
        } else {
            captionOpen.animate().cancel()
            captionLabel.animate().cancel()
            captionOpen.alpha = open
            captionLabel.alpha = label
            captionMore.alpha = more
            done()
        }
        onCaptionExpanded?.invoke(expanded)
    }

    // Media

    private fun loadMedia(storyId: String, frame: StoryFrame, playsVideo: Boolean) {
        loading?.cancel()
        isReady = false
        stopPlayer()
        mediaView.bitmap = null
        unavailable.visibility = if (frame.available) GONE else VISIBLE
        unavailable.text = context.getString(if (frame.kind == StoryFrame.Kind.Video) R.string.stories_video_unavailable else R.string.stories_photo_unavailable)
        local[frame.id]?.let { local ->
            unavailable.visibility = GONE
            when (local) {
                is StoryLocalMedia.Image -> mediaView.bitmap = local.bitmap
                is StoryLocalMedia.Video -> {
                    mediaView.bitmap = local.poster
                    if (playsVideo) attach(StoryPlayer.File(local.path))
                }
                is StoryLocalMedia.Asset -> {
                    mediaView.bitmap = local.poster
                    val media = media
                    if (frame.kind == StoryFrame.Kind.Video && playsVideo && media != null) attach(StoryPlayer.Source(media.source(local.asset)))
                    if (frame.kind == StoryFrame.Kind.Image && media != null) {
                        val pixels = pixels(sizeDp)
                        loading = scope.launch {
                            val image = media.assetImage(local.asset, pixels) ?: return@launch
                            if (shownFrame?.id == frame.id) mediaView.bitmap = image
                        }
                    }
                }
            }
            markReady()
            return
        }
        val media = media
        if (!frame.available || media == null) {
            markReady()
            return
        }
        val pixels = pixels(sizeDp)
        media.cachedFrame(storyId, frame.id, pixels)?.let { cached ->
            mediaView.bitmap = cached
            if (frame.kind == StoryFrame.Kind.Image) markReady()
        }
        if (frame.kind == StoryFrame.Kind.Video && playsVideo) attach(StoryPlayer.Source(media.source(storyId, frame)))
        if (mediaView.bitmap != null && frame.kind != StoryFrame.Kind.Video) return
        loading = scope.launch {
            media.placeholder(frame.thumbhash)?.let { if (mediaView.bitmap == null) mediaView.bitmap = it }
            val image = media.frame(storyId, frame, pixels)
            if (shownFrame?.id != frame.id) return@launch
            if (image != null) mediaView.bitmap = image
            if (shownFrame?.width == null && image != null) measured = Size(image.width.toDouble(), image.height.toDouble())
            requestLayout()
            markReady()
        }
    }

    private fun attach(source: StoryPlayer.Input) {
        val player = StoryPlayer(source, muted = isMuted)
        this.player = player
        player.onAudioKnown = { audio ->
            if (this.player === player) {
                hasAudio = audio
                onAudioKnown?.invoke(audio)
            }
        }
        player.onEnd = { if (this.player === player) onVideoEnd?.invoke() }
        player.onSize = { width, height -> if (this.player === player && shownFrame?.width == null) { measured = Size(width.toDouble(), height.toDouble()); requestLayout() } }
        playerView.player = player
        playerView.visibility = VISIBLE
    }

    /** A recording measured by its first frame, before the daemon has. */
    private var measured: Size? = null

    private fun markReady() {
        if (isReady) return
        isReady = true
        onReady?.invoke()
    }

    private fun stopPlayer() {
        hasAudio = null
        player?.release()
        player = null
        playerView.player = null
        playerView.visibility = GONE
        measured = null
    }

    fun play() { player?.play() }

    /** A recording starts again from its beginning. */
    fun restart() { player?.restart() }
    fun pause() { player?.pause() }

    /** How far a recording has played, 0…1; null for a picture. */
    val videoProgress: Double?
        get() {
            // One that can't play runs on the frame's own clock instead.
            val player = player ?: return null
            if (player.failed) return null
            val duration = player.durationMs
            if (duration <= 0) return 0.0
            // At most a minute is shown (API.md "Frames").
            val shown = min(duration.toDouble(), StoryCanvas.MAX_VIDEO_DURATION_MS.toDouble())
            return min(1.0, max(0.0, player.positionMs / shown))
        }

    /** Lets go of the recording (the viewer moved on). */
    fun stop() {
        loading?.cancel()
        stopPlayer()
        storyId = null
        shownFrame = null
        mediaView.bitmap = null
        isReady = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        player?.pause()
    }

    /** For good: nothing more loads or plays. */
    fun release() {
        stop()
        scope.cancel()
    }

    // Overlays

    private fun applyOverlays(overlays: List<StoryOverlay>) {
        val live = HashSet<String>()
        val order = ArrayList<String>()
        for (overlay in overlays) {
            val id = overlay.id ?: continue
            live += id
            order += id
            val existing = overlayViews[id]
            if (existing != null && existing.update(overlay)) continue
            existing?.let { overlayLayer.removeView(it) }
            val view = StoryOverlayView.make(context, overlay, media) ?: continue
            overlayViews[id] = view
        }
        val iterator = overlayViews.entries.iterator()
        while (iterator.hasNext()) {
            val (id, view) = iterator.next()
            if (id !in live) {
                overlayLayer.removeView(view)
                iterator.remove()
            }
        }
        overlayOrder = order
        // In their order, the last on top.
        for ((index, id) in order.withIndex()) {
            val view = overlayViews[id] ?: continue
            if (overlayLayer.indexOfChild(view) != index) {
                overlayLayer.removeView(view)
                overlayLayer.addView(view, min(index, overlayLayer.childCount))
            }
        }
        for (view in overlayViews.values) {
            val reaction = view as? StoryReactionOverlayView ?: continue
            reaction.count = if (showsCounts) reactions.firstOrNull { it.emoji == reaction.emoji }?.count else null
            reaction.chosen = myReaction == reaction.emoji
        }
        overlayLayer.requestLayout()
    }

    /**
     * The topmost overlay a tap at [point] (dp, in this view) lands on, of
     * the kinds the viewer answers: links and reaction bubbles.
     */
    fun tappable(point: Point): StoryOverlay? {
        for (id in overlayOrder.reversed()) {
            val view = overlayViews[id] ?: continue
            if (view.overlay !is StoryOverlay.Link && view.overlay !is StoryOverlay.Reaction) continue
            if (contains(view, point, 8.0)) return view.overlay
        }
        return null
    }

    fun overlayView(id: String): StoryOverlayView? = overlayViews[id]

    /** The reaction bubble showing [emoji], to bounce as it's chosen. */
    fun overlayViewMatching(emoji: String): View? {
        val id = shownFrame?.overlays?.firstOrNull { it is StoryOverlay.Reaction && it.emoji == emoji }?.id ?: return null
        return overlayView(id)
    }

    /** The topmost overlay of any kind under [point] (dp, in this view), as the editor's fingers take them. */
    fun overlayView(point: Point, slop: Double = 8.0): StoryOverlayView? {
        for (id in overlayOrder.reversed()) {
            val view = overlayViews[id] ?: continue
            if (view.visibility != VISIBLE) continue
            // The view's own points shrink with its scale only when the canvas scales it.
            val reach = if (view.scalesWithTransform) slop / max(0.05, view.overlay.transform?.scale ?: 1.0) else slop
            if (contains(view, point, reach)) return view
        }
        return null
    }

    /** Whether [point] (dp, in this view) falls in [view]'s unscaled bounds grown by [slop], under its turn and scale. */
    private fun contains(view: StoryOverlayView, point: Point, slop: Double): Boolean {
        val local = local(view, point)
        val size = view.baseBounds
        return local.x >= -slop && local.y >= -slop && local.x <= size.width + slop && local.y <= size.height + slop
    }

    /** [point] (dp, in this view) in [view]'s own unscaled dp. */
    fun local(view: View, point: Point): Point {
        val matrix = android.graphics.Matrix()
        view.matrix.invert(matrix)
        val xy = floatArrayOf(dp(point.x) - view.left, dp(point.y) - view.top)
        matrix.mapPoints(xy)
        return Point(px(xy[0]), px(xy[1]))
    }

    /** Overlays, and the caption, hide while the person holds the frame. */
    var chromeHidden = false
        set(value) {
            if (field == value) return
            field = value
            captionLabel.animate().alpha(if (value || captionExpanded) 0f else 1f).setDuration(200).start()
            captionFade.alpha = if (value) 0f else 1f
        }

    // Drawing and layout

    override fun onDraw(canvas: Canvas) {
        if (backgroundShaderHeight != height) {
            backgroundPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), backgroundColors, null, Shader.TileMode.CLAMP)
            backgroundShaderHeight = height
        }
        canvas.drawColor(Color.BLACK)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val size = Size(width, height)
        shownFrame?.let { frame ->
            val pixels = frame.width?.let { w -> frame.height?.let { h -> Size(w.toDouble(), h.toDouble()) } } ?: measured
                ?: mediaView.bitmap?.let { Size(it.width.toDouble(), it.height.toDouble()) }
            val box = StoryCanvasLayout.media(pixels, frame.placement.x, frame.placement.y, frame.placement.scale, size)
            for (view in listOf(mediaView, playerView)) {
                view.setFrame(box)
                view.pivotX = view.width / 2f
                view.pivotY = view.height / 2f
                view.rotation = Math.toDegrees(frame.placement.rotation).toFloat()
            }
        }
        overlayLayer.setFrame(Rect(0.0, 0.0, width, height))
        for (view in overlayViews.values) view.place(size)
        unavailable.textSize = max(13.0, width / 24)
        unavailable.typeface = medium
        val unavailableHeight = unavailable.measureHeight(width * 0.8)
        unavailable.setFrame(Rect(width * 0.1, (height - unavailableHeight) / 2, width * 0.8, unavailableHeight))
        if (captionLabel.visibility == GONE) {
            captionFade.setFrame(Rect(0.0, 0.0, 0.0, 0.0))
            captionMore.visibility = GONE
        } else {
            val font = max(12.0, min(16.0, width / 24))
            val textWidth = max(0.0, width - 32)
            captionLabel.textSize = font
            captionFull.textSize = font
            // Folded only where something can open it.
            val folded = if (onCaptionExpanded == null) null else folded(caption ?: "", captionLabel.paint, textWidth, CAPTION_LINES)
            this.folded = folded
            captionLabel.text = folded ?: (caption ?: "")
            captionMore.visibility = if (folded == null) GONE else VISIBLE
            // Folded, the control reads the whole caption; the cut label stays quiet.
            captionLabel.importantForAccessibility = if (folded == null) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
            captionLabel.contentDescription = caption
            captionMore.contentDescription = (caption ?: "") + ", " + context.getString(R.string.stories_caption_show_more)
            val textHeight = captionLabel.measureHeight(textWidth)
            val full = if (folded == null) textHeight else captionFull.measureHeight(textWidth)
            val layout = StoryCanvasLayout.caption(
                textHeight = if (folded == null) min(textHeight, height / 3) else textHeight,
                fullHeight = full, canvas = size, bottomInset = captionInset,
            )
            captionLabel.setFrame(layout.text)
            captionFade.setFrame(layout.fade)
            captionMore.setFrame(Rect(layout.text.minX - 8, layout.text.minY - 8, layout.text.width + 16, layout.text.height + 16))
            captionOpen.setFrame(Rect(0.0, 0.0, width, height))
            captionDim.setFrame(layout.shade)
            // The text sits in the scroll's content where the layout puts it.
            captionFull.layoutContent(layout.openText, layout.content)
            captionScroll.setFrame(layout.scroll)
            captionOpen.scrolls = layout.scrolls
            // Resized while open: the offset stays inside what scrolls.
            if (captionExpanded) {
                val most = max(0, dp(layout.content.height - layout.scroll.height).toInt())
                if (!layout.scrolls) captionScroll.scrollTo(0, 0) else if (captionScroll.scrollY > most) captionScroll.scrollTo(0, most)
            }
        }
        // Resized so the caption fits whole: nothing is left to open.
        if (captionExpanded && captionMore.visibility != VISIBLE) post { setCaptionExpanded(false, animated = false) }
    }

    companion object {
        /** Lines a folded caption keeps (Telegram's `maximumNumberOfLines: 3`). */
        const val CAPTION_LINES = 3

        private val medium: Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, 500, false) else Typeface.DEFAULT

        /**
         * The pixels a frame is read at for a canvas of [size] (dp); the
         * viewer's preload asks for the same, so both find one cached picture.
         */
        fun pixels(size: Size): Int = (max(max(size.width, size.height), 400.0) * max(1f, density)).toInt()

        /** The caption cut to [lines] with "… Show more", or null when it fits. */
        fun folded(text: String, paint: TextPaint, widthDp: Double, lines: Int, more: String = SHOW_MORE): CharSequence? {
            if (widthDp <= 0) return null
            val width = max(1, (widthDp * density).toInt())
            val room = ceil(paint.fontSpacing * lines) + 1
            fun height(string: CharSequence) = StaticLayout.Builder.obtain(string, 0, string.length, paint, width).setIncludePad(false).build().height
            if (height(text) <= room) return null
            fun cut(count: Int): CharSequence {
                val kept = text.take(count).trim()
                val result = SpannableStringBuilder(kept + "\u2026 ")
                val start = result.length
                result.append(more)
                result.setSpan(SemiboldSpan, start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                return result
            }
            // The most of the caption that still leaves room for the token.
            var low = 0
            var high = text.length
            while (low < high) {
                val middle = (low + high + 1) / 2
                if (height(cut(middle)) <= room) low = middle else high = middle - 1
            }
            return cut(low)
        }

        /** "Show more", read once from the resources (English only). */
        var SHOW_MORE = "Show more"
            internal set

        private object SemiboldSpan : android.text.style.MetricAffectingSpan() {
            private val face: Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, 600, false) else Typeface.DEFAULT_BOLD
            override fun updateDrawState(tp: TextPaint) { tp.typeface = face }
            override fun updateMeasureState(tp: TextPaint) { tp.typeface = face }
        }
    }

    init {
        SHOW_MORE = context.getString(R.string.stories_caption_show_more)
    }

    /** Holds the overlays where the canvas puts them; nothing else lays them out. */
    private class OverlayLayer(context: Context) : ViewGroup(context) {
        init {
            clipChildren = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        // The canvas places every overlay itself.
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}

        override fun onTouchEvent(event: MotionEvent?) = false
    }

    /** The open caption's panel: taps fold it, drags on its text scroll it. */
    private inner class CaptionPanel(context: Context) : ManualLayout(context) {
        var scrolls = false

        override fun layoutSubviews(width: Double, height: Double) {}
    }
}

/** A frame's media while it is only on the device. */
sealed class StoryLocalMedia {
    data class Image(val bitmap: Bitmap) : StoryLocalMedia() {
        override val poster: Bitmap get() = bitmap
    }
    data class Video(val path: String, override val poster: Bitmap?) : StoryLocalMedia()
    /** A conversation's item, read from there until a save places it. */
    data class Asset(val asset: StoryAsset, override val poster: Bitmap?) : StoryLocalMedia()

    abstract val poster: Bitmap?
}

/** A picture stretched to its bounds (UIImageView's scaleToFill). */
class StoryImageView(context: Context) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    var bitmap: Bitmap? = null
        set(value) { if (field !== value) { field = value; invalidate() } }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val image = bitmap ?: return
        canvas.drawBitmap(image, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
    }
}

/** A vertical gradient filling its bounds. */
class GradientView(context: Context, private val colors: IntArray, private val stops: FloatArray?) : View(context) {
    private val paint = Paint()
    private var shaderHeight = -1

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        if (height <= 0) return
        if (shaderHeight != height) {
            paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), colors, stops, Shader.TileMode.CLAMP)
            shaderHeight = height
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

/**
 * Text in the system font at a size in dp, wrapped at its width (UILabel
 * with any number of lines). [measureHeight] answers the height for a width.
 */
class StoryTextView(context: Context) : View(context) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var layout: StaticLayout? = null
    private var contentFrame: Rect? = null

    var text: CharSequence = ""
        set(value) {
            if (field.toString() == value.toString() && field::class == value::class) return
            field = value
            if (importantForAccessibility != IMPORTANT_FOR_ACCESSIBILITY_NO) contentDescription = value
            changed()
        }

    /** In dp, not following the person's text size, as iOS's fixed point sizes. */
    var textSize: Double = 16.0
        set(value) { if (field != value) { field = value; paint.textSize = (value * density).toFloat(); changed() } }

    var typeface: Typeface?
        get() = paint.typeface
        set(value) { if (paint.typeface != value) { paint.typeface = value; changed() } }

    var color: Int
        get() = paint.color
        set(value) { paint.color = value; invalidate() }

    var alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
        set(value) { if (field != value) { field = value; changed() } }

    init {
        paint.textSize = (textSize * density).toFloat()
    }

    private fun changed() {
        layout = null
        requestLayout()
        invalidate()
    }

    private fun build(width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width)).setAlignment(alignment).setIncludePad(false).build()

    fun measureHeight(widthDp: Double): Double {
        if (text.isEmpty()) return 0.0
        return ceil(build((widthDp * density).toInt()).height / density.toDouble())
    }

    /** Inside a scroll view: its frame in the content, and the content's size, in dp. */
    fun layoutContent(frame: Rect, content: Size) {
        contentFrame = frame
        contentSize = content
        val w = dp(content.width).toInt()
        val h = dp(content.height).toInt()
        measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        layout(0, 0, w, h)
        invalidate()
    }

    private var contentSize: Size? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val content = contentSize ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // In its scroll view, as tall as the content it is given.
        setMeasuredDimension(dp(content.width).toInt(), dp(content.height).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val frame = contentFrame
        val left = frame?.let { dp(it.minX) } ?: 0f
        val top = frame?.let { dp(it.minY) } ?: 0f
        val width = frame?.let { dp(it.width).toInt() } ?: width
        val shown = layout?.takeIf { it.width == max(1, width) } ?: build(width).also { layout = it }
        canvas.save()
        canvas.translate(left, top)
        shown.draw(canvas)
        canvas.restore()
    }
}

/**
 * A recording on the platform's player (happy-ios's AVPlayer): read from a
 * [MediaDataSource] over the paired connection or a file on the device,
 * prepared at once and drawn into whatever surface [StoryPlayerView] hands
 * it. Main thread only.
 */
class StoryPlayer(input: Input, muted: Boolean) {
    sealed class Input
    class Source(val source: MediaDataSource) : Input()
    class File(val path: String) : Input()

    private val player = MediaPlayer()
    private var prepared = false
    private var wantsPlay = false
    var failed = false
        private set
    var onAudioKnown: ((Boolean) -> Unit)? = null
    var onEnd: (() -> Unit)? = null
    var onSize: ((Int, Int) -> Unit)? = null

    var muted: Boolean = muted
        set(value) { field = value; if (prepared) applyVolume() }

    init {
        try {
            when (input) {
                is Source -> player.setDataSource(input.source)
                is File -> player.setDataSource(input.path)
            }
            player.setOnPreparedListener {
                prepared = true
                applyVolume()
                val audio = runCatching { player.trackInfo.any { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO } }.getOrDefault(false)
                onAudioKnown?.invoke(audio)
                if (wantsPlay) player.start()
            }
            player.setOnCompletionListener { onEnd?.invoke() }
            player.setOnErrorListener { _, _, _ -> failed = true; true }
            player.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) onSize?.invoke(w, h) }
            player.prepareAsync()
        } catch (_: Exception) {
            failed = true
        }
    }

    private fun applyVolume() {
        val volume = if (muted) 0f else 1f
        player.setVolume(volume, volume)
    }

    val durationMs: Int get() = if (prepared) player.duration else 0
    val positionMs: Int get() = if (prepared) player.currentPosition else 0

    fun setSurface(surface: Surface?) {
        runCatching { player.setSurface(surface) }
    }

    fun play() {
        wantsPlay = true
        if (prepared && !player.isPlaying) player.start()
    }

    fun pause() {
        wantsPlay = false
        if (prepared && player.isPlaying) player.pause()
    }

    fun restart() {
        if (prepared) player.seekTo(0)
    }

    fun release() {
        wantsPlay = false
        runCatching { player.release() }
    }
}

/** A recording's picture: a texture its player draws into, stretched to its bounds. */
class StoryPlayerView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var surface: Surface? = null

    var player: StoryPlayer? = null
        set(value) {
            field?.setSurface(null)
            field = value
            value?.setSurface(surface)
        }

    init {
        surfaceTextureListener = this
        isOpaque = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(texture)
        player?.setSurface(surface)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        player?.setSurface(null)
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
}
