package com.slopus.happy.ui.tablet

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Layout
import android.view.View
import android.widget.ProgressBar
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.home.HomeServices
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.HomeContentInsets
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.SecondaryPalette
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TabletEmptySessionsLayout
import com.slopus.happy.layout.TabletSidebarLayout
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.sidebarNewSession
import com.slopus.happy.layout.sidebarSettings
import com.slopus.happy.layout.surfacePressed
import com.slopus.happy.layout.tabletEmptyButton
import com.slopus.happy.layout.tabletEmptyDescription
import com.slopus.happy.layout.tabletEmptyTitle
import com.slopus.happy.model.TabletEmptySessions
import com.slopus.happy.model.TabletSidebarState
import com.slopus.happy.ui.components.AvatarStyle
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.home.AvatarSettings
import com.slopus.happy.ui.home.HomeScreen
import com.slopus.happy.ui.home.SessionListView
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * SidebarView.tsx (happy-ios `TabletSidebarViewController`): New session and
 * the archive button on top, then MainView.tsx's sidebar variant (a spinner,
 * EmptySessionsTablet, or the session list), then Settings at the foot. The
 * flat list is home's own [SessionListView], acting through [home], so a tap
 * pushes the chat onto the stack beside the sidebar, where everything else
 * the sidebar opens goes too.
 *
 * It reads the same home list as the phone home and tells the shell when
 * home turns into Link Your Computer, which the shell steps aside for.
 */
@SuppressLint("ViewConstructor")
class TabletSidebarView(context: Context, private val graph: AppGraph, private val home: HomeScreen) : ManualLayout(context), ThemeAware {
    private val services = HomeServices.of(graph)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val border = GradientDrawable()
    private val newSession = NewSessionButton(context)
    private val archive = ArchiveButton(context)
    private val settingsRow = SettingsRow(context)
    private val empty = TabletEmptySessionsView(context)
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private var list: SessionListView? = null
    private var avatarSettings = AvatarSettings()

    var state: TabletSidebarState? = null
        private set

    /** The shell steps aside while home shows Link Your Computer. */
    var onStateChange: ((TabletSidebarState) -> Unit)? = null

    var safeTop = 0.0
        set(value) { if (field != value) { field = value; requestLayout() } }
    var safeBottom = 0.0
        set(value) { if (field != value) { field = value; requestLayout() } }

    /** The row the persistent header's controls sit in; the controls start below it. */
    var headerHeight = 0.0
        set(value) { if (field != value) { field = value; requestLayout() } }

    /** The chat open beside the sidebar, whose row stays selected (SessionsList.tsx `selectedSessionId`). */
    var selectedSessionId: String? = null
        set(value) {
            if (field == value) return
            field = value
            list?.selectedSessionId = value
        }

    init {
        background = border
        clipChildren = true
        empty.visibility = GONE
        archive.visibility = GONE
        spinner.visibility = GONE
        listOf(newSession, archive, empty, spinner, settingsRow).forEach(::addView)
        newSession.setOnClickListener { home.openNewSession() }
        archive.setOnClickListener { toggleArchive() }
        settingsRow.setOnClickListener { home.openSettingsFromSidebar() }
        empty.button.setOnClickListener { emptyAction() }
        applyTheme()
        scope.launch {
            combine(services.list.state, graph.settings.settings) { list, settings -> list to settings }.collect { (list, settings) ->
                avatarSettings = AvatarSettings(AvatarStyle.normalize(settings.avatarStyle), settings.avatarMonochrome, settings.showFlavorIcons)
                received(TabletSidebarState.of(list))
            }
        }
    }

    /** The sidebar is gone for good: another account, or signed out. */
    fun close() = scope.cancel()

