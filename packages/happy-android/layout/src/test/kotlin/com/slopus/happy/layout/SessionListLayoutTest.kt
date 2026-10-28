package com.slopus.happy.layout

import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.

class FlatSessionRowLayoutTest {
    private fun row(
        width: Double = 393.0,
        timestamp: String = "12:55",
        workspace: String? = "fix-login",
        divider: Boolean = true
    ): FlatSessionRowLayout {
        return FlatSessionRowLayout(FlatSessionRowLayout.Input(
            width = width,
            title = text("Fix the build", lineHeight = 22.0, maxLines = 1),
            timestamp = text(timestamp, lineHeight = 22.0, maxLines = 1),
            project = text("happy", lineHeight = 20.0, maxLines = 1),
            workspace = workspace?.let { text(it, lineHeight = 18.0, maxLines = 1) },
            showsDivider = divider
        ))
    }

    @Test fun testRowGeometry() {
        val layout = row()
        assertSize(layout.size, Size(393, 81))
        assertFrame(layout.avatar, rect(16, 10.5, 60, 60))
        // Corner slot: at least 56 wide; the stamp is right-aligned in it.
        assertFrame(layout.timestamp, rect(337, 10, 40, 22))
        assertFrame(layout.dot, rect(357, 11, 20, 20))
        assertFrame(layout.title, rect(88, 10, 225, 22))
        assertFrame(layout.project, rect(88, 32, 289, 20))
        assertFrame(layout.workspace, rect(88, 53, 72, 18))
        assertFrame(layout.branchIcon, rect(164, 55.5, 13, 13))
        assertFrame(layout.divider, rect(88, 80.67, 305, 0.33))
    }

    @Test fun testAWideStampSizesTheSlotAndTheTitleGivesWay() {
        val layout = row(timestamp = "12/17/25")
        assertFrame(layout.timestamp, rect(313, 10, 64, 22))
        assertFrame(layout.title, rect(88, 10, 217, 22))
    }

    @Test fun testThePrimaryCheckoutHasNoWorkspaceLineButKeepsItsHeight() {
        val layout = row(workspace = null, divider = false)
        XCTAssertNil(layout.workspace)
        XCTAssertNil(layout.branchIcon)
        XCTAssertNil(layout.divider)
        XCTAssertEqual(layout.size.height, 81, "every row is the same height, so the list never jumps")
    }

    // MARK: Text size (FlatSessionRow.tsx: the row is `paddingVertical: 10`
    // around `alignItems: 'center'` of the 60 avatar and the text column;
    // the title row is its 22 line beside the 22 corner box, the project
    // its 20 line, the workspace row its 18 line with `minHeight: 18`.
    // RN scales the lines, not the 22 box, the 18 minimum, the avatar or
    // the paddings.)

    /// A row whose labels each measure one line of `lines`, as a one-line
    /// label does at that size.
    fun row(lines: FlatSessionRowLayout.Lines, workspace: Boolean = true, stamp: Double = 22.0): FlatSessionRowLayout {
        return FlatSessionRowLayout(FlatSessionRowLayout.Input(
            width = 393.0,
            title = fixed(200, lines.title),
            timestamp = fixed(40, stamp),
            project = fixed(100, lines.project),
            workspace = if (workspace) fixed(72, lines.workspace) else null,
            showsDivider = true,
            lines = lines
        ))
    }

    @Test fun testTheDesignedLinesAreTheStyles() {
        XCTAssertEqual(FlatSessionRowLayout.Lines.designed, FlatSessionRowLayout.Lines(title = 22.0, project = 20.0, workspace = 18.0))
        XCTAssertEqual(TextStyles.sessionTitle.lineHeight, 22)
        XCTAssertEqual(TextStyles.sessionProject.lineHeight, 20)
        XCTAssertEqual(TextStyles.sessionWorkspace.lineHeight, 18)
        for (workspace in listOf(false, true)) {
            XCTAssertEqual(FlatSessionRowLayout.height(lines = FlatSessionRowLayout.Lines.designed, workspace = workspace), 81)
            XCTAssertEqual(row(lines = FlatSessionRowLayout.Lines.designed, workspace = workspace), FlatSessionRowLayout(FlatSessionRowLayout.Input(
                width = 393.0,
                title = fixed(200, 22),
                timestamp = fixed(40, 22),
                project = fixed(100, 20),
                workspace = if (workspace) fixed(72, 18) else null,
                showsDivider = true
            )), "the designed lines are the default")
        }
    }

