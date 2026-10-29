package com.slopus.happy.layout.chat

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.max
import kotlin.math.min

/**
 * The session screen (SessionView.tsx with AgentContentView's floating dock,
 * the phone layout; happy-ios Chat/ChatScreenLayout.swift):
 *
 *   - the list fills the screen and runs under the navigation bar and the
 *     dock; its insets keep the newest message just above the dock and the
 *     oldest just below the bar (+12);
 *   - the dock is docked to the keyboard: its bottom is the keyboard's top,
 *     or the safe area's bottom while the keyboard is down. During an
 *     interactive dismiss the keyboard top follows the finger, and so does
 *     the dock, because this one function places both;
 *   - inside the dock, the bars above the composer stack top to bottom, 8 in
 *     from the sides unless they run full width, then the composer;
 *   - the scroll-to-bottom button floats 16 above the composer's card,
 *     centred;
 *   - the header's scrim covers the bar and 8 past it, and an empty chat's
 *     placeholder is centred between the bar and the dock.
 *
 * On a tablet (`docked`) nothing floats: the list starts under the bar and
 * stops at the dock. A phone on its side (`hidesHeader`) is docked the same
 * way with no header: a 44 round back button floats 16 in and 8 under the
 * status bar.
 */
data class ChatScreenLayout(
    val list: Rect,
    /** Inset at the newest edge (the bottom of the screen). */
    val newestEdgeInset: Double,
    /** Inset at the oldest edge (under the navigation bar). */
    val oldestEdgeInset: Double,
    val dock: Rect,
    /** Accessory frames, in the dock. */
    val accessories: List<Rect>,
    /** The composer, in the dock. */
    val composer: Rect,
    val scrollButton: Rect,
    /** The fade behind the dock, from 28 above it to the screen's bottom. */
    val scrim: Rect,
    /** The scrim behind the navigation bar. */
    val headerScrim: Rect,
    /** Where an empty chat's placeholder is centred: under the bar, above the dock. */
    val placeholder: Rect,
    val backButton: Rect?,
    val statusBarShadow: Rect?,
    /** What hangs under the header, full width; null without a header. */
    val headerAccessory: Rect?,
    /** The header's bottom, past what hangs under it. */
    val headerBottom: Double,
) {
    object Metrics {
        const val accessoryPaddingH = 8.0
        const val olderEdgeExtra = 12.0
        const val newerEdgeExtra = 8.0
        const val scrollButtonSize = 32.0
        const val scrollButtonGap = 16.0
        /** The reader is "away" from the newest message past this. */
        const val scrollButtonThreshold = 300.0
        /** The bars above the composer hide past 48 and come back within 16. */
        const val dockDetailsHideOffset = 48.0
        const val dockDetailsShowOffset = 16.0
        const val scrimExtra = 28.0
        const val dockedScrollButtonGap = 4.0
        const val dockedOlderEdgeExtra = 32.0
        const val headerScrimOverhang = 8.0
        const val headerlessOlderEdgeExtra = 12.0
        const val backButtonSize = 44.0
        const val backButtonLeft = 16.0
        const val backButtonTop = 8.0
        const val regularMaxContentWidth = 800.0
    }

    /** A bar above the composer. */
    class Accessory(
        val content: Measurable,
        /** In the composer's column, 8 in from its sides; otherwise the full width. */
        val inset: Boolean,
    ) {
        companion object {
            fun inset(content: Measurable) = Accessory(content, true)
            fun fullWidth(content: Measurable) = Accessory(content, false)
        }
    }

    class Input(
        val size: Size,
        /** Safe area, with the navigation bar in `top`. */
        val safeArea: LayoutInsets,
        /** The keyboard's top in this view, or null while it is down. */
        val keyboardTop: Double?,
        val accessories: List<Accessory> = emptyList(),
        val composerHeight: Double,
        /** Where the composer's card starts below the composer's top. */
        val composerCardOffset: Double = 0.0,
        val maxContentWidth: Double = Double.MAX_VALUE,
        val docked: Boolean = false,
        val accessoryPaddingH: Double = Metrics.accessoryPaddingH,
        val hidesHeader: Boolean = false,
        val headerAccessory: Double = 0.0,
    )

    companion object {
        operator fun invoke(input: Input): ChatScreenLayout {
            val m = Metrics
            val width = max(0.0, input.size.width)
            val height = max(0.0, input.size.height)
            var list = Rect(0.0, 0.0, width, height)

            val column = ChatColumn(width, input.maxContentWidth)
            val accessoryX = column.x + input.accessoryPaddingH
            val accessoryWidth = max(0.0, column.width - 2 * input.accessoryPaddingH)
            var y = 0.0
            val frames = mutableListOf<Rect>()
            for (accessory in input.accessories) {
                val x = if (accessory.inset) accessoryX else 0.0
                val w = if (accessory.inset) accessoryWidth else width
                val h = accessory.content.height(w)
                frames += Rect(x, y, w, h)
                y += h
            }
            val composer = Rect(0.0, y, width, max(0.0, input.composerHeight))
            val dockHeight = y + max(0.0, input.composerHeight)

            val restingBottom = height - input.safeArea.bottom
            val bottom = min(restingBottom, input.keyboardTop ?: restingBottom)
            val dock = Rect(0.0, bottom - dockHeight, width, dockHeight)
            val accessory = if (input.hidesHeader) 0.0 else max(0.0, input.headerAccessory)
            val headerAccessory = if (input.hidesHeader) null else Rect(0.0, input.safeArea.top, width, accessory)
            val headerBottom = input.safeArea.top + accessory
            var newestEdgeInset = max(0.0, height - dock.minY) + m.newerEdgeExtra
            var oldestEdgeInset = headerBottom + m.olderEdgeExtra
            var scrollButton = Rect(
                (width - m.scrollButtonSize) / 2,
                dock.minY + composer.minY + min(max(0.0, input.composerCardOffset), composer.height) - m.scrollButtonGap - m.scrollButtonSize,
                m.scrollButtonSize,
                m.scrollButtonSize,
            )
            var scrim = Rect(0.0, dock.minY - m.scrimExtra, width, max(0.0, height - dock.minY + m.scrimExtra))
            var headerScrim = Rect(0.0, 0.0, width, max(0.0, input.safeArea.top) + m.headerScrimOverhang)
            val placeholder = Rect(0.0, headerBottom, width, max(0.0, dock.minY - headerBottom))

            var backButton: Rect? = null
            var statusBarShadow: Rect? = null
            if (input.docked || input.hidesHeader) {
                val top = max(0.0, input.safeArea.top)
                list = Rect(0.0, top + accessory, width, max(0.0, dock.minY - top - accessory))
                newestEdgeInset = m.newerEdgeExtra
                oldestEdgeInset = if (input.hidesHeader) m.headerlessOlderEdgeExtra else top + m.dockedOlderEdgeExtra
                scrollButton = scrollButton.copy(y = list.maxY - m.dockedScrollButtonGap - m.scrollButtonSize)
                scrim = Rect(0.0, dock.minY, width, 0.0)
                headerScrim = Rect(0.0, 0.0, width, 0.0)
            }
            if (input.hidesHeader) {
                val top = max(0.0, input.safeArea.top)
                backButton = Rect(m.backButtonLeft, top + m.backButtonTop, m.backButtonSize, m.backButtonSize)
                statusBarShadow = Rect(0.0, 0.0, width, top)
            }
            return ChatScreenLayout(
                list, newestEdgeInset, oldestEdgeInset, dock, frames, composer, scrollButton, scrim, headerScrim,
                placeholder, backButton, statusBarShadow, headerAccessory, headerBottom,
            )
        }

        /** ChatList's header backdrop: stronger once anything is scrolled under the header. */
        fun headerBackdropVisible(distanceFromOldest: Double) = distanceFromOldest > 0.5

        /**
         * ChatList's hysteresis for the bars above the composer: shown at the
         * newest message, hidden once the reader is 48 back, shown again only
         * within 16.
         */
        fun dockDetailsVisible(distanceFromNewest: Double, currentlyVisible: Boolean) =
            if (currentlyVisible) distanceFromNewest <= Metrics.dockDetailsHideOffset
            else distanceFromNewest <= Metrics.dockDetailsShowOffset

        fun showsScrollButton(distanceFromNewest: Double) = distanceFromNewest > Metrics.scrollButtonThreshold
    }
}

