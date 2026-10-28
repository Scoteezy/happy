package com.slopus.happy.ui.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.auth.AuthState
import com.slopus.happy.auth.InvalidLinkException
import com.slopus.happy.auth.TERMINAL_URL_PREFIX
import com.slopus.happy.auth.approveTerminal
import com.slopus.happy.changelog.Changelog
import com.slopus.happy.connect.GmsQrScanner
import com.slopus.happy.connect.ScanResult
import com.slopus.happy.connect.acceptScan
import com.slopus.happy.home.HomeListState
import com.slopus.happy.home.HomeServices
import com.slopus.happy.layout.HomeDockFade
import com.slopus.happy.layout.HomeDockLayout
import com.slopus.happy.layout.HomeScreenLayout
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.NavigationBarLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.model.HomeEmptyState
import com.slopus.happy.model.ProjectHomeEntry
import com.slopus.happy.model.SessionListGrouping
import com.slopus.happy.model.workspaceOrigin
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.AvatarStyle
import com.slopus.happy.ui.components.RoundButton
import com.slopus.happy.ui.chat.ChatScreen
import com.slopus.happy.layout.RoundButtonDisplay
import com.slopus.happy.layout.RoundButtonSize
import com.slopus.happy.ui.nav.NavigationBar
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.onboarding.LinkComputerContentView
import com.slopus.happy.ui.onboarding.OnboardingScreenView
import com.slopus.happy.ui.onboarding.OnboardingSettingsScreen
import com.slopus.happy.ui.onboarding.WelcomeScreen
import com.slopus.happy.ui.stemcells.StemCellsLinkScreen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Signed in: app/(app)/index.tsx and SessionsListWrapper.tsx (happy-ios
 * `HomeViewController`). Home stays the root for the whole session and swaps
 * its content as the account changes:
 *   - a spinner until the machines are known;
 *   - Link Your Computer while none is linked;
 *   - the offline checklist while none is reachable and nothing is listed;
 *   - "No sessions yet" on a reachable computer with nothing on it;
 *   - the session list otherwise.
 *
 * Link Your Computer keeps the onboarding bar: no title, the custom server
 * under it and a gear to the onboarding settings. Every other state is
 * MainView.tsx's phone home: no bar, and the home header floats over content
 * that scrolls under it. The dock sits at the foot of every state but Link
 * Your Computer and, where a computer is a home of its own, the offline
 * checklist. Content swaps cross-fade in 250ms, as UIView.transition does.
 */
class HomeScreen(context: Context, private val graph: AppGraph) : Screen(context), HomeListActions {
    private enum class Kind { Loading, Link, Offline, NoSessions, List }

    private val services = HomeServices.of(graph)
    private val header = HomeHeaderView(context)
    private val dock = HomeDockView(context)
    private val dockController: HomeDockController by lazy {
        HomeDockController(context, dock, services.newSession(context), scope, DockHost())
    }
    private val bar = NavigationBar(context)
    private val root = Root(context)
    private var kind: Kind? = null
    private var content: View? = null
    private var list: SessionListView? = null
    private var grouping = SessionListGrouping.Flat
    private var avatarSettings = AvatarSettings()

    override fun createView(): View = root

    override fun viewCreated(view: View) {
        header.visibility = View.GONE
        header.settingsButton.setOnClickListener { openSettings() }
        header.filterButton.setOnClickListener { showFilterMenu() }
        // Where a computer is a home of its own, the account's picture leads to the profiles' settings.
        header.showsAccountAvatar = graph.stemCells.isAvailable
        dock.visibility = View.GONE
        dockController.render()
        bar.actions = listOf(
            NavigationBar.Action(WelcomeScreen.SETTINGS_ICON, context.getString(R.string.onboarding_settings_title), 22.0) {
                push(OnboardingSettingsScreen(context, graph))
            },
        )
        bar.visibility = View.GONE

        scope.launch { graph.connectionStatus.collect { header.status = it } }
        scope.launch {
            combine(services.list.state, graph.settings.settings, graph.changelog.lastViewedTitle) { state, settings, _ -> state to settings }
                .collect { (state, settings) ->
                    avatarSettings = AvatarSettings(AvatarStyle.normalize(settings.avatarStyle), settings.avatarMonochrome, settings.showFlavorIcons)
                    apply(state)
                }
        }
    }

