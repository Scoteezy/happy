package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.StemCellPickerMetrics as M
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.assertFrames
import com.slopus.happy.layout.XCTAssertGreaterThanOrEqual
import com.slopus.happy.layout.XCTAssertLessThanOrEqual
import com.slopus.happy.layout.rect
import com.slopus.happy.layout.stemcells.StemCellPickerLineLayout.Piece
import com.slopus.happy.layout.stemcells.StemCellPickerPanelLayout as Layout
import com.slopus.happy.layout.stemcells.StemCellPickerPanelLayout.Item
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/StemCellPickerPanelLayoutTests.swift.

class StemCellPickerPanelLayoutTest {
    /** The screen above the composer, and a chip at its trailing end. */
    private val bounds = rect(0, 62, 402, 800)
    private val chip = rect(280, 780, 90, 30)

    @Test fun aMenusSizes() {
        XCTAssertEqual(M.row, 44)
        XCTAssertEqual(M.heading, 32)
        XCTAssertEqual(M.inset, 8)
        XCTAssertEqual(M.gap, 5)
        XCTAssertEqual(M.labelSize, 17)
        XCTAssertEqual(M.radius, 24)
        XCTAssertEqual(M.modelsMaxWidth, 250)
        XCTAssertEqual(M.accountsMinWidth, 250)
        XCTAssertEqual(M.accountsMaxWidth, 280)
    }

    /**
     * Two services, each a heading over its models, a hairline between
     * them; the benchmarks under a hairline of their own, outside the
     * scroll. Its trailing edge on the chip's, 10 above it, a menu's 250 wide.
     */
    @Test fun theModelListStandsOverItsChip() {
        val layout = Layout.over(
            items = listOf(Item.heading, Item.row, Item.row, Item.row, Item.separator, Item.heading, Item.row, Item.row),
            footer = listOf(Item.separator, Item.block(M.row)),
            width = M.modelsMaxWidth, anchor = chip, bounds = bounds,
        )
        XCTAssertEqual(layout.frame, rect(120, 404, 250, 366))
        XCTAssertEqual(layout.frame.maxX, chip.maxX)
        XCTAssertEqual(chip.minY - layout.frame.maxY, 10)
        XCTAssertEqual(layout.viewport, rect(0, 8, 250, 295))
        assertFrames(layout.items, listOf(
            rect(8, 0, 234, 32),
            rect(8, 32, 234, 44),
            rect(8, 76, 234, 44),
            rect(8, 120, 234, 44),
            rect(8, 169, 234, 1),
            rect(8, 175, 234, 32),
            rect(8, 207, 234, 44),
            rect(8, 251, 234, 44),
        ))
        XCTAssertEqual(layout.contentHeight, 295)
        assertFrames(layout.footer, listOf(rect(8, 308, 234, 1), rect(8, 314, 234, 44)))
        XCTAssertEqual(layout.footer.last().maxY + M.inset, layout.frame.height)
        XCTAssertEqual(layout.initialOffset, 0)
    }

    /**
     * A list longer than the room over the chip scrolls, and opens on the
     * picked line: centred, the offset kept inside the list.
     */
    @Test fun aLongListOpensOnThePickedLine() {
        val rows = List(20) { Item.row }
        val low = rect(280, 500, 90, 30)
        fun open(selected: Int?) = Layout.over(items = rows, width = M.effortsMinWidth, anchor = low, bounds = bounds, selected = selected)
        val middle = open(10)
        // 490 down to 8 under the top of the room: 420 tall.
        XCTAssertEqual(middle.frame, rect(210, 70, 160, 420))
        XCTAssertEqual(middle.viewport, rect(0, 8, 160, 404))
        XCTAssertEqual(middle.contentHeight, 880)
        // Line 10's middle (462) on the viewport's (202).
        XCTAssertEqual(middle.initialOffset, 260)
        XCTAssertEqual(open(19).initialOffset, 476)
        XCTAssertEqual(open(0).initialOffset, 0)
        XCTAssertEqual(open(null).initialOffset, 0)
        for (index in listOf(0, 3, 10, 16, 19)) {
            val layout = open(index)
            val line = layout.items[index]
            XCTAssertGreaterThanOrEqual(line.minY, layout.initialOffset, "line $index")
            XCTAssertLessThanOrEqual(line.maxY, layout.initialOffset + layout.viewport.height, "line $index")
        }
        // Never taller than 560.
        val tall = Layout.over(items = List(40) { Item.row }, width = 300.0, anchor = rect(280, 2000, 90, 30), bounds = rect(0, 0, 402, 2000))
        XCTAssertEqual(tall.frame.height, 560)
    }

