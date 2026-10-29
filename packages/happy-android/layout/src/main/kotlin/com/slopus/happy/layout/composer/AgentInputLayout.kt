package com.slopus.happy.layout.composer

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.max
import kotlin.math.min

/**
 * multiTextInputLayout.ts: how tall the growing text input is. It grows with
 * its text from one line (plus its padding) up to `maxHeight`, then scrolls.
 * An emptied input drops back to the minimum at once.
 */
data class MultiTextInputLayout(val height: Double, val containerHeight: Double, val scrollEnabled: Boolean) {
    companion object {
        /** `resolveMultiTextInputLayout`. [contentHeight] is the text's measured height, its padding included. */
        operator fun invoke(
            contentHeight: Double,
            hasText: Boolean = true,
            maxHeight: Double,
            lineHeight: Double,
            paddingTop: Double = 0.0,
            paddingBottom: Double = 0.0,
            minimumHeight: Double? = null,
            containerChromeHeight: Double = 0.0,
        ): MultiTextInputLayout {
            val minimumHeight_ = max(lineHeight + paddingTop + paddingBottom, minimumHeight ?: 0.0)
            val cap = max(0.0, maxHeight)
            val minimum = min(minimumHeight_, cap)
            val measured = if (hasText && contentHeight.isFinite()) contentHeight else minimum
            val height = min(cap, max(minimum, measured))
            return MultiTextInputLayout(height, height + max(0.0, containerChromeHeight), measured >= cap && cap > 0)
        }
    }
}

/**
 * The compact mobile composer's card (AgentInput.tsx `mobileUnifiedPanel`
 * and HomeDock.tsx's focused composer, which share every metric; happy-ios
 * Composer/AgentInputCardLayout.swift):
 *
 *     ┌ hairline ──────────────────────────────────────── r 30 ┐
 *     │ 18 [photos 64, 8 apart, 8 below] 18                    │ 18 top
 *     │    8 text (30…120 with its 4 + 4, then scrolls)  8     │ input 44+
 *     │    [+42] 2 [ permission ··· model · effort ] 2 8 [42]  │ row 42
 *     └────────────────────────────────────────────────────────┘ 8 bottom
 *
 * Frames are in the card's bounds. `middleContent` is in `middle`'s.
 */
