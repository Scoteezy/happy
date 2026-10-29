package com.slopus.happy.layout.stories

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.assertClose
import com.slopus.happy.layout.rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Ported from happy-ios Tests/HappyLayoutTests/StoryViewerLayoutTests.swift,
// StoryViewersLayoutTests, StoryOpeningLayoutTests, StoryReactionBurstTests,
// StoryViewerDragTests, StoryCardLayoutTests and StoriesTooltipLayoutTests.

class StoryViewerLayoutTest {
    private val phone = StoryViewerLayout(Size(402, 874), LayoutInsets(top = 62, bottom = 34))

    /**
     * An iPhone 17 Pro: the canvas fills the width under the status bar,
     * the bar takes what is left below it.
     */
    @Test fun aPhone() {
        val layout = phone
        assertEquals(rect(0, 62, 402, 714), layout.canvas)
        assertEquals(rect(8, 70, 386, 2), layout.strip)
        assertEquals(rect(12, 80, 32, 32), layout.avatar)
        assertEquals(rect(352, 64, 50, 64), layout.close)
        assertEquals(rect(310, 64, 50, 64), layout.more)
        assertEquals(rect(265, 64, 50, 64), layout.sound)
        assertEquals(Point(54.0, 79.0), layout.titleOrigin)
        assertClose(256, layout.titleWidth)
        assertClose(211, layout.titleWidth(withSound = true))
        assertEquals(rect(0, 776, 402, 64), layout.bar)
        assertEquals(rect(350, 780, 44, 44), layout.heart)
        assertEquals(rect(8, 780, 201, 44), layout.views)
    }

    /** A tablet: held by the height, the canvas is centred across. */
    @Test fun aTablet() {
        val layout = StoryViewerLayout(Size(1024, 1366), LayoutInsets(top = 24, bottom = 20))
        assertEquals(rect(154.8125, 24, 714, 1270), layout.canvas)
        assertEquals(512.0, layout.canvas.midX, 0.5)
    }

    @Test fun segmentsShareTheStrip() {
        val strip = rect(8, 70, 386, 2)
        assertEquals(listOf(strip), StoryViewerLayout.segments(1, strip))
        val three = StoryViewerLayout.segments(3, strip)
        for ((index, segment) in three.withIndex()) assertEquals(8 + index * (380.0 / 3 + 3), segment.minX, 0.001)
        assertEquals(strip.maxX, three.last().maxX, 0.001)
        // Thirty frames: never thinner than 2.
        assertTrue(StoryViewerLayout.segments(200, strip).all { it.width >= 2 })
        assertEquals(emptyList<Rect>(), StoryViewerLayout.segments(0, strip))
    }

    @Test fun tapsByThirds() {
        assertEquals(StoryViewerLayout.Tap.Back, phone.tap(10.0))
        assertEquals(StoryViewerLayout.Tap.Back, phone.tap(133.0))
        assertEquals(StoryViewerLayout.Tap.Forward, phone.tap(135.0))
        assertEquals(StoryViewerLayout.Tap.Forward, phone.tap(400.0))
    }

    @Test fun theCubeTurnsAQuarter() {
        assertEquals(PI / 2, StoryCube.angle(1.0), 0.0)
        assertEquals(-PI / 4, StoryCube.angle(-0.5), 0.0)
        assertEquals(PI / 2, StoryCube.angle(3.0), 0.0)
        assertClose(500, StoryCube.perspective)
    }
}

class StoryCanvasLayoutTest {
    private val canvas = Size(360, 640)

    @Test fun fitHoldsNineBySixteen() {
        assertEquals(rect(0, 10, 360, 640), StoryCanvasLayout.fit(rect(0, 10, 360, 1000)))
        assertEquals(rect(320, 0, 360, 640), StoryCanvasLayout.fit(rect(0, 0, 1000, 640)))
        assertEquals(Rect.Zero, StoryCanvasLayout.fit(Rect.Zero))
    }

    /** A screenshot fills the width at scale 1, with bands above and below. */
    @Test fun mediaIsFittedWholeAndCentred() {
        val wide = StoryCanvasLayout.media(Size(1920, 1080), 0.5, 0.5, 1.0, canvas)
        assertEquals(rect(0, 218.75, 360, 202.5), wide)
        assertEquals(rect(0, 0, 360, 640), StoryCanvasLayout.media(Size(1080, 1920), 0.5, 0.5, 1.0, canvas))
        assertEquals(rect(-270, -40, 720, 720), StoryCanvasLayout.media(Size(100, 100), 0.25, 0.5, 2.0, canvas))
        // Not measured yet: as a 16:9 picture.
        assertEquals(wide, StoryCanvasLayout.media(null, 0.5, 0.5, 1.0, canvas))
    }

