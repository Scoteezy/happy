package com.slopus.happy.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// Ported from happy-ios Tests/HappyLayoutTests/Support.swift.

/**
 * Stand-in for a label: every character is [advance] wide, lines wrap at
 * whole characters, and [maxLines] truncates. A single-line label reports its
 * full text width and the layout clamps it.
 */
fun text(string: String, advance: Double, lineHeight: Double, maxLines: Int = 0): Measurable {
    val count = string.length
    return Measurable { width ->
        val total = count * advance
        if (count == 0) return@Measurable Size(0.0, lineHeight)
        if (maxLines == 1 || total <= width) return@Measurable Size(total, lineHeight)
        val perLine = max(1, (width / advance).toInt())
        var lines = ceil(count.toDouble() / perLine).toInt()
        if (maxLines > 0) lines = min(lines, maxLines)
        Size(perLine * advance, lines * lineHeight)
    }
}

fun text(string: String, lineHeight: Number, maxLines: Int = 0) = text(string, 8.0, lineHeight.toDouble(), maxLines)

fun fixed(width: Number, height: Number) = Measurable.fixed(width, height)

/** Content that fills whatever width it gets at a fixed height. */
fun block(height: Number) = Measurable { width -> Size(width, height.toDouble()) }

fun rect(x: Number, y: Number, width: Number, height: Number) = Rect(x, y, width, height)

/** Frames are compared to 1/1000 dp: exact for the spec, tolerant of the last bit of floating point. */
fun assertFrame(actual: Rect?, expected: Rect) {
    assertNotNull("frame is null, expected $expected", actual)
    actual!!
    val t = 0.001
    val close = abs(actual.minX - expected.minX) < t && abs(actual.minY - expected.minY) < t &&
        abs(actual.width - expected.width) < t && abs(actual.height - expected.height) < t
    assertTrue("$actual is not $expected", close)
}

/** Frame lists, each to 1/1000 dp. */
fun assertFrames(actual: List<Rect>, expected: List<Rect>) {
    assertEquals("frames: $actual", expected.size, actual.size)
    actual.zip(expected).forEach { (a, e) -> assertFrame(a, e) }
}

fun assertSize(actual: Size, expected: Size) {
    assertEquals("width", expected.width, actual.width, 0.001)
    assertEquals("height", expected.height, actual.height, 0.001)
}

fun assertClose(expected: Number, actual: Double, message: String = "") =
    assertEquals(message, expected.toDouble(), actual, 0.001)

/** iPhone 15 portrait under a navigation bar: 59 status bar + 44 bar. */
val phone = Size(393, 852)
val phoneUnderBar = LayoutInsets(top = 103, bottom = 34)
val phoneFullScreen = LayoutInsets(top = 59, bottom = 34)

// XCTest's asserts by name, so ported tests read as their Swift originals
// (tools/port/swift-test-to-kotlin.py keeps the calls as they are). Numbers
// compare to 1/1000, like assertFrame.

fun XCTAssertEqual(actual: Any?, expected: Any?, message: String = "") {
    if (actual is Number && expected is Number) {
        assertEquals(message, expected.toDouble(), actual.toDouble(), 0.001)
    } else if (actual is Rect && expected is Rect) {
        assertFrame(actual, expected)
    } else if (actual is Size && expected is Size) {
        assertSize(actual, expected)
    } else {
        assertEquals(message, expected, actual)
    }
}

fun XCTAssertEqual(actual: Number?, expected: Number, accuracy: Number, message: String = "") {
    assertNotNull(message, actual)
    assertEquals(message, expected.toDouble(), actual!!.toDouble(), accuracy.toDouble())
}

fun XCTAssertNotEqual(actual: Any?, expected: Any?, message: String = "") = org.junit.Assert.assertNotEquals(message, expected, actual)
fun XCTAssertNil(actual: Any?, message: String = "") = org.junit.Assert.assertNull(message, actual)
fun XCTAssertNotNil(actual: Any?, message: String = "") = assertNotNull(message, actual)
fun XCTAssertTrue(condition: Boolean, message: String = "") = assertTrue(message, condition)
fun XCTAssertFalse(condition: Boolean, message: String = "") = org.junit.Assert.assertFalse(message, condition)
fun XCTAssertGreaterThan(a: Double, b: Number, message: String = "") = assertTrue("$message: $a > $b", a > b.toDouble())
fun XCTAssertGreaterThanOrEqual(a: Double, b: Number, message: String = "") = assertTrue("$message: $a >= $b", a >= b.toDouble())
fun XCTAssertLessThan(a: Double, b: Number, message: String = "") = assertTrue("$message: $a < $b", a < b.toDouble())
fun XCTAssertLessThanOrEqual(a: Double, b: Number, message: String = "") = assertTrue("$message: $a <= $b", a <= b.toDouble())

/** Swift passes whole numbers where a length goes. */
fun Measurable.size(width: Int) = size(width.toDouble())
fun Measurable.height(width: Int) = height(width.toDouble())
