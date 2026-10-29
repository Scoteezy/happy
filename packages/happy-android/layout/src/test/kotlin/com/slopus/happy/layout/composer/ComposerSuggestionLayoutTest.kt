package com.slopus.happy.layout.composer

import com.slopus.happy.layout.Size
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.XCTAssertTrue
import com.slopus.happy.layout.assertFrame
import com.slopus.happy.layout.rect
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/ComposerSuggestionLayoutTests.swift.

/** AgentInputSuggestionView.tsx's rows. */
class ComposerSuggestionLayoutTest {
    @Test fun testACommandAndItsDescription() {
        val layout = ComposerSuggestionRowLayout(361.0, ComposerSuggestionRowLayout.Input.Command(Size(70.0, 17.0), Size(400.0, 16.0)))
        XCTAssertEqual(layout.size, Size(361.0, 48.0))
        assertFrame(layout.name, rect(16, 15.5, 70, 17))
        // 16 + 70 + 12 = 98, to 345.
        assertFrame(layout.description, rect(98, 16, 247, 16))
        XCTAssertNil(layout.icon)
    }

    @Test fun testACommandWithoutADescription() {
        val layout = ComposerSuggestionRowLayout(361.0, ComposerSuggestionRowLayout.Input.Command(Size(50.0, 17.0), null))
        assertFrame(layout.name, rect(16, 15.5, 50, 17))
        XCTAssertNil(layout.description)
    }

    @Test fun testALongCommandStopsAtThePadding() {
        val layout = ComposerSuggestionRowLayout(200.0, ComposerSuggestionRowLayout.Input.Command(Size(300.0, 17.0), Size(40.0, 16.0)))
        assertFrame(layout.name, rect(16, 15.5, 168, 17))
        assertFrame(layout.description, rect(196, 16, 0, 16))
    }

    @Test fun testAFile() {
        val layout = ComposerSuggestionRowLayout(361.0, ComposerSuggestionRowLayout.Input.File(Size(500.0, 17.0), Size(28.0, 14.5)))
        assertFrame(layout.icon, rect(16, 8, 32, 32))
        assertFrame(layout.iconGlyph, rect(23, 15, 18, 18))
        // 16 + 32 + 12 = 60; the label is 28 wide 8 in from 345.
        assertFrame(layout.path, rect(60, 15.5, 249, 17))
        assertFrame(layout.label, rect(317, 16.75, 28, 14.5))
    }

    @Test fun testAShortPathStillTakesTheRoom() {
        val layout = ComposerSuggestionRowLayout(361.0, ComposerSuggestionRowLayout.Input.File(Size(40.0, 17.0), Size(50.0, 14.5)))
        assertFrame(layout.path, rect(60, 15.5, 227, 17))
        assertFrame(layout.label, rect(295, 16.75, 50, 14.5))
    }

    @Test fun testTheListsRows() {
        val rows = ComposerSuggestionListLayout.rows(3, 361.0, 0.5)
        XCTAssertEqual(rows.size, 3)
        assertFrame(rows[0], rect(0, 0, 360, 48))
        assertFrame(rows[2], rect(0, 96, 360, 48))
        XCTAssertTrue(ComposerSuggestionListLayout.rows(0, 361.0, 0.5).isEmpty())
    }
}
