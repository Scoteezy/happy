package com.slopus.happy.layout.stories

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The full-screen viewer (happy-ios `StoryViewerLayout`), as Telegram's
 * `StoryItemSetContainerComponent` lays one author out: the canvas under
 * the status bar with 12 rounded corners, the progress strip 8 inside its
 * top (2 tall, 3 between segments), the author's 32 avatar 12 in and 18
 * down with their name and the time beside it, the 50 × 64 close button at
 * the top right, the more button and, on a recording, the sound button
 * left of it, and the bar below the canvas: the heart (or, on one's own
 * story, its views).
 */
data class StoryViewerLayout(
    val canvas: Rect,
    val strip: Rect,
    val avatar: Rect,
    /** Name and time, left aligned from here, two lines. */
    val titleOrigin: Point,
    val titleWidth: Double,
    val close: Rect,
    val more: Rect,
    /** A recording's sound button. */
    val sound: Rect,
    /** The bar under the canvas; empty when the canvas reaches the bottom. */
    val bar: Rect,
    val heart: Rect,
    /** One's own story: the views button at the bar's left. */
    val views: Rect,
) {
    object Metrics {
        const val cornerRadius = 12.0
        const val stripInset = 8.0
        const val stripHeight = 2.0
        const val stripSpacing = 3.0
        const val minSegment = 2.0
        const val avatar = 32.0
        val avatarOrigin = Point(12.0, 18.0)
        const val nameGap = 10.0
        val close = Size(50, 64)

        /**
         * The sound button's middle is this far left of the more button's
         * (Telegram's 33 wide buttons, 12 apart).
         */
        const val soundPitch = 45.0
        const val bar = 52.0
        const val barButton = 44.0
        const val barInset = 8.0

        /** The thirds a tap goes back or forward in. */
        const val backFraction = 1.0 / 3.0
    }

    /** The name's and time's width, up to the sound button when it shows. */
    fun titleWidth(withSound: Boolean): Double = if (withSound) max(0.0, sound.minX - titleOrigin.x) else titleWidth

    /** What a tap at `x` on the canvas does. */
    enum class Tap { Back, Forward }

    fun tap(x: Double): Tap = if (x < canvas.minX + canvas.width * Metrics.backFraction) Tap.Back else Tap.Forward

    companion object {
        operator fun invoke(size: Size, safeArea: LayoutInsets): StoryViewerLayout {
            val m = Metrics
            val available = Rect(0.0, safeArea.top, size.width, max(0.0, size.height - safeArea.top - safeArea.bottom - m.bar))
            val canvas = StoryCanvasLayout.fit(available)
            val strip = Rect(canvas.minX + m.stripInset, canvas.minY + m.stripInset, max(0.0, canvas.width - 2 * m.stripInset), m.stripHeight)
            val avatar = Rect(canvas.minX + m.avatarOrigin.x, canvas.minY + m.avatarOrigin.y, m.avatar, m.avatar)
            val close = Rect(canvas.maxX - m.close.width, canvas.minY + 2, m.close.width, m.close.height)
            val more = close.offsetBy(-m.close.width + 8, 0.0)
            val sound = more.offsetBy(-m.soundPitch, 0.0)
            val titleOrigin = Point(avatar.maxX + m.nameGap, avatar.minY - 1)
            val titleWidth = max(0.0, more.minX - titleOrigin.x)
            val barTop = canvas.maxY
            val bar = Rect(0.0, barTop, size.width, max(0.0, size.height - safeArea.bottom - barTop))
            val buttonY = barTop + (min(bar.height, m.bar) - m.barButton) / 2
            val heart = Rect(canvas.maxX - m.barInset - m.barButton, buttonY, m.barButton, m.barButton)
            val views = Rect(canvas.minX + m.barInset, buttonY, max(0.0, canvas.width / 2), m.barButton)
            return StoryViewerLayout(canvas, strip, avatar, titleOrigin, titleWidth, close, more, sound, bar, heart, views)
        }

        /** The progress segments, one per frame, across `strip`. */
        fun segments(count: Int, strip: Rect): List<Rect> {
            val m = Metrics
            if (count <= 0) return emptyList()
            val ideal = (strip.width - (count - 1) * m.stripSpacing) / count
            val width = max(m.minSegment, ideal)
            return (0 until count).map { Rect(strip.minX + it * (width + m.stripSpacing), strip.minY, width, strip.height) }
        }
    }
}

