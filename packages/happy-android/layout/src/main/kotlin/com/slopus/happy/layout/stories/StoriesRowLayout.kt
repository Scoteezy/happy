package com.slopus.happy.layout.stories

import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The stories row above the Stem Cells sidebar (happy-ios
 * `StoriesRowLayout`), measured as Telegram's chat list draws its expanded
 * stories row (`StoryPeerListComponent`, `StoryPeerListItemComponent`):
 * 60 × 77 items 14 apart, a 52 avatar 4 in from the item's top, its ring on
 * a 30.66 radius (2.33 wide unseen, 1.33 seen), and the name in 11 pt 2
 * under the ring.
 */
data class StoriesRowLayout(
    val size: Size,
    val contentSize: Size,
    val spacing: Double,
    /** Each item's frame in the row's content. */
    val items: List<Rect>,
) {
    object Metrics {
        const val height = 98.0
        const val top = 12.0
        const val leading = 12.0
        val item = Size(60, 77)
        const val spacing = 14.0
        const val avatar = 52.0
        const val avatarTop = 4.0
        const val ringRadius = 30.66
        const val ringUnseenWidth = 2.33
        const val ringSeenWidth = 1.33

        /** The gap between ring segments, along the ring. */
        const val segmentGap = 4.0
        const val titleSize = 11.0
        const val titleGap = 2.0
        const val titleOverhang = 10.0

        /** The ring's indicator square (Telegram's avatar inset by 8). */
        const val indicator = 68.0

        /** Telegram widens the spacing so the edge never shows a sliver of a ring. */
        const val ringOutset = 0.66
        const val maximumSliver = 4.0
    }

    /** One item, in the item's own space. */
    data class Item(
        val avatar: Rect,
        /** The ring's square: the avatar grown to the ring's outer radius. */
        val ring: Rect,
    ) {
        /**
         * The title's line, centred 2 under the ring's 68 pt square, at
         * most 10 wider than the item.
         */
        fun title(width: Double, height: Double): Rect {
            val w = min(Metrics.item.width + Metrics.titleOverhang, ceil(width))
            return Rect(floor((Metrics.item.width - w) / 2), avatar.midY + Metrics.indicator / 2 + Metrics.titleGap, w, ceil(height))
        }
    }

    companion object {
        val item: Item = run {
            val m = Metrics
            val avatar = Rect(floor((m.item.width - m.avatar) / 2), m.avatarTop, m.avatar, m.avatar)
            val grow = m.ringRadius - m.avatar / 2
            Item(avatar, Rect(avatar.x - grow, avatar.y - grow, avatar.width + 2 * grow, avatar.height + 2 * grow))
        }

        operator fun invoke(width: Double, count: Int): StoriesRowLayout {
            val m = Metrics
            val spacing = spacing(width, count)
            val pitch = m.item.width + spacing
            val items = (0 until count).map { Rect(m.leading + it * pitch, m.top, m.item.width, m.item.height) }
            val contentWidth = if (count == 0) width else m.leading * 2 + count * m.item.width + (count - 1) * spacing
            return StoriesRowLayout(Size(width, m.height), Size(max(width, contentWidth), m.height), spacing, items)
        }

        /**
         * Telegram's `StoryPeerListRowLayout.itemSpacing`: when the row
         * overflows and the edge would show only a sliver of a ring, the
         * spacing grows until the avatar before it is cut at its centre.
         */
        fun spacing(containerWidth: Double, count: Int): Double {
            val m = Metrics
            val base = m.spacing
            val total = m.leading * 2 + count * m.item.width + max(0, count - 1) * base
            if (total <= containerWidth) return base
            val pitch = m.item.width + base
            var cut = 0
            while (m.leading + cut * pitch + m.item.width <= containerWidth) cut += 1
            if (cut < 2 || cut >= count) return base
            val visible = containerWidth - (m.leading + cut * pitch)
            val peek = visible + m.ringOutset
            if (peek <= 0 || peek >= m.maximumSliver) return base
            return base + floor((visible + pitch - m.item.width * 0.5) / (cut - 1))
        }

        /**
         * Ring segments as angle ranges, clockwise from the top: one per
         * story with a 4 pt gap between, or the whole circle for one.
         */
        fun segments(count: Int, radius: Double = Metrics.ringRadius): List<Pair<Double, Double>> {
            if (count <= 1) return listOf(-PI / 2 to 3 * PI / 2)
            val n = min(count, 30)
            val gap = Metrics.segmentGap / radius
            val arc = (2 * PI - gap * n) / n
            return (0 until n).map { i ->
                val start = gap / 2 - PI / 2 + i * (gap + arc)
                start to start + arc
            }
        }
    }
}
