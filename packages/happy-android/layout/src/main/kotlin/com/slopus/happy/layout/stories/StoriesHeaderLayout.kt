package com.slopus.happy.layout.stories

import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Stories in home's header (happy-ios `StoriesHeaderLayout`), as Telegram's
 * chat list carries them (`StoryPeerListComponent` in
 * `ChatListNavigationBar`): a row of circles under the header while it is
 * open, and up to three small overlapping avatars before the title once it
 * closes. Everything is drawn from where the list is: `pull` is how far it
 * sits below its closed top, 96 when the row is open and at rest.
 *
 *     open      (logo) ……… Sessions ……… (pill)
 *                 (A)   (B)   (C)   (D)
 *                 Ada   Bo    Cy    Di
 *
 *     closed    (logo) …  ⦿⦿⦿ Sessions … (pill)
 *
 * The row opens past 65 of pull and closes again under 61. While it is
 * open the circles follow the list, shrinking into the title as it rises;
 * closed, they stay in the title and only lean out a little as the list
 * is pulled. Between the two the change is animated (`StoriesHeaderMotion`).
 */
data class StoriesHeaderLayout(
    /**
     * The open row's band in the header's space: what scrolls sideways and
     * takes touches while it is open.
     */
    val row: Rect,
    val contentWidth: Double,
    /** Each circle's frame in the open row, in the row's content. */
    val rowItems: List<Rect>,
    val items: List<Item>,
    /** Where the title starts; null leaves it centred. */
    val titleX: Double?,
    /** The closed avatars' target, when there are any. */
    val button: Rect?,
    /** How many avatars close into the title. */
    val collapsedCount: Int,
    /** Where the one-time tooltip under the closed avatars points. */
    val tooltipTip: Point?,
) {
    object Metrics {
        /**
         * `ChatListNavigationBar.storiesScrollHeight`: the room the open row
         * adds above the list.
         */
        const val scrollHeight = 96.0
        const val unlockPull = 65.0
        const val lockPull = 61.0

        /** A closed avatar, and how far the next one starts after it. */
        const val collapsedWidth = 24.0
        const val collapsedPitch = 14.0
        const val collapsedCount = 3

        /** How far the open row's avatars sit under the title's middle. */
        const val rowDrop = 66.0

        /** Between the closed avatars and the title. */
        const val titleSpacing = 8.0

        /** A closed avatar, and one leaning out under a pull, against an open one. */
        const val minScale = 24.0 / 52
        const val pulledScale = 28.0 / 52

        /**
         * How the closed avatars lean out under a pull: the last goes this
         * much right and down, the first this much left, all of them down.
         */
        const val pullSpread = 20.0
        const val pullLead = 4.0
        const val pullDrop = 10.0

        /** The title moves this much right under a full pull. */
        const val titlePull = 12.0

        /**
         * Past the open rest the circles follow the list down this much of
         * the way and grow a little.
         */
        const val overscrollFollow = 83 * 0.5
        const val overscrollGrowth = 0.1

        /**
         * The open row's items, 60 × 77, 12 in from the sides (Telegram
         * insets them 4 less than its 16 bar padding).
         */
        const val inset = 12.0
        const val rowTop = 12.0
        const val rowHeight = 98.0
        const val maxSpacing = 100.0

        /** The avatar's middle, down from the item's top. */
        const val avatarMid = 30.0

        /**
         * The title stays this far inside the band between the buttons, and
         * starts balancing in it this close to either end.
         */
        const val bandMargin = 4.0
        const val balanceRamp = 20.0

        /** The closed avatars' target, as tall as a header button. */
        const val buttonHeight = 44.0

        /**
         * The one-time tooltip's arrow points up at the closed avatars'
         * middle from this far under their target, 1 right of it, as
         * Telegram places it (`anchorForTooltip`, nudged 6 down and 1 right).
         */
        const val tooltipDrop = 8.0

        /**
         * The ring's width grows toward this as a circle shrinks, so a small
         * one still shows it.
         */
        const val minimizedRing = 4.3
        const val unlockDuration = 0.35
        const val lockDuration = 0.25
    }

    /**
     * Where the change between open and closed stands: `min` is how far the
     * closed avatars lean out, `max` how far open the row is (past 1 when
     * pulled beyond its rest), `side` how present the open row's names and
     * further circles are.
     */
    data class State(val min: Double, val max: Double, val side: Double) {
        internal fun mixed(other: State, t: Double) = State(lerp(min, other.min, t), lerp(max, other.max, t), lerp(side, other.side, t))

        companion object {
            /** Where a list `pull` below its closed top puts things, open or not. */
            fun target(unlocked: Boolean, pull: Double): State {
                val fraction = max(0.0, min(4.0, pull / Metrics.scrollHeight))
                return if (unlocked) State(0.0, fraction, 1.0) else State(fraction, 0.0, 0.0)
            }
        }
    }

    data class Input(
        val width: Double,
        /** The header's title where it would be centred alone. */
        val title: Rect,
        /** Between the header's left control and its right pill. */
        val bandMinX: Double,
        val bandMaxX: Double,
        val count: Int,
        /**
         * The first circle that closes into the title: 1 to leave out the
         * person's own, first, when it has nothing new.
         */
        val collapseStart: Int,
        /** How far the open row is scrolled sideways. */
        val scroll: Double,
        val state: State,
    )

    data class Item(
        /** The avatar's middle, in the header's space. */
        val center: Point,
        /** Against the open row's 52 avatar; the name and ring scale with it. */
        val scale: Double,
        val alpha: Double,
        /** One of the avatars that close into the title. */
        val collapses: Boolean,
        /** Drawn over the circles after it. */
        val zPosition: Double,
        /**
         * Where the circle before it stands, from this one's middle, while
         * they overlap: this one is cut around it.
         */
        val neighbor: Point?,
    )

    companion object {
        /**
         * Whether the row is open after a list `pull`: it opens past 65 and
         * closes under 61, keeping what it was between.
         */
        fun unlocked(current: Boolean, pull: Double): Boolean {
            if (pull >= Metrics.unlockPull) return true
            if (pull <= Metrics.lockPull) return false
            return current
        }

        /**
         * Where a list let go of with the row `unlocked` comes to rest, given
         * where it would glide to: never part way into the row's room. As
         * Telegram's chat list ends a scroll there, it goes to whichever the
         * row will be once the glide is done (the glide closes it under 61, it
         * never opens it), so a short flick up puts the row away.
         */
        fun resting(unlocked: Boolean, glide: Double): Double {
            if (glide <= 0 || glide >= Metrics.scrollHeight) return glide
            return if (unlocked && glide > Metrics.lockPull) Metrics.scrollHeight else 0.0
        }

        operator fun invoke(input: Input): StoriesHeaderLayout {
            val m = Metrics
            val width = max(0.0, input.width)
            val state = input.state
            val open = min(1.0, state.max)
            val overscroll = max(0.0, state.max - 1)
            val n = max(0, input.count)
            val rowY = input.title.midY + m.rowDrop - m.avatarMid
            val row = Rect(0.0, rowY - m.rowTop, width, m.rowHeight)
            val (frames, content) = rowFrames(width, n)
            val rowItems = frames.map { it.offsetBy(0.0, m.rowTop) }

            val start = min(max(0, input.collapseStart), n)
            val count = min(n - start, m.collapsedCount)
            val collapsedWidth = if (count > 0) m.collapsedWidth + m.collapsedPitch * (count - 1) else 0.0
            val spacing = if (count > 0) m.titleSpacing else 0.0
            val origin = contentOrigin(width, collapsedWidth + spacing + input.title.width, input.bandMinX, input.bandMaxX)

            // The circles the open row shows from its left edge close first,
            // never the person's own, and always as many as there are room for.
            val visibleMin = input.scroll
            val visibleMax = input.scroll + width
            val firstVisible = frames.indexOfFirst { it.maxX > visibleMin && it.minX < visibleMax }.takeIf { it >= 0 } ?: 0
            val first = min(max(start, firstVisible), max(start, n - count))

            fun measure(i: Int): Pair<Point, Double> {
                val frame = frames[i].offsetBy(-input.scroll, 0.0)
                val regular = Point(frame.midX, rowY + m.avatarMid)
                val index = i - first
                val step = min(max(0, index), max(0, count - 1)).toDouble()
                val collapsedX = if (index < 0) origin else origin + step * m.collapsedPitch * (1 - state.max)
                val collapsed = Point(collapsedX + m.collapsedWidth / 2, regular.y - m.rowDrop)
                var leaning = collapsed
                if (n > 1) {
                    val distance = if (count > 1) max(0.0, min(1.0, index.toDouble() / (count - 1))) else 0.0
                    leaning = Point(
                        leaning.x + distance * m.pullSpread - state.min * m.pullLead,
                        leaning.y + distance * m.pullSpread + state.min * m.pullDrop,
                    )
                }
                val closed = lerp(collapsed, leaning, state.min)
                var center = lerp(closed, regular, open)
                center = Point(center.x, center.y + overscroll * m.overscrollFollow)
                val small = lerp(m.minScale, m.pulledScale, state.min)
                val full = 1 + min(0.5, overscroll) * m.overscrollGrowth
                return center to lerp(small, full, open)
            }

            val items = (0 until n).map { i ->
                val (center, scale) = measure(i)
                val index = i - first
                val collapses = count > 0 && index >= 0 && index < count
                // The rest fade into the last avatar by the title.
                val alpha = if (collapses) 1.0 else state.side
                var neighbor: Point? = null
                if (collapses && index > 0 && state.max < 0.5) {
                    val left = measure(i - 1).first
                    neighbor = Point(left.x - center.x, left.y - center.y)
                }
                Item(center, scale, alpha, collapses, if (collapses) 1000 - i * 0.01 else 0.5, neighbor)
            }

            val titleX = if (n == 0 || count == 0) {
                null
            } else {
                val collapsedTitle = origin + collapsedWidth + spacing
                val leaning = lerp(collapsedTitle, collapsedTitle + m.titlePull, state.min)
                lerp(leaning, (width - input.title.width) / 2, open)
            }
            val button = if (count > 0 && state.side < 1) {
                Rect(input.bandMinX, input.title.midY - m.buttonHeight / 2, max(0.0, input.bandMaxX - input.bandMinX), m.buttonHeight)
            } else {
                null
            }
            val tooltipTip = if (count > 0) Point(origin + collapsedWidth / 2 + 1, input.title.midY + m.buttonHeight / 2 + m.tooltipDrop) else null
            return StoriesHeaderLayout(row, content, rowItems, items, titleX, button, count, tooltipTip)
        }

        /**
         * The open row's items as Telegram's list spaces them: one centred; a
         * few that fit spread evenly about the middle; more from the left at
         * 14 apart, scrolling, the spacing grown so the edge never shows a
         * sliver of a ring. Returns the frames (y 0) and the content's width.
         */
        internal fun rowFrames(width: Double, count: Int): Pair<List<Rect>, Double> {
            val m = Metrics
            val item = StoriesRowLayout.Metrics.item
            if (count <= 0) return emptyList<Rect>() to width
            if (count == 1) {
                val x = m.inset + floor((width - 2 * m.inset - item.width) / 2)
                return listOf(Rect(x, 0.0, item.width, item.height)) to width
            }
            val n = count.toDouble()
            val base = 2 * m.inset + n * item.width + (n - 1) * StoriesRowLayout.Metrics.spacing
            val spacing = if (base >= width) StoriesRowLayout.spacing(width, count) else StoriesRowLayout.Metrics.spacing
            val content = 2 * m.inset + n * item.width + (n - 1) * spacing
            if (content < width) {
                val even = min(m.maxSpacing, floor((width - 2 * m.inset - n * item.width) / (n + 1)))
                val used = n * item.width + (n - 1) * even
                val x0 = floor((width - used) / 2)
                return (0 until count).map { Rect(x0 + (item.width + even) * it, 0.0, item.width, item.height) } to width
            }
            return (0 until count).map { Rect(m.inset + (item.width + spacing) * it, 0.0, item.width, item.height) } to content
        }

        /**
         * The left edge of the title's content, `content` wide: centred on the
         * screen while that keeps it clear of both buttons, in the middle of
         * the band between them once it would touch one, and on a smooth step
         * from one to the other over the last 20 points.
         */
        fun contentOrigin(width: Double, content: Double, minX: Double, maxX: Double): Double {
            val low = minX + Metrics.bandMargin
            val high = maxX - Metrics.bandMargin
            val centred = (width - content) / 2
            val balanced = (low + high - content) / 2
            val gap = min(centred - low, high - (centred + content))
            val x = max(0.0, min(1.0, 1 - gap / Metrics.balanceRamp))
            return centred + (balanced - centred) * x * x * (3 - 2 * x)
        }

        /**
         * A ring's width before the circle's scale, so that it reads about as
         * wide at any size: `base` open, 4.3 when shrunk to nothing.
         */
        fun ringWidth(base: Double, scale: Double): Double {
            val s = max(0.0, min(1.0, scale))
            return base * s + Metrics.minimizedRing * (1 - s)
        }
    }
}

