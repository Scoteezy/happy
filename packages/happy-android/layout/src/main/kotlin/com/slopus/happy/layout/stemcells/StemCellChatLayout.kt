package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.chat.ChatColumn
import com.slopus.happy.layout.chat.MarkdownLayout
import com.slopus.happy.layout.stories.StoryCardLayout
import com.slopus.happy.layout.swiftRound
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * MakeStuffView.tsx's intro (happy-ios `MakeStuffIntroLayout`), the first
 * row of new work's conversation: the 64px mark centred over one line, 16
 * apart and 16 inside the row. Once the conversation has begun (`settled`)
 * the line keeps its place, hidden, and the row ends right under it, where
 * the first message lands.
 */
data class MakeStuffIntroLayout(val size: Size, val mark: Rect, val line: Rect) {
    object Metrics {
        const val inset = 16.0
        const val mark = 64.0
        const val gap = 16.0
    }

    companion object {
        /** `line` is centred text wrapping inside the row. */
        operator fun invoke(width: Double, line: Measurable, settled: Boolean = false): MakeStuffIntroLayout {
            val m = Metrics
            val measure = max(0.0, width - 2 * m.inset)
            val lineSize = line.size(measure)
            val mark = Rect((width - m.mark) / 2, m.inset, m.mark, m.mark)
            val frame = Rect(m.inset, mark.maxY + m.gap, measure, lineSize.height)
            return MakeStuffIntroLayout(Size(max(0.0, width), frame.maxY + (if (settled) 0.0 else m.inset)), mark, frame)
        }
    }
}

/**
 * The conversation screen (happy-ios `StemCellChatScreenLayout`): the
 * transcript under the bar, and the composer card 8 in from the sides and
 * 8 above the keyboard, or above the safe area while the keyboard is down.
 */
data class StemCellChatScreenLayout(
    val card: Rect,
    /** The transcript's insets inside the whole screen. */
    val listTop: Double,
    val listBottom: Double,
    /** The height between those insets, which short content rests at the foot of. */
    val viewport: Double,
) {
    object Metrics {
        const val paddingH = 8.0
        const val paddingBottom = 8.0
        /** Room between the newest row and the card. */
        const val listGap = 8.0
    }

    companion object {
        /** `keyboardTop` is the keyboard guide's top in the screen. */
        operator fun invoke(size: Size, safeArea: LayoutInsets, keyboardTop: Double, cardHeight: (Double) -> Double): StemCellChatScreenLayout {
            val m = Metrics
            val width = max(0.0, size.width - 2 * m.paddingH)
            val height = cardHeight(width)
            val foot = min(keyboardTop, size.height - safeArea.bottom)
            val card = Rect(m.paddingH, foot - m.paddingBottom - height, width, height)
            val listTop = safeArea.top
            val listBottom = max(0.0, size.height - card.minY + m.listGap)
            return StemCellChatScreenLayout(card, listTop, listBottom, max(0.0, size.height - listTop - listBottom))
        }
    }
}

/**
 * The conversation's rows stacked in a scroll view (happy-ios
 * `StemCellChatStackLayout`): newest last, resting on the composer while
 * they are shorter than the screen, as a chat does. A conversation that
 * opens on its intro keeps it pinned under the bar instead, and its rows
 * fill in under it.
 *
 * A lifted send stands on its line from the viewport's top while the reply
 * has not filled the room under it: the content reaches a viewport below
 * the line, so following the end keeps it there whatever the viewport does.
 * Once the rows below it fill that room the lift is spent, and the stack is
 * as it would be without it, at the same place.
 */
