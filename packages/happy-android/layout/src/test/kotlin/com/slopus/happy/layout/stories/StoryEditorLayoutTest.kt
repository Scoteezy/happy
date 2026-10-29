package com.slopus.happy.layout.stories

import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.assertClose
import com.slopus.happy.layout.rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

// Ported from happy-ios Tests/HappyLayoutTests/StoryEditorLayoutTests.swift.

class StoryEditorLayoutTest {
    private val phone = StoryEditorLayout(rect(0, 0, 393, 852), statusBar = 54.0, homeIndicator = 34.0, postContent = 60.0)

    /**
     * An iPhone 16, as Telegram's story editor: the canvas 5 under the
     * status bar, the caption over its foot, back, tools and Post 8 under it.
     */
    @Test fun aPhone() {
        val layout = phone
        assertEquals(rect(0, 59, 393, 698), layout.canvas)
        assertEquals(rect(16, 765, 44, 44), layout.close)
        assertEquals(rect(292, 765, 85, 44), layout.post)
        assertEquals(rect(127, 765, 98, 44), layout.tools)
        assertEquals(listOf(rect(0, 0, 44, 44), rect(54, 0, 44, 44)), layout.toolButtons)
        assertEquals(rect(0, 694, 393, 60), layout.captionPanel)
        assertEquals(rect(9, 708, 375, 40), layout.caption)
        assertEquals(rect(348, 661, 33, 33), layout.framesButton)
        assertEquals(393.0 * 180 / 1080, layout.bin.width, 1.0e-9)
        assertEquals(196.5, layout.bin.midX, 1.0e-9)
        assertEquals(757 - 393.0 * 20 / 1080, layout.bin.maxY, 1.0e-9)
        assertEquals(393.0 * 160 / 1080, layout.binCircle.width, 1.0e-9)
    }

    /** A home-button phone: the canvas from the top, the bar lifted 62 over it. */
    @Test fun aShortPhone() {
        val layout = StoryEditorLayout(rect(0, 0, 375, 667), statusBar = 20.0, homeIndicator = 0.0, postContent = 60.0)
        assertEquals(rect(0, 0, 375, 666), layout.canvas)
        assertEquals(rect(9, 612, 44, 44), layout.close)
        assertClose(541, layout.captionPanel.minY)
    }

    /** A tablet: the canvas held by the height, centred, 30 from the sides. */
    @Test fun aTablet() {
        val layout = StoryEditorLayout(rect(0, 0, 1024, 1366), statusBar = 24.0, homeIndicator = 20.0, postContent = 60.0, tablet = true)
        assertEquals(rect(157, 29, 709, 1262), layout.canvas)
        assertClose(30, layout.close.minX)
        assertClose(1299, layout.close.minY)
    }

    /**
     * The frames panel: cards 95 by 112, 4 apart, the add card last, its
     * right edge on the frames button's, 10 over it.
     */
    @Test fun theFramesPanel() {
        val panel = phone.panel(393.0, frames = 2, canAdd = true)
        assertEquals(rect(80, 531, 301, 120), panel.frame)
        assertEquals(listOf(rect(4, 4, 95, 112), rect(103, 4, 95, 112)), panel.items)
        assertEquals(rect(202, 4, 95, 112), panel.add)
        val full = phone.panel(393.0, frames = 10, canAdd = false)
        assertNull(full.add)
        assertEquals(rect(12, 531, 369, 120), full.frame)
        assertEquals(Size(994, 120), full.content)
    }
}

class StoryStickerPillsLayoutTest {
    /** LINK and the reaction bubble: one row, 30 apart, centred. */
    @Test fun twoItemsCentre() {
        val link = StoryStickerPillsLayout.pill(40.0)
        assertEquals(Size(78, 34), link)
        val layout = StoryStickerPillsLayout(393.0, listOf(link, Size(52, 52)))
        assertEquals(listOf(rect(116, 27, 78, 34), rect(224, 18, 52, 52)), layout.items)
        assertClose(76, layout.height)
    }

