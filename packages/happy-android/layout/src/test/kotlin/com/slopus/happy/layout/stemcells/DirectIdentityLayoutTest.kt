package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.*
import com.slopus.happy.layout.chat.*
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.


class DirectIdentityLayoutTest {
    @Test fun exactMeasuredFrames() {
        val layout = DirectIdentityLayout(width = 320.0, title = Measurable { _ -> Size(320, 30) }, body = Measurable { _ -> Size(320, 90) }, emoji = Measurable { _ -> Size(320, 60) })
        XCTAssertEqual(layout.image, Rect(x = 116.0, y = 0.0, width = 88.0, height = 88.0))
        XCTAssertEqual(layout.title, Rect(x = 0.0, y = 108.0, width = 320.0, height = 30.0))
        XCTAssertEqual(layout.body, Rect(x = 0.0, y = 150.0, width = 320.0, height = 90.0))
        XCTAssertEqual(layout.emoji, Rect(x = 0.0, y = 260.0, width = 320.0, height = 60.0))
        XCTAssertEqual(layout.size, Size(320, 332))
    }
    @Test fun singleLogoAndMultipleAccountAvatar() {
        var input = HomeHeaderLayout.Input(width = 390.0, safeTop = 59.0, hairline = 0.5, title = Measurable { _ -> Size(90, 20) }, status = null)
        XCTAssertEqual(HomeHeaderLayout(input).logo.size, Size(19, 19))
        input.showsAccountAvatar = true
        val avatar = HomeHeaderLayout(input)
        XCTAssertEqual(avatar.logo, Rect(x = 22.0, y = 69.0, width = 32.0, height = 32.0))
        XCTAssertEqual(avatar.logo.midX, avatar.logoControl.midX)
    }
}
