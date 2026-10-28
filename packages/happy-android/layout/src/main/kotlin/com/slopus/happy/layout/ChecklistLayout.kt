package com.slopus.happy.layout

import kotlin.math.max
import kotlin.math.min

/**
 * One row of an onboarding checklist (ChecklistRow in OnboardingContent.tsx):
 * a box and a title side by side in a tappable head, then an optional body
 * indented under the title. Ticking the box never moves these frames.
 */
data class ChecklistRowLayout(val head: Rect, val box: Rect, val title: Rect, val body: Rect?) {
    val maxY get() = body?.maxY ?: head.maxY

    object Metrics {
        const val headMinHeight = 32.0
        const val boxSize = 26.0
        const val boxSpacing = 12.0
        const val bodyPaddingLeft = 38.0
        const val bodyPaddingTop = 6.0
        /** Under every row but the last. */
        const val marginBottom = 20.0
    }

    companion object {
        operator fun invoke(origin: Point, width: Double, title: Measurable, body: Measurable?): ChecklistRowLayout {
            val m = Metrics
            val w = max(0.0, width)
            val textX = origin.x + m.boxSize + m.boxSpacing
            val textWidth = max(0.0, w - m.boxSize - m.boxSpacing)
            val titleHeight = title.height(textWidth)
            val headHeight = maxOf(m.headMinHeight, titleHeight, m.boxSize)
            val head = Rect(origin.x, origin.y, w, headHeight)
            val box = Rect(origin.x, origin.y + (headHeight - m.boxSize) / 2, m.boxSize, m.boxSize)
            val titleFrame = Rect(textX, origin.y + (headHeight - titleHeight) / 2, textWidth, titleHeight)
            val bodyFrame = body?.let {
                val bodyWidth = max(0.0, w - m.bodyPaddingLeft)
                Rect(origin.x + m.bodyPaddingLeft, head.maxY + m.bodyPaddingTop, bodyWidth, it.height(bodyWidth))
            }
            return ChecklistRowLayout(head, box, titleFrame, bodyFrame)
        }
    }
}

/**
 * A few shell lines the way happy.engineering draws them (TerminalBlock.tsx).
 * The border sits inside the frame and the padding inside the border.
 */
data class TerminalBlockLayout(val size: Size, val lines: List<Rect>) {
    object Metrics {
        const val borderWidth = 1.0
        const val paddingVertical = 14.0
        const val paddingHorizontal = 18.0
        const val cornerRadius = 14.0
    }

    companion object {
        operator fun invoke(width: Double, lines: List<Measurable>): TerminalBlockLayout {
            val m = Metrics
            val w = max(0.0, width)
            val x = m.borderWidth + m.paddingHorizontal
            val lineWidth = max(0.0, w - x * 2)
            var y = m.borderWidth + m.paddingVertical
            val frames = lines.map { line ->
                Rect(x, y, lineWidth, line.height(lineWidth)).also { y = it.maxY }
            }
            return TerminalBlockLayout(Size(w, y + m.paddingVertical + m.borderWidth), frames)
        }
    }
}

/**
 * The checklist when every linked computer is offline (LinkComputerChecklist
 * in LinkComputer.tsx). It stands in for the session list, so it scrolls from
 * the top. Every frame is in the scroll view's content coordinates.
 */
data class OfflineChecklistLayout(
    val contentSize: Size,
    val title: Rect,
    val linked: ChecklistRowLayout,
    val open: ChecklistRowLayout,
    val openBody: Rect,
    val terminal: Rect,
    val buttons: List<Rect>,
) {
    object Metrics {
        const val paddingTop = 16.0
        const val paddingBottom = 48.0
        const val contentMaxWidth = 480.0
        const val contentPaddingHorizontal = 24.0
        const val titlePaddingHorizontal = 4.0
        const val titleMarginBottom = 16.0
        const val terminalMarginTop = 12.0
        const val actionsMarginTop = 6.0
        const val buttonWidth = 260.0
        const val buttonMarginBottom = 8.0
    }

    class Input(
        val size: Size,
        /** Includes the navigation bar at the top. */
        val safeArea: LayoutInsets,
        val title: Measurable,
        val linkedTitle: Measurable,
        val openTitle: Measurable,
        val openBody: Measurable,
        val terminal: Measurable,
        val buttons: List<Measurable>,
    )

    companion object {
        operator fun invoke(input: Input): OfflineChecklistLayout {
            val m = Metrics
            val insets = input.safeArea
            val bodyWidth = max(0.0, input.size.width - insets.horizontal)
            val contentWidth = min(m.contentMaxWidth, bodyWidth)
            val innerWidth = max(0.0, contentWidth - m.contentPaddingHorizontal * 2)
            val innerX = insets.left + (bodyWidth - contentWidth) / 2 + m.contentPaddingHorizontal

            var y = insets.top + m.paddingTop
            val titleWidth = max(0.0, innerWidth - m.titlePaddingHorizontal * 2)
            val title = Rect(innerX + m.titlePaddingHorizontal, y, titleWidth, input.title.height(titleWidth))
            y = title.maxY + m.titleMarginBottom

            val linked = ChecklistRowLayout(Point(innerX, y), innerWidth, input.linkedTitle, null)
            y = linked.maxY + ChecklistRowLayout.Metrics.marginBottom

            val text = input.openBody
            val terminal = input.terminal
            val body = Measurable { width -> Size(width, text.height(width) + m.terminalMarginTop + terminal.height(width)) }
            val open = ChecklistRowLayout(Point(innerX, y), innerWidth, input.openTitle, body)
            val bodyFrame = open.body ?: Rect.Zero
            val openBody = Rect(bodyFrame.minX, bodyFrame.minY, bodyFrame.width, text.height(bodyFrame.width))
            val terminalFrame = Rect(bodyFrame.minX, openBody.maxY + m.terminalMarginTop, bodyFrame.width, terminal.height(bodyFrame.width))
            y = open.maxY + m.actionsMarginTop

            val buttonWidth = min(m.buttonWidth, innerWidth)
            val buttons = input.buttons.map { button ->
                Rect(innerX, y, buttonWidth, button.height(buttonWidth)).also { y = it.maxY + m.buttonMarginBottom }
            }
            return OfflineChecklistLayout(
                Size(max(0.0, input.size.width), max(input.size.height, y + m.paddingBottom + insets.bottom)),
                title, linked, open, openBody, terminalFrame, buttons,
            )
        }
    }
}

/** A spinner centred in what is left of the screen, as SessionsListWrapper shows until data is ready. */
data class LoadingLayout(val spinner: Rect) {
    companion object {
        const val paddingBottom = 32.0

        operator fun invoke(size: Size, safeArea: LayoutInsets, spinner: Size): LoadingLayout {
            val width = max(0.0, size.width - safeArea.horizontal)
            val height = max(0.0, size.height - safeArea.bottom - paddingBottom - safeArea.top)
            return LoadingLayout(
                Rect(safeArea.left + (width - spinner.width) / 2, safeArea.top + (height - spinner.height) / 2, spinner.width, spinner.height),
            )
        }
    }
}
