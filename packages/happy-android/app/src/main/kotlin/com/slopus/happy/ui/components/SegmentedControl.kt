package com.slopus.happy.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.slopus.happy.layout.FontFace
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.ui.nav.ScreenStack
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame

/**
 * Where happy-ios uses a UISegmentedControl: Material's segmented button,
 * an outlined pill split into equal segments, the chosen one filled. The
 * fill slides to a segment chosen by touch.
 */
class SegmentedControl(context: Context, items: List<String>) : ManualLayout(context), ThemeAware {
    var selectedIndex = 0
        set(value) {
            if (field == value) return
            field = value
            slide(value.toFloat())
            labels.forEachIndexed { i, label -> label.isSelected = i == value }
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED)
        }

    /** A segment chosen by touch, after [selectedIndex] changed. */
    var onChange: ((Int) -> Unit)? = null

    private var titles = items.toList()
    private val labels = items.map(::makeLabel).toMutableList()
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var position = 0f
    private var animator: ValueAnimator? = null

    init {
        require(items.isNotEmpty())
        labels.forEach(::addView)
        setWillNotDraw(false)
        isFocusable = true
        applyTheme()
    }

    private fun makeLabel(title: String) = Label(context, LABEL, Palette.text, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER).apply { text = title }

    /** The computer's capabilities can change while the dock is open. */
    fun setItems(items: List<String>) {
        require(items.isNotEmpty())
        if (items == titles) return
        val selected = titles.getOrNull(selectedIndex)
        titles = items.toList()
        labels.forEach(::removeView)
        labels.clear()
        labels.addAll(items.map(::makeLabel))
        labels.forEach(::addView)
        selectedIndex = items.indexOf(selected).coerceAtLeast(0)
        animator?.cancel()
        animator = null
        position = selectedIndex.toFloat()
        labels.forEachIndexed { index, label -> label.isSelected = index == selectedIndex }
        requestLayout()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        position = selectedIndex.toFloat()
        super.onDetachedFromWindow()
    }

    private fun slide(to: Float) {
        animator?.cancel()
        if (!isAttachedToWindow) {
            position = to
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(position, to).apply {
            duration = 200
            interpolator = ScreenStack.EASE
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val segment = width / labels.size
        labels.forEachIndexed { i, label ->
            val h = label.lineHeight
            label.setFrame(Rect(segment * i + 8, (height - h) / 2, segment - 16, h))
        }
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = dp(1.0)
        val radius = height / 2f
        val segment = width.toFloat() / labels.size
        rect.set(segment * position, 0f, segment * (position + 1), height.toFloat())
        canvas.save()
        canvas.clipRect(rect)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.restore()
        rect.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        canvas.drawRoundRect(rect, radius, radius, outline)
        for (i in 1 until labels.size) canvas.drawLine(segment * i, 0f, segment * i, height.toFloat(), outline)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val index = (event.x / (width.toFloat() / labels.size)).toInt().coerceIn(0, labels.size - 1)
            if (index != selectedIndex) {
                selectedIndex = index
                performClick()
                onChange?.invoke(index)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.RadioGroup"
        info.contentDescription = labels.getOrNull(selectedIndex)?.text
    }

    override fun applyTheme() {
        outline.color = Theme.color(Palette.textSecondary)
        outline.strokeWidth = dp(1.0)
        fill.color = Theme.color(Palette.surfaceSelected)
        invalidate()
    }

    companion object {
        val LABEL = TextStyle(FontFace.SemiBold, 14.0, 20.0)
    }
}
