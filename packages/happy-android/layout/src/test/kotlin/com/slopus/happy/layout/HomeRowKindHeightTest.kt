package com.slopus.happy.layout

import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.

/// The home list's headings and notices, sized from their layouts with the
/// text's line heights in place of labels.
class HomeRowKindHeightTest {
    private val widths: List<Double> = listOf(0.0, 120.0, 393.0, 1024.0)
    /// The styles' lines, the desktop glyph at 12 on a 3x screen
    /// ((454 + 71) / 512 × 12, up to a third), a 3x hairline.
    private val metrics = HomeRowMetrics(
        dayHeaderLine = TextStyles.sessionDayHeader.lineHeight!!,
        archiveToggleLine = TextStyles.archiveToggle.lineHeight!!,
        machineLine = TextStyles.projectMachine.lineHeight!!,
        sectionLine = TextStyles.projectSection.lineHeight!!,
        machineIcon = Size(12, 37.0 / 3),
        hairline = 1.0 / 3
    )

    fun height(kind: HomeRowKind, width: Double = 393.0, metrics: HomeRowMetrics? = null): Double {
        return HomeRowHeights.height(kind, width = width, metrics = metrics ?: this.metrics)
    }

    @Test fun testTheStylesAreOneFixedLine() {
        XCTAssertEqual(metrics.dayHeaderLine, 18)
        XCTAssertEqual(metrics.archiveToggleLine, 18)
        XCTAssertEqual(metrics.machineLine, 14)
        XCTAssertEqual(metrics.sectionLine, 14)
    }

    @Test fun testHeadings() {
        // 20 above, a line, 8 below.
        XCTAssertEqual(height(HomeRowKind.DayHeader(label = "Today")), 46)
        // Banded: 24 and 8; dividing the page: 20 and 12.
        XCTAssertEqual(height(HomeRowKind.ArchiveToggle(label = "Show archived", banded = true)), 50)
        XCTAssertEqual(height(HomeRowKind.ArchiveToggle(label = "Show archived", banded = false)), 50)
        // 14 above, the name's line over the glyph, 2 below.
        XCTAssertEqual(height(HomeRowKind.MachineHeader(name = "MacBook Pro")), 30, accuracy = 0.0001)
        // 18 above, a line, 6 below.
        XCTAssertEqual(height(HomeRowKind.Section(label = "Projects")), 38)
    }

    @Test fun testAnEmptyLabelHasNoLine() {
        XCTAssertEqual(height(HomeRowKind.DayHeader(label = "")), 28)
        XCTAssertEqual(height(HomeRowKind.ArchiveToggle(label = "", banded = true)), 32)
        XCTAssertEqual(height(HomeRowKind.ArchiveToggle(label = "", banded = false)), 32)
        // Only the glyph is left to hold the row open.
        XCTAssertEqual(height(HomeRowKind.MachineHeader(name = "")), 14 + 37.0 / 3 + 2, accuracy = 0.0001)
        XCTAssertEqual(height(HomeRowKind.Section(label = "")), 24)
    }

    @Test fun testAFlatSessionIsItsLines() {
        for (width in widths) {
            for (workspace in listOf(false, true)) {
                for (changes in listOf(false, true)) {
                    XCTAssertEqual(height(HomeRowKind.FlatSession(workspace = workspace, changes = changes), width = width), HomeRowHeights.flatSession(width = width))
                }
            }
        }
        XCTAssertEqual(height(HomeRowKind.FlatSession(workspace = true, changes = false)), 81)
        val larger = metrics.copy()
        larger.sessionLines = FlatSessionRowLayout.Lines(title = 79.0, project = 71.5, workspace = 64.5, changes = 51.5)
        XCTAssertEqual(height(HomeRowKind.FlatSession(workspace = true, changes = false), metrics = larger), 236)
        XCTAssertEqual(height(HomeRowKind.FlatSession(workspace = false, changes = false), metrics = larger), 189.5)
        // The counts' line under a taller workspace changes nothing; alone
        // it is the row's third line.
        XCTAssertEqual(height(HomeRowKind.FlatSession(workspace = true, changes = true), metrics = larger), 236)
        XCTAssertEqual(height(HomeRowKind.FlatSession(workspace = false, changes = true), metrics = larger), 223)
        larger.branchIcon = Size(13, 70)
        XCTAssertEqual(height(HomeRowKind.FlatSession(workspace = true, changes = false), metrics = larger), 241.5)
    }

