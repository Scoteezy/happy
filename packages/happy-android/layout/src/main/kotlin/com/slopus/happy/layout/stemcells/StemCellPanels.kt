package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.StemCellPickerMetrics
import com.slopus.happy.layout.swiftRound
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The composer's "+" sheet (happy-ios `StemCellAttachSheetLayout`), as
 * Claude's "Add to session" is laid out: a header row with Photos at its
 * left, the title in its middle and the close circle at its right; under it
 * a strip of square tiles (the camera, then the newest photos) scrolling
 * sideways; then one rounded group of rows. Frames are in the sheet's own
 * space; the strip's tiles and the rows are in the strip's content and the
 * group's own space.
 */
data class StemCellAttachSheetLayout(
    val close: Rect,
    val title: Rect,
    /** Photos, or "Add N", at the left. */
    val photos: Rect,
    /** The strip, the sheet's whole width. */
    val strip: Rect,
    val stripContentWidth: Double,
    val tiles: List<Rect>,
    val group: Rect,
    val rows: List<Row>,
    /** The sheet's height above its bottom safe area. */
    val height: Double,
) {
    object Metrics {
        const val padding = 16.0
        const val top = 16.0
        const val control = 44.0
        const val titleHeight = 22.0
        /** Room between the title and the controls either side of it. */
        const val titleGap = 8.0
        const val sectionGap = 16.0
        const val tile = 104.0
        const val tileGap = 8.0
        const val tileRadius = 18.0
        const val check = 24.0
        const val checkInset = 6.0
        const val rowHeight = 52.0
        const val groupRadius = 18.0
        const val icon = 24.0
        const val iconLeading = 16.0
        const val iconGap = 12.0
        const val chevron = 14.0
        const val trailing = 16.0
        const val valueGap = 6.0
        const val bottom = 16.0
    }

    data class Row(
        /** The whole row, in the group's space. */
        val frame: Rect,
        val icon: Rect,
        val title: Rect,
        val value: Rect?,
        val chevron: Rect?,
        /** The hairline above it, from the title's start; none on the first. */
        val separator: Rect?,
    )

    companion object {
        /** The numbered check in a tile's corner, in the tile's space. */
        val check get() = Rect(Metrics.tile - Metrics.checkInset - Metrics.check, Metrics.checkInset, Metrics.check, Metrics.check)

        /**
         * `values` holds each row's trailing value width, null for a row
         * without one (it then has no chevron either). `photosWidth` is the
         * header's left button measured; `scale` rounds the hairline.
         */
        operator fun invoke(width: Double, titleWidth: Double, photosWidth: Double, tiles: Int, values: List<Double?>, scale: Double = 3.0): StemCellAttachSheetLayout {
            val m = Metrics
            val w = max(0.0, width)
            val close = SheetCloseLayout.frame(w)
            val photosShown = ceil(max(0.0, photosWidth))
            val photos = Rect(m.padding, close.minY, photosShown, m.control)
            // Centred on the sheet, never under the controls either side.
            val room = max(0.0, w - 2 * (m.padding + max(m.control, photosShown) + m.titleGap))
            val shown = min(ceil(max(0.0, titleWidth)), room)
            val title = Rect(swiftRound((w - shown) / 2), close.midY - m.titleHeight / 2, shown, m.titleHeight)
            val strip = Rect(0.0, close.maxY + m.sectionGap, w, m.tile)
            val tileFrames = (0 until max(0, tiles)).map { Rect(m.padding + it * (m.tile + m.tileGap), 0.0, m.tile, m.tile) }
            val stripContentWidth = (tileFrames.lastOrNull()?.maxX ?: 0.0) + m.padding
            val group = Rect(m.padding, strip.maxY + m.sectionGap, max(0.0, w - 2 * m.padding), values.size * m.rowHeight)
            val groupWidth = group.width
            val hairline = 1 / max(1.0, scale)
            val rows = values.mapIndexed { index, value ->
                val frame = Rect(0.0, index * m.rowHeight, groupWidth, m.rowHeight)
                val icon = Rect(m.iconLeading, frame.midY - m.icon / 2, m.icon, m.icon)
                val titleX = icon.maxX + m.iconGap
                var end = groupWidth - m.trailing
                var chevron: Rect? = null
                var shownValue: Rect? = null
                if (value != null) {
                    val arrow = Rect(end - m.chevron, frame.midY - m.chevron / 2, m.chevron, m.chevron)
                    chevron = arrow
                    val valueWidth = min(ceil(max(0.0, value)), max(0.0, (arrow.minX - m.valueGap - titleX) / 2))
                    val box = Rect(arrow.minX - m.valueGap - valueWidth, frame.midY - m.titleHeight / 2, valueWidth, m.titleHeight)
                    shownValue = box
                    end = box.minX - m.valueGap
                }
                Row(
                    frame, icon,
                    Rect(titleX, frame.midY - m.titleHeight / 2, max(0.0, end - titleX), m.titleHeight),
                    shownValue, chevron,
                    if (index == 0) null else Rect(titleX, frame.minY, max(0.0, groupWidth - titleX), hairline),
                )
            }
            return StemCellAttachSheetLayout(close, title, photos, strip, stripContentWidth, tileFrames, group, rows, group.maxY + m.bottom)
        }
    }
}

