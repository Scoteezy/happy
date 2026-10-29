package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The stem cell: a seeded identity for work that has not become something
 * yet, the same shape for the same ID on every client (happy-ios
 * `StemCellGeometry`, a port of happy-desktop-ui `stemCellGeometry.ts`).
 * `StemCellGeometryTest` pins it against the TypeScript output.
 */
object StemCellGeometry {
    /** The membrane and the core, each 32 points in a unit square. */
    class Shape(val membrane: List<Point>, val core: List<Point>)

    private class Harmonic(val k: Double, val a: Double, val phase: Double)

    /** FNV-1a over UTF-16 code units, as JavaScript's `charCodeAt` reads them. */
    fun hash(text: String): UInt {
        var h = 0x811c9dc5u
        for (unit in text) {
            h = h xor unit.code.toUInt()
            h *= 0x01000193u
        }
        return h
    }

    /** mulberry32. */
    internal class Random(private var a: UInt) {
        fun next(): Double {
            a += 0x6d2b79f5u
            var t = a
            t = (t xor (t shr 15)) * (t or 1u)
            t = t xor (t + ((t xor (t shr 7)) * (t or 61u)))
            return (t xor (t shr 14)).toDouble() / 4294967296.0
        }
    }

    private fun radial(harmonics: List<Harmonic>, drift: Double): (Double) -> Double = { theta ->
        var r = 1.0
        for (harmonic in harmonics) r += harmonic.a * cos(harmonic.k * theta + harmonic.phase + drift)
        r
    }

    fun shape(id: String, drift: Double = 0.0): Shape {
        val tau = Math.PI * 2
        val samples = 32
        val padding = 0.06
        val coreOffset = 0.11
        val coreRadius = 0.105
        val margin = 0.1
        val random = Random(hash(id))
        fun between(lo: Double, hi: Double) = lo + (hi - lo) * random.next()

        // The core first, then a membrane sized to contain it.
        val coreAngle = between(0.0, tau)
        val coreDistance = coreOffset * sqrt(random.next())
        val coreX = cos(coreAngle) * coreDistance
        val coreY = sin(coreAngle) * coreDistance
        val coreR = coreRadius * between(0.85, 1.15)
        val c2 = Harmonic(2.0, between(0.05, 0.12), between(0.0, tau))
        val c3 = Harmonic(3.0, between(0.0, 0.05), between(0.0, tau))
        val coreShape = radial(listOf(c2, c3), drift)
        val lobes = listOf(3.0, 4.0, 4.0, 4.0, 5.0)[floor(random.next() * 5).toInt()]
        val lobeAmp = between(0.13, 0.18) * (if (lobes == 5.0) 0.75 else 1.0)
        val lobePhase = between(0.0, tau)
        val m1 = Harmonic(lobes, lobeAmp, lobePhase)
        val m2 = Harmonic(2 * lobes, lobeAmp * between(0.08, 0.18), 2 * lobePhase)
        val m3 = Harmonic(lobes - 1, between(0.02, 0.06), between(0.0, tau))
        val m4 = Harmonic(lobes + 1, between(0.02, 0.06), between(0.0, tau))
        val m5 = Harmonic(1.0, between(0.0, 0.06), between(0.0, tau))
        val membraneShape = radial(listOf(m1, m2, m3, m4, m5), drift)

        var scale = 0.32
        for (i in 0 until 48) {
            val t = i.toDouble() / 48 * tau
            val rr = coreR * coreShape(t)
            val px = coreX + cos(t) * rr
            val py = coreY + sin(t) * rr
            val needed = (sqrt(px * px + py * py) + margin) / membraneShape(atan2(py, px))
            if (needed > scale) scale = needed
        }

        val membrane = ArrayList<Point>(samples)
        val core = ArrayList<Point>(samples)
        for (i in 0 until samples) {
            val t = i.toDouble() / samples * tau
            val rm = scale * membraneShape(t)
            membrane += Point(cos(t) * rm, sin(t) * rm)
            val rc = coreR * coreShape(t)
            core += Point(coreX + cos(t) * rc, coreY + sin(t) * rc)
        }
        val minX = membrane.minOf { it.x }
        val maxX = membrane.maxOf { it.x }
        val minY = membrane.minOf { it.y }
        val maxY = membrane.maxOf { it.y }
        val fit = (1 - 2 * padding) / max(maxX - minX, maxY - minY)
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        fun place(point: Point) = Point(0.5 + (point.x - cx) * fit, 0.5 + (point.y - cy) * fit)
        return Shape(membrane.map(::place), core.map(::place))
    }

    /** One cubic segment of the closed Catmull–Rom spline through the points. */
    data class Segment(val control1: Point, val control2: Point, val end: Point)

    /**
     * The closed Catmull–Rom spline through the points at `size`, starting
     * at the first point (`stemCellSmoothPath`).
     */
    fun smooth(points: List<Point>, size: Double): Pair<Point, List<Segment>> {
        val p = points.map { Point(it.x * size, it.y * size) }
        val n = p.size
        val segments = (0 until n).map { i ->
            val p0 = p[(i - 1 + n) % n]
            val p1 = p[i]
            val p2 = p[(i + 1) % n]
            val p3 = p[(i + 2) % n]
            Segment(
                Point(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6),
                Point(p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6),
                p2,
            )
        }
        return p[0] to segments
    }
}

/**
 * Where every modal sheet's close button goes (happy-ios `SheetCloseLayout`):
 * a 44 circle at the top right of the sheet's header, as far down from the
 * sheet's top as it is in from its right side.
 */
object SheetCloseLayout {
    const val SIZE = 44.0
    const val INSET = 16.0

    /** In a sheet `width` wide whose top edge is at `top`. */
    fun frame(width: Double, top: Double = 0.0) =
        com.slopus.happy.layout.Rect(max(0.0, width) - INSET - SIZE, top + INSET, SIZE, SIZE)
}
