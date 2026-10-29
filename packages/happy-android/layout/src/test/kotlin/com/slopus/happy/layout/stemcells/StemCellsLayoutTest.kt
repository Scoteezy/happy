package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.StemCellsMetrics
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertFalse
import com.slopus.happy.layout.XCTAssertLessThan
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.XCTAssertTrue
import com.slopus.happy.layout.block
import com.slopus.happy.layout.chat.MarkdownLayout
import com.slopus.happy.layout.rect
import com.slopus.happy.layout.swiftRound
import com.slopus.happy.layout.text
import org.junit.Test
import com.slopus.happy.layout.stemcells.StemCellsSidebarLayout.Kind
import com.slopus.happy.layout.stemcells.StemCellsSidebarLayout.Row

// Ported from happy-ios StemCellsLayoutTests: exact frames for the Stem
// Cells sidebar, the new-work intro and the conversation's stack.
class StemCellsLayoutTest {
    /** A third of a point: lanes and glyphs sit on 3x pixels. */
    private val third = 1.0 / 3

    @Test fun rowsStackAtTheirKindsHeights() {
        val result = StemCellsSidebarLayout.layout(listOf(
            Row(Kind.Row),
            Row(Kind.Row, hasMark = true, hasTrailing = true),
            Row(Kind.Separator, hasMark = false),
            Row(Kind.Section, hasMark = false),
            Row(Kind.Row, hasMark = false),
        ), 390.0)
        val frames = result.frames
        // Desktop's pixels half again: the box 8 in, 48 tall; a 30 mark
        // centred 24 into the box, then 12 to the label, which rides 2 up.
        XCTAssertEqual(frames[0].box, rect(8, 9, 374, 48))
        XCTAssertEqual(frames[0].mark, rect(17, 18, 30, 30))
        XCTAssertEqual(frames[0].label, Rect(59.0, 7.0, 312 + 2 * third, 48.0))
        XCTAssertNil(frames[0].trailing)
        // The trailing square, 27, centred 24 in from the box's right (on a
        // 3x pixel); the label stops 12 short of it. Rows stand 3 apart.
        XCTAssertEqual(frames[1].trailing, Rect(344 + 2 * third, 70 + 2 * third, 27.0, 27.0))
        XCTAssertEqual(frames[1].label, Rect(59.0, 58.0, 273 + 2 * third, 48.0))
        // The separator is 26 tall, its hairline across the box at its middle.
        XCTAssertEqual(frames[2].box, rect(8, 111, 374, 26))
        XCTAssertEqual(frames[2].rule, rect(8, 123, 374, 1))
        // A section heading is 42; its title starts 15 into the box.
        XCTAssertEqual(frames[3].box, rect(8, 140, 374, 42))
        XCTAssertEqual(frames[3].label, Rect(23.0, 140.0, 315 + 2 * third, 42.0))
        XCTAssertEqual(frames[3].chevron, Rect(344 + 2 * third, 147 + 2 * third, 27.0, 27.0))
        // No mark: no leading space.
        XCTAssertNil(frames[4].mark)
        XCTAssertEqual(frames[4].label, Rect(17.0, 183.0, 354 + 2 * third, 48.0))
        XCTAssertEqual(result.contentHeight, 233 + 9)
    }

    @Test fun aSectionsPlusStandsBeforeItsFold() {
        val frames = StemCellsSidebarLayout.layout(listOf(Row(Kind.Section, hasMark = false, hasAdd = true), Row(Kind.Section, hasMark = false)), 390.0).frames
        XCTAssertEqual(frames[0].chevron, Rect(344 + 2 * third, 16 + 2 * third, 27.0, 27.0))
        // One square and its gap before the fold; the title keeps clear of it.
        XCTAssertEqual(frames[0].add, Rect(305 + 2 * third, 16 + 2 * third, 27.0, 27.0))
        XCTAssertEqual(frames[0].label, Rect(23.0, 9.0, 276 + 2 * third, 42.0))
        // Without one, nothing changes.
        XCTAssertNil(frames[1].add)
        XCTAssertEqual(frames[1].label.width, 315 + 2 * third)
    }