/**
 * The animated change between the open and closed header (happy-ios
 * `StoriesHeaderMotion`): from what was drawn when it began toward wherever
 * the list now puts things, on the keyboard's spring (Telegram's
 * `listViewAnimationCurveSystem`), 0.35 s opening and 0.25 s closing.
 */
data class StoriesHeaderMotion(val from: StoriesHeaderLayout.State, val start: Double, val duration: Double) {
    constructor(from: StoriesHeaderLayout.State, start: Double, opening: Boolean) :
        this(from, start, if (opening) StoriesHeaderLayout.Metrics.unlockDuration else StoriesHeaderLayout.Metrics.lockDuration)

    fun progress(time: Double): Double {
        if (duration <= 0) return 1.0
        return curve(max(0.0, min(1.0, (time - start) / duration)))
    }

    fun state(target: StoriesHeaderLayout.State, time: Double): StoriesHeaderLayout.State = from.mixed(target, progress(time))

    fun isFinished(time: Double): Boolean = time >= start + duration

    companion object {
        /** cubic-bezier(0.23, 1, 0.32, 1). */
        fun curve(t: Double): Double = cubicBezier(t, 0.23, 1.0, 0.32, 1.0)
    }
}

/**
 * A CSS cubic-bezier from (0, 0) to (1, 1) through (x1, y1) and (x2, y2),
 * at `t` of the way across: Newton's method on x, halving where it stalls.
 */
fun cubicBezier(t: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
    fun bezier(u: Double, a: Double, b: Double): Double {
        val v = 1 - u
        return 3 * v * v * u * a + 3 * v * u * u * b + u * u * u
    }
    fun slope(u: Double, a: Double, b: Double): Double {
        val v = 1 - u
        return 3 * v * v * a + 6 * v * u * (b - a) + 3 * u * u * (1 - b)
    }
    if (t <= 0) return 0.0
    if (t >= 1) return 1.0
    var u = t
    for (i in 0 until 8) {
        val d = slope(u, x1, x2)
        if (abs(d) < 1e-6) break
        u -= (bezier(u, x1, x2) - t) / d
        u = max(0.0, min(1.0, u))
    }
    // Newton can stall where the curve is flat; halving settles it.
    if (abs(bezier(u, x1, x2) - t) > 1e-4) {
        var low = 0.0
        var high = 1.0
        for (i in 0 until 30) {
            u = (low + high) / 2
            if (bezier(u, x1, x2) < t) low = u else high = u
        }
    }
    return bezier(u, y1, y2)
}

internal fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
internal fun lerp(a: Point, b: Point, t: Double) = Point(lerp(a.x, b.x, t), lerp(a.y, b.y, t))
