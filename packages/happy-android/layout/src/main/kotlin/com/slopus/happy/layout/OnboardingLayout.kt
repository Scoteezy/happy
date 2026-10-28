package com.slopus.happy.layout

import kotlin.math.max
import kotlin.math.min

/**
 * The first-run template shared by Welcome and Link Your Computer
 * (OnboardingScreen.tsx): a centred block of content, a large primary button
 * and a text action under it, with Get help in the bottom-right corner.
 * The content and secondary slots are measured as the largest of every
 * alternative they can show, so the buttons land in the same place.
 */
data class OnboardingLayout(
    /** The scroll view, in the screen's coordinates. */
    val scroll: Rect,
    val contentSize: Size,
    /** Content, primary and secondary, in the scroll view's coordinates. */
    val content: Rect,
    val primary: Rect,
    val secondary: Rect,
    /** The accessory, as wide as the buttons, in the scroll view's coordinates. */
    val accessory: Rect?,
    /** Under the secondary, as far below it as it is below the primary. */
    val tertiary: Rect?,
    /** Get help, in the screen's coordinates. */
    val help: Rect,
) {
    object Metrics {
        const val blockMaxWidth = 480.0
        const val blockPaddingHorizontal = 24.0
        const val bodyPaddingVertical = 8.0
        const val primaryMarginTop = 12.0
        const val secondaryMarginTop = 4.0
        /** Between glass buttons, which are pills where text actions are not. */
        const val glassActionGap = 12.0
        const val footerPaddingHorizontal = 16.0
        const val footerPaddingBottom = 12.0
        /** Above and below an accessory over the primary button. */
        const val accessoryGap = 16.0
    }

    class Input(
        val size: Size,
        /** Includes the navigation bar at the top and the gesture area at the bottom. */
        val safeArea: LayoutInsets,
        val content: Measurable,
        val primary: Measurable,
        val secondary: Measurable,
        /** A second text action under the first, where a screen has one. */
        val tertiary: Measurable? = null,
        val help: Size,
        /** The actions are pills, spaced as pills. */
        val glass: Boolean = false,
        /** The height of a control held just above the primary button (Relay | Direct). */
        val accessory: Double? = null,
    )

    companion object {
        /** The width the content, primary and secondary are laid out at. */
        fun blockWidth(size: Size, safeArea: LayoutInsets): Double {
            val bodyWidth = max(0.0, size.width - safeArea.horizontal)
            return max(0.0, min(Metrics.blockMaxWidth, bodyWidth) - Metrics.blockPaddingHorizontal * 2)
        }

        operator fun invoke(input: Input): OnboardingLayout {
            val m = Metrics
            val size = input.size
            val insets = input.safeArea
            val help = Rect(
                size.width - insets.right - m.footerPaddingHorizontal - input.help.width,
                size.height - insets.bottom - m.footerPaddingBottom - input.help.height,
                input.help.width,
                input.help.height,
            )
            val scroll = Rect(0.0, insets.top, size.width, max(0.0, help.minY - insets.top))

            val bodyWidth = max(0.0, size.width - insets.horizontal)
            val blockWidth = min(m.blockMaxWidth, bodyWidth)
            val innerWidth = blockWidth(size, insets)
            val innerX = insets.left + (bodyWidth - blockWidth) / 2 + m.blockPaddingHorizontal

            val contentHeight = input.content.height(innerWidth)
            val primaryHeight = input.primary.height(innerWidth)
            val secondaryHeight = input.secondary.height(innerWidth)
            val tertiaryHeight = input.tertiary?.height(innerWidth)
            val gap = if (input.glass) m.glassActionGap else m.secondaryMarginTop
            val accessoryBand = input.accessory?.let { m.accessoryGap + it + m.accessoryGap } ?: m.primaryMarginTop
            val blockHeight = contentHeight + accessoryBand + primaryHeight + gap + secondaryHeight +
                (tertiaryHeight?.let { gap + it } ?: 0.0)

            // Two growing spacers centre the block; when it does not fit, the body scrolls.
            val free = max(0.0, scroll.height - m.bodyPaddingVertical * 2 - blockHeight)
            var y = m.bodyPaddingVertical + free / 2
            val content = Rect(innerX, y, innerWidth, contentHeight)
            val accessory = input.accessory?.let { Rect(innerX, content.maxY + m.accessoryGap, innerWidth, it) }
            y = if (accessory != null) accessory.maxY + m.accessoryGap else content.maxY + m.primaryMarginTop
            val primary = Rect(innerX, y, innerWidth, primaryHeight)
            y = primary.maxY + gap
            val secondary = Rect(innerX, y, innerWidth, secondaryHeight)
            val tertiary = tertiaryHeight?.let { Rect(innerX, secondary.maxY + gap, innerWidth, it) }
            val contentSize = Size(size.width, max(scroll.height, (tertiary ?: secondary).maxY + m.bodyPaddingVertical))
            return OnboardingLayout(scroll, contentSize, content, primary, secondary, accessory, tertiary, help)
        }
    }
}