    @Test fun theSidebarIsDesktopsHalfAgain() {
        val m = StemCellsMetrics
        XCTAssertEqual(m.phone, 1.5)
        XCTAssertEqual(listOf(m.rowHeight, m.sectionHeight, m.separatorHeight, m.rowGap, m.inset), listOf(48.0, 42.0, 26.0, 3.0, 8.0))
        XCTAssertEqual(listOf(m.mark, m.glyph, m.markGap, m.trailing, m.addGlyph), listOf(30.0, 24.0, 12.0, 27.0, 21.0))
        XCTAssertEqual(listOf(m.labelSize, m.sectionLabelSize, m.chevronGlyph, m.questionGlyph, m.clockGlyph), listOf(19.5, 16.5, 18.0, 22.5, 21.0))
        // Nesting and its lines aren't scaled: desktop's 28 a level, 1 thick.
        XCTAssertEqual(listOf(m.indent, m.line), listOf(28.0, 1.0))
    }

    /**
     * Each end of a row holds its mark centred in a square the row's
     * height: the leading mark's centre and the fold's optical centre half
     * the row in from the row box's side, as far as from its top.
     */
    @Test fun eachEndsMarkIsCentredInASquareTheRowsHeight() {
        val m = StemCellsMetrics
        for (width in listOf(375.0, 402.0, 440.0)) {
            val frames = StemCellsSidebarLayout.layout(listOf(
                Row(Kind.Row, hasFold = true),
                Row(Kind.Row, hasFold = true, foldClosed = true),
                Row(Kind.Section, hasMark = false, hasAdd = true),
                Row(Kind.Row, depth = 1, hasTrailing = true),
            ), width).frames
            for ((index, frame) in frames.take(2).withIndex()) {
                val box = frame.box
                val mark = frame.mark!!
                // 24 in from the left, 24 down: on the row's middle.
                XCTAssertEqual(mark.midX - box.minX, 24)
                XCTAssertEqual(mark.midY - box.minY, 24)
                // The chevron's ink weighs 24 in from the right and 24 down, to the nearest 3x pixel.
                val ink = if (index == 0) m.chevronDownInk else m.chevronRightInk
                val glyph = frame.chevronGlyph!!
                XCTAssertEqual(glyph.size, Size(m.chevronGlyph, m.chevronGlyph))
                XCTAssertEqual(box.maxX - (glyph.midX + ink.x), 24, accuracy = third / 2 + 0.001)
                XCTAssertEqual(glyph.midY + ink.y - box.minY, 24, accuracy = third / 2 + 0.001)
                XCTAssertEqual(swiftRound(glyph.minX * 3), glyph.minX * 3, accuracy = 0.0001)
                XCTAssertEqual(swiftRound(glyph.minY * 3), glyph.minY * 3, accuracy = 0.0001)
                // Left inset = right inset: the row is balanced.
                XCTAssertEqual(mark.midX - box.minX, box.maxX - (glyph.midX + ink.x), accuracy = third / 2 + 0.001)
                // The title keeps the mark's 12 and rides 2 up to the marks' middle.
                XCTAssertEqual(frame.label.minX - mark.maxX, 12)
                XCTAssertEqual(frame.label.midY, box.midY - m.titleLift)
            }
            // A section's fold, its "+" and a status mark stand on the same column.
            XCTAssertEqual(frames[2].chevron!!.midX, frames[0].chevron!!.midX)
            XCTAssertEqual(frames[3].trailing!!.midX, frames[0].chevron!!.midX)
            XCTAssertLessThan(frames[2].add!!.maxX, frames[2].chevron!!.minX)
            // A section's title starts where a top-level glyph's ink does.
            XCTAssertEqual(frames[2].label.minX, frames[2].box.minX + 15)
        }
        // On a 2x screen the ends land on its whole pixels, as near 24.
        for (frame in StemCellsSidebarLayout.layout(listOf(Row(Kind.Row, hasTrailing = true, hasFold = true)), 402.0, scale = 2.0).frames) {
            for (rect in listOf(frame.chevronGlyph!!, frame.trailing!!)) {
                XCTAssertEqual(swiftRound(rect.minX * 2), rect.minX * 2, accuracy = 0.0001)
                XCTAssertEqual(swiftRound(rect.minY * 2), rect.minY * 2, accuracy = 0.0001)
            }
            XCTAssertEqual(frame.box.maxX - (frame.chevronGlyph!!.midX + m.chevronDownInk.x), 24, accuracy = 0.25 + 0.001)
        }
        // The numbers: 8 + 24 = 32 from each edge; the "⌄" drawn 0.34 low and
        // the "›" 0.21 right and 0.13 low of their boxes' centres.
        val frame = StemCellsSidebarLayout.layout(listOf(Row(Kind.Row, hasFold = true)), 402.0).frames[0]
        XCTAssertEqual(frame.mark, rect(17, 18, 30, 30))
        XCTAssertEqual(frame.chevronGlyph, Rect(361.0, 24 + third, 18.0, 18.0))
        val closed = StemCellsSidebarLayout.layout(listOf(Row(Kind.Row, hasFold = true, foldClosed = true)), 402.0).frames[0]
        XCTAssertEqual(closed.chevronGlyph, Rect(361 + third, 24.0, 18.0, 18.0))
    }