/** The turn between two authors: a cube, as Telegram's `StoryContainerScreen` draws it, with a perspective of 500. */
object StoryCube {
    const val perspective = 500.0

    /** The angle a side turns for `fraction` of the way (-1…1) to the next. */
    fun angle(fraction: Double): Double = max(-1.0, min(1.0, fraction)) * PI / 2
}

/**
 * The sheet listing who saw one's own story (happy-ios
 * `StoryViewersSheetLayout`), measured as Telegram's
 * (`StoryItemSetContainerComponent`, `StoryItemSetViewListComponent`): edge
 * to edge and down to the screen's foot, its top corners rounded 10; half
 * open it leaves the story 325 + 12 above its 12 pt inset, full it stops
 * 60 + 12 under the top; a 56 header with the title centred in it and the
 * list right under it.
 */
data class StoryViewersSheetLayout(
    /** All in the sheet's own space. */
    val header: Rect,
    val title: Rect,
    val list: Rect,
) {
    object Metrics {
        const val header = 56.0
        const val corner = 10.0

        /** What Telegram keeps of the story above the half-open list. */
        const val story = 325.0 + 12
        const val minimumHeight = 100.0

        /** The full list's distance from the top. */
        const val fullTop = 60.0

        /** The list's own top inset inside Telegram's container. */
        const val inset = 12.0
    }

    companion object {
        /** How much of the sheet shows half open and full, for a container this tall. */
        fun half(height: Double): Double = max(Metrics.minimumHeight, height - Metrics.story) - Metrics.inset
        fun full(height: Double): Double = height - Metrics.fullTop - Metrics.inset

        /**
         * The sheet in its container with `shown` of it showing: between
         * `half` and `full` while dragged; less than `half` while it closes,
         * when it keeps its half height and slides down.
         */
        fun sheet(container: Size, shown: Double): Rect = Rect(0.0, container.height - shown, container.width, max(shown, half(container.height)))

        /** Inside a sheet this big. */
        operator fun invoke(size: Size, titleSize: Size): StoryViewersSheetLayout {
            val m = Metrics
            val header = Rect(0.0, 0.0, size.width, m.header)
            val titleWidth = min(ceil(titleSize.width), max(0.0, size.width - 32))
            val title = Rect(floor((size.width - titleWidth) / 2), floor((m.header - ceil(titleSize.height)) / 2), titleWidth, ceil(titleSize.height))
            val list = Rect(0.0, m.header, size.width, max(0.0, size.height - m.header))
            return StoryViewersSheetLayout(header, title, list)
        }
    }
}

/**
 * One row of the sheet listing who saw one's own story (happy-ios
 * `StoryViewersRowLayout`), measured as Telegram's story view list draws
 * its rows (`PeerListItemComponent` in its `.generic` style): 60 tall with
 * a subtitle and 50 without, 1 in from top and bottom; a 40 avatar 10 in,
 * the name in 17 pt semibold 62 in and the subtitle in 15 pt 2 under it,
 * centred together; the reaction 22 square 14 from the right edge; the
 * separator from the name to 16 short of the right edge.
 */
