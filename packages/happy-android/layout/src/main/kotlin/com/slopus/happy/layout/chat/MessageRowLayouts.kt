package com.slopus.happy.layout.chat

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.max
import kotlin.math.min

// One function per chat row, after MessageView.tsx and its neighbours
// (happy-ios Chat/MessageRowLayouts.swift). Every row is laid out in a column
// at most `maxContentWidth` wide, centred in the list. Frames are relative to
// the row.

/** The centred column every row draws in. */
class ChatColumn(rowWidth: Double, maxContentWidth: Double = Double.MAX_VALUE) {
    val width = min(max(0.0, rowWidth), max(0.0, maxContentWidth))
    val x = (max(0.0, rowWidth) - width) / 2
}

/**
 * UserMessageFrame and UserTextBlock:
 *
 *     6
 *     [author 20, 4]                      (another participant's message)
 *     ┌ bubble: 12 ─ markdown ─ 12 ┐      right-aligned, 16 from the edge,
 *     └ 4 above and below           ┘     hugs its text up to the column
 *     4 (6 under a goal or a command's arguments)
 *     [/command chip, 4]  or  [◎ Sent as a goal, 4]
 *     2, status line 14, 4                always there, so a status that
 *                                         appears or clears never moves rows
 */
data class UserMessageLayout(
    val size: Size,
    val author: Rect?,
    val bubble: Rect?,
    /** The bubble's markdown, placed at [markdownOrigin]. */
    val markdown: MarkdownLayout?,
    val markdownOrigin: Point,
    val chip: Rect?,
    val chipLabel: Rect?,
    val goalIcon: Rect?,
    val goalLabel: Rect?,
    /** The status line, as wide as the column; its text aligns to the bubble's side. */
    val status: Rect,
    val alignsLeft: Boolean,
) {
    object Metrics {
        const val paddingH = 16.0
        const val paddingTop = 6.0
        const val authorMarginBottom = 4.0
        const val authorPaddingH = 12.0
        const val bubblePaddingH = 12.0
        const val bubblePaddingV = 4.0
        const val bubbleRadius = 12.0
        const val bubbleMarginBottom = 4.0
        const val bubbleBeforeChipMarginBottom = 6.0
        const val chipPaddingH = 10.0
        const val chipPaddingV = 2.0
        const val chipRadius = 10.0
        const val chipBorder = 1.0
        const val chipMarginBottom = 4.0
        const val goalIconSize = 16.0
        const val goalGap = 8.0
        const val goalMarginBottom = 4.0
        const val statusMarginTop = 2.0
        const val statusHeight = 14.0
        const val statusMarginBottom = 4.0
        /** The dimmed bubble of a message the agent hasn't taken yet. */
        const val pendingOpacity = 0.45
    }

    sealed interface Content {
        /** A plain message. */
        class Text(val blocks: List<MarkdownLayout.Block>) : Content
        /** A slash command: its chip, under its arguments when it has any. */
        class Command(val chip: Measurable, val arguments: List<MarkdownLayout.Block>?) : Content
        /** `/goal …`: the goal in a bubble, "Sent as a goal" under it. */
        class Goal(val blocks: List<MarkdownLayout.Block>, val sentLabel: Measurable) : Content
    }

    class Input(
        val width: Double,
        val maxContentWidth: Double = Double.MAX_VALUE,
        /** Another participant's name; their messages sit on the left. */
        val author: Measurable? = null,
        val content: Content,
    )

    companion object {
        operator fun invoke(input: Input): UserMessageLayout {
            val m = Metrics
            val column = ChatColumn(input.width, input.maxContentWidth)
            val left = column.x + m.paddingH
            val right = column.x + column.width - m.paddingH
            val inner = max(0.0, right - left)
            val fromOther = input.author != null

            fun place(width: Double) = if (fromOther) left else right - width

            var y = m.paddingTop
            var author: Rect? = null
            if (input.author != null) {
                val available = max(0.0, inner - 2 * m.authorPaddingH)
                val size = input.author.size(available)
                author = Rect(left + m.authorPaddingH, y, size.width, size.height)
                y += size.height + m.authorMarginBottom
            }

            class Bubble(val frame: Rect, val markdown: MarkdownLayout, val origin: Point)

            fun makeBubble(blocks: List<MarkdownLayout.Block>, marginBottom: Double): Bubble {
                val available = max(0.0, inner - 2 * m.bubblePaddingH)
                val natural = MarkdownLayout.naturalWidth(blocks, available)
                val markdown = MarkdownLayout(natural, blocks)
                val width = natural + 2 * m.bubblePaddingH
                val frame = Rect(place(width), y, width, markdown.size.height + 2 * m.bubblePaddingV)
                y += frame.height + marginBottom
                return Bubble(frame, markdown, Point(frame.minX + m.bubblePaddingH, frame.minY + m.bubblePaddingV))
            }

            var bubble: Bubble? = null
            var chip: Rect? = null
            var chipLabel: Rect? = null
            var goalIcon: Rect? = null
            var goalLabel: Rect? = null
            when (val content = input.content) {
                is Content.Text -> bubble = makeBubble(content.blocks, m.bubbleMarginBottom)
                is Content.Command -> {
                    if (content.arguments != null) bubble = makeBubble(content.arguments, m.bubbleBeforeChipMarginBottom)
                    val labelSize = content.chip.size(max(0.0, inner - 2 * (m.chipPaddingH + m.chipBorder)))
                    val width = labelSize.width + 2 * (m.chipPaddingH + m.chipBorder)
                    val frame = Rect(place(width), y, width, labelSize.height + 2 * (m.chipPaddingV + m.chipBorder))
                    chip = frame
                    chipLabel = Rect(
                        frame.minX + m.chipPaddingH + m.chipBorder,
                        frame.minY + m.chipPaddingV + m.chipBorder,
                        labelSize.width,
                        labelSize.height,
                    )
                    y += frame.height + m.chipMarginBottom
                }
                is Content.Goal -> {
                    bubble = makeBubble(content.blocks, m.bubbleBeforeChipMarginBottom)
                    val labelSize = content.sentLabel.size(max(0.0, inner - m.goalIconSize - m.goalGap))
                    val rowHeight = max(m.goalIconSize, labelSize.height)
                    val width = m.goalIconSize + m.goalGap + labelSize.width
                    val x = place(width)
                    goalIcon = Rect(x, y + (rowHeight - m.goalIconSize) / 2, m.goalIconSize, m.goalIconSize)
                    goalLabel = Rect(x + m.goalIconSize + m.goalGap, y + (rowHeight - labelSize.height) / 2, labelSize.width, labelSize.height)
                    y += rowHeight + m.goalMarginBottom
                }
            }

            y += m.statusMarginTop
            val status = Rect(left, y, inner, m.statusHeight)
            y += m.statusHeight + m.statusMarginBottom
            return UserMessageLayout(
                size = Size(max(0.0, input.width), y),
                author = author,
                bubble = bubble?.frame,
                markdown = bubble?.markdown,
                markdownOrigin = bubble?.origin ?: Point.Zero,
                chip = chip,
                chipLabel = chipLabel,
                goalIcon = goalIcon,
                goalLabel = goalLabel,
                status = status,
                alignsLeft = fromOther,
            )
        }
    }
}

