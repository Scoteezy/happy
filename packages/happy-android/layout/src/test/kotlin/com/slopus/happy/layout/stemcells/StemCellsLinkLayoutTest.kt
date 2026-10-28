package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.LinkComputerContentLayout
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.rect
import com.slopus.happy.layout.text
import org.junit.Assert.assertEquals
import org.junit.Test

// Ported from happy-ios StemCellsSettingsLayoutTests (the link sheet's parts).
class StemCellsLinkLayoutTest {
    @Test fun stepsAreTheRelayChecklistsRows() {
        val line = text("Install Happy on your computer", 22)
        val body = text("b".repeat(70), 21)
        val layout = StemCellsLinkContentLayout(
            345.0,
            Measurable { Size(200, 34) },
            List(3) { LinkComputerContentLayout.Row(line, body) },
        )
        assertEquals(rect(0, 0, 345, 34), layout.title)
        assertEquals(listOf(rect(0, 61, 26, 26), rect(0, 161, 26, 26), rect(0, 261, 26, 26)), layout.steps.map { it.box })
        assertEquals(rect(38, 63, 307, 22), layout.steps[0].title)
        assertEquals(rect(38, 96, 307, 42), layout.steps[0].body)
        assertEquals(338.0, layout.steps[2].body!!.maxY, 0.0)
        assertEquals(Size(345, 350), layout.size)
    }

    @Test fun theSwitchIsThreeFifthsOfTheButtonsCentred() {
        val layout = StemCellsLinkSwitchLayout.inAccessory(rect(24, 400, 354, 36), Size(40.2, 17.5))
        assertEquals(rect(95, 400, 212, 36), layout.control)
        assertEquals(rect(256, 391, 41, 18), layout.tag)
    }

    @Test fun theBetaTagSitsAstrideTheSwitchAtDirectsEnd() {
        val layout = StemCellsLinkSwitchLayout.onControl(rect(24, 400, 354, 36), Size(40.2, 17.5))
        assertEquals(rect(327, 391, 41, 18), layout.tag)
    }
}
