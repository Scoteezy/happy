package com.slopus.happy.layout.chat

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertFalse
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.XCTAssertTrue
import com.slopus.happy.layout.assertFrame
import com.slopus.happy.layout.assertSize
import com.slopus.happy.layout.block
import com.slopus.happy.layout.fixed
import com.slopus.happy.layout.phone
import com.slopus.happy.layout.phoneUnderBar
import com.slopus.happy.layout.rect
import com.slopus.happy.layout.text
import org.junit.Assert.assertEquals
import org.junit.Test
import com.slopus.happy.layout.chat.MarkdownLayout.Block as B

// Ported from happy-ios Tests/HappyLayoutTests/ChatLayoutTests.swift.

class ChatListLayoutTest {
    private val layout = ChatListLayout(width = 393.0, heights = listOf(50.0, 30.0, 20.0), newerEndHeight = 6.0, olderEndHeight = 32.0)

    @Test fun testRowsStackFromTheNewestEdge() {
        assertFrame(layout.newerEndFrame, rect(0, 0, 393, 6))
        assertFrame(layout.frameOfRow(0), rect(0, 6, 393, 50))
        assertFrame(layout.frameOfRow(1), rect(0, 56, 393, 30))
        assertFrame(layout.frameOfRow(2), rect(0, 86, 393, 20))
        assertFrame(layout.olderEndFrame, rect(0, 106, 393, 32))
        assertSize(layout.contentSize, Size(393, 138))
    }

    @Test fun testVisibleRowsByBinarySearch() {
        assertEquals(1 until 3, layout.rows(60.0, 90.0))
        assertEquals("only the footer shows", 0, layout.rows(0.0, 5.0).count())
        assertEquals(0 until 3, layout.rows(0.0, 1000.0))
        XCTAssertEqual(layout.firstRow(endingAfter = 56.0), 1, "a row ending exactly at y is not past it")
        XCTAssertEqual(layout.firstRow(endingAfter = 200.0), 3)
    }

    @Test fun testReplacingAHeightMovesEverythingOlder() {
        val grown = layout.replacingHeight(0, 70.0)
        assertFrame(grown.frameOfRow(1), rect(0, 76, 393, 30))
        XCTAssertEqual(grown.contentHeight, 158)
    }

    @Test fun testAnEmptyList() {
        val empty = ChatListLayout(width = 393.0, heights = emptyList(), newerEndHeight = 6.0, olderEndHeight = 32.0)
        XCTAssertEqual(empty.contentHeight, 38)
        assertEquals(0, empty.rows(0.0, 100.0).count())
    }
}

class ChatScrollAnchorTest {
    private val ids = (0 until 10).map { "r$it" }
    private val layout get() = ChatListLayout(width = 393.0, heights = List(10) { 100.0 })

    @Test fun testAReaderNearTheNewestMessageFollowsIt() {
        val anchor = ChatScrollAnchor.capture(layout, ids, visibleMinY = 200.0)
        XCTAssertEqual(anchor.kind, ChatScrollAnchor.Kind.Newest)
        XCTAssertEqual(anchor.visibleMinY(layout, ids, current = 200.0, maxVisibleMinY = 900.0), 0)
    }

    @Test fun testAReaderDraggingNearTheNewestMessageKeepsTheirRow() {
        val anchor = ChatScrollAnchor.capture(layout, ids, visibleMinY = 150.0, follow = false)
        XCTAssertEqual(anchor.kind, ChatScrollAnchor.Kind.Row("r1", -50.0))
        val grown = layout.replacingHeight(0, 130.0)
        XCTAssertEqual(anchor.visibleMinY(grown, ids, current = 150.0, maxVisibleMinY = 1000.0), 180)
    }

    @Test fun testAReaderInHistoryKeepsTheirRowWhenNewerRowsArrive() {
        val anchor = ChatScrollAnchor.capture(layout, ids, visibleMinY = 450.0)
        XCTAssertEqual(anchor.kind, ChatScrollAnchor.Kind.Row("r4", -50.0))
        val newIds = listOf("n1", "n0") + ids
        val grown = ChatListLayout(width = 393.0, heights = List(12) { 100.0 })
        XCTAssertEqual(anchor.visibleMinY(grown, newIds, current = 450.0, maxVisibleMinY = 1100.0), 650)
    }

    @Test fun testStreamingGrowthBelowTheReaderIsAbsorbed() {
        val anchor = ChatScrollAnchor.capture(layout, ids, visibleMinY = 450.0)
        val grown = layout.replacingHeight(0, 160.0)
        XCTAssertEqual(anchor.visibleMinY(grown, ids, current = 450.0, maxVisibleMinY = 1000.0), 510)
    }

    @Test fun testAnOpenedRowGrowsTowardsTheNewestUnderTheReader() {
        val anchor = ChatScrollAnchor.pinning("r4", layout, ids, visibleMinY = 450.0)
        XCTAssertEqual(anchor?.kind, ChatScrollAnchor.Kind.RowEnd("r4", 50.0))
        val opened = layout.replacingHeight(4, 220.0)
        XCTAssertEqual(anchor?.visibleMinY(opened, ids, current = 450.0, maxVisibleMinY = 1200.0), 570)
        val newest = ChatScrollAnchor.pinning("r0", layout, ids, visibleMinY = 0.0)
        XCTAssertEqual(newest?.kind, ChatScrollAnchor.Kind.Newest)
        XCTAssertEqual(newest?.visibleMinY(layout.replacingHeight(0, 220.0), ids, current = 0.0, maxVisibleMinY = 1200.0), 0)
        XCTAssertNil(ChatScrollAnchor.pinning("gone", layout, ids, visibleMinY = 0.0))
    }

