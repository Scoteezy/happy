package com.slopus.happy.layout

// Design tokens, ported from happy-ios HappyLayout/DesignTokens.swift (which
// ports happy-app's theme.ts and component stylesheets, iOS values). Plain
// values, so layouts and tests can use them; the UI turns them into Android
// colours and paints. Area tokens live in Tokens/<Area>Tokens.kt as extension
// properties on these objects, as the Swift files extend `Palette`.

/** An sRGB colour with components in 0..1. */
data class RGBA(val red: Double, val green: Double, val blue: Double, val alpha: Double = 1.0) {
    constructor(red: Number, green: Number, blue: Number, alpha: Number = 1.0) :
        this(red.toDouble(), green.toDouble(), blue.toDouble(), alpha.toDouble())

    /** 0xAARRGGBB, as android.graphics.Color takes it. */
    val argb: Int
        get() {
            fun c(v: Double) = (swiftRound(v.coerceIn(0.0, 1.0) * 255)).toInt()
            return (c(alpha) shl 24) or (c(red) shl 16) or (c(green) shl 8) or c(blue)
        }

    fun withAlpha(alpha: Double) = copy(alpha = alpha)

    companion object {
        val White = RGBA(1.0, 1.0, 1.0)
        val Black = RGBA(0.0, 0.0, 0.0)
        val Clear = RGBA(0.0, 0.0, 0.0, 0.0)
        /** What a malformed token literal becomes: loud magenta. */
        internal val Invalid = RGBA(1.0, 0.0, 1.0, 0.999)

        /** `#RGB`, `#RRGGBB` or `#RRGGBBAA`, with or without the `#`. */
        fun hex(hex: String): RGBA? {
            var digits = hex.removePrefix("#")
            if (digits.length == 3) digits = digits.map { "$it$it" }.joinToString("")
            if (digits.length != 6 && digits.length != 8) return null
            val value = digits.toLongOrNull(16) ?: return null
            val hasAlpha = digits.length == 8
            val rgb = if (hasAlpha) value shr 8 else value
            return RGBA(
                ((rgb shr 16) and 0xFF) / 255.0,
                ((rgb shr 8) and 0xFF) / 255.0,
                (rgb and 0xFF) / 255.0,
                if (hasAlpha) (value and 0xFF) / 255.0 else 1.0,
            )
        }
    }
}

/** A colour that differs between light and dark appearance. */
data class ColorToken(val light: RGBA, val dark: RGBA) {
    /** Token tables are literals; a typo becomes [RGBA.Invalid], which the token tests look for. */
    constructor(light: String, dark: String) : this(RGBA.hex(light) ?: RGBA.Invalid, RGBA.hex(dark) ?: RGBA.Invalid)
    constructor(both: String) : this(both, both)

    fun resolve(dark: Boolean): RGBA = if (dark) this.dark else light

    companion object {
        val Clear = ColorToken(RGBA.Clear, RGBA.Clear)
    }
}

object Palette {
    val text = ColorToken("#000000", "#ffffff")
    val textSecondary = ColorToken("#8E8E93", "#8E8E93")
    val textDestructive = ColorToken("#FF3B30", "#FF453A")
    val textLink = ColorToken("#2BACCC")
    val surface = ColorToken("#ffffff", "#161616")
    val surfaceHigh = ColorToken("#F8F8F8", "#1E1E1E")
    val surfacePressedOverlay = ColorToken("#D1D1D6", "#242424")
    val divider = ColorToken("#eaeaea", "#2A2A2A")
    /** Item separators. theme.colors.glass.divider. */
    val itemDivider = ColorToken(RGBA(60 / 255.0, 60 / 255.0, 67 / 255.0, 0.12), RGBA(1.0, 1.0, 1.0, 0.08))
    val groupedBackground = ColorToken("#F2F2F7", "#000000")
    val groupedChevron = ColorToken("#C7C7CC", "#505050")
    val groupedSectionTitle = ColorToken("#8E8E93", "#8E8E93")
    val headerBackground = ColorToken("#ffffff", "#000000")
    val headerTint = ColorToken("#18171C", "#ffffff")
    val inputBackground = ColorToken("#F5F5F5", "#1E1E1E")
    val inputText = ColorToken("#000000", "#FFFFFF")
    val inputPlaceholder = ColorToken("#999999", "#8E8E93")
    val statusConnected = ColorToken("#34C759", "#34C759")
    val statusConnecting = ColorToken("#007AFF", "#FFFFFF")
    val statusDisconnected = ColorToken("#999999", "#8E8E93")
    val statusError = ColorToken("#FF3B30", "#FF453A")
    val success = ColorToken("#34C759", "#32D74B")
    val warningCritical = ColorToken("#FF3B30", "#FF453A")