data class StoryViewersRowLayout(
    val height: Double,
    val avatar: Rect,
    val title: Rect,
    val subtitle: Rect?,
    val reaction: Rect?,
    /** The separator's insets from the row's left and right edges. */
    val separatorLeading: Double,
    val separatorTrailing: Double,
) {
    object Metrics {
        const val height = 60.0
        const val plainHeight = 50.0
        const val inset = 1.0
        const val avatar = 40.0
        const val avatarLeading = 10.0
        const val textLeading = 62.0
        const val textTrailing = 8.0

        /** What the reaction takes from the text's width besides. */
        const val reactionRoom = 32.0
        const val reaction = 22.0
        const val reactionTrailing = 14.0
        const val titleSize = 17.0
        const val subtitleSize = 15.0
        const val spacing = 2.0
        const val separatorTrailing = 16.0
    }

    companion object {
        /**
         * `titleHeight` and `subtitleHeight` are one line of each font;
         * `subtitleHeight` is null for a viewer with nothing under their name.
         */
        operator fun invoke(width: Double, titleHeight: Double, subtitleHeight: Double?, reacted: Boolean): StoryViewersRowLayout {
            val m = Metrics
            val height = if (subtitleHeight == null) m.plainHeight else m.height
            val inner = height - m.inset * 2
            val avatar = Rect(m.avatarLeading, m.inset + floor((inner - m.avatar) / 2), m.avatar, m.avatar)
            val textWidth = max(0.0, width - m.textLeading - m.textTrailing - if (reacted) m.reactionRoom else 0.0)
            val titleH = ceil(titleHeight)
            val subtitleH = subtitleHeight?.let { ceil(it) }
            val content = titleH + (subtitleH?.let { it + m.spacing } ?: 0.0)
            val title = Rect(m.textLeading, m.inset + floor((inner - content) / 2), textWidth, titleH)
            val subtitle = subtitleH?.let { Rect(m.textLeading, title.maxY + m.spacing, textWidth, it) }
            val reaction = if (reacted) Rect(width - m.reactionTrailing - m.reaction, m.inset + floor((inner - m.reaction) / 2), m.reaction, m.reaction) else null
            return StoryViewersRowLayout(height, avatar, title, subtitle, reaction, m.textLeading, m.separatorTrailing)
        }
    }
}

/**
 * A vertical drag on the viewer (happy-ios `StoryViewerDrag`), as
 * Telegram's `StoryItemSetContainerComponent` follows one: down, the story
 * goes with the finger one to one and shrinks to 0.6 by a screen's height,
 * the black behind it fading to half; let go past 200, or a flick down past
 * 5, it closes. Up, on one's own story, a drag past 200 or a flick past 100
 * opens who has seen it.
 */
object StoryViewerDrag {
    object Metrics {
        const val minScale = 0.6

        /** The black behind at a full fraction, and never under. */
        const val groundFloor = 0.2
        const val groundMin = 0.5
        const val closeDistance = 200.0
        const val closeFlickDistance = 5.0
        const val closeFlickSpeed = 200.0
        const val viewersDistance = 200.0
        const val viewersFlickDistance = 100.0
        const val viewersFlickSpeed = 100.0
    }

    data class State(
        /** How far down the story sits, and its scale about its middle. */
        val offset: Double,
        val scale: Double,
        /** The black behind it. */
        val groundAlpha: Double,
    )

    /** Where a drag of `translation` down puts the story on a screen `height` tall. */
    fun state(translation: Double, height: Double): State {
        val m = Metrics
        val fraction = max(0.0, min(1.0, translation / max(1.0, height)))
        val ground = (1 - fraction) + m.groundFloor * fraction
        return State(fraction * height, (1 - fraction) + m.minScale * fraction, max(m.groundMin, min(1.0, ground)))
    }

    enum class Release { Close, Viewers, Stay }

    /** What letting go after `translation` at `velocity` (both down positive) does. */
    fun release(translation: Double, velocity: Double, canShowViewers: Boolean): Release {
        val m = Metrics
        if (translation > m.closeDistance || (translation > m.closeFlickDistance && velocity > m.closeFlickSpeed)) return Release.Close
        if (canShowViewers && (translation < -m.viewersDistance || (translation < -m.viewersFlickDistance && velocity < -m.viewersFlickSpeed))) return Release.Viewers
        return Release.Stay
    }
}

