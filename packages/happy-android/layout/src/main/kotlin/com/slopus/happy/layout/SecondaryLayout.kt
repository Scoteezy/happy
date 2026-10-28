package com.slopus.happy.layout

import kotlin.math.max

// Layouts for the screens around a session and a machine: session info, the
// machine screen, the file viewer and changes (happy-ios SecondaryLayout.swift).

/**
 * A column of content centred in a screen, each item centred on its own
 * line: `<View style={{ flex: 1, alignItems: 'center', justifyContent:
 * 'center', padding }}>` holding an icon or spinner and some text. Used by
 * info.tsx's loading and deleted states, the machine screen's "not found"
 * and the file viewer's loading, error and binary states.
 */
data class CenteredStackLayout(
    /** Where each item's content goes (inside its horizontal padding). */
    val items: List<Rect>,
) {
    class Item(
        val content: Measurable,
        /** `marginTop` (or the previous item's `marginBottom`). */
        val spacingBefore: Double = 0.0,
        /** The item's own `paddingHorizontal`, which narrows its text but still counts in its box. */
        val horizontalPadding: Double = 0.0,
    )

    class Input(val size: Size, val padding: Double = 0.0, val items: List<Item>)

    companion object {
        operator fun invoke(input: Input): CenteredStackLayout {
            val width = max(0.0, input.size.width - input.padding * 2)
            val sizes = input.items.map { it.content.size(max(0.0, width - it.horizontalPadding * 2)) }
            val total = sizes.withIndex().sumOf { (i, size) -> size.height + if (i == 0) 0.0 else input.items[i].spacingBefore }
            var y = input.padding + (max(0.0, input.size.height - input.padding * 2) - total) / 2
            val frames = input.items.mapIndexed { i, item ->
                if (i > 0) y += item.spacingBefore
                val size = sizes[i]
                Rect((input.size.width - size.width) / 2, y, size.width, size.height).also { y += size.height }
            }
            return CenteredStackLayout(frames)
        }
    }
}

/**
 * A row of fixed-size parts with a gap between them, centred vertically:
 * `flexDirection: 'row', alignItems: 'center', gap`. GitLineChanges (gap 4)
 * and the Changes row's right element (gap 6) are built from it.
 */
data class HorizontalStackLayout(val size: Size, val items: List<Rect>) {
    companion object {
        operator fun invoke(sizes: List<Size>, spacing: Double): HorizontalStackLayout {
            val height = sizes.maxOfOrNull { it.height } ?: 0.0
            var x = 0.0
            val frames = sizes.mapIndexed { i, size ->
                if (i > 0) x += spacing
                Rect(x, (height - size.height) / 2, size.width, size.height).also { x += size.width }
            }
            return HorizontalStackLayout(Size(x, height), frames)
        }
    }
}

/**
 * components/CodeView.tsx as info.tsx places it inside an item group:
 * `<View style={{ marginHorizontal: 16, marginBottom: 12 }}>` around a
 * `surfaceHigh` box with radius 6 and padding 12, mono 12/18 inside. The
 * text comes in pieces stacked with no gap, so a block megabytes long is
 * drawn only where it is near the screen.
 */
data class CodeBlockLayout(
    val size: Size,
    val box: Rect,
    val text: Rect,
    /** Each piece of the text, top to bottom. */
    val pieces: List<Rect>,
) {
    object Metrics {
        const val marginHorizontal = 16.0
        const val marginBottom = 12.0
        const val padding = 12.0
        const val cornerRadius = 6.0
        const val fontSize = 12.0
        const val lineHeight = 18.0
    }

    /** The pieces that meet `minY until maxY`, in the block's coordinates. */
    fun pieces(minY: Double, maxY: Double): IntRange {
        if (maxY <= minY) return IntRange.EMPTY
        // The first piece ending below minY, then the first starting at or below maxY; both by halving.
        val first = firstPiece { it.maxY > minY }
        val last = firstPiece { it.y >= maxY }
        return first until max(first, last)
    }

    private fun firstPiece(isPast: (Rect) -> Boolean): Int {
        var low = 0
        var high = pieces.size
        while (low < high) {
            val middle = (low + high) / 2
            if (isPast(pieces[middle])) high = middle else low = middle + 1
        }
        return low
    }

    companion object {
        /** The width the text wraps at in a block [width] wide. */
        fun textWidth(width: Double): Double = max(0.0, width - Metrics.marginHorizontal * 2 - Metrics.padding * 2)

        /** [heights] are the pieces' heights at [textWidth]. */
        operator fun invoke(width: Double, heights: List<Double>): CodeBlockLayout {
            val m = Metrics
            val boxWidth = max(0.0, width - m.marginHorizontal * 2)
            val textWidth = textWidth(width)
            var y = m.padding
            val pieces = heights.map { height -> Rect(m.marginHorizontal + m.padding, y, textWidth, height).also { y += height } }
            val textHeight = y - m.padding
            val box = Rect(m.marginHorizontal, 0.0, boxWidth, textHeight + m.padding * 2)
            return CodeBlockLayout(
                size = Size(max(0.0, width), box.height + m.marginBottom),
                box = box,
                text = Rect(m.marginHorizontal + m.padding, m.padding, textWidth, textHeight),
                pieces = pieces,
            )
        }
    }
}
