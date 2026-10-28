package com.slopus.happy.ui.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.ProgressBar
import com.slopus.happy.icons.IconCatalog
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.toAndroidPath
import kotlin.math.roundToInt

/** Draws a [ChatRowPlan]'s items. Nothing is measured here. */
internal class ChatPlanPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val elapsedPaints = HashMap<ChatTextEnv, android.text.TextPaint>()

    fun draw(canvas: Canvas, items: List<ChatDrawItem>, clip: Rect? = null) {
        val d = Theme.density
        for (item in items) {
            when (item) {
                is ChatDrawItem.Box -> drawBox(canvas, item.box, d)
                is ChatDrawItem.Text -> {
                    val f = item.frame
                    if (clip != null && (f.maxY < clip.minY || f.minY > clip.maxY)) continue
                    canvas.save()
                    canvas.translate((f.minX * d).toFloat(), (f.minY * d).toFloat())
                    canvas.clipRect(0f, 0f, (f.width * d).toFloat() + 1, (f.height * d).toFloat() + 1)
                    if (item.alpha < 1) {
                        canvas.saveLayerAlpha(0f, 0f, (f.width * d).toFloat() + 1, (f.height * d).toFloat() + 1, (item.alpha * 255).roundToInt())
                        item.layout.draw(canvas)
                        canvas.restore()
                    } else {
                        item.layout.draw(canvas)
                    }
                    canvas.restore()
                }
                is ChatDrawItem.Glyph -> {
                    val icon = IconCatalog.glyph(item.icon) ?: continue
                    val scale = d.toDouble()
                    val layout = icon.layout(item.size, scale)
                    val box = layout.frame(item.frame, scale)
                    icon.path.toAndroidPath(scale = (layout.glyphScale * scale).toFloat(), into = path)
                    glyph.color = Theme.color(item.tint)
                    canvas.save()
                    canvas.translate((box.x * d).toFloat(), (box.y * d).toFloat())
                    canvas.drawPath(path, glyph)
                    canvas.restore()
                }
                is ChatDrawItem.Elapsed -> {
                    val paint = elapsedPaints.getOrPut(item.env) { TextLayouts.paint(ChatTextStyles.toolElapsed, item.env, 0) }
                    paint.color = Theme.color(Palette.textSecondary)
                    val seconds = (System.currentTimeMillis() - item.since).coerceAtLeast(0) / 1000.0
                    val text = String.format("%.1fs", seconds)
                    val f = item.frame
                    val width = paint.measureText(text)
                    val fm = paint.fontMetrics
                    val baseline = (f.midY * d).toFloat() - (fm.ascent + fm.descent) / 2
                    canvas.drawText(text, (f.maxX * d).toFloat() - width, baseline, paint)
                }
                is ChatDrawItem.Pulse -> {
                    // iOS's opacity animation: 0.18 to 0.52 and back, 0.72s each way, ease in-out.
                    val t = (SystemClock.uptimeMillis() % PULSE_PERIOD_MS) / PULSE_HALF_MS.toDouble()
                    val p = if (t <= 1) t else 2 - t
                    val eased = (1 - kotlin.math.cos(Math.PI * p)) / 2
                    drawBox(canvas, item.box.copy(alpha = 0.18 + (0.52 - 0.18) * eased), d)
                }
                is ChatDrawItem.Scroller, is ChatDrawItem.Spinner -> Unit
            }
        }
    }

    private fun drawBox(canvas: Canvas, box: ChatBox, d: Float) {
        val f = box.frame
        rect.set((f.minX * d).toFloat(), (f.minY * d).toFloat(), (f.maxX * d).toFloat(), (f.maxY * d).toFloat())
        val r = (box.radius * d).toFloat()
        val alpha = (box.alpha * 255).roundToInt()
        box.fill?.let {
            fill.color = Theme.color(it)
            fill.alpha = (fill.alpha * box.alpha).roundToInt()
            if (box.topCornersOnly && r > 0) {
                path.reset()
                path.addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
                canvas.drawPath(path, fill)
            } else {
                canvas.drawRoundRect(rect, r, r, fill)
            }
        }
        val border = box.border
        if (border != null && box.borderWidth > 0) {
            val w = (box.borderWidth * d).toFloat()
            stroke.strokeWidth = w
            stroke.color = Theme.color(border)
            stroke.alpha = (stroke.alpha * alpha / 255f).roundToInt()
            rect.inset(w / 2, w / 2)
            canvas.drawRoundRect(rect, maxOf(0f, r - w / 2), maxOf(0f, r - w / 2), stroke)
        }
    }

    fun hasElapsed(items: List<ChatDrawItem>) = items.any { it is ChatDrawItem.Elapsed }

    fun hasPulse(items: List<ChatDrawItem>) = items.any { it is ChatDrawItem.Pulse }

    private companion object {
        const val PULSE_HALF_MS = 720L
        const val PULSE_PERIOD_MS = 2 * PULSE_HALF_MS
    }
}

