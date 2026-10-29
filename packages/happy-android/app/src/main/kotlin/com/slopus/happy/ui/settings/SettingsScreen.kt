package com.slopus.happy.ui.settings

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import com.slopus.happy.AppGraph
import com.slopus.happy.BuildConfig
import com.slopus.happy.R
import com.slopus.happy.config.AppVariant
import com.slopus.happy.icons.Icon
import com.slopus.happy.model.Profile
import com.slopus.happy.model.Settings
import com.slopus.happy.model.settings.MultiClickCounter
import com.slopus.happy.model.settings.SettingsIndexModel
import com.slopus.happy.layout.Palette
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.AvatarStyle
import com.slopus.happy.ui.components.GroupedListView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.components.ItemIcon
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.nav.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The stored profile as it changes; null until the first sync has one. */
fun AppGraph.profiles(): Flow<Profile?> = store.changes
    .map { store.profile() }
    .distinctUntilChanged()
    .map { json -> json?.let(Profile::parse) }
    .flowOn(Dispatchers.IO)

/**
 * Settings (components/SettingsView.tsx, happy-ios `SettingsViewController`):
 * the profile or logo header, connecting a terminal, connected accounts, the
 * feature pages and About. Usage and the Developer row are not ported, as on
 * iOS; logout and the push rows live on the Account page, as in happy-app.
 * The EULA row is iOS-only in happy-app, so it is not here.
 */
class SettingsScreen(context: Context, private val graph: AppGraph) : BarScreen(context) {
    // navigation/Header.tsx's plain header: the tablet shell's back serves a tablet.
    override val dropsBackButtonOnTablet get() = true

    private val list = GroupedListView(context)
    private var profile: Profile? = null
    private var settings: Settings = graph.settings.settings.value
    private val versionClicks = MultiClickCounter()
    private var profileHeader: SettingsProfileHeaderView? = null
    private var logoHeader: SettingsHeaderView? = null
    private var connectingTerminal = false
    private var claudeBusy = false
    private var githubBusy = false

    override fun createContent(): View {
        // Header.tsx's title, which scales with the text size (happy-ios setScaledTitleView).
        bar.plainTitle = true
        bar.title = context.getString(R.string.settings_title)
        return list
    }

    override fun viewCreated(view: View) {
        super.viewCreated(view)
        scope.launch {
            combine(graph.profiles(), graph.settings.settings) { profile, settings -> profile to settings }.collect { (profile, settings) ->
                this@SettingsScreen.profile = profile
                this@SettingsScreen.settings = settings
                rebuild()
            }
        }
    }

    override fun onAppear() {
        super.onAppear()
        rebuild()
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea
    }

    private fun string(id: Int) = context.getString(id)

    private fun glyph(name: String, tint: com.slopus.happy.layout.ColorToken) = ItemIcon.Glyph(Icon.ionicons(name), tint)

    private fun rebuild() {
        val model = SettingsIndexModel(profile)
        val blue = Palette.iconBlue
        val sections = mutableListOf<View>(header(model.header))

        val scan = ItemRowView(context, string(R.string.settings_scan_qr_code_to_authenticate), icon = glyph("qr-code-outline", blue)) { scanTerminal() }
        scan.showsChevron = false
        scan.isLoading = connectingTerminal
        val enterUrl = ItemRowView(context, string(R.string.connect_enter_url_manually), icon = glyph("link-outline", blue)) { enterTerminalUrl() }
        enterUrl.showsChevron = false
        sections += ItemGroupView(context, rows = listOf(scan, enterUrl))

        sections += ItemGroupView(
            context,
            rows = listOf(
                ItemRowView(context, string(R.string.settings_support_us), subtitle = string(R.string.settings_support_us_subtitle), icon = glyph("heart-outline", Palette.iconRed)) {
                    open(SupportUsScreen(context))
                },
            ),
        )

        val claude = ItemRowView(
            context,
            string(R.string.settings_claude_code),
            subtitle = string(if (model.claudeConnected) R.string.settings_account_status_active else R.string.settings_connect_account),
            icon = ContextCompat.getDrawable(context, R.drawable.icon_claude)?.let { ItemIcon.Picture(it) },
        ) { if (model.claudeConnected) disconnectClaude() else open(ConnectClaudeScreen(context)) }
        claude.showsChevron = false
        claude.isLoading = claudeBusy
        val github = ItemRowView(
            context,
            string(R.string.settings_github),
            subtitle = model.githubLogin?.let { context.getString(R.string.settings_github_connected, it) } ?: string(R.string.settings_connect_github_account),
            icon = glyph("logo-github", if (model.githubLogin == null) Palette.textSecondary else Palette.statusConnected),
        ) { if (model.githubLogin == null) connectGitHub() else disconnectGitHub() }
        github.showsChevron = false
        github.isLoading = githubBusy
        sections += ItemGroupView(context, title = string(R.string.settings_connected_accounts), rows = listOf(claude, github))

        val agents = ItemRowView(context, string(R.string.settings_agents), subtitle = string(R.string.settings_agents_subtitle), icon = glyph("options-outline", Palette.iconTeal)) {
            open(AgentsScreen(context, graph))
        }
        sections += ItemGroupView(
            context,
            title = string(R.string.settings_features),
            rows = listOf(
                ItemRowView(context, string(R.string.settings_account), subtitle = string(R.string.settings_account_subtitle), icon = glyph("person-circle-outline", blue)) {
                    open(AccountScreen(context, graph))
                },
                ItemRowView(context, string(R.string.settings_appearance), subtitle = string(R.string.settings_appearance_subtitle), icon = glyph("color-palette-outline", Palette.iconPurple)) {
                    open(AppearanceScreen(context, graph))
                },
                agents,
                ItemRowView(context, string(R.string.settings_voice_assistant), subtitle = string(R.string.settings_voice_row_subtitle), icon = glyph("mic-outline", blue)) {
                    open(VoiceSettingsScreen(context, graph))
                },
            ),
        )
        // ItemGroup counts the `{experiments && …}` slot after Agents even
        // when it is empty, so Agents keeps its divider in happy-app.
        agents.showsDivider = true

        val version = ItemRowView(
            context,
            string(R.string.common_version),
            detail = SettingsIndexModel.versionDetail(BuildConfig.VERSION_NAME, AppVariant.current),
            icon = glyph("information-circle-outline", Palette.textSecondary),
        ) { versionTapped() }
        version.showsChevron = false
        sections += ItemGroupView(
            context,
            title = string(R.string.settings_about),
            footer = string(R.string.settings_about_footer),
            rows = listOf(
                ItemRowView(context, string(R.string.settings_whats_new), subtitle = string(R.string.settings_whats_new_subtitle), icon = glyph("sparkles-outline", Palette.iconOrange)) {
                    open(ChangelogScreen(context, graph))
                },
                ItemRowView(context, string(R.string.settings_github), detail = string(R.string.links_github_detail), icon = glyph("logo-github", Palette.text)) {
                    Alerts.open(context, LINK_GITHUB)
                },
                ItemRowView(context, string(R.string.settings_report_issue), icon = glyph("bug-outline", Palette.iconRed)) { Alerts.open(context, LINK_ISSUES) },
                ItemRowView(context, string(R.string.settings_privacy_policy), icon = glyph("shield-checkmark-outline", blue)) { Alerts.open(context, LINK_PRIVACY) },
                ItemRowView(context, string(R.string.settings_terms_of_service), icon = glyph("document-text-outline", blue)) { Alerts.open(context, LINK_TERMS) },
                version,
            ),
        )
        list.sections = sections
    }