/**
 * AgentTextBlock: markdown 16 in from each side, 10 above and below, and the
 * copy glyph on a 20 line under it, flush with the text.
 */
data class AgentTextLayout(
    val size: Size,
    val markdown: MarkdownLayout,
    val markdownOrigin: Point,
    val copyButton: Rect,
    val copyIcon: Rect,
) {
    object Metrics {
        const val marginH = 16.0
        const val marginV = 10.0
        const val copyHeight = 20.0
        const val copyIconSize = 16.0
        /** COPY_HIT_SLOP: the glyph is small, its touch target is not. */
        val copyHitSlop = LayoutInsets(top = 14, left = 14, bottom = 14, right = 20)
    }

    companion object {
        operator fun invoke(width: Double, maxContentWidth: Double = Double.MAX_VALUE, blocks: List<MarkdownLayout.Block>): AgentTextLayout {
            val m = Metrics
            val column = ChatColumn(width, maxContentWidth)
            val x = column.x + m.marginH
            val markdown = MarkdownLayout(max(0.0, column.width - 2 * m.marginH), blocks)
            val copyY = m.marginV + markdown.size.height
            return AgentTextLayout(
                size = Size(max(0.0, width), copyY + m.copyHeight + m.marginV),
                markdown = markdown,
                markdownOrigin = Point(x, m.marginV),
                copyButton = Rect(x, copyY, m.copyIconSize, m.copyHeight),
                copyIcon = Rect(x, copyY + (m.copyHeight - m.copyIconSize) / 2, m.copyIconSize, m.copyIconSize),
            )
        }
    }
}

