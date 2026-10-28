package com.slopus.happy.layout

import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.

/// The home list grouped by project, on a 3x phone (hairline 1/3, so the
/// tree line is 2/3 wide).
class ProjectListLayoutTest {
    private val hairline: Double = 1.0 / 3

    @Test fun testProjectCardWithEverythingTrailing() {
        val layout = ProjectCardLayout(ProjectCardLayout.Input(
            width = 393.0,
            hairline = hairline,
            name = text("happy", lineHeight = 22.0, maxLines = 1),
            showsDot = true,
            changes = fixed(60, 18),
            showsAdd = true,
            showsTree = true
        ))
        assertSize(layout.size, Size(393, 60))
        assertFrame(layout.avatar, rect(16, 20, 28, 28))
        // The stem drops from under the avatar to the cell's foot.
        assertFrame(layout.stem, rect(29.667, 48, 0.667, 12))
        assertFrame(layout.add, rect(349, 18, 28, 32))
        assertFrame(layout.changes, rect(281, 25, 60, 18))
        assertFrame(layout.dot, rect(266, 30.5, 7, 7))
        assertFrame(layout.name, rect(56, 23, 202, 22))
    }

    @Test fun testABareProjectCardGivesTheNameTheRow() {
        val layout = ProjectCardLayout(ProjectCardLayout.Input(
            width = 393.0,
            hairline = hairline,
            name = text("happy", lineHeight = 22.0, maxLines = 1),
            showsDot = false,
            changes = null,
            showsAdd = false,
            showsTree = false
        ))
        XCTAssertNil(layout.stem)
        XCTAssertNil(layout.dot)
        XCTAssertNil(layout.changes)
        XCTAssertNil(layout.add)
        assertFrame(layout.name, rect(56, 23, 321, 22))
    }

    @Test fun testWorktreeBranchesOffTheTrunk() {
        val layout = WorktreeRowLayout(WorktreeRowLayout.Input(
            width = 393.0,
            hairline = hairline,
            label = text("fix-login", lineHeight = 21.0, maxLines = 1),
            showsDot = false,
            changes = fixed(52, 18),
            last = false
        ))
        assertSize(layout.size, Size(393, 48))
        assertFrame(layout.trunk, rect(29.667, 0, 0.667, 48))
        assertFrame(layout.elbow, rect(30, 23.667, 14, 0.667))
        XCTAssertNil(layout.chevron)
        XCTAssertNil(layout.dot)
        assertFrame(layout.changes, rect(325, 15, 52, 18))
        assertFrame(layout.label, rect(56, 13.5, 261, 21))
    }

    @Test fun testTheLastWorktreeStopsTheTrunkAtItsElbow() {
        val layout = WorktreeRowLayout(WorktreeRowLayout.Input(
            width = 393.0,
            hairline = hairline,
            label = text("fix-login", lineHeight = 21.0, maxLines = 1),
            showsDot = false,
            changes = null,
            last = true
        ))
        assertFrame(layout.trunk, rect(29.667, 0, 0.667, 24))
        // An empty trailing column still keeps its margin.
        assertFrame(layout.label, rect(56, 13.5, 313, 21))
    }

    @Test fun testTheToggleLeadsWithItsChevronAndHugsItsLabel() {
        val layout = WorktreeRowLayout(WorktreeRowLayout.Input(
            width = 393.0,
            hairline = hairline,
            label = text("Show all 5 workspaces", lineHeight = 20.0, maxLines = 1),
            chevron = Size(12, 12),
            showsDot = true,
            changes = null,
            last = true
        ))
        assertFrame(layout.chevron, rect(56, 18, 12, 12))
        assertFrame(layout.label, rect(73, 14, 168, 20))
        assertFrame(layout.dot, rect(370, 20.5, 7, 7))
    }

    @Test fun testALongToggleLabelIsClampedToItsLane() {
        val layout = WorktreeRowLayout(WorktreeRowLayout.Input(
            width = 200.0,
            hairline = hairline,
            label = text("Show all 12 workspaces", lineHeight = 20.0, maxLines = 1),
            chevron = Size(12, 12),
            showsDot = false,
            changes = null,
            last = true
        ))
        // 200 - 16 - 8 = 176 is the lane's right edge.
        assertFrame(layout.label, rect(73, 14, 103, 20))
    }

    @Test fun testChatRowWithATimestamp() {
        val layout = ProjectChatRowLayout(
            width = 393.0,
            name = text("Ops bot", lineHeight = 22.0, maxLines = 1),
            corner = text("12:55", lineHeight = 18.0, maxLines = 1)
        )
        assertSize(layout.size, Size(393, 48))
        assertFrame(layout.avatar, rect(16, 10, 28, 28))
        assertFrame(layout.corner, rect(337, 15, 40, 18))
        assertFrame(layout.name, rect(56, 13, 273, 22))
    }

    @Test fun testChatRowWithADot() {
        val layout = ProjectChatRowLayout(
            width = 393.0,
            name = text("Ops bot", lineHeight = 22.0, maxLines = 1),
            corner = fixed(10, 10)
        )
        assertFrame(layout.corner, rect(367, 19, 10, 10))
        assertFrame(layout.name, rect(56, 13, 303, 22))
    }