    /** Too many for a row: the rest wrap 12 under it. */
    @Test fun theyWrap() {
        val layout = StoryStickerPillsLayout(210.0, listOf(Size(80, 34), Size(80, 34), Size(80, 34)))
        assertEquals(rect(18, 18, 80, 34), layout.items[0])
        assertEquals(rect(112, 18, 80, 34), layout.items[1])
        assertEquals(rect(65, 64, 80, 34), layout.items[2])
    }
}

class StickerPickerLayoutTest {
    /**
     * A phone's picker: five sticker columns of 72 under a header with a ✕,
     * then eight emoji columns of 43 without one.
     */
    @Test fun sections() {
        val layout = StickerPickerLayout(
            393.0,
            listOf(
                StickerPickerLayout.Section(StickerPackKindLayout.Stickers, 7),
                StickerPickerLayout.Section(StickerPackKindLayout.Emoji, 3, header = false),
            ),
            clears = setOf(0),
        )
        assertEquals(rect(8, 0, 377, 32), layout.sections[0].header)
        assertEquals(rect(353, 0, 32, 32), layout.sections[0].clear)
        assertEquals(5, layout.sections[0].columns)
        assertEquals(listOf(8.0, 84.0, 160.0, 236.0, 312.0, 8.0, 84.0), layout.sections[0].cells.map { it.minX })
        assertEquals(rect(8, 32, 72, 72), layout.sections[0].cells[0])
        assertEquals(rect(8, 108, 72, 72), layout.sections[0].cells[5])
        assertNull(layout.sections[1].header)
        assertEquals(8, layout.sections[1].columns)
        assertEquals(listOf(rect(8, 188, 43, 43), rect(55, 188, 43, 43), rect(102, 188, 43, 43)), layout.sections[1].cells)
        assertClose(188 + 43 + 8 + 72, layout.height)
    }

    /** Wider grids take more columns, as the API's formula says. */
    @Test fun columnsGrowWithTheWidth() {
        assertEquals(5, StickerPickerLayout.columns(StickerPackKindLayout.Stickers, 300.0))
        assertEquals(10, StickerPickerLayout.columns(StickerPackKindLayout.Stickers, 736.0))
        assertEquals(8, StickerPickerLayout.columns(StickerPackKindLayout.Emoji, 300.0))
        assertEquals(16, StickerPickerLayout.columns(StickerPackKindLayout.Emoji, 700.0))
    }
}

class StorySnapperTest {
    private val canvas = Size(400, 800)

    /**
     * Within 2% of the width it snaps to the centre line, and holds there
     * until pulled twice as far.
     */
    @Test fun theCentreLineHoldsUntilPulledTwiceAsFar() {
        val snapper = StorySnapper()
        var result = snapper.snap(Point(205.0, 300.0), Size(30, 30), 0.3, canvas, edges = false)
        assertEquals(Point(200.0, 300.0), result.center)
        assertEquals(setOf(StorySnapper.Guide.CenterX), result.guides)
        assertTrue(result.snapped)
        result = snapper.snap(Point(214.0, 300.0), Size(30, 30), 0.3, canvas, edges = false)
        assertClose(200, result.center.x)
        assertFalse(result.snapped)
        result = snapper.snap(Point(217.0, 300.0), Size(30, 30), 0.3, canvas, edges = false)
        assertClose(217, result.center.x)
        assertEquals(emptySet<StorySnapper.Guide>(), result.guides)
    }

    /** A sticker's edge snaps 1/20 of the width in from the canvas's edge. */
    @Test fun aStickersEdgesSnapInsideTheCanvas() {
        val result = StorySnapper().snap(Point(55.0, 400.0), Size(30, 30), 0.3, canvas, edges = true)
        assertEquals(Point(50.0, 400.0), result.center)
        assertEquals(setOf(StorySnapper.Guide.Left, StorySnapper.Guide.CenterY), result.guides)
        assertFalse(result.turned)
        assertEquals(Point(55.0, 390.0), StorySnapper().snap(Point(55.0, 390.0), Size(30, 30), 0.3, canvas, edges = false).center)
    }