    /// The largest text size on a 2x screen: 22, 20 and 18 × 3.571, each
    /// up to the next pixel, are 79, 71.5 and 64.5.
    @Test fun testTheLargestTextGrowsTheRowByItsLines() {
        val lines = FlatSessionRowLayout.Lines(title = 79.0, project = 71.5, workspace = 64.5)
        val layout = row(lines = lines)
        // 10 + 79 + 71.5 + 1 + 64.5 + 10.
        XCTAssertEqual(layout.size.height, 236)
        XCTAssertEqual(layout.title, rect(88, 10, 225, 79))
        // The 22 corner box is centred on the title's row; so is the dot.
        XCTAssertEqual(layout.timestamp, rect(337, 38.5, 40, 22))
        XCTAssertEqual(layout.dot, rect(357, 39.5, 20, 20))
        XCTAssertEqual(layout.project, rect(88, 89, 289, 71.5))
        XCTAssertEqual(layout.workspace, rect(88, 161.5, 72, 64.5))
        // The 13 glyph keeps its size, centred on the workspace's row.
        XCTAssertEqual(layout.branchIcon, rect(164, 187.25, 13, 13))
        XCTAssertEqual(layout.avatar, rect(16, 88, 60, 60))
        XCTAssertEqual(layout.divider?.minY, 236 - 0.33)

        // No workspace: its row keeps only its 18 minimum.
        val primary = row(lines = lines, workspace = false)
        XCTAssertEqual(primary.size.height, 189.5)
        XCTAssertEqual(primary.title, rect(88, 10, 225, 79))
        XCTAssertEqual(primary.avatar, rect(16, 64.75, 60, 60))
    }

    /// The smallest text size on a 2x screen: 18.5, 16.5 and 15. The text
    /// column is shorter than the avatar, so the avatar holds the row and
    /// the column is centred in it.
    @Test fun testTheAvatarHoldsTheRowAtSmallText() {
        val lines = FlatSessionRowLayout.Lines(title = 18.5, project = 16.5, workspace = 15.0)
        val layout = row(lines = lines, stamp = 18.0)
        XCTAssertEqual(layout.size.height, 80)
        // The column is 22 + 16.5 + 1 + 18 = 57.5, centred: 1.25 under the
        // padding.
        XCTAssertEqual(layout.title, rect(88, 13, 225, 18.5))
        XCTAssertEqual(layout.timestamp, rect(337, 13.25, 40, 18))
        XCTAssertEqual(layout.project, rect(88, 33.25, 289, 16.5))
        XCTAssertEqual(layout.workspace, rect(88, 52.25, 72, 15))
        XCTAssertEqual(layout.avatar, rect(16, 10, 60, 60))
        XCTAssertEqual(row(lines = lines, workspace = false).size.height, 80)
    }

    /// 20 + max(60, column): the avatar's 60 holds the row until the
    /// column passes it, and a pixel past grows it by that pixel.
    @Test fun testTheRowGrowsExactlyPastTheAvatar() {
        val atAvatar = FlatSessionRowLayout.Lines(title = 22.0, project = 19.0, workspace = 18.0)
        XCTAssertEqual(FlatSessionRowLayout.height(lines = atAvatar, workspace = true), 80)
        XCTAssertEqual(row(lines = atAvatar).size.height, 80)
        XCTAssertEqual(row(lines = atAvatar).title.minY, 10)
        XCTAssertEqual(row(lines = atAvatar).avatar.minY, 10)
        val past = FlatSessionRowLayout.Lines(title = 22.0, project = 19.5, workspace = 18.0)
        XCTAssertEqual(FlatSessionRowLayout.height(lines = past, workspace = true), 80.5)
        XCTAssertEqual(row(lines = past).size.height, 80.5)
        XCTAssertEqual(row(lines = past).title.minY, 10)
        XCTAssertEqual(row(lines = past).avatar.minY, 10.25)
    }