    @Test fun nestedRowsHangOffTheirParentsMark() {
        val result = StemCellsSidebarLayout.layout(listOf(Row(Kind.Row), Row(Kind.Row, depth = 1), Row(Kind.Row, depth = 2), Row(Kind.Row, depth = 1)), 320.0)
        val frames = result.frames
        // A nested row's mark stands 28 right of its parent's.
        XCTAssertEqual(frames[1].mark, rect(45, 69, 30, 30))
        XCTAssertEqual(frames[2].mark, rect(73, 120, 30, 30))
        XCTAssertEqual(frames[3].label, Rect(87.0, 160.0, 301 + 2 * third - 87, 48.0))
        val expected = listOf(
            // Row 0's children (1 and 3): one stem down its mark's centre, from
            // under the mark to just past the last child's middle, and an elbow
            // into each child, 4 short of its mark.
            rect(31, 48, 1, 139), rect(32, 83, 9, 1), rect(32, 185, 9, 1),
            // Row 1's child (2), one level in.
            rect(59, 99, 1, 37), rect(60, 134, 9, 1),
        )
        XCTAssertEqual(result.connectors.size, expected.size)
        for ((actual, frame) in result.connectors.zip(expected)) XCTAssertEqual(actual, frame)
    }

    @Test fun aRowWithNestedRowsWearsItsFoldAtTheEnd() {
        val frames = StemCellsSidebarLayout.layout(listOf(
            Row(Kind.Row, hasFold = true),
            Row(Kind.Row, depth = 1, hasTrailing = true, hasFold = true),
            Row(Kind.Row, depth = 2),
        ), 390.0).frames
        // The fold takes the trailing square, as a section's does.
        XCTAssertEqual(frames[0].chevron, Rect(344 + 2 * third, 19 + 2 * third, 27.0, 27.0))
        XCTAssertNil(frames[0].trailing)
        XCTAssertEqual(frames[0].label, Rect(59.0, 7.0, 273 + 2 * third, 48.0))
        // A status or dot stands one square before it, and the label stops short of both.
        XCTAssertEqual(frames[1].chevron, Rect(344 + 2 * third, 70 + 2 * third, 27.0, 27.0))
        XCTAssertEqual(frames[1].trailing, Rect(305 + 2 * third, 70 + 2 * third, 27.0, 27.0))
        XCTAssertEqual(frames[1].label, Rect(87.0, 58.0, 293 + 2 * third - 87, 48.0))
        XCTAssertNil(frames[2].chevron)
        // Neither a separator nor a widget takes a row's fold.
        val others = StemCellsSidebarLayout.layout(listOf(Row(Kind.Separator, hasFold = true), Row(Kind.Widget, hasFold = true)), 390.0).frames
        XCTAssertNil(others[0].chevron)
        XCTAssertNil(others[1].chevron)
    }

    @Test fun foldingMovesOnlyWhatFollows() {
        val open = StemCellsSidebarLayout.layout(listOf(Row(Kind.Row), Row(Kind.Section), Row(Kind.Row), Row(Kind.Row)), 390.0)
        val folded = StemCellsSidebarLayout.layout(listOf(Row(Kind.Row), Row(Kind.Section), Row(Kind.Row)), 390.0)
        XCTAssertEqual(open.frames[0], folded.frames[0])
        XCTAssertEqual(open.frames[1], folded.frames[1])
        XCTAssertEqual(open.contentHeight - folded.contentHeight, 51)
    }

