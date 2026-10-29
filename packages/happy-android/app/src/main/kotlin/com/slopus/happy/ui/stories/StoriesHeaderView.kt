package com.slopus.happy.ui.stories

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.storiesAvatarFill
import com.slopus.happy.layout.storiesAvatarText
import com.slopus.happy.layout.storiesSeen
import com.slopus.happy.layout.storiesUnseenBottom
import com.slopus.happy.layout.storiesUnseenTop
import com.slopus.happy.layout.stories.StoriesHeaderLayout
import com.slopus.happy.layout.stories.StoriesRowLayout
import com.slopus.happy.stories.StoriesRowItem
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.FrameContainer
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.density
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * Home's stories, in the header (happy-ios `StoriesHeaderView`, Telegram's
 * `StoryPeerListComponent`): the circles open in a row under the header,
 * scrolling sideways, or closed into the title as up to three small
 * overlapping avatars, tapped to open them again. [StoriesHeaderLayout]
 * places everything; this view only draws where it says. Circles are
 * reused by author, so a change redraws only what changed and nothing jumps.
 */
class StoriesHeaderView(context: Context) : ManualLayout(context) {
    var onOpen: ((StoriesRowItem) -> Unit)? = null
    /** The closed avatars were tapped. */
    var onExpand: (() -> Unit)? = null
    /** The open row scrolled sideways. */
    var onScroll: (() -> Unit)? = null
    /** The view was laid out at a new size. */
    var onLayout: (() -> Unit)? = null
    /** Each frame while [ticking], for the change between open and closed. */
    var onTick: (() -> Unit)? = null
    /** Reads an author's photo; null shows initials. */
    var photos: (suspend (path: String, thumbhash: String?, pixels: Int) -> Bitmap?)? = null

    var ticking = false
        set(value) {
            if (field == value) return
            field = value
            Choreographer.getInstance().removeFrameCallback(tick)
            if (value) Choreographer.getInstance().postFrameCallback(tick)
        }

    private val tick: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            onTick?.invoke()
            if (ticking) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** What scrolls sideways; the circles are in it. */
    val pager = Pager(context)
    private val strip = FrameContainer(context)
    private val collapsed = View(context)
    var items: List<StoriesRowItem> = emptyList()
        private set
    private val circles = LinkedHashMap<String, StoriesCircleView>()
    private var unlocked = false
    private var laidOutSize = Size.Zero

    init {
        clipChildren = false
        clipToPadding = false
        pager.isHorizontalScrollBarEnabled = false
        pager.overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        pager.clipChildren = false
        pager.clipToPadding = false
        pager.contentDescription = null
        strip.clipChildren = false
        pager.addView(strip)
        pager.setOnScrollChangeListener { _, _, _, _, _ -> onScroll?.invoke() }
        addView(pager)
        collapsed.isClickable = true
        collapsed.isFocusable = true
        collapsed.setOnClickListener { onExpand?.invoke() }
        collapsed.visibility = GONE
        addView(collapsed)
    }

