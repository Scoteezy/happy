package com.slopus.happy.layout.chat

import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.max
import kotlin.math.min

/**
 * WorktreeTabStrip.tsx (happy-ios `WorktreeTabStripLayout`): a checkout's
 * chats as tabs in a capsule under the session screen's header, then a
 * hairline and `+`.
 *
 *     10
 *     16 ┌ border ─ 4 [tab] 2 [tab] 2 … 4 ─ scrolls ─┬ hairline ┬─ + 40 ┐ 16
 *        └ 40 tall, round ──────────────────────────┴──────────┴───────┘
 *     6
 *
 * The capsule is held to the header's max width and centred, like the
 * header's row above it. A tab is 32 tall and round, 10 in at each side,
 * its name then, 6 after it, a 7dp dot; it grows to its name, up to 200.
 */
data class WorktreeTabStripLayout(
    val size: Size,
    val pill: Rect,
    /** Inside the rim, in the pill: the scroller, the hairline and `+` are clipped to it. */
    val inner: Rect,
    /** In [inner]. */
    val scroller: Rect,
    val contentWidth: Double,
    val tabs: List<TabFrame>,
    val divider: Rect?,
    val add: Rect?,
) {
    object Metrics {
        const val pillHeight = 40.0
        const val gapAbove = 10.0
        const val gapBelow = 6.0
        /** `WORKTREE_TAB_STRIP_HEIGHT`: what the strip costs the layout. */
        const val height = pillHeight + gapAbove + gapBelow
        const val paddingH = 16.0
        /** The glass capsule's rim; off glass it is a hairline. */
        const val glassBorder = 1.0
        const val tabsPaddingH = 4.0
        const val tabGap = 2.0
        const val tabHeight = pillHeight - 8
        const val tabPaddingH = 10.0
        const val tabMaxWidth = 200.0
        const val dotGap = 6.0
        const val dotSize = 7.0
        const val dividerHeight = 20.0
        const val addWidth = 40.0
        const val addIconSize = 20.0
        const val addHitSlop = 8.0
        /** TAB_SCROLL_MARGIN: room either side of a tab scrolled to. */
        const val scrollMargin = 8.0
        /** `tabPressed` and `addButtonPressed`. */
        const val pressedOpacity = 0.55
    }

    /** [dot]: the blocked or unread dot. */
    data class Tab(val label: Measurable, val dot: Boolean)

    /** [frame] in the scrolling content; [label] and [dot] in the tab. */
    data class TabFrame(val frame: Rect, val label: Rect, val dot: Rect?)

    companion object {
        /** [border] is the rim: 1 on glass, a hairline off it. */
        operator fun invoke(width: Double, maxWidth: Double, border: Double, hairline: Double, tabs: List<Tab>, showsAdd: Boolean): WorktreeTabStripLayout {
            val m = Metrics
            val w = max(0.0, width)
            val column = ChatColumn(w, maxWidth)
            val pill = Rect(column.x + m.paddingH, m.gapAbove, max(0.0, column.width - 2 * m.paddingH), m.pillHeight)
            val inner = Rect(border, border, max(0.0, pill.width - 2 * border), max(0.0, pill.height - 2 * border))
            val trailing = if (showsAdd) hairline + m.addWidth else 0.0
            val scroller = Rect(0.0, 0.0, max(0.0, inner.width - trailing), inner.height)
            val divider = if (showsAdd) Rect(scroller.maxX, (inner.height - m.dividerHeight) / 2, hairline, m.dividerHeight) else null
            val add = if (showsAdd) Rect(scroller.maxX + hairline, 0.0, m.addWidth, inner.height) else null

            var x = m.tabsPaddingH
            val tabY = (inner.height - m.tabHeight) / 2
            val frames = tabs.map { tab ->
                val dotWidth = if (tab.dot) m.dotGap + m.dotSize else 0.0
                val labelRoom = m.tabMaxWidth - 2 * m.tabPaddingH - dotWidth
                val measured = tab.label.size(labelRoom)
                val labelWidth = min(measured.width, labelRoom)
                val frame = Rect(x, tabY, m.tabPaddingH + labelWidth + dotWidth + m.tabPaddingH, m.tabHeight)
                x = frame.maxX + m.tabGap
                TabFrame(
                    frame = frame,
                    label = Rect(m.tabPaddingH, (m.tabHeight - measured.height) / 2, labelWidth, measured.height),
                    dot = if (tab.dot) Rect(m.tabPaddingH + labelWidth + m.dotGap, (m.tabHeight - m.dotSize) / 2, m.dotSize, m.dotSize) else null,
                )
            }
            val contentWidth = (frames.lastOrNull()?.frame?.maxX ?: 0.0) + m.tabsPaddingH
            return WorktreeTabStripLayout(Size(w, m.height), pill, inner, scroller, contentWidth, frames, divider, add)
        }

        /**
         * `scrollToTab`: the smallest scroll that brings a tab into view with
         * 8 to spare, or null when it is in view already. The strip is never
         * re-centred under a finger for a tab already on screen.
         */
        fun revealOffset(tab: Rect, offset: Double, viewport: Double, contentWidth: Double): Double? {
            val left = tab.minX - Metrics.scrollMargin
            val right = tab.maxX + Metrics.scrollMargin
            if (left >= offset && right <= offset + viewport) return null
            val target = if (left < offset) left else right - viewport
            return min(max(0.0, target), max(0.0, contentWidth - viewport))
        }
    }
}
