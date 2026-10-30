package com.slopus.happy.layout

import kotlin.math.max
import kotlin.math.min

/** HappyLayout/TroubleshootLayout.swift: guide in a grouped list. */
data class TroubleshootGuideLayout(val size: Size, val heading: Rect, val intro: Rect, val steps: List<StepFrames>) {
    object Metrics {
        const val paddingHorizontal = 24.0
        const val paddingTop = 20.0
        const val headingMarginBottom = 8.0
        const val introMarginBottom = 24.0
        const val iconWidth = 26.0
        const val iconHeight = 22.0
        const val iconSpacing = 12.0
        const val stepTitleMarginBottom = 4.0
        const val stepMarginBottom = 20.0
        const val terminalMarginTop = 12.0
    }
    data class Step(val title: Measurable, val body: Measurable, val terminal: Measurable? = null)
    data class StepFrames(val icon: Rect, val title: Rect, val body: Rect, val terminal: Rect?)

    companion object {
        operator fun invoke(width: Double, maxWidth: Double? = null, heading: Measurable, intro: Measurable, steps: List<Step>): TroubleshootGuideLayout {
            val m = Metrics
            val w = max(0.0, width)
            val blockWidth = maxWidth?.let { min(w, it) } ?: w
            val innerWidth = max(0.0, blockWidth - m.paddingHorizontal * 2)
            val x = (w - blockWidth) / 2 + m.paddingHorizontal
            val headingFrame = Rect(x, m.paddingTop, innerWidth, heading.height(innerWidth))
            val introFrame = Rect(x, headingFrame.maxY + m.headingMarginBottom, innerWidth, intro.height(innerWidth))
            var y = introFrame.maxY + m.introMarginBottom
            val textX = x + m.iconWidth + m.iconSpacing
            val textWidth = max(0.0, innerWidth - m.iconWidth - m.iconSpacing)
            val frames = steps.map { step ->
                val icon = Rect(x, y, m.iconWidth, m.iconHeight)
                val title = Rect(textX, y, textWidth, step.title.height(textWidth))
                val body = Rect(textX, title.maxY + m.stepTitleMarginBottom, textWidth, step.body.height(textWidth))
                val terminal = step.terminal?.let { Rect(textX, body.maxY + m.terminalMarginTop, textWidth, it.height(textWidth)) }
                y = max(icon.maxY, terminal?.maxY ?: body.maxY) + m.stepMarginBottom
                StepFrames(icon, title, body, terminal)
            }
            return TroubleshootGuideLayout(Size(w, y), headingFrame, introFrame, frames)
        }
    }
}

/** Copy AI prompt, centred, with its hint below. */
data class CopyPromptLayout(val size: Size, val button: Rect, val hint: Rect) {
    object Metrics {
        const val paddingTop = 20.0
        const val paddingHorizontal = 24.0
        const val buttonWidth = 260.0
        const val hintMarginTop = 8.0
    }
    companion object {
        operator fun invoke(width: Double, maxWidth: Double? = null, button: Measurable, hint: Measurable): CopyPromptLayout {
            val m = Metrics
            val w = max(0.0, width)
            val blockWidth = maxWidth?.let { min(w, it) } ?: w
            val innerWidth = max(0.0, blockWidth - m.paddingHorizontal * 2)
            val x = (w - blockWidth) / 2 + m.paddingHorizontal
            val buttonWidth = min(m.buttonWidth, innerWidth)
            val buttonFrame = Rect(x + (innerWidth - buttonWidth) / 2, m.paddingTop, buttonWidth, button.height(buttonWidth))
            val hintFrame = Rect(x, buttonFrame.maxY + m.hintMarginTop, innerWidth, hint.height(innerWidth))
            return CopyPromptLayout(Size(w, hintFrame.maxY), buttonFrame, hintFrame)
        }
    }
}