    /// The kind's height is the row's own, with any words in it, at any
    /// lines and width.
    @Test fun testAFlatSessionsHeightIsItsLayouts() {
        val lineSets: List<FlatSessionRowLayout.Lines> = listOf(
            FlatSessionRowLayout.Lines.designed,
            FlatSessionRowLayout.Lines(title = 18.5, project = 16.5, workspace = 15.0, changes = 12.0),
            FlatSessionRowLayout.Lines(title = 22.0, project = 19.0, workspace = 18.0, changes = 14.5),
            FlatSessionRowLayout.Lines(title = 29.5, project = 27.0, workspace = 24.5, changes = 19.0),
            FlatSessionRowLayout.Lines(title = 79.0, project = 71.5, workspace = 64.5, changes = 51.5),
            // Counts whose line passes the workspace's.
            FlatSessionRowLayout.Lines(title = 22.0, project = 20.0, workspace = 18.0, changes = 30.0),
        )
        for (lines in lineSets) {
            val scaled = metrics.copy()
            scaled.sessionLines = lines
            for (width in widths) {
                for (workspace in listOf(false, true)) {
                    for (changes in listOf(false, true)) {
                        val layout = FlatSessionRowLayout(FlatSessionRowLayout.Input(
                            width = width,
                            title = text("A long title that never wraps on its one line", lineHeight = lines.title, maxLines = 1),
                            timestamp = text("12:55 PM", lineHeight = lines.title, maxLines = 1),
                            project = text("happy", lineHeight = lines.project, maxLines = 1),
                            workspace = if (workspace) text("fix-login", lineHeight = lines.workspace, maxLines = 1) else null,
                            branchIcon = scaled.branchIcon,
                            changes = if (changes) Size(64, lines.changes) else null,
                            showsDivider = true,
                            lines = lines
                        ))
                        XCTAssertEqual(
                            height(HomeRowKind.FlatSession(workspace = workspace, changes = changes), width = width, metrics = scaled),
                            layout.size.height,
                            "$lines at $width"
                        )
                    }
                }
            }
        }
    }

    @Test fun testTheContentRowsAreApplysOwnHeights() {
        for (width in widths) {
            XCTAssertEqual(height(HomeRowKind.ProjectCard, width = width), HomeRowHeights.projectCard(width = width))
            XCTAssertEqual(height(HomeRowKind.Worktree, width = width), HomeRowHeights.worktree(width = width))
            XCTAssertEqual(height(HomeRowKind.Chat, width = width), HomeRowHeights.projectChat(width = width))
        }
        XCTAssertEqual(height(HomeRowKind.ProjectCard), 60)
        XCTAssertEqual(height(HomeRowKind.Worktree), 48)
        XCTAssertEqual(height(HomeRowKind.Chat), 48)
    }

    @Test fun testNoHeightDependsOnWidthOrText() {
        val kinds: List<HomeRowKind> = listOf(
            HomeRowKind.DayHeader(label = "3 days ago"),
            HomeRowKind.ArchiveToggle(label = "Hide archived", banded = true),
            HomeRowKind.ArchiveToggle(label = "Hide archived", banded = false),
            HomeRowKind.MachineHeader(name = "Long name ".repeat(20)),
            HomeRowKind.Section(label = "Bots"),
        )
        for (kind in kinds) {
            for (width in widths) {
                XCTAssertEqual(height(kind, width = width), height(kind), "$kind at $width")
            }
        }
        XCTAssertEqual(height(HomeRowKind.DayHeader(label = "Today")), height(HomeRowKind.DayHeader(label = "x".repeat(200))))
    }

