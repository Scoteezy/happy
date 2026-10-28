package com.slopus.happy.layout.chat

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.max
import kotlin.math.min
import com.slopus.happy.layout.swiftRound

// ToolView.tsx and the pieces tool rows are built from (happy-ios
// Chat/ToolRowLayouts.swift). A tool row sits in MessageView's
// toolContainer, 8 in from each side of the column.

/** The column a tool row draws in: ChatColumn less 8 on each side. */
object ToolRowMetrics {
    const val containerMarginH = 8.0

    fun cardFrame(rowWidth: Double, maxContentWidth: Double): Pair<Double, Double> {
        val column = ChatColumn(rowWidth, maxContentWidth)
        return (column.x + containerMarginH) to max(0.0, column.width - 2 * containerMarginH)
    }
}

/**
 * A full tool card:
 *
 *     8
 *     ┌ header (surfaceHighest), 12 padding ──────────────────────┐
 *     │ [icon 24] 8 title … status        8 [1.2s] 8 [status 20]  │
 *     │             description (2 below)                          │
 *     ├ body: 12 in from the sides, 8 below the header ───────────┤
 *     ├ permission footer ────────────────────────────────────────┤
 *     └ radius 8, surfaceHigh ─────────────────────────────────────┘
 *     8
 *
 * A self-heading tool (a patch, an attachment) has no header; an attachment
 * has no card either (`inline`).
 */
data class ToolCardLayout(
    val size: Size,
    val card: Rect,
    val header: Rect?,
    val icon: Rect?,
    val title: Rect?,
    val description: Rect?,
    val elapsed: Rect?,
    val statusIcon: Rect?,
    val body: Rect?,
    val footer: Rect?,
) {
    object Metrics {
        const val marginV = 8.0
        const val inlineMarginV = 1.0
        const val radius = 8.0
        const val headerPadding = 12.0
        const val iconSize = 24.0
        const val gap = 8.0
        const val descriptionMarginTop = 2.0
        const val statusIconSize = 20.0
        const val contentPaddingH = 12.0
        const val contentPaddingTop = 8.0
        const val elapsedMarginLeft = 8.0
    }

    class Header(
        val title: Measurable,
        val description: Measurable? = null,
        /** The running timer, while the tool runs. */
        val elapsed: Measurable? = null,
        /** Spinner or state glyph. */
        val statusIcon: Size? = null,
    )

    class Input(
        val width: Double,
        val maxContentWidth: Double = Double.MAX_VALUE,
        val header: Header?,
        /** The tool's own view, laid out `width - 24` wide. */
        val body: Measurable?,
        /** PermissionFooter, laid out the card's full width. */
        val footer: Measurable?,
        /** An attachment: no card, no padding. */
        val inline: Boolean = false,
    )

    companion object {
        operator fun invoke(input: Input): ToolCardLayout {
            val m = Metrics
            val (x, width) = ToolRowMetrics.cardFrame(input.width, input.maxContentWidth)
            val marginV = if (input.inline) m.inlineMarginV else m.marginV
            var y = 0.0

            var headerFrame: Rect? = null
            var icon: Rect? = null
            var title: Rect? = null
            var description: Rect? = null
            var elapsed: Rect? = null
            var statusIcon: Rect? = null
            val header = input.header
            if (header != null) {
                // Right to left: status glyph, timer, then the title takes the rest.
                var right = width - m.headerPadding
                var statusFrame: Rect? = null
                var elapsedFrame: Rect? = null
                val elapsedSize = header.elapsed?.size(width)
                val titleX = m.headerPadding + m.iconSize + m.gap
                header.statusIcon?.let { status ->
                    statusFrame = Rect(right - status.width, 0.0, status.width, status.height)
                    right -= status.width + m.gap
                }
                if (elapsedSize != null) {
                    elapsedFrame = Rect(right - elapsedSize.width, 0.0, elapsedSize.width, elapsedSize.height)
                    right -= elapsedSize.width + m.elapsedMarginLeft + m.gap
                }
                val titleWidth = max(0.0, right - titleX)
                val titleHeight = header.title.height(titleWidth)
                val descriptionHeight = header.description?.height(titleWidth)
                val columnHeight = titleHeight + (descriptionHeight?.let { m.descriptionMarginTop + it } ?: 0.0)
                val contentHeight = maxOf(m.iconSize, columnHeight, elapsedSize?.height ?: 0.0, statusFrame?.height ?: 0.0)
                val top = m.headerPadding
                fun centred(height: Double) = top + (contentHeight - height) / 2
                icon = Rect(m.headerPadding, centred(m.iconSize), m.iconSize, m.iconSize)
                val columnTop = centred(columnHeight)
                title = Rect(titleX, columnTop, titleWidth, titleHeight)
                if (descriptionHeight != null) {
                    description = Rect(titleX, columnTop + titleHeight + m.descriptionMarginTop, titleWidth, descriptionHeight)
                }
                elapsed = elapsedFrame?.let { it.copy(y = centred(it.height)) }
                statusIcon = statusFrame?.let { it.copy(y = centred(it.height)) }
                val headerHeight = contentHeight + 2 * m.headerPadding
                headerFrame = Rect(0.0, 0.0, width, headerHeight)
                y = headerHeight
            }

            var body: Rect? = null
            if (input.body != null) {
                val padH = if (input.inline) 0.0 else m.contentPaddingH
                val padTop = if (input.inline) 0.0 else m.contentPaddingTop
                val bodyWidth = max(0.0, width - 2 * padH)
                val height = input.body.height(bodyWidth)
                body = Rect(padH, y + padTop, bodyWidth, height)
                y += padTop + height
            }

            var footer: Rect? = null
            if (input.footer != null) {
                val height = input.footer.height(width)
                footer = Rect(0.0, y, width, height)
                y += height
            }

            return ToolCardLayout(
                size = Size(max(0.0, input.width), y + 2 * marginV),
                card = Rect(x, marginV, width, y),
                header = headerFrame,
                icon = icon,
                title = title,
                description = description,
                elapsed = elapsed,
                statusIcon = statusIcon,
                body = body,
                footer = footer,
            )
        }
    }
}

