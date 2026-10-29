package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.swiftRound
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A stem cell hatching into its picture, desktop's `StemCellGrowth`
 * (happy-ios `StemCellHatch`): the cell divides (8 cells in a 20-point
 * lane, 16 where it has room), the daughters converge onto the picture,
 * and the picture takes over. Its timing is desktop's `stemCellTimeline`
 * exactly; the drawing is a stand-in for desktop's shader. The haptic that
 * plays with it is sampled from the same timeline, so what is felt is what
 * is seen.
 */
object StemCellHatch {
    /** Every hatch takes this long (`STEM_CELL_VARIANTS.*.total`). */
    const val TOTAL = 2.5
    /** Share of a generation's window a cell waits before dividing, and the share one division takes. */
    internal const val STAGGER = 0.3
    internal const val DIVIDE = 0.7

    /** `stemCellVariantFor`: 8 cells where the mark is a lane, 16 where it has room. */
    fun generations(size: Double) = if (size <= 32) 3 else 4

    // Timeline

    /**
     * `stemCellTimeline`: divisions take the first 62% (the first cleavage
     * the most room, since it is the one people see); the rest converges.
     */
    class Timeline(depth: Int, total: Double = TOTAL) {
        val intro = total * 0.04
        val divisionsEnd = total * 0.62
        val end = total
        val starts: List<Double>
        val durations: List<Double>

        init {
            val budget = divisionsEnd - intro
            val weights = (0 until depth).map { g ->
                val k = if (depth <= 1) 0.0 else g.toDouble() / (depth - 1)
                if (g == 0) 1.5 else 1 - 0.6 * sqrt(k)
            }
            val sum = weights.sum()
            var t = intro
            val starts = ArrayList<Double>()
            val durations = ArrayList<Double>()
            for (weight in weights) {
                val d = weight / (if (sum == 0.0) 1.0 else sum) * budget
                starts += t
                durations += d
                t += d
            }
            this.starts = starts
            this.durations = durations
        }

        /** 0…1 through the convergence after the last division. */
        fun converge(t: Double) = clamp((t - divisionsEnd) / (end - divisionsEnd))

        /** When the picture has fully taken over (`morph` reaches 1 at 0.96 of convergence). */
        val formed get() = divisionsEnd + 0.96 * (end - divisionsEnd)

        /** When generation `g`'s cells tear in two: halfway through a division started at the middle of the stagger. */
        fun cleavage(g: Int) = starts[g] + STAGGER * 0.5 * durations[g] + 0.5 * DIVIDE * durations[g]
    }

    // Frames

    data class Cell(val center: Point, val radius: Double)

    data class Frame(
        /** Always 2^generations cells; before they divide, sisters stand on one another. */
        val cells: List<Cell>,
        val cellAlpha: Double,
        /** The cores dissolve before the picture reaches them. */
        val coreAlpha: Double,
        /** 0…1, how far the membranes have taken the picture's colour. */
        val tint: Double,
        val pictureAlpha: Double,
        val pictureScale: Double,
    )

    /** The leaves' regions: the mark halved along alternating axes, the first cut's direction seeded so sister rows don't divide alike. */
    internal fun regions(size: Double, generations: Int, seed: String): List<List<Rect>> {
        var hash = 2_166_136_261u
        var offset = 0
        while (offset < seed.length) {
            val scalar = seed.codePointAt(offset)
            hash = (hash xor scalar.toUInt()) * 16_777_619u
            offset += Character.charCount(scalar)
        }
        val vertical = (hash and 1u) == 0u
        val levels = arrayListOf(listOf(Rect(0.0, 0.0, size, size)))
        for (g in 0 until generations) {
            val splitX = (g % 2 == 0) == vertical
            levels += levels[g].flatMap { rect ->
                if (splitX) listOf(Rect(rect.minX, rect.minY, rect.width / 2, rect.height), Rect(rect.midX, rect.minY, rect.width / 2, rect.height))
                else listOf(Rect(rect.minX, rect.minY, rect.width, rect.height / 2), Rect(rect.minX, rect.midY, rect.width, rect.height / 2))
            }
        }
        return levels
    }

    /** The hatch at `t` seconds, for a mark `size` across. */
    fun frame(t: Double, size: Double, seed: String): Frame = frames(listOf(t), size, seed)[0]

    /** The hatch at each of `times`, its cells split once for them all. */
    fun frames(times: List<Double>, size: Double, seed: String): List<Frame> {
        val generations = generations(size)
        val time = Timeline(generations)
        val levels = regions(size, generations, seed)
        return times.map { frame(it, size, generations, time, levels) }
    }