    override fun onAppear() {
        super.onAppear()
        // The server may have changed on a screen pushed from here.
        bar.subtitle = graph.serverConfig.customHostLabel
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        bar.safeArea = safeArea
        header.safeTop = safeArea.top
        dock.safeBottom = safeArea.bottom
        focus?.let {
            it.safeTop = safeArea.top
            it.safeBottom = safeArea.bottom
            it.keyboard = keyboard
        }
        root.requestLayout()
    }

    override fun onBackPressed(): Boolean = dockController.onBackPressed()

    /** Something can be started from the dock: a computer is linked and, where a computer is a home of its own, one is reachable. */
    private val showsDock: Boolean
        get() = when (kind) {
            null, Kind.Link -> false
            Kind.Offline -> !graph.stemCells.isAvailable
            Kind.Loading, Kind.NoSessions, Kind.List -> true
        }

    private fun apply(state: HomeListState?) {
        val next = when {
            state == null -> Kind.Loading
            state.showsInstallStep -> Kind.Link
            else -> when (state.emptyState) {
                HomeEmptyState.Link -> Kind.Link
                HomeEmptyState.Offline -> Kind.Offline
                HomeEmptyState.NoSessions -> Kind.NoSessions
                HomeEmptyState.List -> Kind.List
            }
        }
        val previous = kind
        kind = next
        // The scrim is for a list that scrolls under the dock.
        dock.showsScrim = next == Kind.List
        if (previous != next) embed(makeContent(next), animated = previous != null)
        val hasArchived = state != null && state.hasArchivedSessions && state.archiveHidden
        when (val view = content) {
            is SessionListView -> if (state != null) {
                grouping = state.grouping
                view.avatarSettings = avatarSettings
                view.banners = banners(state)
                view.submit(state.rows, state.grouping)
            }
            is OfflineChecklistView -> if (state != null) {
                val computers = state.computers
                if (computers.size == 1) {
                    view.title = context.getString(R.string.onboarding_offline_title_one, computers[0])
                    view.linkedTitle = context.getString(R.string.onboarding_offline_linked_step, computers[0])
                } else {
                    view.title = context.getString(R.string.onboarding_offline_title_many)
                    view.linkedTitle = context.getString(R.string.onboarding_offline_linked_step_many, computers.size)
                }
                view.showsArchivedButton = hasArchived
            }
            is NoSessionsView -> view.showsArchivedAction = hasArchived
        }
        if (previous != next) updateChrome(animated = previous != null)
    }

    /** HomeListHeader.tsx: the offline plaque, then What's new while the latest entry is unread. */
    private fun banners(state: HomeListState): HomeBanners {
        val offline = state.offlineBanner?.let { banner ->
            banner.machineName?.let { context.getString(R.string.troubleshoot_banner_title_one, it) }
                ?: context.getString(R.string.troubleshoot_banner_title_many)
        }
        val latest = Changelog.bundled(context.resources).latestTitle
        return HomeBanners(offlineTitle = offline, whatsNew = graph.changelog.hasUnread(latest))
    }

    private fun makeContent(kind: Kind): View {
        list = null
        return when (kind) {
            Kind.Loading -> LoadingView(context)
            Kind.Link -> LinkPane().view
            Kind.Offline -> OfflineChecklistView(context).also { view ->
                view.troubleshoot.onTap = { notPorted(context.getString(R.string.onboarding_offline_troubleshoot)) }
                view.linkAnother.onTap = { scanTerminal(view.linkAnother) }
                view.showArchived.onTap = { showArchive() }
            }
            Kind.NoSessions -> NoSessionsView(context).also { view ->
                // The new-session screen is a later stage of the port; the dock's composer starts one meanwhile.
                view.startNewSession.onTap = { dockController.openFocus() }
                view.showArchived.onTap = { showArchive() }
            }
            Kind.List -> SessionListView(context, this).also { list = it }
        }
    }

