package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.*
import com.slopus.happy.layout.chat.*
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.

class StemCellChatViewingLayoutTest {
    @Test fun pagesAreAScreenWideAndAGapApart() {
        val layout = StemCellPicturePagerLayout(size = Size(402, 874), count = 4)
        XCTAssertEqual(layout.pager, Rect(x = -10.0, y = 0.0, width = 422.0, height = 874.0))
        XCTAssertEqual(layout.contentSize, Size(1688, 874))
        XCTAssertEqual(layout.page(0), Rect(x = 10.0, y = 0.0, width = 402.0, height = 874.0))
        XCTAssertEqual(layout.page(2), Rect(x = 854.0, y = 0.0, width = 402.0, height = 874.0))
        XCTAssertEqual(layout.offset(3), 1266)
    }

    @Test fun thePageMostInViewIsTheIndex() {
        val layout = StemCellPicturePagerLayout(size = Size(402, 874), count = 4)
        XCTAssertEqual(layout.index(offset = 0.0, count = 4), 0)
        XCTAssertEqual(layout.index(offset = 210.0, count = 4), 0)
        XCTAssertEqual(layout.index(offset = 212.0, count = 4), 1)
        XCTAssertEqual(layout.index(offset = 5000.0, count = 4), 3)
        XCTAssertEqual(layout.index(offset = -300.0, count = 4), 0)
    }

    @Test fun aDragDownFadesTheGroundAndShrinksThePicture() {
        val rest = StemCellPictureDismissal(translation = 0.0, velocity = 0.0, height = 874.0)
        XCTAssertEqual(rest.groundAlpha, 1)
        XCTAssertEqual(rest.scale, 1)
        XCTAssertFalse(rest.closes)

        val half = StemCellPictureDismissal(translation = 218.5, velocity = 0.0, height = 874.0)
        XCTAssertEqual(half.progress, 0.5, accuracy = 0.0001)
        XCTAssertEqual(half.groundAlpha, 0.5, accuracy = 0.0001)
        XCTAssertEqual(half.scale, 0.9, accuracy = 0.0001)
        XCTAssertTrue(half.closes)

        val far = StemCellPictureDismissal(translation = -600.0, velocity = 0.0, height = 874.0)
        XCTAssertEqual(far.groundAlpha, 0)
        XCTAssertEqual(far.scale, 0.8, accuracy = 0.0001)
        XCTAssertTrue(far.closes, "up closes too")
    }

    @Test fun letGoShortItStaysUnlessFlung() {
        XCTAssertFalse(StemCellPictureDismissal(translation = 60.0, velocity = 200.0, height = 874.0).closes)
        XCTAssertTrue(StemCellPictureDismissal(translation = 40.0, velocity = 1200.0, height = 874.0).closes)
        XCTAssertFalse(StemCellPictureDismissal(translation = 5.0, velocity = 1200.0, height = 874.0).closes, "a tap's wobble")
        XCTAssertFalse(StemCellPictureDismissal(translation = 160.0, velocity = -1200.0, height = 874.0).closes, "flung back")
    }

    /// Home's scrim, whole from the card's top to the foot and feathered
    /// over the 36 above it, and the way back above the list's end, over
    /// the feather (drawn in front of it).
    @Test fun homesScrimUnderTheCardAndTheWayBackAboveIt() {
        // iPhone 17 Pro, keyboard down: a 64 card 8 above the 34 home indicator.
        val size = Size(402, 874)
        val card = Rect(x = 8.0, y = 768.0, width = 386.0, height = 64.0)
        val layout = StemCellChatFootLayout(size = size, card = card)
        XCTAssertEqual(layout.scrim, Rect(x = 0.0, y = 732.0, width = 402.0, height = 142.0))
        XCTAssertEqual(card.minY - layout.scrim.minY, HeaderScrim.rampPoints)
        XCTAssertEqual(layout.jump, Rect(x = 185.0, y = 716.0, width = 32.0, height = 32.0))
        XCTAssertLessThanOrEqual(layout.jump.maxY, card.minY - 8)
    }

    @Test fun theWayBackShowsPastAThirdOfTheList() {
        XCTAssertFalse(StemCellChatFootLayout.showsJump(distanceFromEnd = 200.0, viewport = 600.0))
        XCTAssertTrue(StemCellChatFootLayout.showsJump(distanceFromEnd = 201.0, viewport = 600.0))
        XCTAssertFalse(StemCellChatFootLayout.showsJump(distanceFromEnd = 0.0, viewport = -40.0), "no list to be away from")
    }
}