    /** RoundButton's filled pill: black in light, a light pill with dark text in dark. */
    val roundButtonFill = ColorToken("#000000", "#F5F5F5")
    val roundButtonFillPressed = ColorToken("#333333", "#D9D9D9")
    val roundButtonText = ColorToken("#FFFFFF", "#111111")

    /** Ionicons tints the settings rows use. */
    val iconBlue = ColorToken("#007AFF")
    val iconOrange = ColorToken("#FF9500")
    val iconRed = ColorToken("#FF3B30")
    val iconPurple = ColorToken("#5856D6")
    val iconGreen = ColorToken("#34C759")

    /** The stacked "Happy / Engineering" wordmark. */
    val wordmarkHappy = ColorToken("#183f38", "#ffffff")
    val wordmarkEngineering = ColorToken("#e6522c")

    /** The QR code always sits on white so any scanner can read it. */
    val qrBackground = ColorToken("#ffffff")
    val scannerDimming = ColorToken(RGBA(0.0, 0.0, 0.0, 0.55), RGBA(0.0, 0.0, 0.0, 0.55))

    /** TerminalBlock.tsx: the terminal on happy.engineering, dark in both appearances. */
    val terminalBackground = ColorToken("#14352f")
    val terminalBorder = ColorToken("#24463f")
    val terminalText = ColorToken("#f0ebe0")
    val terminalPrompt = ColorToken("#6fae74")
    val terminalComment = ColorToken("#8fae8a")

    /** The flat session list paints rows and page alike (FlatSessionRow.tsx `flatListBackgroundColor`). */
    val flatListBackground = ColorToken("#FFFFFF", "#000000")
    val surfaceSelected = ColorToken("#C6C6C8", "#242424")
    val surfaceHighest = ColorToken("#f0f0f0", "#282828")
    /** flatSessionRowPresentation.ts: the agent waits on the person. */
    val sessionBlockedDot = ColorToken("#FF9500")
    /** flatSessionRowPresentation.ts: a finished turn nobody has seen. */
    val sessionReadyDot = ColorToken("#007AFF")
    /** theme.colors.radio.active: selected segments and checkmarks. */
    val radioActive = ColorToken("#007AFF", "#0A84FF")
    /** theme.colors.box.warning.text: the offline plaque's glyph. */
    val warningText = ColorToken("#FF9500", "#FFAB00")
    val iconTeal = ColorToken("#5AC8FA")
    val iconViolet = ColorToken("#AF52DE")
    val switchOn = ColorToken("#34C759")
    /** theme.colors.glass.backgroundSubtle: the chosen option under an open dropdown. */
    val optionSelected = ColorToken(RGBA(1.0, 1.0, 1.0, 0.42), RGBA(1.0, 1.0, 1.0, 0.07))
    /** theme.colors.glass.border, around Connect Claude's terminal card. */
    val glassBorder = ColorToken(RGBA(1.0, 1.0, 1.0, 0.82), RGBA(1.0, 1.0, 1.0, 0.14))
    /** Connect Claude's terminal text: Menlo green prompt, white command. */
    val commandPrompt = ColorToken("#00ff00")
    val commandText = ColorToken("#ffffff")
    /** AvatarBrutalist.tsx's black-and-white pair. */
    val avatarBrutalistMonoTint = ColorToken("#999999")
    val avatarBrutalistMonoBackground = ColorToken("#F0F0F0")
    /** UsageChart.tsx bars. */
    val usageTokens = ColorToken("#007AFF")
    val usageCost = ColorToken("#FF9500")
}

