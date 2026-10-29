package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.*
import com.slopus.happy.layout.chat.*
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.


// Exact frames for the conversation's glass header.
class StemCellChatHeaderLayoutTest {
    /// The capsule runs from 8 past the circle to the standard 16 short of
    /// the right edge, whatever the name; the name is centred in it.
    @Test fun theNameSitsInAGlassCapsuleRunningToTheRightEdge() {
        // iPhone 17 Pro: 402 wide, 62 of status bar.
        val layout = StemCellChatHeaderLayout(width = 402.0, safeTop = 62.0, titleWidth = 140.3, statusWidth = null)
        XCTAssertEqual(layout.back, Rect(x = 16.0, y = 66.0, width = 44.0, height = 44.0))
        XCTAssertEqual(layout.pill, Rect(x = 68.0, y = 66.0, width = 318.0, height = 44.0))
        XCTAssertEqual(layout.pill.minX - layout.back.maxX, 8)
        XCTAssertEqual(402 - layout.pill.maxX, 16)
        XCTAssertEqual(layout.title, Rect(x = 157.0, y = 77.0, width = 141.0, height = 22.0))
        XCTAssertNil(layout.status)
        XCTAssertEqual(layout.backdrop, Rect(x = 0.0, y = 0.0, width = 402.0, height = 122.0))
        // The scrim's 36 of feather end under the capsule, not on it.
        XCTAssertEqual(layout.scrim, Rect(x = 0.0, y = 0.0, width = 402.0, height = 148.0))
        XCTAssertEqual(layout.listTop, 114)
        XCTAssertEqual(layout.titleMaxWidth, 286)
    }

    @Test fun aStatusAddsAGreyLineUnderTheName() {
        val layout = StemCellChatHeaderLayout(width = 402.0, safeTop = 62.0, titleWidth = 60.0, statusWidth = 52.0)
        XCTAssertEqual(layout.pill, Rect(x = 68.0, y = 64.0, width = 318.0, height = 48.0))
        XCTAssertEqual(layout.title, Rect(x = 197.0, y = 69.0, width = 60.0, height = 22.0))
        XCTAssertEqual(layout.status, Rect(x = 201.0, y = 91.0, width = 52.0, height = 16.0))
        // The same scrim with a status or without: it never moves.
        XCTAssertEqual(layout.scrim.maxY, 148)
        XCTAssertEqual(layout.scrim.maxY - layout.pill.maxY, HeaderScrim.rampPoints)
    }

    /// At rest nothing is under the header, so nothing is washed; scrolled
    /// the 8 of room under it, before anything meets the capsule, whole.
    @Test fun theScrimComesInAsTheTranscriptScrollsUnder() {
        XCTAssertEqual(HeaderScrim.presence(scrolledUnder = -20.0), 0)
        XCTAssertEqual(HeaderScrim.presence(scrolledUnder = 0.0), 0)
        XCTAssertEqual(HeaderScrim.presence(scrolledUnder = 4.0), 0.5)
        XCTAssertEqual(HeaderScrim.presence(scrolledUnder = 8.0), 1)
        XCTAssertEqual(HeaderScrim.presence(scrolledUnder = 400.0), 1)
    }

    @Test fun aLongNameIsCutInsideTheCapsule() {
        val layout = StemCellChatHeaderLayout(width = 402.0, safeTop = 62.0, titleWidth = 900.0, statusWidth = null)
        XCTAssertEqual(layout.pill, Rect(x = 68.0, y = 66.0, width = 318.0, height = 44.0))
        XCTAssertEqual(layout.title, Rect(x = 84.0, y = 77.0, width = 286.0, height = 22.0))
        XCTAssertEqual(layout.titleMaxWidth, 286)
    }

    /// The back circle stands where home's account picture does.
    @Test fun theBackCircleIsHomesLeftControl() {
        var input = HomeHeaderLayout.Input(width = 402.0, safeTop = 62.0, hairline = 1.0 / 3, title = Measurable { _ -> Size(90, 20) }, status = null)
        input.showsAccountAvatar = true
        val home = HomeHeaderLayout(input)
        val layout = StemCellChatHeaderLayout(width = 402.0, safeTop = 62.0, titleWidth = 90.0, statusWidth = null)
        XCTAssertEqual(layout.back, home.logoControl)
    }
}
