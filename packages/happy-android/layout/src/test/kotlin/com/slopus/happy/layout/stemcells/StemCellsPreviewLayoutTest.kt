package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.*
import com.slopus.happy.layout.chat.*
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.


class StemCellsPreviewLayoutTest {
    @Test fun aPhonesPreviewIsTheScreenLess12ASideAndAtMost460Tall() {
        // iPhone 17 Pro: 402 × 874, 62 over and 34 under the safe area.
        XCTAssertEqual(StemCellsPreviewLayout.size(screen = Size(402, 874), top = 62.0, bottom = 34.0, regular = false), Size(378, 460))
        // A short screen gives up height before the menu's room.
        XCTAssertEqual(StemCellsPreviewLayout.size(screen = Size(375, 500), top = 20.0, bottom = 0.0, regular = false), Size(351, 456))
    }

    @Test fun aRegularWidthPreviewIsAtMost400Wide() {
        XCTAssertEqual(StemCellsPreviewLayout.size(screen = Size(1024, 1366), top = 24.0, bottom = 20.0, regular = true), Size(400, 460))
    }

    @Test fun theNameSitsCentredInACapsuleAtTheTopAndTheMessagesUnderIt() {
        val layout = StemCellsPreviewLayout(size = Size(378, 460), titleSize = Size(120.4, 20.3))
        XCTAssertEqual(layout.pill, Rect(x = 112.0, y = 10.0, width = 153.0, height = 44.0))
        XCTAssertEqual(layout.title, Rect(x = 128.0, y = 21.0, width = 121.0, height = 21.0))
        XCTAssertEqual(layout.listTop, 62)
        XCTAssertEqual(layout.listBottom, 8)
        // The ground covers down to the capsule's foot and is gone 24 under it.
        XCTAssertEqual(layout.fadeClear, 54)
        XCTAssertEqual(layout.fadeOpaque, 78)
    }

    @Test fun aLongNameIsCutToTheCardLessItsMargins() {
        val layout = StemCellsPreviewLayout(size = Size(378, 460), titleSize = Size(900, 20))
        XCTAssertEqual(layout.pill, Rect(x = 16.0, y = 10.0, width = 346.0, height = 44.0))
        XCTAssertEqual(layout.title.width, 314)
    }
}
