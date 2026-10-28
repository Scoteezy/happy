package com.slopus.happy.layout

import kotlin.math.max
import kotlin.math.min

// Ported from happy-ios HappyLayout/VectorPath.swift: a resolution-independent
// path (move, line, quad, cubic, close) with an SVG path-data parser for the
// subset happy-app's vector art uses (M L H V Q C Z, absolute and relative).

sealed interface PathElement {
    data class Move(val to: Point) : PathElement
    data class Line(val to: Point) : PathElement
    data class QuadCurve(val to: Point, val control: Point) : PathElement
    data class Curve(val to: Point, val control1: Point, val control2: Point) : PathElement
    data object Close : PathElement
}

class VectorPathException(message: String) : Exception(message)

data class VectorPath(val elements: List<PathElement> = emptyList()) {
    /** The bounding box of every point, control points included. */
    val controlBounds: Rect?
        get() {
            val points = elements.flatMap {
                when (it) {
                    is PathElement.Move -> listOf(it.to)
                    is PathElement.Line -> listOf(it.to)
                    is PathElement.QuadCurve -> listOf(it.to, it.control)
                    is PathElement.Curve -> listOf(it.to, it.control1, it.control2)
                    PathElement.Close -> emptyList()
                }
            }
            if (points.isEmpty()) return null
            var minX = points[0].x; var maxX = minX; var minY = points[0].y; var maxY = minY
            for (p in points) {
                minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y)
            }
            return Rect(minX, minY, maxX - minX, maxY - minY)
        }

    fun applying(scale: Double, offset: Point): VectorPath {
        fun map(p: Point) = Point(p.x * scale + offset.x, p.y * scale + offset.y)
        return VectorPath(
            elements.map {
                when (it) {
                    is PathElement.Move -> PathElement.Move(map(it.to))
                    is PathElement.Line -> PathElement.Line(map(it.to))
                    is PathElement.QuadCurve -> PathElement.QuadCurve(map(it.to), map(it.control))
                    is PathElement.Curve -> PathElement.Curve(map(it.to), map(it.control1), map(it.control2))
                    PathElement.Close -> PathElement.Close
                }
            },
        )
    }

    companion object {
        fun svg(data: String): VectorPath {
            val scanner = NumberScanner(data)
            val elements = mutableListOf<PathElement>()
            var current = Point.Zero
            var subpathStart = Point.Zero
            var command: Char? = null
            while (true) {
                val next = scanner.peekNonSeparator() ?: break
                if (isCommand(next)) {
                    scanner.advance()
                    command = next
                } else if (command == null || !startsNumber(next)) {
                    throw VectorPathException("unexpected character $next")
                }
                val letter = command ?: break
                val relative = letter.isLowerCase()
                val origin = if (relative) current else Point.Zero
                fun number() = scanner.number() ?: throw VectorPathException("missing number for $letter")
                fun point(): Point {
                    val x = number()
                    val y = number()
                    return Point(origin.x + x, origin.y + y)
                }
                when (letter.lowercaseChar()) {
                    'm' -> {
                        current = point()
                        subpathStart = current
                        elements += PathElement.Move(current)
                        // Further pairs after a move are lines.
                        command = if (relative) 'l' else 'L'
                    }
                    'l' -> { current = point(); elements += PathElement.Line(current) }
                    'h' -> { current = Point(origin.x + number(), current.y); elements += PathElement.Line(current) }
                    'v' -> { current = Point(current.x, origin.y + number()); elements += PathElement.Line(current) }
                    'q' -> {
                        val control = point()
                        current = point()
                        elements += PathElement.QuadCurve(current, control)
                    }
                    'c' -> {
                        val c1 = point()
                        val c2 = point()
                        current = point()
                        elements += PathElement.Curve(current, c1, c2)
                    }
                    'z' -> {
                        elements += PathElement.Close
                        current = subpathStart
                        command = null
                    }
                    else -> throw VectorPathException("unsupported command $letter")
                }
            }
            return VectorPath(elements)
        }

        private fun isCommand(c: Char) = c.lowercaseChar() in 'a'..'z' && c != 'e' && c != 'E'
        private fun startsNumber(c: Char) = c in '0'..'9' || c == '-' || c == '+' || c == '.'
    }
}

/** Several coloured layers in one view box: the wordmark. */
data class VectorGraphic(val viewBox: Rect, val layers: List<Layer>) {
    data class Layer(val path: VectorPath, val color: ColorToken)

    /** The layers scaled to fit [rect], centred (`xMidYMid meet`). */
    fun fitted(into: Rect): List<Layer> {
        if (viewBox.width <= 0 || viewBox.height <= 0) return emptyList()
        val scale = min(into.width / viewBox.width, into.height / viewBox.height)
        val offset = Point(
            into.minX + (into.width - viewBox.width * scale) / 2 - viewBox.minX * scale,
            into.minY + (into.height - viewBox.height * scale) / 2 - viewBox.minY * scale,
        )
        return layers.map { Layer(it.path.applying(scale, offset), it.color) }
    }
}

private class NumberScanner(private val text: String) {
    private var index = 0

    fun advance() { index++ }

    fun peekNonSeparator(): Char? {
        while (index < text.length && isSeparator(text[index])) index++
        return if (index < text.length) text[index] else null
    }

    fun number(): Double? {
        if (peekNonSeparator() == null) return null
        val start = index
        if (text[index] == '-' || text[index] == '+') index++
        var sawDigit = false
        var sawPoint = false
        while (index < text.length) {
            val c = text[index]
            if (c in '0'..'9') sawDigit = true
            else if (c == '.' && !sawPoint) sawPoint = true
            else break
            index++
        }
        if (sawDigit && index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            var look = index + 1
            if (look < text.length && (text[look] == '-' || text[look] == '+')) look++
            if (look < text.length && text[look] in '0'..'9') {
                index = look
                while (index < text.length && text[index] in '0'..'9') index++
            }
        }
        val value = if (sawDigit) text.substring(start, index).toDoubleOrNull() else null
        if (value == null) index = start
        return value
    }

    private fun isSeparator(c: Char) = c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r'
}

/**
 * The stacked "Happy / Engineering" wordmark: DM Sans 700 outlined, path data
 * from happy-app's HappyEngineeringWordmark.tsx by way of happy-ios
 * Wordmark.swift (resources/wordmark.txt, tools/icons/extract-ios-tables.py).
 */
object Wordmark {
    val viewBox = Rect(0, -700, 5274, 1932)

    val graphic: VectorGraphic by lazy {
        val paths = Wordmark::class.java.getResourceAsStream("/com/slopus/happy/layout/wordmark.txt")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }
            .associate { it.substringBefore(' ') to it.substringAfter(' ') }
        VectorGraphic(
            viewBox,
            listOf(
                VectorGraphic.Layer(VectorPath.svg(paths.getValue("happyPath")), Palette.wordmarkHappy),
                VectorGraphic.Layer(VectorPath.svg(paths.getValue("engineeringPath")), Palette.wordmarkEngineering),
            ),
        )
    }
}