    @Test fun testAMissingAnchorRowKeepsTheOffsetAndNothingOverscrolls() {
        val anchor = ChatScrollAnchor(ChatScrollAnchor.Kind.Row("gone", 0.0))
        XCTAssertEqual(anchor.visibleMinY(layout, ids, current = 450.0, maxVisibleMinY = 300.0), 300)
        val deep = ChatScrollAnchor(ChatScrollAnchor.Kind.Row("r9", -50.0))
        XCTAssertEqual(deep.visibleMinY(layout, ids, current = 0.0, maxVisibleMinY = 600.0), 600)
    }
}

class MarkdownLayoutTest {
    @Test fun testAnImageIsDecodedNoLargerThanItsBoxAt3x() {
        assertSize(MarkdownLayout.decodedImageSize(Size(4000, 1000)), Size(1560, 390))
        assertSize(MarkdownLayout.decodedImageSize(Size(1000, 3000)), Size(240, 720))
        assertSize(MarkdownLayout.decodedImageSize(Size(64, 64)), Size(64, 64))
        assertSize(MarkdownLayout.decodedImageSize(Size.Zero), Size.Zero)
    }

    @Test fun testBlocksStackWithTheirOwnMargins() {
        val layout = MarkdownLayout(
            width = 300.0,
            blocks = listOf(
                B.Text(text("hello world", lineHeight = 25)),
                B.Header(2, text("Title", lineHeight = 24)),
                B.Rule,
                B.List(
                    listOf(
                        MarkdownLayout.ListItem(0, fixed(6, 25), text("one", lineHeight = 25)),
                        MarkdownLayout.ListItem(1, fixed(6, 25), text("two", lineHeight = 25)),
                    ),
                ),
                B.Code(text("swift", lineHeight = 16), Size(400, 40)),
                B.Options(listOf(text("Yes", lineHeight = 24), text("No", lineHeight = 24))),
                B.Image(text("cap", lineHeight = 20)),
            ),
        )
        assertFrame(layout.blocks[0].frame, rect(0, 8, 300, 25))
        assertFrame(layout.blocks[1].frame, rect(0, 59, 300, 24))
        assertFrame(layout.blocks[2].frame, rect(0, 99, 300, 1))

        val list = layout.blocks[3] as MarkdownLayout.Placed.ListBlock
        assertFrame(list.frame, rect(0, 108, 300, 58))
        assertFrame(list.items[0].marker, rect(0, 109, 6, 25))
        assertFrame(list.items[0].text, rect(14, 108, 286, 25))
        assertFrame(list.items[1].marker, rect(16, 141, 6, 25))
        assertFrame(list.items[1].text, rect(30, 140, 270, 25))

        val code = (layout.blocks[4] as MarkdownLayout.Placed.Code).code
        assertFrame(code.frame, rect(0, 182, 300, 96))
        assertFrame(code.language, rect(16, 8, 268, 16))
        assertFrame(code.scroll, rect(0, 24, 300, 72))
        assertSize(code.contentSize, Size(432, 72))
        assertFrame(code.text, rect(16, 16, 400, 40))

        val options = layout.blocks[5] as MarkdownLayout.Placed.Options
        assertFrame(options.frame, rect(0, 294, 300, 88))
        assertFrame(options.buttons[0], rect(0, 294, 300, 40))
        assertFrame(options.labels[0], rect(12, 302, 276, 24))
        assertFrame(options.buttons[1], rect(0, 342, 300, 40))
        assertFrame(options.labels[1], rect(12, 350, 276, 24))

        val image = layout.blocks[6] as MarkdownLayout.Placed.Image
        assertFrame(image.frame, rect(0, 398, 300, 268))
        assertFrame(image.picture, rect(0, 0, 300, 240))
        assertFrame(image.caption, rect(0, 248, 300, 20))

        assertSize(layout.size, Size(300, 674))
    }

    @Test fun testAMermaidDiagramIs200TallUntilItsPageSaysMoreAndScrollsPast600() {
        val layout = MarkdownLayout(
            width = 300.0,
            blocks = listOf(B.Mermaid(null), B.Mermaid(120.0), B.Mermaid(345.5), B.Mermaid(1800.0)),
        )
        assertFrame(layout.blocks[0].frame, rect(0, 8, 300, 200))
        assertFrame(layout.blocks[1].frame, rect(0, 224, 300, 200))
        assertFrame(layout.blocks[2].frame, rect(0, 440, 300, 345.5))
        assertFrame(layout.blocks[3].frame, rect(0, 801.5, 300, 600))
        assertSize(layout.size, Size(300, 1409.5))
    }

    @Test fun testOnlyAMermaidHeightThatGrowsTheBoxIsKept() {
        XCTAssertNil(MarkdownLayout.mermaidHeightToKeep(reported = 180.0, kept = null, scale = 3.0), "200 is shown anyway")
        XCTAssertNil(MarkdownLayout.mermaidHeightToKeep(reported = 200.0, kept = null, scale = 3.0))
        XCTAssertEqual(MarkdownLayout.mermaidHeightToKeep(reported = 345.1, kept = null, scale = 3.0)!!, 1036.0 / 3, accuracy = 0.0001)
        XCTAssertNil(MarkdownLayout.mermaidHeightToKeep(reported = 345.1, kept = 1036.0 / 3, scale = 3.0), "the same pixel again")
        XCTAssertNil(MarkdownLayout.mermaidHeightToKeep(reported = 300.0, kept = 345.0, scale = 3.0), "never shrinks")
        XCTAssertEqual(MarkdownLayout.mermaidHeightToKeep(reported = 5000.0, kept = 345.0, scale = 2.0), 600)
        XCTAssertNil(MarkdownLayout.mermaidHeightToKeep(reported = 5100.0, kept = 600.0, scale = 2.0), "past the cap the page scrolls")
        XCTAssertNil(MarkdownLayout.mermaidHeightToKeep(reported = Double.NaN, kept = null, scale = 2.0))
    }

