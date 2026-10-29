package com.slopus.happy.ui.components

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.view.View
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.px

/**
 * A label that renders a [TextStyle] in a theme colour (happy-ios
 * `StyledLabel`). It draws a `StaticLayout` made for its frame's width;
 * [measure] answers the size for a width without touching the view's state.
 * Limited lines truncate with an ellipsis; unlimited ones wrap.
 */
class Label(
    context: Context,
    style: TextStyle,
    color: ColorToken,
    maxLines: Int = 0,
    alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
) : View(context), ThemeAware {
    var text: CharSequence = ""
        set(value) {
            if (field.toString() == value.toString() && field::class == value::class) return
            field = value
            contentDescription = value
            invalidateText()
        }

    var style: TextStyle = style
        set(value) { if (field != value) { field = value; invalidateText() } }

    var color: ColorToken = color
        set(value) { if (field != value) { field = value; invalidateText() } }

    var maxLines: Int = maxLines
        set(value) { if (field != value) { field = value; invalidateText() } }

    var alignment: Layout.Alignment = alignment
        set(value) { if (field != value) { field = value; invalidateText() } }

    /** Where limited lines are cut: at the end, or in the middle (UIKit's byTruncatingMiddle). */
    var ellipsize: android.text.TextUtils.TruncateAt = android.text.TextUtils.TruncateAt.END
        set(value) { if (field != value) { field = value; invalidateText() } }

    private var layout: StaticLayout? = null
    private var layoutWidth = -1
    private var layoutKey: Any? = null

    private val env get() = TextLayouts.Env(Theme.density, Theme.textScale)

    /** The label's size offered [width] dp. A single-line label reports its whole text width. */
    fun measure(width: Double): Size = TextLayouts.measure(text, style, width, env, maxLines)

    val measurable: Measurable get() = Measurable { measure(it) }

    /** One line of this label's style, as drawn. */
    val lineHeight: Double get() = TextLayouts.lineHeight(style, env)

    private fun invalidateText() {
        layout = null
        requestLayout()
        invalidate()
    }

    override fun applyTheme() {
        layout = null
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY && MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec))
            return
        }
        val size = measure(px(w))
        // Rounded up: a pixel short and the one line ellipsizes.
        setMeasuredDimension(kotlin.math.ceil(size.width * Theme.density).toInt(), kotlin.math.ceil(size.height * Theme.density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || text.isEmpty()) return
        val key = Triple(Theme.isDark, Theme.textScale, Theme.density)
        var l = layout
        if (l == null || layoutWidth != width || layoutKey != key) {
            l = TextLayouts.build(text, style, px(width), env, Theme.color(color), maxLines, alignment, ellipsize)
            layout = l
            layoutWidth = width
            layoutKey = key
        }
        l.draw(canvas)
    }

    /** The laid-out text, for hit testing links. */
    val textLayout: StaticLayout? get() = layout
}
