package com.slopus.happy.layout.chat

import com.slopus.happy.layout.Size
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.assertFrame
import com.slopus.happy.layout.assertSize
import com.slopus.happy.layout.block
import com.slopus.happy.layout.rect
import com.slopus.happy.layout.text
import org.junit.Assert.assertEquals
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/ChatLayoutTests.swift.

class MessageDetailLayoutTest {
    @Test fun testAToolSitsInAReadableColumn() {
        val phone = MessageDetailLayout(393.0, MessageDetailLayout.Kind.Tool, block(300))
        assertFrame(phone.content, rect(16, 12, 361, 300))
        assertSize(phone.contentSize, Size(393, 344))
        val pad = MessageDetailLayout(1024.0, MessageDetailLayout.Kind.Tool, block(300))
        assertFrame(pad.content, rect(128, 12, 768, 300))
    }

    @Test fun testAnEditsDiffsRunEdgeToEdgeOnAPhone() {
        val phone = MessageDetailLayout(393.0, MessageDetailLayout.Kind.WideTool, block(300))
        assertFrame(phone.content, rect(0, 12, 393, 300))
        val pad = MessageDetailLayout(1024.0, MessageDetailLayout.Kind.WideTool, block(300), maxContentWidth = 800.0)
        assertFrame(pad.content, rect(128, 12, 768, 300))
    }

    @Test fun testATextMessageIsPaddedAllRound() {
        val layout = MessageDetailLayout(393.0, MessageDetailLayout.Kind.Text, block(48))
        assertFrame(layout.content, rect(16, 16, 361, 48))
        assertSize(layout.contentSize, Size(393, 80))
    }

    @Test fun testAMultiEditHeaderCarriesItsBadgeBesideTheNumber() {
        // "Edit 2 of 3" at 8 a character beside a 12-high "Replace All".
        val layout = MultiEditHeaderLayout(361.0, text("Edit 2 of 3", lineHeight = 17), text("Replace All", advance = 6.0, lineHeight = 15.0))
        assertFrame(layout.number, rect(0, 3, 88, 17))
        assertFrame(layout.badge, rect(96, 0, 82, 23))
        assertFrame(layout.badgeText, rect(104, 4, 66, 15))
        assertSize(layout.size, Size(361, 23))

        val plain = MultiEditHeaderLayout(361.0, text("Edit 1 of 3", lineHeight = 17), null)
        assertFrame(plain.number, rect(0, 0, 88, 17))
        XCTAssertNil(plain.badge)
        XCTAssertNil(plain.badgeText)
    }

    @Test fun testTheReadingPlaceKeepsThePieceAtTheTop() {
        val pieces = listOf(rect(16, 16, 361, 100), rect(16, 116, 361, 100))
        XCTAssertNil(MessageDetailReadingPlace.at(0.0, pieces), "the start stays the start")
        val place = MessageDetailReadingPlace.at(166.0, pieces)!!
        XCTAssertEqual(place, MessageDetailReadingPlace(1, 0.5, 0.0))
        XCTAssertEqual(place.top(listOf(rect(16, 16, 361, 200), rect(16, 216, 361, 200))), 316)
        XCTAssertNil(MessageDetailReadingPlace.at(400.0, pieces), "past the last piece")
    }
}

class TextChunksTest {
    @Test fun testShortTextIsOnePiece() {
        assertEquals(listOf("one\ntwo\n"), TextChunks.split("one\ntwo\n"))
        assertEquals(listOf(""), TextChunks.split(""))
    }

    @Test fun testLongTextIsCutAfterNewlines() {
        val text = (1..7).joinToString("\n") { "line $it" }
        val chunks = TextChunks.split(text, maxLines = 3, maxLength = 1000)
        assertEquals(listOf("line 1\nline 2\nline 3\n", "line 4\nline 5\nline 6\n", "line 7"), chunks)
        assertEquals(text, chunks.joinToString(""))

        val wide = TextChunks.split("aaaaaa\nbb\ncc", maxLines = 40, maxLength = 5)
        assertEquals("past maxLength, the next newline ends the piece", listOf("aaaaaa\n", "bb\ncc"), wide)
    }

    @Test fun testAVeryLongLineIsCutAfterItsLastSpace() {
        assertEquals(listOf("alpha beta ", "gamma delta"), TextChunks.split("alpha beta gamma delta", maxLength = 6))
        assertEquals("no space: cut anywhere", listOf("abcd", "efgh", "ij"), TextChunks.split("abcdefghij", maxLength = 2))
        val emoji = "👍🏽".repeat(5)
        assertEquals("never inside a character", emoji, TextChunks.split(emoji, maxLength = 4).joinToString(""))
        TextChunks.split(emoji, maxLength = 4).forEach { chunk -> assertEquals(0, chunk.length % 4) }
    }

    @Test fun testAPieceDrawsWithoutTheNewlineThatEndsIt() {
        assertEquals("a\nb", TextChunks.drawn("a\nb\n"))
        assertEquals("an empty last line keeps its height", "a\n ", TextChunks.drawn("a\n\n"))
        assertEquals(" ", TextChunks.drawn("\n"))
        assertEquals("cut inside a line: nothing to drop", "abc", TextChunks.drawn("abc"))
    }

    @Test fun testCutsAreWhatEachPieceDrawsInTheWholeText() {
        val text = "ab\n\ncd\r\nef 🙂\ng"
        val chunks = TextChunks.split(text, maxLines = 1, maxLength = 1000)
        assertEquals(listOf("ab\n", "\n", "cd\r\n", "ef 🙂\n", "g"), chunks)
        val cuts = TextChunks.cuts(chunks)
        assertEquals(
            listOf(
                TextChunks.Cut(0, 2, false),
                // An empty line: nothing kept, and a space for its height.
                TextChunks.Cut(3, 0, true),
                // "\r\n" is one character but two units.
                TextChunks.Cut(4, 2, false),
                TextChunks.Cut(8, 5, false),
                // The last piece is drawn whole.
                TextChunks.Cut(14, 1, false),
            ),
            cuts,
        )
        chunks.zip(cuts).forEachIndexed { index, (chunk, cut) ->
            val kept = text.substring(cut.location, cut.location + cut.length)
            assertEquals(if (index == chunks.size - 1) chunk else TextChunks.drawn(chunk), kept + if (cut.space) " " else "")
        }
    }

    @Test fun testAPieceCutInsideALineIsDrawnWhole() {
        assertEquals(
            listOf(TextChunks.Cut(0, 4, false), TextChunks.Cut(4, 4, false), TextChunks.Cut(8, 2, false)),
            TextChunks.cuts(listOf("abcd", "efgh", "ij")),
        )
        assertEquals(listOf(TextChunks.Cut(0, 0, false)), TextChunks.cuts(listOf("")))
    }
}
