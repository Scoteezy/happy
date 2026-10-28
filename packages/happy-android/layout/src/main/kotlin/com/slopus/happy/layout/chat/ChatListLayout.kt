package com.slopus.happy.layout.chat

import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.max
import kotlin.math.min

/**
 * The chat list's geometry, in the list's own (flipped) content space
 * (happy-ios Chat/ChatListLayout.swift).
 *
 * The list is inverted, as happy-app's ChatList.tsx and Telegram's history
 * list are: content y grows from the newest message towards the oldest, and
 * the view is flipped so y = 0 is the bottom of the screen. Row 0 is the
 * newest. That makes the newest edge the scroll origin, so the keyboard, the
 * composer and history loading never have to move what the reader sees.
 *
 *     y = 0            newer end (ChatFooter)
 *     newerEnd         row 0   (newest)
 *     …                row 1 …
 *     contentHeight    older end (spinner / Load more), then the far edge
 *
 * Every row's height is known before it is inserted: rows are measured off
 * the main thread first, so nothing here is an estimate.
 */
class ChatListLayout(
    width: Double,
    heights: List<Double>,
    newerEndHeight: Double = 0.0,
    olderEndHeight: Double = 0.0,
) {
    val width = max(0.0, width)
    val heights: List<Double> = heights.map { max(0.0, it) }
    val newerEndHeight = max(0.0, newerEndHeight)
    val olderEndHeight = max(0.0, olderEndHeight)

    /** `starts[i]` is row i's minY; `starts[count]` is where the older end starts. */
    private val starts: DoubleArray = DoubleArray(this.heights.size + 1).also { starts ->
        var y = this.newerEndHeight
        this.heights.forEachIndexed { index, height ->
            starts[index] = y
            y += height
        }
        starts[this.heights.size] = y
    }

    val count get() = heights.size

    val contentHeight get() = starts.last() + olderEndHeight

    val contentSize get() = Size(width, contentHeight)

    fun frameOfRow(index: Int) = Rect(0.0, starts[index], width, heights[index])

    val newerEndFrame get() = Rect(0.0, 0.0, width, newerEndHeight)

    val olderEndFrame get() = Rect(0.0, starts.last(), width, olderEndHeight)

    /** Rows that intersect `minY..<maxY`, found by binary search. */
    fun rows(from: Double, to: Double): IntRange {
        if (heights.isEmpty() || to <= from) return IntRange.EMPTY
        val first = firstRow(endingAfter = from)
        var last = first
        while (last < heights.size && starts[last] < to) last += 1
        return first until max(first, last)
    }

    /** The first row whose bottom is past [endingAfter]. */
    fun firstRow(endingAfter: Double): Int {
        var low = 0
        var high = heights.size
        while (low < high) {
            val mid = (low + high) / 2
            if (starts[mid] + heights[mid] <= endingAfter) low = mid + 1 else high = mid
        }
        return low
    }

    /** A copy with one row's height changed. */
    fun replacingHeight(index: Int, height: Double): ChatListLayout =
        ChatListLayout(width, heights.toMutableList().also { it[index] = height }, newerEndHeight, olderEndHeight)

    override fun equals(other: Any?) = other is ChatListLayout && other.width == width && other.heights == heights &&
        other.newerEndHeight == newerEndHeight && other.olderEndHeight == olderEndHeight

    override fun hashCode() = heights.hashCode()
}

/**
 * Keeps what the reader is looking at in place across a list update.
 *
 * Telegram keeps a "stationary" row fixed while it applies a transaction.
 * This is the same rule for a flipped list:
 *   - a reader at the newest edge (within [followThreshold]) stays at the
 *     newest edge, so new and streaming messages carry them along;
 *   - a reader further back keeps the row at the bottom of their screen at
 *     the same distance from that edge, whatever was inserted, removed or
 *     resized before it.
 *
 * Offsets here are "visible min y": the content y at the bottom edge of the
 * visible area. 0 means the newest edge is showing.
 */
data class ChatScrollAnchor(val kind: Kind) {
    sealed interface Kind {
        object Newest : Kind
        /** The row's near edge (its bottom on screen) stays this far from the visible min y. */
        data class Row(val id: String, val distance: Double) : Kind
        /** The row's far edge (its top on screen) stays this far from the visible min y. */
        data class RowEnd(val id: String, val distance: Double) : Kind
    }

