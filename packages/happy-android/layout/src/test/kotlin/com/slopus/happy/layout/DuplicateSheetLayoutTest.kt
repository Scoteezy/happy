package com.slopus.happy.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.max

// happy-ios SecondaryLayoutTests.swift (DuplicateSheetLayoutTests) and
// DuplicateSheetTextSizeLayoutTests.swift.

/** components/DuplicateSheet.tsx, sized by utils/duplicateSheetLayout.ts. A 0.5 hairline (2x) keeps the sums exact. */
class DuplicateSheetLayoutTest {
    private fun layout(window: Size = Size(393, 852), body: DuplicateSheetLayout.Body) = DuplicateSheetLayout(
        DuplicateSheetLayout.Input(window, fixed(100, 20), fixed(300, 32), body, buttonTextHeight = 18.0, hairline = 0.5),
    )

    private fun rows(count: Int, text: Measurable, meta: Measurable) = DuplicateSheetLayout.Body.Rows(List(count) { text to meta })

    @Test fun rowsOnAPhone() {
        val sheet = layout(body = rows(2, fixed(200, 38), fixed(50, 14)))
        // min(560, 393 - 32) = 361 wide; the border leaves 360 inside.
        assertFrame(sheet.title, rect(20.5, 20.5, 320, 20))
        assertFrame(sheet.subtitle, rect(20.5, 44.5, 320, 32))
        assertFrame(sheet.headerDivider, rect(0.5, 88.5, 360, 0.5))
        // Rows: 12 + 38 + 4 + 14 + 12, then their hairline.
        assertEquals(2, sheet.rows.size)
        assertFrame(sheet.rows[0].frame, rect(0, 8, 360, 80.5))
        assertFrame(sheet.rows[0].text, rect(20, 20, 320, 38))
        assertFrame(sheet.rows[0].meta, rect(20, 62, 320, 14))
        assertFrame(sheet.rows[0].divider, rect(0, 88, 360, 0.5))
        assertFrame(sheet.rows[1].frame, rect(0, 88.5, 360, 80.5))
        assertFrame(sheet.rows[1].divider, rect(0, 168.5, 360, 0.5))
        assertNull(sheet.body)
        assertSize(sheet.listContentSize, Size(360, 177))
        assertFrame(sheet.list, rect(0.5, 89, 360, 177))
        // The action bar: hairline, 16, two 40pt buttons 8 apart, 16.
        assertFrame(sheet.actionsDivider, rect(0.5, 266, 360, 0.5))
        assertFrame(sheet.cancel, rect(16.5, 282.5, 160, 40))
        assertFrame(sheet.confirm, rect(184.5, 282.5, 160, 40))
        // 339 tall with both borders, centred in the window.
        assertFrame(sheet.card, rect(16, 256.5, 361, 339))
    }

    @Test fun theListStopsAt420() {
        val sheet = layout(body = rows(20, fixed(200, 19), fixed(50, 14)))
        // Each row 12 + 19 + 4 + 14 + 12 + 0.5 = 61.5; twenty and the padding.
        assertSize(sheet.listContentSize, Size(360, 1246))
        assertFrame(sheet.list, rect(0.5, 89, 360, 420))
        assertFrame(sheet.card, rect(16, 135, 361, 582))
    }

    @Test fun theListShrinksToFitAShortWindow() {
        val sheet = layout(Size(320, 400), rows(20, fixed(200, 19), fixed(50, 14)))
        // round(400 × 0.85) = 340 tall at most: 89 header, 72.5 actions, 0.5 border.
        assertFrame(sheet.list, rect(0.5, 89, 287, 178))
        assertFrame(sheet.card, rect(16, 30, 288, 340))
    }

    @Test fun aWideWindowCapsTheWidth() {
        val sheet = layout(Size(1024, 1366), DuplicateSheetLayout.Body.Loading(Size(20, 20)))
        assertFrame(sheet.card, rect(232, sheet.card.minY, 560, sheet.card.height))
        assertFrame(sheet.cancel, rect(16.5, sheet.cancel.minY, 259.5, 40))
    }

