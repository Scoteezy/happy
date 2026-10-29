package com.slopus.happy.ui.stemcells

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.stemcells.StemCellPictureDismissal
import com.slopus.happy.layout.stemcells.StemCellPicturePagerLayout
import com.slopus.happy.layout.stemcells.StemCellPictureViewerLayout
import com.slopus.happy.stemcells.StemCellChatItem
import com.slopus.happy.ui.chat.ChatMediaSource
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One picture of a conversation's, as the viewer pages them (happy-ios `StemCellPicture`). */
data class StemCellPicture(val key: String, val thumbhash: String?, val label: String) {
    companion object {
        /**
         * Every picture [items] show, in the conversation's order: the ones
         * the person sent and the ones agents presented. Recordings play in
         * their own player, and files open in an app for them.
         */
        fun all(items: List<StemCellChatItem>, pictureLabel: String): List<StemCellPicture> {
            val pictures = ArrayList<StemCellPicture>()
            for (item in items) for (piece in item.pieces) when (piece) {
                is StemCellChatItem.Piece.Picture -> pictures += StemCellPicture(StemCellMediaLoader.sentKey(piece.id), null, pictureLabel)
                is StemCellChatItem.Piece.Gallery -> piece.media.filter { it.isVisual && !it.isVideo }.forEach { pictures += StemCellPicture(it.key, it.item.thumbhash, it.item.name) }
                is StemCellChatItem.Piece.Tree -> piece.media.filter { it.isVisual && !it.isVideo }.forEach { pictures += StemCellPicture(it.key, it.item.thumbhash, it.item.name) }
                else -> Unit
            }
            return pictures
        }
    }
}

/**
 * Desktop's Lightbox on the phone (happy-ios `StemCellPictureViewController`),
 * as Photos and Telegram hold it: a conversation's pictures whole on black,
 * swiped between in its order, each pinched or double-tapped to zoom;
 * dragged up or down it follows the finger as the black fades, and goes;
 * its button, a tap or back closes it. It covers its host.
 */