    @Test fun testAMermaidDiagramTakesTheWholeWidth() {
        XCTAssertEqual(MarkdownLayout.naturalWidth(listOf(B.Text(text("hi", lineHeight = 25)), B.Mermaid(null)), available = 280.0), 280)
    }

    @Test fun testShortCodeKeepsTheBlockFullWidth() {
        val layout = MarkdownLayout(width = 300.0, blocks = listOf(B.Code(null, Size(50, 20))))
        val code = (layout.blocks[0] as MarkdownLayout.Placed.Code).code
        assertFrame(code.frame, rect(0, 8, 300, 52))
        XCTAssertNil(code.language)
        assertSize(code.contentSize, Size(300, 52))
    }

    @Test fun testAVeryLongLineOfCodeIsCutOff() {
        val layout = MarkdownLayout(width = 300.0, blocks = listOf(B.Code(null, Size(90_000, 20))))
        val code = (layout.blocks[0] as MarkdownLayout.Placed.Code).code
        assertSize(code.contentSize, Size(4128, 52))
        assertFrame(code.text, rect(16, 16, 4096, 20))
    }

    @Test fun testTableGrid() {
        val layout = MarkdownLayout(
            width = 300.0,
            blocks = listOf(
                B.TableBlock(
                    MarkdownLayout.Table(
                        columnWidths = listOf(80.0, 120.0),
                        rows = listOf(
                            listOf(text("A", lineHeight = 24), text("B", lineHeight = 24)),
                            listOf(text("1", lineHeight = 24), text("2", lineHeight = 24)),
                        ),
                    ),
                ),
            ),
        )
        val table = (layout.blocks[0] as MarkdownLayout.Placed.TableBlock).table
        assertFrame(table.frame, rect(0, 8, 202, 83))
        assertFrame(table.scroll, rect(1, 1, 200, 81))
        assertSize(table.contentSize, Size(200, 81))
        assertFrame(table.cells[0][0], rect(12, 8, 55, 24))
        assertFrame(table.cells[0][1], rect(92, 8, 96, 24))
        assertFrame(table.cells[1][0], rect(12, 49, 55, 24))
        assertFrame(table.rows[0], rect(0, 0, 200, 41))
        assertFrame(table.rows[1], rect(0, 41, 200, 40))
        assertEquals(listOf(rect(79, 0, 1, 41), rect(0, 40, 200, 1), rect(79, 41, 1, 40)), table.separators)
        XCTAssertEqual(layout.size.height, 99)
    }

    @Test fun testAWideTableScrollsInsideTheColumn() {
        val layout = MarkdownLayout(
            width = 300.0,
            blocks = listOf(B.TableBlock(MarkdownLayout.Table(listOf(200.0, 200.0), listOf(listOf(fixed(10, 24), fixed(10, 24)))))),
        )
        val table = (layout.blocks[0] as MarkdownLayout.Placed.TableBlock).table
        assertFrame(table.frame, rect(0, 8, 300, 43))
        assertFrame(table.scroll, rect(1, 1, 298, 41))
        assertSize(table.contentSize, Size(400, 41))
    }

    @Test fun testColumnWidthsFromCharacterCounts() {
        assertEquals(listOf(80.0, 360.0), MarkdownLayout.columnWidths(listOf(listOf(1, 6), listOf(3, 40))))
        assertEquals(listOf(109.0), MarkdownLayout.columnWidths(listOf(listOf(10))))
        assertEquals(emptyList<Double>(), MarkdownLayout.columnWidths(emptyList()))
    }

    @Test fun testNaturalWidthIsTheWidestBlock() {
        val blocks = listOf(
            B.Text(text("hello", lineHeight = 25)),
            B.List(listOf(MarkdownLayout.ListItem(1, fixed(6, 25), text("abc", lineHeight = 25)))),
            B.Code(null, Size(100, 20)),
        )
        XCTAssertEqual(MarkdownLayout.naturalWidth(blocks, available = 300.0), 132)
        XCTAssertEqual(MarkdownLayout.naturalWidth(blocks, available = 100.0), 100)
        XCTAssertEqual(MarkdownLayout.naturalWidth(listOf(B.Rule), available = 300.0), 0)
    }
}

class MessageRowLayoutTest {
    private fun textBlocks(string: String) = listOf(B.Text(text(string, lineHeight = 25)))

    @Test fun testAUserBubbleHugsItsTextOnTheRight() {
        val layout = UserMessageLayout(UserMessageLayout.Input(width = 393.0, content = UserMessageLayout.Content.Text(textBlocks("Hi there"))))
        XCTAssertNil(layout.author)
        assertFrame(layout.bubble, rect(289, 6, 88, 51))
        XCTAssertEqual(layout.markdownOrigin, Point(301, 10))
        assertSize(layout.markdown!!.size, Size(64, 43))
        assertFrame(layout.status, rect(16, 63, 361, 14))
        assertSize(layout.size, Size(393, 81))
        XCTAssertFalse(layout.alignsLeft)
    }

    @Test fun testAnotherParticipantsMessageSitsLeftUnderTheirName() {
        val layout = UserMessageLayout(
            UserMessageLayout.Input(width = 393.0, author = text("Ann", lineHeight = 20), content = UserMessageLayout.Content.Text(textBlocks("Hi there"))),
        )
        assertFrame(layout.author, rect(28, 6, 24, 20))
        assertFrame(layout.bubble, rect(16, 30, 88, 51))
        assertFrame(layout.status, rect(16, 87, 361, 14))
        XCTAssertEqual(layout.size.height, 105)
        XCTAssertTrue(layout.alignsLeft)
    }