/**
 * The one-line activity row (compact tool calls, terminals, unknown tools):
 *
 *     2
 *     8 [icon 20] 10 label ………………… 8 [1.2s] 10 [status]   min 28, 3 padding
 *     ┌ body, once opened: surfaceHigh, 12 in, 8 down ┐       2 below the row
 *     [permission footer]
 *     2
 *
 * happy-app's compact row has no body; here a tapped row opens to the same
 * body a card would show.
 */
data class CompactToolRowLayout(
    val size: Size,
    /** The tap target (it opens and closes the body). */
    val header: Rect,
    val icon: Rect,
    val label: Rect,
    val elapsed: Rect?,
    val statusIcon: Rect?,
    /** The opened body's box, and its content inside the card padding. */
    val bodyBox: Rect?,
    val body: Rect?,
    val footer: Rect?,
) {
    object Metrics {
        const val marginV = 2.0
        const val minHeight = 28.0
        const val paddingH = 8.0
        const val paddingV = 3.0
        const val iconSize = 20.0
        const val gap = 10.0
        const val elapsedMarginLeft = 8.0
        const val bodyMarginTop = 2.0
        const val bodyRadius = 8.0
    }

    companion object {
        operator fun invoke(
            width: Double,
            maxContentWidth: Double = Double.MAX_VALUE,
            label: Measurable,
            elapsed: Measurable?,
            statusIcon: Size?,
            body: Measurable? = null,
            footer: Measurable?,
        ): CompactToolRowLayout {
            val m = Metrics
            val (x, cardWidth) = ToolRowMetrics.cardFrame(width, maxContentWidth)
            var right = x + cardWidth - m.paddingH
            var statusFrame: Rect? = null
            var elapsedFrame: Rect? = null
            if (statusIcon != null) {
                statusFrame = Rect(right - statusIcon.width, 0.0, statusIcon.width, statusIcon.height)
                right -= statusIcon.width + m.gap
            }
            if (elapsed != null) {
                val size = elapsed.size(cardWidth)
                elapsedFrame = Rect(right - size.width, 0.0, size.width, size.height)
                // The timer's own 8 margin sits inside the 10 gap before it.
                right -= size.width + m.elapsedMarginLeft + m.gap
            }
            val labelX = x + m.paddingH + m.iconSize + m.gap
            val labelWidth = max(0.0, right - labelX)
            val labelHeight = label.height(labelWidth)
            val contentHeight = maxOf(m.iconSize, labelHeight, elapsedFrame?.height ?: 0.0, statusFrame?.height ?: 0.0)
            val headerHeight = max(m.minHeight, contentHeight + 2 * m.paddingV)
            val top = m.marginV
            fun centred(height: Double) = top + (headerHeight - height) / 2
            var y = top + headerHeight
            var bodyBox: Rect? = null
            var bodyFrame: Rect? = null
            if (body != null) {
                val card = ToolCardLayout.Metrics
                val contentWidth = max(0.0, cardWidth - 2 * card.contentPaddingH)
                val height = body.height(contentWidth)
                y += m.bodyMarginTop
                bodyBox = Rect(x, y, cardWidth, card.contentPaddingTop + height)
                bodyFrame = Rect(x + card.contentPaddingH, y + card.contentPaddingTop, contentWidth, height)
                y += card.contentPaddingTop + height
            }
            var footerFrame: Rect? = null
            if (footer != null) {
                val height = footer.height(cardWidth)
                footerFrame = Rect(x, y, cardWidth, height)
                y += height
            }
            return CompactToolRowLayout(
                size = Size(max(0.0, width), y + m.marginV),
                header = Rect(x, top, cardWidth, headerHeight),
                icon = Rect(x + m.paddingH, centred(m.iconSize), m.iconSize, m.iconSize),
                label = Rect(labelX, centred(labelHeight), labelWidth, labelHeight),
                elapsed = elapsedFrame?.let { it.copy(y = centred(it.height)) },
                statusIcon = statusFrame?.let { it.copy(y = centred(it.height)) },
                bodyBox = bodyBox,
                body = bodyFrame,
                footer = footerFrame,
            )
        }
    }
}