/**
 * ChatHeaderView's title on a phone: a capsule as tall as the controls
 * beside it, hugging its text up to `maxWidth`, the text centred.
 *
 *     ┌ 14 ─────── title (one line) ─────── 14 ┐  44
 *     │       subtitle  4  [≈ +12 -3]          │
 *     └────────────────────────────────────────┘
 *
 * The subtitle gives way to the line changes, which never shrink. Frames are
 * in the capsule's own space.
 */
data class ChatHeaderTitleLayout(val size: Size, val title: Rect, val subtitle: Rect?, val changes: Rect?) {
    object Metrics {
        const val height = 44.0
        const val paddingH = 14.0
        const val rowGap = 4.0
        const val headerPaddingH = 16.0
        const val controlSize = 44.0
        const val pillGap = 14.0
        /** The session avatar in the right control. */
        const val avatarSize = 28.0
    }

    companion object {
        operator fun invoke(maxWidth: Double, title: Measurable, subtitle: Measurable?, changes: Size?): ChatHeaderTitleLayout {
            val m = Metrics
            val inner = max(0.0, maxWidth - 2 * m.paddingH)
            val titleSize = title.size(inner)
            val titleWidth = min(titleSize.width, inner)

            var rowWidth = 0.0
            var rowHeight = 0.0
            var subtitleSize: Size? = null
            if (changes != null) {
                rowWidth = changes.width
                rowHeight = changes.height
            }
            if (subtitle != null) {
                val room = max(0.0, inner - rowWidth - (if (changes == null) 0.0 else m.rowGap))
                val measured = subtitle.size(room)
                val s = Size(min(measured.width, room), measured.height)
                subtitleSize = s
                rowWidth += s.width + (if (changes == null) 0.0 else m.rowGap)
                rowHeight = max(rowHeight, s.height)
            }

            val contentWidth = max(titleWidth, rowWidth)
            val contentHeight = titleSize.height + rowHeight
            val size = Size(contentWidth + 2 * m.paddingH, m.height)
            val top = (m.height - contentHeight) / 2
            val titleFrame = Rect((size.width - titleWidth) / 2, top, titleWidth, titleSize.height)

            val rowTop = top + titleSize.height
            var x = (size.width - rowWidth) / 2
            var subtitleFrame: Rect? = null
            if (subtitleSize != null) {
                subtitleFrame = Rect(x, rowTop + (rowHeight - subtitleSize.height) / 2, subtitleSize.width, subtitleSize.height)
                x += subtitleSize.width + m.rowGap
            }
            val changesFrame = changes?.let { Rect(x, rowTop + (rowHeight - it.height) / 2, it.width, it.height) }
            return ChatHeaderTitleLayout(size, titleFrame, subtitleFrame, changesFrame)
        }

        /**
         * How wide the capsule may grow in a header [headerWidth] wide: the
         * same inset from both edges, clearing the wider control, so it stays
         * centred on the header.
         */
        fun maxWidth(headerWidth: Double): Double {
            val m = Metrics
            return max(0.0, headerWidth - 2 * (m.headerPaddingH + m.controlSize + m.pillGap))
        }
    }
}