    /// The title's row and the workspace's keep their 22 and 18 until their
    /// line passes them.
    @Test fun testTheMinimumsHoldUntilTheLinePassesThem() {
        fun height(title: Number, workspace: Number, hasWorkspace: Boolean = true): Double {
            return FlatSessionRowLayout.height(lines = FlatSessionRowLayout.Lines(title = title, project = 20, workspace = workspace), workspace = hasWorkspace)
        }
        XCTAssertEqual(height(21.5, 18), 81)
        XCTAssertEqual(height(22, 18), 81)
        XCTAssertEqual(height(22.5, 18), 81.5)
        XCTAssertEqual(height(22, 17.5), 81)
        XCTAssertEqual(height(22, 18.5), 81.5)
        // Without a workspace, its line never shows.
        XCTAssertEqual(height(22, 40, hasWorkspace = false), 81)
        // A glyph taller than the line holds the workspace's row.
        XCTAssertEqual(FlatSessionRowLayout.height(lines = FlatSessionRowLayout.Lines.designed, workspace = true, branchIcon = 19.0), 82)
        XCTAssertEqual(FlatSessionRowLayout.height(lines = FlatSessionRowLayout.Lines.designed, workspace = false, branchIcon = 19.0), 81)
    }

    // MARK: Line changes (FlatSessionRow.tsx's `workspaceMeta`, at the
    // workspace row's end with `marginLeft: 'auto'` and never shrinking,
    // holds RigGitLineChanges: 8 of `paddingLeft`, then GitLineChanges'
    // 11s, which set no line height. The workspace's side has the rest.)

    private fun counted(
        width: Double = 393.0,
        workspace: Measurable? = fixed(72, 18),
        changes: Size?,
        lines: FlatSessionRowLayout.Lines = FlatSessionRowLayout.Lines.designed
    ): FlatSessionRowLayout {
        return FlatSessionRowLayout(FlatSessionRowLayout.Input(
            width = width,
            title = fixed(200, lines.title),
            timestamp = fixed(40, 22),
            project = fixed(100, lines.project),
            workspace = workspace,
            changes = changes,
            showsDivider = true,
            lines = lines
        ))
    }

    @Test fun testTheCountsEndTheWorkspacesRow() {
        val line: Double = 43.0 / 3
        val layout = counted(changes = Size(40, line))
        XCTAssertEqual(layout.size.height, 81)
        // At the right edge, centred on the 18 row at 53.
        XCTAssertEqual(layout.changes, rect(337, 53 + (18 - line) / 2, 40, line))
        XCTAssertEqual(layout.workspace, rect(88, 53, 72, 18))
        XCTAssertEqual(layout.branchIcon, rect(164, 55.5, 13, 13))
        XCTAssertNil(counted(changes = null).changes)
    }

    @Test fun testTheWorkspaceYieldsTheCountsTheirWidth() {
        val long = text("a-workspace-name-far-too-long-for-the-row", lineHeight = 18.0, maxLines = 1)
        // Alone: the column's 289 less the 4 and the 13 glyph.
        val alone = counted(workspace = long, changes = null)
        XCTAssertEqual(alone.workspace?.width, 272)
        XCTAssertEqual(alone.branchIcon?.maxX, 377)
        // Beside 40 of counts: 8 + 40 less again, and the glyph ends
        // where their 8 begins.
        val yielding = counted(workspace = long, changes = Size(40, 14.5))
        XCTAssertEqual(yielding.workspace?.width, 224)
        XCTAssertEqual(yielding.branchIcon?.maxX, 329)
        XCTAssertEqual(yielding.changes?.minX, 337)
        // A short one keeps its own width.
        XCTAssertEqual(counted(changes = Size(40, 14.5)).workspace?.width, 72)
    }

    @Test fun testCountsWiderThanTheRowStartWhereTheColumnDoes() {
        // The column runs from 88 to 104; the counts keep their 40 from
        // 88 + 8, and the workspace has nothing.
        val layout = counted(width = 120.0, workspace = text("fix-login", lineHeight = 18.0, maxLines = 1), changes = Size(40, 14.5))
        XCTAssertEqual(layout.changes?.minX, 96)
        XCTAssertEqual(layout.changes?.width, 40)
        XCTAssertEqual(layout.workspace?.width, 0)
    }

    /// At the designed size the counts' 14.3, or a screen's 14⅓ or 14.5,
    /// never reaches the row's 18.
    @Test fun testTheCountsNeverMoveADesignedRow() {
        XCTAssertEqual(FlatSessionRowLayout.Lines.designed.changes, 14.3)
        XCTAssertEqual(TextStyles.sessionLineChanges, TextStyle(FontFace.Regular, 11))
        for (workspace in listOf(false, true)) {
            for (changes in listOf(false, true)) {
                XCTAssertEqual(FlatSessionRowLayout.height(lines = FlatSessionRowLayout.Lines.designed, workspace = workspace, changes = changes), 81)
                val pixel = FlatSessionRowLayout.Lines(title = 22.0, project = 20.0, workspace = 18.0, changes = 14.5)
                XCTAssertEqual(FlatSessionRowLayout.height(lines = pixel, workspace = workspace, changes = changes), 81)
            }
        }
    }