    @Test fun testALongMessageFillsTheColumn() {
        val layout = UserMessageLayout(UserMessageLayout.Input(width = 393.0, content = UserMessageLayout.Content.Text(textBlocks("a".repeat(60)))))
        // 337 available → 42 characters a line, two lines.
        assertFrame(layout.bubble, rect(17, 6, 360, 76))
    }

    @Test fun testACommandIsAChip() {
        val layout = UserMessageLayout(
            UserMessageLayout.Input(width = 393.0, content = UserMessageLayout.Content.Command(text("/clear", lineHeight = 16), null)),
        )
        XCTAssertNil(layout.bubble)
        assertFrame(layout.chip, rect(307, 6, 70, 22))
        assertFrame(layout.chipLabel, rect(318, 9, 48, 16))
        assertFrame(layout.status, rect(16, 34, 361, 14))
        XCTAssertEqual(layout.size.height, 52)
    }

    @Test fun testACommandsArgumentsSitAboveItsChip() {
        val layout = UserMessageLayout(
            UserMessageLayout.Input(width = 393.0, content = UserMessageLayout.Content.Command(text("/review", lineHeight = 16), textBlocks("the diff"))),
        )
        assertFrame(layout.bubble, rect(289, 6, 88, 51))
        assertFrame(layout.chip, rect(299, 63, 78, 22))
    }

    @Test fun testAGoalSaysItWasSentAsOne() {
        val layout = UserMessageLayout(
            UserMessageLayout.Input(
                width = 393.0,
                content = UserMessageLayout.Content.Goal(textBlocks("ship it"), text("Sent as a goal", lineHeight = 17)),
            ),
        )
        assertFrame(layout.bubble, rect(297, 6, 80, 51))
        assertFrame(layout.goalIcon, rect(241, 63.5, 16, 16))
        assertFrame(layout.goalLabel, rect(265, 63, 112, 17))
        assertFrame(layout.status, rect(16, 86, 361, 14))
        XCTAssertEqual(layout.size.height, 104)
    }

    @Test fun testAgentText() {
        val layout = AgentTextLayout(width = 393.0, blocks = textBlocks("Done."))
        XCTAssertEqual(layout.markdownOrigin, Point(16, 10))
        assertSize(layout.markdown.size, Size(361, 43))
        assertFrame(layout.copyButton, rect(16, 53, 16, 20))
        assertFrame(layout.copyIcon, rect(16, 55, 16, 16))
        XCTAssertEqual(layout.size.height, 83)
    }

    @Test fun testRowsCentreInAWideWindow() {
        val column = ChatColumn(rowWidth = 1000.0, maxContentWidth = 800.0)
        XCTAssertEqual(column.x, 100)
        XCTAssertEqual(column.width, 800)
        val layout = AgentTextLayout(width = 1000.0, maxContentWidth = 800.0, blocks = emptyList())
        XCTAssertEqual(layout.markdownOrigin.x, 116)
    }

    @Test fun testAnEventLineIsCentred() {
        val layout = ChatEventLayout(width = 393.0, label = text("Switched to plan", lineHeight = 17))
        assertFrame(layout.label, rect(132.5, 8, 128, 17))
        XCTAssertEqual(layout.size.height, 33)
    }

    @Test fun testWorkGroupHeaderIsAlways44() {
        val layout = WorkGroupHeaderLayout(width = 393.0, label = text("Worked for 3s", lineHeight = 20))
        assertFrame(layout.header, rect(16, 8, 361, 28))
        assertFrame(layout.label, rect(16, 12, 104, 20))
        assertFrame(layout.chevron, rect(126, 15.5, 13, 13))
        XCTAssertEqual(layout.size.height, 44)
    }

    @Test fun testChatFooter() {
        XCTAssertEqual(ChatFooterLayout(width = 393.0, warning = null).size.height, 6)
        val layout = ChatFooterLayout(width = 393.0, warning = text("Warn", lineHeight = 15))
        assertFrame(layout.warning, rect(157.5, 8, 78, 24))
        assertFrame(layout.icon, rect(169.5, 12, 16, 16))
        assertFrame(layout.label, rect(191.5, 12.5, 32, 15))
        XCTAssertEqual(layout.size.height, 34)
    }

    @Test fun testOlderEndSlot() {
        val layout = ChatOlderEndLayout(width = 393.0, control = Size(20, 20))
        assertFrame(layout.control, rect(186.5, 6, 20, 20))
        XCTAssertEqual(layout.size.height, 32)
    }
}

class FileViewLayoutTest {
    @Test fun testThePictureKeepsItsShapeInsideTheBox() {
        assertSize(FileViewLayout.imageSize(1000.0, 500.0), Size(280, 140))
        assertSize(FileViewLayout.imageSize(300.0, 1200.0), Size(90, 360))
        assertSize(FileViewLayout.imageSize(100.0, 50.0), Size(100, 50))
        assertSize(FileViewLayout.imageSize(null, null), Size(280, 210))
        assertSize(FileViewLayout.imageSize(200.0, 0.0), Size(200, 150))
    }

    @Test fun testTheNameSitsUnderThePicture() {
        val layout = FileViewLayout(width = 361.0, imageWidth = 1000.0, imageHeight = 500.0, name = text("photo.png", lineHeight = 16, maxLines = 1))
        assertFrame(layout.image, rect(12, 8, 280, 140))
        assertFrame(layout.name, rect(12, 152, 337, 16))
        assertSize(layout.size, Size(361, 176))
        assertFrame(FileViewLayout.badge(layout.image.size), rect(252, 4, 24, 24))
        val narrow = FileViewLayout(width = 164.0, imageWidth = null, imageHeight = null, name = text("a", lineHeight = 16))
        assertFrame(narrow.image, rect(12, 8, 140, 105))
    }

