package com.slopus.happy.images

import java.util.Base64
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ThumbHash's decoder (github.com/evanw/thumbhash, MIT, `thumbHashToRGBA`
 * in thumbhash@0.1.1; happy-ios `ThumbHash`), which expo-image uses for the
 * placeholder it shows while a picture loads. Pure arithmetic: the UI turns
 * the pixels into a bitmap.
 */
object ThumbHash {
    /** Straight (not premultiplied) RGBA, row by row. */
    class Image(val width: Int, val height: Int, val rgba: ByteArray)

    /** Decodes the base64 form the server stores; null when it is not one. */
    fun decode(base64: String): Image? {
        var padded = base64.trim()
        padded += "=".repeat((4 - padded.length % 4) % 4)
        val bytes = runCatching { Base64.getDecoder().decode(padded) }.getOrNull() ?: return null
        return decode(bytes)
    }

    fun decode(hash: ByteArray): Image? {
        if (hash.size < 5) return null
        fun byte(index: Int) = if (index < hash.size) hash[index].toInt() and 0xFF else 0

        val header24 = byte(0) or (byte(1) shl 8) or (byte(2) shl 16)
        val header16 = byte(3) or (byte(4) shl 8)
        val lDC = (header24 and 63) / 63.0
        val pDC = ((header24 shr 6) and 63) / 31.5 - 1
        val qDC = ((header24 shr 12) and 63) / 31.5 - 1
        val lScale = ((header24 shr 18) and 31) / 31.0
        val hasAlpha = (header24 shr 23) != 0
        val pScale = ((header16 shr 3) and 63) / 63.0
        val qScale = ((header16 shr 9) and 63) / 63.0
        val isLandscape = (header16 shr 15) != 0
        val lx = max(3, if (isLandscape) (if (hasAlpha) 5 else 7) else header16 and 7)
        val ly = max(3, if (isLandscape) header16 and 7 else (if (hasAlpha) 5 else 7))
        val aDC = if (hasAlpha) (byte(5) and 15) / 15.0 else 1.0
        val aScale = (byte(5) shr 4) / 15.0

        // The AC terms are nibbles, low first, boosted 1.25x for colour.
        val acStart = if (hasAlpha) 6 else 5
        var acIndex = 0
        fun decodeChannel(nx: Int, ny: Int, scale: Double): DoubleArray {
            val ac = ArrayList<Double>()
            for (cy in 0 until ny) {
                var cx = if (cy == 0) 1 else 0
                while (cx * ny < nx * (ny - cy)) {
                    val nibble = (byte(acStart + (acIndex shr 1)) shr ((acIndex and 1) shl 2)) and 15
                    acIndex++
                    ac += (nibble / 7.5 - 1) * scale
                    cx++
                }
            }
            return ac.toDoubleArray()
        }
        val lAC = decodeChannel(lx, ly, lScale)
        val pAC = decodeChannel(3, 3, pScale * 1.25)
        val qAC = decodeChannel(3, 3, qScale * 1.25)
        val aAC = if (hasAlpha) decodeChannel(5, 5, aScale) else DoubleArray(0)

        val ratio = approximateAspectRatio(hash)
        val width = (if (ratio > 1) 32.0 else 32 * ratio).roundToInt()
        val height = (if (ratio > 1) 32 / ratio else 32.0).roundToInt()
        if (width <= 0 || height <= 0) return null
        val rgba = ByteArray(width * height * 4)
        val columns = max(lx, if (hasAlpha) 5 else 3)
        val rows = max(ly, if (hasAlpha) 5 else 3)
        val fx = DoubleArray(columns)
        val fy = DoubleArray(rows)
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                var l = lDC
                var p = pDC
                var q = qDC
                var a = aDC
                for (cx in 0 until columns) fx[cx] = cos(PI / width * (x + 0.5) * cx)
                for (cy in 0 until rows) fy[cy] = cos(PI / height * (y + 0.5) * cy)
                var j = 0
                for (cy in 0 until ly) {
                    val fy2 = fy[cy] * 2
                    var cx = if (cy == 0) 1 else 0
                    while (cx * ly < lx * (ly - cy)) {
                        l += lAC[j] * fx[cx] * fy2
                        cx++
                        j++
                    }
                }
                j = 0
                for (cy in 0 until 3) {
                    val fy2 = fy[cy] * 2
                    var cx = if (cy == 0) 1 else 0
                    while (cx < 3 - cy) {
                        val f = fx[cx] * fy2
                        p += pAC[j] * f
                        q += qAC[j] * f
                        cx++
                        j++
                    }
                }
                if (hasAlpha) {
                    j = 0
                    for (cy in 0 until 5) {
                        val fy2 = fy[cy] * 2
                        var cx = if (cy == 0) 1 else 0
                        while (cx < 5 - cy) {
                            a += aAC[j] * fx[cx] * fy2
                            cx++
                            j++
                        }
                    }
                }
                val b = l - 2.0 / 3.0 * p
                val r = (3 * l - b + q) / 2
                val g = r - q
                rgba[i] = channel(r)
                rgba[i + 1] = channel(g)
                rgba[i + 2] = channel(b)
                rgba[i + 3] = channel(a)
                i += 4
            }
        }
        return Image(width, height, rgba)
    }

    /** `thumbHashToApproximateAspectRatio`. */
    fun approximateAspectRatio(hash: ByteArray): Double {
        if (hash.size < 5) return 1.0
        val header = hash[3].toInt() and 0xFF
        val hasAlpha = (hash[2].toInt() and 0x80) != 0
        val isLandscape = (hash[4].toInt() and 0x80) != 0
        val lx = if (isLandscape) (if (hasAlpha) 5 else 7) else header and 7
        val ly = if (isLandscape) header and 7 else (if (hasAlpha) 5 else 7)
        if (ly <= 0) return 1.0
        return lx.toDouble() / ly
    }

    /** A Uint8Array store: clamped to 0...255, then truncated. */
    private fun channel(value: Double): Byte = max(0.0, 255 * min(1.0, value)).toInt().toByte()
}
