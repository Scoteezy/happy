package com.slopus.happy.layout

import com.slopus.happy.layout.chat.PermissionFooterLayout
import com.slopus.happy.layout.DeviceCalculations.DeviceType
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.

/// utils/responsive.ts and utils/deviceCalculations.ts (with the iOS cases
/// of deviceCalculations.test.ts), and components/layout.ts.
class TabletBreakpointTest {
    @Test fun testTheTabletLayoutStartsAtAWindow768Wide() {
        XCTAssertFalse(TabletBreakpoint.isTablet(767.5))
        XCTAssertTrue(TabletBreakpoint.isTablet(768.0))
        // A 13-inch iPad in half of Split View is a phone layout; full
        // screen it is a tablet either way round.
        XCTAssertFalse(TabletBreakpoint.isTablet(678.0))
        XCTAssertTrue(TabletBreakpoint.isTablet(1024.0))
        XCTAssertTrue(TabletBreakpoint.isTablet(1366.0))
        // An iPad mini in portrait is under it.
        XCTAssertFalse(TabletBreakpoint.isTablet(744.0))
        // Width alone: a short window stays a tablet.
        XCTAssertTrue(TabletBreakpoint.isTablet(1180.0))
    }

    @Test fun testLandscapeIsWiderThanTall() {
        XCTAssertTrue(TabletBreakpoint.isLandscape(Size(1180, 820)))
        XCTAssertFalse(TabletBreakpoint.isLandscape(Size(820, 1180)))
        XCTAssertFalse(TabletBreakpoint.isLandscape(Size(800, 800)))
    }

    @Test fun testDimensionsAt163PointsPerInch() {
        val phone = DeviceCalculations.dimensions(widthPoints = 390.0, heightPoints = 844.0)
        XCTAssertEqual(phone.widthInches, 2.393, accuracy = 0.001)
        XCTAssertEqual(phone.heightInches, 5.178, accuracy = 0.001)
        XCTAssertEqual(phone.diagonalInches, 5.704, accuracy = 0.01)
        val pro11 = DeviceCalculations.dimensions(widthPoints = 834.0, heightPoints = 1194.0)
        XCTAssertEqual(pro11.diagonalInches, 8.935, accuracy = 0.01)
        val custom = DeviceCalculations.dimensions(widthPoints = 1000.0, heightPoints = 2000.0, pointsPerInch = 96.0)
        XCTAssertEqual(custom.diagonalInches, 23.292, accuracy = 0.01)
    }

    @Test fun testOnlyIPadsOverNineInchesAreTablets() {
        XCTAssertEqual(DeviceCalculations.deviceType(diagonalInches = 8.3, isPad = true), DeviceType.Phone)
        XCTAssertEqual(DeviceCalculations.deviceType(diagonalInches = 12.9, isPad = true), DeviceType.Tablet)
        // Strictly over 9 on an iPad, from 9 on anything else.
        XCTAssertEqual(DeviceCalculations.deviceType(diagonalInches = 9.0, isPad = true), DeviceType.Phone)
        XCTAssertEqual(DeviceCalculations.deviceType(diagonalInches = 9.0, isPad = false), DeviceType.Tablet)
        XCTAssertEqual(DeviceCalculations.deviceType(diagonalInches = 6.7, isPad = false), DeviceType.Phone)
        XCTAssertEqual(DeviceCalculations.deviceType(diagonalInches = 8.0, isPad = false, tabletThresholdInches = 7.0), DeviceType.Tablet)
    }

