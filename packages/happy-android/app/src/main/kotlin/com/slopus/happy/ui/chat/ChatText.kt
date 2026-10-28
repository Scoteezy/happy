package com.slopus.happy.ui.chat

import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.MetricAffectingSpan
import android.text.style.UpdateAppearance
import com.slopus.happy.chat.markdown.MarkdownSpan
import com.slopus.happy.chat.markdown.SpanStyle
import com.slopus.happy.chat.markdown.isHttpMarkdownLink
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.FontFace
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Fonts
import com.slopus.happy.ui.theme.Theme
import kotlin.math.abs

// The chat's text (happy-ios Chat/Text/ChatText.swift): strings carry their
// colour as a theme token resolved when drawn, so a row planned off the main
// thread draws right in either appearance; markdown spans swap the face;
// text is laid out once per width and then only drawn.

/** A run's colour: a theme token, resolved each time the text is drawn. */
class TokenColorSpan(val token: ColorToken, private val alpha: Float = 1f) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        val color = Theme.color(token)
        tp.color = if (alpha >= 1f) color else (color and 0x00FFFFFF) or (((color ushr 24) * alpha).toInt() shl 24)
    }
}

/** A run in another face (and size) than the paragraph's: bold, italic, inline code. */
class FaceSpan(private val face: FontFace, private val sizePx: Float) : MetricAffectingSpan() {
    override fun updateMeasureState(tp: TextPaint) = apply(tp)
    override fun updateDrawState(tp: TextPaint) = apply(tp)

    private fun apply(tp: TextPaint) {
        tp.typeface = Fonts.typeface(face)
        tp.textSize = sizePx
    }
}

/** A tappable link: underlined, carrying its URL. */
class ChatLinkSpan(val url: String) : CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) {
        tp.isUnderlineText = true
    }
}

/** What every row's text is drawn at: the screen's density and the person's text size. */
typealias ChatTextEnv = TextLayouts.Env

object ChatStrings {
    /** [text] in [style], coloured [color]. Upper-cased here when the style says so. */
    fun string(text: String, color: ColorToken, style: TextStyle? = null, alpha: Float = 1f): CharSequence {
        val shown = if (style?.uppercase == true) text.uppercase() else text
        val builder = SpannableStringBuilder(shown)
        builder.setSpan(TokenColorSpan(color, alpha), 0, builder.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return builder
    }

    /**
     * Markdown spans as one paragraph in [style] (`RenderSpans`): bold and
     * italic swap the face, inline code takes the mono face at the code
     * size on the paragraph's line, links are underlined and carry their URL.
     */
    fun spans(spans: List<MarkdownSpan>, style: TextStyle, color: ColorToken, env: ChatTextEnv): CharSequence {
        val builder = SpannableStringBuilder()
        for (span in spans) {
            val start = builder.length
            builder.append(span.text)
            val end = builder.length
            if (end == start) continue
            val face = when {
                SpanStyle.Code in span.styles -> ChatTextStyles.markdownCode
                SpanStyle.Bold in span.styles || SpanStyle.Semibold in span.styles -> style.copy(face = strongFace(style.face))
                SpanStyle.Italic in span.styles -> style.copy(face = emphasisFace(style.face))
                else -> null
            }
            if (face != null) {
                val size = face.scaled(env.scale).size * env.density
                builder.setSpan(FaceSpan(face.face, size.toFloat()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val url = span.url
            if (url != null && isHttpMarkdownLink(url)) {
                builder.setSpan(ChatLinkSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        builder.setSpan(TokenColorSpan(color), 0, builder.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return builder
    }

    private fun strongFace(face: FontFace) = when (face) {
        FontFace.Mono, FontFace.MonoItalic -> FontFace.MonoSemiBold
        FontFace.System, FontFace.SystemItalic, FontFace.SystemMedium -> FontFace.SystemSemiBold
        else -> FontFace.SemiBold
    }

    private fun emphasisFace(face: FontFace) = when (face) {
        FontFace.Mono, FontFace.MonoSemiBold -> FontFace.MonoItalic
        FontFace.System, FontFace.SystemMedium, FontFace.SystemSemiBold -> FontFace.SystemItalic
        else -> FontFace.Italic
    }
}

/**
 * One piece of text measured at whatever widths a layout asks for. The
 * layout that ends up placed is picked afterwards by its frame, so the text
 * that is drawn is the text that was measured. Built and measured off the
 * main thread; drawn on it.
 */
class MeasuredText(
    val text: CharSequence,
    val style: TextStyle,
    val env: ChatTextEnv,
    val maxLines: Int = 0,
    private val alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
) {
    private class Entry(val width: Double, val layout: StaticLayout, val size: Size)

    private val layouts = ArrayList<Entry>(2)

    val measurable: Measurable get() = Measurable { width -> entry(width).size }

    @Synchronized
    private fun entry(width: Double): Entry {
        layouts.firstOrNull { it.width == width }?.let { return it }
        val layout = TextLayouts.build(text, style, width, env, 0, maxLines, alignment)
        val size = if (text.isEmpty()) Size(0.0, 0.0) else TextLayouts.size(layout, env)
        val single = if (maxLines == 1 && text.isNotEmpty()) {
            // A single line reports its whole width, as a label's does; the layout clamps it.
            Size(minOf(width, size.width), size.height)
        } else {
            size
        }
        return Entry(width, layout, single).also { layouts += it }
    }

    fun size(width: Double): Size = entry(width).size

    /**
     * The layout for a placed frame: one wrapped at the frame's width, or a
     * wider offer that came out the same height (a bubble hugging its text).
     */
    @Synchronized
    fun layout(frame: Rect): StaticLayout {
        layouts.firstOrNull { it.width == frame.width }?.let { return it.layout }
        if (alignment == Layout.Alignment.ALIGN_NORMAL) {
            layouts.firstOrNull { abs(it.size.height - frame.height) < 0.5 && it.size.width <= frame.width + 0.5 }?.let { return it.layout }
        }
        return entry(frame.width).layout
    }

    /** Unwrapped: one line per source line, as wide as the longest. */
    fun unwrapped(): Pair<StaticLayout, Size> = entry(100_000.0).let { it.layout to it.size }

    val string: String get() = text.toString()
}