/** The Get help pill: a help icon, then the label. */
data class GetHelpButtonLayout(val size: Size, val icon: Rect, val label: Rect) {
    object Metrics {
        const val minHeight = 36.0
        const val paddingHorizontal = 12.0
        const val iconSize = 17.0
        const val spacing = 6.0
        const val cornerRadius = 18.0
        const val hitSlop = 8.0
    }

    companion object {
        /** [label] is the label's single-line size. */
        operator fun invoke(label: Size): GetHelpButtonLayout {
            val m = Metrics
            val height = maxOf(m.minHeight, label.height, m.iconSize)
            val icon = Rect(m.paddingHorizontal, 0.0, m.iconSize, height)
            val labelFrame = Rect(icon.maxX + m.spacing, (height - label.height) / 2, label.width, label.height)
            return GetHelpButtonLayout(Size(labelFrame.maxX + m.paddingHorizontal, height), icon, labelFrame)
        }
    }
}

/** The "Happy / Engineering" wordmark above the welcome headline. */
object WordmarkMetrics {
    const val height = 84.0
    /** Width over height of the wordmark's view box. */
    const val aspectRatio = 5274.0 / 1932.0

    /** RN rounds the width: round(84 × 5274 / 1932) = 229. */
    fun size(height: Double = WordmarkMetrics.height) = Size(swiftRound(height * aspectRatio), height)
}

/** Welcome: the wordmark, the headline and the three value props (WelcomeContent). */
data class WelcomeContentLayout(val size: Size, val wordmark: Rect, val title: Rect, val props: List<Rect>) {
    object Metrics {
        const val titleMarginTop = 28.0
        const val propsMarginTop = 12.0
        const val propsPaddingBottom = 12.0
    }

    companion object {
        operator fun invoke(width: Double, title: Measurable, props: List<Measurable>): WelcomeContentLayout {
            val m = Metrics
            val w = max(0.0, width)
            var mark = WordmarkMetrics.size()
            if (mark.width > w && mark.width > 0) {
                // Scale down rather than overflow on a very narrow screen.
                mark = Size(w, mark.height * w / mark.width)
            }
            val wordmark = Rect(Point.Zero, mark)
            val titleFrame = Rect(0.0, wordmark.maxY + m.titleMarginTop, w, title.height(w))
            var y = titleFrame.maxY + m.propsMarginTop
            val frames = props.map { prop -> Rect(0.0, y, w, prop.height(w)).also { y += it.height } }
            return WelcomeContentLayout(Size(w, y + m.propsPaddingBottom), wordmark, titleFrame, frames)
        }
    }
}

/** Link Your Computer: the title, a two-step checklist and a reserved error line (LinkComputerContent). */
data class LinkComputerContentLayout(val size: Size, val title: Rect, val rows: List<ChecklistRowLayout>, val error: Rect) {
    object Metrics {
        const val checklistMarginTop = 24.0
        const val errorMarginTop = 16.0
    }

    class Row(val title: Measurable, val body: Measurable? = null)

    companion object {
        /** [errorLineHeight] is one line of the error style, always reserved so an error never moves the buttons. */
        operator fun invoke(width: Double, title: Measurable, rows: List<Row>, errorLineHeight: Double): LinkComputerContentLayout {
            val m = Metrics
            val w = max(0.0, width)
            val titleFrame = Rect(0.0, 0.0, w, title.height(w))
            var y = titleFrame.maxY + m.checklistMarginTop
            val frames = rows.mapIndexed { index, row ->
                val frame = ChecklistRowLayout(Point(0.0, y), w, row.title, row.body)
                y = frame.maxY
                if (index < rows.size - 1) y += ChecklistRowLayout.Metrics.marginBottom
                frame
            }
            val error = Rect(0.0, y + m.errorMarginTop, w, errorLineHeight)
            return LinkComputerContentLayout(Size(w, error.maxY), titleFrame, frames, error)
        }
    }
}

/** A navigation bar title: an optional title over an optional subtitle (the custom server's host), both centred. */
data class NavigationTitleLayout(val size: Size, val title: Rect?, val subtitle: Rect?) {
    companion object {
        operator fun invoke(title: Size?, subtitle: Size?, maxWidth: Double): NavigationTitleLayout {
            val mw = max(0.0, maxWidth)
            val titleWidth = min(title?.width ?: 0.0, mw)
            val subtitleWidth = min(subtitle?.width ?: 0.0, mw)
            val width = max(titleWidth, subtitleWidth)
            val titleHeight = title?.height ?: 0.0
            return NavigationTitleLayout(
                Size(width, titleHeight + (subtitle?.height ?: 0.0)),
                title?.let { Rect((width - titleWidth) / 2, 0.0, titleWidth, titleHeight) },
                subtitle?.let { Rect((width - subtitleWidth) / 2, titleHeight, subtitleWidth, it.height) },
            )
        }
    }
}
