package com.slopus.happy.layout

import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/FileViewerLayoutTests.swift.

/** file.tsx's header, toggle and DiffDisplay rows. */
class FileViewerLayoutTest {
    @Test fun testHeaderAndToggle() {
        val layout = FileViewerLayout(FileViewerLayout.Input(
            size = Size(393, 852),
            top = 100.0,
            path = fixed(200, 18),
            toggle = FileViewerLayout.Toggle(diff = Size(30, 18), file = Size(26, 18)),
        ))
        assertFrame(layout.header, rect(0, 100, 393, 52.5))
        assertFrame(layout.icon, rect(16, 116, 20, 20))
        assertFrame(layout.path, rect(44, 117, 333, 18))
        assertFrame(layout.headerBorder, rect(0, 152, 393, 0.5))
        assertFrame(layout.toggleBar, rect(0, 152.5, 393, 58.5))
        assertFrame(layout.diffButton, rect(16, 164.5, 62, 34))
        assertFrame(layout.diffLabel, rect(32, 172.5, 30, 18))
        assertFrame(layout.fileButton, rect(86, 164.5, 58, 34))
        assertFrame(layout.fileLabel, rect(102, 172.5, 26, 18))
        assertFrame(layout.toggleBorder, rect(0, 210.5, 393, 0.5))
        assertFrame(layout.content, rect(0, 211, 393, 641))
    }

    @Test fun testLongPathsWrapAndNoToggle() {
        val layout = FileViewerLayout(FileViewerLayout.Input(size = Size(393, 852), top = 0.0, path = text("a".repeat(60), advance = 8.0, lineHeight = 18.0), toggle = null))
        // 333 wide holds 41 characters: two lines. The text box is flex: 1.
        assertFrame(layout.path, rect(44, 16, 333, 36))
        assertFrame(layout.icon, rect(16, 24, 20, 20))
        assertFrame(layout.header, rect(0, 0, 393, 68.5))
        XCTAssertNil(layout.toggleBar)
        XCTAssertNil(layout.diffButton)
        assertFrame(layout.content, rect(0, 68.5, 393, 783.5))
    }

    @Test fun testDiffRows() {
        val none = Double.MAX_VALUE
        val diff = FileLinesLayout.RowInsets.diff
        XCTAssertEqual(FileLinesLayout.textWidth(width = 393.0, maxWidth = none, insets = diff, bar = false), 345)
        XCTAssertEqual(FileLinesLayout.textWidth(width = 393.0, maxWidth = none, insets = diff, bar = true), 342)
        val layout = FileLinesLayout(width = 393.0, maxWidth = none, insets = diff, textHeights = listOf(20.0, 40.0, 20.0), bars = listOf(false, true, false))
        XCTAssertEqual(layout.count, 3)
        assertFrame(layout.row(0), rect(16, 16, 361, 22))
        assertFrame(layout.row(1), rect(16, 38, 361, 42))
        assertFrame(layout.row(2), rect(16, 80, 361, 22))
        assertFrame(layout.text(0), rect(8, 1, 345, 20))
        assertFrame(layout.text(1), rect(11, 1, 342, 40))
        XCTAssertNil(layout.bar(0))
        assertFrame(layout.bar(1), rect(0, 0, 3, 42))
        assertFrame(layout.block, rect(16, 16, 361, 86))
        assertSize(layout.contentSize, Size(393, 118))
        XCTAssertEqual(layout.rows(0.0, 16.0), 0 until 0)
        XCTAssertEqual(layout.rows(0.0, 17.0), 0 until 1)
        XCTAssertEqual(layout.rows(37.0, 39.0), 0 until 2)
        XCTAssertEqual(layout.rows(38.0, 80.0), 1 until 2)
        XCTAssertEqual(layout.rows(50.0, 1000.0), 1 until 3)
        XCTAssertEqual(layout.rows(102.0, 1000.0), 3 until 3)
    }

    @Test fun testCodeLinesCentreInTheMaxWidth() {
        // An iPad's 1024 holds the 800 column, centred.
        val code = FileLinesLayout.RowInsets.code
        XCTAssertEqual(FileLinesLayout.textWidth(width = 1024.0, maxWidth = 800.0, insets = code, bar = false), 768)
        val layout = FileLinesLayout(width = 1024.0, maxWidth = 800.0, insets = code, textHeights = listOf(20.0, 60.0), bars = emptyList())
        assertFrame(layout.row(0), rect(128, 16, 768, 20))
        assertFrame(layout.row(1), rect(128, 36, 768, 60))
        assertFrame(layout.text(1), rect(0, 0, 768, 60))
        XCTAssertNil(layout.bar(1))
        assertSize(layout.contentSize, Size(1024, 112))
    }

    @Test fun testMessage() {
        val message = FileLinesLayout.message(width = 393.0, maxWidth = Double.MAX_VALUE, text = fixed(120, 21))
        assertFrame(message.first, rect(16, 16, 361, 21))
        assertSize(message.second, Size(393, 53))
        val wide = FileLinesLayout.message(width = 1024.0, maxWidth = 800.0, text = fixed(120, 21))
        assertFrame(wide.first, rect(128, 16, 768, 21))
    }

    @Test fun testNoRows() {
        val layout = FileLinesLayout(width = 393.0, maxWidth = Double.MAX_VALUE, insets = FileLinesLayout.RowInsets.diff, textHeights = emptyList(), bars = emptyList())
        XCTAssertEqual(layout.count, 0)
        XCTAssertEqual(layout.rows(0.0, 100.0), 0 until 0)
        assertFrame(layout.block, rect(16, 16, 361, 0))
        assertSize(layout.contentSize, Size(393, 32))
    }

    @Test fun testPlaceRoundTrips() {
        val layout = FileLinesLayout(width = 393.0, maxWidth = Double.MAX_VALUE, insets = FileLinesLayout.RowInsets.diff, textHeights = listOf(20.0, 40.0, 20.0), bars = emptyList())
        XCTAssertNil(layout.place(10.0))
        val place = layout.place(59.0)!!
        XCTAssertEqual(place.row, 1)
        XCTAssertEqual(place.fraction, 0.5)
        XCTAssertEqual(layout.y(place), 59)
        XCTAssertEqual(layout.place(500.0), FileLinesLayout.Place(2, 1.0))
    }
}