    /** The sidebar's own hairline frame, on all four sides. */
    override fun applyTheme() {
        border.setColor(Theme.color(Palette.groupedBackground))
        border.setStroke(1, Theme.color(Palette.divider))
        spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(Palette.textSecondary))
    }

    private fun received(state: TabletSidebarState) {
        val previous = this.state
        this.state = state
        archive.visibility = if (state.hasArchived) VISIBLE else GONE
        archive.hidesArchived = state.hidesArchived
        when (val content = state.content) {
            TabletSidebarState.Content.Loading -> {
                spinner.visibility = VISIBLE
                empty.visibility = GONE
                list?.visibility = GONE
            }
            is TabletSidebarState.Content.Empty -> {
                spinner.visibility = GONE
                empty.state = content.sessions
                empty.visibility = VISIBLE
                list?.visibility = GONE
            }
            is TabletSidebarState.Content.List -> {
                spinner.visibility = GONE
                empty.visibility = GONE
                showList(content)
            }
        }
        if (previous?.content?.javaClass != state.content.javaClass || previous?.hasArchived != state.hasArchived) requestLayout()
        if (previous?.showsInstallStep != state.showsInstallStep) onStateChange?.invoke(state)
    }

    private fun showList(content: TabletSidebarState.Content.List) {
        val view = list ?: SessionListView(context, home).also { created ->
            created.selectedSessionId = selectedSessionId
            addView(created, indexOfChild(settingsRow))
            list = created
            requestLayout()
        }
        view.visibility = VISIBLE
        view.avatarSettings = avatarSettings
        view.banners = home.banners(content.state)
        // The project hierarchy is SessionsList.tsx's own (`TabletGroupedList`), not yet ported: the flat rows stand in.
        view.submit(content.state.rows, content.state.grouping)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val hairline = 1.0 / Theme.density
        val layout = TabletSidebarLayout(
            TabletSidebarLayout.Input(
                size = Size(width, height),
                safeTop = safeTop,
                safeBottom = safeBottom,
                headerHeight = headerHeight,
                hairline = hairline,
                newSessionIcon = newSession.icon.intrinsicSize(),
                newSessionTitle = newSession.label.measurable,
                archiveIcon = if (archive.visibility == GONE) null else archive.icon.intrinsicSize(),
                settingsIcon = settingsRow.icon.intrinsicSize(),
                settingsTitle = settingsRow.label.measurable,
                spinner = Size(SPINNER, SPINNER),
            ),
        )
        val button = layout.newSessionButton
        newSession.setFrame(button)
        newSession.icon.setFrame(layout.newSessionIcon.offsetBy(-button.minX, -button.minY))
        newSession.label.setFrame(layout.newSessionTitle.offsetBy(-button.minX, -button.minY))
        val archiveFrame = layout.archiveButton
        val archiveIcon = layout.archiveIcon
        if (archiveFrame != null && archiveIcon != null) {
            archive.setFrame(archiveFrame)
            archive.icon.setFrame(archiveIcon.offsetBy(-archiveFrame.minX, -archiveFrame.minY))
        }
        val row = layout.settingsRow
        settingsRow.setFrame(row)
        settingsRow.divider.setFrame(layout.settingsDivider.offsetBy(-row.minX, -row.minY))
        settingsRow.icon.setFrame(layout.settingsIcon.offsetBy(-row.minX, -row.minY))
        settingsRow.label.setFrame(layout.settingsTitle.offsetBy(-row.minX, -row.minY))
        empty.setFrame(layout.content)
        list?.let { view ->
            // Home's list pads itself under its floating header; here nothing floats over it.
            view.contentInsets = HomeContentInsets(chromeTop = 0.0, contentTop = 0.0, dock = 0.0, listBottom = layout.listBottomInset)
            view.setFrame(layout.content)
        }
        spinner.setFrame(layout.spinner)
    }

    private fun toggleArchive() {
        val state = state ?: return
        graph.settings.change { it.copy(hideInactiveSessions = !state.hidesArchived) }
    }

    /** Start New Session with a reachable computer; troubleshoot with none (`useOfflineMachineTroubleshooting`). */
    private fun emptyAction() {
        when (empty.state) {
            TabletEmptySessions.Online -> home.openNewSession()
            is TabletEmptySessions.Offline -> home.openTroubleshoot()
            TabletEmptySessions.NoMachines -> Unit
        }
    }

    private companion object {
        /** UIActivityIndicatorView's medium style. */
        const val SPINNER = 20.0
    }
}