    @Test fun testScreensByModel() {
        // iPhone 13 Pro Max, iPad mini 6, iPad 10th generation, iPad Pro
        // 11-inch: phones. iPad Pro 12.9-inch and 13-inch: tablets.
        XCTAssertEqual(DeviceCalculations.deviceType(screen = Size(428, 926), isPad = false), DeviceType.Phone)
        XCTAssertEqual(DeviceCalculations.dimensions(widthPoints = 744.0, heightPoints = 1133.0).diagonalInches, 8.316, accuracy = 0.01)
        XCTAssertEqual(DeviceCalculations.deviceType(screen = Size(744, 1133), isPad = true), DeviceType.Phone)
        XCTAssertEqual(DeviceCalculations.deviceType(screen = Size(820, 1180), isPad = true), DeviceType.Phone)
        XCTAssertEqual(DeviceCalculations.deviceType(screen = Size(834, 1194), isPad = true), DeviceType.Phone)
        XCTAssertEqual(DeviceCalculations.deviceType(screen = Size(1024, 1366), isPad = true), DeviceType.Tablet)
        XCTAssertEqual(DeviceCalculations.deviceType(screen = Size(1376, 1032), isPad = true), DeviceType.Tablet)
    }

    @Test fun testHeaderHeightGoesByTheDevice() {
        XCTAssertEqual(DeviceCalculations.headerHeight(isPad = false), 44)
        XCTAssertEqual(DeviceCalculations.headerHeight(isPad = true), 50)
        XCTAssertEqual(DeviceCalculations.headerHeight(isPad = true, isMacCatalyst = true), 56)
    }

    @Test fun testContentWidthLimit() {
        val window = Size(820, 1180)
        XCTAssertEqual(ContentWidthLimit.maxWidth(deviceType = DeviceType.Phone, window = window), 1180)
        XCTAssertEqual(ContentWidthLimit.headerMaxWidth(deviceType = DeviceType.Phone, window = window), 1180)
        XCTAssertEqual(ContentWidthLimit.maxWidth(deviceType = DeviceType.Tablet, window = window), 800)
        XCTAssertEqual(ContentWidthLimit.headerMaxWidth(deviceType = DeviceType.Tablet, window = window), 800)
        XCTAssertEqual(ContentWidthLimit.maxWidth(deviceType = DeviceType.Tablet, window = window, isMacCatalyst = true), 1400)
        XCTAssertEqual(ContentWidthLimit.headerMaxWidth(deviceType = DeviceType.Tablet, window = window, isMacCatalyst = true), Double.MAX_VALUE)
    }
}

/// SidebarNavigator.tsx `isDesktopLayout` and firstRunOnboarding.test.ts's
/// tablet cases.
class TabletShellRuleTest {
    @Test fun testTheShellStepsAsideForInstallAndScanning() {
        XCTAssertTrue(TabletShellRule.suppressesShell(isAuthenticated = true, isTablet = true, showsInstallStep = true, isOnboardingRoute = false))
        XCTAssertTrue(TabletShellRule.suppressesShell(isAuthenticated = true, isTablet = true, showsInstallStep = false, isOnboardingRoute = true))
        XCTAssertFalse(TabletShellRule.suppressesShell(isAuthenticated = true, isTablet = true, showsInstallStep = false, isOnboardingRoute = false))
        XCTAssertFalse(TabletShellRule.suppressesShell(isAuthenticated = true, isTablet = false, showsInstallStep = true, isOnboardingRoute = false))
    }

    @Test fun testTheShellNeedsAnAccountAndATabletWideWindow() {
        XCTAssertTrue(TabletShellRule.showsShell(isAuthenticated = true, isTablet = true, showsInstallStep = false, isOnboardingRoute = false))
        XCTAssertFalse(TabletShellRule.showsShell(isAuthenticated = false, isTablet = true, showsInstallStep = false, isOnboardingRoute = false))
        XCTAssertFalse(TabletShellRule.showsShell(isAuthenticated = true, isTablet = false, showsInstallStep = false, isOnboardingRoute = false))
        XCTAssertFalse(TabletShellRule.showsShell(isAuthenticated = true, isTablet = true, showsInstallStep = true, isOnboardingRoute = false))
        XCTAssertFalse(TabletShellRule.showsShell(isAuthenticated = true, isTablet = true, showsInstallStep = false, isOnboardingRoute = true))
    }
}

class TabletShellLayoutTest {
    /// An iPad Pro 12.9-inch in landscape.
    private val landscape = Size(1366, 1024)
    private val safeArea = LayoutInsets(top = 24.0, bottom = 20.0)