    @Test fun sizesAreFractionsOfTheWidth() {
        assertClose(22.5, StoryCanvasLayout.textFontSize(canvas))
        assertClose(30, StoryCanvasLayout.linkHeight(canvas))
        assertClose(288, StoryCanvasLayout.textWrap(0.8, canvas))
        assertClose(360, StoryCanvasLayout.textWrap(5.0, canvas))
        assertEquals(Size(180, 90), StoryCanvasLayout.sticker(Size(512, 256), 0.5, canvas))
        assertEquals(Size(90, 180), StoryCanvasLayout.sticker(Size(256, 512), 0.5, canvas))
        assertEquals(Size(90, 90), StoryCanvasLayout.reaction(0.25, canvas))
        assertEquals(Point(180.0, 160.0), StoryCanvasLayout.center(0.5, 0.25, canvas))
    }

    @Test fun theCaptionSitsAtTheFoot() {
        val caption = StoryCanvasLayout.caption(39.2, canvas = canvas)
        assertEquals(rect(16, 584, 328, 40), caption.text)
        assertEquals(rect(0, 520, 360, 120), caption.fade)
        assertEquals(rect(0, 0, 360, 632), caption.scroll)
    }

    @Test fun anOpenCaptionGrowsUpFromItsLastLine() {
        // Folded to three lines; nine in full fit under the header.
        val caption = StoryCanvasLayout.caption(60.0, 179.5, canvas)
        assertEquals(rect(16, 564, 328, 60), caption.text)
        assertEquals(rect(16, 444, 328, 180), caption.openText)
        assertEquals(rect(0, 380, 360, 260), caption.shade)
        assertEquals(Size(360, 632), caption.content)
        assertFalse(caption.scrolls)
    }

    @Test fun aLongOpenCaptionStartsUnderTheHeaderAndScrolls() {
        val caption = StoryCanvasLayout.caption(60.0, 900.0, canvas, bottomInset = 60.0, topInset = 72.0)
        assertEquals(rect(16, 520, 328, 60), caption.text)
        assertEquals(rect(0, 0, 360, 588), caption.scroll)
        assertEquals(rect(16, 72, 328, 900), caption.openText)
        assertEquals(rect(0, 8, 360, 632), caption.shade)
        assertEquals(Size(360, 980), caption.content)
        assertTrue(caption.scrolls)
    }
}

class StoryViewersLayoutTest {
    @Test fun theHalfOpenSheetRunsEdgeToEdgeToTheFootUnderA56Header() {
        val container = Size(402, 874)
        assertClose(525, StoryViewersSheetLayout.half(874.0))
        assertClose(802, StoryViewersSheetLayout.full(874.0))
        assertEquals(rect(0, 349, 402, 525), StoryViewersSheetLayout.sheet(container, 525.0))
        assertEquals(rect(0, 72, 402, 802), StoryViewersSheetLayout.sheet(container, 802.0))
        val inside = StoryViewersSheetLayout(Size(402, 525), Size(61.4, 20.3))
        assertEquals(rect(0, 0, 402, 56), inside.header)
        assertEquals(rect(170, 17, 62, 21), inside.title)
        assertEquals(rect(0, 56, 402, 469), inside.list)
    }

    @Test fun aClosingSheetKeepsItsHalfHeightAndSlidesDown() {
        assertEquals(rect(0, 674, 402, 525), StoryViewersSheetLayout.sheet(Size(402, 874), 200.0))
        assertClose(874, StoryViewersSheetLayout.sheet(Size(402, 874), 0.0).minY)
    }

    @Test fun aShortScreenStillShowsSomeOfTheList() {
        assertClose(88, StoryViewersSheetLayout.half(400.0))
    }

    @Test fun aReactorsRowCentresNameAndTimeBesideTheAvatarWithTheReactionRight() {
        val row = StoryViewersRowLayout(393.0, 20.3, 17.9, reacted = true)
        assertClose(60, row.height)
        assertEquals(rect(10, 10, 40, 40), row.avatar)
        // 21 + 2 + 18 = 41 tall, centred in the inner 58.
        assertEquals(rect(62, 9, 291, 21), row.title)
        assertEquals(rect(62, 32, 291, 18), row.subtitle)
        assertEquals(rect(357, 19, 22, 22), row.reaction)
        assertClose(62, row.separatorLeading)
        assertClose(16, row.separatorTrailing)
    }

    @Test fun aViewerWithNothingUnderTheirNameHasAShorterRowAndTheNameCentred() {
        val row = StoryViewersRowLayout(393.0, 20.3, null, reacted = false)
        assertClose(50, row.height)
        assertEquals(rect(10, 5, 40, 40), row.avatar)
        assertEquals(rect(62, 14, 323, 21), row.title)
        assertNull(row.subtitle)
        assertNull(row.reaction)
    }

