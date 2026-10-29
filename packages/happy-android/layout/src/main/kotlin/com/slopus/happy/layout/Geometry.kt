package com.slopus.happy.layout

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// :layout depends on nothing but Kotlin, as happy-ios HappyLayout depends on
// nothing but Foundation: every layout is a pure function from sizes to
// frames, in density-independent pixels (dp, iOS points), and runs under
// plain JUnit. Views convert to device pixels only when they apply a frame.

data class Size(val width: Double, val height: Double) {
    constructor(width: Number, height: Number) : this(width.toDouble(), height.toDouble())

    companion object {
        val Zero = Size(0.0, 0.0)
    }
}

data class Point(val x: Double, val y: Double) {
    constructor(x: Number, y: Number) : this(x.toDouble(), y.toDouble())

    companion object {
        val Zero = Point(0.0, 0.0)
    }
}

/** A frame: origin and size, y down, as CGRect. */
data class Rect(val x: Double, val y: Double, val width: Double, val height: Double) {
    constructor(x: Number, y: Number, width: Number, height: Number) :
        this(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())

    constructor(origin: Point, size: Size) : this(origin.x, origin.y, size.width, size.height)

    val minX get() = x
    val minY get() = y
    val maxX get() = x + width
    val maxY get() = y + height
    val midX get() = x + width / 2
    val midY get() = y + height / 2
    val size get() = Size(width, height)
    val origin get() = Point(x, y)

    fun offsetBy(dx: Double, dy: Double) = Rect(x + dx, y + dy, width, height)
    fun insetBy(dx: Double, dy: Double) = Rect(x + dx, y + dy, max(0.0, width - dx * 2), max(0.0, height - dy * 2))
    fun withWidth(width: Double) = copy(width = width)
    fun withHeight(height: Double) = copy(height = height)
    fun contains(px: Double, py: Double) = px >= x && px < maxX && py >= y && py < maxY

    /** The smallest rect holding both (CGRect.union). */
    fun union(other: Rect): Rect {
        val x0 = min(minX, other.minX)
        val y0 = min(minY, other.minY)
        return Rect(x0, y0, max(maxX, other.maxX) - x0, max(maxY, other.maxY) - y0)
    }

    companion object {
        val Zero = Rect(0.0, 0.0, 0.0, 0.0)

        /** A rect of [size] centred horizontally in [width], starting at [y]. */
        fun centered(size: Size, inWidth: Double, originX: Double = 0.0, y: Double) =
            Rect(originX + (inWidth - size.width) / 2, y, size.width, size.height)
    }
}

/** Safe-area, keyboard or padding insets (happy-ios `LayoutInsets`). */
data class LayoutInsets(val top: Double = 0.0, val left: Double = 0.0, val bottom: Double = 0.0, val right: Double = 0.0) {
    constructor(top: Number = 0, left: Number = 0, bottom: Number = 0, right: Number = 0) :
        this(top.toDouble(), left.toDouble(), bottom.toDouble(), right.toDouble())

    val horizontal get() = left + right
    val vertical get() = top + bottom

    companion object {
        val Zero = LayoutInsets(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Content whose size depends on the width it is offered, such as wrapping
 * text. Views pass a closure over their measurement; tests pass fixed sizes.
 */
class Measurable(private val measure: (width: Double) -> Size) {
    /** The size when offered [width]. Never wider than the offer and never negative. */
    fun size(width: Double): Size {
        val offered = max(0.0, width)
        val measured = measure(offered)
        return Size(min(max(0.0, measured.width), offered), max(0.0, measured.height))
    }

    fun height(width: Double): Double = size(width).height

    companion object {
        /** Content that is the same size at any width, like an icon. */
        fun fixed(size: Size) = Measurable { size }
        fun fixed(width: Number, height: Number) = fixed(Size(width, height))

        /**
         * Several alternatives laid over one another, taking the largest, as
         * the first-run screens reserve room for whichever content is tallest
         * so their buttons never move.
         */
        fun largest(items: List<Measurable>) = Measurable { width ->
            items.fold(Size.Zero) { result, item ->
                val size = item.size(width)
                Size(max(result.width, size.width), max(result.height, size.height))
            }
        }
    }
}

object PixelGrid {
    /**
     * Rounds a frame's edges to the nearest device pixel. Edges are rounded
     * rather than origin and size, so adjacent frames sharing an edge still
     * share it. A hairline keeps one pixel.
     */
    fun snap(rect: Rect, scale: Double): Rect {
        if (scale <= 0) return rect
        fun pixel(value: Double) = swiftRound(value * scale)
        val minX = pixel(rect.minX)
        val minY = pixel(rect.minY)
        val maxX = pixel(rect.maxX)
        val maxY = pixel(rect.maxY)
        val width = if (rect.width > 0) max(maxX - minX, 1.0) / scale else 0.0
        val height = if (rect.height > 0) max(maxY - minY, 1.0) / scale else 0.0
        return Rect(minX / scale, minY / scale, width, height)
    }

    /** Up to the screen's next pixel, as React Native ceils a measured Text's box. */
    fun ceil(value: Double, scale: Double): Double {
        val s = if (scale > 0) scale else 1.0
        return kotlin.math.ceil((value * s) - 0.001) / s
    }

    /** Rounds a length to the nearest device pixel. */
    fun round(value: Double, scale: Double): Double {
        if (scale <= 0 || !value.isFinite()) return value
        return swiftRound(value * scale) / scale
    }
}

/** Swift's `rounded()`: to nearest, halfway away from zero (Kotlin's `round` is half-even). */
fun swiftRound(value: Double): Double =
    if (value >= 0) floor(value + 0.5) else -floor(-value + 0.5)

/** Swift's `.rounded(.up)`. */
fun ceilTo(value: Double): Double = ceil(value)

internal fun Double.roundedToLong(): Long = swiftRound(this).roundToLong()
