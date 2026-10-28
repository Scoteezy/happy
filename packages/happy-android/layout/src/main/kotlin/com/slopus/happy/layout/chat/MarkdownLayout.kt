package com.slopus.happy.layout.chat

import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.swiftRound
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * MarkdownView.tsx (happy-ios `MarkdownLayout`): parsed blocks stacked top to
 * bottom. React Native doesn't collapse margins, so each block's own margins
 * simply add up:
 *
 *     text      8 ┬ text ┬ 10
 *     header    h1–h3 16 / 8, h4 8 / 8, h5–h6 0 / 0
 *     rule      8 ┬ 1 ┬ 8
 *     list      0 ┬ items, 6 apart ┬ 8
 *     code      8 ┬ [8 language] 16 code 16 ┬ 8     (scrolls sideways)
 *     options   8 ┬ buttons, 8 apart ┬ 8
 *     table     8 ┬ bordered grid ┬ 8                (scrolls sideways)
 *     image     8 ┬ 240 picture, 8, caption ┬ 8
 *     mermaid   8 ┬ diagram, 200 to 600 ┬ 8        (scrolls inside)
 *
 * Frames are relative to the markdown view's origin.
 */
data class MarkdownLayout(val blocks: List<Placed>, val size: Size) {
    object Metrics {
        const val textMarginTop = 8.0
        const val textMarginBottom = 10.0
        const val ruleMargin = 8.0
        const val ruleHeight = 1.0
        const val listItemGap = 6.0
        const val listMarginBottom = 8.0
        const val listIndent = 16.0
        const val listMarkerGap = 8.0
        const val listMarkerTop = 1.0
        const val blockMargin = 8.0
        const val codePadding = 16.0
        const val codeLanguageTop = 8.0
        /** The widest a code block's text is drawn; a longer line is cut off there. */
        const val codeMaxWidth = 4096.0
        const val codeRadius = 8.0
        const val optionGap = 8.0
        const val optionPaddingH = 12.0
        const val optionPaddingV = 8.0
        const val optionRadius = 12.0
        const val tableBorder = 1.0
        const val tableRadius = 8.0
        const val tableCellPaddingH = 12.0
        const val tableCellPaddingV = 8.0
        const val tableMinColumn = 80.0
        const val tableMaxColumn = 360.0
        const val tableCharWidth = 8.5
        const val imageMaxWidth = 520.0
        const val imageHeight = 240.0
        const val imageCaptionGap = 8.0
        const val imageRadius = 12.0
        const val mermaidInitialHeight = 200.0
        const val mermaidMaxHeight = 600.0
        const val mermaidRadius = 8.0
        const val mermaidPadding = 16.0

        fun headerMargins(level: Int): Pair<Double, Double> = when (level) {
            1, 2, 3 -> 16.0 to 8.0
            4 -> 8.0 to 8.0
            else -> 0.0 to 0.0
        }
    }

    class ListItem(
        val depth: Int,
        /** The bullet or "3." label. */
        val marker: Measurable,
        val text: Measurable,
    )

    class Table(
        /** One width per column, from [columnWidths]. */
        val columnWidths: List<Double>,
        /** Row 0 is the header row. Missing cells are empty. */
        val rows: List<List<Measurable>>,
    )

    sealed interface Block {
        class Text(val text: Measurable) : Block
        class Header(val level: Int, val text: Measurable) : Block
        object Rule : Block
        class List(val items: kotlin.collections.List<ListItem>) : Block
        /** [code] is the highlighted text's unwrapped size. */
        class Code(val language: Measurable?, val code: Size) : Block
        class Options(val labels: kotlin.collections.List<Measurable>) : Block
        class TableBlock(val table: Table) : Block
        class Image(val caption: Measurable?) : Block
        /** A diagram, and the height its page reported, if it has yet. */
        class Mermaid(val reported: Double?) : Block
    }

    data class PlacedListItem(val marker: Rect, val text: Rect)

    data class PlacedCode(
        val frame: Rect,
        val language: Rect?,
        /** The horizontal scroll view, in the block's frame. */
        val scroll: Rect,
        val contentSize: Size,
        /** The code text, in the scroll view's content. */
        val text: Rect,
    )