    private fun frame(t: Double, size: Double, generations: Int, time: Timeline, levels: List<List<Rect>>): Frame {
        val leaves = 1 shl generations
        fun circle(rect: Rect, scale: Double) = Cell(Point(rect.midX, rect.midY), min(rect.width, rect.height) * 0.5 * scale)
        // Each leaf follows its ancestor at the deepest generation that has divided.
        var cells = ArrayList<Cell>()
        for (leaf in 0 until leaves) {
            var cell = circle(levels[0][0], 0.9)
            for (g in 0 until generations) {
                val p = clamp((t - time.starts[g] - STAGGER * 0.5 * time.durations[g]) / (DIVIDE * time.durations[g]))
                if (p <= 0) break
                val child = levels[g + 1][leaf shr (generations - g - 1)]
                val target = circle(child, 0.92)
                val m = easeInOut(smoothstep(0.22, 0.92, p))
                // The mother rounds up a little before she tears.
                val swell = 1 + 0.08 * smoothstep(0.0, 0.2, p) * (1 - smoothstep(0.4, 0.7, p))
                cell = Cell(
                    Point(lerp(cell.center.x, target.center.x, m), lerp(cell.center.y, target.center.y, m)),
                    lerp(cell.radius * swell, target.radius, m),
                )
            }
            cells += cell
        }
        val q = time.converge(t)
        val converge = 1 - (1 - q).pow(2.2)
        // Converging, the daughters draw in on the picture's middle as they fade into it.
        val middle = Point(size / 2, size / 2)
        val pull = converge * 0.35
        cells = ArrayList(cells.map { Cell(Point(lerp(it.center.x, middle.x, pull), lerp(it.center.y, middle.y, pull)), it.radius * (1 + 0.25 * converge)) })
        val last = time.durations.lastOrNull() ?: 0.0
        val coreFade = 1 - smoothstep(time.divisionsEnd - last * 0.3, time.divisionsEnd + 0.1 * (time.end - time.divisionsEnd), t)
        val morph = smoothstep(0.82, 0.96, q)
        return Frame(
            cells = cells,
            cellAlpha = 1 - smoothstep(0.3, 0.9, q),
            coreAlpha = coreFade,
            tint = smoothstep(0.3, 1.0, clamp((t - time.intro) / (time.divisionsEnd - time.intro))),
            pictureAlpha = max(smoothstep(0.04, 0.55, q) * 0.85, morph),
            pictureScale = 0.82 + 0.18 * converge,
        )
    }

    // Haptics

    /**
     * What the hatch feels like: a wave that swells once per cleavage and
     * grows sharper as the cells get smaller, then a damped ripple that
     * settles as they converge, and a crisp tap where the picture forms.
     */
    data class Haptics(
        val taps: List<Tap>,
        /** The continuous wave: from `start`, for `duration`. */
        val start: Double,
        val duration: Double,
        /** Its envelope sampled every `step`, from `start`: (time, value). */
        val intensity: List<Pair<Double, Double>>,
        val sharpness: List<Pair<Double, Double>>,
    ) {
        data class Tap(val time: Double, val intensity: Double, val sharpness: Double)
    }

    const val HAPTIC_STEP = 0.05

    /**
     * The wave's intensity at `t`: a sine crest over each division, rising
     * generation to generation, on a low floor; then `0.55·e^(−3q)·|cos 3πq|`
     * through the convergence, settling to nothing as the picture forms.
     */
    fun intensity(t: Double, generations: Int): Double {
        val time = Timeline(generations)
        if (t < time.intro || t >= time.formed) return 0.0
        if (t < time.divisionsEnd) {
            var value = 0.12
            for (g in 0 until generations) {
                val from = time.starts[g] + STAGGER * 0.5 * time.durations[g]
                val p = (t - from) / (DIVIDE * time.durations[g])
                if (p <= 0 || p >= 1) continue
                value = max(value, (0.35 + 0.5 * g / max(1, generations - 1)) * sin(PI * p))
            }
            return value
        }
        val q = time.converge(t)
        return 0.55 * exp(-3 * q) * abs(cos(3 * PI * q))
    }

    /** Sharpness rises as the cells get smaller. */
    fun sharpness(t: Double, generations: Int) = 0.2 + 0.4 * clamp(t / Timeline(generations).divisionsEnd)

    fun haptics(size: Double): Haptics {
        val generations = generations(size)
        val time = Timeline(generations)
        val taps = (0 until generations).map { g ->
            val share = g.toDouble() / max(1, generations - 1)
            Haptics.Tap(round3(time.cleavage(g)), round3(0.45 + 0.25 * share), round3(0.35 + 0.4 * share))
        } + Haptics.Tap(round3(time.formed), 0.8, 0.9)
        val intensity = ArrayList<Pair<Double, Double>>()
        val sharpness = ArrayList<Pair<Double, Double>>()
        var t = time.intro
        while (t < time.formed) {
            intensity += round3(t - time.intro) to round3(intensity(t, generations))
            sharpness += round3(t - time.intro) to round3(sharpness(t, generations))
            t += HAPTIC_STEP
        }
        return Haptics(taps, round3(time.intro), round3(time.formed - time.intro), intensity, sharpness)
    }

    // Curves

    internal fun clamp(x: Double) = min(1.0, max(0.0, x))
    internal fun smoothstep(a: Double, b: Double, x: Double): Double {
        val t = clamp((x - a) / (b - a))
        return t * t * (3 - 2 * t)
    }
    internal fun easeInOut(t: Double) = if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
    internal fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
    internal fun round3(x: Double) = swiftRound(x * 1000) / 1000
}
