package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.swiftRound
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Home's dock (happy-ios `StemCellsDockLayout`): the new-work screen's own
 * composer card, where that screen puts it with the keyboard down, over a
 * scrim that rises from the screen's foot to the card's middle. The list
 * keeps clear of it as the transcript does.
 */
data class StemCellsDockLayout(
    val card: Rect,
    val scrim: Rect,
    /** The room under the list, from the screen's foot. */
    val listBottom: Double,
) {
    companion object {
        operator fun invoke(size: Size, safeArea: LayoutInsets, cardHeight: (Double) -> Double): StemCellsDockLayout {
            val screen = StemCellChatScreenLayout(size, safeArea, size.height, cardHeight)
            val card = screen.card
            return StemCellsDockLayout(card, Rect(0.0, card.midY, max(0.0, size.width), max(0.0, size.height - card.midY)), screen.listBottom)
        }
    }
}

/**
 * The note above the Stem Cells list (happy-ios `StemCellsBannerLayout`): a
 * card 12 in from the sides, its title and detail 14 inside it, and the
 * Turn on button at its right, centred; 8 of room under the card.
 */
data class StemCellsBannerLayout(
    val size: Size,
    val card: Rect,
    /** The rest in the card's own space. */
    val title: Rect,
    val detail: Rect?,
    val button: Rect?,
) {
    object Metrics {
        const val inset = 12.0
        const val padding = 14.0
        const val buttonGap = 12.0
        const val detailGap = 2.0
        const val bottom = 8.0
    }

    companion object {
        operator fun invoke(width: Double, title: Measurable, detail: Measurable?, button: Size?): StemCellsBannerLayout {
            val m = Metrics
            val cardWidth = max(0.0, width - 2 * m.inset)
            val textWidth = max(0.0, cardWidth - 2 * m.padding - (button?.let { it.width + m.buttonGap } ?: 0.0))
            val titleFrame = Rect(m.padding, m.padding, textWidth, title.height(textWidth))
            var bottom = titleFrame.maxY
            var detailFrame: Rect? = null
            if (detail != null) {
                detailFrame = Rect(m.padding, bottom + m.detailGap, textWidth, detail.height(textWidth))
                bottom = detailFrame.maxY
            }
            val cardHeight = bottom + m.padding
            val card = Rect(m.inset, 0.0, cardWidth, cardHeight)
            val buttonFrame = button?.let { Rect(cardWidth - m.padding - it.width, swiftRound((cardHeight - it.height) / 2), it.width, it.height) }
            return StemCellsBannerLayout(Size(max(0.0, width), card.height + m.bottom), card, titleFrame, detailFrame, buttonFrame)
        }
    }
}

/**
 * No computer, or one too old (happy-ios `StemCellsEmptyLayout`): the stem
 * cell over a line and the Pair button, the three centred together in the
 * space between the bars.
 */
data class StemCellsEmptyLayout(val mark: Rect, val label: Rect, val button: Rect?) {
    object Metrics {
        const val mark = 64.0
        const val markGap = 20.0
        const val buttonGap = 24.0
        const val sideInset = 24.0
        const val maxWidth = 360.0
    }

    companion object {
        operator fun invoke(size: Size, label: Measurable, button: Size?): StemCellsEmptyLayout {
            val m = Metrics
            val width = max(0.0, min(size.width - 2 * m.sideInset, m.maxWidth))
            val labelHeight = label.height(width)
            val total = m.mark + m.markGap + labelHeight + (button?.let { m.buttonGap + it.height } ?: 0.0)
            var y = swiftRound((size.height - total) / 2)
            val mark = Rect(swiftRound((size.width - m.mark) / 2), y, m.mark, m.mark)
            y += m.mark + m.markGap
            val labelFrame = Rect(swiftRound((size.width - width) / 2), y, width, labelHeight)
            y += labelHeight + m.buttonGap
            val top = y
            return StemCellsEmptyLayout(mark, labelFrame, button?.let { Rect(swiftRound((size.width - it.width) / 2), top, it.width, it.height) })
        }
    }
}

/** Search (happy-ios `StemCellsSearchLayout`): the bar under the navigation bar, 4 in from the sides, and the list under it, scrolling the whole screen. */
data class StemCellsSearchLayout(val bar: Rect, val listInsets: LayoutInsets) {
    companion object {
        operator fun invoke(width: Double, safeArea: LayoutInsets, barHeight: Double): StemCellsSearchLayout {
            val bar = Rect(4.0, safeArea.top, max(0.0, width - 8), barHeight)
            return StemCellsSearchLayout(bar, LayoutInsets(bar.maxY, 0.0, safeArea.bottom, 0.0))
        }
    }
}

/**
 * A todo list on the phone (happy-ios `StemCellsTodoLayout`): the field
 * that adds one at the top, then each todo with its box 16 in and its text
 * beside it, wrapping, or a line saying there are none. Content
 * coordinates, under the bar.
 */