    /** A service's accounts over the model list in place: the same trailing edge and bottom, a menu's 250 wide. */
    @Test fun theAccountsStackOverTheModelListInPlace() {
        val models = rect(120, 404, 250, 366)
        val details = Layout.detailsHeight(lines = 2, primary = true)
        XCTAssertEqual(details, 59)
        val accounts = Layout.stackedOver(
            items = listOf(Item.row, Item.row, Item.separator, Item.block(details)),
            width = M.accountsMinWidth, base = models, bounds = bounds, selected = 1,
        )
        XCTAssertEqual(accounts.frame, rect(120, 596, 250, 174))
        XCTAssertEqual(accounts.frame.maxY, models.maxY)
        XCTAssertEqual(accounts.frame.maxX, models.maxX)
        assertFrames(accounts.items, listOf(
            rect(8, 0, 234, 44),
            rect(8, 44, 234, 44),
            rect(8, 93, 234, 1),
            rect(8, 99, 234, 59),
        ))
        XCTAssertEqual(accounts.initialOffset, 0)
    }

    /** Kept 8 inside the screen at both sides. */
    @Test fun aPanelKeepsInsideTheScreen() {
        val edge = Layout.over(items = listOf(Item.row), width = M.modelsMaxWidth, anchor = rect(360, 780, 42, 30), bounds = bounds)
        XCTAssertEqual(edge.frame.maxX, 394)
        val wide = Layout.over(items = listOf(Item.row), width = 500.0, anchor = chip, bounds = bounds)
        XCTAssertEqual(wide.frame.minX, 8)
        XCTAssertEqual(wide.frame.width, 386)
    }

    /**
     * A long name ends early so the gauge at the line's end stays whole;
     * a short one keeps its own width. The name stands past the check's
     * column: the line it is laid in starts there.
     */
    @Test fun aLinesNameGivesWayToItsGauge() {
        val row = rect(8, 32, 234, 44)
        val named = Rect(row.minX + M.checkColumn, row.minY, row.width - M.checkColumn, row.height)
        val long = StemCellPickerLineLayout(
            line = named,
            leading = listOf(Piece(Size(400, 22), shrinks = true)),
            trailing = listOf(Piece(Size(40, 15))),
        )
        assertFrames(long.trailing, listOf(rect(190, 46.5, 40, 15)))
        // From 8 + 23 + 12 to 8 before the gauge.
        assertFrames(long.leading, listOf(rect(43, 43, 139, 22)))
        val short = StemCellPickerLineLayout(line = named, leading = listOf(Piece(Size(90, 22), shrinks = true)))
        assertFrames(short.leading, listOf(rect(43, 43, 90, 22)))
        // A heading's account gives way to its chevron, at most 110 wide.
        val heading = StemCellPickerLineLayout(
            line = row,
            leading = listOf(Piece(Size(60, 16)), Piece(Size(200, 16), maxWidth = M.accountMax, shrinks = true, gapBefore = M.headingGap)),
            trailing = listOf(Piece(Size(M.chevron, M.chevron))),
            trailingGap = M.headingGap,
        )
        assertFrames(heading.trailing, listOf(rect(217, 47.5, 13, 13)))
        assertFrames(heading.leading, listOf(rect(20, 46, 60, 16), rect(85, 46, 110, 16)))
        // With no room for both, the account goes before the service.
        val tight = StemCellPickerLineLayout(
            line = rect(0, 0, 120, 32),
            leading = listOf(Piece(Size(70, 16), shrinks = true), Piece(Size(60, 16), shrinks = true, gapBefore = 5.0)),
            trailing = listOf(Piece(Size(13, 13))),
            trailingGap = 5.0,
        )
        // 12 to 120 − 12 − 13 − 5 = 90: 78 for 70 + 5 + 60.
        XCTAssertEqual(tight.leading.map { it.width }, listOf(70.0, 3.0))
        val tighter = StemCellPickerLineLayout(
            line = rect(0, 0, 80, 32),
            leading = listOf(Piece(Size(70, 16), shrinks = true), Piece(Size(60, 16), shrinks = true, gapBefore = 5.0)),
            trailing = listOf(Piece(Size(13, 13))),
            trailingGap = 5.0,
        )
        XCTAssertEqual(tighter.leading.map { it.width }, listOf(33.0, 0.0))
    }

    @Test fun aGaugeIsItsNumberThenItsRing() {
        val gauge = StemCellUsageGaugeLayout(text = Size(30, 18))
        XCTAssertEqual(gauge.text, rect(0, 0, 30, 18))
        XCTAssertEqual(gauge.ring, rect(34, 2.5, 13, 13))
        XCTAssertEqual(gauge.size, Size(47, 18))
    }
}