/**
 * AgentEventBlock, and the compact placeholder for a record this build can't
 * show: one centred line, 8 in from the sides, 8 above and below.
 */
data class ChatEventLayout(val size: Size, val label: Rect) {
    object Metrics {
        const val marginH = 8.0
        const val paddingV = 8.0
    }

    companion object {
        operator fun invoke(width: Double, maxContentWidth: Double = Double.MAX_VALUE, label: Measurable): ChatEventLayout {
            val m = Metrics
            val column = ChatColumn(width, maxContentWidth)
            val inner = max(0.0, column.width - 2 * m.marginH)
            val text = label.size(inner)
            return ChatEventLayout(
                size = Size(max(0.0, width), text.height + 2 * m.paddingV),
                label = Rect(column.x + m.marginH + (inner - text.width) / 2, m.paddingV, text.width, text.height),
            )
        }
    }
}

/**
 * AgentWorkGroupHeader: "Worked for 2m3s ›", one line, always 44 tall so the
 * trailing copy of an expanded group lands where the leading one was.
 */
data class WorkGroupHeaderLayout(
    val size: Size,
    /** The tap target. */
    val header: Rect,
    val label: Rect,
    val chevron: Rect,
) {
    object Metrics {
        const val marginV = 8.0
        const val marginH = 16.0
        const val minHeight = 28.0
        const val paddingV = 4.0
        const val gap = 6.0
        const val chevronSize = 13.0
        const val pressedOpacity = 0.6
    }

    companion object {
        operator fun invoke(width: Double, maxContentWidth: Double = Double.MAX_VALUE, label: Measurable): WorkGroupHeaderLayout {
            val m = Metrics
            val column = ChatColumn(width, maxContentWidth)
            val x = column.x + m.marginH
            val inner = max(0.0, column.width - 2 * m.marginH)
            val text = label.size(max(0.0, inner - m.gap - m.chevronSize))
            val height = max(m.minHeight, text.height + 2 * m.paddingV)
            return WorkGroupHeaderLayout(
                size = Size(max(0.0, width), height + 2 * m.marginV),
                header = Rect(x, m.marginV, inner, height),
                label = Rect(x, m.marginV + (height - text.height) / 2, text.width, text.height),
                chevron = Rect(x + text.width + m.gap, m.marginV + (height - m.chevronSize) / 2, m.chevronSize, m.chevronSize),
            )
        }
    }
}

/**
 * ChatFooter, just past the newest message: 4 above, 2 below, and the
 * "permissions shown in terminal only" box when the terminal has control.
 */
data class ChatFooterLayout(val size: Size, val warning: Rect?, val icon: Rect?, val label: Rect?) {
    object Metrics {
        const val paddingTop = 4.0
        const val paddingBottom = 2.0
        const val warningMarginTop = 4.0
        const val warningMarginH = 32.0
        const val warningPaddingH = 12.0
        const val warningPaddingV = 4.0
        const val warningRadius = 8.0
        const val iconSize = 16.0
        const val iconGap = 6.0
    }

