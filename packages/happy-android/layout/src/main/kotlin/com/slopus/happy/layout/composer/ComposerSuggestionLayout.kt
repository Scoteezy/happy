package com.slopus.happy.layout.composer

import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.max
import kotlin.math.min

/**
 * A row of the suggestion list (AgentInputSuggestionView.tsx; happy-ios
 * `ComposerSuggestionRowLayout`): 48 tall, 16 in from the sides, everything
 * centred down the row.
 *
 * A command is `/name` in semibold, then 12, then its description taking
 * what is left on one line. A file is a 32 round icon, 12, its path on one
 * line taking what is left, 8, then FILE or FOLDER.
 */
data class ComposerSuggestionRowLayout(
    val size: Size,
    val name: Rect? = null,
    val description: Rect? = null,
    val icon: Rect? = null,
    val iconGlyph: Rect? = null,
    val path: Rect? = null,
    val label: Rect? = null,
) {
    object Metrics {
        const val height = 48.0
        const val paddingH = 16.0
        const val commandGap = 12.0
        const val iconSize = 32.0
        const val iconGlyphSize = 18.0
        const val iconGap = 12.0
        const val labelGap = 8.0
    }

    sealed interface Input {
        /** The single-line sizes of `/name` and the description. */
        data class Command(val name: Size, val description: Size?) : Input
        /** The single-line sizes of the path and the label. */
        data class File(val path: Size, val label: Size) : Input
    }

    companion object {
        operator fun invoke(width: Double, input: Input): ComposerSuggestionRowLayout {
            val m = Metrics
            val rowWidth = max(0.0, width)
            val size = Size(rowWidth, m.height)
            val left = m.paddingH
            val right = max(left, rowWidth - m.paddingH)
            fun centred(x: Double, width: Double, height: Double) = Rect(x, (m.height - height) / 2, max(0.0, width), height)
            return when (input) {
                is Input.Command -> {
                    // The name keeps its width (flexShrink 0), up to the row.
                    val nameWidth = min(input.name.width, right - left)
                    val description = input.description?.let {
                        val x = left + nameWidth + m.commandGap
                        centred(x, min(it.width, max(0.0, right - x)), it.height)
                    }
                    ComposerSuggestionRowLayout(size, name = centred(left, nameWidth, input.name.height), description = description)
                }
                is Input.File -> {
                    val icon = centred(left, m.iconSize, m.iconSize)
                    val glyph = Rect(icon.midX - m.iconGlyphSize / 2, icon.midY - m.iconGlyphSize / 2, m.iconGlyphSize, m.iconGlyphSize)
                    val labelWidth = min(input.label.width, max(0.0, right - icon.maxX - m.iconGap - m.labelGap))
                    val pathX = icon.maxX + m.iconGap
                    // flex: 1 — the path takes the room left, whatever its text.
                    val pathWidth = max(0.0, right - labelWidth - m.labelGap - pathX)
                    ComposerSuggestionRowLayout(
                        size,
                        icon = icon,
                        iconGlyph = glyph,
                        path = centred(pathX, pathWidth, input.path.height),
                        label = centred(pathX + pathWidth + m.labelGap, labelWidth, input.label.height),
                    )
                }
            }
        }
    }
}

/** AgentInputAutocomplete's list: fixed-pitch rows inside a hairline rim, scrolling past 320. */
object ComposerSuggestionListLayout {
    /** Each row's frame inside the rim. */
    fun rows(count: Int, width: Double, border: Double): List<Rect> {
        val rowWidth = max(0.0, width - 2 * max(0.0, border))
        val height = ComposerSuggestionRowLayout.Metrics.height
        return (0 until max(0, count)).map { Rect(0.0, it * height, rowWidth, height) }
    }
}
