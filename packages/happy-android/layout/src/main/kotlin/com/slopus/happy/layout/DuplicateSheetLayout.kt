package com.slopus.happy.layout

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * components/DuplicateSheet.tsx (happy-ios SecondaryDuplicateSheetLayout.swift):
 * a card centred over a dimmed backdrop with a header (title, subtitle), a
 * list of rewind points capped at 420 and an action bar (Cancel, Duplicate).
 * Sized by utils/duplicateSheetLayout.ts: `min(560, width - 32)` wide, at
 * most 85% of the window tall; the list shrinks first.
 */
data class DuplicateSheetLayout(
    /** The card, in window coordinates. */
    val card: Rect,
    // The rest are in the card's coordinates.
    val title: Rect,
    val subtitle: Rect,
    val headerDivider: Rect,
    val list: Rect,
    val listContentSize: Size,
    val rows: List<Row>,
    /** The spinner or message, in the list's content coordinates. */
    val body: Rect?,
    val actionsDivider: Rect,
    val cancel: Rect,
    val confirm: Rect,
) {
    object Metrics {
        const val horizontalMargin = 16.0
        const val maxWidth = 560.0
        const val maxHeightRatio = 0.85
        const val cornerRadius = 16.0
        const val headerPaddingHorizontal = 20.0
        const val headerPaddingTop = 20.0
        const val headerPaddingBottom = 12.0
        const val subtitleSpacing = 4.0
        const val listMaxHeight = 420.0
        const val listPaddingVertical = 8.0
        const val rowPaddingHorizontal = 20.0
        const val rowPaddingVertical = 12.0
        const val rowMetaSpacing = 4.0
        const val messagePaddingVertical = 32.0
        const val messagePaddingHorizontal = 20.0
        const val actionsPadding = 16.0
        const val actionsGap = 8.0
        const val buttonPaddingVertical = 11.0
        const val buttonCornerRadius = 10.0
    }

    /** What fills the list. */
    sealed interface Body {
        /** A spinner of this size. */
        data class Loading(val size: Size) : Body
        /** An error or the empty copy, centred. */
        class Message(val text: Measurable) : Body
        /** Each point's text (three lines at most) and its age. */
        class Rows(val items: List<Pair<Measurable, Measurable>>) : Body
    }

    class Input(
        val window: Size,
        val title: Measurable,
        val subtitle: Measurable,
        val body: Body,
        /** One line of the buttons' 15pt text. */
        val buttonTextHeight: Double,
        val hairline: Double,
    )

    data class Row(
        /** In the list's content coordinates. */
        val frame: Rect,
        val text: Rect,
        val meta: Rect,
        val divider: Rect,
    )

    companion object {
        operator fun invoke(input: Input): DuplicateSheetLayout {
            val m = Metrics
            val hairline = input.hairline
            val width = min(m.maxWidth, max(0.0, floor(input.window.width) - m.horizontalMargin * 2))
            // Swift's `.rounded()`: half away from zero.
            val maxHeight = floor(floor(input.window.height) * m.maxHeightRatio + 0.5)
            // The card's hairline border insets everything inside it.
            val inner = max(0.0, width - hairline * 2)

            val textWidth = max(0.0, inner - m.headerPaddingHorizontal * 2)
            val title = Rect(hairline + m.headerPaddingHorizontal, hairline + m.headerPaddingTop, textWidth, input.title.height(textWidth))
            val subtitle = Rect(title.x, title.maxY + m.subtitleSpacing, textWidth, input.subtitle.height(textWidth))
            val headerDivider = Rect(hairline, subtitle.maxY + m.headerPaddingBottom, inner, hairline)

            var y = m.listPaddingVertical
            val rows = mutableListOf<Row>()
            var body: Rect? = null
            when (val content = input.body) {
                is Body.Loading -> {
                    val size = content.size
                    body = Rect((inner - size.width) / 2, y + m.messagePaddingVertical, size.width, size.height)
                    y = body.maxY + m.messagePaddingVertical
                }
                is Body.Message -> {
                    val size = content.text.size(max(0.0, inner - m.messagePaddingHorizontal * 2))
                    body = Rect((inner - size.width) / 2, y + m.messagePaddingVertical, size.width, size.height)
                    y = body.maxY + m.messagePaddingVertical
                }
                is Body.Rows -> {
                    val rowTextWidth = max(0.0, inner - m.rowPaddingHorizontal * 2)
                    for ((textItem, metaItem) in content.items) {
                        val textHeight = textItem.height(rowTextWidth)
                        val metaHeight = metaItem.height(rowTextWidth)
                        val text = Rect(m.rowPaddingHorizontal, y + m.rowPaddingVertical, rowTextWidth, textHeight)
                        val meta = Rect(m.rowPaddingHorizontal, text.maxY + m.rowMetaSpacing, rowTextWidth, metaHeight)
                        // The hairline border is part of the row's box.
                        val height = m.rowPaddingVertical * 2 + textHeight + m.rowMetaSpacing + metaHeight + hairline
                        val frame = Rect(0.0, y, inner, height)
                        rows += Row(frame, text, meta, Rect(0.0, frame.maxY - hairline, inner, hairline))
                        y = frame.maxY
                    }
                }
            }
            val contentHeight = y + m.listPaddingVertical

            // The list is the only part that shrinks.
            val buttonHeight = m.buttonPaddingVertical * 2 + input.buttonTextHeight
            val actionsHeight = hairline + m.actionsPadding * 2 + buttonHeight
            val fixed = headerDivider.maxY + actionsHeight + hairline
            val listHeight = max(0.0, minOf(contentHeight, m.listMaxHeight, maxHeight - fixed))
            val list = Rect(hairline, headerDivider.maxY, inner, listHeight)

            val actionsDivider = Rect(hairline, list.maxY, inner, hairline)
            val buttonsY = actionsDivider.maxY + m.actionsPadding
            val buttonWidth = max(0.0, (inner - m.actionsPadding * 2 - m.actionsGap) / 2)
            val cancel = Rect(hairline + m.actionsPadding, buttonsY, buttonWidth, buttonHeight)
            val confirm = Rect(cancel.maxX + m.actionsGap, buttonsY, buttonWidth, buttonHeight)

            val height = cancel.maxY + m.actionsPadding + hairline
            return DuplicateSheetLayout(
                card = Rect((input.window.width - width) / 2, (input.window.height - height) / 2, width, height),
                title = title,
                subtitle = subtitle,
                headerDivider = headerDivider,
                list = list,
                listContentSize = Size(inner, contentHeight),
                rows = rows,
                body = body,
                actionsDivider = actionsDivider,
                cancel = cancel,
                confirm = confirm,
            )
        }
    }
}
