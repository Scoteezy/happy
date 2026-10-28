package com.slopus.happy.layout

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Ported from happy-ios HappyLayout/NewSessionLayout.swift.

/**
 * new/index.tsx on a phone: the place rows sit on the composer, both
 * pinned to the foot of the screen, and ride up on the keyboard.
 *
 *     |   (‹) (Start New Session)                       |  52 below the status bar
 *     |                                                 |
 *     |20| ▭ MacBook                               ⌄ |20|  48 each
 *     |20| ▭ ~/code/happy                          ⌄ |20|
 *     |20| ▭ no worktree                           ⌄ |20|
 *     |                                                 |  8 + 12
 *     |12| ╭ Ask claude code                         ╮ |12|  98 at least
 *     |12| ╰ (◎ claude code ⌄)(⚙)          (🎙)(↑) ╯ |12|
 *     |                                                 |  max(12, safe bottom)
 *
 * The rows are the foot of a scroll view (`mobileConfigScroll`) that fills
 * the space between the header and the composer: `flexGrow` with
 * `justifyContent: 'flex-end'` keeps them down, and a 96dp top pad keeps
 * them clear of the header once they scroll.
 */
class NewSessionScreenLayout(input: Input) {
    object Metrics {
        /** MOBILE_GLASS_HEADER_HEIGHT, below the status bar. */
        const val headerHeight = 52.0
        /** `mobileKeyboardDismissArea`. */
        const val configPaddingTop = 96.0
        /** `mobileInlineConfigWrap`. */
        const val configPaddingHorizontal = 20.0
        const val configPaddingBottom = 8.0
        /** `mobileConfigScrollContent`. */
        const val scrollPaddingBottom = 12.0
        /** `inlineComposerWrap` and `mobileComposerShadow`. */
        const val composerPaddingHorizontal = 12.0
        /** The spacer under the composer: `Math.max(12, safeArea.bottom)`. */
        const val minPaddingBottom = 12.0

        /** `configRow`. */
        const val rowPaddingHorizontal = 12.0
        const val rowPaddingVertical = 12.0
        const val rowMinHeight = 48.0
        const val rowGap = 8.0
        const val rowRadius = 12.0
        const val rowIconSize = 15.0
        const val rowChevronSize = 13.0

        /** `offlineHelp`. */
        const val offlinePaddingHorizontal = 12.0
        const val offlinePaddingVertical = 10.0
        const val offlineGap = 8.0
        const val offlineIconSize = 14.0
        const val offlineTitleMarginBottom = 4.0
    }

    sealed interface Row {
        /** A picker row: an icon, a one-line value and a chevron. */
        class Config(val label: Measurable) : Row
        /** "Machine is offline" and the help under it. */
        class Offline(val title: Measurable, val body: Measurable) : Row
    }

    class Input(
        val size: Size,
        /** The status bar: the header row starts here. */
        val safeTop: Double,
        val safeBottom: Double,
        /** How much of the screen's foot the keyboard covers. */
        val keyboardOverlap: Double = 0.0,
        /** `layout.maxWidth`: on a phone, the longer side of the screen. */
        val maxWidth: Double = Double.MAX_VALUE,
        val composerHeight: Double,
        val rows: List<Row>,
    )

    /** A row in the scroll view's content. Offline help has a body and no chevron; the rest are inside [frame]. */
    data class RowFrame(val frame: Rect, val icon: Rect, val label: Rect, val chevron: Rect?, val body: Rect?)

    /** The bottom of the header row. */
    val headerBottom: Double
    /** The scroll view between the header and the composer. */
    val scroll: Rect
    val contentSize: Size
    /** The rows and their bottom pad (`mobileConfigHeight`). */
    val configHeight: Double
    val rows: List<RowFrame>
    val composer: Rect

