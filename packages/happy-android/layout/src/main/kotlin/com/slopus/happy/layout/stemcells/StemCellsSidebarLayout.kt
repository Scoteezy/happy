package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellsMetrics
import com.slopus.happy.layout.swiftRound
import kotlin.math.floor
import kotlin.math.max

/**
 * The Stem Cells sidebar's frames (happy-ios `StemCellsSidebarLayout`): one
 * column of rows, each a fixed height by its kind, so folding a section
 * moves only the rows after it and no row ever changes size.
 */
object StemCellsSidebarLayout {
    enum class Kind { Row, Section, Separator, Widget }

    /** What the layout needs to know about a row. */
    data class Row(
        val kind: Kind,
        val depth: Int = 0,
        val hasMark: Boolean = true,
        /** A status mark or an unread dot in the trailing square. */
        val hasTrailing: Boolean = false,
        /** Rows nested under it fold: its chevron takes the trailing square, and a status or dot stands just before it. */
        val hasFold: Boolean = false,
        /** A section heading's "+", which makes new work in it: one square before its fold, always shown. */
        val hasAdd: Boolean = false,
        /** The fold points right ("›"), not down ("⌄"): its ink weighs elsewhere. */
        val foldClosed: Boolean = false,
    )

    data class Frame(
        /** The row box: the tap target and its fill, in content coordinates. */
        val box: Rect,
        /** The leading mark, absent for a row without one; then the label starts there. */
        val mark: Rect? = null,
        val label: Rect = Rect.Zero,
        /** The status mark or unread dot. */
        val trailing: Rect? = null,
        /** A section's fold chevron, on the same trailing column: its lane. */
        val chevron: Rect? = null,
        /**
         * The chevron's image (`chevronGlyph` square), placed so its ink's
         * optical centre is the fold's: `trailingCenter` from the right, on
         * the row's middle. On whole pixels at `scale`.
         */
        val chevronGlyph: Rect? = null,
        /** A section's "+", one square before its fold. */
        val add: Rect? = null,
        /** A separator's hairline, across the row box's whole width. */
        val rule: Rect? = null,
    )

    data class Result(
        val frames: List<Frame>,
        /** The tree's lines: vertical stems and the elbows into nested rows, `line` wide. */
        val connectors: List<Rect>,
        val contentHeight: Double,
    )

    fun height(kind: Kind): Double = when (kind) {
        Kind.Row -> StemCellsMetrics.rowHeight
        Kind.Section -> StemCellsMetrics.sectionHeight
        Kind.Separator -> StemCellsMetrics.separatorHeight
        Kind.Widget -> StemCellsMetrics.widgetMinHeight
    }

