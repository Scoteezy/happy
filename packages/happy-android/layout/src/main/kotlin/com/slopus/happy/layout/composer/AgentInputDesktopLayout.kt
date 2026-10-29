package com.slopus.happy.layout.composer

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.max

/**
 * AgentInput.tsx's other composer, in a window wider than 700
 * (`compactMobileComposer` false; happy-ios
 * Composer/AgentInputDesktopLayout.swift): the flat `unifiedPanel` and its
 * `desktopActionControls`.
 *
 *     ┌ input.background ──────────────────────────────────── r 16 ┐ 2 top
 *     │ 8 [attachments 64, 8 below, 8 in]                        8 │
 *     │ 8 2 text (38…120 with its 8 + 8, then scrolls)         8 8 │ input 40+, 4 + 4
 *     │ 8 [gear 40] 8 [stop 32] 8 [image 32] ········· 8 [send 32] 8 │ row 40
 *     └────────────────────────────────────────────────────────────┘ 8 bottom
 *
 * The left group (`actionButtonsLeft`) takes the row less send and clips
 * what it holds. Yoga stretches its children, so the 32 buttons sit at its
 * top while the 40 gear sets its height and send is centred on it. In zen
 * mode the group is gone and send is all there is.
 *
 * Frames are in the panel's bounds; [leftContent] is in [left]'s.
 */