    private fun embed(next: View, animated: Boolean) {
        val previous = content
        content = next
        root.addView(next, 0)
        if (!animated || previous == null) {
            previous?.let(root::removeView)
        } else {
            // UIView.transition's cross-dissolve: the new content fades in as the old fades out.
            next.alpha = 0f
            next.animate().alpha(1f).setDuration(CROSSFADE_MS).setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.removeView(previous)
                }
            }).start()
            previous.animate().alpha(0f).setDuration(CROSSFADE_MS).start()
        }
        root.requestLayout()
    }

    private fun updateChrome(animated: Boolean) {
        val floating = kind != Kind.Link
        header.visibility = if (floating) View.VISIBLE else View.GONE
        bar.visibility = if (floating) View.GONE else View.VISIBLE
        dockController.isAvailable = showsDock
        root.requestLayout()
    }

    private fun showArchive() = graph.settings.change { it.copy(hideInactiveSessions = false) }

    /**
     * MainView.tsx `HeaderRight`: the grouping choice and a way to the
     * appearance settings. iOS shows a native menu from the button; Android's
     * native equivalent is a popup menu anchored to it, the grouping as a
     * checkable group and Appearance below a divider.
     */
    private fun showFilterMenu() {
        val themed = ContextThemeWrapper(context, if (Theme.isDark) android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light)
        val popup = PopupMenu(themed, header.filterButton)
        val menu = popup.menu
        // iOS titles the inline section "Grouping"; a popup menu has no section titles, so a disabled row says it.
        val heading = menu.add(GROUPING, Menu.NONE, 0, R.string.sessions_filter_grouping_title)
        menu.add(GROUPING, FLAT, 1, R.string.sessions_filter_flat_list).isChecked = grouping == SessionListGrouping.Flat
        menu.add(GROUPING, PROJECT, 2, R.string.sessions_filter_group_by_project).isChecked = grouping == SessionListGrouping.Project
        menu.setGroupCheckable(GROUPING, true, true)
        heading.isCheckable = false
        heading.isEnabled = false
        menu.add(APPEARANCE_GROUP, APPEARANCE, 3, R.string.sessions_filter_appearance_settings).icon =
            androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_palette)?.mutate()?.apply { setTint(Theme.color(Palette.text)) }
        menu.setGroupDividerEnabled(true)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                FLAT -> graph.settings.change { it.copy(sessionListGrouping = SessionListGrouping.Flat) }
                PROJECT -> graph.settings.change { it.copy(sessionListGrouping = SessionListGrouping.Project) }
                APPEARANCE -> notPorted(context.getString(R.string.sessions_filter_appearance_settings))
            }
            true
        }
        popup.show()
    }

    private fun openSettings() {
        // Settings is not ported yet; the onboarding settings hold the server and logout meanwhile.
        push(OnboardingSettingsScreen(context, graph, standIn = true))
    }

    private fun notPorted(title: String) = Alerts.show(context, title, NOT_PORTED)

    // HomeListActions. The chat, the session actions and new workspaces are later stages of the port.

    override fun openSession(id: String) = push(ChatScreen(context, graph, id))

    override fun toggleArchive(hidden: Boolean) = graph.settings.change { it.copy(hideInactiveSessions = !hidden) }

    override fun toggleExpanded(projectId: String) = graph.localSettings.change { values ->
        val expanded = values.expandedProjects[projectId] == true
        values.copy(expandedProjects = values.expandedProjects + (projectId to !expanded))
    }

    override fun holdSession(id: String) = Unit

    override fun holdProject(entry: ProjectHomeEntry) = Unit

    /** ProjectRow's +: the draft moves to the project, asking for a new workspace, and the composer opens on it. */
    override fun createWorkspace(entry: ProjectHomeEntry) {
        val origin = (entry.session ?: entry.avatarSession)?.let(::workspaceOrigin) ?: return
        dockController.services.prepareWorkspace(origin.machineId, origin.projectId, origin.path)
        dockController.openFocus()
    }

    override fun openTroubleshoot() = notPorted(context.getString(R.string.onboarding_offline_troubleshoot))

    override fun openWhatsNew() = notPorted(context.getString(R.string.update_banner_whats_new))

    override fun openStore() = Alerts.open(context, "market://details?id=${context.packageName}")

    /** "Link another computer": scans a terminal's code and approves it; failures are alerts, as in happy-app. */
    private fun scanTerminal(button: RoundButton) {
        scope.launch {
            val link = acceptScan(GmsQrScanner(context).scan(), TERMINAL_URL_PREFIX) ?: return@launch
            button.isLoading = true
            val failure = approve(link)
            if (failure == null) {
                delay(MACHINE_ARRIVAL_TIMEOUT)
            } else {
                Alerts.show(context, context.getString(R.string.common_error), failure)
            }
            button.isLoading = false
        }
    }

    /** Approves a terminal link (useConnectTerminal.ts processAuthUrl): null when it went through, otherwise what went wrong. */
    private suspend fun approve(link: String): String? {
        val signedIn = graph.auth.state.value as? AuthState.SignedIn ?: return context.getString(R.string.modals_failed_to_connect_terminal)
        return try {
            approveTerminal(graph.authApi, signedIn.credentials, signedIn.encryption, link)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: InvalidLinkException) {
            context.getString(R.string.modals_invalid_auth_url)
        } catch (_: Exception) {
            context.getString(R.string.modals_failed_to_connect_terminal)
        }
    }

    /**
     * OnboardingLinkComputer (happy-ios `LinkComputerViewController`):
     * scanning opens once both boxes are ticked, and the ticks are kept until
     * logout. A link that went through keeps the button busy until the
     * computer arrives and home swaps this screen out, or for ten seconds if
     * it never does. A failed one says so on the line above the button.
     */
    private inner class LinkPane {
        val content = LinkComputerContentView(context)
        val view = OnboardingScreenView(
            context,
            content,
            { content.layout(it).size },
            RoundButton(context, context.getString(R.string.onboarding_scan_button)),
            RoundButton(context, context.getString(R.string.onboarding_paste_link), RoundButtonSize.Normal, RoundButtonDisplay.Inverted),
        )
        private var linking = false
        private var approved = false
        private var timeout: Job? = null

        init {
            view.primary.loadingTitle = context.getString(R.string.onboarding_linking)
            view.primary.onTap = { scan() }
            view.secondary.onTap = { paste() }
            view.help.onTap = { Alerts.help(context) }
            content.onOpenWebsite = { Alerts.open(context, StemCellsLinkScreen.DESKTOP_URL) }
            val saved = graph.localSettings.values.value.linkComputerChecklist
            content.ticked = buildSet {
                if (saved["install"] == true) add(LinkComputerContentView.Step.Install)
                if (saved["open"] == true) add(LinkComputerContentView.Step.Open)
            }
            content.onToggle = { ticked ->
                graph.localSettings.change {
                    it.copy(
                        linkComputerChecklist = mapOf(
                            "install" to (LinkComputerContentView.Step.Install in ticked),
                            "open" to (LinkComputerContentView.Step.Open in ticked),
                        ),
                    )
                }
                update()
            }
            update()
        }

        private val busy get() = linking || approved

        private fun update() {
            val ready = content.ticked == LinkComputerContentView.Step.entries.toSet()
            view.primary.isLoading = busy
            view.primary.isEnabled = !busy && ready
            view.secondary.isEnabled = !busy
            content.isEnabledSteps = !busy
        }

        private fun scan() {
            content.error = null
            scope.launch {
                val text = (GmsQrScanner(context).scan() as? ScanResult.Scanned)?.value ?: return@launch
                link(text)
            }
        }

        private fun paste() {
            content.error = null
            scope.launch {
                val text = Alerts.prompt(
                    context,
                    context.getString(R.string.onboarding_paste_link_title),
                    context.getString(R.string.onboarding_paste_link_message),
                    placeholder = "happy://terminal?...",
                    confirmTitle = context.getString(R.string.onboarding_paste_link_confirm),
                )?.trim()
                if (text.isNullOrEmpty()) return@launch
                link(text)
            }
        }

        private suspend fun link(text: String) {
            if (busy) return
            if (!text.startsWith(TERMINAL_URL_PREFIX)) {
                // This needs more than one line, and scanning again won't help.
                Alerts.show(context, context.getString(R.string.common_error), context.getString(R.string.modals_invalid_auth_url))
                return
            }
            linking = true
            update()
            val failure = approve(text)
            linking = false
            if (failure == null) {
                approved = true
                timeout?.cancel()
                timeout = scope.launch {
                    delay(MACHINE_ARRIVAL_TIMEOUT)
                    approved = false
                    update()
                }
            } else {
                content.error = context.getString(R.string.onboarding_link_failed)
                view.announceForAccessibility(content.error)
            }
            update()
        }
    }

    private var focus: HomeDockFocusView? = null

    /** The open composer lies over everything on home; the list, header and capsule blur faintly under it. */
    private inner class DockHost : HomeDockController.Host {
        override val canPresent: Boolean get() = root.isAttachedToWindow && stack?.top === this@HomeScreen

        override fun present(focus: HomeDockFocusView) {
            this@HomeScreen.focus = focus
            focus.safeTop = safeArea.top
            focus.safeBottom = safeArea.bottom
            focus.keyboard = keyboard
            focus.onBackdrop = ::blurUnderFocus
            root.addView(focus)
        }

        override fun dismiss(focus: HomeDockFocusView) {
            blurUnderFocus(0f)
            root.removeView(focus)
            if (this@HomeScreen.focus === focus) this@HomeScreen.focus = null
        }

        override fun openSession(id: String) = this@HomeScreen.openSession(id)
    }

    /** expo-blur's intensity 8 under the dim: a few pixels at most, Android 12 and later. */
    private fun blurUnderFocus(amount: Float) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        val radius = com.slopus.happy.ui.view.dp(BACKDROP_BLUR) * amount
        val effect = if (radius < 0.5f) null else android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP)
        for (view in listOfNotNull(content, header)) view.setRenderEffect(effect)
    }

    private inner class Root(context: Context) : ManualLayout(context), ThemeAware {
        init {
            addView(dock)
            addView(header)
            addView(bar)
            applyTheme()
        }

        override fun applyTheme() = setBackgroundColor(Theme.color(Palette.groupedBackground))

        override fun layoutSubviews(width: Double, height: Double) {
            val layout = HomeScreenLayout(Size(width, height), safeArea, if (showsDock) HomeDockLayout.Metrics.contentInset else 0.0)
            header.setFrame(layout.header)
            dock.setFrame(Rect(0.0, 0.0, width, height))
            bar.setFrame(Rect(0.0, 0.0, width, NavigationBarLayout.height(safeArea.top)))
            val floating = kind != Kind.Link
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child === dock || child === header || child === bar) continue
                if (child is HomeDockFocusView) {
                    child.setFrame(Rect(0.0, 0.0, width, height))
                    continue
                }
                child.setFrame(layout.content)
                when (child) {
                    // The list pads itself under the header; every other state sees the padding as safe area.
                    is SessionListView -> child.contentInsets = layout.insets
                    is HomeContentView -> child.safeArea = if (!floating) safeArea else LayoutInsets(
                        top = maxOf(safeArea.top, layout.insets.emptyStateSafeArea.top),
                        left = safeArea.left,
                        bottom = maxOf(safeArea.bottom, layout.insets.listBottom),
                        right = safeArea.right,
                    )
                    is OnboardingScreenView -> child.safeArea =
                        LayoutInsets(NavigationBarLayout.height(safeArea.top), safeArea.left, safeArea.bottom, safeArea.right)
                }
            }
        }
    }

    private companion object {
        const val CROSSFADE_MS = 250L
        /** The open composer's blur under the dim, in dp: the ultra-thin material at 0.08. */
        const val BACKDROP_BLUR = 10.0
        val MACHINE_ARRIVAL_TIMEOUT = 10.seconds
        const val GROUPING = 1
        const val APPEARANCE_GROUP = 2
        const val FLAT = Menu.FIRST
        const val PROJECT = Menu.FIRST + 1
        const val APPEARANCE = Menu.FIRST + 2
        const val NOT_PORTED = "Not available in this Android build yet."
    }
}