/**
 * ChatHeaderView without glass (a tablet): the title over the workspace or
 * branch and the line changes, at the start of the header's row rather than
 * in a capsule. The row is the bar's width held to `maxWidth`
 * (`layout.headerMaxWidth`) and centred, 16 in from its sides; the column is
 * centred top to bottom in the title view.
 *
 * [bar] is the bar's frame in the title view's coordinates (the view's own
 * bounds when the title view is the bar); [size] is the title view's. The
 * row is placed from the bar, then clipped to the title view, where its
 * touches land, and the text is measured to what is left.
 */
data class ChatPlainHeaderLayout(
    /** The column the title and its row take, in the title view. */
    val column: Rect,
    /** In the column, as are [subtitle] and [changes]. */
    val title: Rect,
    val subtitle: Rect?,
    val changes: Rect?,
) {
    object Metrics {
        /** The header's `content` paddingHorizontal. */
        const val paddingH = 16.0
        /** subtitleRow's gap. */
        const val rowGap = 4.0
    }

    companion object {
        operator fun invoke(bar: Rect, size: Size, maxWidth: Double, title: Measurable, subtitle: Measurable?, changes: Size?): ChatPlainHeaderLayout {
            val (x, width) = row(bar, size, maxWidth)
            val titleSize = title.size(width)
            var subtitleSize: Size? = null
            var rowHeight = 0.0
            if (subtitle != null) {
                val measured = subtitle.size(subtitleWidth(width, changes))
                subtitleSize = measured
                rowHeight = measured.height
            }
            if (changes != null) rowHeight = max(rowHeight, changes.height)
            val height = titleSize.height + rowHeight
            val column = Rect(x, (size.height - height) / 2, width, height)
            val rowTop = titleSize.height
            var rowX = 0.0
            val subtitleFrame = subtitleSize?.let { s ->
                Rect(rowX, rowTop + (rowHeight - s.height) / 2, s.width, s.height).also { rowX += s.width + Metrics.rowGap }
            }
            val changesFrame = changes?.let { Rect(rowX, rowTop + (rowHeight - it.height) / 2, it.width, it.height) }
            return ChatPlainHeaderLayout(column, Rect(0.0, 0.0, titleSize.width, titleSize.height), subtitleFrame, changesFrame)
        }

        /** Where the column starts in the title view and what it has across. */
        private fun row(bar: Rect, size: Size, maxWidth: Double): Pair<Double, Double> {
            val barWidth = max(0.0, bar.width)
            val row = min(barWidth, max(0.0, maxWidth))
            val start = bar.minX + (barWidth - row) / 2 + Metrics.paddingH
            val end = start + max(0.0, row - 2 * Metrics.paddingH)
            val viewWidth = max(0.0, size.width)
            val x = min(max(start, 0.0), viewWidth)
            return x to max(0.0, min(end, viewWidth) - x)
        }

        /** What the title is measured to. */
        fun textWidth(bar: Rect, size: Size, maxWidth: Double): Double = row(bar, size, maxWidth).second

        /** What the subtitle is measured to: the column less the line changes and the gap before them. */
        fun subtitleWidth(textWidth: Double, changes: Size?): Double =
            max(0.0, textWidth - (changes?.let { it.width + Metrics.rowGap } ?: 0.0))
    }
}

