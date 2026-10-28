package com.slopus.happy.layout

import kotlin.math.max
import kotlin.math.min

/**
 * The screen's top bar on Android, where happy-ios uses UIKit's navigation
 * bar: Material's 56dp app bar under the status bar, 48dp icon buttons 4dp
 * from each edge, and the title centred between them as iOS centres it.
 * Not a port: the bar is the platform's idiom. Every frame is in the bar's
 * own coordinates, the status bar included at the top.
 */
data class NavigationBarLayout(
    val size: Size,
    /** The row under the status bar. */
    val row: Rect,
    val leading: List<Rect>,
    val trailing: List<Rect>,
    /** Where a title view of [titleSize] is centred: on the bar, held clear of the buttons. */
    val title: Rect?,
) {
    object Metrics {
        const val height = 56.0
        const val button = 48.0
        const val edge = 4.0
        /** Between the outermost button and the title. */
        const val titleGap = 4.0
        /** Each side of a text action's label (Material's text button). */
        const val textPadding = 12.0
    }

    companion object {
        /** A text action's width: its label and Material's 12 of padding each side, at least a button. */
        fun textButtonWidth(label: Double) = max(Metrics.button, label + Metrics.textPadding * 2)

        /** The bar's height under [statusBar]: what a screen keeps clear at its top. */
        fun height(statusBar: Double) = statusBar + Metrics.height

        operator fun invoke(
            width: Double,
            safeArea: LayoutInsets,
            leadingCount: Int,
            trailingCount: Int,
            titleSize: Size?,
            trailingWidths: List<Double>? = null,
            leadingWidths: List<Double>? = null,
        ): NavigationBarLayout {
            val m = Metrics
            val row = Rect(0.0, safeArea.top, width, m.height)
            var x = safeArea.left + m.edge
            val leading = (leadingWidths ?: List(leadingCount) { m.button }).map { w -> Rect(x, row.y + (m.height - m.button) / 2, w, m.button).also { x += w } }
            val leadingEnd = x
            var right = width - safeArea.right - m.edge
            val widths = trailingWidths ?: List(trailingCount) { m.button }
            val trailing = widths.map { w -> Rect(right - w, row.y + (m.height - m.button) / 2, w, m.button).also { right -= w } }.reversed()
            val title = titleSize?.let { size ->
                // Centred on the bar, as iOS centres it, unless the buttons push it off centre.
                val inset = max(leadingEnd, width - right) + m.titleGap
                val available = max(0.0, width - inset * 2)
                val w = min(size.width, available)
                Rect((width - w) / 2, row.y + (m.height - size.height) / 2, w, size.height)
            }
            return NavigationBarLayout(Size(width, row.maxY), row, leading, trailing, title)
        }
    }
}
