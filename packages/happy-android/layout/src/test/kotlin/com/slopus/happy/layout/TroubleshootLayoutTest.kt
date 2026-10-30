package com.slopus.happy.layout

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil

class TroubleshootLayoutTest {
    private fun block(height: Double) = Measurable { Size(it, height) }
    private fun text(value: String, lineHeight: Double) = Measurable { Size(it, ceil(value.length * 8.0 / it) * lineHeight) }

    @Test fun guideMatchesNativeChecklistFrames() {
        val layout = TroubleshootGuideLayout(393.0, heading = text("h".repeat(37), 30.0), intro = text("i".repeat(100), 21.0), steps = listOf(
            TroubleshootGuideLayout.Step(text("Is the computer awake and online?", 22.0), text("s".repeat(52), 21.0)),
            TroubleshootGuideLayout.Step(text("Using the terminal?", 22.0), text("t".repeat(30), 21.0), block(102.0)),
        ))
        assertEquals(Rect(24, 20, 345, 30), layout.heading)
        assertEquals(Rect(24, 58, 345, 63), layout.intro)
        assertEquals(Rect(24, 145, 26, 22), layout.steps[0].icon)
        assertEquals(Rect(62, 145, 307, 22), layout.steps[0].title)
        assertEquals(Rect(62, 171, 307, 42), layout.steps[0].body)
        assertNull(layout.steps[0].terminal)
        assertEquals(Rect(24, 233, 26, 22), layout.steps[1].icon)
        assertEquals(Rect(62, 259, 307, 21), layout.steps[1].body)
        assertEquals(Rect(62, 292, 307, 102), layout.steps[1].terminal)
        assertEquals(Size(393, 414), layout.size)
    }

    @Test fun guideCanBeCappedOnTablet() {
        val layout = TroubleshootGuideLayout(1024.0, 800.0, block(30.0), block(21.0), emptyList())
        assertEquals(Rect(136, 20, 752, 30), layout.heading)
    }

    @Test fun copyPromptCentresButtonOverHint() {
        val layout = CopyPromptLayout(393.0, button = block(44.0), hint = text("p".repeat(80), 18.0))
        assertEquals(Rect(66.5, 20.0, 260.0, 44.0), layout.button)
        assertEquals(Rect(24, 72, 345, 36), layout.hint)
        assertEquals(Size(393, 108), layout.size)
    }

    @Test fun narrowOffersNeverProduceNegativeWidths() {
        val guide = TroubleshootGuideLayout(-1.0, heading = block(30.0), intro = block(21.0), steps = listOf(TroubleshootGuideLayout.Step(block(22.0), block(21.0))))
        assertEquals(0.0, guide.heading.width, 0.0)
        assertEquals(0.0, guide.steps.single().title.width, 0.0)
        assertEquals(0.0, CopyPromptLayout(20.0, button = block(44.0), hint = block(18.0)).button.width, 0.0)
    }
}