/**
 * Where the viewer starts as it opens from what was tapped, and ends as it
 * closes back into it (happy-ios `StoryOpeningLayout`), as Telegram's does
 * (`StoryItemSetContainerComponent` `animateIn` / `animateOut`): the story
 * shows through a window that is the source itself, a circle for a row's
 * avatar, its picture as wide as that window and hung from its top; the
 * author's avatar sits on the source. All of it springs to its place
 * together.
 *
 *     start    ( ⦿ )  the source, the picture's top in it
 *     end      ┌───────┐ the canvas, 12 rounded
 *              │ ⦿ name│
 *              │       │
 *              └───────┘
 */
data class StoryOpeningLayout(
    /** The window the story shows through at the start, and its corners. */
    val window: Rect,
    val windowRadius: Double,
    /** The canvas at the start: as wide as the window, from its top. */
    val canvas: Placement,
    /**
     * The author's avatar at the start: on the source when that is their
     * avatar (Telegram's `sourceIsAvatar`); otherwise it fades with the
     * controls.
     */
    val avatar: Placement?,
    /** How far the bar under the story starts below where it ends. */
    val barDrop: Double,
) {
    object Metrics {
        /**
         * Telegram's `kCAMediaTimingFunctionSpring` at any length but 0.5
         * (and iOS 26's 0.3832): cubic-bezier(0.38, 0.7, 0.125, 1), here
         * over 0.3 s.
         */
        const val duration = 0.3
        val curve = Point(0.38, 0.7) to Point(0.125, 1.0)

        /**
         * The ground fades in over 0.28 and out over 0.25, eased; the
         * controls over the story over 0.25.
         */
        const val groundIn = 0.28
        const val groundOut = 0.25
        const val controlsFade = 0.25

        /**
         * The bar under the story rises from below the screen, more
         * slowly opening, as Telegram's input panel does.
         */
        const val barIn = 0.48
        const val barOut = 0.3
    }

    /** A view's place against where it is laid out: scaled about its middle, then moved. */
    data class Placement(val scale: Double, val offset: Point) {
        companion object {
            val identity = Placement(1.0, Point.Zero)

            /** What puts a view laid out at `from` on `to`, by width. */
            fun of(from: Rect, to: Rect) = Placement(if (from.width > 0) to.width / from.width else 1.0, Point(to.midX - from.midX, to.midY - from.midY))
        }
    }

    companion object {
        /**
         * From `source` (the tapped avatar, or a card), rounded `sourceRadius`,
         * into `viewer`'s layout of a screen `height` tall.
         */
        operator fun invoke(source: Rect, sourceRadius: Double, sourceIsAvatar: Boolean, viewer: StoryViewerLayout, height: Double): StoryOpeningLayout {
            val canvas = viewer.canvas
            val scale = if (canvas.width > 0) source.width / canvas.width else 1.0
            val start = Rect(source.minX, source.minY, source.width, canvas.height * scale)
            return StoryOpeningLayout(
                window = source,
                windowRadius = min(sourceRadius, min(source.width, source.height) / 2),
                canvas = Placement.of(canvas, start),
                avatar = if (sourceIsAvatar) Placement.of(viewer.avatar, source) else null,
                barDrop = max(0.0, height - viewer.heart.minY),
            )
        }
    }
}

/**
 * Telegram's effect as a story is reacted to (happy-ios
 * `StoryReactionBurst`; `StandaloneReactionAnimation`, not large, its centre
 * hidden): a 120 square centred on the heart or the bubble tapped, and the
 * effect drawn in the 240 around that, once. Its generic effect throws
 * seven copies of the reaction out of the middle; ours does the same here:
 * each from the middle to a point on a ring, rising as it goes, growing to
 * its peak then shrinking and fading.
 */