/** Draws a plan that scrolls sideways (a code block's strip, a table's grid). */
internal class ChatPlanView(context: Context) : View(context) {
    private val painter = ChatPlanPainter()
    var plan: ChatRowPlan? = null
        set(value) { field = value; requestLayout(); invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = plan?.size
        setMeasuredDimension(size?.let { (it.width * Theme.density).roundToInt() } ?: 0, size?.let { (it.height * Theme.density).roundToInt() } ?: 0)
    }

    override fun onDraw(canvas: Canvas) {
        plan?.let { painter.draw(canvas, it.items) }
    }
}

/**
 * One chat row: draws its plan, hosts what scrolls sideways and the
 * spinners, and turns taps and long presses into the plan's actions
 * (happy-ios `ChatRowCell`).
 */
class ChatRowView(context: Context) : ViewGroup(context), ThemeAware {
    private val painter = ChatPlanPainter()
    var plan: ChatRowPlan? = null
        private set

    /** A tap or a long press on something with an action; the frame is the hit's, in the row. */
    var onAction: ((ChatRowAction, Rect, Boolean) -> Unit)? = null

    private val scrollers = ArrayList<Pair<HorizontalScrollView, ChatDrawItem.Scroller>>()
    private val spinners = ArrayList<Pair<ProgressBar, ChatDrawItem.Spinner>>()
    private var ticking = false
    /** A pulse redraws every frame; a timer every 100ms. */
    private var pulsing = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = hit(e, hold = false) != null || hit(e, hold = true) != null

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val hit = hit(e, hold = false) ?: return false
            playSoundEffect(android.view.SoundEffectConstants.CLICK)
            onAction?.invoke(hit.action, hit.frame, false)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val hit = hit(e, hold = true) ?: return
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onAction?.invoke(hit.action, hit.frame, true)
        }
    })

    init {
        setWillNotDraw(false)
        clipChildren = true
    }

    private fun hit(e: MotionEvent, hold: Boolean): ChatHit? {
        val x = px(e.x)
        val y = px(e.y)
        return plan?.hits?.firstOrNull { it.hold == hold && it.frame.contains(x, y) }
    }

    fun bind(plan: ChatRowPlan) {
        if (this.plan === plan) return
        this.plan = plan
        alpha = plan.alpha.toFloat()
        contentDescription = plan.accessibilityLabel
        for ((view, _) in scrollers) removeView(view)
        scrollers.clear()
        for ((view, _) in spinners) removeView(view)
        spinners.clear()
        for (item in plan.items) {
            when (item) {
                is ChatDrawItem.Scroller -> {
                    val content = ChatPlanView(context).apply { this.plan = item.plan }
                    val scroll = HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
                        addView(content)
                        if (item.radius > 0) {
                            outlineProvider = object : ViewOutlineProvider() {
                                override fun getOutline(view: View, outline: Outline) {
                                    outline.setRoundRect(0, 0, view.width, view.height, dp(item.radius))
                                }
                            }
                            clipToOutline = true
                        }
                    }
                    addView(scroll)
                    scrollers += scroll to item
                }
                is ChatDrawItem.Spinner -> {
                    val spinner = ProgressBar(context).apply {
                        isIndeterminate = true
                        indeterminateTintList = ColorStateList.valueOf(Theme.color(item.tint))
                    }
                    addView(spinner)
                    spinners += spinner to item
                }
                else -> Unit
            }
        }
        pulsing = painter.hasPulse(plan.items)
        ticking = pulsing || painter.hasElapsed(plan.items)
        requestLayout()
        invalidate()
        if (ticking) tick()
    }

    private fun tick() {
        if (!ticking || !isAttachedToWindow) return
        invalidate()
        if (pulsing) postOnAnimation { tick() } else postOnAnimationDelayed({ tick() }, 100)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ticking) tick()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = plan?.size
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, size?.let { (it.height * Theme.density).roundToInt() } ?: 0)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((view, item) in scrollers) view.setFrame(item.frame)
        for ((view, item) in spinners) {
            // The platform's spinner at 0.8 of the glyph's box, as iOS scales its own.
            val f = item.frame
            val side = f.width * 0.8
            view.setFrame(Rect(f.midX - side / 2, f.midY - side / 2, side, side))
        }
    }

    override fun onDraw(canvas: Canvas) {
        plan?.let { painter.draw(canvas, it.items) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    override fun applyTheme() {
        for ((view, item) in spinners) view.indeterminateTintList = ColorStateList.valueOf(Theme.color(item.tint))
        invalidate()
        for ((view, _) in scrollers) view.getChildAt(0)?.invalidate()
    }

    override fun shouldDelayChildPressedState() = false

    @Suppress("unused")
    private val clock = SystemClock::uptimeMillis
}
