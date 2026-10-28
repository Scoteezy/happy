package com.slopus.happy.layout.diff

import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextScale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Ported from happy-ios HappyLayout/Diff/DiffLayout.swift.

/**
 * components/diff/DiffMetrics.tsx: the mono font's size, line and advance.
 * IBM Plex Mono is 0.6 em wide, the first-frame estimate; views pass the
 * measured advance once they have it.
 *
 * At a text size the font and its line grow by the multiplier, and the
 * advance is measured at the size drawn, so the gutter and code widths grow
 * with the text; the gutter's padding and the code's inset stay fixed. A row
 * is the text's line, up to the screen's next pixel.
 */
data class DiffMetrics(
    /** The designed font size (DiffFileView's `fontSize`). */
    val fontSize: Double = DEFAULT_FONT_SIZE,
    /** The text size the rows are drawn at. */
    val scale: TextScale = TextScale.Standard,
    /** The screen's pixels per dp, which a row's height is rounded up to; 0 leaves it unrounded. */
    val displayScale: Double = 0.0,
    /** The measured advance; null is 0.6 em of the size drawn. */
    private val measuredCharWidth: Double? = null,
) {
    /** The rows' text line: `Math.round(fontSize * 1.5)`, times the multiplier, unrounded. */
    val textLineHeight: Double = jsRound(fontSize * 1.5) * scale.multiplier

    /** One row: the text's line, up to the screen's next pixel. */
    val lineHeight: Double = if (displayScale > 0) ceil(textLineHeight * displayScale) / displayScale else textLineHeight

    val charWidth: Double = measuredCharWidth ?: (fontSize * scale.multiplier * NOMINAL_RATIO)

    /** The font size drawn. */
    val drawnFontSize: Double get() = fontSize * scale.multiplier

    fun gutterWidth(maxLineNo: Int, showLineNumbers: Boolean) = gutterWidth(charWidth, maxLineNo, showLineNumbers)

    /** The marker column: `ceil(charWidth * 2)`. */
    val markerWidth: Double get() = ceil(charWidth * 2)

    companion object {
        /** `DEFAULT_DIFF_FONT_SIZE` on a phone. */
        const val DEFAULT_FONT_SIZE = 12.0
        const val NOMINAL_RATIO = 0.6

        /**
         * MonoProbe's check on a measured advance: a font that hasn't loaded
         * measures outside 0.35 to 1.2 em of the size drawn, and keeps the estimate.
         */
        fun acceptsAdvance(advance: Double, drawnFontSize: Double) = advance >= drawnFontSize * 0.35 && advance <= drawnFontSize * 1.2

        /** layout.ts `gutterWidthFor`: the +/- column always, line numbers sized by the widest one (two digits at least). */
        fun gutterWidth(charWidth: Double, maxLineNo: Int, showLineNumbers: Boolean): Double {
            val markerColumns = 2
            val digits = if (showLineNumbers) max(2, max(1, maxLineNo).toString().length) else 0
            return ceil((digits + markerColumns) * charWidth) + if (showLineNumbers) 12 else 4
        }

        /** JavaScript's `Math.round`: halves go up. */
        private fun jsRound(value: Double) = floor(value + 0.5)
    }
}

/**
 * DiffFileView.tsx's scroll layout: code under a horizontal scroll, the line
 * numbers and markers in a gutter pinned over its left edge, every row one
 * line tall. Then the "Show N more lines" bar when rows are held back.
 *
 * Frames for the code are in the code canvas's coordinates (its x = 0 is the
 * gutter's right edge); the rest are in the view's.
 */
class DiffFileLayout(val input: Input) {
    object Metrics {
        /** Code text starts this far into its row. */
        const val codePaddingLeft = 6.0
        /** The gutter's hunk dots. */
        const val hunkDotsPaddingLeft = 6.0
        /** Columns of slack past the longest line, plus a little more. */
        const val slackColumns = 2.0
        const val slackWidth = 8.0
        /** The "show more" bar: a 1dp rule, 10 above and below its text. */
        const val showMoreBorder = 1.0
        const val showMorePadding = 10.0
        /** While "show more" waits for colour: a small spinner, 8 before the text. */
        const val showMoreSpinner = 20.0
        const val showMoreGap = 8.0
    }

    data class Input(
        val width: Double,
        val metrics: DiffMetrics = DiffMetrics(),
        /** Rows shown (after the collapse). */
        val rowCount: Int,
        /** Rows held back for "show more". */
        val hiddenCount: Int = 0,
        val maxColumns: Int,
        val maxLineNo: Int,
        val showLineNumbers: Boolean = true,
        val paddingVertical: Double = 0.0,
        /** One line of the bar's semibold 13. */
        val showMoreTextHeight: Double,
    )

    val gutterWidth: Double
    /** What the longest line needs. */
    val codeWidth: Double
    /** The code canvas: at least the room right of the gutter. */
    val contentWidth: Double
    /** Whether the code is wider than its room and scrolls. */
    val scrollable: Boolean
    /** The horizontal scroll view, with [scrollContentSize] inside. */
    val scroll: Rect
    val scrollContentSize: Size
    /** The canvas inside the scroll content, after the gutter's room. */
    val canvas: Rect
    /** The pinned gutter, over the rows. */
    val gutter: Rect
    val showMore: Rect?
    val height: Double