data class StemCellChatStackLayout(
    val frames: List<Rect>,
    val contentHeight: Double,
    /** The lift still holds room for the reply. */
    val holding: Boolean,
) {
    /** Row `row` held `line` down from the viewport's top. */
    data class Lift(val row: Int, val line: Double)

    companion object {
        /**
         * `heights` top to bottom; `viewport` is the height between the top
         * inset and the composer, which short content rests at the foot of.
         * `pinsFirst`: the first row is the intro, at the top whatever follows.
         */
        operator fun invoke(heights: List<Double>, width: Double, viewport: Double, pinsFirst: Boolean = false, lift: Lift? = null): StemCellChatStackLayout {
            val total = heights.sum()
            val starts = ArrayList<Double>(heights.size)
            var end = 0.0
            for (height in heights) {
                starts += end
                end += height
            }
            var lead = if (pinsFirst) 0.0 else max(0.0, viewport - total)
            var contentHeight = max(viewport, total)
            var holding = false
            if (lift != null && lift.row in starts.indices) {
                val start = starts[lift.row]
                // Shorter below it than the room under its line: held. At the
                // moment the room fills, the natural rest is this same place.
                if (total - start < viewport - lift.line) {
                    holding = true
                    if (!pinsFirst) lead = max(0.0, lift.line - start)
                    contentHeight = lead + start - lift.line + viewport
                }
            }
            val frames = starts.zip(heights).map { (start, height) -> Rect(0.0, lead + start, width, height) }
            if (!holding) contentHeight = max(viewport, lead + total)
            return StemCellChatStackLayout(frames, contentHeight, holding)
        }
    }
}

/**
 * Desktop's send lift (Message.tsx `sendLift`, `SEND_LIFT_TOP`; happy-ios
 * `StemCellSendLift`): a message sent while the agent is idle is lifted to
 * 30% down the viewport, the turns before it still in view above, and the
 * room under it is held for the reply until the reply fills it. A lift only
 * ever moves a send up: one already at the line or above it stays where it
 * landed. Under a pinned intro the line is no higher than the intro's own
 * line, so the first send, landing right under the intro, is already there.
 * `introLine` is where a pinned intro's line ends.
 */
class StemCellSendLift(viewport: Double, introLine: Double? = null) {
    object Metrics {
        /** `SEND_LIFT_TOP`: of the viewport's height, from its top. */
        const val share = 0.3
        /** No lower than this share, however tall the intro: the reply's room. */
        const val lowest = 0.5
    }

    /** From the viewport's top, where the send's top edge sits. */
    val line: Double = min(max(swiftRound(viewport * Metrics.share), introLine ?: 0.0), swiftRound(viewport * Metrics.lowest))

    /** A send whose top is `shownAt` from the viewport's top, as it landed. */
    fun lifts(shownAt: Double) = shownAt > line

    override fun equals(other: Any?) = other is StemCellSendLift && other.line == line
    override fun hashCode() = line.hashCode()
}

/**
 * One message of a Stem Cells conversation (happy-ios
 * `StemCellMessageLayout`), as desktop's `Message` sets it on a phone's
 * width: 16 in from the sides and 8 above and below, the agent's markdown
 * with its blocks 8 apart, the person's text in a bubble at the right, at
 * most 76% of the column, 10 above and below its text and 12 beside it.
 */