data class StoryReactionBurst(
    /** The effect's square, centred on what was tapped. */
    val effect: Rect,
    val particles: List<Particle>,
) {
    object Metrics {
        const val expanded = 120.0
        const val effect = 240.0
        const val count = 7
        const val duration = 0.9

        /** A copy's side at its peak. */
        const val particle = 30.0

        /** How far up each rises beyond its ring point, as the effect floats. */
        const val rise = 26.0

        /** Where they start round the ring, straight up first. */
        const val firstAngle = -PI / 2

        /**
         * Each copy's reach, of the effect's half less a copy's half, and
         * its start, a beat after the one before.
         */
        val reach = listOf(1.0, 0.82, 0.94, 0.76, 0.9, 0.8, 0.96)
        val peak = listOf(1.0, 0.8, 0.9, 0.72, 0.86, 0.78, 0.94)
        const val stagger = 0.03
    }

    data class Particle(
        val start: Point,
        val end: Point,
        /** Of `Metrics.particle`, at the top of its arc. */
        val peakScale: Double,
        val rotation: Double,
        val delay: Double,
        val duration: Double,
    )

    companion object {
        operator fun invoke(target: Rect): StoryReactionBurst {
            val m = Metrics
            val center = Point(target.midX, target.midY)
            val effect = Rect(center.x - m.effect / 2, center.y - m.effect / 2, m.effect, m.effect)
            val radius = m.effect / 2 - m.particle / 2
            val particles = (0 until m.count).map { index ->
                val angle = m.firstAngle + index * 2 * PI / m.count
                val reach = radius * m.reach[index % m.reach.size]
                val end = Point(center.x + cos(angle) * reach, center.y + sin(angle) * reach - m.rise)
                val delay = index * m.stagger
                Particle(
                    start = center, end = end, peakScale = m.peak[index % m.peak.size],
                    rotation = (if (index % 2 == 0) 1 else -1) * (index + 1) * 0.06,
                    delay = delay, duration = m.duration - delay,
                )
            }
            return StoryReactionBurst(effect, particles)
        }
    }
}

/**
 * A story drawn inline in a conversation (happy-ios `StoryCardLayout`;
 * happy-agent `API.md` "The inline draft"): its first frame on the 9:16
 * canvas, at most 200 wide, rounded as the viewer's, and a 32 tall bar
 * under it with Post and Edit, or "Draft", "Posted", or why it can't be
 * shown. One size whatever its state, so the conversation never moves when
 * it changes.
 */
data class StoryCardLayout(
    val size: Size,
    val canvas: Rect,
    val bar: Rect,
    val post: Rect,
    val edit: Rect,
    /** "Draft" or "Posted": the bar's width, after the ring when posted. */
    val label: Rect,
    val ring: Rect,
    val postedLabel: Rect,
    /** A story no longer there: its mark, and where its words start. */
    val notice: Rect,
    val noticeText: Rect,
) {
    object Metrics {
        const val maxWidth = 200.0
        const val cornerRadius = 12.0
        const val barGap = 6.0
        const val bar = 32.0
        val button = Size(56, 32)
        const val buttonGap = 4.0

        /**
         * A story no longer there: its mark, middle of the canvas, the
         * words 10 under it, 16 in from the sides; as Telegram marks an
         * expired story, the mark beside the words, here above them.
         */
        const val notice = 36.0
        const val noticeGap = 10.0
        const val noticeInset = 16.0

        /** "Posted" follows a story ring as the row draws one, 14 across. */
        const val ring = 14.0
        const val ringGap = 6.0
    }

    companion object {
        operator fun invoke(width: Double): StoryCardLayout {
            val m = Metrics
            val canvasWidth = floor(min(m.maxWidth, max(0.0, width)))
            val canvas = Rect(0.0, 0.0, canvasWidth, floor(canvasWidth / StoryCanvasLayout.aspect))
            val bar = Rect(0.0, canvas.maxY + m.barGap, canvasWidth, m.bar)
            val post = Rect(bar.x, bar.y, m.button.width, m.button.height)
            val edit = post.offsetBy(m.button.width + m.buttonGap, 0.0)
            val ring = Rect(0.0, bar.midY - m.ring / 2, m.ring, m.ring)
            val postedLabel = Rect(ring.maxX + m.ringGap, bar.minY, max(0.0, canvasWidth - ring.maxX - m.ringGap), m.bar)
            val notice = Rect(floor((canvasWidth - m.notice) / 2), floor(canvas.midY - m.notice), m.notice, m.notice)
            val noticeText = Rect(m.noticeInset, notice.maxY + m.noticeGap, max(0.0, canvasWidth - 2 * m.noticeInset), max(0.0, canvas.maxY - notice.maxY - m.noticeGap - m.noticeInset))
            return StoryCardLayout(Size(canvasWidth, bar.maxY), canvas, bar, post, edit, bar, ring, postedLabel, notice, noticeText)
        }

        fun size(width: Double): Size = StoryCardLayout(width).size
    }
}

