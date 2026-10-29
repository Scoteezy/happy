package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.*
import com.slopus.happy.layout.chat.*
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.


// The working line on a 390-wide phone, frame by frame.
class StemCellWorkingLayoutTest {
    @Test fun theLoaderSitsInItsColumnThenClockDotAndPhase() {
        val layout = StemCellWorkingLayout(width = 390.0, glyph = StemCellWorkingLayout.Glyph.Loader, timer = 19.5, separator = 4.25, phase = 70.5)
        XCTAssertEqual(layout.glyph, Rect(x = 19.0, y = 13.0, width = 8.0, height = 14.0))
        XCTAssertEqual(layout.timer, Rect(x = 36.0, y = 8.0, width = 19.5, height = 24.0))
        XCTAssertEqual(layout.separator, Rect(x = 61.5, y = 8.0, width = 4.25, height = 24.0))
        XCTAssertEqual(layout.phase, Rect(x = 71.75, y = 8.0, width = 70.5, height = 24.0))
    }

    @Test fun aMarkFillsTheColumnAndALongPhaseStopsAtTheSide() {
        val layout = StemCellWorkingLayout(width = 390.0, glyph = StemCellWorkingLayout.Glyph.Mark, timer = null, separator = null, phase = 900.0)
        XCTAssertEqual(layout.glyph, Rect(x = 16.0, y = 13.0, width = 14.0, height = 14.0))
        XCTAssertNil(layout.timer)
        XCTAssertNil(layout.separator)
        XCTAssertEqual(layout.phase, Rect(x = 36.0, y = 8.0, width = 338.0, height = 24.0))
    }

    @Test fun theWaitRingKeepsItsStrokeInsideTheColumn() {
        XCTAssertEqual(StemCellWorkingLayout.ringRadius(Size(14, 14)), 6)
        XCTAssertEqual(StemCellWorkingLayout.ringRadius(Size(1, 14)), 0)
    }
}