data class StemCellMessageLayout(
    val size: Size,
    val parts: List<Placed>,
    val bubble: Rect? = null,
    /** The person's text, drawn as written. */
    val text: Rect? = null,
    /** The pictures the person sent, under the bubble at the right. */
    val pictures: List<Rect> = emptyList(),
    val status: Rect? = null,
) {
    object Metrics {
        const val marginH = 16.0
        const val marginV = 8.0
        const val blockGap = 8.0
        const val bubbleMaxFraction = 0.76
        const val bubblePaddingH = 12.0
        const val bubblePaddingV = 10.0
        const val bubbleRadius = 10.0
        /** The status line under a person's message while it is on its way. */
        const val statusGap = 2.0
        const val statusHeight = 14.0
    }

    /** One run of an agent's message: Markdown, or pictures and videos. */
    sealed interface Part {
        class Markdown(val blocks: List<MarkdownLayout.Block>) : Part
        /** A tool call's pictures and recordings; `columns` 0 is justified rows. */
        class Media(val items: List<StemCellMediaLayout.Item>, val columns: Int) : Part
        /** A layout the agent wrote for media it showed. */
        class Tree(val boxes: List<StemCellMediaTreeLayout.Box>) : Part
        /** A document, file or artifact: one card across the column. */
        data object Card : Part
        /** A story drawn inline (`StoryCardLayout`). */
        data object Story : Part
        /** A sidebar Prioritizing proposed, at the column's left. */
        class Proposal(val input: StemCellSidebarProposalLayout.Input) : Part
    }

    sealed interface Placed {
        data class Markdown(val layout: MarkdownLayout, val origin: Point) : Placed
        /** One frame per item, in the items' order. */
        data class Media(val frames: List<Rect>) : Placed
        data class Tree(val layout: StemCellMediaTreeLayout, val origin: Point) : Placed
        data class Card(val frame: Rect) : Placed
        data class Story(val frame: Rect) : Placed
        /** The card's frame; its insides are `layout`'s, from the frame's origin. */
        data class Proposal(val layout: StemCellSidebarProposalLayout, val frame: Rect) : Placed
    }

    /** The first Markdown run, for an agent message that is only text. */
    val markdown: MarkdownLayout? get() = parts.firstNotNullOfOrNull { (it as? Placed.Markdown)?.layout }

    val markdownOrigin: Point get() = parts.firstNotNullOfOrNull { (it as? Placed.Markdown)?.origin } ?: Point.Zero

    companion object {
        fun agent(width: Double, maxContentWidth: Double = Double.MAX_VALUE, blocks: List<MarkdownLayout.Block>) =
            agent(width, maxContentWidth, listOf<Part>(Part.Markdown(blocks)))

        /** The runs one under another, 8 apart, in the reading column. */
        @JvmName("agentParts")
        fun agent(width: Double, maxContentWidth: Double = Double.MAX_VALUE, parts: List<Part>): StemCellMessageLayout {
            val m = Metrics
            val column = ChatColumn(width, maxContentWidth)
            val inner = max(0.0, column.width - 2 * m.marginH)
            val left = column.x + m.marginH
            var y = m.marginV
            val placed = ArrayList<Placed>()
            for (part in parts) {
                if (placed.isNotEmpty()) y += m.blockGap
                when (part) {
                    is Part.Markdown -> {
                        val markdown = MarkdownLayout(inner, part.blocks, m.blockGap)
                        placed += Placed.Markdown(markdown, Point(left, y))
                        y += markdown.size.height
                    }
                    is Part.Media -> {
                        val media = StemCellMediaLayout(inner, part.items, part.columns)
                        placed += Placed.Media(media.frames.map { it.offsetBy(left, y) })
                        y += media.size.height
                    }
                    is Part.Tree -> {
                        val tree = StemCellMediaTreeLayout(inner, part.boxes)
                        placed += Placed.Tree(tree, Point(left, y))
                        y += tree.size.height
                    }
                    Part.Card -> {
                        placed += Placed.Card(Rect(left, y, inner, StemCellResultCardLayout.HEIGHT))
                        y += StemCellResultCardLayout.HEIGHT
                    }
                    Part.Story -> {
                        val size = StoryCardLayout.size(inner)
                        placed += Placed.Story(Rect(Point(left, y), size))
                        y += size.height
                    }
                    is Part.Proposal -> {
                        val proposal = StemCellSidebarProposalLayout(inner, part.input)
                        placed += Placed.Proposal(proposal, Rect(Point(left, y), proposal.size))
                        y += proposal.size.height
                    }
                }
            }
            return StemCellMessageLayout(Size(max(0.0, width), y + m.marginV), placed)
        }

        /**
         * `showsStatus` keeps a line under the bubble for a refusal. `text` is
         * null for pictures sent alone; `pictures` are drawn as desktop draws
         * a message's photos: justified, against the right, at most four.
         */
        fun user(
            width: Double,
            maxContentWidth: Double = Double.MAX_VALUE,
            text: Measurable?,
            pictures: List<StemCellMediaFlow.Image> = emptyList(),
            showsStatus: Boolean,
        ): StemCellMessageLayout {
            val m = Metrics
            val column = ChatColumn(width, maxContentWidth)
            val inner = max(0.0, column.width - 2 * m.marginH)
            val maxBubble = floor(inner * m.bubbleMaxFraction)
            val right = column.x + column.width - m.marginH
            var bottom = m.marginV
            var bubble: Rect? = null
            var textFrame: Rect? = null
            if (text != null) {
                val textSize = text.size(max(0.0, maxBubble - 2 * m.bubblePaddingH))
                val bubbleWidth = textSize.width + 2 * m.bubblePaddingH
                val frame = Rect(right - bubbleWidth, bottom, bubbleWidth, textSize.height + 2 * m.bubblePaddingV)
                bubble = frame
                textFrame = Rect(frame.minX + m.bubblePaddingH, frame.minY + m.bubblePaddingV, textSize.width, textSize.height)
                bottom = frame.maxY
            }
            var pictureFrames = emptyList<Rect>()
            if (pictures.isNotEmpty()) {
                if (bubble != null) bottom += m.blockGap
                val measure = min(maxBubble, StemCellMediaFlow.MESSAGE_MAX_WIDTH)
                val items = pictures.take(StemCellMediaFlow.MESSAGE_MAX).map { StemCellMediaLayout.Item(false, it) }
                val media = StemCellMediaLayout(measure, items, 0, StemCellMediaFlow.message, alignEnd = true)
                // The photos hug the right edge as the bubble does.
                val top = bottom
                pictureFrames = media.frames.map { it.offsetBy(right - measure, top) }
                bottom += media.size.height
            }
            var status: Rect? = null
            if (showsStatus) {
                status = Rect(column.x + m.marginH, bottom + m.statusGap, inner, m.statusHeight)
                bottom += m.statusGap + m.statusHeight
            }
            return StemCellMessageLayout(Size(max(0.0, width), bottom + m.marginV), emptyList(), bubble, textFrame, pictureFrames, status)
        }
    }
}