/** A user-message bubble preset (utils/userMessageBubbleColor.ts). */
data class BubblePalette(val background: ColorToken, val border: ColorToken, val indicator: ColorToken) {
    companion object {
        val Gray = BubblePalette(ColorToken("#f0eee6", "#2C2C2E"), ColorToken("#f0eee6", "#2C2C2E"), ColorToken("#8E8E93", "#8E8E93"))
        val Blue = BubblePalette(ColorToken("#E8F2FF", "#17324D"), ColorToken("#9CC9FF", "#2F6EA8"), ColorToken("#0A84FF", "#64B5FF"))
        val Green = BubblePalette(ColorToken("#E9F7EF", "#173A27"), ColorToken("#96D6AE", "#3D8B58"), ColorToken("#34C759", "#65D385"))
        val Purple = BubblePalette(ColorToken("#F0EBFF", "#2B214A"), ColorToken("#B7A2FF", "#7D68CF"), ColorToken("#7D68CF", "#B8A8FF"))
        val Rose = BubblePalette(ColorToken("#FFF0F5", "#462232"), ColorToken("#F3A4BF", "#C85E82"), ColorToken("#D85D85", "#FF9DBB"))
        val Sand = BubblePalette(ColorToken("#F1E7D0", "#3A3326"), ColorToken("#D9C292", "#8D7A55"), ColorToken("#B28B3D", "#E8C878"))

        /** In `USER_MESSAGE_BUBBLE_COLORS` order. */
        val All = listOf(Gray, Blue, Green, Purple, Rose, Sand)
    }
}

/** The bundled typefaces (res/font, and assets/fonts for the variable ones). */
enum class FontFace(val resourceName: String) {
    Regular("ibm_plex_sans_regular"),
    Italic("ibm_plex_sans_italic"),
    SemiBold("ibm_plex_sans_semibold"),
    Mono("ibm_plex_mono_regular"),
    MonoItalic("ibm_plex_mono_italic"),
    MonoSemiBold("ibm_plex_mono_semibold"),
    Logo("bricolage_grotesque_bold"),

    // React Native's default font, which happy-app's plain <Text>s draw in
    // (San Francisco on iOS): the platform's sans-serif here.
    System("sans-serif"),
    SystemMedium("sans-serif-medium"),
    SystemSemiBold("sans-serif-medium"),
    SystemBold("sans-serif"),
    SystemItalic("sans-serif"),
    // Platform.select({ ios: 'Menlo' }): the platform's monospace here.
    Menlo("monospace"),
    MenloBold("monospace"),
    MenloItalic("monospace"),
    // Desktop's faces for Stem Cells (`--happy-font-ui`, `--happy-font-mono`;
    // happy-ios `StemCellsFonts`): variable fonts in assets/fonts, each at its weight.
    Figtree("fonts/figtree.ttf"),
    FigtreeMedium("fonts/figtree.ttf"),
    FigtreeSemiBold("fonts/figtree.ttf"),
    FigtreeBold("fonts/figtree.ttf"),
    /** Figtree at 400, slanted where it is drawn: the variable font has no italic. */
    FigtreeOblique("fonts/figtree.ttf"),
    JetBrainsMono("fonts/jetbrains_mono.ttf"),
    JetBrainsMonoBold("fonts/jetbrains_mono.ttf"),
    ;