/**
 * A vertical stack of boxes, the building block of tool bodies
 * (ToolSectionView, CommandView, CodeView, ToolError, the todo list…). Each
 * item has margins, padding and a horizontal placement; content is measured
 * at the width the padding leaves.
 */
data class ChatStackLayout(
    val size: Size,
    /** Each item's box (background, border). */
    val boxes: List<Rect>,
    /** Each item's content, inside its padding. */
    val contents: List<Rect>,
) {
    enum class Placement {
        /** As wide as the stack. */
        Fill,
        /** As wide as its content, on the left. */
        Leading,
        /** As wide as its content, centred. */
        Center,
    }

    class Item(
        val content: Measurable,
        val marginTop: Double = 0.0,
        val marginBottom: Double = 0.0,
        val padding: LayoutInsets = LayoutInsets.Zero,
        val placement: Placement = Placement.Fill,
        /** Content taller than this is clipped (ToolError's maxHeight). */
        val maxHeight: Double? = null,
    )

    companion object {
        operator fun invoke(width: Double, items: List<Item>, gap: Double = 0.0): ChatStackLayout {
            val w = max(0.0, width)
            var y = 0.0
            val boxes = mutableListOf<Rect>()
            val contents = mutableListOf<Rect>()
            items.forEachIndexed { index, item ->
                if (index > 0) y += gap
                y += item.marginTop
                val innerWidth = max(0.0, w - item.padding.horizontal)
                val measured = item.content.size(innerWidth)
                val contentWidth = if (item.placement == Placement.Fill) innerWidth else measured.width
                var boxHeight = measured.height + item.padding.vertical
                if (item.maxHeight != null) boxHeight = min(boxHeight, item.maxHeight)
                val boxWidth = contentWidth + item.padding.horizontal
                val x = if (item.placement == Placement.Center) (w - boxWidth) / 2 else 0.0
                boxes += Rect(x, y, boxWidth, boxHeight)
                contents += Rect(
                    x + item.padding.left,
                    y + item.padding.top,
                    contentWidth,
                    max(0.0, min(measured.height, boxHeight - item.padding.vertical)),
                )
                y += boxHeight + item.marginBottom
            }
            return ChatStackLayout(Size(w, y), boxes, contents)
        }

        /** The stack as content for an enclosing layout. */
        fun measurable(gap: Double = 0.0, items: (Double) -> List<Item>) =
            Measurable { width -> ChatStackLayout(width, items(width), gap).size }
    }
}