    @Test fun aNarrowRowNeverGivesTheTextANegativeWidth() {
        val row = StoryViewersRowLayout(80.0, 20.0, 18.0, reacted = true)
        assertClose(0, row.title.width)
        assertClose(0, row.subtitle!!.width)
    }
}

/** The viewer opening from a row's 52 avatar on a 402 × 874 phone, as Telegram's `animateIn` starts it. */
class StoryOpeningLayoutTest {
    private val viewer = StoryViewerLayout(Size(402, 874), LayoutInsets(top = 62, bottom = 34))

    @Test fun fromACircle() {
        val avatar = rect(171, 158, 52, 52)
        val opening = StoryOpeningLayout(avatar, 26.0, sourceIsAvatar = true, viewer = viewer, height = 874.0)
        // The story shows through the circle itself.
        assertEquals(avatar, opening.window)
        assertClose(26, opening.windowRadius)
        // The canvas (0, 62, 402 × 714) as wide as the circle, from its top:
        // 52 × 92.36 at (171, 158), its middle at (197, 204.18).
        assertEquals(52.0 / 402, opening.canvas.scale, 0.000001)
        assertEquals((197 - 201).toDouble(), opening.canvas.offset.x, 0.0001)
        assertEquals(158 + 714 * 26.0 / 402 - 419, opening.canvas.offset.y, 0.0001)
        // The author's 32 avatar at (12, 80) starts on the circle.
        assertEquals(StoryOpeningLayout.Placement(1.625, Point(169.0, 88.0)), opening.avatar)
        // The bar (its buttons from 780) rises from just under the screen.
        assertClose(94, opening.barDrop)
    }

    /** A card's corners are kept; a radius past half its side is a circle. */
    @Test fun fromACard() {
        val card = rect(40, 300, 120, 213)
        val opening = StoryOpeningLayout(card, 10.0, sourceIsAvatar = false, viewer = viewer, height = 874.0)
        assertClose(10, opening.windowRadius)
        // Not an avatar: the author's avatar stays put, fading.
        assertNull(opening.avatar)
        assertClose(60, StoryOpeningLayout(card, 400.0, sourceIsAvatar = false, viewer = viewer, height = 874.0).windowRadius)
    }

    @Test fun placement() {
        assertEquals(StoryOpeningLayout.Placement(0.5, Point(-15.0, 7.5)), StoryOpeningLayout.Placement.of(rect(0, 0, 100, 50), rect(10, 20, 50, 25)))
    }
}

/** The effect as a story is reacted to, around the heart or the bubble, as Telegram's `StandaloneReactionAnimation` sizes it. */
class StoryReactionBurstTest {
    /**
     * 240 around the middle of what was tapped, as Telegram's effect frame
     * is its 120 square inset by half again each side.
     */
    @Test fun effectAroundTheTarget() {
        val burst = StoryReactionBurst(rect(340, 780, 44, 44))
        assertEquals(rect(242, 682, 240, 240), burst.effect)
        assertEquals(7, burst.particles.size)
        for (particle in burst.particles) assertEquals(Point(362.0, 802.0), particle.start)
    }

    /**
     * The first goes straight up, the full reach plus the rise; the others
     * round the ring, each a beat later, all ending with the effect.
     */
    @Test fun thrownOutAndRising() {
        val burst = StoryReactionBurst(rect(0, 0, 40, 40))
        val first = burst.particles[0]
        assertEquals(20.0, first.end.x, 0.001)
        assertEquals((20 - 105 - 26).toDouble(), first.end.y, 0.001)
        assertClose(1, first.peakScale)
        assertClose(0, first.delay)
        val third = burst.particles[2]
        val angle = -PI / 2 + 2 * 2 * PI / 7
        assertEquals(20 + cos(angle) * 105 * 0.94, third.end.x, 0.001)
        assertEquals(20 + sin(angle) * 105 * 0.94 - 26, third.end.y, 0.001)
        assertEquals(0.06, third.delay, 0.0001)
        for (particle in burst.particles) {
            assertEquals(0.9, particle.delay + particle.duration, 0.0001)
            // Every copy stays within the effect, but for the rise.
            assertTrue(abs(particle.end.x - 20) <= 105.001)
        }
    }
}

