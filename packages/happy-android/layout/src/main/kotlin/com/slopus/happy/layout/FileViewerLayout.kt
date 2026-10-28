package com.slopus.happy.layout

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// Ported from happy-ios HappyLayout/FileViewerLayout.swift.

/**
 * app/(app)/session/[id]/file.tsx: the path header, the Diff / File
 * toggle when there is a diff, then the scrolling content.
 *
 *     16 [icon 20] 8 [path, wrapping] 16      0.5 rule below
 *     12  16 [ 16 Diff 16 ] 8 [ 16 File 16 ]  0.5 rule below
 *         (buttons 8 above and below their text, radius 8)
 *     content, padded 16
 */
class FileViewerLayout(input: Input) {
    object Metrics {
        const val headerPadding = 16.0
        const val iconSize = 20.0
        const val iconGap = 8.0
        /** borderBottomWidth 0.5 on both bars. */
        const val border = 0.5
        const val togglePaddingH = 16.0
        const val togglePaddingV = 12.0
        const val buttonPaddingH = 16.0
        const val buttonPaddingV = 8.0
        const val buttonRadius = 8.0
        const val buttonGap = 8.0
        const val contentPadding = 16.0
    }

    /** The Diff and File labels, when the toggle shows. */
    data class Toggle(val diff: Size, val file: Size)

    class Input(
        val size: Size,
        /** Where the screen starts below the navigation bar. */
        val top: Double,
        val path: Measurable,
        val toggle: Toggle?,
    )

    val header: Rect
    val icon: Rect
    val path: Rect
    val headerBorder: Rect
    val toggleBar: Rect?
    val diffButton: Rect?
    val diffLabel: Rect?
    val fileButton: Rect?
    val fileLabel: Rect?
    val toggleBorder: Rect?
    /** The scrolling content, below the bars. */
    val content: Rect

    init {
        val m = Metrics
        val width = max(0.0, input.size.width)
        val pathWidth = max(0.0, width - m.headerPadding * 2 - m.iconSize - m.iconGap)
        val pathSize = input.path.size(pathWidth)
        val rowHeight = max(m.iconSize, pathSize.height)
        val headerTop = input.top
        header = Rect(0.0, headerTop, width, rowHeight + m.headerPadding * 2 + m.border)
        icon = Rect(m.headerPadding, headerTop + m.headerPadding + (rowHeight - m.iconSize) / 2, m.iconSize, m.iconSize)
        path = Rect(
            m.headerPadding + m.iconSize + m.iconGap,
            headerTop + m.headerPadding + (rowHeight - pathSize.height) / 2,
            pathWidth,
            pathSize.height,
        )
        headerBorder = Rect(0.0, header.maxY - m.border, width, m.border)
        var y = header.maxY

        val toggle = input.toggle
        if (toggle != null) {
            val buttonHeight = max(toggle.diff.height, toggle.file.height) + m.buttonPaddingV * 2
            fun button(x: Double, label: Size): Pair<Rect, Rect> {
                val box = Rect(x, y + m.togglePaddingV, label.width + m.buttonPaddingH * 2, buttonHeight)
                val text = Rect(box.minX + m.buttonPaddingH, box.minY + (buttonHeight - label.height) / 2, label.width, label.height)
                return box to text
            }
            val diff = button(m.togglePaddingH, toggle.diff)
            val file = button(diff.first.maxX + m.buttonGap, toggle.file)
            diffButton = diff.first
            diffLabel = diff.second
            fileButton = file.first
            fileLabel = file.second
            val bar = Rect(0.0, y, width, buttonHeight + m.togglePaddingV * 2 + m.border)
            toggleBar = bar
            toggleBorder = Rect(0.0, bar.maxY - m.border, width, m.border)
            y = bar.maxY
        } else {
            toggleBar = null
            diffButton = null
            diffLabel = null
            fileButton = null
            fileLabel = null
            toggleBorder = null
        }
        content = Rect(0.0, y, width, max(0.0, input.size.height - y))
    }
}

/**
 * file.tsx's scrolling content: a column `layout.maxWidth` wide at most,
 * centred and padded 16, holding one row per line. DiffDisplay rows sit 8
 * in and 1 above and below their wrapped text, added and removed lines
 * with a 3pt bar on the left inside the row; SimpleSyntaxHighlighter's
 * lines are bare, one Text block wrapping each line in turn.
 */
