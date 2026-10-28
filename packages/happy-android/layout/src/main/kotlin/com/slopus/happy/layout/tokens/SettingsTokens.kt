package com.slopus.happy.layout

// Settings pages: settings/appearance.tsx, settings/connect/claude.tsx and
// app/(app)/changelog.tsx (happy-ios Tokens/SettingsTokens.swift, iOS values).

/** A pressed dropdown option (`theme.colors.glass.backgroundStrong`). */
val Palette.optionPressed get() = ColorToken(RGBA(1.0, 1.0, 1.0, 0.84), RGBA(28.0 / 255, 28.0 / 255, 28.0 / 255, 0.68))

/**
 * A user-bubble swatch as iOS draws it, a glass surface:
 * `resolveUserMessageBubbleGlassColor` lays a translucent preset over the
 * card. The lines keep the opaque indicator colour.
 */
data class BubbleGlass(val background: ColorToken, val border: ColorToken)

val BubblePalette.glass: BubbleGlass
    get() {
        if (this == BubblePalette.Gray) {
            return BubbleGlass(
                background = ColorToken(background.light.withAlpha(0.48), background.dark.withAlpha(0.34)),
                border = ColorToken(RGBA(1.0, 1.0, 1.0, 0.76), RGBA(1.0, 1.0, 1.0, 0.18)),
            )
        }
        return BubbleGlass(
            background = ColorToken(background.light.withAlpha(0.54), background.dark.withAlpha(0.46)),
            border = ColorToken(border.light.withAlpha(0.70), border.dark.withAlpha(0.56)),
        )
    }

/** What's New's empty state. */
val TextStyles.changelogEmpty get() = TextStyle(FontFace.Regular, 16, 24)

/** The What's New navigation title (16, semibold, natural line height). */
val TextStyles.changelogNavigationTitle get() = TextStyle(FontFace.SemiBold, 16)
