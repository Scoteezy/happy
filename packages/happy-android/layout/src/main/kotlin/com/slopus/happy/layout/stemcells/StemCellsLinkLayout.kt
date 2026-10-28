package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.ChecklistRowLayout
import com.slopus.happy.layout.LinkComputerContentLayout
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.swiftRound
import kotlin.math.ceil
import kotlin.math.max

/** The link sheet's content: the title, then numbered steps as checklist rows (happy-ios `StemCellsLinkContentLayout`). */
data class StemCellsLinkContentLayout(val title: Rect, val steps: List<ChecklistRowLayout>, val size: Size) {
    object Metrics {
        /** From the title to the first step, as the relay's checklist has it. */
        const val stepsGap = LinkComputerContentLayout.Metrics.checklistMarginTop
        const val bottom = 12.0
    }

    companion object {
        operator fun invoke(width: Double, title: Measurable, steps: List<LinkComputerContentLayout.Row>): StemCellsLinkContentLayout {
            val w = max(0.0, width)
            val titleFrame = Rect(0.0, 0.0, w, title.height(w))
            var y = titleFrame.maxY + Metrics.stepsGap
            val rows = steps.mapIndexed { index, step ->
                val row = ChecklistRowLayout(Point(0.0, y), w, step.title, step.body)
                y = row.maxY + if (index < steps.size - 1) ChecklistRowLayout.Metrics.marginBottom else 0.0
                row
            }
            return StemCellsLinkContentLayout(titleFrame, rows, Size(w, y + Metrics.bottom))
        }
    }
}

/**
 * The link sheet's Relay | Direct switch: 60% as wide as the screen's buttons
 * and centred over them, just above the primary one, with the Beta tag astride
 * its top edge at Direct's end (happy-ios `StemCellsLinkSwitchLayout`).
 */
data class StemCellsLinkSwitchLayout(val control: Rect, val tag: Rect) {
    object Metrics {
        const val height = 36.0
        /** Of the buttons' width. */
        const val widthShare = 0.6
        /** From the control's right end to the tag's. */
        const val tagInset = 10.0
    }

    companion object {
        /** In the room the screen keeps for it. */
        fun inAccessory(accessory: Rect, tag: Size): StemCellsLinkSwitchLayout {
            val width = swiftRound(accessory.width * Metrics.widthShare)
            return onControl(Rect(accessory.minX + swiftRound((accessory.width - width) / 2), accessory.minY, width, accessory.height), tag)
        }

        /** The tag on [control] where it stands now. */
        fun onControl(control: Rect, tag: Size): StemCellsLinkSwitchLayout {
            val tagSize = Size(ceil(tag.width), ceil(tag.height))
            return StemCellsLinkSwitchLayout(
                control,
                Rect(control.maxX - Metrics.tagInset - tagSize.width, swiftRound(control.minY - tagSize.height / 2), tagSize.width, tagSize.height),
            )
        }
    }
}
