package com.slopus.happy.layout

import kotlin.math.pow

// Home: MainView.tsx's phone header, MobileHeaderScrim.tsx, HomeHeaderTitle.tsx,
// the project list and the dock (happy-ios Tokens/HomeTokens.swift). Liquid
// glass has no Android counterpart: the header's controls are solid surface
// pills with the same rim and shadow.

/** The controls' 1dp rim: white in light, rgba(255,255,255,0.18) in dark. */
val Palette.headerGlassBorder get() = ColorToken(RGBA.White, RGBA(1.0, 1.0, 1.0, 0.18))

/** The controls' fill, standing in for the static material over the page: what the iOS 26 simulator draws it as over the light page (#E9E9E9) and the dark one (#1A1A1B). */
val Palette.headerControlFill get() = ColorToken(RGBA.hex("#E9E9E9")!!, RGBA.hex("#1A1A1B")!!)

/** The colour the home scrim fades from: white in light, black in dark. */
val Palette.headerScrimTint get() = ColorToken(RGBA.White, RGBA.Black)

/** theme.colors.surfacePressed: the project list's pressed highlight. */
val Palette.surfacePressed get() = ColorToken("#f0f0f2", "#242424")

val Palette.gitAddedText get() = ColorToken("#22c55e", "#34C759")
val Palette.gitRemovedText get() = ColorToken("#ef4444", "#FF453A")

/** The dim over the list while the composer is open (`focusBackdropDim`). */
val Palette.homeDockBackdropDim get() = ColorToken(RGBA(1.0, 1.0, 1.0, 0.88), RGBA(0.0, 0.0, 0.0, 0.88))

/** The controls' drop shadow (Header.tsx): black, 0/4 offset, radius 20, at 0.06 in light and 0.24 in dark. */
object HeaderGlassShadow {
    const val offsetY = 4.0
    const val radius = 20.0
    const val lightOpacity = 0.06
    const val darkOpacity = 0.24
}

/**
 * MobileHeaderScrim.tsx: one dim gradient behind the floating controls, full
 * strength at the screen edge and falling to nothing over the last 36dp.
 */
object HeaderScrim {
    const val featherSteps = 24
    const val rampPoints = 36.0
    const val fallbackFeatherStart = 0.60
    const val strongPeakLight = 0.76
    const val strongPeakDark = 0.55
    const val presenceRamp = 8.0

    data class Stop(val location: Double, val alpha: Double)

    fun presence(scrolledUnder: Double): Double = (scrolledUnder / presenceRamp).coerceIn(0.0, 1.0)

    /** `feather(rgb, peak, hold)`: 25 samples of `1 - p^1.67` after a flat hold. */
    fun stops(height: Double, peak: Double): List<Stop> {
        val hold = if (height > rampPoints) 1 - rampPoints / height else fallbackFeatherStart
        return (0..featherSteps).map { step ->
            val t = step.toDouble() / featherSteps
            val p = if (t <= hold) 0.0 else (t - hold) / (1 - hold)
            val falloff = 1 - p.pow(1.67)
            Stop(t, swiftRound(peak * falloff * 10_000) / 10_000)
        }
    }
}

/** RowPressable's highlight: up in 70ms, gone over 240ms. */
object RowHighlight {
    const val fadeIn = 0.07
    const val fadeOut = 0.24
}

/** HomeHeaderTitle.tsx's socket status: 11, weight 500 (SemiBold in Plex), line height 16. */
val TextStyles.homeHeaderStatus get() = TextStyle(FontFace.SemiBold, 11, 16)

// The project list (components/ProjectHomeList.tsx).
val TextStyles.projectName get() = TextStyle(FontFace.Regular, 17, 22)
val TextStyles.worktreeName get() = TextStyle(FontFace.Regular, 16, 21)
val TextStyles.worktreeToggle get() = TextStyle(FontFace.Regular, 15, 20)
val TextStyles.projectSection get() = TextStyle(FontFace.SemiBold, 11, 14, 0.6, uppercase = true)
val TextStyles.projectMachine get() = TextStyle(FontFace.Regular, 11, 14)
val TextStyles.projectTimestamp get() = TextStyle(FontFace.Regular, 13, 18)
val TextStyles.gitApproximate get() = TextStyle(FontFace.Regular, 11, 18)
val TextStyles.gitLineCount get() = TextStyle(FontFace.SemiBold, 13, 18)

// The dock (components/HomeDock.tsx).
val TextStyles.homeDockEntry get() = TextStyle(FontFace.Regular, 17)
val TextStyles.homeDockRowValue get() = TextStyle(FontFace.Regular, 17)
val TextStyles.homeDockProgress get() = TextStyle(FontFace.Regular, 11)
val TextStyles.homeDockLede get() = TextStyle(FontFace.Regular, 13, 18)