class StemCellPictureViewer(
    context: Context,
    private val source: ChatMediaSource,
    private val pictures: List<StemCellPicture>,
    index: Int,
    private val scope: CoroutineScope,
) : ManualLayout(context) {
    var onClose: (() -> Unit)? = null
    var safeArea = LayoutInsets.Zero
        set(value) { if (field != value) { field = value; requestLayout() } }

    var index = index.coerceIn(0, max(0, pictures.size - 1))
        private set
    private val pages = HashMap<Int, Page>()
    private val close = IconView(context, Icon.ionicons("close"), 26.0, StemCellsPalette.stemCellsViewerControl)
    private val ground = Theme.color(StemCellsPalette.stemCellsViewerGround)
    /** The pager's offset, dp: page i is in view at `i * pageWidth`. */
    private var offset = 0.0
    private var animator: ValueAnimator? = null
    private var closing = false

    init {
        tag = "stem-picture-viewer"
        setBackgroundColor(ground)
        close.contentDescription = context.getString(R.string.stem_close)
        close.isClickable = true
        close.isFocusable = true
        close.setOnClickListener { dismiss() }
        addView(close)
        alpha = 0f
    }

    /** What the harness reads: the picture in view, of how many. */
    val probe: String get() = "${index + 1}/${pictures.size}"

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
        animate().alpha(0f).setDuration(200).withEndAction { finish() }.start()
    }

    private fun finish() {
        pages.values.forEach { it.release() }
        (parent as? android.view.ViewGroup)?.removeView(this)
        onClose?.invoke()
        onClose = null
    }

    private val size get() = Size(px(width), px(height))
    private val pager get() = StemCellPicturePagerLayout(size, pictures.size)

    override fun layoutSubviews(width: Double, height: Double) {
        close.setFrame(StemCellPictureViewerLayout(Size(width, height), safeArea, Size.Zero).close.let { Rect(it.minX + 9, it.minY + 9, 26.0, 26.0) })
        if (animator == null && drag == Drag.None) offset = pager.offset(index)
        showPages()
    }

    /** The page in view and its neighbours, made as they come near and let go as they leave. */
    private fun showPages() {
        if (pictures.isEmpty() || width == 0) return
        val wanted = (max(0, index - 1)..min(pictures.size - 1, index + 1)).toSet()
        for (number in pages.keys.toList()) if (number !in wanted) pages.remove(number)?.let { removeView(it); it.release() }
        for (number in wanted) if (pages[number] == null) {
            val page = Page(context, source, pictures[number], scope)
            addView(page, 0)
            pages[number] = page
        }
        for ((number, page) in pages) {
            page.safeArea = safeArea
            page.current = number == index
            page.contentDescription = pictures[number].label
            page.stateDescription = context.getString(R.string.stem_picture_number, number + 1, pictures.size)
            placePage(number, page)
        }
    }

    private fun placePage(number: Int, page: Page) {
        val frame = pager.page(number)
        page.setFrame(Rect(frame.minX - offset + pager.pager.minX, 0.0, frame.width, frame.height))
    }

    private fun placePages() {
        for ((number, page) in pages) placePage(number, page)
    }

    private fun settle(to: Int) {
        val target = to.coerceIn(0, max(0, pictures.size - 1))
        if (target != index) {
            index = target
            showPages()
        }
        animator?.cancel()
        val from = offset
        val goal = pager.offset(target)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                offset = from + (goal - from) * (it.animatedValue as Float)
                placePages()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    // Turned away from, a page is whole again when it comes back.
                    for ((number, page) in pages) if (number != index) page.reset()
                }
            })
            start()
        }
    }

    // Touches: a pinch zooms, a zoomed picture pans; otherwise the first
    // movement decides between paging (across) and closing (up or down).

    private enum class Drag { None, Paging, Closing, Panning }

    private var drag = Drag.None
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startOffset = 0.0
    private var tracker: VelocityTracker? = null
    private val current: Page? get() = pages[index]

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            current?.zoom(detector.scaleFactor, detector.focusX - (current?.left ?: 0), detector.focusY)
            return true
        }
    })

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            dismiss()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            current?.toggleZoom(e.x - (current?.left ?: 0), e.y)
            return true
        }
    })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // The close button keeps its own taps.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.x >= close.left - dp(9.0) && ev.x <= close.right + dp(9.0) && ev.y >= close.top - dp(9.0) && ev.y <= close.bottom + dp(9.0)) return false
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (closing) return true
        scale.onTouchEvent(event)
        taps.onTouchEvent(event)
        tracker = tracker ?: VelocityTracker.obtain()
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                animator = null
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                startOffset = offset
                drag = Drag.None
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (drag == Drag.Paging) settle(index)
                if (drag == Drag.Closing) restore()
                drag = Drag.Panning
            }
            MotionEvent.ACTION_MOVE -> {
                if (scale.isInProgress) {
                    lastX = event.x
                    lastY = event.y
                    return true
                }
                val dx = event.x - downX
                val dy = event.y - downY
                if (drag == Drag.None) {
                    val zoomed = current?.zoomed == true
                    drag = when {
                        zoomed && (abs(dx) > slop || abs(dy) > slop) -> Drag.Panning
                        abs(dx) > slop && abs(dx) > abs(dy) -> Drag.Paging
                        abs(dy) > slop -> Drag.Closing
                        else -> Drag.None
                    }
                    lastX = event.x
                    lastY = event.y
                }
                when (drag) {
                    Drag.Paging -> {
                        val max = pager.offset(pictures.size - 1)
                        var next = startOffset - px(dx)
                        // Past either end it gives, as a scroll view bounces.
                        if (next < 0) next /= 3 else if (next > max) next = max + (next - max) / 3
                        offset = next
                        placePages()
                    }
                    Drag.Closing -> follow(px(dy))
                    Drag.Panning -> current?.pan(event.x - lastX, event.y - lastY)
                    Drag.None -> Unit
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.computeCurrentVelocity(1000)
                val vx = px(tracker?.xVelocity ?: 0f)
                val vy = px(tracker?.yVelocity ?: 0f)
                tracker?.recycle()
                tracker = null
                when (drag) {
                    Drag.Paging -> {
                        val moved = offset - pager.offset(index)
                        val next = when {
                            vx < -500 || moved > pager.pageWidth / 2 -> index + 1
                            vx > 500 || moved < -pager.pageWidth / 2 -> index - 1
                            else -> index
                        }
                        settle(next)
                    }
                    Drag.Closing -> {
                        val dy = px(event.y - downY)
                        val dismissal = StemCellPictureDismissal(dy, vy, px(height))
                        if (event.actionMasked == MotionEvent.ACTION_UP && dismissal.closes) leave(dy, vy) else restore()
                    }
                    else -> Unit
                }
                drag = Drag.None
            }
        }
        return true
    }

    /** The picture follows the finger, shrinking a little, as the black fades. */
    private fun follow(dy: Double) {
        val page = current ?: return
        val dismissal = StemCellPictureDismissal(dy, 0.0, px(height))
        page.translationY = dp(dy)
        page.scaleX = dismissal.scale.toFloat()
        page.scaleY = dismissal.scale.toFloat()
        background.alpha = (dismissal.groundAlpha * 255).toInt()
        close.alpha = dismissal.groundAlpha.toFloat()
    }

    private fun restore() {
        val page = current ?: return
        page.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(250).start()
        val from = background.alpha
        ValueAnimator.ofInt(from, 255).apply {
            duration = 250
            addUpdateListener { background.alpha = it.animatedValue as Int }
            start()
        }
        close.animate().alpha(1f).setDuration(250).start()
    }

    /** Let go far or fast enough: it carries on off the screen the way it was going as the black goes. */
    private fun leave(dy: Double, vy: Double) {
        val page = current ?: return finish()
        closing = true
        val down = if (vy == 0.0) dy >= 0 else vy > 0
        val distance = px(height)
        val remaining = max(1.0, distance - abs(dy))
        val duration = (min(0.3, max(0.15, remaining / max(abs(vy), 1.0))) * 1000).toLong()
        val min = StemCellPictureDismissal.MIN_SCALE.toFloat()
        page.animate().translationY(dp(if (down) distance else -distance)).scaleX(min).scaleY(min).setDuration(duration).start()
        val from = background.alpha
        ValueAnimator.ofInt(from, 0).apply {
            this.duration = duration
            addUpdateListener { background.alpha = it.animatedValue as Int }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finish()
            })
            start()
        }
        close.animate().alpha(0f).setDuration(duration).start()
    }

    /**
     * One picture, fitted inside the safe area and never enlarged past its
     * own size, read from the conversation's media as it comes near; zoomed
     * up to four times, panned within its edges.
     */
    private class Page(context: Context, private val source: ChatMediaSource, private val picture: StemCellPicture, scope: CoroutineScope) : View(context) {
        var safeArea = LayoutInsets.Zero
            set(value) { if (field != value) { field = value; invalidate() } }
        var current = false
            set(value) {
                field = value
                importantForAccessibility = if (value) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        private var image: Bitmap? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val matrix = Matrix()
        /** The zoom on top of the fitted picture, and where it is panned to (px). */
        private var zoom = 1f
        private var panX = 0f
        private var panY = 0f
        private var zoomAnimator: ValueAnimator? = null
        private val loading: Job

        val zoomed: Boolean get() = zoom > 1.01f

        init {
            loading = scope.launch {
                val cached = source.cachedImage(picture.key, 1)
                if (cached != null) show(cached) else picture.thumbhash?.let { hash -> source.placeholder(hash)?.let(::show) }
                // Enough to zoom into: the long edge a sent photo is kept at.
                source.image(picture.key, 3072)?.let(::show)
            }
        }

        fun release() = loading.cancel()

        private fun show(bitmap: Bitmap) {
            image = bitmap
            invalidate()
        }

        /** The fitted picture, in px. */
        private fun fitted(): android.graphics.RectF? {
            val bitmap = image ?: return null
            // Its pixels as points, as iOS reads a decoded UIImage: never enlarged past them.
            val natural = Size(bitmap.width.toDouble(), bitmap.height.toDouble())
            val frame = StemCellPictureViewerLayout(Size(px(width), px(height)), safeArea, natural).picture
            return android.graphics.RectF(dp(frame.minX), dp(frame.minY), dp(frame.maxX), dp(frame.maxY))
        }

        override fun onDraw(canvas: Canvas) {
            val bitmap = image ?: return
            val box = fitted() ?: return
            matrix.reset()
            matrix.setRectToRect(android.graphics.RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), box, Matrix.ScaleToFit.FILL)
            matrix.postScale(zoom, zoom, width / 2f, height / 2f)
            matrix.postTranslate(panX, panY)
            canvas.drawBitmap(bitmap, matrix, paint)
        }

        fun zoom(factor: Float, focusX: Float, focusY: Float) {
            val next = (zoom * factor).coerceIn(1f, 4f)
            val applied = next / zoom
            // The point under the fingers stays under them.
            panX = (panX - (focusX - width / 2f)) * applied + (focusX - width / 2f)
            panY = (panY - (focusY - height / 2f)) * applied + (focusY - height / 2f)
            zoom = next
            clamp()
            invalidate()
        }

        fun pan(dx: Float, dy: Float) {
            panX += dx
            panY += dy
            clamp()
            invalidate()
        }

        /** Double-tapped: into the point, or whole again. */
        fun toggleZoom(x: Float, y: Float) {
            val from = zoom
            val to = if (zoomed) 1f else 2.5f
            val fromX = panX
            val fromY = panY
            val toX = if (to == 1f) 0f else -(x - width / 2f) * (to - 1)
            val toY = if (to == 1f) 0f else -(y - height / 2f) * (to - 1)
            zoomAnimator?.cancel()
            zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 250
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float
                    zoom = from + (to - from) * t
                    panX = fromX + (toX - fromX) * t
                    panY = fromY + (toY - fromY) * t
                    clamp()
                    invalidate()
                }
                start()
            }
        }

        fun reset() {
            zoom = 1f
            panX = 0f
            panY = 0f
            invalidate()
        }

        /** Panned no further than the zoomed picture's edges, centred where it is smaller than the screen. */
        private fun clamp() {
            val box = fitted() ?: return
            val w = box.width() * zoom
            val h = box.height() * zoom
            val maxX = max(0f, (w - width) / 2f)
            val maxY = max(0f, (h - height) / 2f)
            val centreX = (box.centerX() - width / 2f) * zoom
            val centreY = (box.centerY() - height / 2f) * zoom
            panX = panX.coerceIn(-maxX - centreX, maxX - centreX)
            panY = panY.coerceIn(-maxY - centreY, maxY - centreY)
            if (zoom <= 1f) {
                panX = 0f
                panY = 0f
            }
        }
    }
}
