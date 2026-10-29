package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.*
import com.slopus.happy.layout.chat.*
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.


// Exact frames for the Stem Cells screens around the sidebar: the banner,
// the empty state, search, a todo list and a document.
class StemCellsScreenLayoutTest {
    @Test fun homeStacksTheBannerOverTheList() {
        val layout = StemCellsHomeLayout(size = Size(390, 844), contentTop = 120.0, chromeTop = 110.0, listBottom = 90.0, bannerHeight = 90.0)
        XCTAssertEqual(layout.banner, rect(0, 120, 390, 90))
        XCTAssertEqual(layout.listInsets, LayoutInsets(top = 210.0, left = 0.0, bottom = 90.0, right = 0.0))
        XCTAssertEqual(layout.indicatorInsets, LayoutInsets(top = 110.0, left = 0.0, bottom = 90.0, right = 0.0))
        XCTAssertEqual(layout.empty, rect(0, 120, 390, 634))
    }

    @Test fun theBannerHoldsItsButtonAtTheRight() {
        val off = StemCellsBannerLayout(width = 390.0, title = text("Prioritizing is off", lineHeight = 18.0), detail = text("Turn it on to name and place new work.", lineHeight = 17.0), button = Size(80, 30))
        XCTAssertEqual(off.card, rect(12, 0, 366, 82))
        // 366 less 28 of padding and the button with its 12.
        XCTAssertEqual(off.title, rect(14, 14, 246, 18))
        XCTAssertEqual(off.detail, rect(14, 34, 246, 34))
        XCTAssertEqual(off.button, rect(272, 26, 80, 30))
        XCTAssertEqual(off.size, Size(390, 90))
        // A refusal: the reason alone, across the card.
        val notice = StemCellsBannerLayout(width = 390.0, title = text("The computer did not answer.", lineHeight = 18.0), detail = null, button = null)
        XCTAssertEqual(notice.title, rect(14, 14, 338, 18))
        XCTAssertNil(notice.detail)
        XCTAssertNil(notice.button)
        XCTAssertEqual(notice.size.height, 54)
    }

    @Test fun theEmptyStateCentresItsThree() {
        val layout = StemCellsEmptyLayout(size = Size(390, 600), label = text("Pair a computer to see its Stem Cells.", lineHeight = 20.0), button = Size(180, 44))
        XCTAssertEqual(layout.mark, rect(163, 214, 64, 64))
        XCTAssertEqual(layout.label, rect(24, 298, 342, 20))
        XCTAssertEqual(layout.button, rect(105, 342, 180, 44))
        // Too old: no button, the line wraps at 272 on a narrow phone.
        val old = StemCellsEmptyLayout(size = Size(320, 500), label = text("a".repeat(40), lineHeight = 20.0), button = null)
        XCTAssertEqual(old.label, rect(24, 272, 272, 40))
        XCTAssertNil(old.button)
    }

    @Test fun searchSitsUnderTheBar() {
        val layout = StemCellsSearchLayout(width = 390.0, safeArea = LayoutInsets(top = 103.0, left = 0.0, bottom = 34.0, right = 0.0), barHeight = 56.0)
        XCTAssertEqual(layout.bar, rect(4, 103, 382, 56))
        XCTAssertEqual(layout.listInsets, LayoutInsets(top = 159.0, left = 0.0, bottom = 34.0, right = 0.0))
    }

    @Test fun todosStackUnderTheirField() {
        val layout = StemCellsTodoLayout(width = 390.0, todos = listOf(text("Buy oranges", lineHeight = 20.0), text("a".repeat(60), lineHeight = 20.0)), empty = text("Nothing here yet.", lineHeight = 20.0))
        XCTAssertEqual(layout.field, rect(16, 12, 358, 44))
        XCTAssertEqual(layout.rows.map { it.frame }, listOf(rect(0, 64, 390, 44), rect(0, 108, 390, 60)))
        XCTAssertEqual(layout.rows[0].box, rect(16, 11, 22, 22))
        XCTAssertEqual(layout.rows[0].text, rect(48, 12, 326, 20))
        // 326 wide: 40 to a line, so two lines and a taller row.
        XCTAssertEqual(layout.rows[1].text, rect(48, 10, 326, 40))
        XCTAssertNil(layout.empty)
        XCTAssertEqual(layout.contentHeight, 184)
        val none = StemCellsTodoLayout(width = 390.0, todos = emptyList(), empty = text("Nothing here yet.", lineHeight = 20.0))
        XCTAssertEqual(none.empty, rect(16, 88, 358, 20))
        XCTAssertEqual(none.contentHeight, 124)
    }

    @Test fun aBodyScrollsUnderTheBar() {
        val layout = StemCellsSurfaceBodyLayout(width = 390.0, body = block(height = 300.0))
        XCTAssertEqual(layout.body, rect(0, 8, 390, 300))
        XCTAssertEqual(layout.contentHeight, 324)
        XCTAssertEqual(StemCellsSurfaceBodyLayout(width = 390.0, body = block(height = 20.0), inset = 16.0).body, rect(16, 8, 358, 20))
    }

    @Test fun chatTitleIsItsTextWideAndAllOfItWhileEdited() {
        val max = StemCellChatTitleLayout.maxWidth(barWidth = 393.0)
        XCTAssertEqual(max, 265)
        val shown = StemCellChatTitleLayout(textWidth = 41.3, maxWidth = max, editing = false)
        XCTAssertEqual(shown.size, Size(42, 44))
        XCTAssertEqual(shown.text, rect(0, 0, 42, 44))
        val long = StemCellChatTitleLayout(textWidth = 400.0, maxWidth = max, editing = false)
        XCTAssertEqual(long.text, rect(0, 0, 265, 44))
        val editing = StemCellChatTitleLayout(textWidth = 41.3, maxWidth = max, editing = true)
        XCTAssertEqual(editing.text, rect(0, 0, 265, 44))
        // Before the bar is measured, the name is its own width.
        val unmeasured = StemCellChatTitleLayout(textWidth = 41.3, maxWidth = StemCellChatTitleLayout.maxWidth(barWidth = 0.0), editing = false)
        XCTAssertEqual(unmeasured.text, rect(0, 0, 42, 44))
    }

    @Test fun theDockIsTheNewWorkComposerWithTheKeyboardDown() {
        // iPhone 17 Pro: 402 × 874, 34 under the safe area; a 100-tall card.
        val layout = StemCellsDockLayout(size = Size(402, 874), safeArea = LayoutInsets(top = 62.0, bottom = 34.0)) { width ->
            XCTAssertEqual(width, 386)
            100.0
        }
        XCTAssertEqual(layout.card, Rect(x = 8.0, y = 732.0, width = 386.0, height = 100.0))
        XCTAssertEqual(layout.scrim, Rect(x = 0.0, y = 782.0, width = 402.0, height = 92.0))
        // From the card's top, and 8 of room over it.
        XCTAssertEqual(layout.listBottom, 150)
        // The very frame the conversation gives its composer with the keyboard down.
        val chat = StemCellChatScreenLayout(size = Size(402, 874), safeArea = LayoutInsets(top = 62.0, bottom = 34.0), keyboardTop = 874.0) { _ -> 100.0 }
        XCTAssertEqual(layout.card, chat.card)
    }
}