data class StemCellsTodoLayout(val field: Rect, val rows: List<Row>, val empty: Rect?, val contentHeight: Double) {
    object Metrics {
        const val inset = 16.0
        const val top = 12.0
        const val fieldHeight = 44.0
        const val listGap = 8.0
        const val box = 22.0
        const val boxGap = 10.0
        const val rowPadding = 10.0
        const val minRow = 44.0
        const val emptyGap = 24.0
        const val bottom = 16.0
    }

    data class Row(
        val frame: Rect,
        /** In the row's own space. */
        val box: Rect,
        val text: Rect,
    )

    companion object {
        operator fun invoke(width: Double, todos: List<Measurable>, empty: Measurable): StemCellsTodoLayout {
            val m = Metrics
            val inner = max(0.0, width - 2 * m.inset)
            val field = Rect(m.inset, m.top, inner, m.fieldHeight)
            var y = field.maxY + m.listGap
            val textX = m.inset + m.box + m.boxGap
            val textWidth = max(0.0, width - textX - m.inset)
            val rows = todos.map { todo ->
                val text = todo.size(textWidth)
                val height = max(m.minRow, text.height + 2 * m.rowPadding)
                val row = Row(
                    Rect(0.0, y, max(0.0, width), height),
                    Rect(m.inset, swiftRound((height - m.box) / 2), m.box, m.box),
                    Rect(textX, swiftRound((height - text.height) / 2), textWidth, text.height),
                )
                y += height
                row
            }
            var emptyFrame: Rect? = null
            if (todos.isEmpty()) {
                emptyFrame = Rect(m.inset, y + m.emptyGap, inner, empty.height(inner))
                y = emptyFrame.maxY
            }
            return StemCellsTodoLayout(field, rows, emptyFrame, y + m.bottom)
        }
    }
}

/** A document, or a Surface the phone only names (happy-ios `StemCellsSurfaceBodyLayout`): its markdown, or a line, 8 under the bar, scrolling. */
data class StemCellsSurfaceBodyLayout(val body: Rect, val contentHeight: Double) {
    companion object {
        /** `body` fills the width less `inset` a side. */
        operator fun invoke(width: Double, body: Measurable, inset: Double = 0.0): StemCellsSurfaceBodyLayout {
            val bodyWidth = max(0.0, width - 2 * inset)
            val frame = Rect(inset, 8.0, bodyWidth, body.height(bodyWidth))
            return StemCellsSurfaceBodyLayout(frame, frame.maxY + 16)
        }
    }
}

/**
 * A conversation's name in its bar, renamed where it stands (happy-ios
 * `StemCellChatTitleLayout`): as wide as its text, no wider than
 * `maxWidth`; while it is edited, the whole of `maxWidth`, so the field
 * doesn't grow under the keyboard. The text and the field fill it, centred.
 */
data class StemCellChatTitleLayout(val size: Size, val text: Rect) {
    companion object {
        const val HEIGHT = 44.0
        /** The bar keeps this much clear a side, for the back button. */
        const val SIDE_CLEARANCE = 64.0

        /** No bound while the bar's width is unknown (0): the name is as wide as its text, and the bar fits it. */
        fun maxWidth(barWidth: Double) = if (barWidth > 0) max(0.0, barWidth - 2 * SIDE_CLEARANCE) else Double.MAX_VALUE

        operator fun invoke(textWidth: Double, maxWidth: Double, editing: Boolean): StemCellChatTitleLayout {
            val fitted = min(ceil(textWidth), maxWidth)
            val width = if (editing && maxWidth < Double.MAX_VALUE) maxWidth else fitted
            val size = Size(max(0.0, width), HEIGHT)
            return StemCellChatTitleLayout(size, Rect(Point.Zero, size))
        }
    }
}

/**
 * A picture opened whole (happy-ios `StemCellPictureViewerLayout`): fitted
 * inside the safe area on black, never enlarged past its own size, with its
 * close button at the top right.
 */
data class StemCellPictureViewerLayout(val picture: Rect, val close: Rect) {
    companion object {
        const val CLOSE_SIZE = 44.0
        const val CLOSE_INSET = 8.0

        /** `natural` is the picture's size in points; zero before it is known. */
        operator fun invoke(size: Size, safeArea: LayoutInsets, natural: Size): StemCellPictureViewerLayout {
            val area = Rect(safeArea.left, safeArea.top, max(0.0, size.width - safeArea.left - safeArea.right), max(0.0, size.height - safeArea.top - safeArea.bottom))
            val picture = if (natural.width > 0 && natural.height > 0) {
                val scale = minOf(1.0, area.width / natural.width, area.height / natural.height)
                val fitted = Size(swiftRound(natural.width * scale), swiftRound(natural.height * scale))
                Rect(swiftRound(area.midX - fitted.width / 2), swiftRound(area.midY - fitted.height / 2), fitted.width, fitted.height)
            } else area
            return StemCellPictureViewerLayout(picture, Rect(size.width - safeArea.right - CLOSE_INSET - CLOSE_SIZE, safeArea.top + CLOSE_INSET, CLOSE_SIZE, CLOSE_SIZE))
        }
    }
}