    @Test fun testAPictureIsDecodedNoLargerThanItIsDrawn() {
        assertSize(FileViewLayout.decodedSize(Size(4032, 3024)), Size(840, 630))
        assertSize(FileViewLayout.decodedSize(Size(1170, 2532)), Size(499, 1080))
        assertSize(FileViewLayout.decodedSize(Size(300, 200)), Size(300, 200))
        assertSize(FileViewLayout.decodedSize(Size.Zero), Size.Zero)
    }
}

class ToolRowLayoutTest {
    @Test fun testAToolCard() {
        val layout = ToolCardLayout(
            ToolCardLayout.Input(
                width = 393.0,
                header = ToolCardLayout.Header(
                    title = text("Bash", lineHeight = 17),
                    description = text("run tests", lineHeight = 16),
                    elapsed = text("1.2s", lineHeight = 16),
                    statusIcon = Size(20, 20),
                ),
                body = block(50),
                footer = block(40),
            ),
        )
        assertFrame(layout.header, rect(0, 0, 377, 59))
        assertFrame(layout.icon, rect(12, 17.5, 24, 24))
        assertFrame(layout.title, rect(44, 12, 245, 17))
        assertFrame(layout.description, rect(44, 31, 245, 16))
        assertFrame(layout.elapsed, rect(305, 21.5, 32, 16))
        assertFrame(layout.statusIcon, rect(345, 19.5, 20, 20))
        assertFrame(layout.body, rect(12, 67, 353, 50))
        assertFrame(layout.footer, rect(0, 117, 377, 40))
        assertFrame(layout.card, rect(8, 8, 377, 157))
        XCTAssertEqual(layout.size.height, 173)
    }

    @Test fun testAnAttachmentHasNoCard() {
        val layout = ToolCardLayout(ToolCardLayout.Input(width = 393.0, header = null, body = block(50), footer = null, inline = true))
        XCTAssertNil(layout.header)
        assertFrame(layout.body, rect(0, 0, 377, 50))
        assertFrame(layout.card, rect(8, 1, 377, 50))
        XCTAssertEqual(layout.size.height, 52)
    }

    @Test fun testACompactActivityRow() {
        val layout = CompactToolRowLayout(
            width = 393.0, label = text("npm test", lineHeight = 20), elapsed = text("2.0s", lineHeight = 16),
            statusIcon = Size(20, 20), footer = null,
        )
        assertFrame(layout.header, rect(8, 2, 377, 28))
        assertFrame(layout.icon, rect(16, 6, 20, 20))
        assertFrame(layout.label, rect(46, 6, 251, 20))
        assertFrame(layout.elapsed, rect(315, 8, 32, 16))
        assertFrame(layout.statusIcon, rect(357, 6, 20, 20))
        XCTAssertNil(layout.footer)
        XCTAssertEqual(layout.size.height, 32)
    }

    @Test fun testACompactRowKeepsItsPermissionFooter() {
        val layout = CompactToolRowLayout(width = 393.0, label = text("x", lineHeight = 20), elapsed = null, statusIcon = null, footer = block(46))
        assertFrame(layout.footer, rect(8, 30, 377, 46))
        XCTAssertEqual(layout.size.height, 78)
    }

    @Test fun testAnOpenedCompactRowShowsItsBodyInABox() {
        val layout = CompactToolRowLayout(
            width = 393.0, label = text("x", lineHeight = 20), elapsed = null, statusIcon = null, body = block(60), footer = block(46),
        )
        assertFrame(layout.header, rect(8, 2, 377, 28))
        assertFrame(layout.bodyBox, rect(8, 32, 377, 68))
        assertFrame(layout.body, rect(20, 40, 353, 60))
        assertFrame(layout.footer, rect(8, 100, 377, 46))
        XCTAssertEqual(layout.size.height, 148)
    }

    @Test fun testAStackOfBoxes() {
        val layout = ChatStackLayout(
            width = 300.0,
            items = listOf(
                ChatStackLayout.Item(block(20), marginBottom = 12.0),
                ChatStackLayout.Item(fixed(100, 30), padding = LayoutInsets(12, 12, 12, 12), placement = ChatStackLayout.Placement.Leading),
                ChatStackLayout.Item(fixed(50, 10), marginTop = 8.0, placement = ChatStackLayout.Placement.Center),
            ),
        )
        assertEquals(listOf(rect(0, 0, 300, 20), rect(0, 32, 124, 54), rect(125, 94, 50, 10)), layout.boxes)
        assertEquals(listOf(rect(0, 0, 300, 20), rect(12, 44, 100, 30), rect(125, 94, 50, 10)), layout.contents)
        assertSize(layout.size, Size(300, 104))
    }

    @Test fun testAFullWidthSectionRunsToTheCardsEdgesUnderItsHeader() {
        val layout = ChatStackLayout(
            width = 329.0,
            items = listOf(ChatStackLayout.Item(block(120), marginTop = -8.0, marginBottom = 12.0, padding = LayoutInsets(0, -12, 0, -12))),
        )
        assertFrame(layout.contents[0], rect(-12, -8, 353, 120))
        assertSize(layout.size, Size(329, 124))
    }

    @Test fun testAClippedBox() {
        val layout = ChatStackLayout(
            width = 300.0,
            items = listOf(ChatStackLayout.Item(block(200), padding = LayoutInsets(12, 12, 12, 12), maxHeight = 115.0)),
        )
        assertFrame(layout.boxes[0], rect(0, 0, 300, 115))
        assertFrame(layout.contents[0], rect(12, 12, 276, 91))
    }

