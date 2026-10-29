package com.slopus.happy.layout.stemcells

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Desktop's working loader (`spinnerBrailleCanvas.ts`, cli-spinners'
 * `dots2`), happy-ios `StemCellBraille`: a 2x4 braille cell whose eight
 * dots dim one after another. The dots are placed on the screen's own
 * pixels, as desktop places them: one hard-edged stamp of one diameter,
 * copied eight times at whole-pixel origins, with whitespace taking every
 * rounding so no two dots differ. Every dot runs the same opacity wave,
 * offset by its phase.
 */
object StemCellBraille {
    const val FRAME_COUNT = 8
    /** Milliseconds. */
    const val FRAME = 100.0
    const val CYCLE = FRAME * FRAME_COUNT
    const val FADE = 60.0
    const val DIM = 0.18
    /** The box at desktop's 14px: 8 wide (twice the dot pitch) by 14. */
    const val WIDTH = 8.0
    const val HEIGHT = 14.0
    /** Left column top to bottom, then right column top to bottom; `dots2` dims dots 1, 2, 3, 7, 8, 6, 5, then 4. */
    val phases = listOf(0, 1, 2, 3, 7, 6, 5, 4)

    /** The one wave: down over 60ms, held dim to 100, up over the next 60. */
    fun opacity(time: Double): Double {
        val t = ((time % CYCLE) + CYCLE) % CYCLE
        if (t < FADE) return 1 - (1 - DIM) * (t / FADE)
        if (t < FRAME) return DIM
        if (t < FRAME + FADE) return DIM + (1 - DIM) * ((t - FRAME) / FADE)
        return 1.0
    }

    /** The same wave as keyframes over one cycle (fractions of it, and the opacity there), for an animator. */
    val keyframes: List<Pair<Double, Double>> = listOf(
        0.0 to 1.0, FADE / CYCLE to DIM, FRAME / CYCLE to DIM, (FRAME + FADE) / CYCLE to 1.0, 1.0 to 1.0,
    )

    /** The largest stamp that keeps one clear pixel between dots and round the edge. */
    fun diameter(width: Int, height: Int): Int {
        var diameter = minOf(width, height, max(2, height / 7))
        while (diameter > 1 && (width < diameter * 2 + 3 || height < diameter * 4 + 5)) diameter -= 1
        return diameter
    }

    /** Four rows: a pixel of padding top and bottom, the inner gaps widened evenly, a lone spare row in the middle gap. */
    fun rows(height: Int, diameter: Int): List<Int> {
        val whitespace = max(0, height - diameter * 4)
        val padding = if (whitespace >= 2) 1 else 0
        val inner = max(0, whitespace - padding * 2)
        val base = min(diameter, inner / 3)
        val extras = when (inner - base * 3) {
            0 -> listOf(0, 0, 0)
            1 -> listOf(0, 1, 0)
            2 -> listOf(1, 0, 1)
            3 -> listOf(1, 1, 1)
            else -> listOf(1, 2, 1)
        }
        val gaps = extras.map { base + it }
        return listOf(
            padding,
            padding + diameter + gaps[0],
            padding + diameter * 2 + gaps[0] + gaps[1],
            padding + diameter * 3 + gaps[0] + gaps[1] + gaps[2],
        )
    }

    /** Two columns with even padding; an odd spare pixel widens the gap. */
    fun columns(width: Int, diameter: Int): List<Int> {
        val whitespace = max(0, width - diameter * 2)
        val base = min(diameter, whitespace)
        val remainder = whitespace - base
        val padding = remainder / 2
        val gap = base + remainder % 2
        return listOf(padding, padding + diameter + gap)
    }

    class Origins(val diameter: Int, val origins: List<Pair<Int, Int>>)

    /** Each dot's origin in pixels, in the phases' order. */
    fun origins(width: Int, height: Int): Origins {
        val diameter = diameter(width, height)
        val rows = rows(height, diameter)
        val columns = columns(width, diameter)
        return Origins(diameter, columns.flatMap { x -> rows.map { x to it } })
    }

    /** The stamp: per pixel row, the first lit pixel and how many. */
    fun stamp(diameter: Int): List<Pair<Int, Int>> {
        val center = (diameter - 1) / 2.0
        val radius = diameter / 2.0
        return (0 until diameter).map { y ->
            var first = diameter
            var last = -1
            for (x in 0 until diameter) {
                if (hypot(x - center, y - center) <= radius) {
                    first = min(first, x)
                    last = x
                }
            }
            first to max(0, last - first + 1)
        }
    }
}

/** Desktop's `elapsedTimeFormat` (happy-ios `StemCellElapsed`): seconds, then minutes and seconds, then hours and minutes. */
object StemCellElapsed {
    fun text(milliseconds: Double): String {
        val seconds = max(0, (milliseconds / 1000).toInt())
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}m ${seconds % 60}s"
        return "${minutes / 60}h ${minutes % 60}m"
    }

    /** Desktop's `waitRemainingLabel`: what is left of a wait in its two largest units, a trailing zero unit dropped. */
    fun remaining(milliseconds: Double): String {
        val total = max(0, ceil(milliseconds / 1000).toInt())
        val days = total / 86_400
        val hours = total % 86_400 / 3600
        val minutes = total % 3600 / 60
        val seconds = total % 60
        if (days > 0) return if (hours > 0) "${days}d ${hours}h" else "${days}d"
        if (hours > 0) return if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
        if (minutes > 0) return if (seconds > 0) "${minutes}m ${seconds}s" else "${minutes}m"
        return "${seconds}s"
    }
}
