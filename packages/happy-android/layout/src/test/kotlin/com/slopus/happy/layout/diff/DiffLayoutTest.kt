package com.slopus.happy.layout.diff

import com.slopus.happy.layout.Size
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertFalse
import com.slopus.happy.layout.XCTAssertGreaterThan
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.XCTAssertTrue
import com.slopus.happy.layout.assertFrame
import com.slopus.happy.layout.assertSize
import com.slopus.happy.layout.rect
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/DiffLayoutTests.swift
// (DiffMetricsTests, DiffFileLayoutTests).

class DiffMetricsTest {
    @Test fun lineHeightAndNominalAdvance() {
        val metrics = DiffMetrics()
        XCTAssertEqual(metrics.fontSize, 12.0)
        XCTAssertEqual(metrics.lineHeight, 18.0)
        XCTAssertEqual(metrics.charWidth, 7.2, 0.0001)
        XCTAssertEqual(metrics.markerWidth, 15.0)
        XCTAssertEqual(DiffMetrics(fontSize = 12.5).lineHeight, 19.0)
        XCTAssertEqual(DiffMetrics(fontSize = 11.0, measuredCharWidth = 6.5).charWidth, 6.5)
    }

    @Test fun gutterWidthFor() {
        // layout.spec.ts.
        XCTAssertEqual(DiffMetrics.gutterWidth(7.0, 1234, false), 18.0)
        XCTAssertEqual(DiffMetrics.gutterWidth(7.2, 1, true), DiffMetrics.gutterWidth(7.2, 99, true))
        XCTAssertEqual(DiffMetrics.gutterWidth(7.2, 99, true), 41.0)
        XCTAssertEqual(DiffMetrics.gutterWidth(7.2, 120, true), 48.0)
        XCTAssertEqual(DiffMetrics.gutterWidth(7.2, 1234, true), 56.0)
        XCTAssertGreaterThan(DiffMetrics.gutterWidth(7.2, 100_000, true), 56)
        XCTAssertGreaterThan(DiffMetrics.gutterWidth(9.0, 99, true), 41)
    }
}

class DiffFileLayoutTest {
    @Test fun fitsTheViewport() {
        val layout = DiffFileLayout(DiffFileLayout.Input(width = 393.0, rowCount = 10, maxColumns = 40, maxLineNo = 120, showMoreTextHeight = 17.0))
        XCTAssertEqual(layout.gutterWidth, 48.0)
        XCTAssertEqual(layout.codeWidth, 311.0)
        XCTAssertEqual(layout.contentWidth, 345.0)
        XCTAssertFalse(layout.scrollable)
        assertFrame(layout.scroll, rect(0, 0, 393, 180))
        assertSize(layout.scrollContentSize, Size(393.0, 180.0))
        assertFrame(layout.canvas, rect(48, 0, 345, 180))
        assertFrame(layout.gutter, rect(0, 0, 48, 180))
        XCTAssertNil(layout.showMore)
        XCTAssertNil(layout.showMoreText(100.0))
        XCTAssertEqual(layout.height, 180.0)

        assertFrame(layout.row(3), rect(0, 54, 345, 18))
        assertFrame(layout.gutterRow(3), rect(0, 54, 48, 18))
        assertFrame(layout.codeText(100.0), rect(6, 0, 100, 18))
        assertFrame(layout.lineNumber, rect(0, 0, 33, 18))
        assertFrame(layout.marker, rect(33, 0, 15, 18))
        assertFrame(layout.hunkDots, rect(6, 0, 42, 18))
    }

