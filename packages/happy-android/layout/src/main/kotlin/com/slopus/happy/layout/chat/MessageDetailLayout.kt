package com.slopus.happy.layout.chat

import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.max
import kotlin.math.min

// Ported from happy-ios Sources/HappyLayout/Chat/MessageDetailLayout.swift.

/**
 * A message's own screen ([messageId].tsx). A tool's full view sits in a
 * readable column, 16 in and at most 800 wide, 12 from the top and 32 from
 * the bottom (ToolFullView); an edit's diffs run edge to edge on a phone and
 * 16 in past 700 wide, within [maxContentWidth]. A text message is 16 in all
 * round.
 */
data class MessageDetailLayout(val content: Rect, val contentSize: Size) {
    object Metrics {
        const val toolPaddingTop = 12.0
        const val toolPaddingBottom = 32.0
        const val readableMaxWidth = 800.0
        const val paddingH = 16.0
        /** Wide content gets its padding back past this screen width. */
        const val widePaddingFrom = 700.0
        const val textPadding = 16.0
    }

    enum class Kind {
        /** A readable column. */
        Tool,
        /** An edit's diffs. */
        WideTool,
        Text,
    }

    companion object {
        /** [maxContentWidth] is layout.maxWidth: unlimited on a phone, 800 on a tablet. */
        operator fun invoke(width: Double, kind: Kind, content: Measurable, maxContentWidth: Double = Double.MAX_VALUE): MessageDetailLayout {
            val m = Metrics
            val w = max(0.0, width)
            return when (kind) {
                Kind.Tool, Kind.WideTool -> {
                    val wrapper = min(w, maxContentWidth)
                    val column = if (kind == Kind.Tool) min(wrapper, m.readableMaxWidth) else wrapper
                    val padding = if (kind == Kind.Tool || w > m.widePaddingFrom) m.paddingH else 0.0
                    val inner = max(0.0, column - 2 * padding)
                    val height = content.height(inner)
                    MessageDetailLayout(
                        Rect((w - column) / 2 + padding, m.toolPaddingTop, inner, height),
                        Size(w, m.toolPaddingTop + height + m.toolPaddingBottom),
                    )
                }
                Kind.Text -> {
                    val inner = max(0.0, w - 2 * m.textPadding)
                    val height = content.height(inner)
                    MessageDetailLayout(Rect(m.textPadding, m.textPadding, inner, height), Size(w, height + 2 * m.textPadding))
                }
            }
        }
    }
}

/**
 * Where the reader is on a message's screen, by the piece of text at the top
 * of the screen: so a new text size, which moves every piece, keeps that one,
 * and as far down it, at the top. The space above a piece is padding, which
 * keeps its size.
 */
data class MessageDetailReadingPlace(
    /** The piece's index among the text pieces, in order. */
    val piece: Int,
    /** How far down the piece the top of the screen is, 0 to 1. */
    val fraction: Double,
    /** How far above the piece the top of the screen is, in the gap before it. */
    val above: Double,
) {
    /** The content y of this place among [pieces] laid out again, or null when there is no such piece. */
    fun top(pieces: List<Rect>): Double? {
        val frame = pieces.getOrNull(piece) ?: return null
        return frame.minY - above + fraction * frame.height
    }

    companion object {
        /**
         * The place at content y [top] among [pieces], the text's frames in
         * content order: the first piece that reaches below the top. Null at
         * or above the start, which stays the start, or past the last piece.
         */
        fun at(top: Double, pieces: List<Rect>): MessageDetailReadingPlace? {
            if (top <= 0) return null
            val index = pieces.indexOfFirst { it.maxY > top }
            if (index < 0) return null
            val frame = pieces[index]
            return MessageDetailReadingPlace(
                index,
                if (frame.height > 0) min(1.0, max(0.0, top - frame.minY) / frame.height) else 0.0,
                max(0.0, frame.minY - top),
            )
        }
    }
}

/**
 * MultiEditViewFull's edit header: "Edit N of M" in purple, then a "Replace
 * All" badge 8 to its right when the edit replaces every occurrence, the two
 * centred on one row.
 */
data class MultiEditHeaderLayout(val size: Size, val number: Rect, val badge: Rect?, val badgeText: Rect?) {
    object Metrics {
        const val badgeGap = 8.0
        const val badgePaddingH = 8.0
        const val badgePaddingV = 4.0
        const val badgeRadius = 12.0
    }