    private fun shell(size: Size, safeArea: LayoutInsets? = null, shows: Boolean = true, zen: Boolean = false): TabletShellLayout =
        TabletShellLayout(TabletShellLayout.Input(size = size, safeArea = safeArea ?: this.safeArea, showsShell = shows, zenMode = zen, headerHeight = 50.0))

    @Test fun testTheSidebarIsThirtyPercentHeldBetween250And360() {
        XCTAssertEqual(TabletShellLayout.sidebarWidth(windowWidth = 768.0), 250)
        XCTAssertEqual(TabletShellLayout.sidebarWidth(windowWidth = 834.0), 250)
        // 1024 * 0.3 = 307.2, floored.
        XCTAssertEqual(TabletShellLayout.sidebarWidth(windowWidth = 1024.0), 307)
        XCTAssertEqual(TabletShellLayout.sidebarWidth(windowWidth = 1180.0), 354)
        XCTAssertEqual(TabletShellLayout.sidebarWidth(windowWidth = 1366.0), 360)
    }

    @Test fun testAt768TheSidebarLeaves518ForContent() {
        val layout = shell(Size(768, 1024))
        assertFrame(layout.sidebar, rect(0, 0, 250, 1024))
        assertFrame(layout.detail, rect(250, 0, 518, 1024))
    }

    @Test fun testSidebarStackAndHeader() {
        val layout = shell(landscape)
        assertFrame(layout.sidebar, rect(0, 0, 360, 1024))
        assertFrame(layout.detail, rect(360, 0, 1006, 1024))
        // The status bar and the 50pt header row, across the whole window.
        assertFrame(layout.header, rect(0, 0, 1366, 74))
        // 28pt controls centred in the row: 24 + (50 - 28) / 2.
        assertFrame(layout.zenButton, rect(16, 35, 28, 28))
        assertFrame(layout.zenIcon, rect(21, 40, 18, 18))
        assertFrame(layout.backButton, rect(48, 35, 28, 28))
    }

    @Test fun testOnAnIPhoneHeaderTheControlsCentreIn44() {
        val layout = TabletShellLayout(TabletShellLayout.Input(size = Size(900, 600), safeArea = LayoutInsets.Zero, showsShell = true, zenMode = false, headerHeight = 44.0))
        assertFrame(layout.header, rect(0, 0, 900, 44))
        assertFrame(layout.zenButton, rect(16, 8, 28, 28))
    }

    @Test fun testZenModeCollapsesTheSidebarAndKeepsTheControls() {
        val layout = shell(landscape, zen = true)
        XCTAssertNil(layout.sidebar)
        assertFrame(layout.detail, rect(0, 0, 1366, 1024))
        assertFrame(layout.zenButton, rect(16, 35, 28, 28))
        assertFrame(layout.backButton, rect(48, 35, 28, 28))
    }

    @Test fun testWithoutTheShellTheStackTakesTheWindow() {
        val layout = shell(landscape, shows = false)
        XCTAssertNil(layout.sidebar)
        XCTAssertNil(layout.header)
        XCTAssertNil(layout.zenButton)
        XCTAssertNil(layout.backButton)
        assertFrame(layout.detail, rect(0, 0, 1366, 1024))
    }

    @Test fun testHorizontalSafeAreaPadsTheShell() {
        val layout = shell(landscape, safeArea = LayoutInsets(top = 24.0, left = 10.0, bottom = 20.0, right = 20.0))
        // The drawer still takes 30% of the whole window.
        assertFrame(layout.sidebar, rect(10, 0, 360, 1024))
        assertFrame(layout.detail, rect(370, 0, 976, 1024))
        assertFrame(layout.header, rect(10, 0, 1336, 74))
        assertFrame(layout.zenButton, rect(26, 35, 28, 28))
    }
}

