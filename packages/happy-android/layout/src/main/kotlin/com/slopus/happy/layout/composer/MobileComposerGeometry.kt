package com.slopus.happy.layout.composer

// agentInputLayout.ts (happy-ios Composer/MobileComposerGeometry.swift): the
// compact mobile composer's metric contract. Home's dock and the chat
// composer render different controls, but their shell, input and action
// geometry are these numbers.

/** `MOBILE_COMPOSER_METRICS`. */
data class MobileComposerMetrics(
    val shellRadius: Double,
    val shellInset: Double,
    val shellPaddingTop: Double,
    val shellPaddingBottom: Double,
    val inputMinHeight: Double,
    val inputMaxHeight: Double,
    val inputFontSize: Double,
    val inputLineHeight: Double,
    val inputPaddingTop: Double,
    val inputPaddingBottom: Double,
    val actionRowHeight: Double,
    val actionSize: Double,
    val addIconSize: Double,
    val secondaryActionHeight: Double,
    val effortWidth: Double,
    val primaryActionSize: Double,
    val primaryActionMarginLeft: Double,
    val attachmentExtraHeight: Double,
) {
    /** `MOBILE_COMPOSER_BASE_HEIGHT`: an empty one-line composer. */
    val baseHeight get() = shellPaddingTop + inputMinHeight + actionRowHeight + shellPaddingBottom

    /** `MOBILE_COMPOSER_CHROME_HEIGHT`: everything but the input. */
    val chromeHeight get() = baseHeight - inputMinHeight

    /**
     * `resolveMobileComposerHeight`: the shell's height around an input
     * [inputHeight] tall (its own padding included).
     */
    fun composerHeight(inputHeight: Double, hasAttachments: Boolean = false): Double {
        val inputContainerHeight = maxOf(inputMinHeight, inputHeight + inputPaddingTop + inputPaddingBottom)
        return chromeHeight + inputContainerHeight + (if (hasAttachments) attachmentExtraHeight else 0.0)
    }

    companion object {
        val mobile = MobileComposerMetrics(
            shellRadius = 30.0,
            shellInset = 10.0,
            shellPaddingTop = 8.0,
            shellPaddingBottom = 8.0,
            inputMinHeight = 44.0,
            inputMaxHeight = 120.0,
            inputFontSize = 16.0,
            inputLineHeight = 22.0,
            inputPaddingTop = 4.0,
            inputPaddingBottom = 4.0,
            actionRowHeight = 42.0,
            actionSize = 42.0,
            addIconSize = 26.0,
            secondaryActionHeight = 40.0,
            effortWidth = 64.0,
            primaryActionSize = 42.0,
            primaryActionMarginLeft = 8.0,
            // The photos 18 down instead of the 8 the text starts at, 64, 8 below.
            attachmentExtraHeight = 82.0,
        )
    }
}

/** `AgentInputLayout`: where the text starts, from the leading add glyph. */
data class AgentInputInsets(
    val shellInset: Double,
    val addGlyphOffset: Double,
    val textInset: Double,
    val inputContainerPaddingLeft: Double,
    val inputContainerPaddingRight: Double,
) {
    companion object {
        /**
         * `resolveAgentInputLayout`: the text's left edge lines up with the
         * add glyph's, not with the button around it.
         */
        operator fun invoke(shellInset: Double, actionSize: Double, addIconSize: Double): AgentInputInsets {
            val offset = (actionSize - addIconSize) / 2
            return AgentInputInsets(shellInset, offset, shellInset + offset, offset, offset)
        }

        /** `MOBILE_COMPOSER_LAYOUT`. */
        val mobile = AgentInputInsets(
            shellInset = MobileComposerMetrics.mobile.shellInset,
            actionSize = MobileComposerMetrics.mobile.actionSize,
            addIconSize = MobileComposerMetrics.mobile.addIconSize,
        )
    }
}

/**
 * `MobileCollapsedComposerGeometry`: Home's collapsed one-line capsule. The
 * text starts where the capsule's rounded end meets its straight edge.
 */
class MobileCollapsedComposerGeometry(
    val shellHeight: Double = 56.0,
    contentPaddingHorizontal: Double = 7.0,
    val inputPaddingRight: Double = 4.0,
) {
    val shellRadius = shellHeight / 2
    val contentPaddingLeft = contentPaddingHorizontal
    val contentPaddingRight = contentPaddingHorizontal
    val inputPaddingLeft = shellRadius - contentPaddingHorizontal
    val textInset = contentPaddingHorizontal + inputPaddingLeft
}