/** A hairline-framed control that darkens while pressed, rounded 10 (SidebarView.tsx's buttons). */
private abstract class SidebarButton(context: Context) : ManualLayout(context), ThemeAware {
    private val fill = GradientDrawable().apply { cornerRadius = com.slopus.happy.ui.view.dp(TabletSidebarLayout.Metrics.buttonRadius) }

    init {
        background = fill
        isClickable = true
        isFocusable = true
    }

    /** The resting fill. */
    abstract val restingFill: com.slopus.happy.layout.ColorToken

    override fun applyTheme() {
        fill.setColor(Theme.color(if (isPressed) Palette.surfacePressed else restingFill))
        fill.setStroke(1, Theme.color(Palette.divider))
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        applyTheme()
    }

    override fun layoutSubviews(width: Double, height: Double) = Unit
}

/** SidebarView.tsx's New session button. Its label follows the person's text size, and the button grows with it. */
private class NewSessionButton(context: Context) : SidebarButton(context) {
    val icon = IconView(context, Icon.ionicons("create-outline"), TabletSidebarLayout.Metrics.newSessionIconSize, Palette.text)
    val label = Label(context, TextStyles.sidebarNewSession, Palette.text, maxLines = 1)
    override val restingFill get() = Palette.surface

    init {
        label.text = context.getString(R.string.sidebar_new_session)
        listOf(icon, label).forEach { it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; addView(it) }
        contentDescription = label.text
        applyTheme()
    }
}

/** SidebarView.tsx's archive button: shows or hides the archive. Selected (the archive showing) it takes the selected surface and the filled glyph. */
private class ArchiveButton(context: Context) : SidebarButton(context) {
    val icon = IconView(context, Icon.ionicons("archive-outline"), TabletSidebarLayout.Metrics.archiveIconSize, Palette.text)

    /** `hideInactiveSessions`. */
    var hidesArchived = true
        set(value) { if (field != value) { field = value; apply() } }

    override val restingFill get() = if (hidesArchived) Palette.surface else Palette.surfaceSelected

    init {
        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icon)
        apply()
    }

    private fun apply() {
        icon.icon = Icon.ionicons(if (hidesArchived) "archive-outline" else "archive")
        contentDescription = context.getString(if (hidesArchived) R.string.sidebar_show_archived else R.string.sidebar_hide_archived)
        isSelected = !hidesArchived
        applyTheme()
    }
}

/** SidebarView.tsx's Settings row at the foot of the sidebar. Its label follows the person's text size, and the row grows with it. */
private class SettingsRow(context: Context) : ManualLayout(context), ThemeAware {
    val divider = View(context)
    val icon = IconView(context, Icon.ionicons("settings-outline"), TabletSidebarLayout.Metrics.settingsIconSize, Palette.text)
    val label = Label(context, TextStyles.sidebarSettings, Palette.text, maxLines = 1)

    init {
        label.text = context.getString(R.string.settings_title)
        listOf(divider, icon, label).forEach { it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; addView(it) }
        contentDescription = label.text
        isClickable = true
        isFocusable = true
        applyTheme()
    }

    /** It has no pressed look in happy-app; the platform's ripple marks the touch. */
    override fun applyTheme() {
        divider.setBackgroundColor(Theme.color(Palette.divider))
        background = RippleDrawable(ColorStateList.valueOf(Theme.color(Palette.surfacePressedOverlay)), null, null)
    }

    override fun layoutSubviews(width: Double, height: Double) = Unit
}

/**
 * EmptySessionsTablet.tsx: a glyph, a title, a line of help and, with a
 * computer linked, one filled button. Its text follows the person's text
 * size; the column is measured from it as drawn.
 */