    init {
        val m = Metrics
        val metrics = input.metrics
        val width = max(0.0, input.width)
        gutterWidth = metrics.gutterWidth(input.maxLineNo, input.showLineNumbers)
        codeWidth = ceil((input.maxColumns + m.slackColumns) * metrics.charWidth) + m.slackWidth
        val room = max(0.0, width - gutterWidth)
        contentWidth = max(codeWidth, room)
        scrollable = codeWidth > room

        val rowsHeight = max(0, input.rowCount) * metrics.lineHeight
        val bodyHeight = rowsHeight + input.paddingVertical * 2
        scroll = Rect(0.0, 0.0, width, bodyHeight)
        scrollContentSize = Size(gutterWidth + contentWidth, bodyHeight)
        canvas = Rect(gutterWidth, input.paddingVertical, contentWidth, rowsHeight)
        gutter = Rect(0.0, input.paddingVertical, gutterWidth, rowsHeight)

        if (input.hiddenCount > 0) {
            val bar = Rect(0.0, bodyHeight, width, m.showMoreBorder + m.showMorePadding * 2 + input.showMoreTextHeight)
            showMore = bar
            height = bar.maxY
        } else {
            showMore = null
            height = bodyHeight
        }
    }

    val lineHeight: Double get() = input.metrics.lineHeight

    /** Row [index] in the canvas. */
    fun row(index: Int) = Rect(0.0, index * lineHeight, contentWidth, lineHeight)

    fun gutterRow(index: Int) = Rect(0.0, index * lineHeight, gutterWidth, lineHeight)

    /** Rows that meet `minY until maxY`, in the canvas's coordinates. */
    fun rows(minY: Double, maxY: Double): IntRange {
        val count = max(0, input.rowCount)
        if (count == 0 || maxY <= minY || lineHeight <= 0) return IntRange.EMPTY
        val first = min(count, max(0, floor(minY / lineHeight).toInt()))
        val last = min(count, max(first, ceil(maxY / lineHeight).toInt()))
        return first until last
    }

    /** The row at [y] in the canvas, if any. */
    fun rowIndex(y: Double): Int? {
        if (y < 0 || lineHeight <= 0) return null
        val index = floor(y / lineHeight).toInt()
        return if (index < input.rowCount) index else null
    }

    // In-row frames, relative to the row.

    /** A line's code text, past the 6dp padding, as wide as it needs. */
    fun codeText(width: Double) = Rect(Metrics.codePaddingLeft, 0.0, width, lineHeight)

    /** The gutter's number: the room left of the marker, right-aligned. */
    val lineNumber: Rect?
        get() = if (!input.showLineNumbers) null else Rect(0.0, 0.0, max(0.0, gutterWidth - input.metrics.markerWidth), lineHeight)

    /** The gutter's +/−, centred in two columns, after the number. */
    val marker: Rect
        get() {
            val width = input.metrics.markerWidth
            val x = if (input.showLineNumbers) max(0.0, gutterWidth - width) else 0.0
            return Rect(x, 0.0, width, lineHeight)
        }

    /** The gutter's `···` on a hunk row. */
    val hunkDots: Rect get() = Rect(Metrics.hunkDotsPaddingLeft, 0.0, max(0.0, gutterWidth - Metrics.hunkDotsPaddingLeft), lineHeight)

    /** The bar's text, centred, in the bar's coordinates. */
    fun showMoreText(textWidth: Double): Rect? = showMoreContent(textWidth, busy = false)?.first

    /**
     * The bar's text and, while it is busy, the spinner before it: the pair
     * centred, the spinner on the text's middle.
     */
    fun showMoreContent(textWidth: Double, busy: Boolean): Pair<Rect, Rect?>? {
        val bar = showMore ?: return null
        val m = Metrics
        val lead = if (busy) m.showMoreSpinner + m.showMoreGap else 0.0
        val width = min(textWidth, max(0.0, bar.width - lead))
        val x = (bar.width - width - lead) / 2
        val text = Rect(x + lead, m.showMoreBorder + m.showMorePadding, width, input.showMoreTextHeight)
        val spinner = if (busy) Rect(x, text.midY - m.showMoreSpinner / 2, m.showMoreSpinner, m.showMoreSpinner) else null
        return text to spinner
    }
}

/** DiffChunk: its files' views stacked. */
class DiffChunkLayout(inputs: List<DiffFileLayout.Input>) {
    val files: List<DiffFileLayout> = inputs.map(::DiffFileLayout)
    val frames: List<Rect>
    val size: Size

    init {
        var y = 0.0
        var width = 0.0
        frames = files.map { file ->
            Rect(0.0, y, file.input.width, file.height).also {
                y += file.height
                width = max(width, file.input.width)
            }
        }
        size = Size(width, y)
    }

    companion object {
        /** Rows before "show more" in a chat. */
        const val COLLAPSE_AFTER = 80
    }
}

/**
 * The horizontal window of a long code line worth drawing: the visible part
 * of the canvas plus a screen on each side, snapped to [STEP] so small
 * scrolls don't redraw.
 */
object DiffTextWindow {
    const val STEP = 512.0

    /** The span of `0 until textWidth` to draw when `visible` of it shows. */
    fun span(textWidth: Double, visible: ClosedFloatingPointRange<Double>, margin: Double): ClosedFloatingPointRange<Double>? {
        if (textWidth <= 0) return null
        val lower = max(0.0, floor((visible.start - margin) / STEP) * STEP)
        val upper = min(textWidth, ceil((visible.endInclusive + margin) / STEP) * STEP)
        if (upper <= lower) return null
        return lower..upper
    }
}