data class AgentInputDesktopLayout(
    val size: Size,
    /** The horizontal strip; its thumbnails come from [AgentInputCardLayout.attachmentThumbnails]. */
    val attachments: Rect?,
    val inputContainer: Rect,
    /** The text view, its 8 + 8 padding included. */
    val text: Rect,
    val textScrolls: Boolean,
    val actionRow: Rect,
    /** `actionButtonsLeft`; null in zen mode. */
    val left: Rect?,
    val leftContent: LeftContent,
    val send: Rect,
) {
    object Metrics {
        /** `unifiedPanel`: radius 16 on iOS, paddingVertical 2 with paddingBottom 8, paddingHorizontal 8. */
        const val panelRadius = 16.0
        const val panelPaddingTop = 2.0
        const val panelPaddingBottom = 8.0
        const val panelPaddingH = 8.0

        /** `inputContainer`. */
        const val inputPaddingLeft = 2.0
        const val inputPaddingRight = 8.0
        const val inputPaddingV = 4.0
        const val inputMinHeight = 40.0
        /** MultiTextInput on native: paddingTop and paddingBottom 8, line height 22, at most 120. */
        const val textPaddingTop = 8.0
        const val textPaddingBottom = 8.0
        const val lineHeight = 22.0
        const val inputMaxHeight = 120.0

        /** The gear's settings menu, 40 square. */
        const val settingsSize = 40.0
        /** Stop and image (`actionButton`): 32 tall, 8 either side of a 16 glyph. */
        const val actionHeight = 32.0
        const val actionPaddingH = 8.0
        const val actionIconSize = 16.0
        /** `actionButtonsLeft`'s gap. */
        const val actionGap = 8.0
        /** `sendButton`. */
        const val sendSize = 32.0
        const val sendMarginLeft = 8.0
        const val sendIconSize = 16.0
        const val lockIconSize = 15.0
        /** A small ActivityIndicator. */
        const val spinnerSize = 20.0
        /** Stop, image and send: `hitSlop={{ top: 5, bottom: 10 }}`. */
        val actionHitSlop = LayoutInsets(top = 5, left = 0, bottom = 10, right = 0)
        /** Their Pressable's `opacity: pressed ? 0.7 : 1`. */
        const val pressedOpacity = 0.7
    }

    data class Input(
        val width: Double,
        val attachmentCount: Int = 0,
        /** The text view's height for its text, its 8 + 8 padding included. */
        val textContentHeight: Double,
        val hasText: Boolean,
        /** One line of the text as drawn: 22, scaled where the composer follows the text size. The 40 minimum and 120 cap stay. */
        val lineHeight: Double = Metrics.lineHeight,
        /** `minHeight` for the input container; null keeps 40. */
        val inputMinHeight: Double? = null,
        /** The gear (`onPermissionModeChange`). */
        val showsSettings: Boolean = false,
        /** Stop (`onAbort`): the width of what it shows, the glyph or, while it stops, the spinner. Null hides it. */
        val stopContentWidth: Double? = null,
        /** The image button (`onPickImages`): its glyph's width. Null hides it. */
        val imageContentWidth: Double? = null,
        /** AgentInput.tsx `zenMode`: no left group, send stays. */
        val zenMode: Boolean = false,
    )

    /** The left group's buttons, in its bounds. */
    data class LeftContent(val settings: Rect? = null, val stop: Rect? = null, val image: Rect? = null)

    companion object {
        /** The text view's width at a panel [panelWidth] wide: measure its text there. */
        fun textWidth(panelWidth: Double): Double {
            val m = Metrics
            return max(0.0, max(0.0, panelWidth) - 2 * m.panelPaddingH - m.inputPaddingLeft - m.inputPaddingRight)
        }

        operator fun invoke(input: Input): AgentInputDesktopLayout {
            val m = Metrics
            val width = max(0.0, input.width)
            val contentX = m.panelPaddingH
            val contentWidth = max(0.0, width - 2 * m.panelPaddingH)
            var y = m.panelPaddingTop

            var attachments: Rect? = null
            if (input.attachmentCount > 0) {
                val strip = AgentInputCardLayout.Metrics
                attachments = Rect(
                    contentX + strip.attachmentStripMarginH,
                    y,
                    max(0.0, contentWidth - 2 * strip.attachmentStripMarginH),
                    strip.attachmentSize,
                )
                y += strip.attachmentSize + strip.attachmentStripMarginBottom
            }

            val text = MultiTextInputLayout(
                contentHeight = input.textContentHeight,
                hasText = input.hasText,
                maxHeight = m.inputMaxHeight,
                lineHeight = input.lineHeight,
                paddingTop = m.textPaddingTop,
                paddingBottom = m.textPaddingBottom,
            )
            val containerHeight = max(input.inputMinHeight ?: m.inputMinHeight, text.height + 2 * m.inputPaddingV)
            val inputContainer = Rect(contentX, y, contentWidth, containerHeight)
            val textFrame = Rect(
                contentX + m.inputPaddingLeft,
                y + m.inputPaddingV + (containerHeight - 2 * m.inputPaddingV - text.height) / 2,
                textWidth(width),
                text.height,
            )
            y += containerHeight

            // [left, flex 1] 8 [send]; the row is as tall as its tallest.
            var settings: Rect? = null
            var stop: Rect? = null
            var image: Rect? = null
            var leftHeight = 0.0
            if (!input.zenMode) {
                var x = 0.0
                // Stretched, but with a height of its own: at the top.
                fun place(w: Double, h: Double): Rect {
                    val frame = Rect(x, 0.0, w, h)
                    x += w + m.actionGap
                    leftHeight = max(leftHeight, h)
                    return frame
                }
                if (input.showsSettings) settings = place(m.settingsSize, m.settingsSize)
                input.stopContentWidth?.let { stop = place(2 * m.actionPaddingH + it, m.actionHeight) }
                input.imageContentWidth?.let { image = place(2 * m.actionPaddingH + it, m.actionHeight) }
            }
            val rowHeight = max(leftHeight, m.sendSize)
            val actionRow = Rect(contentX, y, contentWidth, rowHeight)
            val send = Rect(actionRow.maxX - m.sendSize, y + (rowHeight - m.sendSize) / 2, m.sendSize, m.sendSize)
            val left = if (input.zenMode) {
                null
            } else {
                Rect(contentX, y + (rowHeight - leftHeight) / 2, max(0.0, send.minX - m.sendMarginLeft - contentX), leftHeight)
            }
            y += rowHeight + m.panelPaddingBottom
            return AgentInputDesktopLayout(
                Size(width, y), attachments, inputContainer, textFrame, text.scrollEnabled,
                actionRow, left, LeftContent(settings, stop, image), send,
            )
        }
    }
}
