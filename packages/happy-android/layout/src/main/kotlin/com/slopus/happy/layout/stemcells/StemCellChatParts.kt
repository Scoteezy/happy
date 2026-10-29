package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.HeaderScrim
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.chat.ChatColumn
import com.slopus.happy.layout.chat.MarkdownLayout
import com.slopus.happy.layout.swiftRound
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A conversation's pictures side by side (happy-ios
 * `StemCellPicturePagerLayout`), one a screen wide, `GAP` apart as Photos
 * keeps them: the paging scroller is a gap wider than the screen, half a
 * gap off each side, so each page is centred in it.
 */
class StemCellPicturePagerLayout(private val size: Size, count: Int) {
    val pageWidth = size.width + GAP
    val pager = Rect(-GAP / 2, 0.0, pageWidth, size.height)
    val contentSize = Size(pageWidth * max(1, count), size.height)

    /** Page `index` inside the pager. */
    fun page(index: Int) = Rect(index * pageWidth + GAP / 2, 0.0, size.width, size.height)

    /** The pager's offset with page `index` in view. */
    fun offset(index: Int) = index * pageWidth

    /** The page most in view at `offset`, within the `count` there are. */
    fun index(offset: Double, count: Int): Int {
        if (pageWidth <= 0 || count <= 0) return 0
        return min(count - 1, max(0, swiftRound(offset / pageWidth).toInt()))
    }

    override fun equals(other: Any?) = other is StemCellPicturePagerLayout && other.size == size && other.contentSize == contentSize
    override fun hashCode() = size.hashCode()

    companion object {
        const val GAP = 20.0
    }
}

/**
 * A picture dragged up or down to close (happy-ios
 * `StemCellPictureDismissal`), as Photos and Telegram do: it follows the
 * finger, shrinking a little, while the black around it fades; let go far
 * or fast enough, it goes the way it was going.
 */
data class StemCellPictureDismissal(
    /** 0 resting, 1 at `FADE_DISTANCE` and past. */
    val progress: Double,
    val groundAlpha: Double,
    val scale: Double,
    val closes: Boolean,
) {
    companion object {
        /** How far, as a share of the screen's height, takes the ground away. */
        const val FADE_DISTANCE = 0.5
        /** The size it shrinks to at most. */
        const val MIN_SCALE = 0.8
        /** Let go past this far, or flung faster than `CLOSING_SPEED`, it closes. */
        const val CLOSING_DISTANCE = 100.0
        const val CLOSING_SPEED = 800.0

        /** `translation` and `velocity` are the finger's along the screen's height. */
        operator fun invoke(translation: Double, velocity: Double, height: Double): StemCellPictureDismissal {
            val progress = if (height > 0) min(1.0, abs(translation) / (height * FADE_DISTANCE)) else 0.0
            // Flung back toward the middle, it stays, however far it was.
            val flungBack = translation != 0.0 && velocity != 0.0 && (translation > 0) != (velocity > 0) && abs(velocity) > CLOSING_SPEED
            val closes = !flungBack && (abs(translation) > CLOSING_DISTANCE || (abs(velocity) > CLOSING_SPEED && abs(translation) > 10))
            return StemCellPictureDismissal(progress, 1 - progress, 1 - (1 - MIN_SCALE) * progress, closes)
        }
    }
}

/**
 * What a Stem Cell conversation keeps around its composer (happy-ios
 * `StemCellChatFootLayout`): home's dock scrim, whole from the card's top
 * to the foot and feathered over the 36 above it, so what scrolls on under
 * the card has faded out by the time it meets it; and desktop's way back to
 * the newest message (`.happy-message-list__jump`), a 32 circle centred 12
 * above where the list ends, drawn over the scrim's feather.
 */