    fun layout(
        rows: List<Row>,
        width: Double,
        top: Double = StemCellsMetrics.contentTop,
        bottom: Double = StemCellsMetrics.contentBottom,
        scale: Double = 3.0,
    ): Result {
        val m = StemCellsMetrics
        val frames = ArrayList<Frame>(rows.size)
        var y = top
        val boxX = m.inset
        val boxWidth = max(0.0, width - 2 * m.inset)
        for ((index, row) in rows.withIndex()) {
            if (index > 0) y += m.rowGap
            val height = height(row.kind)
            val box = Rect(boxX, y, boxWidth, height)
            val midY = y + height / 2
            fun pixel(value: Double) = swiftRound(value * max(1.0, scale)) / max(1.0, scale)
            // Centred `trailingCenter` in, on the row's middle, on whole pixels.
            val trailingRect = Rect(pixel(width - m.trailingCenter - m.trailing / 2), pixel(midY - m.trailing / 2), m.trailing, m.trailing)
            val inkRight = trailingRect.maxX
            fun fold(): Rect {
                val ink = if (row.foldClosed) m.chevronRightInk else m.chevronDownInk
                val size = m.chevronGlyph
                return Rect(pixel(width - m.trailingCenter - size / 2 - ink.x), pixel(midY - size / 2 - ink.y), size, size)
            }
            var frame = Frame(box)
            when (row.kind) {
                Kind.Separator -> frame = frame.copy(rule = Rect(boxX, floor(midY - m.line / 2), boxWidth, m.line))
                Kind.Section -> {
                    // The title starts where a top-level glyph's ink does, and
                    // keeps clear of the fold's lane (`padding-right: row
                    // padding + 22`), so showing the fold moves nothing.
                    val x = boxX + m.sectionTitleInset
                    var end = trailingRect.minX
                    var add: Rect? = null
                    if (row.hasAdd) {
                        add = trailingRect.offsetBy(-(m.trailing + m.trailingGap), 0.0)
                        end = add.minX
                    }
                    frame = frame.copy(
                        add = add,
                        label = Rect(x, y, max(0.0, end - SECTION_FOLD_GAP - x), height),
                        chevron = trailingRect,
                        chevronGlyph = fold(),
                    )
                }
                Kind.Widget, Kind.Row -> {
                    var x = leadingX(row.depth)
                    if (row.hasMark) {
                        frame = frame.copy(mark = Rect(x, midY - m.mark / 2, m.mark, m.mark))
                        x += m.mark + m.markGap
                    }
                    var right = inkRight
                    var slot = trailingRect
                    if (row.hasFold && row.kind == Kind.Row) {
                        frame = frame.copy(chevron = slot, chevronGlyph = fold())
                        right = slot.minX - m.trailingGap
                        slot = slot.offsetBy(-(m.trailing + m.trailingGap), 0.0)
                    }
                    if (row.hasTrailing) {
                        frame = frame.copy(trailing = slot)
                        right = slot.minX - m.trailingGap
                    }
                    // A row's title rides up to its marks' middle.
                    frame = frame.copy(label = Rect(x, if (row.kind == Kind.Row) y - m.titleLift else y, max(0.0, right - x), height))
                }
            }
            frames += frame
            y += height
        }
        return Result(frames, connectors(rows, frames), y + bottom)
    }

    /** Between a section's title and its fold: `22 − 18`. */
    internal const val SECTION_FOLD_GAP = 4 * StemCellsMetrics.phone

    /**
     * Where a row's mark (or, without one, its label) starts: a top-level
     * mark centred `endInset` in from the row box, `indent` more per level.
     */
    internal fun leadingX(depth: Int): Double = StemCellsMetrics.markCenter - StemCellsMetrics.mark / 2 + depth * StemCellsMetrics.indent

    /**
     * `SidebarTreeConnectors`: one stem per parent down its mark's centre,
     * from under the mark to a point past its last child's middle, and an
     * elbow into each child at its middle, stopping short of its mark.
     */
    internal fun connectors(rows: List<Row>, frames: List<Frame>): List<Rect> {
        val m = StemCellsMetrics
        val lines = ArrayList<Rect>()
        val children = HashMap<Int, MutableList<Int>>()
        val order = ArrayList<Int>()
        for ((index, row) in rows.withIndex()) {
            if (row.depth <= 0 || row.kind != Kind.Row) continue
            var parent = index - 1
            while (parent >= 0 && rows[parent].depth >= row.depth) parent -= 1
            if (parent < 0 || rows[parent].depth != row.depth - 1) continue
            if (children[parent] == null) order += parent
            children.getOrPut(parent) { ArrayList() } += index
        }
        for (parent in order) {
            val kids = children[parent].orEmpty()
            val stemX = floor(leadingX(rows[parent].depth) + m.mark / 2 - m.line / 2)
            val top = frames[parent].box.midY + m.stemInset
            val bottom = (kids.maxOfOrNull { frames[it].box.midY } ?: top) + m.line
            lines += Rect(stemX, top, m.line, max(0.0, bottom - top))
            for (kid in kids) {
                val end = leadingX(rows[kid].depth) - m.elbowGap
                lines += Rect(stemX + m.line, floor(frames[kid].box.midY - m.line / 2), max(0.0, end - stemX - m.line), m.line)
            }
        }
        return lines
    }
}