data class AgentInputCardLayout(
    val size: Size,
    val attachments: Rect?,
    val attachmentInsets: AttachmentInsets,
    val inputContainer: Rect,
    /** The text view, its 4 + 4 padding included. */
    val text: Rect,
    val textScrolls: Boolean,
    /** The full-screen editor's button, over the text's top right corner. */
    val expand: Rect?,
    val textTrailingInset: Double,
    val actionRow: Rect,
    val add: Rect?,
    /** The box between add and send. It clips what it holds. */
    val middle: Rect,
    val middleContent: MiddleContent,
    val primary: Rect,
) {
    object Metrics {
        const val attachmentSize = 64.0
        const val attachmentGap = 8.0
        const val attachmentStripPaddingH = 4.0
        const val attachmentStripMarginH = 8.0
        const val attachmentStripMarginBottom = 8.0
        const val attachmentRadius = 8.0
        const val attachmentBorder = 1.0
        const val attachmentRemoveSize = 18.0
        const val attachmentRemoveIconSize = 10.0
        const val attachmentRemoveOverhang = 6.0
        /** `mobileModeText`: every chip label is 14. */
        const val chipFontSize = 14.0
        const val permissionIconSize = 18.0
        const val primaryIconSize = 16.0
        const val lockIconSize = 14.0
        const val disabledChipOpacity = 0.58
        const val readOnlyTextOpacity = 0.58
        const val expandIconSize = 14.0
        const val expandSize = 30.0

        // resolveMobileComposerMenuGeometry / ActionRow / Middle.
        const val rowGap = 2.0
        const val permissionPaddingLeft = 4.0
        const val permissionPaddingRight = 8.0
        const val modelPaddingLeft = 12.0
        const val modelPaddingRight = 4.0
        /** `mobileModeButton`, the plain model chip. */
        const val plainModelPaddingLeft = 8.0
        const val effortPaddingLeft = 4.0
        const val effortPaddingRight = 6.0
    }

    data class AttachmentInsets(val leading: Double, val top: Double, val trailing: Double) {
        companion object {
            /** The desktop panel's strip: 4 in at both ends, flush with its top. */
            val panel = AttachmentInsets(Metrics.attachmentStripPaddingH, 0.0, Metrics.attachmentStripPaddingH)
        }
    }

    /** A chip's label (or glyph), measured on one line. */
    data class Chip(val labelSize: Size)

    data class Input(
        val width: Double,
        val metrics: MobileComposerMetrics = MobileComposerMetrics.mobile,
        val borderWidth: Double = 0.0,
        val attachmentCount: Int = 0,
        /** The text view's height for its text, its 4 + 4 padding included. */
        val textContentHeight: Double,
        val hasText: Boolean,
        val lineHeight: Double? = null,
        val inputMinHeight: Double? = null,
        val showsAdd: Boolean = false,
        val permission: Chip? = null,
        val model: Chip? = null,
        val modelIsMenu: Boolean = true,
        val effort: Chip? = null,
        /** The "·" between model and effort. */
        val separator: Size = Size.Zero,
        val zenMode: Boolean = false,
        val offersExpand: Boolean = false,
    )

    data class ChipFrame(val frame: Rect, val label: Rect)

    data class MiddleContent(
        val permission: ChipFrame? = null,
        val model: ChipFrame? = null,
        val separator: Rect? = null,
        val effort: ChipFrame? = null,
    )

    companion object {
        operator fun invoke(input: Input): AgentInputCardLayout {
            val m = Metrics
            val metrics = input.metrics
            val width = max(0.0, input.width)
            val border = max(0.0, input.borderWidth)
            val contentX = border + metrics.shellInset
            val contentWidth = max(0.0, width - 2 * border - 2 * metrics.shellInset)
            var y = border + metrics.shellPaddingTop
            val insets = AgentInputInsets(metrics.shellInset, metrics.actionSize, metrics.addIconSize)

            // The strip runs edge to edge, so photos scroll under the card's side.
            val textLeft = contentX + insets.inputContainerPaddingLeft
            val inside = textLeft - border
            var attachments: Rect? = null
            if (input.attachmentCount > 0) {
                val photoTop = textLeft
                attachments = Rect(
                    border,
                    photoTop - m.attachmentRemoveOverhang,
                    max(0.0, width - 2 * border),
                    m.attachmentRemoveOverhang + m.attachmentSize,
                )
                y = photoTop + m.attachmentSize + m.attachmentStripMarginBottom
            }
            val attachmentInsets = AttachmentInsets(inside, m.attachmentRemoveOverhang, inside)

            val text = MultiTextInputLayout(
                contentHeight = input.textContentHeight,
                hasText = input.hasText,
                maxHeight = metrics.inputMaxHeight,
                lineHeight = input.lineHeight ?: metrics.inputLineHeight,
                paddingTop = metrics.inputPaddingTop,
                paddingBottom = metrics.inputPaddingBottom,
            )
            val containerHeight = max(
                input.inputMinHeight ?: metrics.inputMinHeight,
                text.height + metrics.inputPaddingTop + metrics.inputPaddingBottom,
            )
            val inputContainer = Rect(contentX, y, contentWidth, containerHeight)
            val innerHeight = containerHeight - metrics.inputPaddingTop - metrics.inputPaddingBottom
            val textFrame = Rect(
                contentX + insets.inputContainerPaddingLeft,
                y + metrics.inputPaddingTop + (innerHeight - text.height) / 2,
                max(0.0, contentWidth - insets.inputContainerPaddingLeft - insets.inputContainerPaddingRight),
                text.height,
            )
            val expand: Rect?
            val textTrailingInset: Double
            if (input.offersExpand && text.scrollEnabled) {
                expand = Rect(textFrame.maxX - m.expandSize, textFrame.minY, m.expandSize, m.expandSize)
                textTrailingInset = m.expandSize
            } else {
                expand = null
                textTrailingInset = 0.0
            }
            y += containerHeight

            // [+] [middle, flex 1] [send, 8 margin], 2 apart.
            val actionRow = Rect(contentX, y, contentWidth, metrics.actionRowHeight)
            val gap = m.rowGap
            var middleX = actionRow.minX
            var add: Rect? = null
            if (input.showsAdd && !input.zenMode) {
                add = Rect(
                    actionRow.minX,
                    actionRow.minY + (metrics.actionRowHeight - metrics.actionSize) / 2,
                    metrics.actionSize,
                    metrics.actionSize,
                )
                middleX += metrics.actionSize + gap
            }
            val primary = Rect(
                actionRow.maxX - metrics.primaryActionSize,
                actionRow.minY + (metrics.actionRowHeight - metrics.primaryActionSize) / 2,
                metrics.primaryActionSize,
                metrics.primaryActionSize,
            )
            val middle = Rect(
                middleX,
                actionRow.minY,
                max(0.0, primary.minX - metrics.primaryActionMarginLeft - gap - middleX),
                metrics.actionRowHeight,
            )
            val middleContent = if (input.zenMode) MiddleContent() else middle(input, middle.width)
            y += metrics.actionRowHeight + metrics.shellPaddingBottom + border
            return AgentInputCardLayout(
                Size(width, y), attachments, attachmentInsets, inputContainer, textFrame, text.scrollEnabled, expand,
                textTrailingInset, actionRow, add, middle, middleContent, primary,
            )
        }

        /**
         * The middle box as Yoga lays out
         * `[permission] [spacer, flex 1] [model] [·] [effort ≥ 64]`, 2 apart:
         * the spacer takes the slack, and only the model gives way when there
         * is none. Chips are 40 tall in the 42 row. Labels sit centred in the
         * permission chip, flush right in the model's, flush left in the
         * effort's, cut to the chip.
         */
        internal fun middle(input: Input, width: Double): MiddleContent {
            val m = Metrics
            val metrics = input.metrics
            val gap = m.rowGap
            val chipHeight = metrics.secondaryActionHeight
            val rowHeight = metrics.actionRowHeight
            val modelLeft = if (input.modelIsMenu) m.modelPaddingLeft else m.plainModelPaddingLeft
            val modelRight = if (input.modelIsMenu) m.modelPaddingRight else 0.0

            val permissionWidth = input.permission?.let { m.permissionPaddingLeft + it.labelSize.width + m.permissionPaddingRight }
            val modelBasis = input.model?.let { modelLeft + it.labelSize.width + modelRight } ?: 0.0
            val effortWidth = input.effort?.let {
                max(metrics.effortWidth, m.effortPaddingLeft + it.labelSize.width + m.effortPaddingRight)
            }

            // The spacer is always there; so is the model's slot.
            var items = 1 + (if (input.model == null) 0 else 1)
            var fixed = 0.0
            if (permissionWidth != null) {
                items += 1
                fixed += permissionWidth
            }
            if (effortWidth != null) {
                items += 2
                fixed += input.separator.width + effortWidth
            }
            val free = width - (items - 1) * gap - fixed - modelBasis
            val spacer = max(0.0, free)
            val model = if (free < 0) max(0.0, modelBasis + free) else modelBasis

            val chipY = (rowHeight - chipHeight) / 2
            fun label(size: Size, frame: Rect, align: (Double) -> Double): Rect {
                val labelWidth = min(size.width, frame.width)
                return Rect(align(labelWidth), (frame.height - size.height) / 2, labelWidth, size.height)
            }

            var permission: ChipFrame? = null
            var modelFrame: ChipFrame? = null
            var separator: Rect? = null
            var effort: ChipFrame? = null
            var x = 0.0
            if (permissionWidth != null && input.permission != null) {
                val frame = Rect(x, chipY, permissionWidth, chipHeight)
                permission = ChipFrame(frame, label(input.permission.labelSize, frame) { (frame.width - it) / 2 })
                x += permissionWidth + gap
            }
            x += spacer
            if (input.model != null) {
                x += gap
                val frame = Rect(x, chipY, model, chipHeight)
                modelFrame = ChipFrame(frame, label(input.model.labelSize, frame) { frame.width - it })
                x += model
            }
            if (effortWidth != null && input.effort != null) {
                x += gap
                separator = Rect(x, (rowHeight - input.separator.height) / 2, input.separator.width, input.separator.height)
                x += input.separator.width + gap
                val frame = Rect(x, chipY, effortWidth, chipHeight)
                effort = ChipFrame(frame, label(input.effort.labelSize, frame) { 0.0 })
            }
            return MiddleContent(permission, modelFrame, separator, effort)
        }

        /** The thumbnails in the attachment strip's scrolling content, and that content's width. */
        fun attachmentThumbnails(count: Int, insets: AttachmentInsets = AttachmentInsets.panel): Pair<List<Rect>, Double> {
            val m = Metrics
            val frames = (0 until max(0, count)).map { index ->
                Rect(insets.leading + index * (m.attachmentSize + m.attachmentGap), insets.top, m.attachmentSize, m.attachmentSize)
            }
            return frames to (frames.lastOrNull()?.let { it.maxX + insets.trailing } ?: 0.0)
        }

        /** A thumbnail's remove button, in the thumbnail: 18, 6 over its top-right corner. */
        val attachmentRemoveButton = Rect(
            Metrics.attachmentSize + Metrics.attachmentRemoveOverhang - Metrics.attachmentRemoveSize,
            -Metrics.attachmentRemoveOverhang,
            Metrics.attachmentRemoveSize,
            Metrics.attachmentRemoveSize,
        )
    }
}