private class TabletEmptySessionsView(context: Context) : ManualLayout(context), ThemeAware {
    private val icon = IconView(context, Icon.ionicons("terminal-outline"), TabletEmptySessionsLayout.Metrics.iconSize, Palette.textSecondary)
    private val titleLabel = Label(context, TextStyles.tabletEmptyTitle, Palette.textSecondary, alignment = Layout.Alignment.ALIGN_CENTER)
    private val descriptionLabel = Label(context, TextStyles.tabletEmptyDescription, Palette.textSecondary, alignment = Layout.Alignment.ALIGN_CENTER)
    val button = object : ManualLayout(context) {
        override fun layoutSubviews(width: Double, height: Double) = Unit
    }
    private val buttonFill = GradientDrawable().apply { cornerRadius = com.slopus.happy.ui.view.dp(TabletEmptySessionsLayout.Metrics.buttonRadius) }
    private val buttonIcon = IconView(context, Icon.ionicons("add"), TabletEmptySessionsLayout.Metrics.buttonIconSize, SecondaryPalette.buttonPrimaryTint)
    private val buttonLabel = Label(context, TextStyles.tabletEmptyButton, SecondaryPalette.buttonPrimaryTint)

    var state: TabletEmptySessions = TabletEmptySessions.NoMachines
        set(value) { if (field != value) { field = value; apply() } }

    init {
        androidx.core.view.ViewCompat.setAccessibilityHeading(titleLabel, true)
        button.background = buttonFill
        button.isClickable = true
        button.isFocusable = true
        listOf(buttonIcon, buttonLabel).forEach { it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; button.addView(it) }
        listOf(icon, titleLabel, descriptionLabel, button).forEach(::addView)
        applyTheme()
        apply()
    }

    override fun applyTheme() {
        setBackgroundColor(Theme.color(Palette.groupedBackground))
        buttonFill.setColor(Theme.color(SecondaryPalette.buttonPrimary))
    }

    private fun apply() {
        when (val state = state) {
            TabletEmptySessions.Online -> {
                icon.icon = Icon.ionicons("terminal-outline")
                titleLabel.text = context.getString(R.string.tablet_no_sessions_title)
                descriptionLabel.text = context.getString(R.string.tablet_start_description)
                buttonIcon.icon = Icon.ionicons("add")
                buttonLabel.text = context.getString(R.string.tablet_start_new_session)
            }
            is TabletEmptySessions.Offline -> {
                icon.icon = Icon.ionicons("cloud-offline-outline")
                titleLabel.text = if (state.names.size == 1) {
                    context.getString(R.string.tablet_machine_unreachable, state.names[0])
                } else {
                    context.getString(R.string.tablet_no_machines_reachable)
                }
                descriptionLabel.text = context.getString(R.string.tablet_offline_description)
                buttonIcon.icon = Icon.ionicons("help-circle-outline")
                buttonLabel.text = context.getString(R.string.tablet_troubleshoot)
            }
            TabletEmptySessions.NoMachines -> {
                icon.icon = Icon.ionicons("terminal-outline")
                titleLabel.text = context.getString(R.string.tablet_no_sessions_title)
                descriptionLabel.text = context.getString(R.string.tablet_install_description)
            }
        }
        button.visibility = if (hasButton) VISIBLE else GONE
        button.contentDescription = buttonLabel.text
        requestLayout()
    }

    private val hasButton get() = state != TabletEmptySessions.NoMachines

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = TabletEmptySessionsLayout(
            TabletEmptySessionsLayout.Input(
                size = Size(width, height),
                icon = icon.intrinsicSize(),
                title = titleLabel.measurable,
                description = descriptionLabel.measurable,
                button = if (hasButton) TabletEmptySessionsLayout.Button(buttonIcon.intrinsicSize(), buttonLabel.measurable) else null,
            ),
        )
        icon.setFrame(layout.icon)
        titleLabel.setFrame(layout.title)
        descriptionLabel.setFrame(layout.description)
        val frame = layout.button ?: return
        button.setFrame(frame)
        layout.buttonIcon?.let { buttonIcon.setFrame(it.offsetBy(-frame.minX, -frame.minY)) }
        layout.buttonTitle?.let { buttonLabel.setFrame(it.offsetBy(-frame.minX, -frame.minY)) }
    }
}