/// SidebarView.tsx and EmptySessionsTablet.tsx.
class TabletSidebarLayoutTest {
    /// `line` is one line of the labels as drawn: 18 at the designed
    /// size, taller at a larger text size.
    private fun sidebar(archive: Boolean, line: Double = 18.0): TabletSidebarLayout =
        TabletSidebarLayout(TabletSidebarLayout.Input(
            size = Size(360, 1024),
            safeTop = 24.0,
            safeBottom = 20.0,
            headerHeight = 50.0,
            hairline = 0.5,
            newSessionIcon = Size(16, 16.5),
            newSessionTitle = text("New session", lineHeight = line),
            archiveIcon = if (archive) Size(18, 18.5) else null,
            settingsIcon = Size(18, 18.5),
            settingsTitle = text("Settings", lineHeight = line),
            spinner = Size(20, 20),
        ))

    @Test fun testTheControlsAndSettingsGrowWithTheirText() {
        // A larger text size: a 50pt line. The glyphs and paddings keep
        // their size, as RN's do.
        val layout = sidebar(archive = true, line = 50.0)
        // 0.5 + 10 + 50 + 10 + 0.5 is past the 40pt archive button, which
        // centres in the row now.
        assertFrame(layout.newSessionButton, rect(16.5, 82.5, 279, 71))
        assertFrame(layout.newSessionIcon, rect(31, 109.75, 16, 16.5))
        assertFrame(layout.newSessionTitle, rect(55, 93, 88, 50))
        assertFrame(layout.archiveButton, rect(303.5, 98, 40, 40))
        // Hairline + 14 + 50 + 14.
        assertFrame(layout.settingsRow, rect(0.5, 945, 359, 78.5))
        assertFrame(layout.settingsIcon, rect(16.5, 975.25, 18, 18.5))
        assertFrame(layout.settingsTitle, rect(44.5, 959.5, 64, 50))
        // The list takes what is left between them.
        assertFrame(layout.content, rect(0.5, 157.5, 359, 787.5))
    }

    @Test fun testControlsRowWithTheArchiveButton() {
        val layout = sidebar(archive = true)
        // Under the border, the status bar, the header and 8: 0.5 + 24 +
        // 50 + 8. The 40pt archive button sets the row; the 39pt button
        // (hairlines, 10 + 18 + 10) centres in it.
        assertFrame(layout.newSessionButton, rect(16.5, 83, 279, 39))
        assertFrame(layout.newSessionIcon, rect(31, 94.25, 16, 16.5))
        assertFrame(layout.newSessionTitle, rect(55, 93.5, 88, 18))
        assertFrame(layout.archiveButton, rect(303.5, 82.5, 40, 40))
        assertFrame(layout.archiveIcon, rect(314.5, 93.25, 18, 18.5))
    }

    @Test fun testWithoutAnArchiveNewSessionTakesTheRow() {
        val layout = sidebar(archive = false)
        XCTAssertNil(layout.archiveButton)
        XCTAssertNil(layout.archiveIcon)
        assertFrame(layout.newSessionButton, rect(16.5, 82.5, 327, 39))
        // The list starts 4 under the 39pt row.
        assertFrame(layout.content, rect(0.5, 125.5, 359, 851))
    }

    @Test fun testListBetweenTheControlsAndSettings() {
        val layout = sidebar(archive = true)
        assertFrame(layout.content, rect(0.5, 126.5, 359, 850))
        XCTAssertEqual(layout.listBottomInset, 148)
        assertFrame(layout.spinner, rect(170, 541.5, 20, 20))
    }

    @Test fun testSettingsRowSitsOnTheBottomEdge() {
        val layout = sidebar(archive = true)
        // Hairline + 14 + 18.5 + 14, inside the bottom border.
        assertFrame(layout.settingsRow, rect(0.5, 976.5, 359, 47))
        assertFrame(layout.settingsDivider, rect(0.5, 976.5, 359, 0.5))
        assertFrame(layout.settingsIcon, rect(16.5, 991, 18, 18.5))
        assertFrame(layout.settingsTitle, rect(44.5, 991.25, 64, 18))
    }

