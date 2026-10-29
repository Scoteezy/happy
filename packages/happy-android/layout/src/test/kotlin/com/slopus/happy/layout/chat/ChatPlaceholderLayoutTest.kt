package com.slopus.happy.layout.chat

import com.slopus.happy.layout.Size
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.assertFrame
import com.slopus.happy.layout.rect
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/ChatPlaceholderLayoutTests.swift, at the designed
// text size: iOS's stand-in text (8 a character) is given here as the sizes it measures to.

class ChatPlaceholderLayoutTest {
    private val frame = Size(393.0, 700.0)
    private val spinner = Size(20.0, 20.0)

    /** EmptyMessages paging: the glyph, host, path, the spinner and "Created …". */
    private fun empty(height: Double = frame.height) = ChatPlaceholderLayout(
        Size(frame.width, height), icon = Size(72.0, 72.0), title = null,
        lines = listOf(
            ChatPlaceholderLayout.Line(Size(80.0, 24.0), 12.0, 48.0),
            ChatPlaceholderLayout.Line(Size(160.0, 18.0), 4.0, 48.0),
            ChatPlaceholderLayout.Line(Size(96.0, 24.0), 8.0, 48.0),
        ),
        status = ChatPlaceholderLayout.Status(2, spinner, 40.0),
    )

    @Test fun testAtTheDesignedSizeTheEmptyChatIsWhereRNPutsIt() {
        val layout = empty()
        assertFrame(layout.icon, rect(160.5, 239, 72, 72))
        XCTAssertNil(layout.title)
        XCTAssertEqual(layout.lines.size, 3)
        assertFrame(layout.lines[0], rect(156.5, 323, 80, 24))
        assertFrame(layout.lines[1], rect(116.5, 351, 160, 18))
        assertFrame(layout.status, rect(186.5, 409, 20, 20))
        assertFrame(layout.lines[2], rect(148.5, 437, 96, 24))
    }

    /** The keyboard moving the dock changes the frame's height: the column moves by half of it. */
    @Test fun testANewHeightOnlyMovesTheColumn() {
        val tall = empty()
        val short = empty(600.0)
        assertFrame(short.icon, tall.icon!!.offsetBy(0.0, -50.0))
        tall.lines.zip(short.lines).forEach { (was, moved) -> assertFrame(moved, was.offsetBy(0.0, -50.0)) }
        assertFrame(short.status, tall.status!!.offsetBy(0.0, -50.0))
    }

    @Test fun testAtTheDesignedSizeTheStartingChatIsWhereRNPutsIt() {
        val layout = ChatPlaceholderLayout(
            frame, icon = Size(64.0, 64.0), title = Size(144.0, 23.0),
            lines = listOf(ChatPlaceholderLayout.Line(Size(296.0, 42.0), 10.0, 48.0)),
            status = null,
        )
        assertFrame(layout.icon, rect(164.5, 272.5, 64, 64))
        assertFrame(layout.title, rect(124.5, 352.5, 144, 23))
        assertFrame(layout.lines.first(), rect(48.5, 385.5, 296, 42))
        XCTAssertNil(layout.status)
    }

    @Test fun testTheDeletedStateKeepsItsMarginsAndPaddings() {
        val layout = ChatPlaceholderLayout(
            frame, icon = Size(48.0, 48.0), title = null,
            lines = listOf(
                ChatPlaceholderLayout.Line(Size(120.0, 24.0), 16.0, 0.0),
                ChatPlaceholderLayout.Line(Size(328.0, 36.0), 8.0, 32.0),
            ),
            status = null,
        )
        assertFrame(layout.icon, rect(172.5, 284, 48, 48))
        assertFrame(layout.lines[0], rect(136.5, 348, 120, 24))
        assertFrame(layout.lines[1], rect(32.5, 380, 328, 36))
    }

    @Test fun testTheFirstLoadsRetrySitsInTheMiddle() {
        assertFrame(ChatPlaceholderLayout.button(frame, Size(393.0, 33.0)).status, rect(0, 333.5, 393, 33))
    }

    @Test fun testAStatusPastTheLinesIsNotPlaced() {
        val layout = ChatPlaceholderLayout(
            frame, icon = null, title = null,
            lines = listOf(ChatPlaceholderLayout.Line(Size(80.0, 24.0), 0.0, 48.0)),
            status = ChatPlaceholderLayout.Status(2, spinner, 8.0),
        )
        XCTAssertNil(layout.status)
        assertFrame(layout.lines.first(), rect(156.5, 338, 80, 24))
    }
}
