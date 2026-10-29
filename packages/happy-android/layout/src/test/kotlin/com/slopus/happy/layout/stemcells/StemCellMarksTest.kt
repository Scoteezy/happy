package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.Point
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.exp

/**
 * Pins the stem cell against desktop's `stemCellGeometry.ts` (happy-ios
 * `StemCellGeometryTests`, the same fixture): the same ID is the same cell.
 */
class StemCellGeometryTest {
    private class Case(val id: String, val drift: Double, val membrane: List<Point>, val core: List<Point>, val path20: String?)

    private fun cases(): List<Case> {
        val text = javaClass.classLoader.getResource("stem-cell-geometry.json")!!.readText()
        fun points(value: Any?) = (value as JsonArray).map { val pair = it as JsonArray; Point((pair[0] as JsonPrimitive).double, (pair[1] as JsonPrimitive).double) }
        return (Json.parseToJsonElement(text) as JsonArray).map {
            val o = it as JsonObject
            Case(
                (o["id"] as JsonPrimitive).content, (o["drift"] as JsonPrimitive).double,
                points(o["membrane"]), points(o["core"]), (o["path20"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content,
            )
        }
    }

    @Test fun shapesMatchDesktop() {
        for (item in cases()) {
            val shape = StemCellGeometry.shape(item.id, item.drift)
            assertEquals(item.id, item.membrane.size, shape.membrane.size)
            for ((point, expected) in shape.membrane.zip(item.membrane)) {
                assertEquals(item.id, expected.x, point.x, 1e-9)
                assertEquals(item.id, expected.y, point.y, 1e-9)
            }
            for ((point, expected) in shape.core.zip(item.core)) {
                assertEquals(item.id, expected.x, point.x, 1e-9)
                assertEquals(item.id, expected.y, point.y, 1e-9)
            }
        }
    }

    @Test fun splineMatchesDesktopPath() {
        for (item in cases()) {
            val expected = item.path20 ?: continue
            val (start, segments) = StemCellGeometry.smooth(StemCellGeometry.shape(item.id).membrane, 20.0)
            fun f(value: Double) = String.format(java.util.Locale.ROOT, "%.2f", value)
            val path = StringBuilder("M${f(start.x)} ${f(start.y)}")
            for (segment in segments) {
                path.append("C${f(segment.control1.x)} ${f(segment.control1.y)} ${f(segment.control2.x)} ${f(segment.control2.y)} ${f(segment.end.x)} ${f(segment.end.y)}")
            }
            assertEquals(item.id, expected, "${path}Z")
        }
    }

    @Test fun hashAndRandomFollowJavaScript() {
        // FNV-1a of "a" and of nothing, from Node.
        assertEquals(0xe40c292cu, StemCellGeometry.hash("a"))
        assertEquals(0x811c9dc5u, StemCellGeometry.hash(""))
    }
}

/** Desktop's braille loader on a phone's pixels (happy-ios `StemCellBrailleTests`). */
class StemCellBrailleTest {
    @Test fun at3xTheDotsAreSixPixelsWithTheSpareRowInTheMiddleGap() {
        val origins = StemCellBraille.origins(24, 42)
        assertEquals(6, origins.diameter)
        assertEquals(listOf(3, 3, 3, 3, 15, 15, 15, 15), origins.origins.map { it.first })
        assertEquals(listOf(1, 12, 24, 35, 1, 12, 24, 35), origins.origins.map { it.second })
        // One clear pixel at the foot, as at the head.
        assertEquals(41, origins.origins[3].second + origins.diameter)
    }

    @Test fun at2xTheDotsAreFourPixels() {
        val origins = StemCellBraille.origins(16, 28)
        assertEquals(4, origins.diameter)
        assertEquals(listOf(2, 2, 2, 2, 10, 10, 10, 10), origins.origins.map { it.first })
        assertEquals(listOf(1, 8, 16, 23, 1, 8, 16, 23), origins.origins.map { it.second })
    }

    @Test fun theStampIsAHardRoundDot() {
        val stamp = StemCellBraille.stamp(6)
        assertEquals(listOf(1, 0, 0, 0, 0, 1), stamp.map { it.first })
        assertEquals(listOf(4, 6, 6, 6, 6, 4), stamp.map { it.second })
    }

    @Test fun theWaveFallsHoldsAndRises() {
        val accuracy = 1e-9
        assertEquals(1.0, StemCellBraille.opacity(0.0), accuracy)
        assertEquals(0.59, StemCellBraille.opacity(30.0), accuracy)
        assertEquals(0.18, StemCellBraille.opacity(60.0), accuracy)
        assertEquals(0.18, StemCellBraille.opacity(99.0), accuracy)
        assertEquals(0.59, StemCellBraille.opacity(130.0), accuracy)
        assertEquals(1.0, StemCellBraille.opacity(160.0), accuracy)
        assertEquals(1.0, StemCellBraille.opacity(799.0), accuracy)
        // A phase reaches back across the cycle.
        assertEquals(0.59, StemCellBraille.opacity(-770.0), accuracy)
    }

    @Test fun elapsedReadsAsDesktopsClock() {
        assertEquals("0s", StemCellElapsed.text(999.0))
        assertEquals("59s", StemCellElapsed.text(59_999.0))
        assertEquals("1m 1s", StemCellElapsed.text(61_000.0))
        assertEquals("1h 2m", StemCellElapsed.text(3_726_000.0))
    }

    @Test fun aWaitCountsDownInItsTwoLargestUnits() {
        assertEquals("41s", StemCellElapsed.remaining(40_100.0))
        assertEquals("5m", StemCellElapsed.remaining(300_000.0))
        assertEquals("5m 1s", StemCellElapsed.remaining(301_000.0))
        assertEquals("2h", StemCellElapsed.remaining(7_200_000.0))
        assertEquals("1d 1h", StemCellElapsed.remaining(90_000_000.0))
        assertEquals("0s", StemCellElapsed.remaining(-5.0))
    }

    @Test fun theKeyframesAreTheWave() {
        for ((time, opacity) in StemCellBraille.keyframes.dropLast(1)) assertEquals(opacity, StemCellBraille.opacity(time * StemCellBraille.CYCLE), 1e-9)
        val times = StemCellBraille.keyframes.map { it.first }
        for ((actual, expected) in times.zip(listOf(0.0, 0.075, 0.125, 0.2, 1.0))) assertEquals(expected, actual, 1e-12)
    }
}

/** The hatch's timing is desktop's `stemCellTimeline` (happy-ios `StemCellHatchTests`); its haptic is sampled from the same curves. */
class StemCellHatchTest {
    @Test fun theTimelineIsDesktops() {
        val time = StemCellHatch.Timeline(3)
        assertEquals(0.1, time.intro, 1e-9)
        assertEquals(1.55, time.divisionsEnd, 1e-9)
        assertEquals(0.1, time.starts[0], 1e-9)
        assertEquals(0.87853, time.durations[0], 1e-5)
        assertEquals(0.33720, time.durations[1], 1e-5)
        assertEquals(0.23427, time.durations[2], 1e-5)
        assertEquals(1.55, time.starts[2] + time.durations[2], 1e-9)
        assertEquals(2.462, time.formed, 1e-9)
        assertEquals(3, StemCellHatch.generations(20.0))
        assertEquals(4, StemCellHatch.generations(64.0))
    }

    @Test fun theWaveAtFixedTimes() {
        fun wave(t: Double) = StemCellHatch.intensity(t, 3)
        // Still before the intro.
        assertEquals(0.0, wave(0.05), 0.0)
        // The first cleavage's crest, halfway through its division.
        assertEquals(0.35, wave(0.53927), 1e-3)
        // Between crests, the floor.
        assertEquals(0.12, wave(0.97), 1e-9)
        // The last, sharpest crest is the tallest.
        assertEquals(0.85, wave(1.43287), 1e-3)
        // Convergence: a damped ripple from 0.55, a trough at q = 1/6, a crest at 1/3.
        assertEquals(0.55, wave(1.55), 1e-9)
        assertEquals(0.0, wave(1.55 + 0.95 / 6), 1e-9)
        assertEquals(0.55 * exp(-1.0), wave(1.55 + 0.95 / 3), 1e-9)
        // Settled once the picture has formed.
        assertEquals(0.0, wave(2.47), 0.0)
        assertEquals(0.2, StemCellHatch.sharpness(0.0, 3), 1e-9)
        assertEquals(0.6, StemCellHatch.sharpness(2.0, 3), 1e-9)
    }

    @Test fun thePatternForALaneMark() {
        val haptics = StemCellHatch.haptics(20.0)
        // A tap at each tear, stronger and sharper as the cells get smaller, and the crisp one where the picture forms.
        assertEquals(listOf(
            StemCellHatch.Haptics.Tap(0.539, 0.45, 0.35),
            StemCellHatch.Haptics.Tap(1.147, 0.575, 0.55),
            StemCellHatch.Haptics.Tap(1.433, 0.7, 0.75),
            StemCellHatch.Haptics.Tap(2.462, 0.8, 0.9),
        ), haptics.taps)
        assertEquals(0.1, haptics.start, 0.0)
        assertEquals(2.362, haptics.duration, 0.0)
        assertEquals(48, haptics.intensity.size)
        assertEquals(0.0, haptics.intensity.first().first, 0.0)
        assertEquals(0.45, haptics.intensity[9].first, 1e-9)
        assertEquals(StemCellHatch.round3(StemCellHatch.intensity(0.55, 3)), haptics.intensity[9].second, 0.0)
        assertEquals(0.6, haptics.sharpness.last().second, 1e-9)
    }

    @Test fun theCellsDivideThenThePictureTakesOver() {
        val start = StemCellHatch.frame(0.0, 20.0, "a")
        assertEquals(8, start.cells.size)
        // One cell before it divides.
        XCTAssertTrue(start.cells.all { it == StemCellHatch.Cell(Point(10.0, 10.0), 9.0) })
        assertEquals(0.0, start.pictureAlpha, 0.0)
        val divided = StemCellHatch.frame(1.55, 20.0, "a")
        // Eight cells apart.
        assertEquals(8, divided.cells.map { "${it.center.x},${it.center.y}" }.toSet().size)
        XCTAssertEqual(divided.coreAlpha, 1 - StemCellHatch.smoothstep(1.55 - 0.23427 * 0.3, 1.55 + 0.095, 1.55), accuracy = 1e-4)
        val end = StemCellHatch.frame(2.5, 20.0, "a")
        assertEquals(1.0, end.pictureAlpha, 0.0)
        assertEquals(1.0, end.pictureScale, 0.0)
        assertEquals(0.0, end.cellAlpha, 0.0)
    }
}
