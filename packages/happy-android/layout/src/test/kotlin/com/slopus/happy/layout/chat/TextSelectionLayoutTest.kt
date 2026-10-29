package com.slopus.happy.layout.chat

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.assertFrame
import com.slopus.happy.layout.rect
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/ChatLayoutTests.swift (TextSelectionLayoutTests).

class TextSelectionLayoutTest {
    @Test fun testTheCardRunsToTheFootOfTheScreenAndTheTextClearsTheHomeIndicator() {
        val layout = TextSelectionLayout(Size(393.0, 852.0), LayoutInsets(103, 0, 34, 0))
        assertFrame(layout.card, rect(16, 119, 361, 717))
        XCTAssertEqual(layout.textInsets, LayoutInsets(16, 16, 66, 16))
    }
}