    @Test fun testAnErrorLineHangsItsGlyphAtTheTop() {
        val layout = ChatAccessoryRowLayout(
            width = 300.0, leading = Size(16, 16), text = Measurable.fixed(200, 36), alignment = ChatAccessoryRowLayout.Alignment.Top,
        )
        assertFrame(layout.leading, rect(0, 0, 16, 16))
        assertFrame(layout.text, rect(24, 0, 276, 36))
        XCTAssertNil(layout.trailing)
        assertSize(layout.size, Size(300, 36))
    }

    @Test fun testATaskLineCentresItsStatusAtTheEnd() {
        val layout = ChatAccessoryRowLayout(width = 300.0, text = Measurable { Size(it, 17.0) }, trailing = Size(20, 20))
        assertFrame(layout.text, rect(0, 1.5, 272, 17))
        assertFrame(layout.trailing, rect(280, 0, 20, 20))
        XCTAssertEqual(layout.size.height, 20)
    }

    @Test fun testPermissionButtonsStretchAndGrowForTwoLines() {
        val layout = PermissionFooterLayout(
            width = 377.0,
            labels = listOf(text("Yes", lineHeight = 18), text("x".repeat(50), lineHeight = 18, maxLines = 2)),
        )
        assertFrame(layout.buttons[0], rect(6, 4, 365, 34))
        assertFrame(layout.labels[0], rect(176.5, 12, 24, 18))
        assertFrame(layout.buttons[1], rect(6, 45, 365, 52))
        assertFrame(layout.labels[1], rect(20.5, 53, 336, 36))
        XCTAssertEqual(layout.size.height, 105)
        assertFrame(layout.list, rect(6, 4, 365, 93))
        XCTAssertFalse(layout.scrolls)
    }

    @Test fun testTheListIsAtMost260OrAThirdAndABitOfTheWindow() {
        XCTAssertEqual(PermissionFooterLayout.maxListHeight(windowHeight = 852.0), 260)
        XCTAssertEqual(PermissionFooterLayout.maxListHeight(windowHeight = 375.0), 131)
        XCTAssertEqual(PermissionFooterLayout.maxListHeight(windowHeight = 667.0), 233)
    }

    @Test fun testALongPermissionListScrollsInsideTheCap() {
        val labels = List(5) { text("Yes", lineHeight = 18) }
        val layout = PermissionFooterLayout(width = 377.0, labels = labels, maxListHeight = 131.0)
        XCTAssertEqual(layout.listContentHeight, 198)
        assertFrame(layout.list, rect(6, 4, 365, 131))
        XCTAssertTrue(layout.scrolls)
        XCTAssertEqual(layout.size.height, 143)
        assertFrame(layout.buttons[4], rect(6, 168, 365, 34))
        val roomy = PermissionFooterLayout(width = 377.0, labels = labels, maxListHeight = 260.0)
        XCTAssertFalse(roomy.scrolls)
        XCTAssertEqual(roomy.size.height, 210)
    }
}

class ChatScreenLayoutTest {
    private fun screen(keyboardTop: Double?) = ChatScreenLayout(
        ChatScreenLayout.Input(
            size = phone, safeArea = phoneUnderBar, keyboardTop = keyboardTop,
            accessories = listOf(ChatScreenLayout.Accessory.inset(block(60))), composerHeight = 70.0,
        ),
    )

    @Test fun testTheDockRestsOnTheSafeArea() {
        val layout = screen(null)
        assertFrame(layout.list, rect(0, 0, 393, 852))
        assertFrame(layout.accessories[0], rect(8, 0, 377, 60))
        assertFrame(layout.composer, rect(0, 60, 393, 70))
        assertFrame(layout.dock, rect(0, 688, 393, 130))
        XCTAssertEqual(layout.newestEdgeInset, 172)
        XCTAssertEqual(layout.oldestEdgeInset, 115)
        assertFrame(layout.scrollButton, rect(180.5, 700, 32, 32))
        assertFrame(layout.scrim, rect(0, 660, 393, 192))
    }

    @Test fun testTheRigActivityBarRunsFullWidthUnderTheInsetBars() {
        val layout = ChatScreenLayout(
            ChatScreenLayout.Input(
                size = phone, safeArea = phoneUnderBar, keyboardTop = null,
                accessories = listOf(ChatScreenLayout.Accessory.inset(block(57)), ChatScreenLayout.Accessory.fullWidth(block(23))),
                composerHeight = 70.0,
            ),
        )
        assertEquals(listOf(rect(8, 0, 377, 57), rect(0, 57, 393, 23)), layout.accessories)
        assertFrame(layout.composer, rect(0, 80, 393, 70))
        assertFrame(layout.dock, rect(0, 668, 393, 150))
    }

    @Test fun testTheScrollButtonSitsAboveTheComposerCard() {
        val layout = ChatScreenLayout(
            ChatScreenLayout.Input(size = phone, safeArea = phoneUnderBar, keyboardTop = null, composerHeight = 70.0, composerCardOffset = 20.0),
        )
        assertFrame(layout.dock, rect(0, 748, 393, 70))
        assertFrame(layout.scrollButton, rect(180.5, 720, 32, 32))
    }

    @Test fun testOnATabletTheListStopsAtTheDock() {
        val layout = ChatScreenLayout(
            ChatScreenLayout.Input(
                size = Size(664, 768), safeArea = LayoutInsets(top = 74, bottom = 20), keyboardTop = null,
                accessories = listOf(ChatScreenLayout.Accessory.inset(block(60))), composerHeight = 70.0, composerCardOffset = 20.0,
                docked = true, accessoryPaddingH = 12.0,
            ),
        )
        assertFrame(layout.accessories[0], rect(12, 0, 640, 60))
        assertFrame(layout.dock, rect(0, 618, 664, 130))
        assertFrame(layout.list, rect(0, 74, 664, 544))
        XCTAssertEqual(layout.newestEdgeInset, 8)
        XCTAssertEqual(layout.oldestEdgeInset, 106)
        assertFrame(layout.scrollButton, rect(316, 582, 32, 32))
        XCTAssertEqual(layout.scrim.height, 0)
        XCTAssertEqual(layout.headerScrim.height, 0)
        assertFrame(layout.placeholder, rect(0, 74, 664, 544))
        XCTAssertNil(layout.backButton)
        XCTAssertNil(layout.statusBarShadow)
    }

