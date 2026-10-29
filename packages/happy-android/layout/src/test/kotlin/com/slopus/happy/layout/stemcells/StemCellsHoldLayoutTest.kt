package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.*
import com.slopus.happy.layout.chat.*
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.


class StemCellsHoldLayoutTest {
    private val menuTitles = listOf(listOf(40.0, 90.0, 95.0), listOf(70.0, 90.0))

    @Test fun theMenuIsTelegramsActionStack() {
        val menu = StemCellsHoldLayout.Menu(titles = menuTitles, titleHeight = 20.3)
        // 95 + 18 + 32 + 20 + 8 is narrower than the least, 220.
        XCTAssertEqual(menu.size, Size(220, 255))
        XCTAssertEqual(menu.corner, 30)
        XCTAssertEqual(menu.items[0][0], StemCellsHoldLayout.Menu.Item(frame = Rect(x = 0.0, y = 10.0, width = 220.0, height = 43.0), icon = Rect(x = 24.0, y = 19.0, width = 24.0, height = 24.0), title = Rect(x = 60.0, y = 21.0, width = 40.0, height = 21.0)))
        XCTAssertEqual(menu.items[0][2].frame.maxY, 139)
        XCTAssertEqual(menu.separators, listOf(Rect(x = 18.0, y = 148.0, width = 184.0, height = 1.0)))
        XCTAssertEqual(menu.items[1][0].frame, Rect(x = 0.0, y = 159.0, width = 220.0, height = 43.0))
    }

    @Test fun aLongTitleWidensTheMenuToAtMost240AndIsCut() {
        val menu = StemCellsHoldLayout.Menu(titles = listOf(listOf(200.0)), titleHeight = 20.0)
        XCTAssertEqual(menu.size.width, 240)
        XCTAssertEqual(menu.items[0][0].title.width, 162)
        XCTAssertEqual(menu.size.height, 62)
        XCTAssertEqual(menu.corner, 30)
    }

    @Test fun thePreviewAndMenuRestOnTheFootWhenCentringWouldOverrunIt() {
        val layout = StemCellsHoldLayout(screen = Size(402, 874), top = 62.0, bottom = 34.0, source = Rect(x = 6.0, y = 343.0, width = 390.0, height = 32.0), card = Size(378, 460), menu = Size(220, 255))
        XCTAssertEqual(layout.card, Rect(x = 12.0, y = 105.0, width = 378.0, height = 460.0))
        XCTAssertEqual(layout.menu, Rect(x = 170.0, y = 575.0, width = 220.0, height = 255.0))
    }

    @Test fun aShortScreenTakesHeightFromThePreview() {
        val layout = StemCellsHoldLayout(screen = Size(375, 667), top = 20.0, bottom = 0.0, source = Rect(x = 6.0, y = 300.0, width = 363.0, height = 32.0), card = Size(351, 460), menu = Size(220, 255))
        XCTAssertEqual(layout.card, Rect(x = 12.0, y = 28.0, width = 351.0, height = 364.0))
        XCTAssertEqual(layout.menu, Rect(x = 143.0, y = 402.0, width = 220.0, height = 255.0))
    }

    @Test fun aRowWithoutAPreviewKeepsItsPlaceWithTheMenuUnderOrOverIt() {
        val screen = Size(402, 874)
        val under = StemCellsHoldLayout(screen = screen, top = 62.0, bottom = 34.0, source = Rect(x = 6.0, y = 343.0, width = 390.0, height = 32.0), card = null, menu = Size(220, 150))
        XCTAssertEqual(under.card, Rect(x = 6.0, y = 343.0, width = 390.0, height = 32.0))
        XCTAssertEqual(under.menu, Rect(x = 170.0, y = 385.0, width = 220.0, height = 150.0))
        val over = StemCellsHoldLayout(screen = screen, top = 62.0, bottom = 34.0, source = Rect(x = 6.0, y = 800.0, width = 390.0, height = 32.0), card = null, menu = Size(220, 150))
        XCTAssertEqual(over.menu, Rect(x = 170.0, y = 640.0, width = 220.0, height = 150.0))
    }
}
