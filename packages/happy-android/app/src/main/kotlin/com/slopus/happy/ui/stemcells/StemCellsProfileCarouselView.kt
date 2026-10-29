package com.slopus.happy.ui.stemcells

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.stemcells.StemCellsProfileCarousel
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Every paired computer and signed-in account as a strip of story circles
 * (happy-ios `StemCellsProfileCarouselView`, after Telegram's settings):
 * the chosen one in the middle, larger and ringed. A drag scales them as
 * they pass the middle, a stop snaps to one (a pick tick on each), a tap
 * brings one to the middle; the "+" at the end only taps. Frames are
 * [StemCellsProfileCarousel]'s.
 */
class StemCellsProfileCarouselView(context: Context) : ManualLayout(context), ThemeAware {
    data class Item(
        val id: String,
        val label: String,
        val image: Bitmap?,
        /** Shown without a picture: Happy's mark for a computer, a person for an account. */
        val isDirect: Boolean,
    )

    /** A profile came to rest in the middle. */
    var onSelect: ((Int) -> Unit)? = null
    /** A profile's circle was tapped: it is chosen and settings goes. */
    var onTap: ((Int) -> Unit)? = null
    var onAdd: (() -> Unit)? = null

    var items: List<Item> = emptyList()
        private set
    var selected = 0
        private set
    private val circles = ArrayList<Circle>()
    private val add = Circle(context, isAdd = true)
    /** The strip's offset: item i is in the middle at `StemCellsProfileCarousel.offset(i)`. */
    private var offset = 0.0
    /** The item in the middle while moving, for the tick. */
    private var centered = 0
    /** The circle last tapped, until the strip comes to rest on it. */
    private var tapped: Int? = null
    private var needsCentering = true
    private var motion: ValueAnimator? = null
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var lastX = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        add.tag = "stem-profile-add"
        add.contentDescription = context.getString(R.string.stem_add_profile)
        add.setOnClickListener { onAdd?.invoke() }
        addView(add)
    }

    /** Shows `items` with `selected` in the middle; the strip holds still when that one was already there. */
    fun set(items: List<Item>, selected: Int) {
        val resized = items.size != circles.size
        this.items = items
        while (circles.size < items.size) {
            val circle = Circle(context, isAdd = false)
            circle.setOnClickListener { circleTapped(circles.indexOf(circle)) }
            addView(circle, circles.size)
            circles += circle
        }
        while (circles.size > items.size) removeView(circles.removeAt(circles.size - 1))
        for ((index, pair) in circles.zip(items).withIndex()) {
            pair.first.show(pair.second)
            // By place in the strip: a computer paired twice wears one name.
            pair.first.tag = "stem-profile:$index"
        }
        val moved = selected != this.selected || resized
        this.selected = selected
        centered = selected
        markSelected()
        if (moved && !dragging && motion == null) needsCentering = true
        requestLayout()
    }

    private fun markSelected() {
        for ((index, circle) in circles.withIndex()) circle.isSelected = index == selected
    }

    override fun layoutSubviews(width: Double, height: Double) {
        // Only once there is a width to centre in.
        if (needsCentering && width > 0) {
            needsCentering = false
            offset = StemCellsProfileCarousel.offset(selected)
        }
        place()
    }

    private fun place() {
        val width = px(this.width)
        val outset = StemCellsProfileCarousel.Metrics.ringGap + StemCellsProfileCarousel.Metrics.ringWidth
        for ((index, circle) in circles.withIndex()) {
            val item = StemCellsProfileCarousel.item(index, offset, width)
            circle.emphasis = item.emphasis
            circle.setFrame(shifted(item.frame).insetBy(-outset, -outset))
        }
        // The "+" is one more place along, but never a resting place.
        val item = StemCellsProfileCarousel.item(items.size, offset, width)
        add.emphasis = 0.0
        add.setFrame(shifted(item.frame).insetBy(-outset, -outset))
    }

    /** From the strip's content space to the view's. */
    private fun shifted(frame: Rect) = Rect(frame.minX - offset, frame.minY, frame.width, frame.height)

    private val maxOffset get() = StemCellsProfileCarousel.offset(max(0, items.size - 1))

    private fun scrollTo(value: Double) {
        offset = value
        place()
        val index = StemCellsProfileCarousel.index(offset, items.size)
        if (index != centered) {
            centered = index
            StemCellsHaptics.play(this, StemCellsHaptics.Moment.Pick)
        }
    }

    // Touches: a horizontal drag moves the strip; anything shorter is a tap on a circle.

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                lastX = ev.x
                dragging = false
                // A touch while the strip glides stops it, and is a tap on what it lands on.
                motion?.cancel()
                motion = null
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (abs(ev.x - downX) > slop) {
                    startDrag(ev)
                    return true
                }
            }
        }
        return false
    }

    private fun startDrag(ev: MotionEvent) {
        dragging = true
        tapped = null
        lastX = ev.x
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    if (abs(event.x - downX) <= slop) return true
                    startDrag(event)
                }
                val delta = px(event.x - lastX)
                lastX = event.x
                // Past either end it gives, as a scroll view's edge does.
                val next = offset - delta
                val over = next < 0 || next > maxOffset
                scrollTo(if (over) offset - delta / 3 else next)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return true
                dragging = false
                val velocity = tracker?.let {
                    it.computeCurrentVelocity(1000)
                    px(it.xVelocity)
                } ?: 0.0
                tracker?.recycle()
                tracker = null
                // UIScrollView's fast deceleration: where a flick would stop, then the item nearest it.
                val target = offset - velocity * DECELERATION
                glide(StemCellsProfileCarousel.snapped(min(maxOffset, max(0.0, target)), items.size))
            }
        }
        return true
    }

    /** To `target`, then at rest there. */
    private fun glide(target: Double) {
        motion?.cancel()
        val from = offset
        if (abs(from - target) < 0.5) {
            scrollTo(target)
            rest()
            return
        }
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = min(450.0, 200 + abs(target - from) * 1.5).roundToLong()
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { scrollTo(from + (target - from) * (it.animatedValue as Float)) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (motion === animation) motion = null
                    if (!cancelled) rest()
                }
            })
            start()
        }
    }

    private fun rest() {
        val index = tapped ?: StemCellsProfileCarousel.index(offset, items.size)
        tapped = null
        if (index == selected || index !in items.indices) return
        selected = index
        markSelected()
        onSelect?.invoke(index)
    }

    /** A tap chooses the profile at once, as Telegram's account list does: it comes to the middle, then settings goes. */
    private fun circleTapped(index: Int) {
        if (index !in items.indices) return
        StemCellsHaptics.play(this, StemCellsHaptics.Moment.Pick)
        centered = index
        tapped = index
        if (index != selected) {
            selected = index
            markSelected()
        }
        glide(StemCellsProfileCarousel.offset(index))
        onTap?.invoke(index)
    }

    override fun applyTheme() {
        for (circle in circles) circle.applyTheme()
        add.applyTheme()
    }

    /** One circle: the picture clipped round, and a ring around it as far out as it is chosen. */
    private class Circle(context: Context, private val isAdd: Boolean) : ManualLayout(context), ThemeAware {
        private val picture = ImageView(context)
        private val mark = HappyMarkView(context)
        private val glyph = IconView(context, if (isAdd) Icon.ionicons("add") else StemCellsGlyphs.icon("person-outline"), if (isAdd) 26.0 else 28.0, StemCellsPalette.stemCellsSettingsText)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private var hasPicture = false
        var emphasis = 0.0
            set(value) { if (field != value) { field = value; invalidate() } }

        init {
            setWillNotDraw(false)
            picture.scaleType = ImageView.ScaleType.CENTER_CROP
            picture.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            picture.clipToOutline = true
            for (view in listOf(picture, mark, glyph)) {
                view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(view)
            }
            picture.visibility = GONE
            mark.visibility = GONE
            isClickable = true
            isFocusable = true
            applyTheme()
        }

        fun show(item: Item) {
            contentDescription = item.label
            hasPicture = item.image != null
            picture.visibility = if (hasPicture) VISIBLE else GONE
            picture.setImageBitmap(item.image)
            mark.visibility = if (!hasPicture && item.isDirect) VISIBLE else GONE
            glyph.visibility = if (!hasPicture && !item.isDirect) VISIBLE else GONE
            invalidate()
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val outset = StemCellsProfileCarousel.Metrics.ringGap + StemCellsProfileCarousel.Metrics.ringWidth
            val inner = Rect(outset, outset, width - 2 * outset, height - 2 * outset)
            picture.setFrame(inner)
            glyph.setFrame(inner)
            // Happy's mark at the person glyph's 28, unscaled as the circle grows (iOS `happyMark(side: 28)`, centred).
            val side = 28.0
            mark.setFrame(Rect(inner.midX - side / 2, inner.midY - side / 2, side, side))
        }

        override fun onDraw(canvas: Canvas) {
            val outset = dp(StemCellsProfileCarousel.Metrics.ringGap + StemCellsProfileCarousel.Metrics.ringWidth)
            val w = width.toFloat()
            val h = height.toFloat()
            if (!hasPicture) canvas.drawOval(RectF(outset, outset, w - outset, h - outset), fill)
            val alpha = if (isAdd) 0.0 else emphasis * emphasis
            if (alpha <= 0) return
            val half = dp(StemCellsProfileCarousel.Metrics.ringWidth) / 2
            ring.strokeWidth = half * 2
            ring.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            canvas.drawOval(RectF(half, half, w - half, h - half), ring)
        }

        override fun applyTheme() {
            ring.color = Theme.color(StemCellsPalette.stemCellsSettingsRing)
            fill.color = Theme.color(StemCellsPalette.stemCellsSettingsAddFill)
            glyph.applyTheme()
            mark.applyTheme()
            invalidate()
        }
    }

    private companion object {
        /** How far a flick carries, seconds of its speed: UIScrollView's fast rate, roughly. */
        const val DECELERATION = 0.12
    }
}