    @Test fun testAPhoneOnItsSideHasNoHeader() {
        val layout = ChatScreenLayout(
            ChatScreenLayout.Input(
                size = Size(667, 375), safeArea = LayoutInsets.Zero, keyboardTop = null,
                accessories = listOf(ChatScreenLayout.Accessory.inset(block(60))), composerHeight = 70.0, composerCardOffset = 20.0,
                hidesHeader = true,
            ),
        )
        assertFrame(layout.dock, rect(0, 245, 667, 130))
        assertFrame(layout.list, rect(0, 0, 667, 245))
        XCTAssertEqual(layout.newestEdgeInset, 8)
        XCTAssertEqual(layout.oldestEdgeInset, 12)
        assertFrame(layout.scrollButton, rect(317.5, 209, 32, 32))
        assertFrame(layout.backButton, rect(16, 8, 44, 44))
        assertFrame(layout.statusBarShadow, rect(0, 0, 667, 0))
    }

    @Test fun testTheTabStripHangsUnderTheHeader() {
        val layout = ChatScreenLayout(
            ChatScreenLayout.Input(
                size = phone, safeArea = phoneUnderBar, keyboardTop = null,
                accessories = listOf(ChatScreenLayout.Accessory.inset(block(60))), composerHeight = 70.0, headerAccessory = 56.0,
            ),
        )
        assertFrame(layout.headerAccessory, rect(0, 103, 393, 56))
        XCTAssertEqual(layout.headerBottom, 159)
        XCTAssertEqual(layout.oldestEdgeInset, 171)
        assertFrame(layout.placeholder, rect(0, 159, 393, 529))
        assertFrame(layout.headerScrim, rect(0, 0, 393, 111))
    }

    @Test fun testTheDockRidesTheKeyboard() {
        val layout = screen(500.0)
        assertFrame(layout.dock, rect(0, 370, 393, 130))
        XCTAssertEqual(layout.newestEdgeInset, 490)
        // Dragged nearly closed: never below the safe area.
        assertFrame(screen(830.0).dock, rect(0, 688, 393, 130))
    }

    @Test fun testDockDetailsHysteresis() {
        XCTAssertTrue(ChatScreenLayout.dockDetailsVisible(distanceFromNewest = 30.0, currentlyVisible = true))
        XCTAssertFalse(ChatScreenLayout.dockDetailsVisible(distanceFromNewest = 60.0, currentlyVisible = true))
        XCTAssertFalse(ChatScreenLayout.dockDetailsVisible(distanceFromNewest = 30.0, currentlyVisible = false))
        XCTAssertTrue(ChatScreenLayout.dockDetailsVisible(distanceFromNewest = 10.0, currentlyVisible = false))
        XCTAssertFalse(ChatScreenLayout.showsScrollButton(distanceFromNewest = 300.0))
        XCTAssertTrue(ChatScreenLayout.showsScrollButton(distanceFromNewest = 301.0))
    }

    @Test fun testTheHeaderScrimAndTheEmptyChatsPlace() {
        val layout = screen(null)
        assertFrame(layout.headerScrim, rect(0, 0, 393, 111))
        assertFrame(layout.placeholder, rect(0, 103, 393, 585))
        val typing = ChatScreenLayout(
            ChatScreenLayout.Input(size = phone, safeArea = phoneUnderBar, keyboardTop = 500.0, composerHeight = 70.0),
        )
        assertFrame(typing.placeholder, rect(0, 103, 393, 327))
    }

    @Test fun testTheBackdropStrengthensOnceContentIsUnderTheHeader() {
        XCTAssertFalse(ChatScreenLayout.headerBackdropVisible(distanceFromOldest = 0.0))
        XCTAssertFalse(ChatScreenLayout.headerBackdropVisible(distanceFromOldest = 0.4))
        XCTAssertTrue(ChatScreenLayout.headerBackdropVisible(distanceFromOldest = 1.0))
    }

    @Test fun testQuestionBanner() {
        val layout = QuestionBannerLayout(
            width = 377.0, title = text("Pick one", lineHeight = 18), subtitle = text("Which db?", lineHeight = 17), trailing = fixed(18, 18),
        )
        assertFrame(layout.card, rect(12, 0, 353, 57))
        assertFrame(layout.icon, rect(27, 18.5, 20, 20))
        assertFrame(layout.title, rect(59, 11, 265, 18))
        assertFrame(layout.subtitle, rect(59, 29, 265, 17))
        assertFrame(layout.trailing, rect(334, 19.5, 18, 18))
        XCTAssertEqual(layout.size.height, 65)
    }

    @Test fun testGoalBar() {
        val layout = GoalBarLayout(width = 377.0, label = text("Current goal", lineHeight = 16), text = text("Ship", lineHeight = 19), actionCount = 2)
        assertFrame(layout.card, rect(0, 0, 377, 57))
        assertFrame(layout.icon, rect(13, 19.5, 18, 18))
        assertFrame(layout.label, rect(41, 11, 249, 16))
        assertFrame(layout.text, rect(41, 27, 249, 19))
        assertEquals(listOf(rect(300, 13.5, 30, 30), rect(334, 13.5, 30, 30)), layout.actions)
        XCTAssertEqual(layout.size.height, 65)
    }

