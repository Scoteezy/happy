package com.slopus.happy.layout

import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.

class HomeHeaderLayoutTest {
    private val hairline: Double = 1.0 / 3

    fun header(status: String? = null, width: Double = 393.0): HomeHeaderLayout {
        return HomeHeaderLayout(HomeHeaderLayout.Input(
            width = width,
            safeTop = 59.0,
            hairline = hairline,
            title = text("Sessions", lineHeight = 20.0, maxLines = 1),
            status = status?.let { text(it, lineHeight = 16.0, maxLines = 1) }
        ))
    }

    @Test fun testControlsSitInTheFiftyTwoPointRowUnderTheStatusBar() {
        val layout = header()
        assertSize(layout.size, Size(393, 111))
        assertFrame(layout.backdrop, rect(0, 0, 393, 119))
        // A 44pt circle, its 19pt mark centred.
        assertFrame(layout.logoControl, rect(16, 63, 44, 44))
        assertFrame(layout.logo, rect(28.5, 75.5, 19, 19))
        // The pill's rim sits outside the two 44pt buttons and the hairline.
        assertFrame(layout.actions, rect(393 - 16 - (90 + hairline), 62, 90 + hairline, 46))
        assertFrame(layout.filterButton, rect(287 - hairline + 1, 63, 44, 44))
        assertFrame(layout.actionDivider, rect(331 - hairline + 1, 74, hairline, 22))
        assertFrame(layout.settingsButton, rect(332, 63, 44, 44))
        XCTAssertEqual(layout.settingsButton.maxX + 1, 393 - 16, accuracy = 0.001)
    }

    /// One button alone: the pill hugs it at the right edge.
    @Test fun testASingleActionPill() {
        val input = HomeHeaderLayout.Input(width = 393.0, safeTop = 59.0, hairline = hairline, title = text("Mac", lineHeight = 20.0, maxLines = 1), status = null)
        input.singleAction = true
        val layout = HomeHeaderLayout(input)
        assertFrame(layout.actions, rect(331, 62, 46, 46))
        assertFrame(layout.filterButton, rect(332, 63, 44, 44))
        XCTAssertEqual(layout.actionDivider.width, 0)
        XCTAssertEqual(layout.settingsButton.width, 0)
        XCTAssertEqual(layout.title.midX, 393.0 / 2, accuracy = 0.001)
    }

    @Test fun testTheTitleIsCentredOnTheScreenNotBetweenTheControls() {
        val layout = header()
        assertFrame(layout.title, rect(164.5, 75, 64, 20))
        XCTAssertEqual(layout.title.midX, 393.0 / 2, accuracy = 0.001)
        XCTAssertNil(layout.status)
        XCTAssertNil(layout.statusDot)
    }

    @Test fun testTheStatusHangsUnderTheTitleWithoutMovingIt() {
        val layout = header(status = "connecting")
        assertFrame(layout.title, rect(164.5, 75, 64, 20))
        // Dot 6, gap 4, then 80pt of text, centred; 2pt into the title.
        assertFrame(layout.statusDot, rect(151.5, 98, 6, 6))
        assertFrame(layout.status, rect(161.5, 93, 80, 16))
    }

    @Test fun testALongTitleStopsAtTheSixtyFourPointInsets() {
        val layout = HomeHeaderLayout(HomeHeaderLayout.Input(
            width = 393.0,
            safeTop = 59.0,
            hairline = hairline,
            title = text("x".repeat(60), lineHeight = 20.0, maxLines = 1),
            status = null
        ))
        assertFrame(layout.title, rect(64, 75, 265, 20))
    }

    @Test fun testContentInsetsFollowMainView() {
        val insets = HomeContentInsets(safeArea = LayoutInsets(top = 59.0, bottom = 34.0), dock = 108.0)
        XCTAssertEqual(insets.chromeTop, 111)
        XCTAssertEqual(insets.contentTop, 123)
        XCTAssertEqual(insets.listBottom, 142)
        XCTAssertEqual(insets.emptyStateSafeArea, LayoutInsets(top = 123.0, bottom = 108.0))
        XCTAssertEqual(
            insets.additionalSafeArea(over = LayoutInsets(top = 59.0, bottom = 34.0)),
            LayoutInsets(top = 64.0, bottom = 74.0)
        )
    }

    @Test fun testTheScreenFloatsTheHeaderAndItsScrimOverFullHeightContent() {
        val layout = HomeScreenLayout(size = phone, safeArea = phoneFullScreen, dock = 108.0)
        assertFrame(layout.content, rect(0, 0, 393, 852))
        assertFrame(layout.header, rect(0, 0, 393, 119))
        XCTAssertEqual(layout.insets.contentTop, 123)
    }

    @Test fun testTheScrimHoldsItsPeakThenFeathersOverTheLastThirtySixPoints() {
        val stops = HeaderScrim.stops(height = 119.0, peak = HeaderScrim.strongPeakLight)
        XCTAssertEqual(stops.size, 25)
        XCTAssertEqual(stops[0].alpha, 0.76)
        XCTAssertEqual(stops[18].alpha, 0.7192, accuracy = 0.00001)
        XCTAssertEqual(stops[20].alpha, 0.5604, accuracy = 0.00001)
        XCTAssertEqual(stops[24].alpha, 0)
        XCTAssertEqual(stops[24].location, 1)
    }
}