class FileLinesLayout(
    width: Double,
    maxWidth: Double,
    val insets: RowInsets,
    /** Each line's measured height at `textWidth`. */
    textHeights: List<Double>,
    private val bars: List<Boolean>,
) {
    object Metrics {
        const val padding = 16.0
    }

    data class RowInsets(
        val horizontal: Double,
        val vertical: Double,
        /** The left bar's width, on rows that have one. */
        val bar: Double,
    ) {
        companion object {
            /** DiffDisplay's rows. */
            val diff = RowInsets(8.0, 1.0, 3.0)
            /** SimpleSyntaxHighlighter's lines. */
            val code = RowInsets(0.0, 0.0, 0.0)
        }
    }

    /** A row and how far down it (0 to 1). */
    data class Place(val row: Int, val fraction: Double)

    val width = max(0.0, width)
    /** The rows' left edge and width. */
    private val rowX: Double
    private val rowWidth: Double
    /** Each row's top, in content coordinates, plus the end. */
    private val offsets: DoubleArray
    val contentSize: Size

    init {
        val m = Metrics
        val column = column(width, maxWidth)
        rowX = column.first + m.padding
        rowWidth = max(0.0, column.second - m.padding * 2)
        offsets = DoubleArray(textHeights.size + 1)
        var y = m.padding
        offsets[0] = y
        textHeights.forEachIndexed { index, height ->
            y += height + insets.vertical * 2
            offsets[index + 1] = y
        }
        contentSize = Size(this.width, y + m.padding)
    }

    val count: Int get() = offsets.size - 1

    /** The rows together: the Text block a long press copies from. */
    val block: Rect get() = Rect(rowX, Metrics.padding, rowWidth, offsets[count] - Metrics.padding)

    fun row(index: Int) = Rect(rowX, offsets[index], rowWidth, offsets[index + 1] - offsets[index])

    private fun hasBar(index: Int) = index < bars.size && bars[index]

    /** The bar, in the row's coordinates; null for lines without one. */
    fun bar(index: Int): Rect? {
        if (!hasBar(index)) return null
        return Rect(0.0, 0.0, insets.bar, row(index).height)
    }

    /** The text, in the row's coordinates. */
    fun text(index: Int): Rect {
        val frame = row(index)
        val bar = if (hasBar(index)) insets.bar else 0.0
        return Rect(
            bar + insets.horizontal,
            insets.vertical,
            max(0.0, frame.width - bar - insets.horizontal * 2),
            max(0.0, frame.height - insets.vertical * 2),
        )
    }

    /**
     * The row at [y] and how far down it (0 to 1), so a layout made again
     * at another text size or width can put the same line at the top.
     * Null with no rows or above the first (the top padding, which keeps
     * its offset); past the last it is the last's end.
     */
    fun place(y: Double): Place? {
        if (count <= 0 || y < offsets[0]) return null
        if (y >= offsets[count]) return Place(count - 1, 1.0)
        val index = rows(y, y + 1).first
        val frame = row(index)
        return Place(index, if (frame.height > 0) (y - frame.minY) / frame.height else 0.0)
    }

    /** Where [place] is in this layout; null when its row is gone. */
    fun y(place: Place): Double? {
        if (place.row < 0 || place.row >= count) return null
        val frame = row(place.row)
        return frame.minY + place.fraction * frame.height
    }

    /** Rows that meet `minY until maxY`, found by binary search. */
    fun rows(minY: Double, maxY: Double): IntRange {
        if (count <= 0 || maxY <= minY) return 0 until 0
        fun firstRowEndingAfter(y: Double): Int {
            var low = 0
            var high = count
            while (low < high) {
                val mid = (low + high) / 2
                if (offsets[mid + 1] <= y) low = mid + 1 else high = mid
            }
            return low
        }
        fun firstRowStartingAtOrAfter(y: Double): Int {
            var low = 0
            var high = count
            while (low < high) {
                val mid = (low + high) / 2
                if (offsets[mid] < y) low = mid + 1 else high = mid
            }
            return low
        }
        val first = firstRowEndingAfter(minY)
        val end = max(first, firstRowStartingAtOrAfter(maxY))
        return first until end
    }

    companion object {
        /** The content column's left edge and width. */
        fun column(width: Double, maxWidth: Double): Pair<Double, Double> {
            val outer = max(0.0, width)
            val inner = min(outer, max(0.0, maxWidth))
            return (outer - inner) / 2 to inner
        }

        /** The text width a row offers its line, with or without the bar. */
        fun textWidth(width: Double, maxWidth: Double, insets: RowInsets, bar: Boolean): Double {
            val column = column(width, maxWidth).second
            return max(0.0, column - Metrics.padding * 2 - insets.horizontal * 2 - if (bar) insets.bar else 0.0)
        }

        /**
         * "File is empty" / "No changes to display" in place of the rows,
         * and the content it makes.
         */
        fun message(width: Double, maxWidth: Double, text: Measurable): Pair<Rect, Size> {
            val column = column(width, maxWidth)
            val textWidth = max(0.0, column.second - Metrics.padding * 2)
            val size = text.size(textWidth)
            val frame = Rect(column.first + Metrics.padding, Metrics.padding, textWidth, size.height)
            return frame to Size(max(0.0, width), size.height + Metrics.padding * 2)
        }

        /**
         * One line's text box: its measured height, at least the style's
         * line, up to the screen's next pixel as RN ceils a Text's box
         * (`displayScale` 0 rounds up to whole points). A thousandth of a
         * pixel over is float noise in the sum of lines, not a pixel.
         */
        fun textHeight(measured: Double, line: Double, displayScale: Double): Double {
            val height = max(line, measured)
            val scale = if (displayScale > 0) displayScale else 1.0
            return ceil(height * scale - 0.001) / scale
        }

        /**
         * Whether a line can't wrap at [width] without measuring it: printable
         * ASCII, a character short of the width at the mono advance.
         */
        fun fitsOneLine(string: String, advance: Double, width: Double): Boolean {
            for (unit in string) if (unit.code < 0x20 || unit.code >= 0x7F) return false
            return (string.length + 1) * advance <= width
        }
    }
}
