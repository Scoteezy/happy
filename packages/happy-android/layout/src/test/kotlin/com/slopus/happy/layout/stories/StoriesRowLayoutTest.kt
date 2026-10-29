package com.slopus.happy.layout.stories

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.stemcells.StemCellsHomeLayout
import com.slopus.happy.layout.assertClose
import com.slopus.happy.layout.rect
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

// Ported from happy-ios Tests/HappyLayoutTests/StoriesRowLayoutTests.swift.

/**
 * Exact frames for the stories row, after Telegram's
 * `StoryPeerListComponent` and `StoryPeerListItemComponent`.
 */
class StoriesRowLayoutTest {
    @Test fun oneCircle() {
        val item = StoriesRowLayout.item
        assertEquals(rect(4, 4, 52, 52), item.avatar)
        // 30.66 out from the avatar's centre.
        assertEquals(-0.66, item.ring.minX, 0.0001)
        assertEquals(61.32, item.ring.width, 0.0001)
        // 2 under the ring's 68 pt square; a long name is held to 70.
        assertEquals(rect(10, 66, 40, 14), item.title(39.4, 13.2))
        assertEquals(rect(-5, 66, 70, 14), item.title(120.0, 13.2))
    }

    @Test fun circlesSit14Apart() {
        val layout = StoriesRowLayout(390.0, 3)
        assertEquals(listOf(rect(12, 12, 60, 77), rect(86, 12, 60, 77), rect(160, 12, 60, 77)), layout.items)
        assertEquals(Size(390, 98), layout.contentSize)
        assertClose(98, layout.size.height)
    }

    @Test fun aRowThatOverflowsScrolls() {
        val layout = StoriesRowLayout(390.0, 10)
        assertClose(14, layout.spacing)
        assertClose(12 + 600 + 9 * 14 + 12, layout.contentSize.width)
    }

    /**
     * The edge would show a 2.66 pt sliver of the sixth ring: the spacing
     * grows until the fifth is cut at its centre.
     */
    @Test fun theEdgeNeverShowsASliverOfARing() {
        val layout = StoriesRowLayout(384.0, 10)
        assertClose(25, layout.spacing)
        assertEquals(384.0, layout.items[4].midX, 9.0)
        assertClose(14, StoriesRowLayout(390.0, 2).spacing)
    }

    @Test fun ringSegments() {
        assertEquals(1, StoriesRowLayout.segments(1).size)
        val three = StoriesRowLayout.segments(3, 29.5)
        assertEquals(3, three.size)
        val gap = 4.0 / 29.5
        assertEquals(-PI / 2 + gap / 2, three[0].first, 1.0e-9)
        assertEquals(gap, three[1].first - three[0].second, 1.0e-9)
        assertEquals(-PI / 2, three[2].second + gap / 2 - 2 * PI, 1.0e-9)
        // Telegram draws at most 30.
        assertEquals(30, StoriesRowLayout.segments(80).size)
    }

    /** The open stories push the banner and the list down by their room. */
    @Test fun homeMakesRoomForTheOpenStories() {
        val layout = StemCellsHomeLayout(Size(390, 844), contentTop = 120.0, chromeTop = 110.0, listBottom = 90.0, bannerHeight = 40.0, storiesHeight = 96.0)
        assertEquals(rect(0, 216, 390, 40), layout.banner)
        assertEquals(LayoutInsets(256, 0, 90, 0), layout.listInsets)
        assertEquals(LayoutInsets(110, 0, 90, 0), layout.indicatorInsets)
        // Closed or off: home exactly as it was.
        val off = StemCellsHomeLayout(Size(390, 844), contentTop = 120.0, chromeTop = 110.0, listBottom = 90.0, bannerHeight = 0.0, storiesHeight = 0.0)
        assertEquals(StemCellsHomeLayout(Size(390, 844), contentTop = 120.0, chromeTop = 110.0, listBottom = 90.0, bannerHeight = 0.0), off)
        assertEquals(120.0, off.listInsets.top, 0.0)
    }
}