    /// A bigger font or another screen moves the heights: nothing here is
    /// fixed to today's styles.
    @Test fun testTheMetricsAreWhatTheHeightsAreMadeOf() {
        val larger = metrics.copy()
        larger.dayHeaderLine = 24.0
        larger.archiveToggleLine = 22.0
        larger.machineLine = 20.0
        larger.sectionLine = 17.0
        XCTAssertEqual(height(HomeRowKind.DayHeader(label = "Today"), metrics = larger), 52)
        XCTAssertEqual(height(HomeRowKind.ArchiveToggle(label = "Show", banded = true), metrics = larger), 54)
        XCTAssertEqual(height(HomeRowKind.ArchiveToggle(label = "Show", banded = false), metrics = larger), 54)
        XCTAssertEqual(height(HomeRowKind.MachineHeader(name = "Mac"), metrics = larger), 36)
        XCTAssertEqual(height(HomeRowKind.Section(label = "Bots"), metrics = larger), 41)

        val tallGlyph = metrics.copy()
        tallGlyph.machineIcon = Size(30, 30)
        XCTAssertEqual(height(HomeRowKind.MachineHeader(name = "Mac"), metrics = tallGlyph), 46)
    }

    // MARK: Notices

    private val chevron = Size(10, 18)

    fun banner(title: String, subtitle: String): HomeBannerHeights.Banner {
        return HomeBannerHeights.Banner(
            title = Measurable.fixed(width = 0.0, height = if (title.isEmpty()) 0 else 22),
            subtitle = text(subtitle, lineHeight = 20.0, maxLines = 2)
        )
    }

    @Test fun testAOneLineNotice() {
        // 4 above, an 11 + 22 + 2 + 20 + 11 row, 16 below.
        XCTAssertEqual(HomeBannerHeights.banner(banner("Offline", "Troubleshoot"), width = 393.0, maxWidth = null, chevron = chevron), 86)
    }

    @Test fun testASubtitleWrapsToTwoLinesAtMost() {
        // The text lane on a 393 phone: 361 of card, less 57 ahead, 16 + 8
        // after and the 4 + 10 chevron, is 266; 39 characters take 312.
        val whatsNew = banner("What's new", "See the latest updates and improvements")
        XCTAssertEqual(HomeBannerHeights.banner(whatsNew, width = 393.0, maxWidth = null, chevron = chevron), 106)
        XCTAssertEqual(HomeBannerHeights.banner(whatsNew, width = 1024.0, maxWidth = null, chevron = chevron), 86)
        val long = banner("Offline", "word ".repeat(80))
        XCTAssertEqual(HomeBannerHeights.banner(long, width = 393.0, maxWidth = null, chevron = chevron), 106, "two lines, then cut")
    }

    @Test fun testTheGroupKeepsToTheContentWidth() {
        val whatsNew = banner("What's new", "See the latest updates and improvements")
        XCTAssertEqual(HomeBannerHeights.banner(whatsNew, width = 1024.0, maxWidth = 320.0, chevron = chevron), 106)
    }

    @Test fun testAnEmptyTitleLeavesTheIconToHoldTheRow() {
        // 2 + 20 of text, under the 29 icon: 29 + 22.
        XCTAssertEqual(HomeBannerHeights.banner(banner("", "Troubleshoot"), width = 393.0, maxWidth = null, chevron = chevron), 4 + 51 + 16)
    }

    @Test fun testTheNoticesStack() {
        XCTAssertEqual(HomeBannerHeights.header(emptyList(), width = 393.0, maxWidth = null, chevron = chevron), 0)
        val notices = listOf(banner("Offline", "Troubleshoot"), banner("What's new", "See the latest updates and improvements"))
        XCTAssertEqual(HomeBannerHeights.header(notices, width = 393.0, maxWidth = null, chevron = chevron), 86 + 106)
    }

    @Test fun testTheGroupPaddingIsTheHeaderViews() {
        XCTAssertEqual(HomeBannerHeights.Metrics.groupPaddingTop, 4)
        XCTAssertEqual(HomeBannerHeights.Metrics.groupPaddingBottom, 16)
    }
}
