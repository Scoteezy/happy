package com.slopus.happy.ui.stemcells

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.slopus.happy.R
import com.slopus.happy.layout.*
import com.slopus.happy.stemcells.StemCellsDrag
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.*
import kotlin.math.abs

/** A lifted row/block, carried by the menu's next drag or the preview's still-held pointer. */
@SuppressLint("ClickableViewAccessibility")
class StemCellsReorderOverlay(
    context: Context,
    private val sidebar: StemCellsSidebarView,
    private val drag: StemCellsDrag,
    private val picture: StemCellsSidebarView.CarryPicture,
    private val source: Rect,
    label: String,
) : ManualLayout(context) {
    var onDrop: ((Int) -> Unit)? = null
    var onEnd: (() -> Unit)? = null
    var onGone: (() -> Unit)? = null
    private val card = ImageView(context)
    private var target = drag.from
    private var down: Point? = null
    private var hand: Point? = null
    private var offsetAtDown = 0.0
    private var moving = false
    private var closing = false
    private var finished = false
    private var lastFrame = 0L
    private var placedSize: Size? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val frame = object : Runnable {
        override fun run() {
            if (!moving || closing) return
            val now = SystemClock.uptimeMillis()
            val delta = (now - lastFrame).coerceIn(1, 32) / (1000.0 / 60)
            lastFrame = now
            val point = hand
            if (point != null) {
                val top = sidebar.list.insetTop + 56
                val bottom = px(height) - sidebar.list.insetBottom - 56
                var speed = 0.0
                if (point.y < top) speed = -minOf(1.0, (top - point.y) / 56) * 14
                if (point.y > bottom) speed = minOf(1.0, (point.y - bottom) / 56) * 14
                val low = -sidebar.list.insetTop
                val high = maxOf(low, sidebar.list.contentHeight + sidebar.list.insetBottom - px(sidebar.list.height))
                val next = (sidebar.list.contentOffset + speed * delta).coerceIn(low, high)
                if (next != sidebar.list.contentOffset) {
                    sidebar.list.setContentOffset(next)
                    updateTarget()
                }
            }
            postOnAnimation(this)
        }
    }

    init {
        tag = "stem-reorder"
        isClickable = true
        card.tag = "stem-lifted"
        card.setImageBitmap(picture.image)
        card.scaleType = ImageView.ScaleType.FIT_XY
        card.background = GradientDrawable().apply { setColor(Theme.color(Palette.surface)); cornerRadius = dp(StemCellsMetrics.cornerRadius) }
        card.clipToOutline = true
        card.elevation = dp(12.0)
        card.isFocusable = true
        card.contentDescription = "$label. ${context.getString(R.string.stem_reorder_hint)}"
        // TalkBack can move between legal slots and activate to put the row down.
        ViewCompat.replaceAccessibilityAction(card, AccessibilityActionCompat.ACTION_SCROLL_BACKWARD, context.getString(R.string.stem_move_up)) { _, _ -> accessibleMove(-1) }
        ViewCompat.replaceAccessibilityAction(card, AccessibilityActionCompat.ACTION_SCROLL_FORWARD, context.getString(R.string.stem_move_down)) { _, _ -> accessibleMove(1) }
        card.setOnClickListener { finishDrag(drop = target != drag.from) }
        addView(card)
    }

    fun present() {
        card.alpha = 0f
        card.animate().alpha(1f).scaleX(1.025f).scaleY(1.025f).setDuration(150).start()
        StemCellsHaptics.play(this, StemCellsHaptics.Moment.Lift)
        post { card.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) }
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val size = Size(width, height)
        if (placedSize != null && placedSize != size) { cancel(animated = false); return }
        placedSize = size
        card.setFrame(source)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (closing) return true
        val point = Point(px(event.x), px(event.y))
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = point
                hand = point
                offsetAtDown = sidebar.list.contentOffset
            }
            MotionEvent.ACTION_MOVE -> {
                val start = down ?: return true
                hand = point
                if (!moving && (abs(event.x - dp(start.x)) > slop || abs(event.y - dp(start.y)) > slop)) {
                    moving = true
                    lastFrame = SystemClock.uptimeMillis()
                    postOnAnimation(frame)
                }
                if (moving) {
                    card.translationX = dp(point.x - start.x)
                    card.translationY = dp(point.y - start.y)
                    updateTarget()
                }
            }
            MotionEvent.ACTION_UP -> {
                hand = point
                updateTarget()
                // A tap, or a release outside the sidebar, goes back without an edit.
                val inside = point.x in 0.0..px(width) && point.y in sidebar.list.insetTop..(px(height) - sidebar.list.insetBottom)
                finishDrag(drop = moving && inside)
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    private fun updateTarget() {
        val start = down ?: return
        val current = hand ?: return
        if (!moving) return
        choose(drag.target(current.y - start.y + sidebar.list.contentOffset - offsetAtDown, picture.heights))
    }

    private fun choose(next: Int) {
        if (target == next) return
        target = next
        sidebar.arrangeReorder(drag, target)
        StemCellsHaptics.play(this, StemCellsHaptics.Moment.Slot)
    }

    private fun accessibleMove(direction: Int): Boolean {
        if (closing) return false
        var next = target + direction
        while (next in drag.units.indices && drag.blocked[next]) next += direction
        if (next !in drag.units.indices) return false
        choose(next)
        val destination = destination() ?: return true
        card.translationY = dp(destination.minY - source.minY)
        return true
    }

    fun cancel(animated: Boolean = true) = finishDrag(drop = false, animated = animated)

    private fun finishDrag(drop: Boolean, animated: Boolean = true) {
        if (closing) { if (!animated) finish(); return }
        closing = true
        moving = false
        removeCallbacks(frame)
        if (drop) {
            sidebar.arrangeReorder(drag, target)
            onDrop?.invoke(target)
            StemCellsHaptics.play(this, StemCellsHaptics.Moment.Drop)
        }
        sidebar.endReorder(dropped = drop)
        onEnd?.invoke()
        onEnd = null
        val at = destination()
        card.animate().cancel()
        if (!animated || at == null) { finish(); return }
        card.animate().translationX(dp(at.minX - source.minX)).translationY(dp(at.minY - source.minY))
            .scaleX(1f).scaleY(1f).setDuration(250).withEndAction { finish() }.start()
    }

    private fun destination(): Rect? {
        val rect = sidebar.heldFrame(drag) ?: return null
        val at = IntArray(2).also(sidebar::getLocationOnScreen)
        val base = IntArray(2).also(this::getLocationOnScreen)
        return rect.offsetBy(px(at[0] - base[0]), px(at[1] - base[1]) - sidebar.list.contentOffset)
    }

    private fun finish() {
        if (finished) return
        finished = true
        removeCallbacks(frame)
        card.animate().cancel()
        sidebar.revealReorder()
        (parent as? ViewGroup)?.removeView(this)
        card.setImageDrawable(null)
        picture.image.recycle()
        onGone?.invoke()
        onGone = null
    }
}