/**
 * ChatHeaderView's scrim: MobileHeaderScrim's strong variant, its wash at
 * 0.80 while the conversation starts below the header and 0.96 once content
 * is scrolled under it, faded over 200 ms.
 */
object ChatHeaderScrim {
    const val restingOpacity = 0.80
    const val underlapOpacity = 0.96
    const val fadeDuration = 0.2
}

/**
 * InactiveArchivedHint: the notice (and, without Resume, "To resume it from
 * the terminal:") 8 in, then 10 below it either the Resume button, 8 in and
 * 44 tall with its label in the middle, or the command block, edge to edge,
 * 16 and 12 inside, the commands on the left and the copy glyph on the
 * right. 12 above, 10 below.
 */
data class InactiveHintLayout(
    val size: Size,
    val notice: Rect,
    val terminalNotice: Rect?,
    val button: Rect?,
    val buttonLabel: Rect?,
    val command: Rect?,
    val commandText: Rect?,
    val commandIcon: Rect?,
) {
    object Metrics {
        const val paddingTop = 12.0
        const val paddingBottom = 10.0
        const val gap = 10.0
        const val textInset = 8.0
        const val textGap = 4.0
        const val buttonHeight = 44.0
        const val buttonInset = 8.0
        const val radius = 18.0
        const val commandMinHeight = 48.0
        const val commandPaddingH = 16.0
        const val commandPaddingV = 12.0
        const val commandGap = 8.0
        const val iconSize = 16.0
        const val iconTop = 1.0
        const val resumingOpacity = 0.6
    }

    sealed interface Action {
        object None : Action
        class Resume(val label: Measurable) : Action
        class Command(val text: Measurable) : Action
    }

    companion object {
        operator fun invoke(width: Double, notice: Measurable, terminalNotice: Measurable?, action: Action): InactiveHintLayout {
            val m = Metrics
            val w = max(0.0, width)
            val textWidth = max(0.0, w - 2 * m.textInset)
            var y = m.paddingTop
            val noticeHeight = notice.height(textWidth)
            val noticeFrame = Rect(m.textInset, y, textWidth, noticeHeight)
            y += noticeHeight
            var terminalFrame: Rect? = null
            if (terminalNotice != null) {
                y += m.textGap
                val height = terminalNotice.height(textWidth)
                terminalFrame = Rect(m.textInset, y, textWidth, height)
                y += height
            }
            var button: Rect? = null
            var buttonLabel: Rect? = null
            var command: Rect? = null
            var commandText: Rect? = null
            var commandIcon: Rect? = null
            when (action) {
                Action.None -> Unit
                is Action.Resume -> {
                    y += m.gap
                    val b = Rect(m.buttonInset, y, max(0.0, w - 2 * m.buttonInset), m.buttonHeight)
                    val labelSize = action.label.size(b.width)
                    button = b
                    buttonLabel = Rect(b.midX - labelSize.width / 2, b.midY - labelSize.height / 2, labelSize.width, labelSize.height)
                    y += m.buttonHeight
                }
                is Action.Command -> {
                    y += m.gap
                    val innerWidth = max(0.0, w - 2 * m.commandPaddingH - m.commandGap - m.iconSize)
                    val textHeight = action.text.height(innerWidth)
                    val height = max(m.commandMinHeight, textHeight + 2 * m.commandPaddingV)
                    command = Rect(0.0, y, w, height)
                    commandText = Rect(m.commandPaddingH, y + m.commandPaddingV, innerWidth, textHeight)
                    commandIcon = Rect(w - m.commandPaddingH - m.iconSize, y + m.commandPaddingV + m.iconTop, m.iconSize, m.iconSize)
                    y += height
                }
            }
            return InactiveHintLayout(
                Size(w, y + m.paddingBottom), noticeFrame, terminalFrame, button, buttonLabel, command, commandText, commandIcon,
            )
        }
    }
}