/**
 * AgentInput.tsx on a phone: the card with the rows around it.
 *
 *     8 ┌ status row: ● connection text ─────────────────── 16 ┐ ← top
 *       ├ card (AgentInputCardLayout) ─────────────────────────┤ ← cardOffset
 *       └──────────────────────────────────────────────────────┘
 *         usage row:                  52% week   12% context ◔  (18 in)
 *     8
 *
 * The status and usage rows fade while the reader is back in history and
 * keep their space. Frames are in the composer's bounds; the card's own
 * content is in [cardLayout] (the compact card) or [desktopLayout] (the
 * flat panel of a window wider than 700), in the card's bounds.
 */
data class AgentInputLayout(
    val size: Size,
    /** The inner container: the composer less its side padding. */
    val inner: Rect,
    val status: StatusRow?,
    val card: Rect,
    val cardLayout: AgentInputCardLayout?,
    val desktopLayout: AgentInputDesktopLayout?,
    val usage: UsageRow?,
    /** The window is wider than 700. */
    val isWide: Boolean,
) {
    /** From the composer's top to the card's. */
    val cardOffset get() = card.minY

    object Metrics {
        const val containerPaddingTop = 8.0
        const val containerPaddingBottom = 8.0
        const val containerPaddingH = 8.0
        const val containerPaddingHWide = 12.0
        const val statusPaddingH = 16.0
        const val statusPaddingBottom = 4.0
        const val statusMinHeight = 20.0
        const val statusDotSize = 6.0
        const val statusDotMarginTop = 1.0
        const val statusDotGap = 4.0
        const val usagePaddingH = 18.0
        const val usagePaddingTop = 6.0
        const val usageMinHeight = 18.0
        const val usageGap = 10.0
        const val usageGaugeGap = 5.0
        const val usageGaugeSize = 14.0
        const val usageGaugeStroke = 2.5
        val usageContextSlop = LayoutInsets(top = 12, left = 10, bottom = 14, right = 14)
        const val usageWeekSlop = 10.0
        const val autocompleteMarginBottom = 8.0
        const val autocompletePaddingH = 8.0
        const val autocompleteMaxHeight = 320.0
        const val autocompleteRowHeight = 48.0
        const val autocompleteRadius = 24.0
        const val fadeInDuration = 0.18
        const val fadeOutDuration = 0.14
    }

    class Input(
        val width: Double,
        val paddingH: Double = Metrics.containerPaddingH,
        val maxContentWidth: Double = Double.MAX_VALUE,
        val screenWidth: Double? = null,
        /** The status row's text, measured; null hides the row. */
        val statusText: Size? = null,
        /** The compact card; its width is the layout's to set. */
        val card: AgentInputCardLayout.Input? = null,
        /** The flat panel instead, when set. */
        val desktop: AgentInputDesktopLayout.Input? = null,
        val usageWeek: Size? = null,
        val usageContext: Size? = null,
    )

    data class StatusRow(val frame: Rect, val dot: Rect, val text: Rect)

    data class UsageRow(
        val frame: Rect,
        val week: Rect? = null,
        val context: Rect? = null,
        val contextText: Rect? = null,
        val gauge: Rect? = null,
    )

    /**
     * The suggestion list: above the composer, 8 over the inner container
     * and 8 in from its sides, sized for [rows] 48 rows up to 320 inside a
     * [border] rim. Its bottom edge is the composer's top.
     */
    fun autocomplete(rows: Int, border: Double): Rect {
        val m = Metrics
        val list = max(0, rows) * m.autocompleteRowHeight + 2 * max(0.0, border)
        val height = min(m.autocompleteMaxHeight, list)
        val bottom = inner.minY - m.autocompleteMarginBottom
        val paddingH = if (isWide) 0.0 else m.autocompletePaddingH
        return Rect(inner.minX + paddingH, bottom - height, max(0.0, inner.width - 2 * paddingH), height)
    }

    companion object {
        fun innerWidth(width: Double, paddingH: Double, maxContentWidth: Double) =
            min(max(0.0, max(0.0, width) - 2 * paddingH), maxContentWidth)

        fun paddingH(screenWidth: Double) = if (isWide(screenWidth)) Metrics.containerPaddingHWide else Metrics.containerPaddingH

        fun isWide(screenWidth: Double) = screenWidth > 700

        operator fun invoke(input: Input): AgentInputLayout {
            val m = Metrics
            val width = max(0.0, input.width)
            val innerWidth = innerWidth(width, input.paddingH, input.maxContentWidth)
            val innerX = (width - innerWidth) / 2
            var y = m.containerPaddingTop

            // [dot 6, 1 down] 4 [text], centred in a row at least 20 tall with 4 under it.
            var status: StatusRow? = null
            val text = input.statusText
            if (text != null) {
                val dotBox = m.statusDotSize + m.statusDotMarginTop
                val group = max(dotBox, text.height)
                val height = max(m.statusMinHeight, group + m.statusPaddingBottom)
                val groupY = y + (height - m.statusPaddingBottom - group) / 2
                val x = innerX + m.statusPaddingH
                val textX = x + m.statusDotSize + m.statusDotGap
                status = StatusRow(
                    frame = Rect(innerX, y, innerWidth, height),
                    dot = Rect(x, groupY + (group - dotBox) / 2 + m.statusDotMarginTop, m.statusDotSize, m.statusDotSize),
                    text = Rect(
                        textX,
                        groupY + (group - text.height) / 2,
                        min(text.width, max(0.0, innerX + innerWidth - m.statusPaddingH - textX)),
                        text.height,
                    ),
                )
                y += height
            }

            val isWide = isWide(input.screenWidth ?: width)
            val desktopLayout = input.desktop?.let { AgentInputDesktopLayout(it.copy(width = innerWidth)) }
            val cardLayout = if (desktopLayout != null) null else AgentInputCardLayout(requireNotNull(input.card).copy(width = innerWidth))
            val card = Rect(innerX, y, innerWidth, desktopLayout?.size?.height ?: cardLayout!!.size.height)
            y += card.height

            // Right-aligned: [week] 10 [context 5 gauge].
            var usage: UsageRow? = null
            if (input.usageWeek != null || input.usageContext != null) {
                val contextHeight = input.usageContext?.let { max(it.height, m.usageGaugeSize) } ?: 0.0
                val content = max(input.usageWeek?.height ?: 0.0, contextHeight)
                val height = max(m.usageMinHeight, m.usagePaddingTop + content)
                val top = y + m.usagePaddingTop
                val innerH = height - m.usagePaddingTop
                var right = innerX + innerWidth - m.usagePaddingH
                var row = UsageRow(Rect(innerX, y, innerWidth, height))
                input.usageContext?.let { ctx ->
                    val groupWidth = ctx.width + m.usageGaugeGap + m.usageGaugeSize
                    val group = Rect(right - groupWidth, top + (innerH - contextHeight) / 2, groupWidth, contextHeight)
                    row = row.copy(
                        context = group,
                        contextText = Rect(group.minX, group.minY + (contextHeight - ctx.height) / 2, ctx.width, ctx.height),
                        gauge = Rect(group.maxX - m.usageGaugeSize, group.minY + (contextHeight - m.usageGaugeSize) / 2, m.usageGaugeSize, m.usageGaugeSize),
                    )
                    right = group.minX - m.usageGap
                }
                input.usageWeek?.let { week ->
                    row = row.copy(week = Rect(right - week.width, top + (innerH - week.height) / 2, week.width, week.height))
                }
                usage = row
                y += height
            }

            y += m.containerPaddingBottom
            val inner = Rect(innerX, m.containerPaddingTop, innerWidth, y - m.containerPaddingTop - m.containerPaddingBottom)
            return AgentInputLayout(Size(width, y), inner, status, card, cardLayout, desktopLayout, usage, isWide)
        }
    }
}