    private fun empty(width: Double, button: Boolean): TabletEmptySessionsLayout =
        TabletEmptySessionsLayout(TabletEmptySessionsLayout.Input(
            size = Size(width, 850),
            icon = Size(64, 66),
            title = text("No sessions yet", lineHeight = 26.0),
            description = text("Start a new session on any of your connected machines.", lineHeight = 22.0),
            button = if (button) TabletEmptySessionsLayout.Button(icon = Size(20, 21), title = text("Start New Session", lineHeight = 22.0)) else null,
        ))

    @Test fun testEmptySessionsCentresItsColumn() {
        val layout = empty(width = 359.0, button = true)
        // 66 + 24 + 26 + 8 + 44 + 24 + 46 = 238, centred in 850.
        assertFrame(layout.icon, rect(147.5, 306, 64, 66))
        assertFrame(layout.title, rect(119.5, 396, 120, 26))
        // 263 wide after 48 a side: two lines.
        assertFrame(layout.description, rect(51.5, 430, 256, 44))
        assertFrame(layout.button, rect(73.5, 498, 212, 46))
        assertFrame(layout.buttonIcon, rect(97.5, 510.5, 20, 21))
        assertFrame(layout.buttonTitle, rect(125.5, 510, 136, 22))
    }

    @Test fun testANarrowSidebarWrapsTheButtonsLabel() {
        // A 250pt sidebar: 153 for the column leaves 77 for the label, two
        // lines of 9, so the button stays inside the column.
        val layout = empty(width = 249.0, button = true)
        assertFrame(layout.icon, rect(92.5, 284, 64, 66))
        assertFrame(layout.title, rect(64.5, 374, 120, 26))
        assertFrame(layout.description, rect(48.5, 408, 152, 66))
        assertFrame(layout.button, rect(50.5, 498, 148, 68))
        assertFrame(layout.buttonIcon, rect(74.5, 521.5, 20, 21))
        assertFrame(layout.buttonTitle, rect(102.5, 510, 72, 44))
    }

    @Test fun testWithoutAComputerThereIsNoButton() {
        val layout = empty(width = 359.0, button = false)
        XCTAssertNil(layout.button)
        XCTAssertNil(layout.buttonIcon)
        XCTAssertNil(layout.buttonTitle)
        // 192 tall, centred.
        assertFrame(layout.icon, rect(147.5, 329, 64, 66))
    }
}

/// The tablet branches inside other screens (ChatHeaderView.tsx,
/// SessionView.tsx, PermissionFooter.tsx, WorktreeTabStrip.tsx,
/// navigation/Header.tsx).
class TabletBranchesTest {
    @Test fun testATabletChatHasAPlainHeaderAndADockedComposer() {
        val tablet = TabletBranches(isTablet = true, isLandscape = false, headerHeight = 50.0)
        XCTAssertFalse(tablet.chatShowsBackButton)
        XCTAssertFalse(tablet.chatHeaderGlass)
        XCTAssertEqual(tablet.chatHeaderContentHeight, 50)
        XCTAssertFalse(tablet.chatShowsAvatar)
        XCTAssertFalse(tablet.chatGlassBackdrop)
        XCTAssertFalse(tablet.chatHidesHeaderInLandscape)
        XCTAssertFalse(tablet.chatShowsLandscapeBackButton)
        XCTAssertFalse(tablet.chatContentRunsUnderHeader)
        XCTAssertEqual(tablet.chatContentTop(safeTop = 24.0, tabStrip = 0.0), 74)
        XCTAssertEqual(tablet.chatContentTop(safeTop = 24.0, tabStrip = 40.0), 114)
        XCTAssertFalse(tablet.chatFloatingDock)
        XCTAssertEqual(tablet.chatInputPaddingH, 12)
        XCTAssertEqual(tablet.chatOldestEndSpacer(safeTop = 24.0, headerAccessory = 40.0), 106)
        XCTAssertTrue(tablet.chatShowsCliWarning)
        XCTAssertTrue(tablet.permissionButtonsTrailing)
        XCTAssertFalse(tablet.worktreeStripGlass)
        XCTAssertFalse(tablet.headerGlassControls)
        XCTAssertFalse(tablet.plainHeaderShowsBackButton(isOnboardingRoute = false))
        XCTAssertTrue(tablet.plainHeaderShowsBackButton(isOnboardingRoute = true))
    }

