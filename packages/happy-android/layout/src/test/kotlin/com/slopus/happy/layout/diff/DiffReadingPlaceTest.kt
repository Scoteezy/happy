package com.slopus.happy.layout.diff

import com.slopus.happy.layout.KeptPlace
import com.slopus.happy.layout.ScrollPlace
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextScale
import org.junit.Assert.*
import org.junit.Test

/** Same frames as HappyLayoutTests/DiffTextSizeLayoutTests.swift's reading-place tests. */
class DiffReadingPlaceTest {
    private data class ListPlan(val layout: DiffFilesListLayout, val sections: List<DiffReadingPlace.Section>)
    private fun list(scale: Double = 1.0, notices: Double = 50.0, header: Double = 35.0, hint: Double = 17.0): ListPlan {
        val metrics = DiffMetrics(scale = TextScale(scale.toFloat().toDouble()), displayScale = 3.0)
        val files = listOf(10, 4).map { DiffFileLayout(DiffFileLayout.Input(
            width = 393.0, metrics = metrics, rowCount = it, maxColumns = 40, maxLineNo = 99, showMoreTextHeight = 17.0)) }
        val open = DiffSectionLayout(393.0, headerHeight = header, body = DiffSectionLayout.Body.Files(files.map { it.height }))
        val collapsed = DiffSectionLayout(393.0, headerHeight = header, body = DiffSectionLayout.Body.Hint(Size(200.0, hint)))
        val sections = listOf(DiffReadingPlace.Section(open, files), DiffReadingPlace.Section(collapsed))
        return ListPlan(DiffFilesListLayout(393.0, notices, sections.map { it.layout.height }, Size(0, 0)), sections)
    }

    @Test fun rowKeepsItsPlace() {
        val base = list()
        val place = DiffReadingPlace.at(292.0, base.layout, base.sections)!!
        assertEquals(DiffReadingPlace(0, DiffReadingPlace.Part.Row(1, 1, 0.5)), place)
        val bigger = list(2.143, 90.0, 60.0, 36.0)
        assertEquals(90 + 60 + 11.5 * 116 / 3, place.y(bigger.layout, bigger.sections)!!, 1e-9)
        assertEquals(292.0, place.y(base.layout, base.sections)!!, 1e-9)
    }

    @Test fun headerAndRestOfSection() {
        val base = list()
        val header = DiffReadingPlace.at(57.0, base.layout, base.sections)!!
        val hint = DiffReadingPlace.at(376.5, base.layout, base.sections)!!
        assertEquals(DiffReadingPlace(0, DiffReadingPlace.Part.Header(0.2)), header)
        assertEquals(DiffReadingPlace(1, DiffReadingPlace.Part.Section(0.5)), hint)
        val bigger = list(2.143, 90.0, 60.0, 36.0)
        assertEquals(102.0, header.y(bigger.layout, bigger.sections)!!, 1e-9)
        assertEquals(90 + 60 + 14 * 116.0 / 3 + 1 + 121.0 / 2, hint.y(bigger.layout, bigger.sections)!!, 1e-9)
    }

    @Test fun noticesEndAndRemovedRowsHaveNoPlace() {
        val base = list()
        assertNull(DiffReadingPlace.at(49.0, base.layout, base.sections))
        assertNull(DiffReadingPlace.at(base.layout.contentHeight, base.layout, base.sections))
        assertNull(DiffReadingPlace(0, DiffReadingPlace.Part.Row(1, 4, 0.0)).y(base.layout, base.sections))
        assertNull(DiffReadingPlace(2, DiffReadingPlace.Part.Section(0.0)).y(base.layout, base.sections))
    }

    @Test fun successiveSizesKeepTheOriginalAnchorUntilTheReaderScrolls() {
        val base = list()
        val start = 288.0
        val first = DiffReadingPlace.at(start, base.layout, base.sections)!!
        val kept = KeptPlace<DiffReadingPlace>()
        var offset = start
        for (next in listOf(list(2.143, 90.0, 60.0, 36.0), list(0.823, 41.0, 29.0, 14.0), list(3.571, 150.0, 100.0, 61.0), base)) {
            val place = kept.place(offset) { DiffReadingPlace.at(it, base.layout, base.sections) }!!
            assertEquals(first, place)
            val y = place.y(next.layout, next.sections)!!
            offset = ScrollPlace.offset(y, 0.0, 0.0, 852.0, next.layout.contentHeight + 10_000, 3.0)
            kept.put(place, offset)
            assertEquals(y, offset, 0.5 / 3 + 1e-9)
        }
        assertEquals(start, offset, 1e-9)
        assertEquals(DiffReadingPlace.at(start + 1, base.layout, base.sections),
            kept.place(offset + 1) { DiffReadingPlace.at(it, base.layout, base.sections) })
        kept.forget()
        assertNull(kept.place(offset) { null })
    }

    @Test fun offsetIsRoundedAndClamped() {
        assertEquals(100.0 + 1.0 / 3, ScrollPlace.offset(100.2, 0.0, 0.0, 800.0, 2000.0, 3.0), 1e-9)
        assertEquals(90.0, ScrollPlace.offset(100.2, 10.0, 0.0, 800.0, 2000.0, 2.0), 1e-9)
        assertEquals(100.2, ScrollPlace.offset(100.2, 0.0, 0.0, 800.0, 2000.0, 0.0), 1e-9)
        assertEquals(-20.0, ScrollPlace.offset(-10.0, 20.0, 0.0, 800.0, 2000.0, 3.0), 1e-9)
        assertEquals(1234.0, ScrollPlace.offset(1900.0, 0.0, 34.0, 800.0, 2000.0, 3.0), 1e-9)
        assertEquals(0.0, ScrollPlace.offset(300.0, 0.0, 0.0, 800.0, 500.0, 3.0), 1e-9)
    }
}