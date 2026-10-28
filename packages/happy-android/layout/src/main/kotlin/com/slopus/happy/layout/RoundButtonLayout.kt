package com.slopus.happy.layout

import kotlin.math.max

enum class RoundButtonSize { Large, Normal, Small }

enum class RoundButtonDisplay {
    /** The filled pill. */
    Filled,
    /** A text action: no fill, secondary text colour, may wrap. */
    Inverted,
}

/** From components/RoundButton.tsx: heights follow the platform control sizes, line height round(fontSize × 1.3). */
data class RoundButtonMetrics(
    val height: Double,
    val fontSize: Double,
    val lineHeight: Double,
    val paddingHorizontal: Double,
    /** How far beyond its frame a touch still counts. */
    val hitSlop: Double,
) {
    companion object {
        fun of(size: RoundButtonSize) = when (size) {
            RoundButtonSize.Large -> RoundButtonMetrics(50.0, 17.0, 22.0, 24.0, 0.0)
            RoundButtonSize.Normal -> RoundButtonMetrics(44.0, 15.0, 20.0, 20.0, 4.0)
            RoundButtonSize.Small -> RoundButtonMetrics(32.0, 13.0, 17.0, 14.0, 8.0)
        }

        const val minimumWidth = 64.0
        /** Vertical padding of the inverted (wrapping) variant. */
        const val invertedPaddingVertical = 8.0
        /** Between the spinner and the loading title. */
        const val inlineSpinnerSpacing = 10.0
    }
}

/** A RoundButton stretched to the width it is offered. */
data class RoundButtonLayout(val size: Size, val label: Rect, val spinner: Rect?, val cornerRadius: Double) {
    sealed interface Loading {
        data object None : Loading
        /** The spinner replaces the label in place; the label keeps its frame. */
        data class Overlay(val spinner: Size) : Loading
        /** The spinner sits beside a loading title, centred together. */
        data class Inline(val spinner: Size) : Loading
    }

    class Input(
        val width: Double,
        val size: RoundButtonSize = RoundButtonSize.Large,
        val display: RoundButtonDisplay = RoundButtonDisplay.Filled,
        /** The visible label: the title, or the loading title when inline. */
        val label: Measurable,
        val loading: Loading = Loading.None,
    )

    companion object {
        operator fun invoke(input: Input): RoundButtonLayout {
            val metrics = RoundButtonMetrics.of(input.size)
            val width = max(RoundButtonMetrics.minimumWidth, input.width)
            val innerWidth = max(0.0, width - metrics.paddingHorizontal * 2)
            val loading = input.loading
            val spinnerSize: Size? = when (loading) {
                Loading.None -> null
                is Loading.Overlay -> loading.spinner
                is Loading.Inline -> loading.spinner
            }
            val labelWidthLimit = when (loading) {
                is Loading.Inline -> max(0.0, innerWidth - loading.spinner.width - RoundButtonMetrics.inlineSpinnerSpacing)
                else -> innerWidth
            }
            val labelSize = input.label.size(labelWidthLimit)
            val paddingVertical = if (input.display == RoundButtonDisplay.Inverted) RoundButtonMetrics.invertedPaddingVertical else 0.0
            val height = maxOf(metrics.height, labelSize.height + paddingVertical * 2, spinnerSize?.height ?: 0.0)
            val midY = height / 2
            return when (loading) {
                is Loading.Inline -> {
                    val spinner = loading.spinner
                    val groupWidth = spinner.width + RoundButtonMetrics.inlineSpinnerSpacing + labelSize.width
                    val x = (width - groupWidth) / 2
                    RoundButtonLayout(
                        Size(width, height),
                        label = Rect(x + spinner.width + RoundButtonMetrics.inlineSpinnerSpacing, midY - labelSize.height / 2, labelSize.width, labelSize.height),
                        spinner = Rect(x, midY - spinner.height / 2, spinner.width, spinner.height),
                        cornerRadius = height / 2,
                    )
                }
                is Loading.Overlay -> RoundButtonLayout(
                    Size(width, height),
                    label = Rect(metrics.paddingHorizontal, midY - labelSize.height / 2, innerWidth, labelSize.height),
                    spinner = Rect((width - loading.spinner.width) / 2, midY - loading.spinner.height / 2, loading.spinner.width, loading.spinner.height),
                    cornerRadius = height / 2,
                )
                // The label spans the inner width so centred text stays centred.
                Loading.None -> RoundButtonLayout(
                    Size(width, height),
                    label = Rect(metrics.paddingHorizontal, midY - labelSize.height / 2, innerWidth, labelSize.height),
                    spinner = null,
                    cornerRadius = height / 2,
                )
            }
        }
    }
}
