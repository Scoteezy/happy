package com.slopus.happy.layout

// The diff renderer's tokens (happy-ios Tokens/SecondaryTokens.swift
// `DiffPalette` and `DiffTextStyles`): components/diff/DiffPalette.ts.

object DiffPalette {
    val rowAdded = ColorToken("#E6FFEC", "#12261E")
    val rowRemoved = ColorToken("#FFEBE9", "#25171C")
    val rowContext = ColorToken.Clear
    val gutterAdded = ColorToken("#CCFFD8", "#1B4721")
    val gutterRemoved = ColorToken("#FFD7D5", "#78191B")
    val gutterContext = ColorToken.Clear
    val gutterBorder = ColorToken("#D8DEE4", "#21262D")
    val lineNumber = ColorToken("#959DA5", "#6E7681")
    val markerAdded = ColorToken("#1A7F37", "#3FB950")
    val markerRemoved = ColorToken("#CF222E", "#F85149")
    val wordAdded = ColorToken("#ABF2BC", "#1F6F2E")
    val wordRemoved = ColorToken("#FFC1C0", "#8B2C2F")
    val hunkBackground = ColorToken("#F1F8FF", "#161B22")
    val hunkText = ColorToken("#005CC5", "#58A6FF")
    val sectionText = ColorToken("#6E7781", "#8B949E")

    // Traditional colours (file.tsx DiffDisplay).
    val addedBackground = ColorToken("#E6FFED", "#0D2E1F")
    val addedBorder = ColorToken("#34D058", "#3FB950")
    val addedText = ColorToken("#24292E", "#C9D1D9")
    val removedBackground = ColorToken("#FFEEF0", "#3F1B23")
    val removedBorder = ColorToken("#D73A49", "#F85149")
    val removedText = ColorToken("#24292E", "#C9D1D9")
    val contextText = ColorToken("#586069", "#8B949E")

    /** diff.syntax, by span kind. */
    object Syntax {
        val plain = ColorToken("#1F2328", "#E6EDF3")
        val keyword = ColorToken("#CF222E", "#FF7B72")
        val string = ColorToken("#0A3069", "#A5D6FF")
        val comment = ColorToken("#6E7781", "#8B949E")
        val number = ColorToken("#0550AE", "#79C0FF")
        val function = ColorToken("#8250DF", "#D2A8FF")
        val operator = ColorToken("#0550AE", "#79C0FF")
        val punctuation = ColorToken("#1F2328", "#E6EDF3")
        val type = ColorToken("#953800", "#FFA657")
        val variable = ColorToken("#953800", "#FFA657")
        val tag = ColorToken("#116329", "#7EE787")
        val attr = ColorToken("#0550AE", "#79C0FF")
    }
}

object DiffTextStyles {
    /** DiffFileView's "Show N more lines": semibold 13. */
    val showMore = TextStyle(FontFace.SemiBold, 13)

    /**
     * DiffFileHeader: the directory (mono 12), the file name (mono
     * semibold 12.5), the change badge (mono semibold 10.5, upper case) and
     * the +N / −N counts (mono 12).
     */
    val headerDir = TextStyle(FontFace.Mono, 12)
    val headerFileName = TextStyle(FontFace.MonoSemiBold, 12.5)
    val headerBadge = TextStyle(FontFace.MonoSemiBold, 10.5, uppercase = true)
    val headerCount = TextStyle(FontFace.Mono, 12)

    /** FileSection's size hint and messages, and DiffImageView's "binary". */
    val sectionMessage = TextStyle(FontFace.Regular, 13)

    /** DiffImageView's Before / After. */
    val imageLabel = TextStyle(FontFace.MonoSemiBold, 10.5, uppercase = true)

    /**
     * DiffFilesList's empty text, the changes screen's centred messages and
     * HappyAgentDiffView's notices: Typography.default() at RN's 14.
     */
    val body = TextStyle(FontFace.Regular, 14)

    /** HappyAgentDiffView's Retry. */
    val retry = TextStyle(FontFace.SemiBold, 14)
}