    init {
        val m = Metrics
        val width = max(0.0, input.size.width)
        headerBottom = input.safeTop + m.headerHeight

        val laneWidth = min(input.maxWidth, width)
        val laneX = (width - laneWidth) / 2
        val bottom = input.size.height - input.keyboardOverlap - max(m.minPaddingBottom, input.safeBottom)
        composer = Rect(
            laneX + m.composerPaddingHorizontal,
            bottom - input.composerHeight,
            max(0.0, laneWidth - 2 * m.composerPaddingHorizontal),
            input.composerHeight,
        )
        scroll = Rect(0.0, headerBottom, width, max(0.0, composer.minY - headerBottom))

        val rowWidth = max(0.0, laneWidth - 2 * m.configPaddingHorizontal)
        val placed = mutableListOf<RowFrame>()
        var y = 0.0
        for (row in input.rows) {
            val frame = when (row) {
                is Row.Config -> {
                    val labelWidth = max(0.0, rowWidth - 2 * m.rowPaddingHorizontal - m.rowIconSize - m.rowChevronSize - 2 * m.rowGap)
                    val text = row.label.size(labelWidth)
                    val height = max(m.rowMinHeight, 2 * m.rowPaddingVertical + maxOf(m.rowIconSize, text.height, m.rowChevronSize))
                    val mid = height / 2
                    val labelX = m.rowPaddingHorizontal + m.rowIconSize + m.rowGap
                    RowFrame(
                        frame = Rect(0.0, y, rowWidth, height),
                        icon = Rect(m.rowPaddingHorizontal, mid - m.rowIconSize / 2, m.rowIconSize, m.rowIconSize),
                        label = Rect(labelX, mid - text.height / 2, labelWidth, text.height),
                        chevron = Rect(rowWidth - m.rowPaddingHorizontal - m.rowChevronSize, mid - m.rowChevronSize / 2, m.rowChevronSize, m.rowChevronSize),
                        body = null,
                    )
                }
                is Row.Offline -> {
                    val textX = m.offlinePaddingHorizontal + m.offlineIconSize + m.offlineGap
                    val textWidth = max(0.0, rowWidth - textX - m.offlinePaddingHorizontal)
                    val titleSize = row.title.size(textWidth)
                    val bodySize = row.body.size(textWidth)
                    val column = titleSize.height + m.offlineTitleMarginBottom + bodySize.height
                    val height = 2 * m.offlinePaddingVertical + max(m.offlineIconSize, column)
                    val top = m.offlinePaddingVertical
                    RowFrame(
                        frame = Rect(0.0, y, rowWidth, height),
                        icon = Rect(m.offlinePaddingHorizontal, top, m.offlineIconSize, m.offlineIconSize),
                        label = Rect(textX, top, titleSize.width, titleSize.height),
                        chevron = null,
                        body = Rect(textX, top + titleSize.height + m.offlineTitleMarginBottom, bodySize.width, bodySize.height),
                    )
                }
            }
            placed += frame
            y += frame.frame.height
        }
        configHeight = y + m.configPaddingBottom

        // The rows hang from the bottom of content at least as tall as the scroll view.
        val needed = m.configPaddingTop + configHeight + m.scrollPaddingBottom
        val contentHeight = max(scroll.height, needed)
        contentSize = Size(width, contentHeight)
        val rowsTop = contentHeight - m.scrollPaddingBottom - configHeight
        val rowsX = laneX + m.configPaddingHorizontal
        rows = placed.map { it.copy(frame = it.frame.offsetBy(rowsX, rowsTop)) }
    }
}

/**
 * The composer on a phone (`mobileInputBox`): the prompt over a row of the
 * harness, settings, the microphone and Send.
 *
 *     ╭ |8+10| Ask claude code ………………………………… |6+6| ╮   44 at least
 *     │ |8| (◎ claude code ⌄)|2|(⚙) ……… (🎙)|2|(↑) |6| │   40
 *     ╰──────────────────────────────────────────────╯   98 at least
 */