    val isMonospaced get() = this == Mono || this == MonoItalic || this == MonoSemiBold || this == Menlo || this == MenloBold || this == MenloItalic ||
        this == JetBrainsMono || this == JetBrainsMonoBold
}

data class TextStyle(
    val face: FontFace,
    val size: Double,
    /** Fixed line height; null leaves the font's own. */
    val lineHeight: Double? = null,
    val letterSpacing: Double = 0.0,
    val uppercase: Boolean = false,
    /** `maxFontSizeMultiplier`: the most the person's text size may grow this style. */
    val maxScale: Double? = null,
) {
    constructor(
        face: FontFace,
        size: Number,
        lineHeight: Number? = null,
        letterSpacing: Number = 0,
        uppercase: Boolean = false,
        maxScale: Number? = null,
    ) : this(face, size.toDouble(), lineHeight?.toDouble(), letterSpacing.toDouble(), uppercase, maxScale?.toDouble())

    /**
     * This style at a text size, as React Native draws it: the font size and a
     * fixed line height grow by the multiplier; letter spacing is kept.
     */
    fun scaled(scale: TextScale): TextStyle {
        val multiplier = scale.effective(maxScale)
        return copy(size = size * multiplier, lineHeight = lineHeight?.let { it * multiplier })
    }
}

/**
 * How much bigger than designed text is drawn: React Native's
 * `fontSizeMultiplier` (on Android, the system font scale). Only text grows.
 */
data class TextScale(val multiplier: Double) {
    /** A style's cap applies only from 1 up, so it never stops text from shrinking. */
    fun effective(maxScale: Double?): Double {
        if (maxScale == null || maxScale < 1) return multiplier
        return minOf(maxScale.toFloat(), multiplier.toFloat()).toDouble()
    }

    companion object {
        val Standard = TextScale(1.0)
    }
}

object TextStyles {
    // Item (components/Item.tsx)
    val itemTitle = TextStyle(FontFace.Regular, 17, 22, -0.41)
    val itemSubtitle = TextStyle(FontFace.Regular, 15, 20, -0.24)
    val itemDetail = TextStyle(FontFace.Regular, 17, letterSpacing = -0.41)

    // ItemGroup (components/ItemGroup.tsx)
    val groupHeader = TextStyle(FontFace.Regular, 13, 18, -0.08, uppercase = true)
    val groupFooter = TextStyle(FontFace.Regular, 13, 18, -0.08)

    // Navigation header (components/onboarding/OnboardingHeader.tsx)
    val headerTitle = TextStyle(FontFace.SemiBold, 16, 20)
    /** The platform header title: semibold at 17. */
    val nativeHeaderTitle = TextStyle(FontFace.SemiBold, 17)
    val headerSubtitle = TextStyle(FontFace.Regular, 11, 14)

    // First run (components/onboarding/OnboardingContent.tsx)
    /** OnboardingContent.tsx `TITLE_MAX_SCALE`: grows at most 1.2 times. */
    val onboardingTitle = TextStyle(FontFace.SemiBold, 28, 34, maxScale = 1.2)
    val onboardingProp = TextStyle(FontFace.Regular, 17, 24)
    val checklistTitle = TextStyle(FontFace.SemiBold, 17, 22)
    val checklistBody = TextStyle(FontFace.Regular, 15, 21)
    val onboardingError = TextStyle(FontFace.Regular, 15, 20)
    val getHelp = TextStyle(FontFace.SemiBold, 15, 20)
    /** The offline checklist's title and the troubleshoot heading. */
    val emptyStateTitle = TextStyle(FontFace.SemiBold, 24, 30)
    val terminalLine = TextStyle(FontFace.Mono, 14, 24)
    val promptHint = TextStyle(FontFace.Regular, 13, 18)

    // Restore (app/(app)/restore/index.tsx and manual.tsx)
    val restoreInstruction = TextStyle(FontFace.Regular, 17, 22)
    val restorePath = TextStyle(FontFace.Regular, 15, 20)
    val secretKeyInput = TextStyle(FontFace.Mono, 15, 22)

