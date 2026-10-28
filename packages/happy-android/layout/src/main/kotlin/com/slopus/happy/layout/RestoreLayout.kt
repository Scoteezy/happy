package com.slopus.happy.layout

import kotlin.math.max
import kotlin.math.min

/**
 * Restore by QR: the other phone scans the code shown here. From
 * app/(app)/restore/index.tsx (happy-ios `RestoreQRLayout`): a centred
 * column of instruction, menu path, the QR on a white frame (or a
 * placeholder while the keypair is made) and a text action to use the
 * secret key instead. Frames are in the scroll view's content coordinates;
 * the scroll view fills the screen.
 */
data class RestoreQRLayout(
    val contentSize: Size,
    val instruction: Rect,
    val path: Rect,
    /** The white frame around the code, or the placeholder. */
    val codeContainer: Rect,
    /** The code itself; null while the placeholder shows. */
    val code: Rect?,
    val button: Rect,
) {
    object Metrics {
        const val paddingHorizontal = 32.0
        const val paddingVertical = 24.0
        const val instructionMarginBottom = 8.0
        const val pathMarginBottom = 32.0
        const val qrSize = 260.0
        const val qrFramePadding = 12.0
        const val qrCornerRadius = 16.0
        const val buttonMaxWidth = 280.0
        const val buttonMarginTop = 32.0
    }

    class Input(
        val size: Size,
        val safeArea: LayoutInsets,
        val instruction: Measurable,
        val path: Measurable,
        /** The QR code is ready; until then a same-size placeholder shows. */
        val showsCode: Boolean,
        val button: Measurable,
    )

    companion object {
        fun innerWidth(size: Size, safeArea: LayoutInsets) = max(0.0, size.width - safeArea.horizontal - Metrics.paddingHorizontal * 2)

        fun buttonWidth(size: Size, safeArea: LayoutInsets) = min(Metrics.buttonMaxWidth, innerWidth(size, safeArea))

        operator fun invoke(input: Input): RestoreQRLayout {
            val m = Metrics
            val insets = input.safeArea
            val innerWidth = innerWidth(input.size, insets)
            val innerX = insets.left + m.paddingHorizontal

            val instructionHeight = input.instruction.height(innerWidth)
            val pathHeight = input.path.height(innerWidth)
            val codeSide: Double
            val containerSide: Double
            if (input.showsCode) {
                codeSide = max(0.0, min(m.qrSize, innerWidth - m.qrFramePadding * 2))
                containerSide = codeSide + m.qrFramePadding * 2
            } else {
                codeSide = max(0.0, min(m.qrSize, innerWidth))
                containerSide = codeSide
            }
            val buttonWidth = buttonWidth(input.size, insets)
            val buttonHeight = input.button.height(buttonWidth)

            val columnHeight = instructionHeight + m.instructionMarginBottom + pathHeight + m.pathMarginBottom +
                containerSide + m.buttonMarginTop + buttonHeight
            val visibleHeight = max(0.0, input.size.height - insets.vertical)
            val free = max(0.0, visibleHeight - m.paddingVertical * 2 - columnHeight)

            var y = insets.top + m.paddingVertical + free / 2
            val instruction = Rect(innerX, y, innerWidth, instructionHeight)
            y = instruction.maxY + m.instructionMarginBottom
            val path = Rect(innerX, y, innerWidth, pathHeight)
            y = path.maxY + m.pathMarginBottom
            val container = Rect(innerX + (innerWidth - containerSide) / 2, y, containerSide, containerSide)
            val code = if (input.showsCode) container.insetBy(m.qrFramePadding, m.qrFramePadding) else null
            y = container.maxY + m.buttonMarginTop
            val button = Rect(innerX + (innerWidth - buttonWidth) / 2, y, buttonWidth, buttonHeight)
            val contentSize = Size(input.size.width, max(input.size.height, button.maxY + m.paddingVertical + insets.bottom))
            return RestoreQRLayout(contentSize, instruction, path, container, code, button)
        }
    }
}

/**
 * Restore with the secret key, from app/(app)/restore/manual.tsx (happy-ios
 * `RestoreSecretKeyLayout`): an instruction, a multi-line monospace field and
 * the Restore button. The screen keeps the field above the keyboard by
 * shrinking the scroll view.
 */