/**
 * Telegram's one-time "Tap above to view stories from …" under the avatars
 * closed into the title (happy-ios `StoriesTooltipLayout`; `TooltipScreen`,
 * default style, arrow at the top): a bubble 12 in from the screen's sides
 * at most, 11 × 8 around 14pt text wrapped as evenly as its lines allow,
 * 12.5 rounded, with a 29 × 10 arrow pointing up from it, its tip at `tip`.
 */
data class StoriesTooltipLayout(
    /** The bubble, without its arrow. */
    val bubble: Rect,
    val text: Rect,
    /** The arrow's box above the bubble; its tip is the top middle. */
    val arrow: Rect,
) {
    object Metrics {
        const val sideInset = 12.0
        val contentInset = Size(11, 8)
        const val cornerRadius = 12.5
        val arrow = Size(29, 10)
        const val maxWidth = 614.0
        const val fontSize = 14.0
    }

    companion object {
        /**
         * Pointing at `tip` on a screen `width` wide; `measure` gives the
         * text's size for a width.
         */
        operator fun invoke(tip: Point, width: Double, measure: (Double) -> Size): StoriesTooltipLayout {
            val m = Metrics
            val room = max(0.0, min(width - 2 * m.sideInset, m.maxWidth) - 2 * m.contentInset.width)
            val size = balanced(room, measure)
            val textSize = Size(ceil(min(room, size.width)), ceil(size.height))
            val bubbleSize = Size(textSize.width + 2 * m.contentInset.width, textSize.height + 2 * m.contentInset.height)
            val top = tip.y + m.arrow.height
            val x = max(m.sideInset, min(width - m.sideInset - bubbleSize.width, tip.x - bubbleSize.width / 2))
            val bubble = Rect(x, top, bubbleSize.width, bubbleSize.height)
            val text = Rect(x + m.contentInset.width, top + m.contentInset.height, textSize.width, textSize.height)
            // The arrow stays over the bubble, clear of its rounded corners.
            val low = bubble.minX + m.cornerRadius + m.arrow.width / 2
            val high = bubble.maxX - m.cornerRadius - m.arrow.width / 2
            val middle = if (low <= high) max(low, min(high, tip.x)) else bubble.midX
            return StoriesTooltipLayout(bubble, text, Rect(middle - m.arrow.width / 2, tip.y, m.arrow.width, m.arrow.height))
        }

        /**
         * The text at `room`, or as much narrower as it goes without taking
         * another line, so a wrapped last line isn't left short.
         */
        internal fun balanced(room: Double, measure: (Double) -> Size): Size {
            val full = measure(room)
            if (full.width <= 0) return full
            var low = 0.0
            var high = min(room, ceil(full.width))
            while (high - low > 1) {
                val mid = floor((low + high) / 2)
                if (measure(mid).height <= full.height) high = mid else low = mid
            }
            return measure(high)
        }
    }
}