    /// The largest text size on a 2x screen: the counts' 11 × 3.571 at
    /// 1.3 em is 51.07, 51.5 at a pixel. Under a workspace they sit in its
    /// taller row; without one their line is the row's.
    @Test fun testTheCountsLineHoldsTheRowWithoutAWorkspace() {
        val lines = FlatSessionRowLayout.Lines(title = 79.0, project = 71.5, workspace = 64.5, changes = 51.5)
        val size = Size(120, 51.5)
        val underWorkspace = counted(changes = size, lines = lines)
        XCTAssertEqual(underWorkspace.size.height, 236)
        XCTAssertEqual(underWorkspace.changes, rect(257, 168, 120, 51.5))
        val alone = counted(workspace = null, changes = size, lines = lines)
        // 10 + 79 + 71.5 + 1 + 51.5 + 10.
        XCTAssertEqual(alone.size.height, 223)
        XCTAssertEqual(alone.changes, rect(257, 161.5, 120, 51.5))
        XCTAssertEqual(alone.avatar, rect(16, 81.5, 60, 60))
        XCTAssertEqual(counted(workspace = null, changes = null, lines = lines).size.height, 189.5)
    }
}

class SessionListChromeLayoutTest {
    @Test fun testDayHeader() {
        val layout = SessionDayHeaderLayout(width = 393.0, label = text("Today", lineHeight = 18.0))
        assertFrame(layout.label, rect(16, 20, 361, 18))
        assertSize(layout.size, Size(393, 46))
    }

    @Test fun testArchiveToggleCentresTheLabelBetweenHairlines() {
        val layout = ArchiveToggleLayout(width = 393.0, label = text("Show archived", lineHeight = 18.0))
        assertFrame(layout.leadingLine, rect(24, 32.835, 108.5, 0.33))
        assertFrame(layout.label, rect(144.5, 24, 104, 18))
        assertFrame(layout.trailingLine, rect(260.5, 32.835, 108.5, 0.33))
        assertSize(layout.size, Size(393, 50))
    }

    @Test fun testNoSessionsIsCentredInTheSafeArea() {
        val layout = NoSessionsLayout(NoSessionsLayout.Input(
            size = phone,
            safeArea = phoneUnderBar,
            title = text("No sessions yet", lineHeight = 30.0),
            description = text("Start one on a connected machine.", lineHeight = 22.0),
            action = null
        ))
        assertFrame(layout.icon, rect(168.5, 364.5, 56, 56))
        assertFrame(layout.title, rect(136.5, 440.5, 120, 30))
        assertFrame(layout.description, rect(64.5, 478.5, 264, 22))
        XCTAssertNil(layout.action)
    }

    @Test fun testNoSessionsWithAnAction() {
        val layout = NoSessionsLayout(NoSessionsLayout.Input(
            size = phone,
            safeArea = phoneUnderBar,
            title = text("No sessions yet", lineHeight = 30.0),
            description = text("Start one on a connected machine.", lineHeight = 22.0),
            action = fixed(132, 40)
        ))
        // 44 more content lifts everything by 22.
        assertFrame(layout.icon, rect(168.5, 342.5, 56, 56))
        assertFrame(layout.action, rect(130.5, 506.5, 132, 40))
    }

    @Test fun testNoSessionsStartNewSessionStretchesAcross() {
        val layout = NoSessionsLayout(NoSessionsLayout.Input(
            size = phone,
            safeArea = phoneUnderBar,
            title = text("No sessions yet", lineHeight = 30.0),
            description = text("Start one on a connected machine.", lineHeight = 22.0),
            buttonHeight = 50.0,
            action = fixed(132, 40)
        ))
        // 94 more content lifts everything by 47; the button follows the
        // description's 24 and the action sits 4 under it.
        assertFrame(layout.icon, rect(168.5, 317.5, 56, 56))
        assertFrame(layout.description, rect(64.5, 431.5, 264, 22))
        assertFrame(layout.button, rect(0, 477.5, 393, 50))
        assertFrame(layout.action, rect(130.5, 531.5, 132, 40))
    }