    @Test fun introCentresItsMarkOverItsLine() {
        val line = text("Say what it is — it’ll find its place.", 8.0, 20.0)
        val wide = MakeStuffIntroLayout(390.0, line)
        XCTAssertEqual(wide.mark, rect(163, 16, 64, 64))
        XCTAssertEqual(wide.line, rect(16, 96, 358, 20))
        XCTAssertEqual(wide.size, Size(390, 132))
        // Too narrow for one line: the line wraps and the row grows under it.
        val narrow = MakeStuffIntroLayout(200.0, line)
        XCTAssertEqual(narrow.mark, rect(68, 16, 64, 64))
        XCTAssertEqual(narrow.line, rect(16, 96, 168, 40))
        XCTAssertEqual(narrow.size.height, 152)
        // Begun: the row ends under its (hidden) line, where the first message lands.
        val settled = MakeStuffIntroLayout(390.0, line, settled = true)
        XCTAssertEqual(settled.mark, wide.mark)
        XCTAssertEqual(settled.line, wide.line)
        XCTAssertEqual(settled.size, Size(390, 116))
    }

    @Test fun theComposerRidesTheKeyboard() {
        val size = Size(390, 844)
        val safe = LayoutInsets(top = 103, bottom = 34)
        // Keyboard down: the guide's top is the safe area's foot.
        val down = StemCellChatScreenLayout(size, safe, 810.0) { 60.0 }
        XCTAssertEqual(down.card, rect(8, 742, 374, 60))
        XCTAssertEqual(down.listTop, 103)
        XCTAssertEqual(down.listBottom, 110)
        XCTAssertEqual(down.viewport, 631)
        // Keyboard up: the card sits 8 above it.
        val up = StemCellChatScreenLayout(size, safe, 508.0) { width -> if (width == 374.0) 92.0 else 0.0 }
        XCTAssertEqual(up.card, rect(8, 408, 374, 92))
        XCTAssertEqual(up.listBottom, 444)
        XCTAssertEqual(up.viewport, 297)
    }

    @Test fun shortConversationsRestOnTheComposer() {
        val short = StemCellChatStackLayout(listOf(132.0, 50.0), 390.0, 600.0)
        XCTAssertEqual(short.frames, listOf(rect(0, 418, 390, 132), rect(0, 550, 390, 50)))
        XCTAssertEqual(short.contentHeight, 600)
        val long = StemCellChatStackLayout(listOf(400.0, 300.0), 390.0, 600.0)
        XCTAssertEqual(long.frames, listOf(rect(0, 0, 390, 400), rect(0, 400, 390, 300)))
        XCTAssertEqual(long.contentHeight, 700)
    }

    /** Desktop's leading header: the intro stays under the bar and the rows fill in under it. */
    @Test fun anIntroIsPinnedUnderTheBarAndTheRowsFillInUnderIt() {
        val short = StemCellChatStackLayout(listOf(116.0, 60.0, 0.0), 390.0, 600.0, pinsFirst = true)
        XCTAssertEqual(short.frames, listOf(rect(0, 0, 390, 116), rect(0, 116, 390, 60), rect(0, 176, 390, 0)))
        XCTAssertEqual(short.contentHeight, 600)
        val long = StemCellChatStackLayout(listOf(116.0, 400.0, 300.0), 390.0, 600.0, pinsFirst = true)
        XCTAssertEqual(long.frames.map { it.minY }, listOf(0.0, 116.0, 516.0))
        XCTAssertEqual(long.contentHeight, 816)
    }

    /** A send while the agent is idle goes 30% down the viewport, no higher than a pinned intro's line and no lower than half. */
    @Test fun theSendLiftLineIsDesktopsUnderTheIntro() {
        XCTAssertEqual(StemCellSendLift(600.0, 116.0).line, 180)
        // The keyboard up: 30% is above the intro's line, so the line is the intro's.
        XCTAssertEqual(StemCellSendLift(297.0, 116.0).line, 116)
        XCTAssertEqual(StemCellSendLift(600.0).line, 180)
        XCTAssertEqual(StemCellSendLift(200.0, 160.0).line, 100)
        // The first send, right under the intro's line: there already.
        XCTAssertFalse(StemCellSendLift(600.0, 116.0).lifts(116.0))
        XCTAssertTrue(StemCellSendLift(600.0, 116.0).lifts(420.0))
    }