/**
 * SessionView's CLI version warning: a pill centred 8 under the header, 14
 * and 7 inside, the warning glyph, the label 6 after it and the close glyph 8
 * after that, all on one middle line.
 */
data class CliWarningPillLayout(val pill: Rect, val icon: Rect, val label: Rect, val close: Rect) {
    object Metrics {
        const val top = 8.0
        const val paddingH = 14.0
        const val paddingV = 7.0
        const val iconSize = 14.0
        const val iconGap = 6.0
        const val closeGap = 8.0
        const val shadowOpacity = 0.15
        const val shadowRadius = 4.0
        const val shadowOffsetY = 2.0
    }

    companion object {
        /** [top] is the header's bottom. */
        operator fun invoke(width: Double, top: Double, label: Measurable): CliWarningPillLayout {
            val m = Metrics
            val chrome = 2 * m.paddingH + 2 * m.iconSize + m.iconGap + m.closeGap
            val labelSize = label.size(max(0.0, width - chrome))
            val line = max(m.iconSize, labelSize.height)
            val size = Size(chrome + labelSize.width, line + 2 * m.paddingV)
            val pill = Rect((max(0.0, width) - size.width) / 2, top + m.top, size.width, size.height)
            val mid = pill.minY + m.paddingV + line / 2
            val icon = Rect(pill.minX + m.paddingH, mid - m.iconSize / 2, m.iconSize, m.iconSize)
            val labelFrame = Rect(icon.maxX + m.iconGap, mid - labelSize.height / 2, labelSize.width, labelSize.height)
            val close = Rect(labelFrame.maxX + m.closeGap, icon.minY, m.iconSize, m.iconSize)
            return CliWarningPillLayout(pill, icon, labelFrame, close)
        }
    }
}

