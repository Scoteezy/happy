package com.slopus.happy.layout

import com.slopus.happy.layout.chat.PermissionFooterLayout
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// The tablet shell (happy-ios HappyLayout/Tablet): utils/responsive.ts,
// utils/deviceCalculations.ts, components/layout.ts, SidebarNavigator.tsx,
// SidebarView.tsx and EmptySessionsTablet.tsx, iOS values.

/**
 * When happy-app draws its tablet layout. Two different questions get two
 * different answers there, and both are kept:
 *   - [isTablet] (`useIsTablet`) is a rule about the window, not the device.
 *     A split-screen window can fall below it and get the phone layout;
 *     widening the window brings the sidebar back. A large phone on its side
 *     passes it, as a large iPhone does.
 *   - [DeviceCalculations.deviceType] (`getDeviceType`) is about the screen.
 *     It only feeds `layout.maxWidth`, and it calls most tablets phones.
 */
object TabletBreakpoint {
    /** "At 768 logical pixels the sidebar is 250px and leaves 518px for content." */
    const val minWidth = 768.0

    /** Width alone, never height, so a keyboard that shrinks the window never collapses the sidebar. */
    fun isTablet(windowWidth: Double) = windowWidth >= minWidth

    /** `useIsLandscape`. */
    fun isLandscape(window: Size) = window.width > window.height
}

object DeviceCalculations {
    /** iOS points per inch (`calculateDeviceDimensions`'s 163). */
    const val pointsPerInch = 163.0
    /** `determineDeviceType`'s default threshold. */
    const val tabletThresholdInches = 9.0

    data class Dimensions(val widthInches: Double, val heightInches: Double, val diagonalInches: Double)

    enum class DeviceType { Phone, Tablet }

    /** `calculateDeviceDimensions`. */
    fun dimensions(widthPoints: Double, heightPoints: Double, pointsPerInch: Double = DeviceCalculations.pointsPerInch): Dimensions {
        val width = widthPoints / pointsPerInch
        val height = heightPoints / pointsPerInch
        return Dimensions(width, height, sqrt(width * width + height * height))
    }

    /**
     * `determineDeviceType` on iOS: an iPad is a tablet only past 9 inches
     * (strictly); anything else is a tablet from 9 inches up.
     */
    fun deviceType(diagonalInches: Double, isPad: Boolean, tabletThresholdInches: Double = DeviceCalculations.tabletThresholdInches): DeviceType {
        if (isPad) return if (diagonalInches > 9) DeviceType.Tablet else DeviceType.Phone
        return if (diagonalInches >= tabletThresholdInches) DeviceType.Tablet else DeviceType.Phone
    }

    /** `getDeviceType()`: the screen in points (dp here), at 163 per inch. */
    fun deviceType(screen: Size, isPad: Boolean) =
        deviceType(dimensions(screen.width, screen.height).diagonalInches, isPad)

    /**
     * `calculateHeaderHeight` on iOS: a tablet 50, a phone 44. It goes by the
     * device, so a tablet's headers stay 50 even in a window narrow enough
     * for the phone layout.
     */
    fun headerHeight(isPad: Boolean, isMacCatalyst: Boolean = false): Double = when {
        isMacCatalyst -> 56.0
        isPad -> 50.0
        else -> 44.0
    }
}

/**
 * components/layout.ts, iOS: how wide content may grow. Fixed from the screen
 * and the window of the moment it is first read.
 */
object ContentWidthLimit {
    /** Tablets (by [DeviceCalculations.deviceType]) and the web. */
    const val tablet = 800.0
    /** `getMaxLayoutWidth` on Mac Catalyst. */
    const val macLayout = 1400.0

    /** `layout.maxWidth`: a phone's longest window side (so, in effect, no limit), 800 on a tablet. */
    fun maxWidth(deviceType: DeviceCalculations.DeviceType, window: Size, isMacCatalyst: Boolean = false): Double {
        if (deviceType == DeviceCalculations.DeviceType.Phone) return max(window.width, window.height)
        return if (isMacCatalyst) macLayout else tablet
    }

    /** `layout.headerMaxWidth`: the same, unlimited on Mac Catalyst. */
    fun headerMaxWidth(deviceType: DeviceCalculations.DeviceType, window: Size, isMacCatalyst: Boolean = false): Double {
        if (deviceType == DeviceCalculations.DeviceType.Phone) return max(window.width, window.height)
        return if (isMacCatalyst) Double.MAX_VALUE else tablet
    }
}