/**
 * One of the composer's model control panels (happy-ios
 * `StemCellPickerPanelLayout`; desktop's `composer-model-control.css`): the
 * model list or the effort list over its chip, or a service's accounts
 * stacked over the model list in place.
 *
 * Lines run down the panel `inset` in from its sides; a block (a service, a
 * hairline, the footer) keeps `gap` from the one before it. The lines
 * scroll in the panel's viewport when they don't fit; the footer stays
 * under them. The picked line is in view from the panel's first frame:
 * centred when the list is long, the offset clamped to the list.
 */
data class StemCellPickerPanelLayout(
    /** The panel, in the space it was placed in. */
    val frame: Rect,
    /** Where the lines scroll, in the panel. */
    val viewport: Rect,
    /** The lines, in the viewport's content. */
    val items: List<Rect>,
    val contentHeight: Double,
    /** The footer's lines, in the panel. */
    val footer: List<Rect>,
    /** Where the lines stand scrolled when the panel first shows. */
    val initialOffset: Double,
) {
    /** A line: its height, and whether it starts a block. */
    data class Item(val height: Double, val startsBlock: Boolean = false) {
        companion object {
            val row = Item(StemCellPickerMetrics.row)
            val heading = Item(StemCellPickerMetrics.heading, true)
            val separator = Item(1.0, true)
            /** The line after a hairline, or the first of a new block. */
            fun block(height: Double) = Item(height, true)
        }
    }

    companion object {
        /**
         * A list over its chip: its trailing edge on the chip's (kept
         * `margin` inside `bounds`), its bottom `anchorGap` above it, as tall
         * as its lines or as `bounds` leaves above the chip, at most `maxHeight`.
         */
        fun over(items: List<Item>, footer: List<Item> = emptyList(), width: Double, anchor: Rect, bounds: Rect, selected: Int? = null) =
            make(items, footer, width, anchor.maxX, anchor.minY - StemCellPickerMetrics.anchorGap, bounds, selected)

        /** A panel stacked over another in place: the same trailing edge and bottom, so it covers the panel under it from where that stands. */
        fun stackedOver(items: List<Item>, footer: List<Item> = emptyList(), width: Double, base: Rect, bounds: Rect, selected: Int? = null) =
            make(items, footer, width, base.maxX, base.maxY, bounds, selected)

        private fun make(items: List<Item>, footer: List<Item>, width: Double, trailing: Double, bottom: Double, bounds: Rect, selected: Int?): StemCellPickerPanelLayout {
            val m = StemCellPickerMetrics
            val w = max(0.0, min(width, bounds.width - 2 * m.margin))
            val x = max(bounds.minX + m.margin, min(trailing, bounds.maxX - m.margin) - w)
            val lineWidth = max(0.0, w - 2 * m.inset)
            fun stack(lines: List<Item>, top: Double): Pair<List<Rect>, Double> {
                var y = top
                val frames = ArrayList<Rect>()
                for ((index, item) in lines.withIndex()) {
                    if (index > 0 && item.startsBlock) y += m.gap
                    frames += Rect(m.inset, y, lineWidth, item.height)
                    y += item.height
                }
                return frames to y
            }
            val (lines, linesBottom) = stack(items, 0.0)
            val footerHeight = stack(footer, 0.0).second
            val chrome = 2 * m.inset + (if (footer.isEmpty()) 0.0 else m.gap + footerHeight)
            val room = min(m.maxHeight, bottom - (bounds.minY + m.margin))
            val viewportHeight = max(0.0, min(linesBottom, room - chrome))
            val height = viewportHeight + chrome
            val viewport = Rect(0.0, m.inset, w, viewportHeight)
            val scrolls = max(0.0, linesBottom - viewportHeight)
            val initialOffset = if (selected != null && selected in lines.indices) {
                min(scrolls, max(0.0, swiftRound(lines[selected].midY - viewportHeight / 2)))
            } else 0.0
            return StemCellPickerPanelLayout(
                Rect(x, bottom - height, w, height), viewport, lines, linesBottom,
                stack(footer, viewport.maxY + m.gap).first, initialOffset,
            )
        }

        /** The account's windows under its list: a week line, then the rest, `gap` apart, `detailsInsetY` above and below. */
        fun detailsHeight(lines: Int, primary: Boolean): Double {
            if (lines <= 0) return 0.0
            val m = StemCellPickerMetrics
            val first = if (primary) m.detailsPrimaryLine else m.detailsLine
            return 2 * m.detailsInsetY + first + (lines - 1) * (m.detailsLine + m.gap)
        }
    }
}