    @Test fun testSectionLabel() {
        val layout = ProjectSectionLayout(width = 393.0, label = text("PROJECTS", lineHeight = 14.0))
        assertFrame(layout.label, rect(16, 18, 365, 14))
        assertSize(layout.size, Size(393, 38))
    }

    @Test fun testMachineHeaderRunsARuleToTheEdge() {
        val layout = MachineHeaderLayout(
            width = 393.0,
            hairline = hairline,
            icon = Size(13, 13),
            name = text("MacBook Pro", lineHeight = 14.0, maxLines = 1)
        )
        assertFrame(layout.icon, rect(16, 14.5, 13, 13))
        assertFrame(layout.name, rect(35, 14, 88, 14))
        assertFrame(layout.rule, rect(129, 20.667, 248, 0.667))
        assertSize(layout.size, Size(393, 30))
    }

    @Test fun testAMachineNameStopsAtSixtyPercent() {
        val layout = MachineHeaderLayout(
            width = 393.0,
            hairline = hairline,
            icon = Size(13, 13),
            name = text("A very long machine name for this test", lineHeight = 14.0, maxLines = 1)
        )
        assertFrame(layout.name, rect(35, 14, 216.6, 14))
        assertFrame(layout.rule, rect(257.6, 20.667, 119.4, 0.667))
    }

    @Test fun testGitChangesSitOnOneCentreLine() {
        val all = GitChangesLayout(
            approximate = Size(8, 14),
            added = Size(24, 18),
            removed = Size(16, 18)
        )
        assertFrame(all.approximate, rect(0, 2, 8, 14))
        assertFrame(all.added, rect(12, 0, 24, 18))
        assertFrame(all.removed, rect(40, 0, 16, 18))
        assertSize(all.size, Size(56, 18))

        val added = GitChangesLayout(approximate = null, added = Size(24, 18), removed = null)
        XCTAssertNil(added.approximate)
        XCTAssertNil(added.removed)
        assertFrame(added.added, rect(0, 0, 24, 18))
        assertSize(added.size, Size(24, 18))
    }

    @Test fun testListHeaderStacksItsBanners() {
        val layout = HomeListHeaderLayout(width = 393.0, banners = listOf(block(height = 80.0), block(height = 64.0)))
        XCTAssertEqual(layout.banners.size, 2)
        assertFrame(layout.banners[0], rect(0, 0, 393, 80))
        assertFrame(layout.banners[1], rect(0, 80, 393, 64))
        assertSize(layout.size, Size(393, 144))
        assertSize(HomeListHeaderLayout(width = 393.0, banners = emptyList()).size, Size(393, 0))
    }

    @Test fun testTheUnbandedArchiveToggle() {
        val layout = ArchiveToggleLayout(
            width = 393.0,
            label = text("Show archived", lineHeight = 18.0),
            paddingTop = ArchiveToggleLayout.unbanded.top,
            paddingBottom = ArchiveToggleLayout.unbanded.bottom
        )
        assertFrame(layout.leadingLine, rect(24, 28.835, 108.5, 0.33))
        assertFrame(layout.label, rect(144.5, 20, 104, 18))
        assertFrame(layout.trailingLine, rect(260.5, 28.835, 108.5, 0.33))
        assertSize(layout.size, Size(393, 50))
    }
}

/// The glyph boxes come from `Icon.layout`: one line of the icon font
/// tall, so a hair taller than wide on a 3x screen.
class SessionListIconBoxTest {
    @Test fun testTheBranchIconCentresItsRNBox() {
        val layout = FlatSessionRowLayout(FlatSessionRowLayout.Input(
            width = 393.0,
            title = text("Fix the build", lineHeight = 22.0, maxLines = 1),
            timestamp = text("12:55", lineHeight = 22.0, maxLines = 1),
            project = text("happy", lineHeight = 20.0, maxLines = 1),
            workspace = text("fix-login", lineHeight = 18.0, maxLines = 1),
            branchIcon = Size(13, 13.33),
            showsDivider = true
        ))
        assertFrame(layout.workspace, rect(88, 53, 72, 18))
        assertFrame(layout.branchIcon, rect(164, 55.335, 13, 13.33))
        XCTAssertEqual(layout.size.height, 81)
    }

    @Test fun testNoSessionsCentresTheTallerIconBox() {
        val layout = NoSessionsLayout(NoSessionsLayout.Input(
            size = phone,
            safeArea = phoneUnderBar,
            icon = Size(56, 57.67),
            title = text("No sessions yet", lineHeight = 30.0),
            description = text("Start one on a connected machine.", lineHeight = 22.0),
            action = null
        ))
        assertFrame(layout.icon, rect(168.5, 363.665, 56, 57.67))
        assertFrame(layout.title, rect(136.5, 441.335, 120, 30))
        assertFrame(layout.description, rect(64.5, 479.335, 264, 22))
    }
}
