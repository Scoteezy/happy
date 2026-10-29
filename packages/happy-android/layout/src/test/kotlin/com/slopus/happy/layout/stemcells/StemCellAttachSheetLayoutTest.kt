package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.*
import com.slopus.happy.layout.chat.*
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.

class StemCellAttachSheetLayoutTest {
    @Test fun theSheetOnAnIPhone17Pro() {
        // 402 wide; "Add to message" 120.4, "Photos" 52.3; the camera and
        // three photos; Paste and Add files without a value, Permission "Auto".
        val layout = StemCellAttachSheetLayout(width = 402.0, titleWidth = 120.4, photosWidth = 52.3, tiles = 4, values = listOf(null, null, 30.2))
        // Photos at the left, the close circle at the right.
        XCTAssertEqual(layout.photos, rect(16, 16, 53, 44))
        XCTAssertEqual(layout.close, rect(342, 16, 44, 44))
        // Centred on the sheet, as the header's middle.
        XCTAssertEqual(layout.title, rect(141, 27, 121, 22))
        XCTAssertEqual(layout.strip, rect(0, 76, 402, 104))
        XCTAssertEqual(layout.tiles, listOf(rect(16, 0, 104, 104), rect(128, 0, 104, 104), rect(240, 0, 104, 104), rect(352, 0, 104, 104)))
        // 16 in from both ends: the last tile scrolls fully into view.
        XCTAssertEqual(layout.stripContentWidth, 472)
        XCTAssertEqual(layout.group, rect(16, 196, 370, 156))
        XCTAssertEqual(layout.height, 368)

        val paste = layout.rows[0]
        XCTAssertEqual(paste.frame, rect(0, 0, 370, 52))
        XCTAssertEqual(paste.icon, rect(16, 14, 24, 24))
        XCTAssertEqual(paste.title, rect(52, 15, 302, 22))
        XCTAssertNil(paste.value)
        XCTAssertNil(paste.chevron)
        XCTAssertNil(paste.separator)

        val files = layout.rows[1]
        XCTAssertEqual(files.frame, rect(0, 52, 370, 52))
        XCTAssertEqual(files.separator, rect(52, 52, 318, 1.0 / 3))

        val permission = layout.rows[2]
        XCTAssertEqual(permission.chevron, rect(340, 123, 14, 14))
        XCTAssertEqual(permission.value, rect(303, 119, 31, 22))
        XCTAssertEqual(permission.title, rect(52, 119, 245, 22))
    }

    @Test fun aLongTitleKeepsClearOfTheControls() {
        // "Add 12" is wider than the circle: the room is kept from it on both sides.
        val layout = StemCellAttachSheetLayout(width = 402.0, titleWidth = 400.0, photosWidth = 60.0, tiles = 1, values = emptyList())
        XCTAssertEqual(layout.photos, rect(16, 16, 60, 44))
        XCTAssertEqual(layout.title, rect(84, 27, 234, 22))
        XCTAssertEqual(layout.title.minX - layout.photos.maxX, 8)
        XCTAssertEqual(layout.close.minX - layout.title.maxX, 24)
        XCTAssertEqual(layout.stripContentWidth, 136)
        XCTAssertEqual(layout.group, rect(16, 196, 370, 0))
    }

    @Test fun aLongValueTakesAtMostHalfTheRoom() {
        val layout = StemCellAttachSheetLayout(width = 402.0, titleWidth = 100.0, photosWidth = 50.0, tiles = 1, values = listOf(500.0))
        val row = layout.rows[0]
        // From the title's start to the chevron's gap: 282, half of it 141.
        XCTAssertEqual(row.value, rect(193, 15, 141, 22))
        XCTAssertEqual(row.title, rect(52, 15, 135, 22))
    }

    @Test fun theCheckSitsInTheTilesCorner() {
        XCTAssertEqual(StemCellAttachSheetLayout.check, rect(74, 6, 24, 24))
    }
}