    @Test fun scrollsLongLinesAndHoldsRowsBack() {
        val layout = DiffFileLayout(
            DiffFileLayout.Input(width = 393.0, rowCount = 10, hiddenCount = 5, maxColumns = 100, maxLineNo = 120, paddingVertical = 4.0, showMoreTextHeight = 17.0),
        )
        XCTAssertEqual(layout.codeWidth, 743.0)
        XCTAssertEqual(layout.contentWidth, 743.0)
        XCTAssertTrue(layout.scrollable)
        assertFrame(layout.scroll, rect(0, 0, 393, 188))
        assertSize(layout.scrollContentSize, Size(791.0, 188.0))
        assertFrame(layout.canvas, rect(48, 4, 743, 180))
        assertFrame(layout.gutter, rect(0, 4, 48, 180))
        assertFrame(layout.showMore, rect(0, 188, 393, 38))
        assertFrame(layout.showMoreText(120.0), rect(136.5, 11, 120, 17))
        // Busy: [spinner 20] 8 [text 120], centred in 393.
        val busy = layout.showMoreContent(120.0, busy = true)
        assertFrame(busy?.second, rect(122.5, 9.5, 20, 20))
        assertFrame(busy?.first, rect(150.5, 11, 120, 17))
        XCTAssertEqual(layout.height, 226.0)
    }

    @Test fun withoutLineNumbersTheGutterIsTheMarker() {
        val layout = DiffFileLayout(DiffFileLayout.Input(width = 300.0, rowCount = 1, maxColumns = 10, maxLineNo = 5000, showLineNumbers = false, showMoreTextHeight = 17.0))
        XCTAssertEqual(layout.gutterWidth, 19.0)
        XCTAssertNil(layout.lineNumber)
        assertFrame(layout.marker, rect(0, 0, 15, 18))
    }

    @Test fun visibleRows() {
        val layout = DiffFileLayout(DiffFileLayout.Input(width = 393.0, rowCount = 10, maxColumns = 40, maxLineNo = 9, showMoreTextHeight = 17.0))
        XCTAssertEqual(layout.rows(20.0, 60.0), 1 until 4)
        XCTAssertEqual(layout.rows(-100.0, 10.0), 0 until 1)
        XCTAssertEqual(layout.rows(170.0, 1000.0), 9 until 10)
        XCTAssertTrue(layout.rows(200.0, 300.0).isEmpty())
        XCTAssertTrue(layout.rows(50.0, 50.0).isEmpty())
        XCTAssertEqual(layout.rowIndex(35.9), 1)
        XCTAssertEqual(layout.rowIndex(36.0), 2)
        XCTAssertNil(layout.rowIndex(180.0))
        XCTAssertNil(layout.rowIndex(-1.0))
    }

    @Test fun emptyFile() {
        val layout = DiffFileLayout(DiffFileLayout.Input(width = 393.0, rowCount = 0, maxColumns = 0, maxLineNo = 0, showMoreTextHeight = 17.0))
        XCTAssertEqual(layout.height, 0.0)
        XCTAssertTrue(layout.rows(0.0, 100.0).isEmpty())
    }

    @Test fun textWindowSnapsToSteps() {
        XCTAssertEqual(DiffTextWindow.span(5000.0, 600.0..993.0, 393.0), 0.0..1536.0)
        XCTAssertEqual(DiffTextWindow.span(5000.0, 2000.0..2393.0, 393.0), 1536.0..3072.0)
        XCTAssertEqual(DiffTextWindow.span(1000.0, 0.0..393.0, 393.0), 0.0..1000.0)
        XCTAssertNil(DiffTextWindow.span(0.0, 0.0..393.0, 393.0))
        XCTAssertNil(DiffTextWindow.span(1000.0, 3000.0..3393.0, 393.0))
    }

    @Test fun chunkStacksFiles() {
        val chunk = DiffChunkLayout(
            listOf(
                DiffFileLayout.Input(width = 393.0, rowCount = 10, maxColumns = 40, maxLineNo = 120, showMoreTextHeight = 17.0),
                DiffFileLayout.Input(width = 393.0, rowCount = 10, hiddenCount = 5, maxColumns = 100, maxLineNo = 120, paddingVertical = 4.0, showMoreTextHeight = 17.0),
            ),
        )
        assertFrame(chunk.frames[0], rect(0, 0, 393, 180))
        assertFrame(chunk.frames[1], rect(0, 180, 393, 226))
        assertSize(chunk.size, Size(393.0, 406.0))
        XCTAssertEqual(DiffChunkLayout.COLLAPSE_AFTER, 80)
    }
}