/**
 * When the window gets the tablet shell: SidebarNavigator.tsx
 * `isDesktopLayout`, with firstRunOnboarding.ts `shouldSuppressTabletShell`.
 */
object TabletShellRule {
    /**
     * The first-run screens own the whole canvas, so the sidebar and its
     * header stay out of them, including the moment between a computer
     * arriving and the onboarding route closing.
     */
    fun suppressesShell(isAuthenticated: Boolean, isTablet: Boolean, showsInstallStep: Boolean, isOnboardingRoute: Boolean) =
        isAuthenticated && isTablet && (showsInstallStep || isOnboardingRoute)

    /** Signed in, a tablet-wide window, and not on the first-run screens. */
    fun showsShell(isAuthenticated: Boolean, isTablet: Boolean, showsInstallStep: Boolean, isOnboardingRoute: Boolean) =
        isAuthenticated && isTablet && !suppressesShell(isAuthenticated, isTablet, showsInstallStep, isOnboardingRoute)
}

/**
 * The window with the tablet shell (SidebarNavigator.tsx): a permanent
 * drawer holding the sidebar, the screen stack beside it, and the
 * persistent header floating over both.
 *
 *     |16|(zen)|4|(‹)            …
 *     +-----------+---------------------------
 *     |  sidebar  |  detail stack
 *     | 30% of the|
 *     | window,   |
 *     | 250…360   |
 *
 *   - The sidebar is 30% of the window, floored, then held between 250 and
 *     360. Zen mode collapses it to nothing, without animating.
 *   - The header row is the navigation header's height under the status
 *     bar. Its two 28 controls sit 16 from the shell's left edge, 4 apart,
 *     centred in the row; they stay put in zen mode, over the stack.
 *   - Without the shell the stack takes the whole window.
 *
 * The window's left and right safe-area insets pad the shell
 * (`HorizontalSafeAreaWrapper`).
 */
data class TabletShellLayout(
    /** The drawer; null without the shell and in zen mode. */
    val sidebar: Rect?,
    /** The screen stack. */
    val detail: Rect,
    /** The persistent header's box; null without the shell. */
    val header: Rect?,
    val zenButton: Rect?,
    val zenIcon: Rect?,
    val backButton: Rect?,
) {
    object Metrics {
        const val sidebarFraction = 0.3
        const val sidebarMinWidth = 250.0
        const val sidebarMaxWidth = 360.0
        /** PersistentHeader's `paddingLeft`. */
        const val headerPaddingLeft = 16.0
        const val controlSize = 28.0
        const val controlGap = 4.0
        /** zen-icon.png's 18 box inside the zen control. */
        const val zenIconSize = 18.0
        /** The back chevron's Ionicons size. */
        const val backIconSize = 20.0
        /** Both controls' `hitSlop`. */
        const val hitSlop = 10.0
        /** The back control's opacity while there is nothing to go back to. */
        const val disabledOpacity = 0.3
    }

    class Input(
        /** The window. */
        val size: Size,
        val safeArea: LayoutInsets,
        /** [TabletShellRule.showsShell]. */
        val showsShell: Boolean,
        /** `localSettings.zenMode`. */
        val zenMode: Boolean,
        /** [DeviceCalculations.headerHeight]. */
        val headerHeight: Double,
    )

    companion object {
        /** `fullDrawerWidth` on a tablet-wide window. */
        fun sidebarWidth(windowWidth: Double): Double =
            min(max(floor(windowWidth * Metrics.sidebarFraction), Metrics.sidebarMinWidth), Metrics.sidebarMaxWidth)

        operator fun invoke(input: Input): TabletShellLayout {
            val m = Metrics
            val width = max(0.0, input.size.width)
            val height = max(0.0, input.size.height)
            if (!input.showsShell) return TabletShellLayout(null, Rect(0.0, 0.0, width, height), null, null, null, null)
            val left = input.safeArea.left
            val inner = max(0.0, width - input.safeArea.horizontal)
            val drawer = if (input.zenMode) 0.0 else min(inner, sidebarWidth(width))
            val sidebar = if (drawer > 0) Rect(left, 0.0, drawer, height) else null
            val detail = Rect(left + drawer, 0.0, inner - drawer, height)

            val top = input.safeArea.top
            val header = Rect(left, 0.0, inner, top + input.headerHeight)
            val controlY = top + (input.headerHeight - m.controlSize) / 2
            val zen = Rect(left + m.headerPaddingLeft, controlY, m.controlSize, m.controlSize)
            val iconInset = (m.controlSize - m.zenIconSize) / 2
            val zenIcon = Rect(zen.minX + iconInset, zen.minY + iconInset, m.zenIconSize, m.zenIconSize)
            val back = Rect(zen.maxX + m.controlGap, controlY, m.controlSize, m.controlSize)
            return TabletShellLayout(sidebar, detail, header, zen, zenIcon, back)
        }
    }
}