/**
 * PermissionFooter: a column of outlined buttons, 7 apart, stretched across
 * the card on a phone, in a list no taller than `maxListHeight` that scrolls
 * past it.
 *
 *     4
 *     6 ┌ 1 border, 7 ─ label (14/18, centred) ─ 7 ┐ 6   min 34
 *     …
 *     8
 */
data class PermissionFooterLayout(
    val size: Size,
    /** Where the buttons would be with the list scrolled to its top. */
    val buttons: List<Rect>,
    val labels: List<Rect>,
    /** The list's visible part, in the footer. */
    val list: Rect,
    /** The whole list, taller than [list] when it scrolls. */
    val listContentHeight: Double,
) {
    val scrolls get() = listContentHeight > list.height

    object Metrics {
        const val paddingH = 6.0
        const val paddingTop = 4.0
        const val paddingBottom = 8.0
        const val gap = 7.0
        const val buttonPaddingH = 10.0
        const val buttonPaddingV = 7.0
        const val buttonBorder = 1.0
        const val buttonMinHeight = 34.0
        const val buttonRadius = 7.0
        const val inactiveOpacity = 0.52
        /** min(260, 35% of the window): taller lists scroll. */
        const val maxListHeight = 260.0
    }

    companion object {
        /** `optionsScroll`'s maxHeight for a window [windowHeight] tall. */
        fun maxListHeight(windowHeight: Double): Double =
            min(Metrics.maxListHeight, swiftRound(max(0.0, windowHeight) * 0.35))

        operator fun invoke(width: Double, labels: List<Measurable>, maxListHeight: Double = Double.MAX_VALUE): PermissionFooterLayout {
            val m = Metrics
            val w = max(0.0, width)
            val buttonWidth = max(0.0, w - 2 * m.paddingH)
            val labelWidth = max(0.0, buttonWidth - 2 * (m.buttonPaddingH + m.buttonBorder))
            var y = m.paddingTop
            val buttons = mutableListOf<Rect>()
            val labelFrames = mutableListOf<Rect>()
            labels.forEachIndexed { index, label ->
                if (index > 0) y += m.gap
                val text = label.size(labelWidth)
                val height = max(m.buttonMinHeight, text.height + 2 * (m.buttonPaddingV + m.buttonBorder))
                buttons += Rect(m.paddingH, y, buttonWidth, height)
                labelFrames += Rect(m.paddingH + (buttonWidth - text.width) / 2, y + (height - text.height) / 2, text.width, text.height)
                y += height
            }
            val listContentHeight = y - m.paddingTop
            val list = Rect(m.paddingH, m.paddingTop, buttonWidth, min(listContentHeight, max(0.0, maxListHeight)))
            return PermissionFooterLayout(Size(w, list.maxY + m.paddingBottom), buttons, labelFrames, list, listContentHeight)
        }
    }
}

/**
 * A line of text with a fixed-size glyph before or after it: ToolError's
 * warning sign (top-aligned, 8 before the text), TaskView's status (centred,
 * 8 after the text).
 */
data class ChatAccessoryRowLayout(val size: Size, val leading: Rect?, val text: Rect, val trailing: Rect?) {
    enum class Alignment { Top, Center }