/** The viewer under a vertical drag, in Telegram's numbers. */
class StoryViewerDragTest {
    @Test fun followsTheFingerAndShrinks() {
        val rest = StoryViewerDrag.state(0.0, 874.0)
        assertEquals(StoryViewerDrag.State(0.0, 1.0, 1.0), rest)
        val quarter = StoryViewerDrag.state(218.5, 874.0)
        assertEquals(218.5, quarter.offset, 0.001)
        assertEquals(0.9, quarter.scale, 0.001)
        assertEquals(0.8, quarter.groundAlpha, 0.001)
        // The black never goes under half; up never moves it.
        assertClose(0.5, StoryViewerDrag.state(800.0, 874.0).groundAlpha)
        assertEquals(rest, StoryViewerDrag.state(-100.0, 874.0))
        assertEquals(0.6, StoryViewerDrag.state(2000.0, 874.0).scale, 0.001)
    }

    @Test fun lettingGo() {
        assertEquals(StoryViewerDrag.Release.Close, StoryViewerDrag.release(201.0, 0.0, canShowViewers = false))
        assertEquals(StoryViewerDrag.Release.Close, StoryViewerDrag.release(6.0, 201.0, canShowViewers = false))
        assertEquals(StoryViewerDrag.Release.Stay, StoryViewerDrag.release(150.0, 100.0, canShowViewers = false))
        assertEquals(StoryViewerDrag.Release.Viewers, StoryViewerDrag.release(-201.0, 0.0, canShowViewers = true))
        assertEquals(StoryViewerDrag.Release.Viewers, StoryViewerDrag.release(-101.0, -101.0, canShowViewers = true))
        assertEquals(StoryViewerDrag.Release.Stay, StoryViewerDrag.release(-300.0, -500.0, canShowViewers = false))
        assertEquals(StoryViewerDrag.Release.Stay, StoryViewerDrag.release(-150.0, 0.0, canShowViewers = true))
    }
}

class StoryCardLayoutTest {
    /** At most 200 wide, 9:16, with its 32 bar 6 under it. */
    @Test fun theCardIsTheCanvasAndItsBar() {
        val layout = StoryCardLayout(358.0)
        assertEquals(rect(0, 0, 200, 355), layout.canvas)
        assertEquals(rect(0, 361, 200, 32), layout.bar)
        assertEquals(rect(0, 361, 56, 32), layout.post)
        assertEquals(rect(60, 361, 56, 32), layout.edit)
        assertEquals(layout.bar, layout.label)
        assertEquals(Size(200, 393), layout.size)
    }

    /**
     * Posted: a 14 ring, then the word. Gone: a 36 mark over the canvas's
     * middle, the words 10 under it.
     */
    @Test fun postedAndGone() {
        val layout = StoryCardLayout(358.0)
        assertEquals(rect(0, 370, 14, 14), layout.ring)
        assertEquals(rect(20, 361, 180, 32), layout.postedLabel)
        assertEquals(rect(82, 141, 36, 36), layout.notice)
        assertEquals(rect(16, 187, 168, 152), layout.noticeText)
    }

    @Test fun aNarrowColumnShrinksIt() {
        assertEquals(rect(0, 0, 150, 266), StoryCardLayout(150.6).canvas)
        assertEquals(Size(150, 304), StoryCardLayout.size(150.6))
        assertEquals(Size(0, 38), StoryCardLayout.size(-5.0))
    }

    // iOS's testAStorySitsUnderItsMessage pins StemCellMessageLayout; it
    // comes with the Stem Cell chat.
}

/** The one-time stories tooltip under the closed avatars, as Telegram's `TooltipScreen` places it. */
class StoriesTooltipLayoutTest {
    @Test fun underTheAvatars() {
        val layout = StoriesTooltipLayout(Point(157.0, 103.0), 402.0) { Size(200.4, 33.2) }
        assertEquals(rect(142.5, 103, 29, 10), layout.arrow)
        assertEquals(rect(45.5, 113, 223, 50), layout.bubble)
        assertEquals(rect(56.5, 121, 201, 34), layout.text)
    }

    /**
     * Kept 12 in from the side, the arrow still over the avatars, clear of
     * the bubble's corners; long text wraps within the screen.
     */
    @Test fun keptOnScreen() {
        var widest = 0.0
        val layout = StoriesTooltipLayout(Point(22.0, 103.0), 402.0) { width ->
            widest = max(widest, width)
            Size(600, 50)
        }
        assertClose(402 - 24 - 22, widest)
        assertClose(12, layout.bubble.minX)
        assertClose(390, layout.bubble.maxX)
        assertClose(12 + 12.5 + 14.5, layout.arrow.midX)
    }

    /**
     * Text that wraps is laid out as narrow as it goes on as many lines,
     * so the lines come out even, as Telegram's balanced text does.
     */
    @Test fun balancedLines() {
        val layout = StoriesTooltipLayout(Point(201.0, 103.0), 402.0) { width -> Size(min(500.0, width), 17.0 * ceil(500 / width)) }
        assertEquals(Size(250, 34), layout.text.size)
        assertEquals(rect(65, 113, 272, 50), layout.bubble)
    }
}
