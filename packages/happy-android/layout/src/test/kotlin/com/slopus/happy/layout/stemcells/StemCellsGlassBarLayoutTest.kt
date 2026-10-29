package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.*
import com.slopus.happy.layout.chat.*
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.


// The glass bar of settings, the link sheet and the pages pushed from
// them, on a 390-wide phone: its circle is a conversation's back, its
// actions are home's capsule, its title is a conversation's.
class StemCellsGlassBarLayoutTest {
    /// A conversation's capsule runs to the edge; the bar's hugs its title.
    @Test fun backIsTheConversationsBackAndTheTitleCentredInACapsule() {
        val bar = StemCellsGlassBarLayout(width = 390.0, safeTop = 47.0, hairline = 1.0, hasLeading = true, actions = 0, titleWidth = 99.4)
        val chat = StemCellChatHeaderLayout(width = 390.0, safeTop = 47.0, titleWidth = 99.4, statusWidth = null)
        XCTAssertEqual(bar.leading, Rect(x = 16.0, y = 51.0, width = 44.0, height = 44.0))
        XCTAssertEqual(bar.leading, chat.back)
        XCTAssertEqual(bar.pill, Rect(x = 129.0, y = 51.0, width = 132.0, height = 44.0))
        XCTAssertEqual(bar.pill?.minY, chat.pill.minY)
        XCTAssertEqual(bar.title, Rect(x = 145.0, y = 62.0, width = 100.0, height = 22.0))
        XCTAssertEqual(bar.title?.minY, chat.title.minY)
        XCTAssertEqual(bar.backdrop, Rect(x = 0.0, y = 0.0, width = 390.0, height = 107.0))
        XCTAssertEqual(bar.backdrop, chat.backdrop)
        XCTAssertEqual(bar.contentTop, 99)
        XCTAssertEqual(bar.contentTop, chat.listTop)
        XCTAssertNil(bar.actions)
        XCTAssertNil(bar.searchPill)
    }

    @Test fun twoActionsShareHomesCapsuleSplitByAHairline() {
        val bar = StemCellsGlassBarLayout(width = 390.0, safeTop = 47.0, hairline = 1.0, hasLeading = true, actions = 2, titleWidth = null)
        XCTAssertEqual(bar.actions, Rect(x = 283.0, y = 50.0, width = 91.0, height = 46.0))
        XCTAssertEqual(bar.buttons, listOf(Rect(x = 284.0, y = 51.0, width = 44.0, height = 44.0), Rect(x = 329.0, y = 51.0, width = 44.0, height = 44.0)))
        XCTAssertEqual(bar.dividers, listOf(Rect(x = 328.0, y = 62.0, width = 1.0, height = 22.0)))
        XCTAssertNil(bar.pill)
        XCTAssertNil(bar.title)
        // Home's own capsule, at a device's real hairline.
        val third: Double = 1.0 / 3
        val glass = StemCellsGlassBarLayout(width = 390.0, safeTop = 47.0, hairline = third, hasLeading = true, actions = 2, titleWidth = null)
        val home = HomeHeaderLayout(HomeHeaderLayout.Input(width = 390.0, safeTop = 47.0, hairline = third, title = fixed(80, 20), status = null))
        XCTAssertEqual(glass.actions, home.actions)
        XCTAssertEqual(glass.buttons, listOf(home.filterButton, home.settingsButton))
        XCTAssertEqual(glass.dividers, listOf(home.actionDivider))
        XCTAssertEqual(glass.leading, home.logoControl)
    }

    @Test fun besideActionsTheTitleKeepsAsClearOfBothSidesAndStaysCentred() {
        val short = StemCellsGlassBarLayout(width = 390.0, safeTop = 47.0, hairline = 1.0, hasLeading = true, actions = 2, titleWidth = 100.0)
        XCTAssertEqual(short.titleMaxWidth, 128)
        XCTAssertEqual(short.pill, Rect(x = 129.0, y = 51.0, width = 132.0, height = 44.0))
        val long = StemCellsGlassBarLayout(width = 390.0, safeTop = 47.0, hairline = 1.0, hasLeading = true, actions = 2, titleWidth = 300.0)
        XCTAssertEqual(long.pill, Rect(x = 115.0, y = 51.0, width = 160.0, height = 44.0))
        XCTAssertEqual(long.title, Rect(x = 131.0, y = 62.0, width = 128.0, height = 22.0))
        // One action: the same 8 to its left as to the circle's right.
        val one = StemCellsGlassBarLayout(width = 390.0, safeTop = 47.0, hairline = 1.0, hasLeading = true, actions = 1, titleWidth = 300.0)
        XCTAssertEqual(one.actions, Rect(x = 328.0, y = 50.0, width = 46.0, height = 46.0))
        XCTAssertEqual(one.buttons, listOf(Rect(x = 329.0, y = 51.0, width = 44.0, height = 44.0)))
        XCTAssertEqual(one.dividers, emptyList<Rect>())
        XCTAssertEqual(one.pill, Rect(x = 70.0, y = 51.0, width = 250.0, height = 44.0))
    }

    @Test fun withNothingAtTheLeftTheTitleIsStillCentred() {
        val bar = StemCellsGlassBarLayout(width = 390.0, safeTop = 0.0, hairline = 1.0, hasLeading = false, actions = 0, titleWidth = 100.0)
        XCTAssertNil(bar.leading)
        XCTAssertEqual(bar.pill, Rect(x = 129.0, y = 4.0, width = 132.0, height = 44.0))
        XCTAssertEqual(bar.contentTop, 52)
    }

    @Test fun searchingAFieldCapsuleTakesTheRowBesideACircleThatEndsIt() {
        val bar = StemCellsGlassBarLayout(width = 390.0, safeTop = 47.0, hairline = 1.0, hasLeading = true, actions = 2, titleWidth = 100.0, searching = true)
        XCTAssertNil(bar.leading)
        XCTAssertNil(bar.actions)
        XCTAssertNil(bar.pill)
        XCTAssertEqual(bar.cancel, Rect(x = 330.0, y = 51.0, width = 44.0, height = 44.0))
        XCTAssertEqual(bar.searchPill, Rect(x = 16.0, y = 51.0, width = 306.0, height = 44.0))
        XCTAssertEqual(bar.field, Rect(x = 28.0, y = 51.0, width = 282.0, height = 44.0))
        XCTAssertEqual(bar.contentTop, 99)
    }
}