    companion object {
        operator fun invoke(
            width: Double,
            leading: Size? = null,
            text: Measurable,
            trailing: Size? = null,
            gap: Double = 8.0,
            alignment: Alignment = Alignment.Center,
        ): ChatAccessoryRowLayout {
            val w = max(0.0, width)
            val textX = leading?.let { it.width + gap } ?: 0.0
            val textRight = w - (trailing?.let { it.width + gap } ?: 0.0)
            val textWidth = max(0.0, textRight - textX)
            val textHeight = text.height(textWidth)
            val height = maxOf(textHeight, leading?.height ?: 0.0, trailing?.height ?: 0.0)
            fun y(itemHeight: Double) = if (alignment == Alignment.Top) 0.0 else (height - itemHeight) / 2
            return ChatAccessoryRowLayout(
                size = Size(w, height),
                leading = leading?.let { Rect(0.0, y(it.height), it.width, it.height) },
                text = Rect(textX, y(textHeight), textWidth, textHeight),
                trailing = trailing?.let { Rect(w - it.width, y(it.height), it.width, it.height) },
            )
        }
    }
}

/**
 * FileView, a picture a person attached: the picture at its own size, at
 * most 280 wide and 360 tall (4:3 at 280 when the message doesn't give its
 * size) and never wider than the row, rounded 8, its name 4 under it, 12 and
 * 8 inside.
 */
data class FileViewLayout(val size: Size, val image: Rect, val name: Rect) {
    object Metrics {
        const val paddingH = 12.0
        const val paddingV = 8.0
        const val gap = 4.0
        const val radius = 8.0
        const val maxImageWidth = 280.0
        const val maxImageHeight = 360.0
        const val defaultAspect = 4.0 / 3.0
        const val badgeSize = 24.0
        const val badgeInset = 4.0
        const val badgeIconSize = 20.0
        const val fadeIn = 0.15
    }

    companion object {
        operator fun invoke(width: Double, imageWidth: Double?, imageHeight: Double?, name: Measurable): FileViewLayout {
            val m = Metrics
            val available = max(0.0, width - 2 * m.paddingH)
            var picture = imageSize(imageWidth, imageHeight)
            if (picture.width > available && picture.width > 0) {
                picture = Size(available, picture.height * available / picture.width)
            }
            val image = Rect(m.paddingH, m.paddingV, picture.width, picture.height)
            val nameHeight = name.height(available)
            val nameFrame = Rect(m.paddingH, image.maxY + m.gap, available, nameHeight)
            return FileViewLayout(Size(max(0.0, width), nameFrame.maxY + m.paddingV), image, nameFrame)
        }

        /** The picture's size before the row has a say. */
        fun imageSize(width: Double?, height: Double?): Size {
            val m = Metrics
            val w = width ?: 0.0
            val h = height ?: 0.0
            val aspect = if (w > 0 && h > 0) w / h else m.defaultAspect
            var displayWidth = min(if (w > 0) w else m.maxImageWidth, m.maxImageWidth)
            var displayHeight = displayWidth / aspect
            if (displayHeight > m.maxImageHeight) {
                displayHeight = m.maxImageHeight
                displayWidth = displayHeight * aspect
            }
            return Size(displayWidth, displayHeight)
        }

        /** How many pixels to decode a picture of [size] pixels to: no more than FileView draws on a 3x screen. */
        fun decodedSize(size: Size): Size {
            if (size.width <= 0 || size.height <= 0) return size
            val scale = minOf(1.0, 3 * Metrics.maxImageWidth / size.width, 3 * Metrics.maxImageHeight / size.height)
            return Size(swiftRound(size.width * scale), swiftRound(size.height * scale))
        }

        /** The failed picture's badge, in the picture. */
        fun badge(picture: Size): Rect {
            val m = Metrics
            return Rect(picture.width - m.badgeInset - m.badgeSize, m.badgeInset, m.badgeSize, m.badgeSize)
        }
    }
}