    @Test fun testNoSessionsThatFitsFillsTheScreenAndDoesNotScroll() {
        val layout = NoSessionsLayout(NoSessionsLayout.Input(
            size = phone,
            safeArea = phoneUnderBar,
            title = text("No sessions yet", lineHeight = 30.0),
            description = text("Start one on a connected machine.", lineHeight = 22.0),
            buttonHeight = 50.0,
            action = fixed(132, 40)
        ))
        XCTAssertEqual(layout.contentHeight, 852)
    }

    @Test fun testNoSessionsTallerThanTheSafeAreaStartsAtItsTopAndScrolls() {
        // 56 + 20 + 400 + 8 + 200 + 24 + 50 + 4 + 40 = 802, over the 683
        // between the bar and the home indicator less the 32 margin.
        val layout = NoSessionsLayout(NoSessionsLayout.Input(
            size = phone,
            safeArea = phoneUnderBar,
            title = fixed(345, 400),
            description = fixed(312, 200),
            buttonHeight = 50.0,
            action = fixed(132, 40)
        ))
        assertFrame(layout.icon, rect(168.5, 103, 56, 56))
        assertFrame(layout.title, rect(24, 179, 345, 400))
        assertFrame(layout.description, rect(40.5, 587, 312, 200))
        assertFrame(layout.button, rect(0, 811, 393, 50))
        assertFrame(layout.action, rect(130.5, 865, 132, 40))
        // The action, then the margin and the home indicator.
        XCTAssertEqual(layout.contentHeight, 905 + 32 + 34)
    }

    @Test fun testNoSessionsThatJustFitsIsBothCentredAndAtTheTop() {
        // 56 + 20 + 300 + 8 + 225 + 24 + 50 = 683: exactly the space.
        val exact = NoSessionsLayout(NoSessionsLayout.Input(
            size = phone,
            safeArea = phoneUnderBar,
            title = fixed(120, 300),
            description = fixed(264, 225),
            buttonHeight = 50.0,
            action = null
        ))
        assertFrame(exact.icon, rect(168.5, 103, 56, 56))
        assertFrame(exact.button, rect(0, 736, 393, 50))
        XCTAssertEqual(exact.contentHeight, 852)

        // A point more starts at the same place and scrolls by that point.
        val over = NoSessionsLayout(NoSessionsLayout.Input(
            size = phone,
            safeArea = phoneUnderBar,
            title = fixed(120, 301),
            description = fixed(264, 225),
            buttonHeight = 50.0,
            action = null
        ))
        assertFrame(over.icon, rect(168.5, 103, 56, 56))
        assertFrame(over.button, rect(0, 737, 393, 50))
        XCTAssertEqual(over.contentHeight, 853)
    }

    @Test fun testTextAction() {
        val layout = TextActionLayout(width = 393.0, label = text("Show archived", lineHeight = 20.0))
        assertSize(layout.size, Size(132, 40))
        assertFrame(layout.label, rect(14, 10, 104, 20))
    }
}

/// Seeds hashed by node with happy-app's `hashCode`.
class GeneratedAvatarTest {
    @Test fun testHashMatchesJavaScript() {
        XCTAssertEqual(GeneratedAvatar.hashCode("avatar-style-preview"), 2_011_298_248)
        XCTAssertEqual(GeneratedAvatar.hashCode("avatar-style-previewcolor"), 2_095_860_779)
        XCTAssertEqual(GeneratedAvatar.hashCode("m1:/Users/steve/happy"), 1_073_159_556)
        XCTAssertEqual(GeneratedAvatar.hashCode("abc"), 96_354)
        XCTAssertEqual(GeneratedAvatar.hashCode(""), 0)
        XCTAssertEqual(GeneratedAvatar.hashCode("machine:bot:b1"), 1_899_767_499)
        XCTAssertEqual(GeneratedAvatar.hashCode("😀x"), 54_959_989, "UTF-16 code units, as charCodeAt")
    }

