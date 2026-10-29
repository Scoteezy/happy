package com.slopus.happy.layout.stories

import com.slopus.happy.layout.Point
import com.slopus.happy.layout.rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/StoriesHeaderLayoutTests.swift.

/**
 * Exact places for home's stories in the header, after Telegram's
 * `StoryPeerListComponent` and `ChatListNavigationBar`: a 390 wide header
 * with a 70 × 20 title centred at 73, between a logo ending at 60 and a
 * pill starting at 284.
 */
class StoriesHeaderLayoutTest {
    private fun layout(state: StoriesHeaderLayout.State, count: Int = 3, start: Int = 0, scroll: Double = 0.0, width: Double = 390.0) =
        StoriesHeaderLayout(StoriesHeaderLayout.Input(width, rect(160, 63, 70, 20), 60.0, 284.0, count, start, scroll, state))

    private fun target(unlocked: Boolean, pull: Double) = StoriesHeaderLayout.State.target(unlocked, pull)

    private fun assertPoint(actual: Point, x: Number, y: Number) {
        assertEquals("x", x.toDouble(), actual.x, 0.001)
        assertEquals("y", y.toDouble(), actual.y, 0.001)
    }

    /**
     * Open at rest: three circles spread about the middle 66 under the
     * title's middle, full size, the title centred alone.
     */
    @Test fun openAtRest() {
        val open = layout(target(true, 96.0))
        assertEquals(rect(0, 97, 390, 98), open.row)
        assertEquals(listOf(rect(59, 12, 60, 77), rect(165, 12, 60, 77), rect(271, 12, 60, 77)), open.rowItems)
        assertPoint(open.items[0].center, 89, 139)
        assertPoint(open.items[1].center, 195, 139)
        assertPoint(open.items[2].center, 301, 139)
        assertEquals(listOf(1.0, 1.0, 1.0), open.items.map { it.scale })
        assertEquals(listOf(1.0, 1.0, 1.0), open.items.map { it.alpha })
        assertEquals(listOf(null, null, null), open.items.map { it.neighbor })
        assertEquals(160.0, open.titleX)
        assertNull(open.button)
        // The first is drawn over the rest.
        assertTrue(open.items[0].zPosition > open.items[1].zPosition)
    }

    /**
     * Closed: 24 pt avatars 14 apart before the title, the group centred
     * as far as the pill allows, each cut around the one before it.
     */
    @Test fun closedIntoTheTitle() {
        val closed = layout(target(false, 0.0))
        assertPoint(closed.items[0].center, 142, 73)
        assertPoint(closed.items[1].center, 156, 73)
        assertPoint(closed.items[2].center, 170, 73)
        for (item in closed.items) assertEquals(24.0 / 52, item.scale, 0.0001)
        assertEquals(190.0, closed.titleX)
        assertEquals(rect(60, 51, 224, 44), closed.button)
        assertEquals(Point(157.0, 103.0), closed.tooltipTip)
        assertNull(closed.items[0].neighbor)
        assertPoint(closed.items[1].neighbor!!, -14, 0)
        assertPoint(closed.items[2].neighbor!!, -14, 0)
        assertEquals(3, closed.collapsedCount)
    }

    /** Only three close; the rest fade into the third. */
    @Test fun onlyThreeClose() {
        val closed = layout(target(false, 0.0), count = 5)
        assertEquals(listOf(23.0, 94.0, 165.0, 236.0, 307.0), closed.rowItems.map { it.minX })
        assertPoint(closed.items[3].center, 170, 73)
        assertEquals(0.0, closed.items[3].alpha, 0.0)
        assertFalse(closed.items[4].collapses)
        assertEquals(190.0, closed.titleX)
    }

    /**
     * Pulled half way while closed: the avatars grow toward 28 and lean
     * out right and down, the title 6 to the right.
     */
    @Test fun pulledWhileClosed() {
        val pulled = layout(target(false, 48.0))
        assertPoint(pulled.items[0].center, 141, 75.5)
        assertPoint(pulled.items[1].center, 160, 80.5)
        assertPoint(pulled.items[2].center, 179, 85.5)
        assertEquals(0.5, pulled.items[0].scale, 0.0001)
        assertEquals(196.0, pulled.titleX)
        assertPoint(pulled.items[1].neighbor!!, -19, -5)
    }

    /**
     * Half open: half way from the title to the row, the avatars closing
     * up as they go, the title half way back to the middle.
     */
    @Test fun halfOpen() {
        val half = layout(target(true, 48.0))
        assertPoint(half.items[0].center, 115.5, 106)
        assertPoint(half.items[1].center, 172, 106)
        assertPoint(half.items[2].center, 228.5, 106)
        assertEquals((24.0 / 52 + 1) / 2, half.items[0].scale, 0.0001)
        assertEquals(175.0, half.titleX)
        assertNull(half.items[1].neighbor)
        assertNull(half.button)
    }

    /** Past the open rest the circles follow the list down and grow. */
    @Test fun pulledPastOpen() {
        val over = layout(target(true, 144.0))
        assertPoint(over.items[0].center, 89, 159.75)
        assertEquals(1.05, over.items[0].scale, 0.0001)
    }

    /** The person's own circle, first, stays out of the title. */
    @Test fun myOwnStaysOutOfTheTitle() {
        val closed = layout(target(false, 0.0), count = 3, start = 1)
        assertFalse(closed.items[0].collapses)
        assertEquals(0.0, closed.items[0].alpha, 0.0)
        assertEquals(2, closed.collapsedCount)
        // Two avatars: 38 wide, then 8, then the title.
        assertEquals(183.0, closed.titleX)
        val alone = layout(target(false, 0.0), count = 1, start = 1)
        assertNull(alone.titleX)
        assertNull(alone.button)
    }