class NewSessionComposerLayout(input: Input) {
    object Metrics {
        const val minHeight = 98.0
        const val radius = 26.0
        const val paddingVertical = 6.0
        const val paddingLeft = 8.0
        const val paddingRight = 6.0
        /** `mobileInputField`. */
        const val fieldMinHeight = 44.0
        const val fieldPaddingLeft = 10.0
        const val fieldPaddingRight = 6.0
        /** MultiTextInput: one 22dp line, up to 120 (`COMPACT_COMPOSER_INPUT_MAX_HEIGHT`). */
        const val lineHeight = 22.0
        const val inputMaxHeight = 120.0
        /** `mobileActionButtonsContainer`. */
        const val actionRowMinHeight = 40.0
        const val actionGap = 2.0
        /** `composerAgentButton`. */
        const val agentHeight = 36.0
        const val agentMaxWidth = 132.0
        const val agentPaddingHorizontal = 9.0
        const val agentGap = 6.0
        const val agentIconSize = 14.0
        const val agentChevronSize = 12.0
        /** `composerActionButton` and `mobileSendButton`. */
        const val controlSize = 38.0
        const val settingsIconSize = 18.0
        const val micIconSize = 21.0
        const val sendIconSize = 18.0
    }

    class Input(
        val width: Double,
        /** One device pixel: the glass's border. */
        val hairline: Double,
        /** The text's own height at the input's width. */
        val textHeight: Double,
        val agentLabel: Measurable,
        val showsSettings: Boolean,
        /** The prompt's line at the text size; the text is never shorter, the 44 field and the 120 cap stay. */
        val lineHeight: Double = Metrics.lineHeight,
    )

    val size: Size
    /** The text view. */
    val input: Rect
    /** The text outgrew 120dp and scrolls. */
    val textScrolls: Boolean
    val actionRow: Rect
    val agent: Rect
    /** Inside [agent], as are the label and chevron. */
    val agentIcon: Rect
    val agentLabel: Rect
    val agentChevron: Rect
    val settings: Rect?
    val mic: Rect
    val send: Rect

    init {
        val m = Metrics
        val border = input.hairline
        val width = max(0.0, input.width)
        val contentX = border + m.paddingLeft
        val contentWidth = max(0.0, width - 2 * border - m.paddingLeft - m.paddingRight)

        val textHeight = min(m.inputMaxHeight, max(input.lineHeight, input.textHeight))
        textScrolls = input.textHeight > m.inputMaxHeight
        val fieldY = border + m.paddingVertical
        val fieldHeight = max(m.fieldMinHeight, textHeight)
        // Centred to the pixel above, so the input keeps its place when its frame is snapped.
        val slack = (fieldHeight - textHeight) / 2
        this.input = Rect(
            contentX + m.fieldPaddingLeft,
            fieldY + if (border > 0) floor(slack / border + 0.001) * border else slack,
            max(0.0, contentWidth - m.fieldPaddingLeft - m.fieldPaddingRight),
            textHeight,
        )

        val rowY = fieldY + fieldHeight
        val rowHeight = max(m.actionRowMinHeight, m.controlSize)
        actionRow = Rect(contentX, rowY, contentWidth, rowHeight)
        send = Rect(contentX + contentWidth - m.controlSize, rowY + (rowHeight - m.controlSize) / 2, m.controlSize, m.controlSize)
        mic = send.offsetBy(-(m.controlSize + m.actionGap), 0.0)
        val leftWidth = max(0.0, mic.minX - m.actionGap - contentX)

        val chrome = 2 * m.agentPaddingHorizontal + m.agentIconSize + m.agentChevronSize + 2 * m.agentGap
        val agentLimit = min(m.agentMaxWidth, leftWidth)
        // One line that shrinks to fit (`numberOfLines={1}`, `flexShrink: 1`).
        val labelRoom = max(0.0, agentLimit - chrome)
        val label = input.agentLabel.size(labelRoom)
        val labelWidth = min(label.width, labelRoom)
        agent = Rect(contentX, rowY + (rowHeight - m.agentHeight) / 2, chrome + labelWidth, m.agentHeight)
        val mid = m.agentHeight / 2
        agentIcon = Rect(m.agentPaddingHorizontal, mid - m.agentIconSize / 2, m.agentIconSize, m.agentIconSize)
        agentLabel = Rect(agentIcon.maxX + m.agentGap, mid - label.height / 2, labelWidth, label.height)
        agentChevron = Rect(agentLabel.maxX + m.agentGap, mid - m.agentChevronSize / 2, m.agentChevronSize, m.agentChevronSize)
        // The left group clips what doesn't fit (`overflow: 'hidden'`).
        val settingsX = agent.maxX + m.actionGap
        settings = if (input.showsSettings && settingsX + m.controlSize <= contentX + leftWidth) {
            Rect(settingsX, rowY + (rowHeight - m.controlSize) / 2, m.controlSize, m.controlSize)
        } else {
            null
        }

        size = Size(width, max(m.minHeight, rowY + rowHeight + m.paddingVertical + border))
    }
}

