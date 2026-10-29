package com.slopus.happy.ui.stories

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.stories.StoriesTooltipLayout
import com.slopus.happy.layout.storiesTooltipGround
import com.slopus.happy.layout.storiesTooltipText
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.density
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import kotlin.math.ceil
import kotlin.math.max

/**
 * Telegram's one-time "Tap above to view stories from …" (happy-ios
 * `StoriesTooltipView`, `TooltipScreen`'s default style): a dark bubble
 * under the closed avatars, its arrow pointing up at them, springing out of
 * the arrow's tip. Laid over the whole screen, the first touch anywhere puts
 * it away, and goes on through to what is under it unless it [takesTouch].
 * Android has no backdrop blur for a view, so the bubble is its tint alone.
 */
class StoriesTooltipView(context: Context, private val text: String, private val takesTouch: Boolean = false, private val dark: Boolean? = null) : View(context) {
    var onDismiss: (() -> Unit)? = null
    private var tip = Point.Zero
    private var dismissed = false
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private var layout: StoriesTooltipLayout? = null
    private var textLayout: StaticLayout? = null
    private var grow = 1f
    private var animator: ValueAnimator? = null

    init {
        paint.textSize = (StoriesTooltipLayout.Metrics.fontSize * density).toFloat()
        contentDescription = text
        tag = "stories-tooltip"
    }

    /** Points at [tip], in this view's space (dp). */
    fun point(tip: Point) {
        if (tip == this.tip) return
        this.tip = tip
        layout = null
        invalidate()
    }

    private fun build(width: Int) = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()

    private fun measure(): StoriesTooltipLayout {
        layout?.let { return it }
        val made = StoriesTooltipLayout(tip, px(width)) { room ->
            val built = build(dp(room).toInt())
            var widest = 0f
            for (i in 0 until built.lineCount) widest = max(widest, built.getLineWidth(i))
            Size(ceil(px(widest)), ceil(px(built.height)))
        }
        textLayout = build(dp(made.text.width).toInt() + 1)
        layout = made
        return made
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layout = null
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        val l = measure()
        val dark = dark ?: Theme.isDark
        fill.color = Palette.storiesTooltipGround.resolve(dark).argb
        paint.color = Palette.storiesTooltipText.resolve(dark).argb
        val frame = l.bubble.union(l.arrow)
        // It grows from and shrinks into the arrow's tip.
        canvas.save()
        canvas.scale(grow, grow, dp(l.arrow.midX), dp(frame.minY))
        shape.reset()
        val r = dp(StoriesTooltipLayout.Metrics.cornerRadius)
        shape.addRoundRect(RectF(dp(l.bubble.minX), dp(l.bubble.minY), dp(l.bubble.maxX), dp(l.bubble.maxY)), r, r, Path.Direction.CW)
        arrow(shape, l.arrow)
        canvas.drawPath(shape, fill)
        canvas.translate(dp(l.text.minX), dp(l.text.minY))
        textLayout?.draw(canvas)
        canvas.restore()
    }

    /** Pointing up: a rounded tip, its sides curving out to a wide foot. */
    private fun arrow(path: Path, box: Rect) {
        val x0 = box.minX
        val y0 = box.minY
        val w = box.width
        val h = box.height
        fun x(v: Double) = dp(x0 + v)
        fun y(v: Double) = dp(y0 + v)
        path.moveTo(x(0.0), y(h))
        path.cubicTo(x(w * 0.22), y(h), x(w / 2 - 6), y(3.6), x(w / 2 - 2.6), y(1.1))
        path.quadTo(x(w / 2), y(-0.9), x(w / 2 + 2.6), y(1.1))
        path.cubicTo(x(w / 2 + 6), y(3.6), x(w * 0.78), y(h), x(w), y(h))
        path.close()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (dismissed) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) post { dismiss() }
        return takesTouch
    }

    /** Springs out of the arrow's tip, as Telegram's does. */
    fun show() {
        alpha = 0f
        grow = 0.01f
        animate().alpha(1f).setDuration(200).start()
        animator = ValueAnimator.ofFloat(0.01f, 1f).apply {
            duration = 400
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener { grow = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Shrinks back into the arrow's tip and goes. */
    fun dismiss() {
        if (dismissed) return
        dismissed = true
        onDismiss?.invoke()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(grow, 0.01f).apply {
            duration = 200
            addUpdateListener { grow = it.animatedValue as Float; invalidate() }
            start()
        }
        animate().alpha(0f).setDuration(200).withEndAction { (parent as? android.view.ViewGroup)?.removeView(this) }.start()
    }
}
