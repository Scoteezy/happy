package com.slopus.happy.layout.chat

import com.slopus.happy.layout.Size
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.assertFrame
import com.slopus.happy.layout.fixed
import com.slopus.happy.layout.rect
import com.slopus.happy.layout.text
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/ChatLayoutTests.swift (WorktreeTabStripLayoutTests).

class WorktreeTabStripLayoutTest {
    private val unbounded = Double.MAX_VALUE

    @Test fun testTabsSitInACapsuleThatEndsInPlus() {
        val layout = WorktreeTabStripLayout(
            width = 393.0, maxWidth = unbounded, border = 1.0, hairline = 0.5,
            tabs = listOf(
                WorktreeTabStripLayout.Tab(text("Main", lineHeight = 18, maxLines = 1), dot = false),
                WorktreeTabStripLayout.Tab(text("Fix strip", lineHeight = 18, maxLines = 1), dot = true),
            ),
            showsAdd = true,
        )
        XCTAssertEqual(layout.size, Size(393, 56))
        assertFrame(layout.pill, rect(16, 10, 361, 40))
        assertFrame(layout.inner, rect(1, 1, 359, 38))
        assertFrame(layout.scroller, rect(0, 0, 318.5, 38))
        assertFrame(layout.divider, rect(318.5, 9, 0.5, 20))
        assertFrame(layout.add, rect(319, 0, 40, 38))
        // 32 tall, 10 in, 2 apart, 4 from the capsule's ends.
        assertFrame(layout.tabs[0].frame, rect(4, 3, 52, 32))
        assertFrame(layout.tabs[0].label, rect(10, 7, 32, 18))
        XCTAssertNil(layout.tabs[0].dot)
        assertFrame(layout.tabs[1].frame, rect(58, 3, 105, 32))
        assertFrame(layout.tabs[1].dot, rect(88, 12.5, 7, 7))
        XCTAssertEqual(layout.contentWidth, 167.0)
    }

    @Test fun testATabGrowsToTwoHundred() {
        val layout = WorktreeTabStripLayout(
            width = 393.0, maxWidth = unbounded, border = 1.0, hairline = 0.5,
            tabs = listOf(WorktreeTabStripLayout.Tab(text("a".repeat(30), lineHeight = 18, maxLines = 1), dot = false)),
            showsAdd = false,
        )
        assertFrame(layout.tabs[0].frame, rect(4, 3, 200, 32))
        assertFrame(layout.tabs[0].label, rect(10, 7, 180, 18))
        // No `+`: the tabs have the whole capsule.
        assertFrame(layout.scroller, rect(0, 0, 359, 38))
        XCTAssertNil(layout.add)
    }

    @Test fun testANameAtALargeTextSizeIsCentredOnATabThatKeepsItsHeight() {
        // 14/18 at the largest text size: the names grow, the strip's heights don't, as RN scales text and nothing else.
        val layout = WorktreeTabStripLayout(
            width = 393.0, maxWidth = unbounded, border = 1.0, hairline = 0.5,
            tabs = listOf(
                WorktreeTabStripLayout.Tab(fixed(150, 64.5), dot = false),
                WorktreeTabStripLayout.Tab(fixed(400, 64.5), dot = true),
            ),
            showsAdd = true,
        )
        XCTAssertEqual(layout.size, Size(393, 56))
        assertFrame(layout.pill, rect(16, 10, 361, 40))
        assertFrame(layout.tabs[0].frame, rect(4, 3, 170, 32))
        assertFrame(layout.tabs[0].label, rect(10, -16.25, 150, 64.5))
        // Capped at 200, the dot after what is left of the name.
        assertFrame(layout.tabs[1].frame, rect(176, 3, 200, 32))
        assertFrame(layout.tabs[1].label, rect(10, -16.25, 167, 64.5))
        assertFrame(layout.tabs[1].dot, rect(183, 12.5, 7, 7))
        XCTAssertEqual(layout.contentWidth, 380.0)
    }

    @Test fun testTheCapsuleKeepsToTheHeadersWidth() {
        val layout = WorktreeTabStripLayout(width = 1000.0, maxWidth = 800.0, border = 0.5, hairline = 0.5, tabs = emptyList(), showsAdd = true)
        assertFrame(layout.pill, rect(116, 10, 768, 40))
        assertFrame(layout.inner, rect(0.5, 0.5, 767, 39))
    }

    @Test fun testOnlyATabOutOfViewIsScrolledTo() {
        val l = WorktreeTabStripLayout
        XCTAssertNil(l.revealOffset(rect(100, 3, 80, 32), offset = 0.0, viewport = 300.0, contentWidth = 600.0))
        // The smallest scroll that shows it, 8 to spare.
        XCTAssertEqual(l.revealOffset(rect(300, 3, 100, 32), offset = 0.0, viewport = 300.0, contentWidth = 600.0), 108.0)
        XCTAssertEqual(l.revealOffset(rect(10, 3, 100, 32), offset = 50.0, viewport = 300.0, contentWidth = 600.0), 2.0)
        // Never past the content's end.
        XCTAssertEqual(l.revealOffset(rect(480, 3, 100, 32), offset = 0.0, viewport = 300.0, contentWidth = 584.0), 284.0)
    }
}