    private fun header(header: SettingsIndexModel.Header): View = when (header) {
        SettingsIndexModel.Header.Logo -> {
            profileHeader = null
            logoHeader ?: SettingsHeaderView(context).also { logoHeader = it }
        }
        is SettingsIndexModel.Header.Profile -> {
            logoHeader = null
            val view = profileHeader ?: SettingsProfileHeaderView(context).also { profileHeader = it }
            view.content = SettingsProfileHeaderView.Content(
                header.id,
                header.name,
                header.bio,
                header.avatarUrl,
                header.thumbhash,
                AvatarStyle.normalize(settings.avatarStyle),
                settings.avatarMonochrome,
            )
            view
        }
    }

    private fun open(screen: Screen) = push(screen)

    // Connect a terminal (useConnectTerminal.ts)

    private fun scanTerminal() {
        com.slopus.happy.ui.stemcells.DirectPairingFlow.scan(graph, this)
    }

    private fun enterTerminalUrl() {
        scope.launch {
            val url = SettingsConnections.promptTerminalUrl(context) ?: return@launch
            approve(url)
        }
    }

    private suspend fun approve(text: String) {
        if (com.slopus.happy.direct.DirectPairingLink.isDirect(text)) {
            com.slopus.happy.ui.stemcells.DirectPairingFlow.open(text, graph, this)
            return
        }
        if (connectingTerminal) return
        connectingTerminal = true
        rebuild()
        SettingsConnections.approveTerminal(graph, text, list)
        connectingTerminal = false
        rebuild()
    }

    // Connected accounts

    private fun disconnectClaude() = busy({ claudeBusy }, { claudeBusy = it }) { SettingsConnections.disconnectClaude(graph, context) }

    private fun connectGitHub() = busy({ githubBusy }, { githubBusy = it }) { SettingsConnections.connectGitHub(graph, context) }

    private fun disconnectGitHub() = busy({ githubBusy }, { githubBusy = it }) { SettingsConnections.disconnectGitHub(graph, context) }

    private fun busy(get: () -> Boolean, set: (Boolean) -> Unit, action: suspend () -> Unit) {
        if (get()) return
        set(true)
        rebuild()
        scope.launch {
            action()
            set(false)
            rebuild()
        }
    }

    // About

    /** Ten taps toggle developer mode. The Developer row it reveals is not ported, but the switch is kept so it means the same in both apps. */
    private fun versionTapped() {
        if (!versionClicks.click(SystemClock.elapsedRealtime())) return
        val enabled = !graph.localSettings.values.value.devModeEnabled
        graph.localSettings.change { it.copy(devModeEnabled = enabled) }
        Alerts.show(
            context,
            string(R.string.modals_developer_mode),
            string(if (enabled) R.string.modals_developer_mode_enabled else R.string.modals_developer_mode_disabled),
        )
    }

    companion object {
        const val LINK_GITHUB = "https://github.com/slopus/happy"
        const val LINK_ISSUES = "https://github.com/slopus/happy/issues"
        const val LINK_PRIVACY = "https://happy.engineering/privacy/"
        const val LINK_TERMS = "https://happy.engineering/terms/"
    }
}