    /** A lifted follow-up under a pinned intro: on its line with the room under it held, until the reply fills it. */
    @Test fun aLiftedSendHoldsItsLineUntilTheReplyFillsTheRoom() {
        val lift = StemCellChatStackLayout.Lift(3, 180.0)
        // Intro 116, a send 60, a reply 724, the follow-up 60, working 0.
        val heights = listOf(116.0, 60.0, 724.0, 60.0, 0.0)
        val held = StemCellChatStackLayout(heights, 390.0, 600.0, pinsFirst = true, lift = lift)
        XCTAssertTrue(held.holding)
        XCTAssertEqual(held.frames[3].minY, 900)
        XCTAssertEqual(held.contentHeight, 1320)
        // Following the end, the follow-up stands on the line.
        XCTAssertEqual(held.frames[3].minY - (held.contentHeight - 600), 180)
        // The keyboard up: the same line, the room shorter.
        val up = StemCellChatStackLayout(heights, 390.0, 297.0, pinsFirst = true, lift = lift)
        XCTAssertEqual(up.frames[3].minY - (up.contentHeight - 297), 180)
        // The reply fills the room: spent, and nothing moves as it goes.
        val filling = StemCellChatStackLayout(listOf(116.0, 60.0, 724.0, 60.0, 359.0), 390.0, 600.0, pinsFirst = true, lift = lift)
        XCTAssertTrue(filling.holding)
        val full = StemCellChatStackLayout(listOf(116.0, 60.0, 724.0, 60.0, 360.0), 390.0, 600.0, pinsFirst = true, lift = lift)
        XCTAssertFalse(full.holding)
        XCTAssertEqual(full.contentHeight, 1320)
        XCTAssertEqual(filling.contentHeight, 1320)
    }

    /** Without an intro a short conversation rests on the composer; a send lifted from there stands on its line. */
    @Test fun aLiftWithoutAnIntroComesUpFromTheComposer() {
        val lift = StemCellChatStackLayout.Lift(0, 180.0)
        val sent = StemCellChatStackLayout(listOf(80.0, 0.0), 390.0, 600.0, lift = lift)
        XCTAssertTrue(sent.holding)
        XCTAssertEqual(sent.frames[0].minY, 180)
        XCTAssertEqual(sent.contentHeight, 600)
        // A reply streaming in stays under it, the send unmoved.
        val streaming = StemCellChatStackLayout(listOf(80.0, 200.0), 390.0, 600.0, lift = lift)
        XCTAssertEqual(streaming.frames[0].minY, 180)
        XCTAssertEqual(streaming.contentHeight, 600)
        // The reply fills the room: spent, the same place it rests at.
        val full = StemCellChatStackLayout(listOf(80.0, 340.0), 390.0, 600.0, lift = lift)
        XCTAssertFalse(full.holding)
        XCTAssertEqual(full.frames[0].minY, 180)
    }

    @Test fun agentParagraphsSitOneGapApart() {
        val layout = StemCellMessageLayout.agent(390.0, blocks = listOf(MarkdownLayout.Block.Text(block(24)), MarkdownLayout.Block.Text(block(48))))
        XCTAssertEqual(layout.markdownOrigin, Point(16.0, 8.0))
        val frames = layout.markdown!!.blocks.mapNotNull { (it as? MarkdownLayout.Placed.Text)?.frame }
        // No outer margins: the first paragraph starts at the top, the next 8 below.
        XCTAssertEqual(frames, listOf(rect(0, 0, 358, 24), rect(0, 32, 358, 48)))
        XCTAssertEqual(layout.size, Size(390, 96))
        // A wide screen keeps the reading column.
        val wide = StemCellMessageLayout.agent(1000.0, 800.0, blocks = listOf(MarkdownLayout.Block.Text(block(24))))
        XCTAssertEqual(wide.markdownOrigin, Point(116.0, 8.0))
        XCTAssertEqual(wide.markdown!!.size.width, 768)
    }

    @Test fun aPersonsMessageHugsTheRightInItsBubble() {
        val short = StemCellMessageLayout.user(390.0, text = text("hello", 20), showsStatus = false)
        XCTAssertEqual(short.bubble, rect(310, 8, 64, 40))
        XCTAssertEqual(short.text, rect(322, 18, 40, 20))
        XCTAssertNil(short.status)
        XCTAssertEqual(short.size, Size(390, 56))
        // Long text wraps at 76% of the column, less the padding.
        val long = StemCellMessageLayout.user(390.0, text = text("a".repeat(100), 20), showsStatus = true)
        XCTAssertEqual(long.bubble, rect(102, 8, 272, 100))
        XCTAssertEqual(long.text, rect(114, 18, 248, 80))
        XCTAssertEqual(long.status, rect(16, 110, 358, 14))
        XCTAssertEqual(long.size.height, 132)
    }
}