    companion object {
        operator fun invoke(width: Double, warning: Measurable?): ChatFooterLayout {
            val m = Metrics
            val w = max(0.0, width)
            if (warning == null) return ChatFooterLayout(Size(w, m.paddingTop + m.paddingBottom), null, null, null)
            val maxBox = max(0.0, w - 2 * m.warningMarginH)
            val textSize = warning.size(max(0.0, maxBox - 2 * m.warningPaddingH - m.iconSize - m.iconGap))
            val rowHeight = max(m.iconSize, textSize.height)
            val boxWidth = textSize.width + m.iconSize + m.iconGap + 2 * m.warningPaddingH
            val box = Rect((w - boxWidth) / 2, m.paddingTop + m.warningMarginTop, boxWidth, rowHeight + 2 * m.warningPaddingV)
            return ChatFooterLayout(
                size = Size(w, box.maxY + m.paddingBottom),
                warning = box,
                icon = Rect(box.minX + m.warningPaddingH, box.minY + m.warningPaddingV + (rowHeight - m.iconSize) / 2, m.iconSize, m.iconSize),
                label = Rect(
                    box.minX + m.warningPaddingH + m.iconSize + m.iconGap,
                    box.minY + m.warningPaddingV + (rowHeight - textSize.height) / 2,
                    textSize.width,
                    textSize.height,
                ),
            )
        }
    }
}

/**
 * ChatList's OlderEnd: the 32 slot past the oldest message holding the
 * spinner, Retry or Load more. Always there, so a status change never moves
 * the conversation.
 */
data class ChatOlderEndLayout(val size: Size, val control: Rect) {
    companion object {
        const val height = 32.0

        operator fun invoke(width: Double, control: Size): ChatOlderEndLayout {
            val w = max(0.0, width)
            return ChatOlderEndLayout(
                Size(w, height),
                Rect((w - control.width) / 2, (height - control.height) / 2, control.width, control.height),
            )
        }
    }
}

/**
 * LongPressCopyable's menu, in window coordinates: 152 by 44, 8 above the
 * message, or 8 below it when above would come within 12 of the top (and
 * never within 12 of the bottom). Its right edge lines up with the
 * message's, kept 12 from either side. Inside it: the copy glyph 14 in, then
 * the label 10 after it.
 */
data class CopyMenuLayout(
    val menu: Rect,
    /** In the menu's own coordinates. */
    val icon: Rect,
    val label: Rect,
) {
    object Metrics {
        const val width = 152.0
        const val height = 44.0
        const val gap = 8.0
        const val screenMargin = 12.0
        const val radius = 14.0
        const val paddingH = 14.0
        const val iconSize = 17.0
        const val iconGap = 10.0
        const val shadowOpacity = 0.1
        const val shadowRadius = 16.0
        const val shadowOffsetY = 6.0
        /** AnimatedPopup: in over 190 ms (ease-out cubic) from 0.96 and 8 lower, out over 140 ms. */
        const val enterDuration = 0.19
        const val enterScale = 0.96
        const val enterOffsetY = 8.0
        const val exitDuration = 0.14
        /** The long press that opens it. */
        const val pressDuration = 0.4
    }

    companion object {
        operator fun invoke(anchor: Rect, window: Size, label: Measurable): CopyMenuLayout {
            val m = Metrics
            val above = anchor.minY - m.height - m.gap
            val top = if (above >= m.screenMargin) above else min(anchor.maxY + m.gap, window.height - m.height - m.screenMargin)
            val left = max(m.screenMargin, min(window.width - m.width - m.screenMargin, anchor.maxX - m.width))
            val icon = Rect(m.paddingH, (m.height - m.iconSize) / 2, m.iconSize, m.iconSize)
            val labelX = icon.maxX + m.iconGap
            val size = label.size(max(0.0, m.width - labelX - m.paddingH))
            return CopyMenuLayout(
                Rect(left, top, m.width, m.height),
                icon,
                Rect(labelX, (m.height - size.height) / 2, size.width, size.height),
            )
        }
    }
}
