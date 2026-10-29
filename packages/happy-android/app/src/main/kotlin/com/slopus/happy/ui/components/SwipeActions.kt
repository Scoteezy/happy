package com.slopus.happy.ui.components

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.slopus.happy.icons.Icon
import com.slopus.happy.icons.IconCatalog
import com.slopus.happy.layout.FontFace
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.ui.theme.Fonts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.toAndroidPath
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A row's trailing swipe action (UIKit's `trailingSwipeActionsConfiguration`
 * with one destructive `UIContextualAction` and no full swipe): dragging a
 * row left slides it over a red capsule with the action's glyph and its
 * title under it; let go past half way it stays open, and a tap on the
 * capsule runs the action. A tap anywhere else, a scroll or another drag
 * closes it, and that tap does nothing else, as on iOS.
 */
class SwipeActions(
    private val list: RecyclerView,
    private val actionFor: (RecyclerView.ViewHolder) -> Action?,
) : RecyclerView.ItemDecoration(), RecyclerView.OnItemTouchListener {
    data class Action(val title: String, val icon: Icon, val run: () -> Unit)

    private val slop = ViewConfiguration.get(list.context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(list.context).scaledMinimumFlingVelocity * 4

    /** The open row, if any, and what its capsule is. */
    private var open: RecyclerView.ViewHolder? = null

    private var downX = 0f
    private var downY = 0f
    private var tracked: RecyclerView.ViewHolder? = null
    private var startOffset = 0f
    private var dragging = false
    /** The gesture began with a row open: it closes it, or taps its capsule. */
    private var closing = false
    private var tapsAction = false
    private var velocity: VelocityTracker? = null
    private var animator: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val capsule = RectF()

    init {
        list.addItemDecoration(this)
        list.addOnItemTouchListener(this)
        list.addRecyclerListener { holder ->
            holder.itemView.translationX = 0f
            if (open === holder) open = null
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) close()
            }
        })
    }

    /** Closes the open row, if any. */
    fun close(animated: Boolean = true) {
        val row = open ?: return
        open = null
        settle(row, 0f, animated)
    }

    private val density get() = Theme.density
    private val openOffset get() = OPEN_WIDTH * density

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragging = false
                tapsAction = false
                closing = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(e) }
                val row = open
                if (row != null) {
                    // The open row's capsule, or a tap that only closes it. The row is
                    // judged by its height: slid over, its bounds no longer cover the capsule.
                    closing = true
                    val view = row.itemView
                    tracked = row.takeIf { e.y >= view.top + view.translationY && e.y < view.bottom + view.translationY }
                    startOffset = view.translationX
                    tapsAction = capsuleRect(view)?.contains(e.x, e.y) == true
                    return true
                }
                val holder = rv.findChildViewUnder(e.x, e.y)?.let(rv::getChildViewHolder)
                tracked = holder?.takeIf { actionFor(it) != null }
                startOffset = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (tracked == null || dragging) return dragging
                val dx = e.x - downX
                val dy = e.y - downY
                if (abs(dy) > slop && abs(dy) > abs(dx)) {
                    tracked = null
                } else if (dx < -slop && abs(dx) > abs(dy)) {
                    beginDrag(rv)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracked = null
        }
        return false
    }

    private fun beginDrag(rv: RecyclerView) {
        dragging = true
        tapsAction = false
        animator?.cancel()
        rv.parent?.requestDisallowInterceptTouchEvent(true)
        tracked?.itemView?.isPressed = false
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        velocity?.addMovement(e)
        val row = tracked
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (!dragging && row != null && abs(dx) > slop && abs(dx) > abs(e.y - downY)) beginDrag(rv)
                if (dragging && row != null) {
                    row.itemView.translationX = rubberBand(startOffset + (e.x - downX))
                    rv.invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging && row != null) {
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    val offset = row.itemView.translationX
                    val opens = if (abs(vx) > minFling) vx < 0 else offset < -openOffset / 2
                    if (opens) {
                        open = row
                        settle(row, -openOffset, animated = true)
                    } else {
                        if (open === row) open = null
                        settle(row, 0f, animated = true)
                    }
                } else if (closing) {
                    val action = open?.let(actionFor)
                    val runs = tapsAction && open?.let { capsuleRect(it.itemView)?.contains(e.x, e.y) } == true
                    close()
                    if (runs) action?.run?.invoke()
                }
                finish()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging && row != null) settle(row, if (open === row) -openOffset else 0f, animated = true)
                finish()
            }
        }
    }

    private fun finish() {
        tracked = null
        dragging = false
        closing = false
        tapsAction = false
        velocity?.recycle()
        velocity = null
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

    /** Past the open width the row follows at a third of the finger, as UIKit's swipe without a full swipe. */
    private fun rubberBand(offset: Float): Float {
        if (offset > 0f) return 0f
        val limit = -openOffset
        return if (offset >= limit) offset else limit + (offset - limit) / 3f
    }

    private fun settle(row: RecyclerView.ViewHolder, to: Float, animated: Boolean) {
        animator?.cancel()
        val view = row.itemView
        if (!animated || view.translationX == to) {
            view.translationX = to
            list.invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(view.translationX, to).apply {
            duration = SETTLE_MS
            interpolator = PathInterpolator(0.25f, 1f, 0.5f, 1f)
            addUpdateListener {
                view.translationX = it.animatedValue as Float
                list.invalidate()
            }
            start()
        }
    }

    /** Where the capsule is drawn behind [view] at its current offset; null while nothing is revealed. */
    private fun capsuleRect(view: View): RectF? {
        val revealed = -view.translationX
        if (revealed <= 0f) return null
        val d = density
        val width = min(CAPSULE_WIDTH * d, max(0f, revealed - 2 * MARGIN * d))
        if (width <= 0f) return null
        val height = CAPSULE_HEIGHT * d
        val group = height + TITLE_GAP * d + TITLE_LINE * d
        val top = view.top + view.translationY + (view.height - group) / 2
        val centre = view.right - revealed / 2
        capsule.set(centre - width / 2, top, centre + width / 2, top + height)
        return capsule
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.translationX >= 0f) continue
            val holder = parent.getChildViewHolder(child) ?: continue
            val action = actionFor(holder) ?: continue
            val rect = capsuleRect(child) ?: continue
            drawAction(c, action, RectF(rect), child)
        }
    }

    private fun drawAction(c: Canvas, action: Action, rect: RectF, row: View) {
        val d = density
        val progress = (rect.width() / (CAPSULE_WIDTH * d)).coerceIn(0f, 1f)
        val alpha = (255 * progress).toInt()
        fill.color = Theme.color(Palette.statusError)
        fill.alpha = alpha
        c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)

        IconCatalog.glyph(action.icon)?.let { glyph ->
            val layout = glyph.layout(ICON_SIZE, d.toDouble())
            glyph.path.toAndroidPath(scale = (layout.glyphScale * d).toFloat(), into = path)
            val size = layout.size
            glyphPaint.color = 0xFFFFFFFF.toInt()
            glyphPaint.alpha = alpha
            c.save()
            c.clipRect(rect)
            c.translate(rect.centerX() - (size.width * d).toFloat() / 2, rect.centerY() - (size.height * d).toFloat() / 2)
            val box = layout.frame(com.slopus.happy.layout.Rect(0.0, 0.0, size.width, size.height), d.toDouble())
            c.translate((box.x * d).toFloat(), (box.y * d).toFloat())
            c.drawPath(path, glyphPaint)
            c.restore()
        }

        val style = TITLE.scaled(Theme.textScale)
        titlePaint.typeface = Fonts.typeface(style.face)
        titlePaint.textSize = (style.size * d).toFloat()
        titlePaint.color = Theme.color(Palette.textSecondary)
        titlePaint.alpha = alpha
        val baseline = rect.bottom + TITLE_GAP * d - titlePaint.fontMetrics.ascent
        c.save()
        c.clipRect(row.right + row.translationX, rect.bottom, row.right.toFloat(), rect.bottom + (TITLE_GAP + TITLE_LINE * 2) * d)
        c.drawText(action.title, rect.centerX(), baseline, titlePaint)
        c.restore()
    }

    private companion object {
        // Measured from happy-ios's home row swipe (iOS 26's capsule action), in dp.
        const val OPEN_WIDTH = 117f
        const val CAPSULE_WIDTH = 95f
        const val CAPSULE_HEIGHT = 49f
        const val MARGIN = 11f
        const val TITLE_GAP = 4f
        const val TITLE_LINE = 15f
        const val ICON_SIZE = 20.0
        const val SETTLE_MS = 300L
        val TITLE = TextStyle(FontFace.System, 12.0, 15.0)
    }
}