    data class PlacedTable(
        val frame: Rect,
        /** Inside the border. */
        val scroll: Rect,
        val contentSize: Size,
        /** Cell text frames by row then column, in the scroll content. */
        val cells: List<List<Rect>>,
        /** Row backgrounds (the header row is shaded), in the scroll content. */
        val rows: List<Rect>,
        /** Hairlines between rows and between columns, in the scroll content. */
        val separators: List<Rect>,
    )

    sealed interface Placed {
        val frame: Rect

        data class Text(override val frame: Rect) : Placed
        data class Header(override val frame: Rect) : Placed
        data class Rule(override val frame: Rect) : Placed
        data class ListBlock(override val frame: Rect, val items: List<PlacedListItem>) : Placed
        data class Code(val code: PlacedCode) : Placed {
            override val frame get() = code.frame
        }
        data class Options(override val frame: Rect, val buttons: List<Rect>, val labels: List<Rect>) : Placed
        data class TableBlock(val table: PlacedTable) : Placed {
            override val frame get() = table.frame
        }
        data class Image(override val frame: Rect, val picture: Rect, val caption: Rect?) : Placed
        data class Mermaid(override val frame: Rect) : Placed
    }

    companion object {
        /**
         * [gap]: blocks stand exactly this far apart, with nothing above the
         * first or below the last, as CSS margins between siblings do; null
         * keeps RN's own margins above.
         */
        operator fun invoke(width: Double, blocks: List<Block>, gap: Double? = null): MarkdownLayout {
            val m = Metrics
            val w = max(0.0, width)
            var y = 0.0
            val placed = mutableListOf<Placed>()
            fun open(margin: Double) {
                if (gap != null) {
                    if (placed.isNotEmpty()) y += gap
                } else {
                    y += margin
                }
            }
            fun close(margin: Double) {
                if (gap == null) y += margin
            }

            for (block in blocks) {
                when (block) {
                    is Block.Text -> {
                        open(m.textMarginTop)
                        val size = block.text.size(w)
                        placed += Placed.Text(Rect(0.0, y, w, size.height))
                        y += size.height
                        close(m.textMarginBottom)
                    }
                    is Block.Header -> {
                        val (top, bottom) = m.headerMargins(block.level)
                        open(top)
                        val height = block.text.height(w)
                        placed += Placed.Header(Rect(0.0, y, w, height))
                        y += height
                        close(bottom)
                    }
                    Block.Rule -> {
                        open(m.ruleMargin)
                        placed += Placed.Rule(Rect(0.0, y, w, m.ruleHeight))
                        y += m.ruleHeight
                        close(m.ruleMargin)
                    }
                    is Block.List -> {
                        open(0.0)
                        val top = y
                        val items = mutableListOf<PlacedListItem>()
                        block.items.forEachIndexed { index, item ->
                            if (index > 0) y += m.listItemGap
                            val x = min(w, max(0, item.depth) * m.listIndent)
                            val marker = item.marker.size(max(0.0, w - x))
                            val textX = min(w, x + marker.width + m.listMarkerGap)
                            val textWidth = max(0.0, w - textX)
                            val textHeight = item.text.height(textWidth)
                            items += PlacedListItem(
                                marker = Rect(x, y + m.listMarkerTop, marker.width, marker.height),
                                text = Rect(textX, y, textWidth, textHeight),
                            )
                            y += max(marker.height + m.listMarkerTop, textHeight)
                        }
                        placed += Placed.ListBlock(Rect(0.0, top, w, y - top), items)
                        close(m.listMarginBottom)
                    }
                    is Block.Code -> {
                        open(m.blockMargin)
                        var inner = 0.0
                        var languageFrame: Rect? = null
                        if (block.language != null) {
                            val size = block.language.size(max(0.0, w - 2 * m.codePadding))
                            languageFrame = Rect(m.codePadding, m.codeLanguageTop, max(0.0, w - 2 * m.codePadding), size.height)
                            inner = m.codeLanguageTop + size.height
                        }
                        val scrollHeight = block.code.height + 2 * m.codePadding
                        val codeWidth = min(block.code.width, m.codeMaxWidth)
                        val frame = Rect(0.0, y, w, inner + scrollHeight)
                        placed += Placed.Code(
                            PlacedCode(
                                frame = frame,
                                language = languageFrame,
                                scroll = Rect(0.0, inner, w, scrollHeight),
                                contentSize = Size(max(w, codeWidth + 2 * m.codePadding), scrollHeight),
                                text = Rect(m.codePadding, m.codePadding, codeWidth, block.code.height),
                            ),
                        )
                        y += frame.height
                        close(m.blockMargin)
                    }
                    is Block.Options -> {
                        open(m.blockMargin)
                        val top = y
                        val buttons = mutableListOf<Rect>()
                        val labels = mutableListOf<Rect>()
                        val labelWidth = max(0.0, w - 2 * m.optionPaddingH)
                        block.labels.forEachIndexed { index, label ->
                            if (index > 0) y += m.optionGap
                            val height = label.height(labelWidth)
                            buttons += Rect(0.0, y, w, height + 2 * m.optionPaddingV)
                            labels += Rect(m.optionPaddingH, y + m.optionPaddingV, labelWidth, height)
                            y += height + 2 * m.optionPaddingV
                        }
                        placed += Placed.Options(Rect(0.0, top, w, y - top), buttons, labels)
                        close(m.blockMargin)
                    }
                    is Block.TableBlock -> {
                        open(m.blockMargin)
                        val grid = tableGrid(block.table)
                        val scrollWidth = min(grid.contentSize.width, max(0.0, w - 2 * m.tableBorder))
                        val frame = Rect(0.0, y, scrollWidth + 2 * m.tableBorder, grid.contentSize.height + 2 * m.tableBorder)
                        placed += Placed.TableBlock(
                            PlacedTable(
                                frame = frame,
                                scroll = Rect(m.tableBorder, m.tableBorder, scrollWidth, grid.contentSize.height),
                                contentSize = grid.contentSize,
                                cells = grid.cells,
                                rows = grid.rows,
                                separators = grid.separators,
                            ),
                        )
                        y += frame.height
                        close(m.blockMargin)
                    }
                    is Block.Image -> {
                        open(m.blockMargin)
                        val imageWidth = min(w, m.imageMaxWidth)
                        val picture = Rect(0.0, 0.0, imageWidth, m.imageHeight)
                        var height = m.imageHeight
                        var captionFrame: Rect? = null
                        if (block.caption != null) {
                            val captionHeight = block.caption.height(imageWidth)
                            captionFrame = Rect(0.0, height + m.imageCaptionGap, imageWidth, captionHeight)
                            height += m.imageCaptionGap + captionHeight
                        }
                        placed += Placed.Image(Rect(0.0, y, imageWidth, height), picture, captionFrame)
                        y += height
                        close(m.blockMargin)
                    }
                    is Block.Mermaid -> {
                        open(m.blockMargin)
                        val height = mermaidHeight(block.reported)
                        placed += Placed.Mermaid(Rect(0.0, y, w, height))
                        y += height
                        close(m.blockMargin)
                    }
                }
            }
            return MarkdownLayout(placed, Size(w, y))
        }

        /**
         * The narrowest width the blocks fit at, for a bubble that hugs its
         * text: the widest line, list row, code line, table or option.
         */
        fun naturalWidth(blocks: List<Block>, available: Double): Double {
            val m = Metrics
            val avail = max(0.0, available)
            var widest = 0.0
            for (block in blocks) {
                val width = when (block) {
                    is Block.Text -> block.text.size(avail).width
                    is Block.Header -> block.text.size(avail).width
                    Block.Rule -> 0.0
                    is Block.List -> block.items.maxOfOrNull { item ->
                        val x = max(0, item.depth) * m.listIndent
                        val marker = item.marker.size(avail).width
                        x + marker + m.listMarkerGap + item.text.size(max(0.0, avail - x - marker - m.listMarkerGap)).width
                    } ?: 0.0
                    is Block.Code -> max(block.code.width, block.language?.size(avail)?.width ?: 0.0) + 2 * m.codePadding
                    is Block.Options ->
                        (block.labels.maxOfOrNull { it.size(max(0.0, avail - 2 * m.optionPaddingH)).width } ?: 0.0) + 2 * m.optionPaddingH
                    is Block.TableBlock -> block.table.columnWidths.sum() + 2 * m.tableBorder
                    is Block.Image -> m.imageMaxWidth
                    // `width: '100%'`.
                    is Block.Mermaid -> avail
                }
                widest = max(widest, width)
            }
            return min(widest, avail)
        }

        /** MermaidRenderer's box: 200 until the page reports, then the most it has reported, never more than 600. */
        fun mermaidHeight(reported: Double?): Double =
            min(max(Metrics.mermaidInitialHeight, reported ?: 0.0), Metrics.mermaidMaxHeight)

        /**
         * What to keep of a height a diagram's page reported, given what is
         * kept already: rounded up to whole pixels, no more than the box can
         * show, and only if the box grows by it.
         */
        fun mermaidHeightToKeep(reported: Double, kept: Double?, scale: Double): Double? {
            if (!reported.isFinite() || scale <= 0) return null
            val height = min(ceil(reported * scale) / scale, Metrics.mermaidMaxHeight)
            if (height <= max(kept ?: 0.0, Metrics.mermaidInitialHeight)) return null
            return height
        }

        /**
         * How many pixels to decode an image block's picture of [size] pixels
         * to: contained in the largest box it is drawn in, on a 3x screen,
         * never more than it has.
         */
        fun decodedImageSize(size: Size): Size {
            if (size.width <= 0 || size.height <= 0) return size
            val scale = minOf(1.0, 3 * Metrics.imageMaxWidth / size.width, 3 * Metrics.imageHeight / size.height)
            return Size(swiftRound(size.width * scale), swiftRound(size.height * scale))
        }

        /**
         * RenderTableBlock's column widths: the longest cell's character count
         * at 8.5 a character plus padding, clamped to 80…360.
         */
        fun columnWidths(lengths: List<List<Int>>): List<Double> {
            val columns = lengths.firstOrNull()?.size ?: 0
            return (0 until columns).map { column ->
                val longest = lengths.maxOfOrNull { if (column < it.size) it[column] else 0 } ?: 0
                val estimate = longest * Metrics.tableCharWidth + 2 * Metrics.tableCellPaddingH
                min(Metrics.tableMaxColumn, max(Metrics.tableMinColumn, estimate))
            }
        }

        private class Grid(val contentSize: Size, val cells: List<List<Rect>>, val rows: List<Rect>, val separators: List<Rect>)

        private fun tableGrid(table: Table): Grid {
            val m = Metrics
            val widths = table.columnWidths
            val columns = widths.size
            val xs = mutableListOf<Double>()
            var x = 0.0
            for (width in widths) {
                xs += x
                x += width
            }
            val totalWidth = x
            var y = 0.0
            val cells = mutableListOf<List<Rect>>()
            val rows = mutableListOf<Rect>()
            val separators = mutableListOf<Rect>()
            table.rows.forEachIndexed { rowIndex, row ->
                val last = rowIndex == table.rows.size - 1
                // The header row always has its rule; data rows have one unless last.
                val bottomBorder = if (rowIndex == 0 || !last) m.tableBorder else 0.0
                val textWidths = mutableListOf<Double>()
                val textHeights = mutableListOf<Double>()
                for (column in 0 until columns) {
                    val rightBorder = if (column < columns - 1) m.tableBorder else 0.0
                    val textWidth = max(0.0, widths[column] - 2 * m.tableCellPaddingH - rightBorder)
                    textWidths += textWidth
                    textHeights += if (column < row.size) row[column].height(textWidth) else 0.0
                }
                val contentHeight = (textHeights.maxOrNull() ?: 0.0) + 2 * m.tableCellPaddingV
                cells += (0 until columns).map { column ->
                    Rect(xs[column] + m.tableCellPaddingH, y + m.tableCellPaddingV, textWidths[column], textHeights[column])
                }
                rows += Rect(0.0, y, totalWidth, contentHeight + bottomBorder)
                for (column in 0 until max(0, columns - 1)) {
                    separators += Rect(xs[column] + widths[column] - m.tableBorder, y, m.tableBorder, contentHeight + bottomBorder)
                }
                if (bottomBorder > 0) separators += Rect(0.0, y + contentHeight, totalWidth, bottomBorder)
                y += contentHeight + bottomBorder
            }
            return Grid(Size(totalWidth, y), cells, rows, separators)
        }
    }
}
