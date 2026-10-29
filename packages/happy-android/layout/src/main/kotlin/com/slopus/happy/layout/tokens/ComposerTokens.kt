package com.slopus.happy.layout

// The composer (happy-ios Tokens/ComposerTokens.swift): AgentInput.tsx,
// MobileGlass.tsx's frosted material and HomeDock.tsx (iOS values).

/**
 * The frosted material's flat tint over its blur (MobileGlass.tsx
 * `surfaceOverlay`, frosted): rgba(255,255,255,0.82) and rgba(20,20,22,0.82).
 */
val Palette.composerFrostedOverlay get() = ColorToken(RGBA(1.0, 1.0, 1.0, 0.82), RGBA(20 / 255.0, 20 / 255.0, 22 / 255.0, 0.82))

/**
 * Android has no backdrop blur behind a view, so the frosted capsule is drawn
 * solid: the frosted tint resolved over the material's own resting colour.
 * #FBFBFB and #131314 are what the iOS 26 simulator draws it as over the list. An
 * Android stand-in, as [headerControlFill] is for the header's glass.
 */
val Palette.composerCapsuleFill get() = ColorToken("#FBFBFB", "#131314")

/** `mobilePrimaryButtonInactive`: send with nothing to send. */
val Palette.composerPrimaryInactive get() = ColorToken("#D1D1D6", "#3A3A3C")
/** `mobileStopButton`. */
val Palette.composerStopFill get() = ColorToken("#000000", "#F5F5F5")
val Palette.composerStopIcon get() = ColorToken("#FFFFFF", "#000000")
/** HomeDock.tsx `sendButtonActive`: send and stop on the new-session card. */
val Palette.composerNewSessionPrimaryFill get() = ColorToken("#000000", "#F5F5F5")
/** HomeDock.tsx `primaryActionIconColor`. */
val Palette.composerNewSessionPrimaryIcon get() = ColorToken("#FFFFFF", "#111111")
/** HomeDock.tsx `primaryActionFlash`: the wash over Stop's fill when a press is refused. */
val Palette.composerPrimaryFlash get() = ColorToken("#FFFFFF", "#4A4A4E")

/**
 * The wide composer's send (`sendButton`): theme.colors.button.primary
 * `background` with text (`sendButtonActive`), `disabled` without
 * (`sendButtonInactive`), and its `tint` for the arrow and spinner.
 */
val Palette.composerSendFill get() = ColorToken("#000000")
val Palette.composerSendDisabled get() = ColorToken("#C0C0C0")
val Palette.composerSendIcon get() = ColorToken("#FFFFFF")

/**
 * The suggestion list's glass (theme.colors.glass.overlay over the
 * ultra-thin material), drawn solid in the colour the iOS simulator shows
 * it as over the chat (iPad, light and dark), as [newSessionPopoverFill]
 * is for the pickers.
 */
val Palette.composerSuggestionFill get() = ColorToken("#EBEBED", "#060606")

// AgentInputSuggestionView.tsx's rows.
val TextStyles.suggestionCommand get() = TextStyle(FontFace.SemiBold, 14)
val TextStyles.suggestionDescription get() = TextStyle(FontFace.Regular, 13)
val TextStyles.suggestionFile get() = TextStyle(FontFace.Regular, 14)
val TextStyles.suggestionLabel get() = TextStyle(FontFace.Regular, 12)

/** `unifiedPanelShadow`: black, 0/2 radius 8 at 0.08 in light; 0/6 radius 16 at 0.22 in dark. */
object ComposerShadow {
    const val lightOffsetY = 2.0
    const val lightRadius = 8.0
    const val lightOpacity = 0.08
    const val darkOffsetY = 6.0
    const val darkRadius = 16.0
    const val darkOpacity = 0.22
}