/**
 * AgentQuestionBanner: a rounded card above the composer.
 *
 *     12 ┌ 1 border, 12 ─ [icon 24] 10 title / subtitle ─ 10 [› or Dismiss] 12 ┐ 12
 *        └ 10 above and below the text ────────────────────────────────────────┘
 *     8
 */
data class QuestionBannerLayout(
    val size: Size,
    val card: Rect,
    val icon: Rect,
    val title: Rect,
    val subtitle: Rect,
    val trailing: Rect,
) {
    object Metrics {
        const val marginH = 12.0
        const val marginBottom = 8.0
        const val paddingH = 12.0
        const val paddingV = 10.0
        const val border = 1.0
        const val radius = 14.0
        const val iconWidth = 24.0
        const val iconSize = 20.0
        const val gap = 10.0
    }

    companion object {
        operator fun invoke(width: Double, title: Measurable, subtitle: Measurable, trailing: Measurable): QuestionBannerLayout {
            val m = Metrics
            val w = max(0.0, width)
            val cardWidth = max(0.0, w - 2 * m.marginH)
            val inset = m.border + m.paddingH
            val trailingSize = trailing.size(cardWidth)
            val bodyX = inset + m.iconWidth + m.gap
            val bodyWidth = max(0.0, cardWidth - bodyX - m.gap - trailingSize.width - inset)
            val titleHeight = title.height(bodyWidth)
            val subtitleHeight = subtitle.height(bodyWidth)
            val bodyHeight = titleHeight + subtitleHeight
            val contentHeight = maxOf(m.iconSize, bodyHeight, trailingSize.height)
            val top = m.border + m.paddingV
            fun centred(height: Double) = top + (contentHeight - height) / 2
            val card = Rect(m.marginH, 0.0, cardWidth, contentHeight + 2 * top)
            val bodyTop = centred(bodyHeight)
            return QuestionBannerLayout(
                size = Size(w, card.height + m.marginBottom),
                card = card,
                icon = Rect(card.minX + inset + (m.iconWidth - m.iconSize) / 2, centred(m.iconSize), m.iconSize, m.iconSize),
                title = Rect(card.minX + bodyX, bodyTop, bodyWidth, titleHeight),
                subtitle = Rect(card.minX + bodyX, bodyTop + titleHeight, bodyWidth, subtitleHeight),
                trailing = Rect(card.maxX - inset - trailingSize.width, centred(trailingSize.height), trailingSize.width, trailingSize.height),
            )
        }
    }
}

/**
 * AgentGoalBar: the current goal above the composer.
 *
 *     ┌ 1 border, 12 ─ [◎ 18] 10 "Current goal" / goal text ─ 10 [actions 30, 4 apart] 12 ┐
 *     └ 10 above and below ─────────────────────────────────────────────────────────────────┘
 *     8
 */
