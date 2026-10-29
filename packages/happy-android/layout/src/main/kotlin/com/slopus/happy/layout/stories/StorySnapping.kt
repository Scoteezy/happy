package com.slopus.happy.layout.stories

import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.swiftRound
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where an overlay being dragged or turned settles (happy-ios
 * `StorySnapper`), as Telegram's media editor snaps entities and API.md
 * "Haptics" sets the lines: its centre onto the canvas's centre lines, a
 * sticker's edges onto lines 1/20 of the width in from the canvas's edges,
 * and its angle onto a multiple of 45°. It snaps within 2% of the width (2°
 * for the angle) and stays until pulled twice as far, so a finger near a
 * line isn't nudged back and forth.
 */
class StorySnapper {
    enum class Guide { CenterX, CenterY, Left, Right, Top, Bottom }

    data class Result(
        val center: Point,
        val rotation: Double,
        /** The lines it sits on, to draw. */
        val guides: Set<Guide>,
        /** Its angle is held on a multiple of 45°, to draw. */
        val turned: Boolean,
        /** It reached a line or an angle it wasn't on before: a tick. */
        val snapped: Boolean,
    )

    private data class Held(val guide: Guide, val value: Double)

    private var x: Held? = null
    private var y: Held? = null
    private var turn: Double? = null

    /** Lets go of every line, as lifting the finger does. */
    fun reset() {
        x = null
        y = null
        turn = null
    }

    /**
     * Holds the lines an overlay already sits on as a finger lands on it,
     * so the first move doesn't tick for them.
     */
    fun begin(center: Point, half: Size, rotation: Double, canvas: Size, edges: Boolean) {
        reset()
        snap(center, half, rotation, canvas, edges)
    }

    /**
     * The settled place for a proposed `center` and `rotation` (canvas
     * points and radians) of an overlay whose box, turned, reaches `half`
     * out from its centre. `edges` snaps its edges too (stickers).
     */
    fun snap(center: Point, half: Size, rotation: Double, canvas: Size, edges: Boolean): Result {
        val reach = canvas.width * DISTANCE
        val inset = canvas.width * INSET
        val xs = mutableListOf(Guide.CenterX to canvas.width / 2)
        val ys = mutableListOf(Guide.CenterY to canvas.height / 2)
        if (edges) {
            xs += listOf(Guide.Left to inset + half.width, Guide.Right to canvas.width - inset - half.width)
            ys += listOf(Guide.Top to inset + half.height, Guide.Bottom to canvas.height - inset - half.height)
        }
        val (settledX, heldX, snappedX) = settle(center.x, x, xs, reach)
        x = heldX
        val (settledY, heldY, snappedY) = settle(center.y, y, ys, reach)
        y = heldY
        val (settledTurn, heldTurn, snappedTurn) = settleTurn(rotation, turn, ANGLE)
        turn = heldTurn
        val snapped = snappedX || snappedY || snappedTurn
        val guides = setOfNotNull(x?.guide, y?.guide)
        return Result(Point(settledX, settledY), settledTurn, guides, turn != null, snapped)
    }

    private fun settle(value: Double, held: Held?, candidates: List<Pair<Guide, Double>>, reach: Double): Triple<Double, Held?, Boolean> {
        if (held != null) {
            // The line may have moved with the overlay's size; follow it.
            val line = candidates.firstOrNull { it.first == held.guide }?.second ?: held.value
            if (abs(value - line) <= 2 * reach) return Triple(line, Held(held.guide, line), false)
        }
        val nearest = candidates.minByOrNull { abs(value - it.second) }
        if (nearest == null || abs(value - nearest.second) > reach) return Triple(value, null, false)
        return Triple(nearest.second, Held(nearest.first, nearest.second), true)
    }

    private fun settleTurn(rotation: Double, held: Double?, reach: Double): Triple<Double, Double?, Boolean> {
        if (held != null && abs(rotation - held) <= 2 * reach) return Triple(held, held, false)
        val nearest = swiftRound(rotation / STEP) * STEP
        if (abs(rotation - nearest) > reach) return Triple(rotation, null, false)
        return Triple(nearest, nearest, true)
    }

    override fun equals(other: Any?): Boolean = other is StorySnapper && other.x == x && other.y == y && other.turn == turn
    override fun hashCode(): Int = (x?.hashCode() ?: 0) * 31 + (y?.hashCode() ?: 0)

    companion object {
        const val DISTANCE = 0.02
        const val INSET = 1.0 / 20
        const val ANGLE = 2 * PI / 180
        const val STEP = PI / 4

        /** How far a box of `size`, turned by `rotation`, reaches from its centre. */
        fun half(size: Size, rotation: Double): Size {
            val c = abs(cos(rotation))
            val s = abs(sin(rotation))
            return Size((size.width * c + size.height * s) / 2, (size.width * s + size.height * c) / 2)
        }
    }
}