    companion object {
        operator fun invoke(width: Double, number: Measurable, badge: Measurable?): MultiEditHeaderLayout {
            val m = Metrics
            val w = max(0.0, width)
            val badgeText = badge?.size(w)
            val badgeSize = badgeText?.let { Size(it.width + 2 * m.badgePaddingH, it.height + 2 * m.badgePaddingV) }
            val numberWidth = max(0.0, w - (badgeSize?.let { it.width + m.badgeGap } ?: 0.0))
            val numberSize = number.size(numberWidth)
            val height = max(numberSize.height, badgeSize?.height ?: 0.0)
            val numberFrame = Rect(0.0, (height - numberSize.height) / 2, numberSize.width, numberSize.height)
            if (badgeSize == null || badgeText == null) return MultiEditHeaderLayout(Size(w, height), numberFrame, null, null)
            val pill = Rect(numberSize.width + m.badgeGap, (height - badgeSize.height) / 2, badgeSize.width, badgeSize.height)
            val text = Rect(pill.minX + m.badgePaddingH, pill.minY + m.badgePaddingV, badgeText.width, badgeText.height)
            return MultiEditHeaderLayout(Size(w, height), numberFrame, pill, text)
        }
    }
}

/**
 * Long text cut into pieces small enough to draw one at a time, so a message
 * screen showing thousands of lines of output only ever draws the few near
 * the screen.
 *
 * Pieces end after a newline, at most [MAX_LINES] lines and, once past
 * [MAX_LENGTH] UTF-16 units, at the next newline. A line longer than twice
 * that is cut after its last space (or anywhere, if it has none). Joined back
 * together, the pieces are the text.
 */
object TextChunks {
    const val MAX_LINES = 40
    const val MAX_LENGTH = 3000

    fun split(text: String, maxLines: Int = MAX_LINES, maxLength: Int = MAX_LENGTH): List<String> {
        val chunks = ArrayList<String>()
        var start = 0
        var lines = 0
        var length = 0
        var lastSpace = -1
        var index = 0
        while (index < text.length) {
            val next = nextCharacter(text, index)
            length += next - index
            if (isNewline(text, index)) {
                lines += 1
                lastSpace = -1
                if (lines >= maxLines || length >= maxLength) {
                    chunks += text.substring(start, next)
                    start = next
                    lines = 0
                    length = 0
                }
            } else {
                if (text[index].isWhitespace()) lastSpace = next
                if (length >= 2 * maxLength) {
                    val cut = if (lastSpace >= 0) lastSpace else next
                    chunks += text.substring(start, cut)
                    length = next - cut
                    start = cut
                    lines = 0
                    lastSpace = -1
                }
            }
            index = next
        }
        if (start < text.length || chunks.isEmpty()) chunks += text.substring(start)
        return chunks
    }

    /**
     * What a piece other than the last draws: without the newline that ends
     * it (the next piece starts on the next line anyway), and with a space
     * for an empty last line, which would otherwise take no room.
     */
    fun drawn(chunk: String): String {
        val end = lastNewlineStart(chunk) ?: return chunk
        val line = chunk.substring(0, end)
        if (line.isEmpty() || lastNewlineStart(line) != null) return "$line "
        return line
    }

    /**
     * What each piece draws, as a range of the whole text in UTF-16 units
     * and whether [drawn] adds its space after it: for cutting text that is
     * already styled the way the pieces cut the plain text.
     */
    data class Cut(val location: Int, val length: Int, val space: Boolean)

    /** [chunks] as [split] returns them; the last is drawn whole. */
    fun cuts(chunks: List<String>): List<Cut> {
        var location = 0
        return chunks.mapIndexed { index, chunk ->
            val length = chunk.length
            val at = location
            location += length
            val drawn = if (index == chunks.size - 1) chunk else drawn(chunk)
            if (drawn == chunk) return@mapIndexed Cut(at, length, false)
            val kept = lastNewlineStart(chunk) ?: length
            Cut(at, kept, drawn.length > kept)
        }
    }

    /** Swift's Character: "\r\n" is one newline, and a surrogate pair one character. */
    private fun nextCharacter(text: String, index: Int): Int = when {
        text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n' -> index + 2
        else -> text.offsetByCodePoints(index, 1).let { next ->
            // Keep combining marks and modifiers (an emoji's skin tone) with their base.
            var end = next
            while (end < text.length) {
                val type = Character.getType(text.codePointAt(end))
                val modifier = text.codePointAt(end) in 0x1F3FB..0x1F3FF || text.codePointAt(end) == 0x200D || text.codePointAt(end) in 0xFE00..0xFE0F
                if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() || modifier) {
                    end = text.offsetByCodePoints(end, 1)
                } else {
                    break
                }
            }
            end
        }
    }

    private fun isNewline(text: String, index: Int): Boolean = text[index] in "\n\r\u000B\u000C\u0085\u2028\u2029"

    private fun newlineLength(text: String): Int = if (text.endsWith("\r\n")) 2 else 1

    /** Where the newline that ends [text] starts, or null when it doesn't end in one. */
    private fun lastNewlineStart(text: String): Int? {
        if (text.isEmpty() || !isNewline(text, text.length - 1)) return null
        return text.length - newlineLength(text)
    }
}
