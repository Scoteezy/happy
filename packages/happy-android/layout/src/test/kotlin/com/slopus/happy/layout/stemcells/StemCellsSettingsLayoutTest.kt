package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.Size
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.fixed
import com.slopus.happy.layout.rect
import com.slopus.happy.layout.stemcells.StemCellsProfileCarousel as C
import com.slopus.happy.layout.stemcells.StemCellsSettingsLayout as L
import com.slopus.happy.layout.stemcells.StemCellsSettingsLayout.Accessory
import com.slopus.happy.layout.stemcells.StemCellsSettingsLayout.RowSpec
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/StemCellsSettingsLayoutTests.swift.
// Its link sheet and switch cases are StemCellsLinkLayoutTest.

/**
 * Stem Cells settings on a 390-wide phone: the bar, the profile strip,
 * the cards and the QR sheet, frame by frame.
 */
class StemCellsSettingsLayoutTest {
    @Test fun searchingTheCardsMoveUpUnderTheField() {
        val layout = L(390.0, 34.0, 22.0, listOf(listOf(RowSpec(hasIcon = true, accessory = Accessory.Chevron))), searching = true)
        XCTAssertEqual(layout.cards[0].frame, rect(16, 8, 358, 52))
        XCTAssertEqual(layout.empty, rect(0, 40, 390, 24))
        XCTAssertEqual(layout.contentHeight, 92)
        val nothing = L(390.0, 34.0, 22.0, emptyList(), searching = true)
        XCTAssertEqual(nothing.contentHeight, 40)
    }

    @Test fun theChosenProfileIsCentredLargerAndTheOthersShrinkWithDistance() {
        XCTAssertEqual(C.Metrics.height, 104)
        XCTAssertEqual(C.contentWidth(count = 3, width = 390.0), 558)
        XCTAssertEqual(C.item(0, offset = 0.0, width = 390.0), C.Item(frame = rect(149, 6, 92, 92), emphasis = 1.0))
        XCTAssertEqual(C.item(1, offset = 0.0, width = 390.0), C.Item(frame = rect(249, 22, 60, 60), emphasis = 0.0))
        // Halfway between two, both are halfway sizes.
        XCTAssertEqual(C.item(0, offset = 42.0, width = 390.0), C.Item(frame = rect(157, 14, 76, 76), emphasis = 0.5))
        XCTAssertEqual(C.item(1, offset = 42.0, width = 390.0), C.Item(frame = rect(241, 14, 76, 76), emphasis = 0.5))
        // A flick rests on an item, never past the ends.
        XCTAssertEqual(C.snapped(100.0, count = 3), 84)
        XCTAssertEqual(C.snapped(500.0, count = 3), 168)
        XCTAssertEqual(C.index(offset = -50.0, count = 3), 0)
    }

    @Test fun theCardsAreInsetWithTilesValuesChevronsAndAToggle() {
        val layout = L(390.0, 34.0, 22.0, listOf(
            listOf(RowSpec(hasIcon = true, accessory = Accessory.Toggle)),
            listOf(RowSpec(hasIcon = true, accessory = Accessory.Chevron), RowSpec(hasIcon = true, valueWidth = 40.0, accessory = Accessory.Chevron)),
            listOf(RowSpec(hasIcon = false, accessory = Accessory.None)),
        ))
        XCTAssertEqual(layout.strip, rect(0, 8, 390, 104))
        XCTAssertEqual(layout.name, rect(24, 124, 342, 34))
        XCTAssertEqual(layout.subtitle, rect(24, 160, 342, 22))

        val prioritizing = layout.cards[0]
        XCTAssertEqual(prioritizing.frame, rect(16, 210, 358, 52))
        XCTAssertEqual(prioritizing.rows[0].icon, rect(16, 11, 30, 30))
        XCTAssertEqual(prioritizing.rows[0].title, rect(62, 15, 209, 22))
        XCTAssertEqual(prioritizing.rows[0].toggle, rect(279, 12, 63, 28))
        XCTAssertNil(prioritizing.rows[0].separator)

        val more = layout.cards[1]
        XCTAssertEqual(more.frame, rect(16, 286, 358, 104))
        XCTAssertEqual(more.rows[0].chevron, rect(334, 19, 8, 14))
        XCTAssertEqual(more.rows[0].title, rect(62, 15, 264, 22))
        XCTAssertEqual(more.rows[0].separator, rect(62, 52 - 1.0 / 3, 296, 1.0 / 3))
        XCTAssertEqual(more.rows[1].chevron, rect(334, 71, 8, 14))
        XCTAssertEqual(more.rows[1].value, rect(286, 67, 40, 22))
        XCTAssertEqual(more.rows[1].title, rect(62, 67, 216, 22))
        XCTAssertNil(more.rows[1].separator)

        val disconnect = layout.cards[2]
        XCTAssertEqual(disconnect.frame, rect(16, 414, 358, 52))
        XCTAssertNil(disconnect.rows[0].icon)
        XCTAssertEqual(disconnect.rows[0].title, rect(16, 15, 326, 22))
        XCTAssertEqual(layout.contentHeight, 498)
    }

    @Test fun theQRSheetSitsAtTheFootWithThePictureCentredAboveIt() {
        val sheet = StemCellsQRSheetLayout(Size(390, 844), safeTop = 47.0, safeBottom = 34.0, nameHeight = 28.0, text = fixed(300, 44))
        XCTAssertEqual(sheet.sheet, rect(0, 570, 390, 274))
        XCTAssertEqual(sheet.close, rect(330, 16, 44, 44))
        XCTAssertEqual(sheet.title, rect(74, 25, 242, 22))
        XCTAssertEqual(sheet.text, rect(30, 58, 330, 44))
        XCTAssertEqual(sheet.primary, rect(30, 126, 330, 52))
        XCTAssertEqual(sheet.secondary, rect(30, 188, 330, 52))
        XCTAssertEqual(sheet.avatar, rect(145, 237, 100, 100))
        XCTAssertEqual(sheet.name, rect(30, 353, 330, 28))
    }
}