/**
 * The popover a picker opens in (`nativePickerViewportPopover`): 20dp in
 * from each side, above the row or the composer that opened it, never
 * above the header.
 */
class NewSessionPickerLayout(input: Input) {
    object Metrics {
        const val inset = 20.0
        const val radius = 24.0
        const val paddingHorizontal = 12.0
        const val paddingVertical = 10.0
        const val maxHeight = 264.0
        /** Room kept under the header. */
        const val headerGap = 12.0
        /** Above the composer, for the harness and settings pickers. */
        const val composerGap = 10.0
        /** Above the row, for the place pickers. */
        const val rowGap = 8.0
        /** `NATIVE_PICKER_TOP`, less the first row's: each place row is taken to be 48 tall. */
        const val rowStep = 48.0
        /** AnimatedPopup: in from 0.96 and 8dp lower over 190ms. */
        const val enterScale = 0.96
        const val enterOffset = 8.0
        const val enterDuration = 0.19
    }

    /** What opened the popover. */
    sealed interface Anchor {
        /** The harness button or the settings gear. */
        data object Composer : Anchor
        /** A place row: 0 the machine, 1 the path, 2 the worktree. */
        data class Row(val index: Int) : Anchor
    }

    class Input(
        val size: Size,
        val safeTop: Double,
        val safeBottom: Double,
        val composerHeight: Double,
        /** `mobileConfigHeight`. */
        val configHeight: Double,
        val anchor: Anchor,
        /** The popover's content, before its padding. */
        val contentHeight: Double,
    )

    val frame: Rect
    /** Inside [frame]. */
    val content: Rect

    init {
        val m = Metrics
        val height = min(m.maxHeight, input.contentHeight + 2 * m.paddingVertical)
        val headerBottom = input.safeTop + NewSessionScreenLayout.Metrics.headerHeight
        // Measured against the window, not the keyboard: pickers open with the keyboard down.
        val composerTop = input.size.height - input.safeBottom - input.composerHeight
        val top = when (val anchor = input.anchor) {
            Anchor.Composer -> max(headerBottom + m.headerGap, composerTop - height - m.composerGap)
            is Anchor.Row -> {
                val configTop = composerTop - NewSessionScreenLayout.Metrics.scrollPaddingBottom - input.configHeight
                val rowTop = configTop + anchor.index * m.rowStep
                max(headerBottom + m.headerGap, rowTop - height - m.rowGap)
            }
        }
        val width = max(0.0, input.size.width - 2 * m.inset)
        frame = Rect(m.inset, top, width, height)
        content = Rect(m.paddingHorizontal, m.paddingVertical, max(0.0, width - 2 * m.paddingHorizontal), max(0.0, height - 2 * m.paddingVertical))
    }
}

/**
 * A picker's rows inside the popover: `PickerContent`, `PathPickerContent`
 * and the composer's settings pages, embedded. Some rows stay put above a
 * list that scrolls past 176dp.
 */