/**
 * The sidebar (SidebarView.tsx): one column inside a hairline border, on the
 * grouped background.
 *
 *     (status bar + header row, where the persistent header's controls are)
 *     |16| [✎ New session              ] |8| [▣] |16|     8 above, 4 below
 *     +--------------------------------------------+
 *     |  the session list, or its empty state      |
 *     +--------------------------------------------+
 *     |16| ⚙ |10| Settings                          |     hairline on top
 *
 *   - The border takes its hairline inside the box, as React Native's borders
 *     do, so everything sits one hairline in.
 *   - New session stretches; its hairline frame, rounded 10, holds a 16 glyph
 *     and the label, padded 10 by 14, 8 apart.
 *   - The 40 archive button shows only when there is an archive.
 *   - The two share a row and centre in it.
 *   - The settings row sits on the sidebar's bottom edge. Nothing pads it
 *     above the navigation bar, as in happy-app.
 */
data class TabletSidebarLayout(
    val newSessionButton: Rect,
    val newSessionIcon: Rect,
    val newSessionTitle: Rect,
    val archiveButton: Rect?,
    val archiveIcon: Rect?,
    /** The list, or what stands in for it (MainView.tsx's sidebar variant). */
    val content: Rect,
    /** The list's bottom padding: `safeArea.bottom + 128`. It has none on top. */
    val listBottomInset: Double,
    /** The loading spinner, centred in [content]. */
    val spinner: Rect,
    val settingsRow: Rect,
    /** The row's top border. */
    val settingsDivider: Rect,
    val settingsIcon: Rect,
    val settingsTitle: Rect,
) {
    object Metrics {
        const val controlsMarginHorizontal = 16.0
        const val controlsMarginTop = 8.0
        const val controlsMarginBottom = 4.0
        const val controlsGap = 8.0
        const val newSessionPaddingVertical = 10.0
        const val newSessionPaddingHorizontal = 14.0
        const val newSessionGap = 8.0
        const val newSessionIconSize = 16.0
        const val buttonRadius = 10.0
        const val archiveButtonSize = 40.0
        const val archiveIconSize = 18.0
        const val settingsPaddingHorizontal = 16.0
        const val settingsPaddingVertical = 14.0
        const val settingsGap = 10.0
        const val settingsIconSize = 18.0
        /** SessionsList.tsx's default `bottomContentInset`, over the navigation bar's inset. */
        const val listBottomInset = 128.0
    }

    class Input(
        val size: Size,
        val safeTop: Double,
        val safeBottom: Double,
        /** [DeviceCalculations.headerHeight]. */
        val headerHeight: Double,
        /** One device pixel (`StyleSheet.hairlineWidth`). */
        val hairline: Double,
        /** create-outline at 16: its RN box. */
        val newSessionIcon: Size,
        val newSessionTitle: Measurable,
        /** The archive glyph's box at 18; null when there is no archive and so no button. */
        val archiveIcon: Size?,
        /** settings-outline at 18. */
        val settingsIcon: Size,
        val settingsTitle: Measurable,
        /** The small activity indicator. */
        val spinner: Size,
    )

    companion object {
        operator fun invoke(input: Input): TabletSidebarLayout {
            val m = Metrics
            val hairline = input.hairline
            val width = max(0.0, input.size.width)
            val height = max(0.0, input.size.height)
            val innerWidth = max(0.0, width - 2 * hairline)

            // The controls row.
            val rowX = hairline + m.controlsMarginHorizontal
            val rowWidth = max(0.0, innerWidth - 2 * m.controlsMarginHorizontal)
            val rowY = hairline + input.safeTop + input.headerHeight + m.controlsMarginTop
            val archiveWidth = if (input.archiveIcon == null) 0.0 else m.archiveButtonSize + m.controlsGap
            val buttonWidth = max(0.0, rowWidth - archiveWidth)
            val buttonInner = max(0.0, buttonWidth - 2 * hairline - 2 * m.newSessionPaddingHorizontal)
            val icon = input.newSessionIcon
            val title = input.newSessionTitle.size(max(0.0, buttonInner - icon.width - m.newSessionGap))
            val buttonContent = max(icon.height, title.height)
            val buttonHeight = 2 * hairline + 2 * m.newSessionPaddingVertical + buttonContent
            val rowHeight = max(buttonHeight, if (input.archiveIcon == null) 0.0 else m.archiveButtonSize)

            val newSessionButton = Rect(rowX, rowY + (rowHeight - buttonHeight) / 2, buttonWidth, buttonHeight)
            val buttonContentY = newSessionButton.minY + hairline + m.newSessionPaddingVertical
            val newSessionIcon = Rect(
                newSessionButton.minX + hairline + m.newSessionPaddingHorizontal,
                buttonContentY + (buttonContent - icon.height) / 2,
                icon.width,
                icon.height,
            )
            val newSessionTitle = Rect(newSessionIcon.maxX + m.newSessionGap, buttonContentY + (buttonContent - title.height) / 2, title.width, title.height)
            var archiveButton: Rect? = null
            var archiveIcon: Rect? = null
            input.archiveIcon?.let { archive ->
                val button = Rect(rowX + rowWidth - m.archiveButtonSize, rowY + (rowHeight - m.archiveButtonSize) / 2, m.archiveButtonSize, m.archiveButtonSize)
                archiveButton = button
                archiveIcon = Rect(
                    button.minX + (m.archiveButtonSize - archive.width) / 2,
                    button.minY + (m.archiveButtonSize - archive.height) / 2,
                    archive.width,
                    archive.height,
                )
            }

            // The settings row, on the bottom edge.
            val settingsIcon = input.settingsIcon
            val settingsText = input.settingsTitle.size(max(0.0, innerWidth - 2 * m.settingsPaddingHorizontal - settingsIcon.width - m.settingsGap))
            val settingsContent = max(settingsIcon.height, settingsText.height)
            val settingsHeight = hairline + 2 * m.settingsPaddingVertical + settingsContent
            val settingsRow = Rect(hairline, height - hairline - settingsHeight, innerWidth, settingsHeight)
            val settingsDivider = Rect(settingsRow.minX, settingsRow.minY, innerWidth, hairline)
            val settingsContentY = settingsRow.minY + hairline + m.settingsPaddingVertical
            val settingsIconFrame = Rect(
                settingsRow.minX + m.settingsPaddingHorizontal,
                settingsContentY + (settingsContent - settingsIcon.height) / 2,
                settingsIcon.width,
                settingsIcon.height,
            )
            val settingsTitle = Rect(
                settingsIconFrame.maxX + m.settingsGap,
                settingsContentY + (settingsContent - settingsText.height) / 2,
                settingsText.width,
                settingsText.height,
            )

            val contentY = rowY + rowHeight + m.controlsMarginBottom
            val content = Rect(hairline, contentY, innerWidth, max(0.0, settingsRow.minY - contentY))
            val spinner = Rect(content.midX - input.spinner.width / 2, content.midY - input.spinner.height / 2, input.spinner.width, input.spinner.height)
            return TabletSidebarLayout(
                newSessionButton, newSessionIcon, newSessionTitle, archiveButton, archiveIcon, content,
                input.safeBottom + m.listBottomInset, spinner, settingsRow, settingsDivider, settingsIconFrame, settingsTitle,
            )
        }
    }
}

