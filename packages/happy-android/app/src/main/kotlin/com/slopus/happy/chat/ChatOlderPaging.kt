package com.slopus.happy.chat

/**
 * When the chat asks the server for older messages, and what its older end
 * shows (happy-ios `ChatOlderPaging`): ChatList.tsx's `fillOlder` and
 * `olderStatus`, and EmptyMessages' paging while no message is in the
 * store yet.
 *
 * Whenever the oldest rendered row is within a screen, one older page is
 * fetched (the store renders whole here, so there is no window to grow
 * first). Each settlement checks again, so paging walks back as far as the
 * reader goes and stops at the end, at an error, or after five pages that
 * showed nothing new (a run of events the list hides), where "Load more"
 * asks the reader before going on.
 *
 * Pure: the screen reads its list and the store's flags into a [Reading],
 * carries out the [Step], and reports how each fetch it started settled.
 */
class ChatOlderPaging {
    sealed interface Step {
        data object None : Step
        /** Render the next page the store already holds. */
        data object GrowWindow : Step
        /** Fetch one older page; report it with [settled] or [failed]. */
        data class FetchOlder(val request: Int) : Step
    }

    /** OlderEnd's `status`. */
    enum class Status { Idle, Loading, Error, LoadMore }

    enum class Block { Error, Limit }

    /** What EmptyMessages shows under the session's details. */
    enum class EmptyStatus { NoMessages, Loading, Error, LoadMore }

    /** What the screen sees at a check. */
    data class Reading(
        /** The list has a measured viewport; before its first layout nothing is near anything. */
        val laidOut: Boolean,
        /** The store holds a message; with none the screen is EmptyMessages, which pages on its own. */
        val storeHasMessages: Boolean,
        /** A window growth was asked for and has not reached the screen. */
        val growthPending: Boolean,
        /** The oldest rendered row is within a screen of the visible area. */
        val nearOldest: Boolean,
        /** The store holds messages older than the window renders. */
        val windowCanGrow: Boolean,
        /** [boundary] of the rendered rows; null for none. */
        val oldestBoundary: String?,
        /** The server has messages older than the store's. */
        val hasMore: Boolean,
        /** The store's flag: an older page is on its way, for anyone. */
        val loading: Boolean,
        /** The screen is the one shown. */
        val active: Boolean,
    )

    /** The far end a settled page is judged against (`olderPageRef`); [boundary] null is an empty list. */
    data class Baseline(val boundary: String?)

    /** This screen's fetch still out, until it settles; a failed one stays out until Retry. */
    var pending: Int? = null
        private set
    /** The page awaiting judgement, once its rows have had the chance to join. */
    var baseline: Baseline? = null
        private set
    var invisiblePages = 0
        private set
    var blocked: Block? = null
        private set
    private var requests = 0

    /** `fillOlder`. */
    fun check(reading: Reading): Step {
        if (!reading.laidOut || !reading.storeHasMessages || reading.growthPending || !reading.nearOldest) return Step.None
        if (reading.windowCanGrow) return Step.GrowWindow
        // A settled page is judged once its rows could have joined.
        val judged = baseline
        if (judged != null && pending == null) {
            baseline = null
            if (reading.oldestBoundary != judged.boundary) {
                invisiblePages = 0
                blocked = null
            } else {
                invisiblePages += 1
                if (invisiblePages >= MAX_INVISIBLE_PAGES) {
                    blocked = Block.Limit
                    return Step.None
                }
            }
        }
        if (!reading.active || !reading.hasMore || reading.loading || blocked != null || pending != null ||
            invisiblePages >= MAX_INVISIBLE_PAGES
        ) {
            return Step.None
        }
        requests += 1
        pending = requests
        baseline = Baseline(reading.oldestBoundary)
        return Step.FetchOlder(requests)
    }

    /**
     * A fetch came back. True when it moved the server's cursor, so the
     * screen checks again once its rows are in; a page that moved nothing
     * rests until the next scroll or store change, so it cannot spin.
     */
    fun settled(request: Int, advanced: Boolean): Boolean {
        if (pending != request) return false
        pending = null
        if (!advanced) baseline = null
        return advanced
    }

    /** A fetch failed: paging stops, and the older end offers Retry. */
    fun failed(request: Int) {
        if (pending != request) return
        baseline = null
        blocked = Block.Error
    }

    /**
     * Starts over for another chat, or for the list taking over from
     * EmptyMessages. The request numbers keep counting, so an answer to a
     * fetch from before never settles one started after.
     */
    fun reset() {
        pending = null
        baseline = null
        invisiblePages = 0
        blocked = null
    }

    /** `retryOlder`. */
    fun retry() {
        pending = null
        blocked = null
    }

    /** `loadMoreOlder`: five more pages before asking again. */
    fun loadMore() {
        invisiblePages = 0
        blocked = null
    }

    /**
     * `olderStatus`. The store's loading flag also pulses for pages the
     * reader never sees; the spinner shows only while the window renders
     * all the store holds.
     */
    fun status(hasMore: Boolean, loading: Boolean, windowReachesOldest: Boolean): Status = when {
        blocked == Block.Error && hasMore -> Status.Error
        blocked == Block.Limit && hasMore -> Status.LoadMore
        loading && windowReachesOldest -> Status.Loading
        else -> Status.Idle
    }

    // No messages yet

    fun emptyStatus(hasMore: Boolean): EmptyStatus = when {
        !hasMore -> EmptyStatus.NoMessages
        blocked == Block.Error -> EmptyStatus.Error
        blocked == Block.Limit -> EmptyStatus.LoadMore
        else -> EmptyStatus.Loading
    }

    /**
     * EmptyMessages: while the store has no message the screen shows its
     * placeholder and pages back on its own, every page invisible by
     * definition, five at most before Load more.
     */
    fun checkEmpty(hasMore: Boolean, loading: Boolean): Step {
        if (!hasMore || loading || blocked != null || pending != null) return Step.None
        requests += 1
        pending = requests
        return Step.FetchOlder(requests)
    }

    /** An empty screen's fetch came back. True to check again. */
    fun settledEmpty(request: Int, advanced: Boolean): Boolean {
        if (pending != request) return false
        pending = null
        if (!advanced) return false
        invisiblePages += 1
        if (invisiblePages >= MAX_INVISIBLE_PAGES) {
            blocked = Block.Limit
            return false
        }
        return true
    }

    companion object {
        /** `MAX_INVISIBLE_OLDER_PAGES`. */
        const val MAX_INVISIBLE_PAGES = 5

        /**
         * `oldestBoundary`: the oldest row's key, and for a folded turn its
         * oldest member too, so a page that extends that turn counts as
         * seen. [items] newest first, as the list renders them.
         */
        fun boundary(items: List<ChatListItem>): String? = when (val oldest = items.lastOrNull()) {
            null -> null
            is ChatListItem.WorkHeader -> "${oldest.key}:${oldest.group.rows.firstOrNull()?.key.orEmpty()}"
            else -> oldest.key
        }
    }
}