/**
 * Desktop's ResultCard (happy-ios `StemCellResultCardLayout`): one thing an
 * agent produced, as a 64-tall card across its container. A 40 chip 12 in
 * carries the kind's glyph, the title (20 tall) over a detail line (18)
 * beside it, and a chevron at the right when it opens.
 */
data class StemCellResultCardLayout(
    val chip: Rect,
    val glyph: Rect,
    val title: Rect,
    val detail: Rect,
    val chevron: Rect?,
) {
    companion object {
        const val HEIGHT = 64.0
        const val RADIUS = 8.0
        const val CHIP = 40.0
        const val CHIP_RADIUS = 6.0
        const val GLYPH = 20.0
        const val CHEVRON = 20.0
        const val PADDING = 12.0
        const val GAP = 12.0
        const val TITLE_HEIGHT = 20.0
        const val DETAIL_HEIGHT = 18.0

        operator fun invoke(frame: Rect, opens: Boolean): StemCellResultCardLayout {
            val chip = Rect(frame.minX + PADDING, frame.minY + (frame.height - CHIP) / 2, CHIP, CHIP)
            val glyph = Rect(chip.midX - GLYPH / 2, chip.midY - GLYPH / 2, GLYPH, GLYPH)
            val textX = chip.maxX + GAP
            var right = frame.maxX - PADDING
            var chevron: Rect? = null
            if (opens) {
                chevron = Rect(right - CHEVRON, frame.minY + (frame.height - CHEVRON) / 2, CHEVRON, CHEVRON)
                right -= CHEVRON + GAP
            }
            val top = frame.minY + (frame.height - TITLE_HEIGHT - DETAIL_HEIGHT) / 2
            return StemCellResultCardLayout(
                chip, glyph,
                Rect(textX, top, max(0.0, right - textX), TITLE_HEIGHT),
                Rect(textX, top + TITLE_HEIGHT, max(0.0, right - textX), DETAIL_HEIGHT),
                chevron,
            )
        }
    }
}
