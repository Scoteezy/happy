package com.slopus.happy.connect

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
/**
 * A QR code's modules (qrMatrix.ts): true is dark. The three 7x7 finder
 * patterns read as light, since [QrCode] draws them separately.
 */
class QrMatrix(private val bits: Array<BooleanArray>) {
    val size: Int get() = bits.size

    fun isFinder(x: Int, y: Int): Boolean =
        (x < FINDER && y < FINDER) || (x >= size - FINDER && y < FINDER) || (x < FINDER && y >= size - FINDER)

    /** getPixel: false outside the matrix and inside the finder patterns. */
    fun dark(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= size || y >= size || isFinder(x, y)) return false
        return bits[y][x]
    }

    /** The raw module, finder patterns included. */
    fun module(x: Int, y: Int): Boolean = bits[y][x]

    companion object {
        private const val FINDER = 7

        /** createQRMatrix: byte mode, the smallest version that fits, at [level]. */
        fun encode(data: String, level: ErrorCorrectionLevel = ErrorCorrectionLevel.M): QrMatrix {
            val matrix = Encoder.encode(data, level, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
            return QrMatrix(Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y).toInt() == 1 } })
        }
    }
}