data class RestoreSecretKeyLayout(
    /** The scroll view, in the screen's coordinates. */
    val scroll: Rect,
    val contentSize: Size,
    /** The rest are in the scroll view's content coordinates. */
    val instruction: Rect,
    val field: Rect,
    val button: Rect,
) {
    object Metrics {
        const val paddingHorizontal = 24.0
        const val paddingVertical = 24.0
        const val instructionMarginBottom = 20.0
        const val fieldPadding = 16.0
        const val fieldMinHeight = 120.0
        const val fieldCornerRadius = 12.0
        const val fieldMarginBottom = 24.0
    }

    class Input(
        val size: Size,
        val safeArea: LayoutInsets,
        /** How far the keyboard overlaps the screen from the bottom. */
        val keyboardOverlap: Double = 0.0,
        val instruction: Measurable,
        /** The typed text at the field's text width. */
        val fieldText: Measurable,
        val button: Measurable,
    )

    companion object {
        fun innerWidth(size: Size, safeArea: LayoutInsets) = max(0.0, size.width - safeArea.horizontal - Metrics.paddingHorizontal * 2)

        /** The width the field's text wraps at, inside its padding. */
        fun fieldTextWidth(size: Size, safeArea: LayoutInsets) = max(0.0, innerWidth(size, safeArea) - Metrics.fieldPadding * 2)

        operator fun invoke(input: Input): RestoreSecretKeyLayout {
            val m = Metrics
            val insets = input.safeArea
            val overlap = min(max(0.0, input.keyboardOverlap), input.size.height)
            val scroll = Rect(0.0, 0.0, input.size.width, input.size.height - overlap)
            // The keyboard covers the gesture area, so its inset no longer applies once it is up.
            val bottomInset = max(0.0, insets.bottom - overlap)

            val innerWidth = innerWidth(input.size, insets)
            val innerX = insets.left + m.paddingHorizontal
            var y = insets.top + m.paddingVertical
            val instruction = Rect(innerX, y, innerWidth, input.instruction.height(innerWidth))
            y = instruction.maxY + m.instructionMarginBottom
            val textHeight = input.fieldText.height(fieldTextWidth(input.size, insets))
            val field = Rect(innerX, y, innerWidth, max(m.fieldMinHeight, textHeight + m.fieldPadding * 2))
            y = field.maxY + m.fieldMarginBottom
            val button = Rect(innerX, y, innerWidth, input.button.height(innerWidth))
            return RestoreSecretKeyLayout(scroll, Size(input.size.width, button.maxY + m.paddingVertical + bottomInset), instruction, field, button)
        }
    }
}

/**
 * The custom server form, from app/(app)/server.tsx (happy-ios
 * `ServerFormLayout`). It is the single row of an untitled ItemGroup, so its
 * width is the group's card width.
 *
 *     SERVER URL                      label, 8 below
 *     [ https://…                ]    field, 8 below
 *     error / validating…             each 12 below
 *     ( Save )                        4 above the column
 *     ( Reset to Default )            4 between buttons, 12 below
 *     Currently using custom server
 */
data class ServerFormLayout(
    val size: Size,
    val label: Rect,
    val field: Rect,
    val error: Rect?,
    val validating: Rect?,
    val save: Rect,
    val reset: Rect?,
    val status: Rect?,
) {
    object Metrics {
        const val paddingHorizontal = 16.0
        const val paddingVertical = 12.0
        const val labelMarginBottom = 8.0
        const val fieldPaddingHorizontal = 14.0
        const val fieldPaddingVertical = 12.0
        const val fieldMinHeight = 44.0
        const val fieldCornerRadius = 10.0
        const val fieldMarginBottom = 8.0
        const val messageMarginBottom = 12.0
        const val buttonsMarginTop = 4.0
        const val buttonSpacing = 4.0
        const val buttonsMarginBottom = 12.0
    }

    class Input(
        val width: Double,
        val label: Measurable,
        /** One line of the field's text style. */
        val fieldLineHeight: Double,
        val error: Measurable? = null,
        val validating: Measurable? = null,
        val save: Measurable,
        /** Shown only while a custom server is set. */
        val reset: Measurable? = null,
        val status: Measurable? = null,
    )

    companion object {
        fun innerWidth(width: Double) = max(0.0, width - Metrics.paddingHorizontal * 2)

        operator fun invoke(input: Input): ServerFormLayout {
            val m = Metrics
            val width = max(0.0, input.width)
            val innerWidth = innerWidth(width)
            val x = m.paddingHorizontal
            fun frame(measurable: Measurable, y: Double) = Rect(x, y, innerWidth, measurable.height(innerWidth))

            var y = m.paddingVertical
            val label = frame(input.label, y)
            y = label.maxY + m.labelMarginBottom
            val field = Rect(x, y, innerWidth, max(m.fieldMinHeight, input.fieldLineHeight + m.fieldPaddingVertical * 2))
            y = field.maxY + m.fieldMarginBottom

            val error = input.error?.let { frame(it, y) }
            if (error != null) y = error.maxY + m.messageMarginBottom
            val validating = input.validating?.let { frame(it, y) }
            if (validating != null) y = validating.maxY + m.messageMarginBottom

            y += m.buttonsMarginTop
            val save = frame(input.save, y)
            y = save.maxY
            val reset = input.reset?.let { frame(it, y + m.buttonSpacing) }
            if (reset != null) y = reset.maxY
            y += m.buttonsMarginBottom

            val status = input.status?.let { frame(it, y) }
            if (status != null) y = status.maxY
            return ServerFormLayout(Size(width, y + m.paddingVertical), label, field, error, validating, save, reset, status)
        }
    }
}
