package com.slopus.happy.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationBarLayoutTest {
    private val pixel = LayoutInsets(top = 24, bottom = 24)

    @Test fun backAndOneActionAroundACentredTitle() {
        val layout = NavigationBarLayout(411.0, pixel, 1, 1, Size(120, 20))
        assertSize(layout.size, Size(411, 80))
        assertFrame(layout.row, rect(0, 24, 411, 56))
        assertFrame(layout.leading[0], rect(4, 28, 48, 48))
        assertFrame(layout.trailing[0], rect(359, 28, 48, 48))
        assertFrame(layout.title, rect(145.5, 42, 120, 20))
    }

    @Test fun aLongTitleIsHeldClearOfTheButtons() {
        val layout = NavigationBarLayout(411.0, pixel, 1, 2, Size(400, 20))
        // Two trailing buttons: 4 + 96 + 4 gap on each side.
        assertFrame(layout.title, rect(104, 42, 203, 20))
        assertFrame(layout.trailing[0], rect(311, 28, 48, 48))
        assertFrame(layout.trailing[1], rect(359, 28, 48, 48))
    }

    @Test fun noButtons() {
        val layout = NavigationBarLayout(411.0, pixel, 0, 0, null)
        assertEquals(0, layout.leading.size)
        assertEquals(null, layout.title)
    }
}
