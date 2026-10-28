package com.slopus.happy.ui.text

import android.graphics.Paint
import android.text.Layout
import android.text.Spanned
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.LineHeightSpan
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.PixelGrid
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextScale
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.ui.theme.Fonts
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where text is measured and laid out: one `StaticLayout` per text, style,
 * width and scale, built on any thread (happy-ios measures off the main
 * thread and so does this). A [TextStyle]'s fixed line height is exact, as
 * TextKit's minimum and maximum line height make it on iOS: every line is
 * that tall, with the glyphs placed as happy-ios places them
 * (`TextStyle.attributes`: the slack above the glyphs, a quarter of it moved
 * under them as a baseline offset).
 */
object TextLayouts {
    /** Inputs that decide a layout's pixels. Density and scale are explicit so this never reads the UI. */
    data class Env(val density: Float, val scale: TextScale)

    fun paint(style: TextStyle, env: Env, color: Int): TextPaint {
        val scaled = style.scaled(env.scale)
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.typeface(style.face)
            textSize = (scaled.size * env.density).toFloat()
            this.color = color
            if (style.letterSpacing != 0.0 && scaled.size > 0) letterSpacing = (style.letterSpacing / style.size).toFloat()
            isSubpixelText = true
        }
    }

    /** One line of [style] as drawn, in dp. */
    fun lineHeight(style: TextStyle, env: Env): Double {
        val scaled = style.scaled(env.scale)
        scaled.lineHeight?.let { return it }
        val fm = paint(style, env, 0).fontMetricsInt
        return ceil((fm.descent - fm.ascent) / env.density.toDouble())
    }

    fun build(
        text: CharSequence,
        style: TextStyle,
        widthDp: Double,
        env: Env,
        color: Int,
        maxLines: Int = 0,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    ): StaticLayout {
        val paint = paint(style, env, color)
        val shown = if (style.uppercase) text.toString().uppercase() else text
        val scaled = style.scaled(env.scale)
        val source: CharSequence = scaled.lineHeight?.let { lineHeight ->
            val lineHeightPx = (lineHeight * env.density).roundToInt()
            val fm = paint.fontMetricsInt
            val fontHeight = fm.descent - fm.ascent
            val slack = lineHeightPx - fontHeight
            val baseline = lineHeightPx - fm.descent - slack / 4
            val spannable = if (shown is Spanned) SpannableString(shown) else SpannableString(shown)
            spannable.setSpan(ExactLineHeight(lineHeightPx, baseline), 0, spannable.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            spannable
        } ?: shown
        val width = max(1, ceil(widthDp * env.density).toInt())
        val builder = StaticLayout.Builder.obtain(source, 0, source.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (maxLines > 0) {
            builder.setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
        }
        return builder.build()
    }

    /**
     * The size [text] needs offered [widthDp], in dp, as a label's
     * `sizeThatFits`: the widest line, and the lines' height, each up to the
     * device pixel. A single-line label reports its whole width, as UIKit's does.
     */
    fun measure(
        text: CharSequence,
        style: TextStyle,
        widthDp: Double,
        env: Env,
        maxLines: Int = 0,
    ): Size {
        if (maxLines == 1) {
            val paint = paint(style, env, 0)
            val shown = if (style.uppercase) text.toString().uppercase() else text
            val w = Layout.getDesiredWidth(shown, paint) / env.density
            return Size(PixelGrid.ceil(w.toDouble(), env.density.toDouble()), lineHeight(style, env))
        }
        val layout = build(text, style, widthDp, env, 0, maxLines)
        return size(layout, env)
    }

    fun size(layout: StaticLayout, env: Env): Size {
        var widest = 0f
        for (i in 0 until layout.lineCount) widest = max(widest, layout.getLineWidth(i))
        val d = env.density.toDouble()
        return Size(PixelGrid.ceil(widest / d, d), PixelGrid.ceil(layout.height / d, d))
    }

    fun measurable(text: CharSequence, style: TextStyle, env: Env, maxLines: Int = 0) =
        Measurable { width -> measure(text, style, width, env, maxLines) }

    private class ExactLineHeight(private val height: Int, private val baseline: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence?, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            fm.ascent = -baseline
            fm.top = fm.ascent
            fm.descent = height - baseline
            fm.bottom = fm.descent
        }
    }

    fun clampWidth(size: Size, width: Double) = Size(min(size.width, width), size.height)
}
