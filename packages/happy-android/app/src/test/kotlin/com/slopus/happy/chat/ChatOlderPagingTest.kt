package com.slopus.happy.chat

import com.slopus.happy.chat.ChatOlderPaging.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from happy-ios Tests/HappyCoreTests/ChatOlderPagingTests.swift (the folded-turn boundary
// test is left out: it builds iOS's own row types).

/** ChatList.tsx's and EmptyMessages.tsx's history paging, step by step. */
class ChatOlderPagingTest {
    private fun reading(
        laidOut: Boolean = true, messages: Boolean = true, growing: Boolean = false,
        near: Boolean = true, canGrow: Boolean = false, boundary: String? = "m1",
        hasMore: Boolean = true, loading: Boolean = false, active: Boolean = true,
    ) = ChatOlderPaging.Reading(laidOut, messages, growing, near, canGrow, boundary, hasMore, loading, active)

    @Test fun theStoreIsRenderedBeforeTheServerIsAsked() {
        val paging = ChatOlderPaging()
        assertEquals(Step.None, paging.check(reading(near = false)))
        assertEquals(Step.GrowWindow, paging.check(reading(canGrow = true)))
        assertEquals(Step.GrowWindow, paging.check(reading(canGrow = true, hasMore = false)))
        assertNull(paging.pending)
        assertEquals(Step.FetchOlder(1), paging.check(reading()))
        assertEquals(1, paging.pending)
    }

    @Test fun onePageAtATimeAndNoneWhileAnotherIsOnItsWay() {
        val paging = ChatOlderPaging()
        assertEquals("a background page", Step.None, paging.check(reading(loading = true)))
        assertEquals(Step.None, paging.check(reading(hasMore = false)))
        assertEquals(Step.None, paging.check(reading(active = false)))
        assertEquals(Step.FetchOlder(1), paging.check(reading()))
        assertEquals(Step.None, paging.check(reading()))
        assertEquals(Step.None, paging.check(reading()))
        assertEquals(ChatOlderPaging.Status.Loading, paging.status(hasMore = true, loading = true, windowReachesOldest = true))
    }

    @Test fun aPageThatShowsSomethingKeepsPagingAndOneThatMovedNothingRests() {
        val paging = ChatOlderPaging()
        assertEquals(Step.FetchOlder(1), paging.check(reading(boundary = "m1")))
        assertTrue(paging.settled(1, advanced = true))
        assertEquals(Step.GrowWindow, paging.check(reading(canGrow = true, boundary = "m1")))
        assertEquals(Step.FetchOlder(2), paging.check(reading(boundary = "m0")))
        assertEquals(0, paging.invisiblePages)

        assertFalse("the cursor did not move", paging.settled(2, advanced = false))
        assertNull(paging.baseline)
        assertEquals("the next scroll asks again", Step.FetchOlder(3), paging.check(reading(boundary = "m0")))
        assertEquals("a page that moved nothing is not judged", 0, paging.invisiblePages)
    }

    @Test fun fivePagesThatShowNothingNewStopForLoadMore() {
        val paging = ChatOlderPaging()
        for (request in 1..5) {
            assertEquals("page $request", Step.FetchOlder(request), paging.check(reading(boundary = "m1")))
            assertTrue(paging.settled(request, advanced = true))
        }
        assertEquals(Step.None, paging.check(reading(boundary = "m1")))
        assertEquals(5, paging.invisiblePages)
        assertEquals(ChatOlderPaging.Block.Limit, paging.blocked)
        assertEquals(ChatOlderPaging.Status.LoadMore, paging.status(hasMore = true, loading = false, windowReachesOldest = true))
        assertEquals(ChatOlderPaging.Status.Idle, paging.status(hasMore = false, loading = false, windowReachesOldest = true))
        assertEquals(Step.None, paging.check(reading(boundary = "m1")))

        paging.loadMore()
        assertEquals(Step.FetchOlder(6), paging.check(reading(boundary = "m1")))
        assertEquals(ChatOlderPaging.Status.Loading, paging.status(hasMore = true, loading = true, windowReachesOldest = true))
    }

    @Test fun aPageThatShowsSomethingStartsTheCountAgain() {
        val paging = ChatOlderPaging()
        for (request in 1..4) {
            assertEquals(Step.FetchOlder(request), paging.check(reading(boundary = "m1")))
            paging.settled(request, advanced = true)
        }
        assertEquals(Step.FetchOlder(5), paging.check(reading(boundary = "m1")))
        assertEquals(4, paging.invisiblePages)
        paging.settled(5, advanced = true)
        assertEquals(Step.FetchOlder(6), paging.check(reading(boundary = "m0")))
        assertEquals(0, paging.invisiblePages)
        assertNull(paging.blocked)
    }

    @Test fun aFailureStopsUntilRetry() {
        val paging = ChatOlderPaging()
        assertEquals(Step.FetchOlder(1), paging.check(reading()))
        paging.failed(1)
        assertEquals(ChatOlderPaging.Block.Error, paging.blocked)
        assertEquals(ChatOlderPaging.Status.Error, paging.status(hasMore = true, loading = false, windowReachesOldest = true))
        assertEquals(Step.None, paging.check(reading()))
        assertEquals(Step.None, paging.check(reading(boundary = "m0")))
        assertEquals(1, paging.pending)

        paging.retry()
        assertNull(paging.pending)
        assertEquals(ChatOlderPaging.Status.Idle, paging.status(hasMore = true, loading = false, windowReachesOldest = true))
        assertEquals(Step.FetchOlder(2), paging.check(reading()))
    }

    @Test fun aSettlementForAFetchRetriedSinceIsIgnored() {
        val paging = ChatOlderPaging()
        assertEquals(Step.FetchOlder(1), paging.check(reading()))
        paging.failed(1)
        paging.retry()
        assertEquals(Step.FetchOlder(2), paging.check(reading()))
        assertFalse(paging.settled(1, advanced = true))
        paging.failed(1)
        assertNull(paging.blocked)
        assertEquals(2, paging.pending)
        assertTrue(paging.settled(2, advanced = true))
    }

    @Test fun theSpinnerShowsOnlyOnceTheWindowRendersAllTheStoreHolds() {
        val paging = ChatOlderPaging()
        assertEquals(ChatOlderPaging.Status.Idle, paging.status(hasMore = true, loading = true, windowReachesOldest = false))
        assertEquals(ChatOlderPaging.Status.Loading, paging.status(hasMore = true, loading = true, windowReachesOldest = true))
        assertEquals(ChatOlderPaging.Status.Idle, paging.status(hasMore = true, loading = false, windowReachesOldest = true))
    }

    @Test fun withNoMessagesEveryPageIsInvisibleAndFiveAskBeforeMore() {
        val paging = ChatOlderPaging()
        assertEquals(ChatOlderPaging.EmptyStatus.NoMessages, paging.emptyStatus(hasMore = false))
        assertEquals(Step.None, paging.checkEmpty(hasMore = false, loading = false))
        assertEquals(Step.None, paging.checkEmpty(hasMore = true, loading = true))
        for (request in 1..4) {
            assertEquals(Step.FetchOlder(request), paging.checkEmpty(hasMore = true, loading = false))
            assertEquals("one at a time", Step.None, paging.checkEmpty(hasMore = true, loading = false))
            assertEquals(ChatOlderPaging.EmptyStatus.Loading, paging.emptyStatus(hasMore = true))
            assertTrue(paging.settledEmpty(request, advanced = true))
        }
        assertEquals(Step.FetchOlder(5), paging.checkEmpty(hasMore = true, loading = false))
        assertFalse(paging.settledEmpty(5, advanced = true))
        assertEquals(ChatOlderPaging.EmptyStatus.LoadMore, paging.emptyStatus(hasMore = true))
        assertEquals(Step.None, paging.checkEmpty(hasMore = true, loading = false))
        paging.loadMore()
        assertEquals(Step.FetchOlder(6), paging.checkEmpty(hasMore = true, loading = false))

        assertFalse(paging.settledEmpty(6, advanced = false))
        assertEquals(0, paging.invisiblePages)
        assertEquals(Step.FetchOlder(7), paging.checkEmpty(hasMore = true, loading = false))
        paging.failed(7)
        assertEquals(ChatOlderPaging.EmptyStatus.Error, paging.emptyStatus(hasMore = true))
        assertEquals(Step.None, paging.checkEmpty(hasMore = true, loading = false))
        paging.retry()
        assertEquals(Step.FetchOlder(8), paging.checkEmpty(hasMore = true, loading = false))
    }

    @Test fun nothingHappensBeforeTheFirstLayoutWithoutMessagesOrWhileAGrowthIsOnItsWay() {
        val paging = ChatOlderPaging()
        assertEquals(Step.None, paging.check(reading(laidOut = false)))
        assertEquals(Step.None, paging.check(reading(laidOut = false, canGrow = true)))
        assertEquals("EmptyMessages pages", Step.None, paging.check(reading(messages = false)))
        assertEquals(Step.None, paging.check(reading(growing = true, canGrow = true)))
        assertEquals(Step.None, paging.check(reading(growing = true)))
        assertNull(paging.pending)
        assertEquals(Step.FetchOlder(1), paging.check(reading()))
    }

    @Test fun aPageIsJudgedOnlyOnceItsRowsCouldHaveJoinedTheWindow() {
        val paging = ChatOlderPaging()
        assertEquals(Step.FetchOlder(1), paging.check(reading(boundary = "m1")))
        assertTrue(paging.settled(1, advanced = true))
        assertEquals(Step.GrowWindow, paging.check(reading(canGrow = true, boundary = "m1")))
        assertEquals(Step.None, paging.check(reading(growing = true, boundary = "m1")))
        assertEquals(Step.GrowWindow, paging.check(reading(canGrow = true, boundary = "m1")))
        assertEquals(0, paging.invisiblePages)
        assertEquals("still awaiting judgement", ChatOlderPaging.Baseline("m1"), paging.baseline)
        assertEquals(Step.FetchOlder(2), paging.check(reading(boundary = "m-120")))
        assertEquals("it showed something", 0, paging.invisiblePages)
    }

    @Test fun resetStartsOverAndAnAnswerFromBeforeSettlesNothing() {
        val paging = ChatOlderPaging()
        for (request in 1..5) {
            assertEquals(Step.FetchOlder(request), paging.check(reading(boundary = "m1")))
            paging.settled(request, advanced = true)
        }
        assertEquals(Step.None, paging.check(reading(boundary = "m1")))
        assertEquals(ChatOlderPaging.Block.Limit, paging.blocked)
        paging.loadMore()
        assertEquals(Step.FetchOlder(6), paging.check(reading(boundary = "m1")))

        paging.reset()
        assertNull(paging.pending)
        assertNull(paging.baseline)
        assertNull(paging.blocked)
        assertEquals(0, paging.invisiblePages)
        assertEquals(ChatOlderPaging.Status.Idle, paging.status(hasMore = true, loading = false, windowReachesOldest = true))
        assertEquals(Step.FetchOlder(7), paging.check(reading(boundary = "n1")))
        assertFalse(paging.settled(6, advanced = true))
        paging.failed(6)
        assertNull(paging.blocked)
        assertEquals(7, paging.pending)
    }

    @Test fun theListTakingOverFromEmptyMessagesStartsItsOwnCount() {
        val paging = ChatOlderPaging()
        for (request in 1..3) {
            assertEquals(Step.FetchOlder(request), paging.checkEmpty(hasMore = true, loading = false))
            assertTrue(paging.settledEmpty(request, advanced = true))
        }
        assertEquals(3, paging.invisiblePages)
        assertEquals(Step.FetchOlder(4), paging.checkEmpty(hasMore = true, loading = false))
        paging.reset()
        assertEquals(Step.FetchOlder(5), paging.check(reading(boundary = "m1")))
        assertFalse(paging.settledEmpty(4, advanced = true))
        assertEquals(0, paging.invisiblePages)
        assertTrue(paging.settled(5, advanced = true))
    }

    @Test fun theBoundaryOfAnEmptyListIsNone() {
        assertNull(ChatOlderPaging.boundary(emptyList()))
    }
}