    /** A drag that starts on a circle scrolls the row; closed, it doesn't scroll at all. */
    inner class Pager(context: Context) : HorizontalScrollView(context) {
        var scrollEnabled = true

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = scrollEnabled && super.onInterceptTouchEvent(ev)

        override fun onTouchEvent(ev: MotionEvent): Boolean = scrollEnabled && super.onTouchEvent(ev)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
            strip.measure(MeasureSpec.makeMeasureSpec(dp(strip.contentSize.width).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(strip.contentSize.height).toInt(), MeasureSpec.EXACTLY))
        }
    }

    /** Another computer: remove even circles already fading out, without a cross-account fade. */
    fun reset() {
        for (index in 0 until strip.childCount) {
            val child = strip.getChildAt(index)
            child.animate().cancel()
            (child as? StoriesCircleView)?.leave()
        }
        strip.removeAllViews()
        strip.frames.clear()
        circles.clear()
        items = emptyList()
        collapsed.visibility = GONE
        pager.scrollTo(0, 0)
    }

    /** Shows [items]; returns how many circles it touched. */
    fun apply(items: List<StoriesRowItem>): Int {
        if (items == this.items) return 0
        val previous = this.items.associateBy { it.id }
        this.items = items
        var touched = 0
        val live = HashSet<String>()
        for (item in items) {
            live += item.id
            val existing = circles[item.id]
            if (existing != null) {
                if (previous[item.id] == item) continue
                existing.configure(item, photos)
            } else {
                val circle = StoriesCircleView(context)
                circle.setOnClickListener { tapped(circle) }
                circle.configure(item, photos)
                circles[item.id] = circle
                strip.addView(circle)
                if (previous.isNotEmpty()) {
                    // Arrives where it belongs, faded in rather than sliding.
                    circle.contentAlpha = 0f
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 200
                        addUpdateListener { circle.contentAlpha = it.animatedValue as Float }
                        start()
                    }
                }
            }
            touched++
        }
        val gone = circles.filterKeys { it !in live }
        for ((id, circle) in gone) {
            circles.remove(id)
            circle.leave()
            circle.animate().alpha(0f).setDuration(200).withEndAction { strip.removeView(circle) }.start()
            touched++
        }
        return touched
    }

    fun circle(id: String): StoriesCircleView? = circles[id]

    /**
     * Draws [layout]; [unlocked] says whether the row is open, so takes
     * touches, or closed into the title, where only the avatars' target does.
     */
    fun place(layout: StoriesHeaderLayout, unlocked: Boolean, side: Double) {
        this.unlocked = unlocked
        pager.setFrame(layout.row)
        val content = Size(layout.contentWidth, layout.row.height)
        if (strip.contentSize != content) {
            strip.contentSize = content
            strip.measure(MeasureSpec.makeMeasureSpec(dp(content.width).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(content.height).toInt(), MeasureSpec.EXACTLY))
            strip.layout(0, 0, dp(content.width).toInt(), dp(content.height).toInt())
        }
        pager.scrollEnabled = unlocked
        pager.importantForAccessibility = if (unlocked) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val shift = Point(px(pager.scrollX) - layout.row.minX, -layout.row.minY)
        for ((item, geometry) in items.zip(layout.items)) {
            val circle = circles[item.id] ?: continue
            circle.isEnabled = unlocked
            circle.place(
                center = Point(geometry.center.x + shift.x, geometry.center.y + shift.y),
                scale = geometry.scale, alpha = geometry.alpha, zPosition = geometry.zPosition,
                names = side, single = geometry.collapses && side < 0.5, neighbor = geometry.neighbor,
            )
        }
        val button = layout.button
        if (button != null && !unlocked) {
            collapsed.visibility = VISIBLE
            collapsed.setFrame(button)
        } else {
            collapsed.visibility = GONE
        }
        val names = items.zip(layout.items).filter { it.second.collapses }.map { it.first.title }
        collapsed.contentDescription = StoriesStrings.collapsed(context, names)
    }

    /** Whether a touch at [x], [y] (px, in this view) is the stories'. */
    fun takes(x: Float, y: Float): Boolean {
        if (visibility != VISIBLE || items.isEmpty()) return false
        fun inside(view: View) = x >= view.left && x < view.right && y >= view.top && y < view.bottom
        if (unlocked) return inside(pager)
        return collapsed.visibility == VISIBLE && inside(collapsed)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val size = Size(width, height)
        if (size == laidOutSize) return
        laidOutSize = size
        post { onLayout?.invoke() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ticking = false
    }

    private fun tapped(circle: StoriesCircleView) {
        val item = circle.item ?: return
        if (!unlocked) return
        onOpen?.invoke(item)
    }
}

/**
 * One circle (happy-ios `StoriesCircleView`): the avatar, its ring and the
 * name under it. It turns about the avatar's middle, so it is placed by
 * that point and scaled whole; the ring keeps about its width at any size,
 * and the name fades with the row. Drawn here, layers and all: the ring's
 * segments, the dashes while its story loads, and the cut around the
 * circle before it.
 */
class StoriesCircleView(context: Context) : View(context), ThemeAware {
    var item: StoriesRowItem? = null
        private set