data class StemCellChatFootLayout(val scrim: Rect, val jump: Rect) {
    object Metrics {
        /** The list's gap above the card. */
        const val listGap = 8.0
        const val jumpSize = 32.0
        const val jumpGap = 12.0
        const val jumpIcon = 16.0
        /** `box-shadow: 0 2px 8px var(--shadow-color)`. */
        const val jumpShadowOffset = 2.0
        const val jumpShadowRadius = 4.0
        const val jumpShadowOpacity = 0.12
    }

    companion object {
        /** `card` is the composer's frame in a screen of `size`. */
        operator fun invoke(size: Size, card: Rect): StemCellChatFootLayout {
            val m = Metrics
            val top = max(0.0, card.minY - m.listGap)
            val scrimTop = max(0.0, card.minY - HeaderScrim.rampPoints)
            return StemCellChatFootLayout(
                Rect(0.0, scrimTop, size.width, max(0.0, size.height - scrimTop)),
                Rect(swiftRound((size.width - m.jumpSize) / 2), top - m.jumpGap - m.jumpSize, m.jumpSize, m.jumpSize),
            )
        }

        /** Desktop shows the way back once the reader is more than a third of the list's height from its end. */
        fun showsJump(distanceFromEnd: Double, viewport: Double) = viewport > 0 && distanceFromEnd > viewport / 3
    }
}

/**
 * A message another agent sent this one (happy-ios
 * `StemCellAgentMessageLayout`), as desktop's `AgentMessageActivity` sets
 * it: one line in the reading column, 6 above and below, 20 tall. A 14
 * glyph lane, then 6 apart the sender (at most 45% of the line, giving way
 * first), what it said it is, and the chevron with them, not at the line's
 * end. Opened, the message sits 4 under the line, 16 in.
 */
data class StemCellAgentMessageLayout(
    val size: Size,
    /** The whole line, which opens and closes the message. */
    val header: Rect,
    val glyph: Rect,
    val verb: Rect?,
    val text: Rect,
    val chevron: Rect?,
    /** The message, when open. */
    val body: MarkdownLayout?,
    val bodyOrigin: Point,
) {
    object Metrics {
        const val paddingV = 6.0
        const val lineHeight = 20.0
        const val glyph = 14.0
        const val gap = 6.0
        const val chevron = 14.0
        const val verbMaxFraction = 0.45
        /** Between the line and the message under it (`.happy-agent-activity` gap). */
        const val bodyGap = 4.0
        /** `.happy-agent-activity__body` padding-left. */
        const val bodyIndent = 16.0
        /** Between the message's paragraphs: the agent text's 8 at its 12/18. */
        const val bodyBlockGap = 6.0
    }

    companion object {
        /**
         * `verbWidth` and `textWidth` are the words' widths on one line; null
         * `verbWidth` draws no sender word. `body` is the open message's
         * blocks; null while closed. `opens` draws the chevron.
         */
        operator fun invoke(
            width: Double,
            maxContentWidth: Double = Double.MAX_VALUE,
            verbWidth: Double?,
            textWidth: Double,
            opens: Boolean,
            body: List<MarkdownLayout.Block>?,
        ): StemCellAgentMessageLayout {
            val m = Metrics
            val message = StemCellMessageLayout.Metrics
            val column = ChatColumn(width, maxContentWidth)
            val left = column.x + message.marginH
            val right = column.x + column.width - message.marginH
            val lineWidth = max(0.0, right - left)
            val top = m.paddingV
            val header = Rect(left, top, lineWidth, m.lineHeight)
            val glyph = Rect(left, top + (m.lineHeight - m.glyph) / 2, m.glyph, m.glyph)
            var x = glyph.maxX + m.gap
            val tail = if (opens) m.gap + m.chevron else 0.0
            // What follows the name keeps room for the chevron after it.
            val room = max(0.0, right - x - tail)
            var verb: Rect? = null
            if (verbWidth != null) {
                val frame = Rect(x, top, minOf(ceil(verbWidth), floor(lineWidth * m.verbMaxFraction), room), m.lineHeight)
                verb = frame
                x = frame.maxX + m.gap
            }
            val textRoom = max(0.0, right - x - tail)
            val text = Rect(x, top, min(ceil(textWidth), textRoom), m.lineHeight)
            val chevron = if (opens) Rect(text.maxX + m.gap, top + (m.lineHeight - m.chevron) / 2, m.chevron, m.chevron) else null
            var bottom = header.maxY
            val bodyOrigin = Point(left + m.bodyIndent, bottom + m.bodyGap)
            var markdown: MarkdownLayout? = null
            if (body != null) {
                markdown = MarkdownLayout(max(0.0, lineWidth - m.bodyIndent), body, m.bodyBlockGap)
                bottom = bodyOrigin.y + markdown.size.height
            }
            return StemCellAgentMessageLayout(Size(max(0.0, width), bottom + m.paddingV), header, glyph, verb, text, chevron, markdown, bodyOrigin)
        }
    }
}