/**
 * The composer's text full screen, as Telegram's input expands once it is
 * past the lines the card shows: the text from the safe area's top to the
 * keyboard, and the button that puts it back in the card at its top right.
 */
data class ComposerEditorLayout(val collapse: Rect, val text: Rect, val collapseClearance: Rect) {
    object Metrics {
        const val textInset = 16.0
        const val textTop = 8.0
        const val textBottom = 8.0
        const val collapseSize = 44.0
        const val collapseIconSize = 18.0
        const val collapseTop = 4.0
        const val collapseTrailing = 8.0
    }

    companion object {
        operator fun invoke(size: Size, safeArea: LayoutInsets, keyboardTop: Double?): ComposerEditorLayout {
            val m = Metrics
            val left = safeArea.left
            val right = size.width - safeArea.right
            val bottom = min(size.height - safeArea.bottom, keyboardTop ?: Double.MAX_VALUE)
            val collapse = Rect(right - m.collapseTrailing - m.collapseSize, safeArea.top + m.collapseTop, m.collapseSize, m.collapseSize)
            val textX = left + m.textInset
            val textY = safeArea.top + m.textTop
            val text = Rect(textX, textY, max(0.0, right - m.textInset - textX), max(0.0, bottom - m.textBottom - textY))
            val clearance = Rect(collapse.minX - textX, 0.0, max(0.0, text.maxX - collapse.minX), max(0.0, collapse.maxY - textY))
            return ComposerEditorLayout(collapse, text, clearance)
        }
    }
}