/**
 * EmptySessionsTablet.tsx: the sidebar's stand-in for a list with nothing in
 * it. One centred column, 48 in from each side:
 *
 *         (64 glyph)              24
 *     No sessions yet             20, 8
 *     Start a new session on …    16, 24
 *     [ + Start New Session ]     only with a reachable or offline computer
 *
 * The button hugs its glyph and label (padding 12 by 24, 8 between) but
 * never outgrows the column; the label wraps first.
 */
data class TabletEmptySessionsLayout(
    val icon: Rect,
    val title: Rect,
    val description: Rect,
    val button: Rect?,
    val buttonIcon: Rect?,
    val buttonTitle: Rect?,
) {
    object Metrics {
        const val paddingHorizontal = 48.0
        const val iconSize = 64.0
        const val iconMarginBottom = 24.0
        const val titleMarginBottom = 8.0
        const val descriptionMarginBottom = 24.0
        const val buttonPaddingHorizontal = 24.0
        const val buttonPaddingVertical = 12.0
        const val buttonRadius = 12.0
        const val buttonIconSize = 20.0
        const val buttonIconGap = 8.0
    }

    class Button(
        /** The glyph's RN box at 20. */
        val icon: Size,
        val title: Measurable,
    )

    class Input(
        val size: Size,
        /** The 64 glyph's RN box. */
        val icon: Size,
        val title: Measurable,
        val description: Measurable,
        val button: Button?,
    )

    companion object {
        operator fun invoke(input: Input): TabletEmptySessionsLayout {
            val m = Metrics
            val width = max(0.0, input.size.width)
            val column = max(0.0, width - 2 * m.paddingHorizontal)
            val titleSize = input.title.size(column)
            val descriptionSize = input.description.size(column)

            var buttonSize: Size? = null
            var buttonText = Size.Zero
            var buttonContent = 0.0
            input.button?.let { button ->
                buttonText = button.title.size(max(0.0, column - 2 * m.buttonPaddingHorizontal - button.icon.width - m.buttonIconGap))
                buttonContent = max(button.icon.height, buttonText.height)
                buttonSize = Size(
                    min(column, 2 * m.buttonPaddingHorizontal + button.icon.width + m.buttonIconGap + buttonText.width),
                    2 * m.buttonPaddingVertical + buttonContent,
                )
            }

            val total = input.icon.height + m.iconMarginBottom + titleSize.height + m.titleMarginBottom +
                descriptionSize.height + m.descriptionMarginBottom + (buttonSize?.height ?: 0.0)
            var y = (input.size.height - total) / 2
            val icon = Rect.centered(input.icon, width, y = y)
            y += input.icon.height + m.iconMarginBottom
            val title = Rect.centered(titleSize, width, y = y)
            y += titleSize.height + m.titleMarginBottom
            val description = Rect.centered(descriptionSize, width, y = y)
            y += descriptionSize.height + m.descriptionMarginBottom

            val size = buttonSize
            val glyph = input.button?.icon
            if (size == null || glyph == null) return TabletEmptySessionsLayout(icon, title, description, null, null, null)
            val frame = Rect.centered(size, width, y = y)
            val contentY = frame.minY + m.buttonPaddingVertical
            val iconFrame = Rect(frame.minX + m.buttonPaddingHorizontal, contentY + (buttonContent - glyph.height) / 2, glyph.width, glyph.height)
            val titleFrame = Rect(iconFrame.maxX + m.buttonIconGap, contentY + (buttonContent - buttonText.height) / 2, buttonText.width, buttonText.height)
            return TabletEmptySessionsLayout(icon, title, description, frame, iconFrame, titleFrame)
        }
    }
}