/**
 * Happy's mark, drawn: three by three squares with the middle column's top
 * and bottom left out, the outer corners rounded (happy-ios `logo-black`).
 */
class HappyMarkView(context: Context) : View(context), ThemeAware {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        applyTheme()
    }

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        if (side <= 0) return
        // The logo's own proportions: 1024 wide, cells 321 with 30 between.
        val gap = side * 30f / 1024
        val cell = (side - 2 * gap) / 3
        val radius = side * 62f / 1024
        path.reset()
        for (row in 0..2) for (column in 0..2) {
            if (column == 1 && row != 1) continue
            val x = column * (cell + gap)
            val y = row * (cell + gap)
            val corner = when {
                row == 0 && column == 0 -> floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, 0f, 0f)
                row == 0 && column == 2 -> floatArrayOf(0f, 0f, radius, radius, 0f, 0f, 0f, 0f)
                row == 2 && column == 2 -> floatArrayOf(0f, 0f, 0f, 0f, radius, radius, 0f, 0f)
                row == 2 && column == 0 -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, radius, radius)
                else -> FloatArray(8)
            }
            path.addRoundRect(RectF(x, y, x + cell, y + cell), corner, Path.Direction.CW)
        }
        canvas.drawPath(path, paint)
    }

    override fun applyTheme() {
        paint.color = Theme.color(StemCellsPalette.stemCellsSettingsText)
        invalidate()
    }
}