    // Server (app/(app)/server.tsx)
    val fieldLabel = TextStyle(FontFace.SemiBold, 12, letterSpacing = 0.5, uppercase = true)
    val serverInput = TextStyle(FontFace.Mono, 15)
    val fieldError = TextStyle(FontFace.Regular, 12)
    val fieldStatus = TextStyle(FontFace.Regular, 12)

    // Secret key reveal (app/(app)/settings/account.tsx)
    val secretKeyLabel = TextStyle(FontFace.SemiBold, 11, letterSpacing = 0.5, uppercase = true)
    val secretKeyValue = TextStyle(FontFace.Mono, 13, 20, 0.5)

    // Flat session list (components/FlatSessionRow.tsx, SessionsList.tsx)
    val sessionTitle = TextStyle(FontFace.SemiBold, 17, 22)
    val sessionTimestamp = TextStyle(FontFace.Regular, 13, 22)
    val sessionProject = TextStyle(FontFace.Regular, 15, 20)
    val sessionWorkspace = TextStyle(FontFace.Regular, 13, 18)
    /** Happy Agent's line counts at the end of the workspace's row (GitLineChanges.tsx). */
    val sessionLineChanges = TextStyle(FontFace.Regular, 11)
    val sessionDayHeader = TextStyle(FontFace.SemiBold, 14, 18, 0.1)
    val archiveToggle = TextStyle(FontFace.Regular, 14, 18)
    val swipeAction = TextStyle(FontFace.SemiBold, 12, 15)

    // Empty home (components/EmptyMainScreen.tsx)
    val emptyStateDescription = TextStyle(FontFace.Regular, 16, 22)
    val secondaryAction = TextStyle(FontFace.SemiBold, 15, 20)

    // Settings header with a profile (components/SettingsView.tsx)
    val profileName = TextStyle(FontFace.SemiBold, 20, 25)
    val profileBio = TextStyle(FontFace.Regular, 14, 19)

    // Option rows under a dropdown (settings/appearance.tsx)
    val optionTitle = TextStyle(FontFace.Regular, 16, 21)
    val dropdownValue = TextStyle(FontFace.Regular, 15, 20)

    // What's New (app/(app)/changelog.tsx)
    val changelogTitle = TextStyle(FontFace.SemiBold, 20, 28)
    val changelogSummary = TextStyle(FontFace.Regular, 16, 23)
    val changelogBody = TextStyle(FontFace.Regular, 16, 25)
    val changelogBold = TextStyle(FontFace.SemiBold, 16, 25)

    // Connect Claude (settings/connect/claude.tsx)
    val connectTitle = TextStyle(FontFace.SemiBold, 18, 23)
    val connectBody = TextStyle(FontFace.Regular, 14, 19)
    val connectCommand = TextStyle(FontFace.Mono, 14, 19)

    // QR scanner.
    val scannerHint = TextStyle(FontFace.SemiBold, 17, 22)
    val scannerMessage = TextStyle(FontFace.Regular, 17, 22)

    fun roundButton(size: RoundButtonSize, display: RoundButtonDisplay): TextStyle {
        val metrics = RoundButtonMetrics.of(size)
        // The inverted (text) action is regular weight; filled pills are semibold.
        return TextStyle(
            if (display == RoundButtonDisplay.Inverted) FontFace.Regular else FontFace.SemiBold,
            metrics.fontSize,
            metrics.lineHeight,
        )
    }
}

object Spacing {
    const val xs = 4.0
    const val s = 8.0
    const val m = 12.0
    const val l = 16.0
    const val xl = 20.0
    const val xxl = 24.0
}

object Radius {
    const val xs = 4.0
    const val s = 8.0
    const val m = 10.0
    const val l = 12.0
    const val xl = 16.0
    /** ItemGroup cards. */
    const val group = 14.0
}