/**
 * The tablet branches inside screens other parts own: every `useIsTablet()` /
 * `useHeaderHeight()` decision in happy-app's native iOS code paths, as
 * values the owning screen reads. Each answer names the line it ports.
 */
data class TabletBranches(
    /** `useIsTablet()`: the window is at least 768 wide. */
    val isTablet: Boolean,
    /** `useIsLandscape()`. */
    val isLandscape: Boolean,
    /** `useHeaderHeight()`: 50 on a tablet, 44 on a phone. */
    val headerHeight: Double,
) {
    object Metrics {
        /** MOBILE_GLASS_HEADER_HEIGHT: the phone's glass header row. */
        const val glassHeaderHeight = 52.0
        /** SessionView.tsx `sessionInputHorizontalPadding`. */
        const val tabletInputPaddingH = 12.0
        const val phoneInputPaddingH = 8.0
        /** ChatList.tsx `OlderEnd`: past the header when no inset is given. */
        const val olderEndExtra = 32.0
    }

    // Chat header (ChatHeaderView.tsx)

    /** :59 `showBackButton`: the persistent header's back serves a tablet. */
    val chatShowsBackButton get() = !isTablet

    /**
     * :61 `glassEnabled`. Without glass the header is an opaque bar
     * (`header.background`), the title and subtitle sit at its start rather
     * than in a centred capsule, the subtitle is `textSecondary`, and there is
     * no scrim.
     */
    val chatHeaderGlass get() = !isTablet

    /** :62 `contentHeight`: the glass row is at least 52. */
    val chatHeaderContentHeight get() = if (chatHeaderGlass) max(headerHeight, Metrics.glassHeaderHeight) else headerHeight

    // Session screen (-session/SessionView.tsx)

    /** :426 `headerRight`: the avatar that opens session info. Tablets reach info through the title. */
    val chatShowsAvatar get() = !isTablet

    /** :449 `MobileGlassBackdrop`. */
    val chatGlassBackdrop get() = !isTablet

    /** :131 `hidesLandscapeHeader`: a phone on its side drops the header for a floating back button; a tablet never. */
    val chatHidesHeaderInLandscape get() = isLandscape && !isTablet

    /** :1327 the floating back button that stands in for the header. */
    val chatShowsLandscapeBackButton get() = chatHidesHeaderInLandscape

    /** :132 `contentRunsUnderHeader`. When false the content starts under the header. */
    val chatContentRunsUnderHeader get() = !isTablet && !isLandscape

    /** The content's top padding when it does not run under the header and the header shows (:474-478). */
    fun chatContentTop(safeTop: Double, tabStrip: Double): Double {
        if (chatHidesHeaderInLandscape || chatContentRunsUnderHeader) return 0.0
        val header = if (isTablet) headerHeight else max(headerHeight, Metrics.glassHeaderHeight)
        return safeTop + header + tabStrip
    }

    /** :781 `usesFloatingMobileDock`: only the portrait phone floats the composer over the list. */
    val chatFloatingDock get() = !isTablet && !isLandscape

    /** :833 `sessionInputHorizontalPadding`. */
    val chatInputPaddingH get() = if (isTablet) Metrics.tabletInputPaddingH else Metrics.phoneInputPaddingH

    /**
     * :834 `chatListTopContentInset`, as the list's oldest-end spacer. A
     * tablet passes none, so ChatList.tsx's `OlderEnd` falls back to
     * `headerHeight + safeArea.top + 32` (:224), on top of the content already
     * starting under the header.
     */
    fun chatOldestEndSpacer(safeTop: Double, headerAccessory: Double): Double = when {
        isLandscape && !isTablet -> 12.0
        !isTablet -> safeTop + Metrics.glassHeaderHeight + headerAccessory + 12
        else -> headerHeight + safeTop + Metrics.olderEndExtra
    }

    /** :1272 the CLI-outdated pill, hidden only on a landscape phone. */
    val chatShowsCliWarning get() = !(isLandscape && !isTablet)

    // Tools (tools/PermissionFooter.tsx)

    /** :308 `alignItems`: a tablet hugs each button to its label at the trailing edge; a phone stretches them. */
    val permissionButtonsTrailing get() = isTablet

    // Worktree tabs (WorktreeTabStrip.tsx)

    /** :95 `glassEnabled`: the strip's chips are glass on a phone only. */
    val worktreeStripGlass get() = !isTablet

    // Navigation header (navigation/Header.tsx)

    /** :80 `isNativePhone`: glass controls, a scrim and a centred title are phone-only. */
    val headerGlassControls get() = !isTablet

    /**
     * :322 `shouldHideBackButton`: the plain headers (settings, session info,
     * machine, What's New) drop their back button on a tablet, except on the
     * onboarding routes, which have no shell.
     */
    fun plainHeaderShowsBackButton(isOnboardingRoute: Boolean) = !isTablet || isOnboardingRoute
}

/**
 * PermissionFooter.tsx on a tablet (:308, `alignItems: 'flex-end'`): each
 * button as wide as its label and padding, never wider than the card, against
 * the trailing edge; heights as on a phone.
 */
fun PermissionFooterLayout.Companion.tablet(width: Double, labels: List<Measurable>, maxListHeight: Double = Double.MAX_VALUE): PermissionFooterLayout {
    val m = PermissionFooterLayout.Metrics
    val layout = PermissionFooterLayout(width, labels, maxListHeight)
    val right = max(0.0, width) - m.paddingH
    val inset = m.buttonPaddingH + m.buttonBorder
    val buttons = layout.buttons.toMutableList()
    val frames = layout.labels.toMutableList()
    for (index in buttons.indices) {
        val label = frames[index]
        val button = buttons[index]
        val hugged = min(button.width, label.width + 2 * inset)
        buttons[index] = Rect(right - hugged, button.minY, hugged, button.height)
        frames[index] = Rect(right - hugged + (hugged - label.width) / 2, label.minY, label.width, label.height)
    }
    return layout.copy(buttons = buttons, labels = frames)
}