class NewSessionPickerContentLayout(input: Input) {
    object Metrics {
        /** `embeddedOptionList`. */
        const val listMaxHeight = 176.0
        /** `embeddedOption`. */
        const val optionPaddingVertical = 8.0
        const val optionPaddingHorizontal = 4.0
        const val optionGap = 12.0
        /** `embeddedSearchRow`. */
        const val searchPaddingVertical = 8.0
        const val searchPaddingHorizontal = 4.0
        const val searchGap = 10.0
        const val searchMarginBottom = 4.0
        const val searchIconSize = 16.0
        /** `embeddedPathInputRow`. */
        const val pathMinHeight = 38.0
        const val pathFieldMinHeight = 34.0
        /** `sectionLabel` and `pathMetaText`. */
        const val textPaddingHorizontal = 4.0
        const val textPaddingBottom = 8.0
        /** `divider`. */
        const val dividerMarginHorizontal = 12.0
        const val dividerMarginVertical = 4.0
        /** `emptyText`. */
        const val emptyPaddingVertical = 20.0
        /** `composerPickerHeader`. */
        const val backMinHeight = 40.0
        const val backButtonSize = 36.0
        const val backIconSize = 20.0
        const val backGap = 4.0
        const val backPaddingBottom = 4.0
        /** The containers' padding: `embeddedContainer` and the settings page's `composerSettingsContainer`. */
        const val embeddedPaddingBottom = 2.0
        const val settingsPaddingTop = 4.0
        const val settingsPaddingBottom = 8.0
    }

    sealed interface Element {
        /** The search field: a 16dp glyph and a one-line field. */
        class Search(val fieldHeight: Double) : Element
        /** The path field (`pathInputRow`). */
        class PathField(val fieldHeight: Double) : Element
        /** A section heading or a line of meta text. */
        class Text(val text: Measurable) : Element
        /** A row: a glyph, a label (with a line under it) and maybe a trailing glyph. */
        class Option(val icon: Double, val label: Measurable, val detail: Measurable?, val trailing: Double?) : Element
        data object Divider : Element
        /** "no results", centred. */
        class Empty(val text: Measurable) : Element
        /** A settings page's back button and title. */
        class Back(val title: Measurable) : Element
    }

    class Input(
        val width: Double,
        /** Rows above the list, which never scroll. */
        val header: List<Element> = emptyList(),
        val list: List<Element>,
        /** Null for a list that never scrolls (the settings page). */
        val listMaxHeight: Double? = Metrics.listMaxHeight,
        val paddingTop: Double = 0.0,
        val paddingBottom: Double = Metrics.embeddedPaddingBottom,
    )

    /** In the content for header rows, in the list for list rows; the rest are inside [frame]. */
    data class ElementFrame(
        val frame: Rect,
        val icon: Rect? = null,
        /** The label, the field, the heading or the title. */
        val label: Rect? = null,
        val detail: Rect? = null,
        val trailing: Rect? = null,
    )

    val header: List<ElementFrame>
    /** The list's viewport, in the content. */
    val listFrame: Rect
    val listContentHeight: Double
    val list: List<ElementFrame>
    /** The content's height, padding included. */
    val height: Double

    init {
        val width = max(0.0, input.width)
        var y = input.paddingTop
        header = input.header.map { element ->
            val (frame, advance) = place(element, y, width)
            y += advance
            frame
        }
        var listY = 0.0
        list = input.list.map { element ->
            val (frame, advance) = place(element, listY, width)
            listY += advance
            frame
        }
        listContentHeight = listY
        val viewport = input.listMaxHeight?.let { min(it, listY) } ?: listY
        listFrame = Rect(0.0, y, width, viewport)
        height = y + viewport + input.paddingBottom
    }

