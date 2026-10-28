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
}