/**
 * One line's parts (happy-ios `StemCellPickerLineLayout`), desktop's flex
 * row: pieces from its leading edge, `padding` in, each `gapBefore` after
 * the last, and pieces from its trailing edge back. A piece that `shrinks`
 * gives up what doesn't fit, so a long name ends early and what follows it
 * (a gauge) stays whole; with more than one, the last gives way first.
 */
data class StemCellPickerLineLayout(val leading: List<Rect>, val trailing: List<Rect>) {
    data class Piece(val size: Size, val maxWidth: Double? = null, val shrinks: Boolean = false, val gapBefore: Double = 0.0)

    companion object {
        /** `trailingGap` keeps the leading pieces off the trailing ones. */
        operator fun invoke(
            line: Rect,
            padding: Double = StemCellPickerMetrics.padding,
            leading: List<Piece>,
            trailing: List<Piece> = emptyList(),
            trailingGap: Double = StemCellPickerMetrics.checkGap,
        ): StemCellPickerLineLayout {
            fun centred(x: Double, size: Size, width: Double) = Rect(x, line.midY - size.height / 2, width, size.height)
            var right = line.maxX - padding
            val placed = ArrayList<Rect>()
            for ((index, piece) in trailing.withIndex()) {
                if (index > 0) right -= piece.gapBefore
                val width = min(piece.size.width, piece.maxWidth ?: Double.MAX_VALUE)
                placed += centred(right - width, piece.size, width)
                right -= width
            }
            val end = if (trailing.isEmpty()) line.maxX - padding else right - trailingGap
            val widths = leading.map { min(it.size.width, it.maxWidth ?: Double.MAX_VALUE) }.toMutableList()
            val gaps = leading.drop(1).sumOf { it.gapBefore }
            var over = widths.sum() + gaps - (end - (line.minX + padding))
            for (index in leading.indices.reversed()) {
                if (over <= 0 || !leading[index].shrinks) continue
                val given = min(over, widths[index])
                widths[index] -= given
                over -= given
            }
            var x = line.minX + padding
            val frames = ArrayList<Rect>()
            for ((index, piece) in leading.withIndex()) {
                if (index > 0) x += piece.gapBefore
                frames += centred(x, piece.size, widths[index])
                x += widths[index]
            }
            return StemCellPickerLineLayout(frames, placed)
        }
    }
}

/** A usage gauge as desktop's (happy-ios `StemCellUsageGaugeLayout`): what is left, then a ring that drains as the window is spent. */
data class StemCellUsageGaugeLayout(val size: Size, val text: Rect, val ring: Rect) {
    companion object {
        operator fun invoke(text: Size): StemCellUsageGaugeLayout {
            val ringSize = StemCellPickerMetrics.ring
            val height = max(text.height, ringSize)
            val textFrame = Rect(0.0, (height - text.height) / 2, text.width, text.height)
            val ring = Rect(text.width + StemCellPickerMetrics.ringGap, (height - ringSize) / 2, ringSize, ringSize)
            return StemCellUsageGaugeLayout(Size(ring.maxX, height), textFrame, ring)
        }
    }
}

/** Shared measured content for pairing and the direct instance's home (happy-ios `DirectIdentityLayout`). */
data class DirectIdentityLayout(val image: Rect, val title: Rect, val body: Rect, val emoji: Rect, val size: Size) {
    companion object {
        operator fun invoke(width: Double, title: Measurable, body: Measurable, emoji: Measurable): DirectIdentityLayout {
            val w = max(0.0, width)
            val imageSize = min(88.0, w)
            val image = Rect((w - imageSize) / 2, 0.0, imageSize, imageSize)
            val titleFrame = Rect(0.0, image.maxY + 20, w, title.height(w))
            val bodyFrame = Rect(0.0, titleFrame.maxY + 12, w, body.height(w))
            val emojiFrame = Rect(0.0, bodyFrame.maxY + 20, w, emoji.height(w))
            return DirectIdentityLayout(image, titleFrame, bodyFrame, emojiFrame, Size(w, emojiFrame.maxY + 12))
        }
    }
}