    private val geometry = StoriesRowLayout.item
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val initialsPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = semibold
        textAlign = Paint.Align.CENTER
    }
    private val seenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val unseenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val seenPath = Path()
    private val unseenPath = Path()
    /** The ring as it was before it last changed, fading out. */
    private val fadingSeen = Path()
    private val fadingUnseen = Path()
    private var fade = 1f
    private var fadeAnimator: ValueAnimator? = null
    private val cut = Path()
    private var photo: Bitmap? = null
    private var photoKey: String? = null
    private var loading: kotlinx.coroutines.Job? = null
    private var single = false
    private var drawnScale = 1.0
    private var drawnNeighbor: Point? = null
    private var names = 1f
    private var titleAlpha = 1f
    private var pressScale = 1f
    private var pressAnimator: ValueAnimator? = null
    private var dashes: ValueAnimator? = null
    private var dashTime = 0f

    /** The circle's own fade, apart from where the layout puts it. */
    var contentAlpha: Float = 1f
        set(value) { field = value; invalidate() }

    init {
        // The circle and its name are one button.
        isClickable = true
        isFocusable = true
        outlineProvider = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        applyTheme()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = StoriesRowLayout.Metrics.item
        setMeasuredDimension(dp(size.width).toInt(), dp(size.height).toInt())
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {}

    override fun applyTheme() {
        seenPaint.color = Theme.color(Palette.storiesSeen)
        unseenPaint.shader = null
        avatarPaint.color = Theme.color(Palette.storiesAvatarFill)
        initialsPaint.color = Theme.color(Palette.storiesAvatarText)
        invalidate()
    }

    private fun titleColor(item: StoriesRowItem): Int {
        val color = Theme.color(Palette.text)
        return if (item.isSelf || !item.allSeen) color else (color and 0x00FFFFFF) or (((Color.alpha(color) * 0.5f).toInt()) shl 24)
    }

    fun configure(item: StoriesRowItem, photos: (suspend (String, String?, Int) -> Bitmap?)?) {
        val previous = this.item
        this.item = item
        contentDescription = StoriesStrings.author(context, item.title, item.unseen)
        tag = "story-circle:${if (item.isSelf) "self" else item.title}"
        if (previous?.segments != item.segments || previous.unseen != item.unseen) {
            // Seen since it last showed: the colour gives way to grey.
            if (previous != null && previous.unseen > item.unseen && isAttachedToWindow) {
                fadingSeen.set(seenPath)
                fadingUnseen.set(unseenPath)
                fadeAnimator?.cancel()
                fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 350
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { fade = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
            drawRing()
        }
        loadPhoto(item, photos)
        invalidate()
    }

    /**
     * Where the layout puts it: its avatar's middle in its parent, how big,
     * how present; [names] is the row's names' presence, [single] draws the
     * ring whole as the closed avatars show it, and [neighbor] is where the
     * circle over this one stands, to be cut around.
     */
    fun place(center: Point, scale: Double, alpha: Double, zPosition: Double, names: Double, single: Boolean, neighbor: Point?) {
        if (width == 0) {
            measure(0, 0)
            layout(0, 0, measuredWidth, measuredHeight)
        }
        pivotX = width / 2f
        pivotY = dp(geometry.avatar.midY)
        translationX = dp(center.x) - pivotX
        translationY = dp(center.y) - pivotY
        scaleX = scale.toFloat()
        scaleY = scale.toFloat()
        this.alpha = alpha.toFloat()
        translationZ = zPosition.toFloat()
        this.names = names.toFloat()
        if (this.single != single) {
            this.single = single
            drawRing()
        }
        val rescaled = drawnScale != scale
        drawnScale = scale
        if (rescaled || drawnNeighbor != neighbor) {
            drawnNeighbor = neighbor
            cut.reset()
            if (neighbor != null && scale > 0) {
                val middle = Point(geometry.avatar.midX + neighbor.x / scale, geometry.avatar.midY + neighbor.y / scale)
                val radius = geometry.avatar.width / 2 + StoriesHeaderLayout.ringWidth(StoriesRowLayout.Metrics.ringSeenWidth, scale) * 1.4
                cut.addCircle(dp(middle.x), dp(middle.y), dp(radius), Path.Direction.CW)
            }
        }
        invalidate()
    }

    /**
     * The story it opens on is loading: the ring gives way to the dashes,
     * as Telegram's does (thirty short dashes round the avatar, chasing each
     * other, the whole turning once in four seconds), and comes back once
     * it's done.
     */
    var isLoadingStory = false
        set(value) {
            if (field == value) return
            field = value
            stateDescription = if (value) "loading" else null
            dashes?.cancel()
            dashes = null
            if (value) {
                dashes = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 20_000
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { dashTime = it.animatedFraction * 20f; invalidate() }
                    start()
                }
            }
            invalidate()
        }

    /**
     * Seen segments first, unseen last, as Telegram orders them; closed
     * into the title, one whole ring, coloured if anything is unseen.
     */
    private fun drawRing() {
        val item = item ?: return
        val center = Point(geometry.ring.midX, geometry.ring.midY)
        val radius = StoriesRowLayout.Metrics.ringRadius - StoriesRowLayout.Metrics.ringUnseenWidth / 2
        seenPath.reset()
        unseenPath.reset()
        val count = if (single) 1 else item.segments
        val segments = StoriesRowLayout.segments(count, radius)
        val firstUnseen = if (single) (if (item.unseen > 0) 0 else 1) else segments.size - min(item.unseen, segments.size)
        if (item.segments > 0) {
            val box = RectF(dp(center.x - radius), dp(center.y - radius), dp(center.x + radius), dp(center.y + radius))
            for ((index, segment) in segments.withIndex()) {
                val path = if (index >= firstUnseen) unseenPath else seenPath
                val sweep = Math.toDegrees(segment.second - segment.first).toFloat()
                // A whole ring as one closed circle, so its round caps don't meet.
                if (sweep >= 359.9f) path.addOval(box, Path.Direction.CW)
                else path.addArc(box, Math.toDegrees(segment.first).toFloat(), sweep)
            }
        }
        invalidate()
    }

    private fun loadPhoto(item: StoriesRowItem, photos: (suspend (String, String?, Int) -> Bitmap?)?) {
        // Without a way to read it, the photo waits for the next configure.
        val key = if (photos == null) null else item.photo?.let { "$it#${item.thumbhash ?: ""}" }
        if (key == photoKey) return
        photoKey = key
        loading?.cancel()
        val path = item.photo
        if (path == null || photos == null) {
            photo = null
            return
        }
        val pixels = (StoriesRowLayout.Metrics.avatar * density).toInt()
        loading = kotlinx.coroutines.MainScope().launch {
            val image = photos(path, item.thumbhash, pixels)
            if (photoKey == key && image != null) {
                photo = image
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val item = item ?: return
        val d = density
        val scale = drawnScale
        canvas.save()
        if (!cut.isEmpty) canvas.clipOutPath(cut)
        val layerAlpha = (contentAlpha * 255).toInt().coerceIn(0, 255)
        if (layerAlpha < 255) canvas.saveLayerAlpha(null, layerAlpha)
        // The avatar and its ring shrink a little under the finger.
        val avatar = geometry.avatar
        val cx = dp(avatar.midX)
        val cy = dp(avatar.midY)
        canvas.save()
        canvas.scale(pressScale, pressScale, cx, cy)
        val r = dp(avatar.width / 2)
        val image = photo
        if (image != null) {
            val matrix = Matrix()
            val fill = max(2 * r / image.width, 2 * r / image.height)
            matrix.setScale(fill, fill)
            matrix.postTranslate(cx - image.width * fill / 2, cy - image.height * fill / 2)
            photoPaint.shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
            canvas.drawCircle(cx, cy, r, photoPaint)
        } else {
            canvas.drawCircle(cx, cy, r, avatarPaint)
            initialsPaint.textSize = 20 * d
            val fm = initialsPaint.fontMetrics
            canvas.drawText(item.initials, cx, cy - (fm.ascent + fm.descent) / 2, initialsPaint)
        }
        val unseenWidth = dp(StoriesHeaderLayout.ringWidth(StoriesRowLayout.Metrics.ringUnseenWidth, scale))
        val seenWidth = dp(StoriesHeaderLayout.ringWidth(StoriesRowLayout.Metrics.ringSeenWidth, scale))
        seenPaint.strokeWidth = seenWidth
        unseenPaint.strokeWidth = unseenWidth
        if (unseenPaint.shader == null) {
            val ring = geometry.ring
            unseenPaint.shader = LinearGradient(0f, dp(ring.minY), 0f, dp(ring.maxY), Theme.color(Palette.storiesUnseenTop), Theme.color(Palette.storiesUnseenBottom), Shader.TileMode.CLAMP)
        }
        if (isLoadingStory) {
            drawDashes(canvas, cx, cy)
        } else {
            if (fade < 1f) {
                seenPaint.alpha = ((1 - fade) * 255).toInt()
                unseenPaint.alpha = ((1 - fade) * 255).toInt()
                canvas.drawPath(fadingSeen, seenPaint)
                canvas.drawPath(fadingUnseen, unseenPaint)
            }
            seenPaint.alpha = (fade * 255).toInt()
            unseenPaint.alpha = (fade * 255).toInt()
            canvas.drawPath(seenPath, seenPaint)
            canvas.drawPath(unseenPath, unseenPaint)
            seenPaint.alpha = 255
            unseenPaint.alpha = 255
        }
        canvas.restore()
        if (layerAlpha < 255) canvas.restore()
        canvas.restore()
        // The name, under the ring, fading with the row.
        val shown = min(names, contentAlpha)
        if (shown > 0) {
            titlePaint.textSize = (StoriesRowLayout.Metrics.titleSize * d).toFloat()
            titlePaint.color = titleColor(item)
            titlePaint.alpha = (Color.alpha(titlePaint.color) * shown).toInt()
            val measured = titlePaint.measureText(item.title)
            val fm = titlePaint.fontMetrics
            val frame = geometry.title(px(measured), px(fm.descent - fm.ascent))
            val text = TextUtils.ellipsize(item.title, titlePaint, dp(frame.width), TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, dp(frame.midX), dp(frame.minY) - fm.ascent, titlePaint)
        }
    }

    /** Thirty dashes, each a thirtieth of the ring, shrinking and growing in turn as the whole turns. */
    private fun drawDashes(canvas: Canvas, cx: Float, cy: Float) {
        val ring = geometry.ring
        val inset = StoriesRowLayout.Metrics.ringUnseenWidth / 2 + (ring.width / 2 - StoriesRowLayout.Metrics.ringRadius)
        val radius = dp(ring.width / 2 - inset)
        val box = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val turn = -90f + (dashTime % 4f) / 4f * 360f
        for (i in 0 until 30) {
            val t = ((dashTime - i * 0.025f) % 2.5f + 2.5f) % 2.5f / 2.5f
            // strokeStart through 0, 1/30, 1/30, 0 at 0, 0.45, 0.55, 1.
            val start = when {
                t < 0.45f -> t / 0.45f
                t < 0.55f -> 1f
                else -> 1 - (t - 0.55f) / 0.45f
            } / 30f
            val sweep = (1 / 30f - start) * 360f
            if (sweep <= 0.01f) continue
            canvas.drawArc(box, turn + i * 12f + start * 360f, sweep, false, unseenPaint)
        }
    }

    override fun setPressed(pressed: Boolean) {
        val was = isPressed
        super.setPressed(pressed)
        if (was == pressed) return
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, if (pressed) 0.92f else 1f).apply {
            duration = if (pressed) 120 else 200
            addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Leaves the row: no more loading, and no taps while it fades. */
    fun leave() {
        loading?.cancel()
        isLoadingStory = false
        isEnabled = false
        isClickable = false
    }

    /** Only the circle's own box takes a touch, not where its scale moved its frame from. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = isEnabled && super.dispatchTouchEvent(event)

    companion object {
        private val semibold: Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, 600, false) else Typeface.DEFAULT_BOLD
    }
}