/** A horizontal shake: offsets at evenly spaced key times, each step eased in and out. */
data class ComposerShake(val values: List<Double>, val keyTimes: List<Double>, val duration: Double) {
    companion object {
        /** Steps of [step] seconds from rest through [offsets]. */
        fun steps(offsets: List<Double>, step: Double): ComposerShake {
            val n = offsets.size
            return ComposerShake(listOf(0.0) + offsets, (0..n).map { if (n == 0) 0.0 else it.toDouble() / n }, n * step)
        }

        /** Shaker.tsx's `shakeKeyframes`. */
        fun shakerKeyframes(amplitude: Double = 3.0, count: Int = 4, decay: Boolean = false): List<Double> {
            val keyframes = mutableListOf(0.0)
            for (index in 0 until max(0, count)) {
                val sign = if (index % 2 == 0) 1.0 else -1.0
                val multiplier = if (decay) 1.0 / (index + 1) else 1.0
                keyframes += amplitude * sign * multiplier
            }
            keyframes += 0.0
            return keyframes
        }

        /** Shaker.tsx: the keyframes in 300ms, a sixth each, from rest. A refused send, a failed stop. */
        val shaker = steps(shakerKeyframes(), 0.05)

        /** HomeDock.tsx `shakeOnce`: [3, -3, 3, -3, 0], 50ms each. */
        val refusal = steps(listOf(3.0, -3.0, 3.0, -3.0, 0.0), 0.05)
    }
}

/** HomeDock.tsx's refusal flash on the primary button. */
object AgentInputPrimaryFlash {
    fun scale(amount: Double) = 1 + max(0.0, amount) * 0.1
    fun overlayAlpha(amount: Double) = min(1.0, max(0.0, amount) * 0.55)
}

/**
 * HomeDock.tsx's open animation for the focused card: the input rises in
 * first, the action row after. Transform and alpha only.
 */
object AgentInputCardReveal {
    val inputRange = 0.22..0.6
    const val inputDistance = 8.0
    val actionsRange = 0.46..0.82
    const val actionsDistance = 7.0

    fun input(progress: Double) = reveal(progress, inputRange, inputDistance)
    fun actions(progress: Double) = reveal(progress, actionsRange, actionsDistance)

    /** (alpha, offsetY). */
    internal fun reveal(progress: Double, range: ClosedFloatingPointRange<Double>, distance: Double): Pair<Double, Double> {
        val span = range.endInclusive - range.start
        val value = if (span > 0) (progress - range.start) / span else if (progress >= range.endInclusive) 1.0 else 0.0
        val reveal = min(1.0, max(0.0, value))
        return reveal to distance * (1 - reveal)
    }
}