data class GoalBarLayout(
    val size: Size,
    val card: Rect,
    val icon: Rect,
    val label: Rect,
    val text: Rect,
    val actions: List<Rect>,
) {
    object Metrics {
        const val paddingH = 12.0
        const val paddingV = 10.0
        const val border = 1.0
        const val radius = 14.0
        const val iconSize = 18.0
        const val gap = 10.0
        const val actionSize = 30.0
        const val actionGap = 4.0
        const val actionIconSize = 16.0
        const val marginBottom = 8.0
    }

    companion object {
        operator fun invoke(width: Double, label: Measurable, text: Measurable, actionCount: Int): GoalBarLayout {
            val m = Metrics
            val w = max(0.0, width)
            val inset = m.border + m.paddingH
            val actionsWidth = if (actionCount > 0) actionCount * m.actionSize + (actionCount - 1) * m.actionGap else 0.0
            val bodyX = inset + m.iconSize + m.gap
            val bodyRight = w - inset - (if (actionCount > 0) actionsWidth + m.gap else 0.0)
            val bodyWidth = max(0.0, bodyRight - bodyX)
            val labelHeight = label.height(bodyWidth)
            val textHeight = text.height(bodyWidth)
            val bodyHeight = labelHeight + textHeight
            val contentHeight = maxOf(m.iconSize, bodyHeight, if (actionCount > 0) m.actionSize else 0.0)
            val top = m.border + m.paddingV
            fun centred(height: Double) = top + (contentHeight - height) / 2
            val card = Rect(0.0, 0.0, w, contentHeight + 2 * top)
            val bodyTop = centred(bodyHeight)
            var x = w - inset - actionsWidth
            val actions = (0 until max(0, actionCount)).map {
                Rect(x, centred(m.actionSize), m.actionSize, m.actionSize).also { x += m.actionSize + m.actionGap }
            }
            return GoalBarLayout(
                size = Size(w, card.height + m.marginBottom),
                card = card,
                icon = Rect(inset, centred(m.iconSize), m.iconSize, m.iconSize),
                label = Rect(bodyX, bodyTop, bodyWidth, labelHeight),
                text = Rect(bodyX, bodyTop + labelHeight, bodyWidth, textHeight),
                actions = actions,
            )
        }
    }
}

/**
 * RigActivityBar: "2 agents · 1 processes", wrapping, 16 in, 5 above and
 * below, 10 between indicators, a 12 glyph 4 before each count.
 */
data class RigActivityLayout(val size: Size, val icons: List<Rect>, val labels: List<Rect>) {
    object Metrics {
        const val paddingH = 16.0
        const val paddingV = 5.0
        const val gap = 10.0
        const val iconSize = 12.0
        const val iconGap = 4.0
    }

    companion object {
        operator fun invoke(width: Double, labels: List<Measurable>): RigActivityLayout {
            val m = Metrics
            val w = max(0.0, width)
            val right = w - m.paddingH
            var x = m.paddingH
            var y = m.paddingV
            var lineHeight = 0.0
            val icons = mutableListOf<Rect>()
            val labelFrames = mutableListOf<Rect>()
            val placed = mutableListOf<Pair<Rect, Rect>>()
            val line = mutableListOf<Int>()
            fun closeLine() {
                // Items on a line are centred on it.
                for (index in line) {
                    val (icon, label) = placed[index]
                    icons += Rect(icon.minX, y + (lineHeight - m.iconSize) / 2, m.iconSize, m.iconSize)
                    labelFrames += Rect(label.minX, y + (lineHeight - label.height) / 2, label.width, label.height)
                }
                line.clear()
            }
            for (label in labels) {
                val text = label.size(max(0.0, right - m.paddingH - m.iconSize - m.iconGap))
                val itemWidth = m.iconSize + m.iconGap + text.width
                if (line.isNotEmpty() && x + itemWidth > right) {
                    closeLine()
                    y += lineHeight + m.gap
                    x = m.paddingH
                    lineHeight = 0.0
                }
                placed += Rect(x, 0.0, m.iconSize, m.iconSize) to Rect(x + m.iconSize + m.iconGap, 0.0, text.width, text.height)
                line += placed.size - 1
                lineHeight = maxOf(lineHeight, m.iconSize, text.height)
                x += itemWidth + m.gap
            }
            closeLine()
            return RigActivityLayout(Size(w, if (labels.isEmpty()) 0.0 else y + lineHeight + m.paddingV), icons, labelFrames)
        }
    }
}