    companion object {
        /** A row's frames and how far down the next one starts. */
        private fun place(element: Element, y: Double, width: Double): Pair<ElementFrame, Double> {
            val m = Metrics
            return when (element) {
                is Element.Search -> {
                    val fieldHeight = element.fieldHeight
                    val height = 2 * m.searchPaddingVertical + max(m.searchIconSize, fieldHeight)
                    val fieldX = m.searchPaddingHorizontal + m.searchIconSize + m.searchGap
                    ElementFrame(
                        frame = Rect(0.0, y, width, height),
                        icon = Rect(m.searchPaddingHorizontal, (height - m.searchIconSize) / 2, m.searchIconSize, m.searchIconSize),
                        label = Rect(fieldX, (height - fieldHeight) / 2, max(0.0, width - fieldX - m.searchPaddingHorizontal), fieldHeight),
                    ) to height + m.searchMarginBottom
                }
                is Element.PathField -> {
                    val field = max(m.pathFieldMinHeight, element.fieldHeight)
                    val height = maxOf(m.pathMinHeight, field, m.searchIconSize)
                    val fieldX = m.searchPaddingHorizontal + m.searchIconSize + m.searchGap
                    ElementFrame(
                        frame = Rect(0.0, y, width, height),
                        icon = Rect(m.searchPaddingHorizontal, (height - m.searchIconSize) / 2, m.searchIconSize, m.searchIconSize),
                        label = Rect(fieldX, (height - field) / 2, max(0.0, width - fieldX - m.searchPaddingHorizontal), field),
                    ) to height + m.searchMarginBottom
                }
                is Element.Text -> {
                    val size = element.text.size(max(0.0, width - 2 * m.textPaddingHorizontal))
                    val height = size.height + m.textPaddingBottom
                    ElementFrame(
                        frame = Rect(0.0, y, width, height),
                        label = Rect(m.textPaddingHorizontal, 0.0, size.width, size.height),
                    ) to height
                }
                is Element.Option -> {
                    val icon = element.icon
                    val trailing = element.trailing
                    val textX = m.optionPaddingHorizontal + icon + m.optionGap
                    val trailingRoom = trailing?.let { it + m.optionGap } ?: 0.0
                    val textWidth = max(0.0, width - textX - m.optionPaddingHorizontal - trailingRoom)
                    val labelSize = element.label.size(textWidth)
                    val detailSize = element.detail?.size(textWidth)
                    val column = labelSize.height + (detailSize?.height ?: 0.0)
                    val height = 2 * m.optionPaddingVertical + maxOf(icon, column, trailing ?: 0.0)
                    val columnY = (height - column) / 2
                    ElementFrame(
                        frame = Rect(0.0, y, width, height),
                        icon = Rect(m.optionPaddingHorizontal, (height - icon) / 2, icon, icon),
                        label = Rect(textX, columnY, textWidth, labelSize.height),
                        detail = detailSize?.let { Rect(textX, columnY + labelSize.height, textWidth, it.height) },
                        trailing = trailing?.let { Rect(width - m.optionPaddingHorizontal - it, (height - it) / 2, it, it) },
                    ) to height
                }
                Element.Divider -> ElementFrame(
                    frame = Rect(m.dividerMarginHorizontal, y + m.dividerMarginVertical, max(0.0, width - 2 * m.dividerMarginHorizontal), 1.0),
                ) to 1 + 2 * m.dividerMarginVertical
                is Element.Empty -> {
                    val size = element.text.size(width)
                    val height = size.height + 2 * m.emptyPaddingVertical
                    ElementFrame(
                        frame = Rect(0.0, y, width, height),
                        label = Rect((width - size.width) / 2, m.emptyPaddingVertical, size.width, size.height),
                    ) to height
                }
                is Element.Back -> {
                    val titleX = m.backButtonSize + m.backGap
                    val size = element.title.size(max(0.0, width - titleX))
                    val row = maxOf(m.backMinHeight, m.backButtonSize, size.height)
                    ElementFrame(
                        frame = Rect(0.0, y, width, row),
                        icon = Rect(0.0, (row - m.backButtonSize) / 2, m.backButtonSize, m.backButtonSize),
                        label = Rect(titleX, (row - size.height) / 2, max(0.0, width - titleX), size.height),
                    ) to row + m.backPaddingBottom
                }
            }
        }
    }
}