    @Test fun testATabletOnItsSideKeepsItsHeader() {
        val tablet = TabletBranches(isTablet = true, isLandscape = true, headerHeight = 50.0)
        XCTAssertFalse(tablet.chatHidesHeaderInLandscape)
        XCTAssertEqual(tablet.chatContentTop(safeTop = 24.0, tabStrip = 0.0), 74)
        XCTAssertTrue(tablet.chatShowsCliWarning)
    }

    @Test fun testAPortraitPhone() {
        val phone = TabletBranches(isTablet = false, isLandscape = false, headerHeight = 44.0)
        XCTAssertTrue(phone.chatShowsBackButton)
        XCTAssertTrue(phone.chatHeaderGlass)
        XCTAssertEqual(phone.chatHeaderContentHeight, 52)
        XCTAssertTrue(phone.chatShowsAvatar)
        XCTAssertTrue(phone.chatContentRunsUnderHeader)
        XCTAssertEqual(phone.chatContentTop(safeTop = 59.0, tabStrip = 40.0), 0)
        XCTAssertTrue(phone.chatFloatingDock)
        XCTAssertEqual(phone.chatInputPaddingH, 8)
        XCTAssertEqual(phone.chatOldestEndSpacer(safeTop = 59.0, headerAccessory = 40.0), 163)
        XCTAssertFalse(phone.permissionButtonsTrailing)
        XCTAssertTrue(phone.worktreeStripGlass)
        XCTAssertTrue(phone.plainHeaderShowsBackButton(isOnboardingRoute = false))
    }

    @Test fun testALandscapePhoneDropsTheHeader() {
        val phone = TabletBranches(isTablet = false, isLandscape = true, headerHeight = 44.0)
        XCTAssertTrue(phone.chatHidesHeaderInLandscape)
        XCTAssertTrue(phone.chatShowsLandscapeBackButton)
        XCTAssertFalse(phone.chatContentRunsUnderHeader)
        XCTAssertEqual(phone.chatContentTop(safeTop = 0.0, tabStrip = 0.0), 0)
        XCTAssertFalse(phone.chatFloatingDock)
        XCTAssertEqual(phone.chatOldestEndSpacer(safeTop = 0.0, headerAccessory = 0.0), 12)
        XCTAssertFalse(phone.chatShowsCliWarning)
    }

    @Test fun testANarrowIPadWindowIsAPhoneWithAnIPadHeader() {
        val window = TabletBranches(isTablet = false, isLandscape = false, headerHeight = 50.0)
        XCTAssertEqual(window.chatHeaderContentHeight, 52)
        XCTAssertTrue(window.chatShowsBackButton)
    }

    @Test fun testTabletPermissionButtonsHugTheirLabelsAtTheTrailingEdge() {
        val labels = listOf(text("Yes", lineHeight = 18.0), text("a".repeat(60), lineHeight = 18.0))
        val phone = PermissionFooterLayout(width = 400.0, labels = labels)
        assertFrame(phone.buttons[0], rect(6, 4, 388, 34))
        val tablet = PermissionFooterLayout.tablet(width = 400.0, labels = labels)
        assertFrame(tablet.buttons[0], rect(348, 4, 46, 34))
        assertFrame(tablet.labels[0], rect(359, 12, 24, 18))
        // Two lines of 45 at 366: 360 wide, 22 of padding and border.
        assertFrame(tablet.buttons[1], rect(12, 45, 382, 52))
        assertFrame(tablet.labels[1], rect(23, 53, 360, 36))
        assertSize(tablet.size, phone.size)
        assertSize(tablet.size, Size(400, 105))
    }
}