    /** Landing on an overlay already on a line holds it without a tick. */
    @Test fun landingOnALineDoesntTick() {
        val snapper = StorySnapper()
        snapper.begin(Point(200.0, 400.0), Size(30, 30), 0.0, canvas, edges = false)
        val result = snapper.snap(Point(203.0, 404.0), Size(30, 30), 0.01, canvas, edges = false)
        assertEquals(Point(200.0, 400.0), result.center)
        assertClose(0, result.rotation)
        assertTrue(result.turned)
        assertFalse(result.snapped)
    }

    /** The angle snaps to multiples of 45° within 2°, and holds to 4°. */
    @Test fun theAngleSnapsToFortyFiveDegrees() {
        val snapper = StorySnapper()
        val far = Point(120.0, 120.0)
        val half = Size(10, 10)
        assertClose(0, snapper.snap(far, half, 0.03, canvas, edges = false).rotation)
        assertClose(0, snapper.snap(far, half, 0.06, canvas, edges = false).rotation)
        assertClose(0.08, snapper.snap(far, half, 0.08, canvas, edges = false).rotation)
        val turned = snapper.snap(far, half, 0.80, canvas, edges = false)
        assertEquals(PI / 4, turned.rotation, 1.0e-9)
        assertTrue(turned.snapped)
    }

    /** A box turned a quarter reaches as far as its height across. */
    @Test fun aTurnedBoxsReach() {
        val half = StorySnapper.half(Size(100, 40), PI / 2)
        assertEquals(20.0, half.width, 1.0e-9)
        assertEquals(50.0, half.height, 1.0e-9)
    }
}

class StoryTextEntryLayoutTest {
    /**
     * Over a phone's keyboard: Cancel and Done 20 under the safe area's
     * top, the slider centred between it and the keyboard.
     */
    @Test fun overTheKeyboard() {
        val layout = StoryTextEntryLayout(rect(0, 0, 393, 852), statusBar = 54.0, safeTop = 59.0, safeBottom = 34.0, keyboard = 380.0, cancelWidth = 54.3, doneWidth = 41.2)
        assertEquals(rect(13, 79, 55, 30), layout.cancel)
        assertEquals(rect(338, 79, 42, 30), layout.done)
        assertEquals(rect(0, 145, 30, 240), layout.slider)
    }

    /** With the keyboard down the slider centres over the safe area's bottom. */
    @Test fun withoutTheKeyboard() {
        val layout = StoryTextEntryLayout(rect(0, 0, 393, 852), statusBar = 54.0, safeTop = 59.0, safeBottom = 34.0, keyboard = 0.0, cancelWidth = 54.3, doneWidth = 41.2)
        assertEquals(rect(0, 318, 30, 240), layout.slider)
    }

    /** The knob rides 12 past the track's ends; a finger maps back to the value. */
    @Test fun theKnob() {
        assertClose(14, StoryTextEntryLayout.knob(1.0, 240.0))
        assertClose(226, StoryTextEntryLayout.knob(0.0, 240.0))
        assertClose(1, StoryTextEntryLayout.value(12.0, 240.0))
        assertClose(0.5, StoryTextEntryLayout.value(120.0, 240.0))
        assertClose(0, StoryTextEntryLayout.value(300.0, 240.0))
    }

    /** The keyboard's bar: swatch, fill and alignment at the left, the font at the right. */
    @Test fun theBar() {
        val bar = StoryTextEntryLayout.bar(393.0, 89.6)
        assertEquals(rect(2, 0, 44, 44), bar.swatch)
        assertEquals(rect(48, 0, 44, 44), bar.style)
        assertEquals(rect(92, 0, 44, 44), bar.align)
        assertEquals(rect(247, 7, 130, 30), bar.font)
    }

    /** The slider runs the font from 2.5% to 25% of the canvas's height. */
    @Test fun theSliderMapsTheSize() {
        assertEquals(0.025 * 1920 / 67.5, StoryTextEntryLayout.scale(0.0), 1.0e-9)
        assertEquals(0.25 * 1920 / 67.5, StoryTextEntryLayout.scale(1.0), 1.0e-9)
        assertEquals(0.3, StoryTextEntryLayout.slider(StoryTextEntryLayout.scale(0.3)), 1.0e-9)
        assertClose(0, StoryTextEntryLayout.slider(0.1))
    }
}