    /** Scrolled sideways, what closes is what the row shows from its left. */
    @Test fun scrolledRowClosesWhatShows() {
        val closed = layout(target(false, 0.0), count = 10, scroll = 150.0)
        // 74 apart from 12: the third starts at 160, the first to show.
        assertEquals((12 + 600 + 9 * 14 + 12).toDouble(), closed.contentWidth, 0.0)
        assertFalse(closed.items[1].collapses)
        assertTrue(closed.items[2].collapses)
        assertTrue(closed.items[4].collapses)
        assertFalse(closed.items[5].collapses)
        assertPoint(closed.items[2].center, 142, 73)
    }

    /**
     * Scrolled past the person's own, or to the row's end, three still
     * close: the person's never, the last three at most.
     */
    @Test fun whatClosesAfterScrolling() {
        val past = layout(target(false, 0.0), count = 10, start = 1, scroll = 150.0)
        assertEquals(listOf(2, 3, 4), past.items.indices.filter { past.items[it].collapses })
        val mine = layout(target(false, 0.0), count = 10, start = 1)
        assertEquals(listOf(1, 2, 3), mine.items.indices.filter { mine.items[it].collapses })
        val end = layout(target(false, 0.0), count = 10, scroll = 600.0)
        assertEquals(listOf(7, 8, 9), end.items.indices.filter { end.items[it].collapses })
        assertPoint(end.items[7].center, 142, 73)
        assertNull(end.items[7].neighbor)
        assertNotNull(end.items[8].neighbor)
    }

    /** A long title balances in the band between the buttons. */
    @Test fun aLongTitleBalancesBetweenTheButtons() {
        assertEquals(130.0, StoriesHeaderLayout.contentOrigin(390.0, 130.0, 60.0, 284.0), 0.0)
        // Clear of both by 40 or more: centred.
        assertEquals(145.0, StoriesHeaderLayout.contentOrigin(390.0, 100.0, 60.0, 284.0), 0.0)
        // Touching the right end: in the band's middle, 4 in from each end;
        // 10 from it, half way there on the smooth step.
        assertEquals((64.0 + 280 - 170) / 2, StoriesHeaderLayout.contentOrigin(390.0, 170.0, 60.0, 284.0), 0.0001)
        assertEquals(108.5, StoriesHeaderLayout.contentOrigin(390.0, 150.0, 60.0, 284.0), 0.0001)
    }

    /** Opens past 65, closes under 61, keeps what it was between. */
    @Test fun opensAndClosesWithRoomBetween() {
        assertTrue(StoriesHeaderLayout.unlocked(false, 65.0))
        assertFalse(StoriesHeaderLayout.unlocked(false, 64.0))
        assertTrue(StoriesHeaderLayout.unlocked(true, 62.0))
        assertFalse(StoriesHeaderLayout.unlocked(true, 61.0))
        assertEquals(StoriesHeaderLayout.State(0.0, 0.5, 1.0), target(true, 48.0))
        assertEquals(StoriesHeaderLayout.State(0.0, 0.0, 0.0), target(false, -20.0))
    }

    @Test fun ringsKeepTheirWidthWhenSmall() {
        assertEquals(2.33, StoriesHeaderLayout.ringWidth(2.33, 1.0), 0.0)
        assertEquals(4.3, StoriesHeaderLayout.ringWidth(2.33, 0.0), 0.0)
        assertEquals(2.815, StoriesHeaderLayout.ringWidth(1.33, 0.5), 0.0001)
    }

    /**
     * The change between open and closed runs on the keyboard's spring,
     * 0.35 s opening and 0.25 s closing, from what was drawn.
     */
    @Test fun motion() {
        val from = StoriesHeaderLayout.State(0.0, 0.0, 0.0)
        val opening = StoriesHeaderMotion(from, 10.0, opening = true)
        assertEquals(0.35, opening.duration, 0.0)
        assertEquals(0.25, StoriesHeaderMotion(from, 10.0, opening = false).duration, 0.0)
        assertEquals(0.0, opening.progress(10.0), 0.0)
        assertEquals(1.0, opening.progress(10.35), 0.0)
        // Quick at first, settling slowly.
        assertTrue(opening.progress(10.0875) > 0.6)
        val target = StoriesHeaderLayout.State(0.0, 1.0, 1.0)
        assertEquals(target, opening.state(target, 11.0))
        assertFalse(opening.isFinished(10.2))
        assertTrue(opening.isFinished(10.35))
        assertEquals(0.966, StoriesHeaderMotion.curve(0.5), 0.001)
    }

    /**
     * Let go of part way into the row's room, the list rests at the row as
     * it will be: a glide past 61 up has closed it, so a short flick up puts
     * it away and a nudge leaves it open; closed, it rests closed.
     */
    @Test fun restingWhereTheRowWillBe() {
        assertEquals(96.0, StoriesHeaderLayout.resting(true, 80.0), 0.0)
        assertEquals(96.0, StoriesHeaderLayout.resting(true, 62.0), 0.0)
        assertEquals(0.0, StoriesHeaderLayout.resting(true, 61.0), 0.0)
        assertEquals(0.0, StoriesHeaderLayout.resting(true, 20.0), 0.0)
        assertEquals(0.0, StoriesHeaderLayout.resting(false, 80.0), 0.0)
        // Outside the room, the glide is left alone.
        assertEquals(-300.0, StoriesHeaderLayout.resting(true, -300.0), 0.0)
        assertEquals(96.0, StoriesHeaderLayout.resting(true, 96.0), 0.0)
        assertEquals(0.0, StoriesHeaderLayout.resting(false, 0.0), 0.0)
    }
}
