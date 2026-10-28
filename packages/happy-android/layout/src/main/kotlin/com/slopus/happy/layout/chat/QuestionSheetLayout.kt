package com.slopus.happy.layout.chat

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.max
import kotlin.math.min

/**
 * AgentQuestionModal: the full-screen answer sheet for a question the chat
 * can't show inline (a written answer, or more than options). Ported from
 * happy-ios HappyLayout/Chat/QuestionSheetLayout.swift.
 *
 *     safe top
 *     16 [Cancel] ──── title, centred ──── [60] 16      14 above and below
 *     ─ hairline ─────────────────────────────────────
 *     scroll, 16 in, 28 between questions:
 *       [HEADER chip] 8 [•]                              8 between rows
 *       Question text (17 semibold)
 *       Choose as many as apply
 *       ┌ 14 [☐ 22] 12 label / description ┐ radius 12
 *       Your own answer (4 above, 6 below)
 *       ┌ text input, min 48 ┐ radius 12
 *     ─ hairline ─────────────────────────────────────
 *     16 ┌ Send answer, 50 tall, radius 14 ┐ 16          12 above, 12 + safe bottom below
 *
 * The footer rides on the keyboard while it is up, so the button is never under it.
 */
data class QuestionSheetLayout(
    val header: Rect,
    val cancel: Rect,
    val title: Rect,
    val headerDivider: Rect,
    val scroll: Rect,
    val contentSize: Size,
    val questions: List<PlacedQuestion>,
    val error: Rect?,
    val footer: Rect,
    val footerDivider: Rect,
    val submit: Rect,
    val submitLabel: Rect,
) {
    object Metrics {
        const val headerPaddingH = 16.0
        const val headerPaddingV = 14.0
        const val headerSpacer = 60.0
        const val contentPadding = 16.0
        const val questionGap = 28.0
        const val rowGap = 8.0
        const val chipPaddingH = 8.0
        const val chipPaddingV = 4.0
        const val chipRadius = 4.0
        const val dotSize = 6.0
        const val optionPadding = 14.0
        const val optionRadius = 12.0
        const val optionGap = 12.0
        const val checkboxSize = 22.0
        const val checkboxTop = 1.0
        const val checkboxRadius = 6.0
        const val descriptionGap = 2.0
        const val customMarginTop = 4.0
        const val customGap = 6.0
        const val inputMinHeight = 48.0
        const val inputPaddingH = 14.0
        const val inputPaddingV = 12.0
        const val inputRadius = 12.0
        const val footerPaddingH = 16.0
        const val footerPaddingTop = 12.0
        const val footerPaddingBottom = 12.0
        const val submitHeight = 50.0
        const val submitRadius = 14.0
        /** The scroll content ends this far past the safe area (96). */
        const val contentBottomExtra = 96.0
    }

    class Option(val label: Measurable, val description: Measurable? = null)

    /** "Your own answer" and the text the person has typed (the input's text, measured inside its padding). */
    class Custom(val label: Measurable, val text: Measurable)

    class Question(
        val chip: Measurable,
        val showsDot: Boolean,
        val text: Measurable,
        val hint: Measurable? = null,
        val options: List<Option>,
        val custom: Custom? = null,
    )

    class Input(
        val size: Size,
        val safeArea: LayoutInsets,
        /** The keyboard's top in the sheet, null while it is down. */
        val keyboardTop: Double? = null,
        val hairline: Double,
        val cancel: Measurable,
        val title: Measurable,
        val submit: Measurable,
        val questions: List<Question>,
        val error: Measurable? = null,
    )

    data class PlacedOption(val box: Rect, val checkbox: Rect, val label: Rect, val description: Rect?)

    /** One question's frames, in the scroll content. */
    data class PlacedQuestion(
        val chip: Rect,
        val chipText: Rect,
        val dot: Rect?,
        val text: Rect,
        val hint: Rect?,
        val options: List<PlacedOption>,
        val customLabel: Rect?,
        val input: Rect?,
    )

    companion object {
        operator fun invoke(input: Input): QuestionSheetLayout {
            val m = Metrics
            val width = max(0.0, input.size.width)
            val height = max(0.0, input.size.height)

            // Header
            val top = input.safeArea.top
            val cancelSize = input.cancel.size(width / 2)
            val titleX = m.headerPaddingH + cancelSize.width
            val titleWidth = max(0.0, width - 2 * m.headerPaddingH - cancelSize.width - m.headerSpacer)
            val titleSize = input.title.size(titleWidth)
            val rowHeight = max(cancelSize.height, titleSize.height)
            val rowTop = top + m.headerPaddingV
            val cancel = Rect(m.headerPaddingH, rowTop + (rowHeight - cancelSize.height) / 2, cancelSize.width, cancelSize.height)
            val title = Rect(titleX + (titleWidth - titleSize.width) / 2, rowTop + (rowHeight - titleSize.height) / 2, titleSize.width, titleSize.height)
            val headerHeight = rowHeight + 2 * m.headerPaddingV + input.hairline
            val header = Rect(0.0, top, width, headerHeight)
            val headerDivider = Rect(0.0, header.maxY - input.hairline, width, input.hairline)

            // Footer, on the keyboard or the safe area.
            val keyboardTop = input.keyboardTop
            val keyboardUp = keyboardTop != null && keyboardTop < height - 0.5
            val bottomPadding = m.footerPaddingBottom + if (keyboardUp) 0.0 else input.safeArea.bottom
            val footerHeight = input.hairline + m.footerPaddingTop + m.submitHeight + bottomPadding
            val footerBottom = if (keyboardUp) min(height, keyboardTop!!) else height
            val footer = Rect(0.0, footerBottom - footerHeight, width, footerHeight)
            val footerDivider = Rect(0.0, footer.minY, width, input.hairline)
            val submit = Rect(m.footerPaddingH, footer.minY + input.hairline + m.footerPaddingTop, max(0.0, width - 2 * m.footerPaddingH), m.submitHeight)
            val labelSize = input.submit.size(submit.width)
            val submitLabel = Rect(
                submit.minX + (submit.width - labelSize.width) / 2,
                submit.minY + (submit.height - labelSize.height) / 2,
                labelSize.width,
                labelSize.height,
            )

            // The questions, in the scroll content between the two bars. It
            // ends 96 past the safe area, as happy-app's does.
            val scroll = Rect(0.0, header.maxY, width, max(0.0, footer.minY - header.maxY))
            val contentWidth = max(0.0, width - 2 * m.contentPadding)
            var y = m.contentPadding
            val placed = input.questions.mapIndexed { index, question ->
                if (index > 0) y += m.questionGap
                val x = m.contentPadding
                val chipText = question.chip.size(max(0.0, contentWidth - 2 * m.chipPaddingH - if (question.showsDot) m.rowGap + m.dotSize else 0.0))
                val chip = Rect(x, y, chipText.width + 2 * m.chipPaddingH, chipText.height + 2 * m.chipPaddingV)
                val dot = if (question.showsDot) Rect(chip.maxX + m.rowGap, chip.midY - m.dotSize / 2, m.dotSize, m.dotSize) else null
                y = chip.maxY + m.rowGap
                val text = Rect(x, y, contentWidth, question.text.height(contentWidth))
                y = text.maxY
                val hint = question.hint?.let { content ->
                    y += m.rowGap
                    Rect(x, y, contentWidth, content.height(contentWidth)).also { y = it.maxY }
                }
                val options = question.options.map { option ->
                    y += m.rowGap
                    val columnX = x + m.optionPadding + m.checkboxSize + m.optionGap
                    val columnWidth = max(0.0, x + contentWidth - m.optionPadding - columnX)
                    val labelHeight = option.label.height(columnWidth)
                    val descriptionHeight = option.description?.height(columnWidth)
                    val columnHeight = labelHeight + (descriptionHeight?.let { m.descriptionGap + it } ?: 0.0)
                    val inner = max(columnHeight, m.checkboxTop + m.checkboxSize)
                    val box = Rect(x, y, contentWidth, inner + 2 * m.optionPadding)
                    PlacedOption(
                        box = box,
                        checkbox = Rect(x + m.optionPadding, y + m.optionPadding + m.checkboxTop, m.checkboxSize, m.checkboxSize),
                        label = Rect(columnX, y + m.optionPadding, columnWidth, labelHeight),
                        description = descriptionHeight?.let { Rect(columnX, y + m.optionPadding + labelHeight + m.descriptionGap, columnWidth, it) },
                    ).also { y = box.maxY }
                }
                var customLabel: Rect? = null
                var inputFrame: Rect? = null
                question.custom?.let { custom ->
                    y += m.rowGap + m.customMarginTop
                    customLabel = Rect(x, y, contentWidth, custom.label.height(contentWidth)).also { y = it.maxY + m.customGap }
                    val textHeight = custom.text.height(max(0.0, contentWidth - 2 * m.inputPaddingH))
                    inputFrame = Rect(x, y, contentWidth, max(m.inputMinHeight, textHeight + 2 * m.inputPaddingV)).also { y = it.maxY }
                }
                PlacedQuestion(
                    chip = chip,
                    chipText = Rect(chip.minX + m.chipPaddingH, chip.minY + m.chipPaddingV, chipText.width, chipText.height),
                    dot = dot,
                    text = text,
                    hint = hint,
                    options = options,
                    customLabel = customLabel,
                    input = inputFrame,
                )
            }
            val error = input.error?.let { content ->
                y += m.questionGap
                Rect(m.contentPadding, y, contentWidth, content.height(contentWidth)).also { y = it.maxY }
            }
            val contentSize = Size(width, y + input.safeArea.bottom + m.contentBottomExtra)
            return QuestionSheetLayout(header, cancel, title, headerDivider, scroll, contentSize, placed, error, footer, footerDivider, submit, submitLabel)
        }
    }
}