    @Test fun testBrutalistPicks() {
        val preview = GeneratedAvatar.brutalist(seed = "avatar-style-preview", monochrome = false)
        XCTAssertEqual(preview.imageName, "Abstract-149")
        XCTAssertEqual(preview.tint, RGBA.hex("#84E600"))
        XCTAssertEqual(preview.background, RGBA.hex("#C026D3"))
        XCTAssertEqual(GeneratedAvatar.brutalist(seed = "m1:/Users/steve/happy", monochrome = false).imageName, "Brutalism-35")
        XCTAssertEqual(GeneratedAvatar.brutalist(seed = "m1:/Users/steve/happy", monochrome = false).background, RGBA.hex("#047857"))
        XCTAssertEqual(GeneratedAvatar.brutalist(seed = "machine:bot:b1", monochrome = false).imageName, "Brutalism-98")
        XCTAssertEqual(GeneratedAvatar.brutalist(seed = "", monochrome = false).imageName, "Abstract-1")

        val mono = GeneratedAvatar.brutalist(seed = "abc", monochrome = true)
        XCTAssertEqual(mono.imageName, "Abstract-175")
        XCTAssertEqual(mono.tint, RGBA.hex("#999999"))
        XCTAssertEqual(mono.background, RGBA.hex("#F0F0F0"))
    }

    @Test fun testBrutalistImageOrder() {
        XCTAssertEqual(GeneratedAvatar.brutalistImageName(261), "Abstract-262")
        XCTAssertEqual(GeneratedAvatar.brutalistImageName(262), "Bauhaus-1")
        XCTAssertEqual(GeneratedAvatar.brutalistImageName(301), "Bauhaus-40")
        XCTAssertEqual(GeneratedAvatar.brutalistImageName(302), "Brutalism-1")
        XCTAssertEqual(GeneratedAvatar.brutalistImageName(419), "Brutalism-118")
    }

    @Test fun testGradientPicks() {
        XCTAssertEqual(GeneratedAvatar.gradientImageName(seed = "avatar-style-preview"), "gradient-49")
        XCTAssertEqual(GeneratedAvatar.gradientImageName(seed = ""), "gradient-01")
        XCTAssertEqual(GeneratedAvatar.gradientImageName(seed = "machine:bot:b1"), "gradient-100")
        XCTAssertEqual(GeneratedAvatar.gradientImageName(seed = "😀x"), "gradient-90")
    }

    @Test fun testPixels() {
        val palette = listOf("#0a0310", "#49007e", "#ff005b", "#ff7d10", "#ffb238").map { RGBA.hex(it)!! }
        val cells = GeneratedAvatar.pixels(seed = "abc", monochrome = false)
        XCTAssertEqual(cells.size, 64)
        // node: listOf(0,0,0,2,4,0,1,2)
        XCTAssertEqual(cells.take(8), listOf(0, 0, 0, 2, 4, 0, 1, 2).map { palette[it] })
        XCTAssertEqual(GeneratedAvatar.pixels(seed = "abc", monochrome = true).first(), RGBA.hex("#070707"))
        val frames = GeneratedAvatar.pixelFrames(size = 60.0)
        assertFrame(frames[9], rect(7.5, 7.5, 7.5, 7.5))
        assertFrame(frames[63], rect(52.5, 52.5, 7.5, 7.5))
    }

    @Test fun testGlyphsAndBadge() {
        assertFrame(GeneratedAvatar.brutalistGlyphFrame(avatarSize = 60.0), rect(6, 6, 48, 48))
        assertFrame(GeneratedAvatar.botGlyphFrame(avatarSize = 60.0), rect(13.5, 13.5, 33, 33))
        val badge = GeneratedAvatar.badge(avatarSize = 60.0, glyphRatio = GeneratedAvatar.badgeGlyphRatio(harness = "claude"))
        // round(60 × 0.42) = 25, hanging 2 past the bottom-right corner.
        assertFrame(badge.first, rect(37, 37, 25, 25))
        assertFrame(badge.second, rect(39.5, 39.5, 20, 20))
        XCTAssertEqual(GeneratedAvatar.badgeGlyphRatio(harness = "rig"), 0.26)
        XCTAssertEqual(GeneratedAvatar.badgeGlyphRatio(harness = "agy"), 0.42)
    }

    /// Every image a seed can pick ships in the app's assets.
    @Test fun testEveryAvatarImageIsBundled() {
        // Gradle runs unit tests from the module's directory.
        val assets = java.io.File("../app/src/main/assets/avatars")
        for (index in 0 until GeneratedAvatar.brutalistImageCount) {
            val file = java.io.File(assets, "brutalist/${GeneratedAvatar.brutalistImageName(index)}.png")
            XCTAssertTrue(file.exists(), "missing $file")
        }
        for (index in 1..GeneratedAvatar.gradientImageCount) {
            val file = java.io.File(assets, "gradients/%02d.png".format(index))
            XCTAssertTrue(file.exists(), "missing $file")
        }
    }
}