    /** Where the visible min y must be after the update. */
    fun visibleMinY(layout: ChatListLayout, ids: List<String>, current: Double, maxVisibleMinY: Double): Double = when (kind) {
        Kind.Newest -> 0.0
        is Kind.Row -> {
            val index = ids.indexOf(kind.id)
            if (index < 0) min(current, max(0.0, maxVisibleMinY))
            else min(max(0.0, layout.frameOfRow(index).minY - kind.distance), max(0.0, maxVisibleMinY))
        }
        is Kind.RowEnd -> {
            val index = ids.indexOf(kind.id)
            if (index < 0) min(current, max(0.0, maxVisibleMinY))
            else min(max(0.0, layout.frameOfRow(index).maxY - kind.distance), max(0.0, maxVisibleMinY))
        }
    }

    companion object {
        const val followThreshold = 200.0

        /**
         * Notes where the reader is before an update. [follow] is false while
         * the reader's finger is on the list: snapping to the newest edge then
         * would fight the drag, so even near it the row under them holds.
         */
        fun capture(layout: ChatListLayout, ids: List<String>, visibleMinY: Double, follow: Boolean = true): ChatScrollAnchor {
            if (layout.count == 0 || (follow && visibleMinY <= followThreshold)) return ChatScrollAnchor(Kind.Newest)
            val index = min(layout.firstRow(endingAfter = visibleMinY), layout.count - 1)
            val frame = layout.frameOfRow(index)
            return ChatScrollAnchor(Kind.Row(ids[index], frame.minY - visibleMinY))
        }

        /**
         * Holds one row's top on screen where it is: a row the reader just
         * opened grows down towards the newest messages, under their finger.
         * A reader at the newest edge stays there. Null when the row isn't there.
         */
        fun pinning(id: String, layout: ChatListLayout, ids: List<String>, visibleMinY: Double): ChatScrollAnchor? {
            val index = ids.indexOf(id)
            if (index < 0 || index >= layout.count) return null
            if (visibleMinY <= 0.5) return ChatScrollAnchor(Kind.Newest)
            return ChatScrollAnchor(Kind.RowEnd(id, layout.frameOfRow(index).maxY - visibleMinY))
        }

        /**
         * Where the visible min y goes when the inset at the newest edge
         * changes by [delta] (the keyboard, the dock growing). Left alone,
         * the reader keeps what they see at the newest edge. Under the
         * reader's finger the content stays where the finger has it.
         */
        fun visibleMinYAfterNewestInsetChange(delta: Double, from: Double, maxVisibleMinY: Double, tracking: Boolean): Double {
            if (tracking) return from + delta
            return min(max(0.0, from), max(0.0, maxVisibleMinY))
        }
    }
}

/**
 * How the chat moves while it follows the newest message: ease-out (0, 0,
 * 0.58, 1) over 0.4 s. It starts at speed and lands softly, so a streamed
 * reply that grows a line every 100 to 250 ms never stalls between follows.
 */
object ChatFollowMotion {
    const val duration = 0.4
    val controlPoints = doubleArrayOf(0.0, 0.0, 0.58, 1.0)

    /**
     * The rows [new] adds at its newest end, newest first. Empty when nothing
     * was added. Null when anything else changed membership or order.
     */
    fun newestInserts(old: List<String>, new: List<String>): List<String>? {
        val kept = old.toHashSet()
        val added = new.takeWhile { it !in kept }
        if (new.drop(added.size) != old) return null
        return added
    }

    /**
     * How far the rows the reader sees move away from the newest edge when
     * [old] becomes [new] at the newest edge: the far edge of the oldest row
     * on screen that both have. Null when no row on screen survives.
     */
    fun shift(old: ChatListLayout, oldIds: List<String>, new: ChatListLayout, newIds: List<String>, visibleHeight: Double): Double? {
        if (old.count != oldIds.size || new.count != newIds.size) return null
        val index = HashMap<String, Int>()
        newIds.forEachIndexed { i, id -> index.putIfAbsent(id, i) }
        for (row in old.rows(0.0, max(visibleHeight, 1.0)).reversed()) {
            val next = index[oldIds[row]] ?: continue
            return new.frameOfRow(next).maxY - old.frameOfRow(row).maxY
        }
        return null
    }
}
