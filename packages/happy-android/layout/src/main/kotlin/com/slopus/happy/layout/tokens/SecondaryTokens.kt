package com.slopus.happy.layout

// The secondary screens' tokens (happy-ios Tokens/SecondaryTokens.swift
// `SecondaryPalette`, `SecondaryTextStyles`, `DuplicateSheetMaterial`,
// `FileIconPalette`, `FileViewerPalette`, `FileViewerTextStyles`): session
// info, the machine screen, the duplicate sheet and the file viewer.

object SecondaryPalette {
    /** theme.colors.gitAddedText / gitRemovedText (GitLineChanges). */
    val gitAdded = ColorToken("#22c55e", "#34C759")
    val gitRemoved = ColorToken("#ef4444", "#FF453A")

    // Fixed icon tints info.tsx and machine/[id].tsx pass to Ionicons.
    val blue = ColorToken("#007AFF")
    val purple = ColorToken("#5856D6")
    val red = ColorToken("#FF3B30")
    val orange = ColorToken("#FF9500")
    val green = ColorToken("#34C759")
    val gray = ColorToken("#8E8E93")
    val yellow = ColorToken("#FFCC00")
    val claude = ColorToken("#9C27B0")
    val codex = ColorToken("#10A37F")
    /** CopyableItem's check and the resume command's icon. */
    val copied = ColorToken("#30D158")
    /** The machine screen's session rows draw their own chevron in this. */
    val chevron = ColorToken("#C7C7CC")
    /** "Machine not found". */
    val notFound = ColorToken("#666666")
    /** file.tsx's binary file name. */
    val fileName = ColorToken("#999999")

    // DuplicateSheet.tsx.
    /** theme.colors.glass.overlay: the sheet's background on iOS. */
    val sheetOverlay = ColorToken(RGBA(245 / 255.0, 245 / 255.0, 248 / 255.0, 0.58), RGBA(0, 0, 0, 0.72))
    /** BaseModal's backdrop dim, over its blur. */
    val backdropDim = ColorToken(RGBA(0, 0, 0, 0.42), RGBA(0, 0, 0, 0.42))
    /** theme.colors.button.primary. */
    val buttonPrimary = ColorToken("#000000")
    val buttonPrimaryTint = ColorToken("#FFFFFF")
    /** MobileGlassSurface's liquid sheen: a diagonal white gradient at [sheenLocations], top left to bottom right. */
    val sheen = listOf(
        ColorToken(RGBA(1, 1, 1, 0.76), RGBA(1, 1, 1, 0.12)),
        ColorToken(RGBA(1, 1, 1, 0.10), RGBA(1, 1, 1, 0.018)),
        ColorToken(RGBA(1, 1, 1, 0.42), RGBA(1, 1, 1, 0.055)),
    )
    val sheenLocations = listOf(0.0, 0.48, 1.0)

    val all: List<ColorToken> = listOf(
        gitAdded, gitRemoved, blue, purple, red, orange, green, gray, yellow, claude, codex, copied, chevron, notFound, fileName,
        sheetOverlay, backdropDim, buttonPrimary, buttonPrimaryTint,
    ) + sheen
}

/** DuplicateSheet.tsx's materials. expo-blur runs its effect to `intensity / 100`. */
object DuplicateSheetMaterial {
    /** BaseModal's AnimatedBlurBackdrop: intensity 44, the regular material. */
    const val backdropBlurFraction = 0.44
    /** BaseModal: the content fades in from 0.9 scale over 200 ms. */
    const val appearScale = 0.9
    const val appearDuration = 0.2
    /** The buttons, pressed and disabled. */
    const val pressedOpacity = 0.7
    const val disabledOpacity = 0.4
}

object SecondaryTextStyles {
    // info.tsx's loading and deleted states.
    val infoLoading = TextStyle(FontFace.SemiBold, 17)
    val infoDeletedTitle = TextStyle(FontFace.SemiBold, 20)
    val infoDeletedBody = TextStyle(FontFace.Regular, 15)
    /** machine/[id].tsx "Machine not found". */
    val machineNotFound = TextStyle(FontFace.Regular, 16)

    /** The Changes row's "N changed files" (12, Typography.default()). */
    val changedFiles = TextStyle(FontFace.Regular, 12)
    /** GitLineChanges: 11 at weight 600, the semibold face. */
    val lineChanges = TextStyle(FontFace.SemiBold, 11)
    val lineChangesApproximate = TextStyle(FontFace.Regular, 11)

    /** Item subtitles that ask for Menlo 13 / 12 keep Item's 20pt line; Plex Mono stands in, as for Connect Claude's command. */
    val monoSubtitle = TextStyle(FontFace.Mono, 13.0, 20.0, -0.24)
    val monoSubtitleSmall = TextStyle(FontFace.Mono, 12.0, 20.0, -0.24)
}

/** components/FileIcon.tsx's Seti themes: each Seti colour name, light and dark. */
object FileIconPalette {
    val blue = ColorToken("#268bd2", "#268bd2")
    val grey = ColorToken("#6b7280", "#eee")
    val greyLight = ColorToken("#9ca3af", "#839496")
    val green = ColorToken("#059669", "#4bae4f")
    val orange = ColorToken("#d97706", "#cb4b16")
    val pink = ColorToken("#db2777", "#d33682")
    val purple = ColorToken("#7c3aed", "#6c71c4")
    val red = ColorToken("#dc2626", "#dc322f")
    val white = ColorToken("#374151", "#fdf6e3")
    val yellow = ColorToken("#eab308", "#ffcb29")
    val ignore = ColorToken("#9ca3af", "#586e75")

    val all = listOf(blue, grey, greyLight, green, orange, pink, purple, red, white, yellow, ignore)
}

/** app/(app)/session/[id]/file.tsx's colours beyond the theme's named ones. */
object FileViewerPalette {
    /** The chosen Diff / File button: `${theme.colors.textLink}66`. */
    val toggleSelected = ColorToken("#2BACCC66")
    /** Its label: 'white'. */
    val toggleSelectedText = ColorToken("#FFFFFF")

    val all = listOf(toggleSelected, toggleSelectedText)
}

/** file.tsx's type. */
object FileViewerTextStyles {
    /** The header's path: Typography.mono() at 14. */
    val path = TextStyle(FontFace.Mono, 14)
    /** Diff / File: 14 at weight 600, the semibold face. */
    val toggle = TextStyle(FontFace.SemiBold, 14)
    /** DiffDisplay's rows and SimpleSyntaxHighlighter: mono 14 on a 20 line; headers, keywords, types and functions semibold. */
    val code = TextStyle(FontFace.Mono, 14, 20)
    val codeBold = TextStyle(FontFace.MonoSemiBold, 14, 20)
    /** "File is empty" / "No changes to display": italic 16. */
    val message = TextStyle(FontFace.Italic, 16)
    /** "Loading {fileName}...", and the error and binary states' text: 16. */
    val body = TextStyle(FontFace.Regular, 16)
    /** "Error" / "Binary File": 18, bold over the semibold face. */
    val stateTitle = TextStyle(FontFace.SemiBold, 18)
    /** The binary file's name under them: 14. */
    val fileName = TextStyle(FontFace.Regular, 14)
}
