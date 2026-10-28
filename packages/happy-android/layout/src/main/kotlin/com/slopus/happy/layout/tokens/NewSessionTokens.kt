package com.slopus.happy.layout

// The new-session screen (app/(app)/new/index.tsx; happy-ios
// Tokens/HomeTokens.swift and ComposerTokens.swift). Android has no liquid
// glass or backdrop blur: the composer, Send and the popovers are drawn as
// their glass's tint over a solid stand-in, with the same rim, sheen and
// shadow.

/** theme.colors.glass.tint: the composer's liquid glass. */
val Palette.glassTint get() = ColorToken(RGBA(1.0, 1.0, 1.0, 0.14), RGBA(16 / 255.0, 16 / 255.0, 16 / 255.0, 0.08))
/** theme.colors.glass.overlayTint: the picker popover's glass. */
val Palette.glassOverlayTint get() = ColorToken(RGBA(1.0, 1.0, 1.0, 0.46), RGBA(0.0, 0.0, 0.0, 0.56))
/** theme.colors.glass.highlight: Send's rim. */
val Palette.glassHighlight get() = ColorToken(RGBA(1.0, 1.0, 1.0, 0.94), RGBA(1.0, 1.0, 1.0, 0.22))
/** theme.colors.glass.overlay: under the popover's effect. */
val Palette.glassOverlay get() = ColorToken(RGBA(245 / 255.0, 245 / 255.0, 248 / 255.0, 0.58), RGBA(0.0, 0.0, 0.0, 0.72))
/** theme.colors.glass.shadow: carries its own alpha. */
val Palette.glassShadow get() = ColorToken(RGBA(39 / 255.0, 47 / 255.0, 54 / 255.0, 0.16), RGBA(0.0, 0.0, 0.0, 0.55))

/**
 * The composer's glass with nothing behind it to refract: what the iOS 26
 * simulator draws the clear glass and its sheen as over the grouped page
 * (#F8F9FC in light, #1C1C1C in dark). An Android stand-in, as
 * [headerControlFill] is for the header's controls.
 */
val Palette.newSessionGlassFill get() = ColorToken("#F8F9FC", "#1C1C1C")
/** The popover's regular glass over the page, read off the simulator the same way. */
val Palette.newSessionPopoverFill get() = ColorToken("#FBFBFD", "#1A1A1A")
/** Send's interactive glass while it can be pressed, read off the simulator the same way. */
val Palette.newSessionSendFill get() = ColorToken("#FFFFFF", "#373737")

/** The new-session screen's materials, shadows and fades. */
object NewSessionMaterial {
    /** `mobileComposerShadow`: glass.shadow, 0/10 radius 24, full opacity. */
    const val composerShadowOffsetY = 10.0
    const val composerShadowRadius = 24.0
    /** `nativePopoverSurface`: glass.shadow, 0/12 radius 32. */
    const val popoverShadowOffsetY = 12.0
    const val popoverShadowRadius = 32.0
    /** `configRowPressed`, and BubblePressable's own. */
    const val pressedOpacity = 0.6
    /** The place rows while the computer is offline. */
    const val offlineOpacity = 0.4
    /** An offline computer in the machine picker. */
    const val dimmedOpacity = 0.45
    /** Send while it can't be pressed. */
    const val sendInactiveOpacity = 0.56
}

/** `configLabel` on a phone. */
val TextStyles.newSessionRow get() = TextStyle(FontFace.SemiBold, 16)
/** `offlineHelpTitle` and `offlineHelpText`. */
val TextStyles.newSessionOfflineTitle get() = TextStyle(FontFace.SemiBold, 13)
val TextStyles.newSessionOfflineBody get() = TextStyle(FontFace.Regular, 12, 18)
/** `composerAgentLabel`. */
val TextStyles.newSessionAgent get() = TextStyle(FontFace.SemiBold, 13)
/** A picker row (`optionText`) and the line under it. */
val TextStyles.newSessionOption get() = TextStyle(FontFace.Regular, 15)
val TextStyles.newSessionOptionDetail get() = TextStyle(FontFace.Regular, 13)
/** `sectionLabel`. */
val TextStyles.newSessionSection get() = TextStyle(FontFace.SemiBold, 13)
/** `pathMetaText`. */
val TextStyles.newSessionMeta get() = TextStyle(FontFace.Regular, 13)
/** `emptyText`. */
val TextStyles.newSessionEmpty get() = TextStyle(FontFace.Regular, 14)
/** A settings row's name, over its value. */
val TextStyles.newSessionSettingName get() = TextStyle(FontFace.Regular, 12)
/** `composerPickerTitle`. */
val TextStyles.newSessionPickerTitle get() = TextStyle(FontFace.SemiBold, 14)