    @Test fun testRigActivityWraps() {
        val layout = RigActivityLayout(
            width = 200.0,
            labels = listOf(text("2 agents", lineHeight = 13), text("1 processes", lineHeight = 13), text("3 tasks", lineHeight = 13)),
        )
        assertEquals(listOf(rect(16, 5.5, 12, 12), rect(16, 28.5, 12, 12), rect(16, 51.5, 12, 12)), layout.icons)
        assertEquals(listOf(rect(32, 5, 64, 13), rect(32, 28, 88, 13), rect(32, 51, 56, 13)), layout.labels)
        XCTAssertEqual(layout.size.height, 69)
        XCTAssertEqual(RigActivityLayout(width = 200.0, labels = emptyList()).size.height, 0)
    }
}

class ChatHeaderTitleLayoutTest {
    @Test fun testTheCapsuleHugsATitleAlone() {
        val layout = ChatHeaderTitleLayout(maxWidth = 245.0, title = text("Fix the login", lineHeight = 20, maxLines = 1), subtitle = null, changes = null)
        assertSize(layout.size, Size(132, 44))
        assertFrame(layout.title, rect(14, 12, 104, 20))
        XCTAssertNil(layout.subtitle)
        XCTAssertNil(layout.changes)
    }

    @Test fun testTheBranchAndLineChangesShareARowUnderTheTitle() {
        val layout = ChatHeaderTitleLayout(
            maxWidth = 245.0, title = text("Chat", lineHeight = 20, maxLines = 1), subtitle = text("main", lineHeight = 14, maxLines = 1),
            changes = Size(40, 13),
        )
        assertSize(layout.size, Size(104, 44))
        assertFrame(layout.title, rect(36, 5, 32, 20))
        assertFrame(layout.subtitle, rect(14, 25, 32, 14))
        assertFrame(layout.changes, rect(50, 25.5, 40, 13))
    }

    @Test fun testALongTitleStopsAtTheInsetAndTheBranchGivesWayToTheCounts() {
        XCTAssertEqual(ChatHeaderTitleLayout.maxWidth(headerWidth = 393.0), 245)
        val layout = ChatHeaderTitleLayout(
            maxWidth = ChatHeaderTitleLayout.maxWidth(headerWidth = 393.0),
            title = text("t".repeat(40), lineHeight = 20, maxLines = 1),
            subtitle = text("b".repeat(30), lineHeight = 14, maxLines = 1),
            changes = Size(40, 13),
        )
        assertSize(layout.size, Size(245, 44))
        assertFrame(layout.title, rect(14, 5, 217, 20))
        assertFrame(layout.subtitle, rect(14, 25, 173, 14))
        assertFrame(layout.changes, rect(191, 25.5, 40, 13))
    }
}

class CopyMenuLayoutTest {
    private val window = Size(393, 852)

    @Test fun testTheMenuSitsAboveTheMessageWithItsRightEdgeOnTheMessages() {
        val layout = CopyMenuLayout(anchor = rect(150, 400, 227, 60), window = window, label = text("Copy", lineHeight = 20))
        assertFrame(layout.menu, rect(225, 348, 152, 44))
        assertFrame(layout.icon, rect(14, 13.5, 17, 17))
        assertFrame(layout.label, rect(41, 12, 32, 20))
    }

    @Test fun testNearTheTopItDropsBelowAndStaysOnScreen() {
        val below = CopyMenuLayout(anchor = rect(16, 40, 80, 30), window = window, label = text("Copy", lineHeight = 20))
        assertFrame(below.menu, rect(12, 78, 152, 44))
        val tall = CopyMenuLayout(anchor = rect(16, 40, 361, 900), window = window, label = text("Copy", lineHeight = 20))
        assertFrame(tall.menu, rect(225, 796, 152, 44))
    }
}

class CliWarningPillLayoutTest {
    @Test fun testThePillIsCentredUnderTheHeader() {
        val layout = CliWarningPillLayout(width = 393.0, top = 103.0, label = fixed(115, 15))
        assertFrame(layout.pill, rect(104, 111, 185, 29))
        assertFrame(layout.icon, rect(118, 118.5, 14, 14))
        assertFrame(layout.label, rect(138, 118, 115, 15))
        assertFrame(layout.close, rect(261, 118.5, 14, 14))
    }
}

class InactiveHintLayoutTest {
    @Test fun testResumeSitsUnderTheNotice() {
        val layout = InactiveHintLayout(
            width = 361.0, notice = text("This session is inactive.", advance = 7.0, lineHeight = 18.0), terminalNotice = null,
            action = InactiveHintLayout.Action.Resume(Measurable.fixed(111, 20)),
        )
        assertFrame(layout.notice, rect(8, 12, 345, 18))
        assertFrame(layout.button, rect(8, 40, 345, 44))
        assertFrame(layout.buttonLabel, rect(125, 52, 111, 20))
        XCTAssertNil(layout.command)
        assertSize(layout.size, Size(361, 94))
    }

    @Test fun testTheCommandBlockRunsEdgeToEdgeWithItsCopyGlyph() {
        val layout = InactiveHintLayout(
            width = 361.0, notice = text("x", lineHeight = 18), terminalNotice = text("y", lineHeight = 18),
            action = InactiveHintLayout.Action.Command(block(36)),
        )
        assertFrame(layout.terminalNotice, rect(8, 34, 345, 18))
        assertFrame(layout.command, rect(0, 62, 361, 60))
        assertFrame(layout.commandText, rect(16, 74, 305, 36))
        assertFrame(layout.commandIcon, rect(329, 75, 16, 16))
        assertSize(layout.size, Size(361, 132))
        val short = InactiveHintLayout(
            width = 361.0, notice = text("x", lineHeight = 18), terminalNotice = null, action = InactiveHintLayout.Action.Command(block(18)),
        )
        assertFrame(short.command, rect(0, 40, 361, 48))
    }
}