    @Test fun spinnerAndMessage() {
        val loading = layout(body = DuplicateSheetLayout.Body.Loading(Size(20, 20)))
        // 8 list padding, then 32 above and below the spinner.
        assertFrame(loading.body, rect(170, 40, 20, 20))
        assertSize(loading.listContentSize, Size(360, 100))

        // The message wraps inside 20 on each side and is centred.
        val message = layout(body = DuplicateSheetLayout.Body.Message(text("x".repeat(40), 10.0, 17.0)))
        assertFrame(message.body, rect(20, 40, 320, 34))
        assertSize(message.listContentSize, Size(360, 114))
        assertTrue(message.rows.isEmpty())
    }
}

/**
 * The duplicate sheet with its text at each size, as its labels measure it
 * scaled by RN's multiplier: the header and buttons grow with their lines,
 * and the list shrinks first, so the card stays inside 85% of the window at
 * every size.
 */
class DuplicateSheetTextSizeLayoutTest {
    private val window = Size(393, 852)
    private val hairline = 1.0 / 3

    /** iOS's text size categories, as React Native multiplies them (TextScale.swift). */
    private val multipliers = listOf(0.823, 0.882, 0.941, 1.0, 1.118, 1.235, 1.353, 1.786, 2.143, 2.643, 3.143, 3.571)

    /** Up to the next of the screen's pixels, as a label measures itself. */
    private fun ceiled(value: Double) = ceil(value * 3) / 3

    private class Sized(val layout: DuplicateSheetLayout, val title: Double, val preview: Double, val meta: Double, val button: Double)

    private fun layout(m: Double, points: Int, window: Size = this.window): Sized {
        val title = ceiled(20.29 * m)
        val subtitle = ceiled(15.52 * m)
        val preview = ceiled(3 * 19 * m)
        val meta = ceiled(14.32 * m)
        val button = ceiled(17.9 * m)
        val rows = List(points) { fixed(200, preview) to fixed(60, meta) }
        val layout = DuplicateSheetLayout(
            DuplicateSheetLayout.Input(window, fixed(150, title), fixed(250, subtitle), DuplicateSheetLayout.Body.Rows(rows), button, hairline),
        )
        return Sized(layout, title, preview, meta, button)
    }

    @Test fun theHeaderAndButtonsGrowWithTheirLinesAtEverySize() {
        for (m in multipliers) {
            val s = layout(m, 1)
            val l = s.layout
            assertFrame(l.title, Rect(hairline + 20, hairline + 20, 361 - 2 * hairline - 40, s.title))
            assertClose(l.title.maxY + 4, l.subtitle.minY, "$m")
            assertClose(l.subtitle.maxY + 12, l.headerDivider.minY, "$m")
            val row = l.rows[0]
            assertClose(s.preview, row.text.height, "$m")
            assertClose(row.text.maxY + 4, row.meta.minY, "$m")
            assertClose(12 * 2 + s.preview + 4 + s.meta + hairline, row.frame.height, "$m")
            // 11 above and below the button's one line.
            assertClose(22 + s.button, l.cancel.height, "$m")
            assertClose(l.cancel.height, l.confirm.height, "$m")
            assertClose(l.cancel.maxY + 16 + hairline, l.card.height, "$m")
        }
    }

    @Test fun theListShrinksFirstSoTheCardFitsTheWindow() {
        // Portrait and landscape: 85% of each height, rounded.
        val landscape = Size(852, 393)
        for ((window, maxHeight) in listOf(this.window to 724.0, landscape to 334.0)) {
            for (m in multipliers) {
                val l = layout(m, 12, window).layout
                val at = "$m in $window"
                val fixed = l.headerDivider.maxY + hairline + 16 * 2 + l.cancel.height + hairline
                assertClose(max(0.0, minOf(l.listContentSize.height, 420.0, maxHeight - fixed)), l.list.height, at)
                assertTrue(at, l.card.height <= max(maxHeight, fixed) + 1e-9)
                // Twelve points never fit: the list scrolls.
                assertTrue(at, l.list.height < l.listContentSize.height)
                assertClose(window.height / 2, l.card.midY, at)
            }
        }
        // Upright, the 420 cap holds the list even at the largest size.
        assertClose(420, layout(1.0, 12).layout.list.height)
        val largest = layout(3.571, 12).layout
        assertClose(420, largest.list.height)
        assertTrue(largest.card.height < 724)
        // On its side the window does: the card is exactly its 85%.
        val side = layout(3.571, 12, landscape).layout
        assertTrue(side.list.height < 420 && side.list.height > 0)
        assertClose(334, side.card.height)
    }
}
