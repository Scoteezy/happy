package com.slopus.happy.layout

import kotlin.math.ceil
import kotlin.math.floor

/** One glyph's metrics in font units: what [IconLayout] needs to place it. */
data class IconMetrics(
    val unitsPerEm: Double,
    /** The glyph's advance width (hmtx). */
    val advance: Double,
    /** The font's hhea ascender, positive. */
    val ascender: Double,
    /** The font's hhea descender, negative. */
    val descender: Double,
)

/**
 * Where React Native on iOS draws `<Ionicons name=… size={size} />`, so an
 * icon here lands on the same box as on iOS (happy-ios `IconLayout`): a
 * text box `ascender - descender` tall and the glyph's advance wide, both
 * rounded up to whole device pixels, with the glyph's pen at the top-left
 * and its baseline `ascender` down.
 */
data class IconLayout(
    /** The `<Text>` box RN measures, in dp, whole device pixels. */
    val size: Size,
    /** dp per font unit. */
    val glyphScale: Double,
    /** Where the outline's origin lands in the box (y-down, on the ascender line). */
    val glyphOrigin: Point,
    /** The baseline's distance from the top of the box: the ascender. */
    val baseline: Double,
) {
    /** The box centred in [container], the offset floored to device pixels as Yoga snaps text. */
    fun frame(centeredIn: Rect, scale: Double) = Rect(
        centeredIn.minX + floorToPixel((centeredIn.width - size.width) / 2, scale),
        centeredIn.minY + floorToPixel((centeredIn.height - size.height) / 2, scale),
        size.width,
        size.height,
    )

    companion object {
        operator fun invoke(metrics: IconMetrics, size: Double, scale: Double): IconLayout {
            val glyphScale = if (metrics.unitsPerEm > 0) size / metrics.unitsPerEm else 0.0
            val lineHeight = (metrics.ascender - metrics.descender) * glyphScale
            return IconLayout(
                Size(ceilToPixel(metrics.advance * glyphScale, scale), ceilToPixel(lineHeight, scale)),
                glyphScale,
                Point.Zero,
                metrics.ascender * glyphScale,
            )
        }

        /** A text box placed at [frame], snapped as Yoga snaps text: the origin floored to device pixels. */
        fun textFrame(frame: Rect, scale: Double) =
            Rect(floorToPixel(frame.minX, scale), floorToPixel(frame.minY, scale), frame.width, frame.height)

        /** RN's `ceil(value * scale) / scale`, ignoring floating-point noise. */
        fun ceilToPixel(value: Double, scale: Double): Double {
            if (scale <= 0) return value
            return ceil(value * scale - 1e-9) / scale
        }

        /** Yoga's `roundValueToPixelGrid` with `forceFloor`. */
        fun floorToPixel(value: Double, scale: Double): Double {
            if (scale <= 0) return value
            val scaled = value * scale
            val f = floor(scaled)
            val fraction = scaled - f
            return (if (fraction > 1 - 0.0001) f + 1 else f) / scale
        }
    }
}