/**
 * Desktop's working line (`AgentWorkingStatus`; happy-ios
 * `StemCellWorkingLayout`) at the transcript's foot: a 32-high row under 4
 * of clearance, the 14 glyph column (the 8-wide braille loader padded 3
 * either side, or a 14 ring or icon), then the clock, a "·" and the phase,
 * 6 apart on one 16/24 line.
 */
data class StemCellWorkingLayout(
    /** All in the row's own space, the row being `HEIGHT` tall. */
    val glyph: Rect,
    val timer: Rect?,
    val separator: Rect?,
    val phase: Rect,
) {
    object Metrics {
        /** The row with its clearance (`AGENT_WORKING_STATUS_ROW_HEIGHT`). */
        const val height = 36.0
        const val clearance = 4.0
        const val row = 32.0
        const val line = 24.0
        const val fontSize = 16.0
        /** The glyph column, and the text after it. */
        const val column = 14.0
        const val gap = 6.0
        /** The loader inside the column: 3 in, 8 wide, 14 high. */
        const val loaderInset = 3.0
        val loader = Size(8.0, 14.0)
        /** The transcript's sides. */
        const val side = 16.0
        /** A wait's ring (`WaitRing`): its stroke, track and fill alike. */
        const val ringStroke = 2.0
    }

    enum class Glyph {
        /** The braille loader. */
        Loader,
        /** A ring or a still icon, filling the column. */
        Mark,
    }

    companion object {
        /** The ring's radius inside a box, the stroke kept inside it. */
        fun ringRadius(box: Size) = max(0.0, (min(box.width, box.height) - Metrics.ringStroke) / 2)

        /** Text widths as measured; `timer` and `separator` null where no clock is shown. */
        operator fun invoke(width: Double, glyph: Glyph, timer: Double?, separator: Double?, phase: Double): StemCellWorkingLayout {
            val m = Metrics
            val top = m.clearance
            val glyphFrame = when (glyph) {
                Glyph.Loader -> Rect(m.side + m.loaderInset, top + (m.row - m.loader.height) / 2, m.loader.width, m.loader.height)
                Glyph.Mark -> Rect(m.side, top + (m.row - m.column) / 2, m.column, m.column)
            }
            val lineY = top + (m.row - m.line) / 2
            var x = m.side + m.column + m.gap
            // Widths as measured, unrounded: desktop's flex row runs its text
            // at fractional widths, and a rounding per run would drift the phase.
            var timerFrame: Rect? = null
            var separatorFrame: Rect? = null
            if (timer != null && separator != null) {
                timerFrame = Rect(x, lineY, timer, m.line)
                x += timer + m.gap
                separatorFrame = Rect(x, lineY, separator, m.line)
                x += separator + m.gap
            }
            return StemCellWorkingLayout(glyphFrame, timerFrame, separatorFrame, Rect(x, lineY, max(0.0, min(phase, width - m.side - x)), m.line))
        }
    }
}
