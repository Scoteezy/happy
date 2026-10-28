package com.slopus.happy.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * happy-ios `ThumbHashTests`: vectors from thumbhash@0.1.1's own
 * `rgbaToThumbHash` and `thumbHashToRGBA`, run under node on three
 * generated images.
 */
class ThumbHashTest {
    private fun pixel(image: ThumbHash.Image, x: Int, y: Int): List<Int> {
        val start = (y * image.width + x) * 4
        return (start until start + 4).map { image.rgba[it].toInt() and 0xFF }
    }

    private fun check(base64: String, size: Pair<Int, Int>, first: List<Int>, middle: List<Int>, last: List<Int>, sum: Int) {
        val image = ThumbHash.decode(base64)
        assertNotNull(image)
        image!!
        assertEquals(size.first, image.width)
        assertEquals(size.second, image.height)
        assertEquals(first, pixel(image, 0, 0))
        assertEquals(middle, pixel(image, image.width shr 1, image.height shr 1))
        assertEquals(last, pixel(image, image.width - 1, image.height - 1))
        assertEquals(sum, image.rgba.sumOf { it.toInt() and 0xFF })
    }

    @Test
    fun square() = check("HwgKPxxwd3dweHiHiHiHd4h3Bwd4gI8I", 32 to 32, listOf(13, 0, 139, 255), listOf(130, 144, 116, 255), listOf(230, 198, 73, 255), 646_841)

    @Test
    fun landscapeWithoutPadding() = check("HwgKPJxwd3dweHiHiHdwgAf3iA", 32 to 18, listOf(6, 0, 141, 255), listOf(142, 144, 120, 255), listOf(227, 213, 80, 255), 364_200)

    @Test
    fun portraitWithAlpha() = check("W5eFGw43M0B3eHczQDjzi4+IiIiIiHg=", 19 to 32, listOf(14, 42, 127, 255), listOf(109, 128, 121, 113), listOf(72, 158, 117, 23), 270_241)

    @Test
    fun garbageIsNull() {
        assertNull(ThumbHash.decode("not base64!"))
        assertNull(ThumbHash.decode(byteArrayOf(1, 2)))
    }
}
