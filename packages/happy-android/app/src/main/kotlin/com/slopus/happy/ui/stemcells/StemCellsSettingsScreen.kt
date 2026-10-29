package com.slopus.happy.ui.stemcells

import android.content.Context
import android.graphics.Bitmap
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import com.slopus.happy.R
import com.slopus.happy.config.ThemePreference
import com.slopus.happy.direct.DirectSettings
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.stemcells.StemCellsSettingsLayout
import com.slopus.happy.model.settings.MultiClickCounter
import com.slopus.happy.stories.StoriesSnapshot
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.TextField
import com.slopus.happy.ui.nav.BackInterceptor
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.settings.SettingsScreen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.BounceScrollView
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * What Stem Cells settings reads and asks of the app around it: every
 * profile (paired computers, then Happy accounts), switching between them,
 * and the pages and flows that live elsewhere. The app's account shell
 * answers it; the harness answers it with its fake computer.
 */
interface StemCellsSettingsHost {
    data class Profile(
        val id: String,
        val name: String,
        /** A computer's status, an account's @username. */
        val subtitle: String,
        val isDirect: Boolean,
        val isActive: Boolean,
        val picture: Bitmap? = null,
    )

    /** A Happy account's connections, for its page. */
    data class Connections(val claudeConnected: Boolean = false, val githubLogin: String? = null)

    /** A Happy account's own pages, and the flows its rows start. */
    enum class Page { Account, Appearance, Language, Voice, Agents, ConnectClaude, SupportUs, WhatsNew }

    enum class Connection { Claude, GitHub, ScanTerminal, EnterTerminal }

    /** Computers first, then Happy accounts, each in the order added. */
    val profiles: List<Profile>
    /** Calls `changed` whenever the profiles change; the result stops it. */
    fun watch(changed: () -> Unit): () -> Unit
    /** A computer's Stem Cells, for its page; null for an account or one not running. */
    fun session(id: String): StemCellsSession?
    fun connections(id: String): Connections
    suspend fun switchTo(id: String)
    suspend fun rename(id: String, name: String)
    suspend fun remove(id: String)
    fun addComputer(from: Screen)
    fun addHappyAccount(from: Screen)
    fun open(page: Page, id: String, from: Screen)
    fun connect(connection: Connection, connected: Boolean, id: String, from: Screen)
    /** The QR sheet's two ways in. */
    fun scan(from: Screen)
    fun enterLink(from: Screen)
    var directConnection: Boolean
    var theme: ThemePreference
    /** "1.2.3 / development". */
    val version: String
    /** Ten taps on Version: developer mode turned the other way; what it is now. */
    fun toggleDeveloperMode(): Boolean
}

/**
 * Stem Cells settings (happy-ios `StemCellsSettingsViewController`), laid
 * out as Telegram's settings are (its techniques, none of its code): the
 * glass bar with back at the left, and search then the QR button in one
 * capsule at the right; every computer and account as story circles, the
 * chosen one centred and ringed, its name and line under it; then every
 * setting the chosen profile has, in grouped cards. Swiping a profile to
 * the middle makes it the active one; tapping its circle does too and goes
 * back to its home. "+" adds a computer.
 *
 * A computer's page: its name (renamed in place) and status; Hide
 * projects, Stories where the computer offers them, and the Direct
 * connection experiment; the theme; adding a computer or a Happy account;
 * About; Disconnect. A Happy account's: its own pages, its connected
 * services, connecting a terminal, Direct connection, Support us, adding;
 * About; Sign out. Apple's EULA row stays on iOS, as the classic settings
 * leave it.
 */
class StemCellsSettingsScreen(
    context: Context,
    private val host: StemCellsSettingsHost,
    computer: String?,
) : Screen(context), BackInterceptor {
    private var shownId: String? = computer
    private var profiles: List<StemCellsSettingsHost.Profile> = emptyList()
    private lateinit var root: Root
    private val bar = StemCellsGlassBarView(context)
    private val scroll = BounceScrollView(context)
    private val carousel = StemCellsProfileCarouselView(context)
    private val name = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.Medium, 28.0), StemCellsPalette.stemCellsSettingsText, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER).apply { followsTextSize = false }
    private val subtitle = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 17.0), StemCellsPalette.stemCellsSettingsSecondary, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER).apply { followsTextSize = false }
    private val empty = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 17.0), StemCellsPalette.stemCellsSettingsSecondary, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER).apply { followsTextSize = false }
    private var nameField: TextField? = null
    private var fieldOwner: String? = null
    /** A rename sent, shown until the computer says the same. */
    private var heldName: Pair<String, String>? = null
    private val cards = ArrayList<StemCellsSettingsCardView>()
    private var rows: List<List<StemCellsSettingsRow>> = emptyList()
    private var query: String? = null
    /** "Nothing found": a search that found nothing. Kept here, as the label's own visibility is the frames' to set. */
    private var showsEmpty = false
    private var projectsHidden = false
    private var stories: StoriesSnapshot.Availability? = null
    private var watchingShown: List<() -> Unit> = emptyList()
    private var unwatch: (() -> Unit)? = null
    private var busy = false
    private val versionClicks = MultiClickCounter()
    private var switching: Job? = null
    /** The profile last chosen, which the switches in line end on. */
    private var wanted: String? = null
    private var qr: StemCellsQRSheet? = null

    private val shown: StemCellsSettingsHost.Profile? get() = profiles.firstOrNull { it.id == shownId }

    /** What the harness reads. */
    val probe: String
        get() = "settings=${shown?.name} profile=${profiles.indexOfFirst { it.id == shownId }}/${profiles.size} " +
            "cards=${rows.size} rows=${rows.sumOf { it.size }} searching=${if (query != null) 1 else 0} " +
            "renaming=${if (nameField != null) 1 else 0} qr=${if (qr != null) 1 else 0} theme=${host.theme.name}"

    override fun createView(): View {
        root = Root(context)
        root.tag = "stem-settings"
        root.addView(scroll)
        carousel.onSelect = { choose(it) }
        carousel.onTap = { choose(it, closing = true) }
        carousel.onAdd = { addComputer() }
        scroll.content.addView(carousel)
        name.tag = "stem-settings-name"
        name.isClickable = true
        name.setOnClickListener { beginRename() }
        scroll.content.addView(name)
        scroll.content.addView(subtitle)
        empty.text = context.getString(R.string.stem_nothing_found)
        empty.visibility = View.GONE
        scroll.content.addView(empty)
        scroll.listener = object : BounceScrollView.Listener {
            override fun willBeginDragging() {
                // As iOS's interactive keyboard dismissal: a scroll puts the keyboard away.
                if (query != null) bar.searchField.dismissKeyboard()
            }
        }
        setUpBar()
        root.addView(bar)
        unwatch = host.watch { reload() }
        reload()
        root.applyTheme()
        return root
    }

    override fun onDestroy() {
        unwatch?.invoke()
        for (stop in watchingShown) stop()
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        if (::root.isInitialized) root.requestLayout()
    }

    // Bar

    /** Back; no title, as Telegram's has none; search, then the QR button at the far right, in one capsule. */
    private fun setUpBar() {
        bar.hasBack = true
        bar.onBack = { pop() }
        bar.actions = listOf(
            StemCellsGlassBarView.Action(StemCellsGlyphs.icon("search"), context.getString(R.string.stem_search_settings), "stem-settings-search") { beginSearch() },
            StemCellsGlassBarView.Action(StemCellsGlyphs.icon("qr-code-outline"), context.getString(R.string.stem_show_qr), "stem-settings-qr") { showQR() },
        )
        bar.searchField.hint = context.getString(R.string.stem_search_settings)
        bar.searchField.tag = "stem-settings-search-field"
        bar.onSearchText = { text ->
            if (query != null) {
                query = text
                rebuild(animated = false)
            }
        }
        bar.onEndSearch = { endSearch() }
    }

    // Profiles

    private fun reload() {
        profiles = host.profiles.map { profile ->
            val held = heldName
            if (held != null && held.first == profile.id) profile.copy(name = held.second) else profile
        }
        val held = heldName
        if (held != null && host.profiles.firstOrNull { it.id == held.first }?.name == held.second) heldName = null
        if (profiles.isEmpty()) return
        if (shown == null) shownId = (profiles.firstOrNull { it.isActive } ?: profiles[0]).id
        watchShown()
        showCarousel()
        rebuild(animated = false)
    }

    private fun showCarousel() {
        val items = profiles.map { StemCellsProfileCarouselView.Item(it.id, it.name, it.picture, it.isDirect) }
        val selected = profiles.indexOfFirst { it.id == shownId }.coerceAtLeast(0)
        if (carousel.items != items || carousel.selected != selected) carousel.set(items, selected)
    }

    private var watchedId: String? = null

    /** The shown computer's settings and Stories, while its page shows. */
    private fun watchShown() {
        val profile = shown ?: return
        if (watchedId == profile.id) return
        watchedId = profile.id
        for (stop in watchingShown) stop()
        watchingShown = emptyList()
        projectsHidden = false
        stories = null
        if (!profile.isDirect) return
        val session = host.session(profile.id) ?: return
        val settings = scope.launch {
            session.engine.updates.collect { snapshot ->
                val hidden = DirectSettings.projectsHidden(snapshot.settings)
                if (hidden == projectsHidden) return@collect
                projectsHidden = hidden
                rebuild(animated = false)
            }
        }
        val storiesJob = session.stories.watch { snapshot ->
            if (stories == snapshot.availability) return@watch
            stories = snapshot.availability
            rebuild(animated = false)
        }
        watchingShown = listOf({ settings.cancel() }, { storiesJob.cancel() })
    }

    /**
     * The circle that came to rest, or was tapped: the page turns to it and
     * it becomes the active profile. `closing` (a tap) then goes back to
     * that home.
     */
    private fun choose(index: Int, closing: Boolean = false) {
        val profile = profiles.getOrNull(index) ?: return
        endEditingName(keep = true)
        if (shownId != profile.id) {
            shownId = profile.id
            watchShown()
            rebuild(animated = true)
        }
        // One switch at a time, and only the last choice still wanted; a
        // failed one turns the page back to the profile that is active.
        val previous = switching
        wanted = profile.id
        switching = scope.launch {
            previous?.join()
            if (wanted != profile.id) return@launch
            if (host.profiles.firstOrNull { it.id == profile.id }?.isActive == false) {
                try {
                    host.switchTo(profile.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (shownId != profile.id) return@launch
                    shownId = host.profiles.firstOrNull { it.isActive }?.id
                    reload()
                    Alerts.show(context, context.getString(R.string.stem_could_not_switch), e.message)
                    return@launch
                }
            }
            if (closing && wanted == profile.id && stack?.top === this@StemCellsSettingsScreen) pop()
        }
    }

    private fun addComputer() {
        endEditingName(keep = true)
        host.addComputer(this)
    }

    // Rows

    private fun rowsFor(profile: StemCellsSettingsHost.Profile): List<List<StemCellsSettingsRow>> {
        val id = profile.id
        fun string(id: Int) = context.getString(id)
        val adding = listOf(
            StemCellsSettingsRow("add-computer", string(R.string.stem_add_computer), "laptop-outline" to StemCellsSettingsRow.Tile.Blue, action = { addComputer() }),
            StemCellsSettingsRow("add-account", string(R.string.stem_add_happy_account), "person-add-outline" to StemCellsSettingsRow.Tile.Gray, action = { host.addHappyAccount(this) }),
        )
        if (profile.isDirect) {
            // Home follows desktop's account setting; there is no layout selector here.
            val experiments = mutableListOf(
                StemCellsSettingsRow("hide-projects", string(R.string.stem_hide_projects), "folder-outline" to StemCellsSettingsRow.Tile.Orange,
                    accessory = StemCellsSettingsLayout.Accessory.Toggle, isOn = projectsHidden, toggled = { on -> setProjectsHidden(id, on) }),
            )
            // Stories: only where the computer offers them.
            if (stories == StoriesSnapshot.Availability.On || stories == StoriesSnapshot.Availability.Off) {
                experiments += StemCellsSettingsRow("stories", string(R.string.stories_setting), "sparkles-outline" to StemCellsSettingsRow.Tile.Blue,
                    accessory = StemCellsSettingsLayout.Accessory.Toggle, isOn = stories == StoriesSnapshot.Availability.On, toggled = { setStories(id, it) })
            }
            experiments += directConnectionRow()
            return listOf(
                listOf(
                    StemCellsSettingsRow("edit-name", string(R.string.stem_edit_name), "create-outline" to StemCellsSettingsRow.Tile.Blue, action = { beginRename() }),
                    StemCellsSettingsRow("status", string(R.string.stem_status), "desktop-outline" to StemCellsSettingsRow.Tile.Green, value = profile.subtitle, accessory = StemCellsSettingsLayout.Accessory.None),
                ),
                experiments,
                listOf(themeRow()),
                adding,
                aboutRows(),
                listOf(StemCellsSettingsRow("disconnect", string(R.string.stem_disconnect), accessory = StemCellsSettingsLayout.Accessory.None, destructive = true, action = { remove(id, direct = true) })),
            )
        }
        val connections = host.connections(id)
        fun page(page: StemCellsSettingsHost.Page) = { host.open(page, id, this) }
        return listOf(
            listOf(
                StemCellsSettingsRow("account", string(R.string.settings_account), "person-circle-outline" to StemCellsSettingsRow.Tile.Blue, action = page(StemCellsSettingsHost.Page.Account)),
                StemCellsSettingsRow("appearance", string(R.string.settings_appearance), "color-palette-outline" to StemCellsSettingsRow.Tile.Purple, action = page(StemCellsSettingsHost.Page.Appearance)),
                StemCellsSettingsRow("language", string(R.string.stem_language), "language-outline" to StemCellsSettingsRow.Tile.Green, action = page(StemCellsSettingsHost.Page.Language)),
                StemCellsSettingsRow("voice", string(R.string.stem_voice), "mic-outline" to StemCellsSettingsRow.Tile.Orange, action = page(StemCellsSettingsHost.Page.Voice)),
                StemCellsSettingsRow("agents", string(R.string.settings_agents), "options-outline" to StemCellsSettingsRow.Tile.Teal, action = page(StemCellsSettingsHost.Page.Agents)),
            ),
            listOf(
                StemCellsSettingsRow("claude", string(R.string.settings_claude_code), "code-slash-outline" to StemCellsSettingsRow.Tile.Orange,
                    value = string(if (connections.claudeConnected) R.string.stem_connected else R.string.stem_connect), accessory = StemCellsSettingsLayout.Accessory.None,
                    action = { host.connect(StemCellsSettingsHost.Connection.Claude, connections.claudeConnected, id, this) }),
                StemCellsSettingsRow("github", string(R.string.settings_github), "logo-github" to StemCellsSettingsRow.Tile.Gray,
                    value = connections.githubLogin?.let { "@$it" } ?: string(R.string.stem_connect), accessory = StemCellsSettingsLayout.Accessory.None,
                    action = { host.connect(StemCellsSettingsHost.Connection.GitHub, connections.githubLogin != null, id, this) }),
            ),
            listOf(
                StemCellsSettingsRow("scan-terminal", string(R.string.stem_connect_terminal), "qr-code-outline" to StemCellsSettingsRow.Tile.Indigo, accessory = StemCellsSettingsLayout.Accessory.None,
                    action = { host.connect(StemCellsSettingsHost.Connection.ScanTerminal, false, id, this) }),
                StemCellsSettingsRow("enter-terminal", string(R.string.stem_enter_terminal_url), "link-outline" to StemCellsSettingsRow.Tile.Indigo, accessory = StemCellsSettingsLayout.Accessory.None,
                    action = { host.connect(StemCellsSettingsHost.Connection.EnterTerminal, false, id, this) }),
            ),
            listOf(directConnectionRow()),
            listOf(StemCellsSettingsRow("support-us", string(R.string.settings_support_us), "heart-outline" to StemCellsSettingsRow.Tile.Red, action = page(StemCellsSettingsHost.Page.SupportUs))),
            adding,
            aboutRows(),
            listOf(StemCellsSettingsRow("sign-out", string(R.string.stem_sign_out), accessory = StemCellsSettingsLayout.Accessory.None, destructive = true, action = { remove(id, direct = false) })),
        )
    }

    /** The Direct connection experiment, on this phone. */
    private fun directConnectionRow() = StemCellsSettingsRow(
        "direct-connection", context.getString(R.string.stem_direct_connection), "flask-outline" to StemCellsSettingsRow.Tile.Purple,
        accessory = StemCellsSettingsLayout.Accessory.Toggle, isOn = host.directConnection, toggled = { host.directConnection = it },
    )

    /** The theme on this phone: a tap goes Adaptive, Light, Dark. */
    private fun themeRow() = StemCellsSettingsRow(
        "theme", context.getString(R.string.settings_appearance_theme), "contrast-outline" to StemCellsSettingsRow.Tile.Indigo,
        value = themeName(host.theme), accessory = StemCellsSettingsLayout.Accessory.None, action = { cycleTheme() },
    )

    private fun aboutRows(): List<StemCellsSettingsRow> {
        fun string(id: Int) = context.getString(id)
        fun link(url: String) = { Alerts.open(context, url) }
        return listOf(
            StemCellsSettingsRow("whats-new", string(R.string.settings_whats_new), "sparkles-outline" to StemCellsSettingsRow.Tile.Orange,
                action = { host.open(StemCellsSettingsHost.Page.WhatsNew, shownId ?: "", this) }),
            StemCellsSettingsRow("github-repo", string(R.string.settings_github), "logo-github" to StemCellsSettingsRow.Tile.Gray, value = string(R.string.links_github_detail), action = link(SettingsScreen.LINK_GITHUB)),
            StemCellsSettingsRow("report-issue", string(R.string.settings_report_issue), "bug-outline" to StemCellsSettingsRow.Tile.Red, action = link(SettingsScreen.LINK_ISSUES)),
            StemCellsSettingsRow("privacy", string(R.string.settings_privacy_policy), "shield-checkmark-outline" to StemCellsSettingsRow.Tile.Blue, action = link(SettingsScreen.LINK_PRIVACY)),
            StemCellsSettingsRow("terms", string(R.string.settings_terms_of_service), "document-text-outline" to StemCellsSettingsRow.Tile.Gray, action = link(SettingsScreen.LINK_TERMS)),
            StemCellsSettingsRow("version", string(R.string.common_version), "information-circle-outline" to StemCellsSettingsRow.Tile.Gray, value = host.version,
                accessory = StemCellsSettingsLayout.Accessory.None, action = { versionTapped() }),
        )
    }

    /** What search looks through: the profiles, and the shown page's rows. */
    private fun searchRows(query: String): List<StemCellsSettingsRow> {
        val found = ArrayList<StemCellsSettingsRow>()
        for ((index, profile) in profiles.withIndex()) {
            if (!profile.name.contains(query, ignoreCase = true)) continue
            found += StemCellsSettingsRow(
                "profile:${profile.id}", profile.name, (if (profile.isDirect) "desktop-outline" else "person-outline") to StemCellsSettingsRow.Tile.Blue,
                value = context.getString(if (profile.isDirect) R.string.stem_direct_computer else R.string.stem_happy_account),
                action = {
                    endSearch()
                    carousel.set(carousel.items, index)
                    choose(index)
                },
            )
        }
        shown?.let { profile -> found += rowsFor(profile).flatten().filter { it.title.contains(query, ignoreCase = true) && !it.destructive } }
        return found
    }

    /** Fades the cards in, and crosses the name and line, as iOS's 0.2 s transitions do. */
    private fun rebuild(animated: Boolean) {
        if (!::root.isInitialized) return
        val profile = shown ?: return
        val query = query
        rows = when {
            query != null && query.isNotEmpty() -> searchRows(query).let { if (it.isEmpty()) emptyList() else listOf(it) }
            query != null -> emptyList()
            else -> rowsFor(profile)
        }
        // A row tapped keeps a name being typed first.
        rows = rows.map { card ->
            card.map { row ->
                val action = row.action ?: return@map row
                row.copy(action = {
                    endEditingName(keep = true)
                    action()
                })
            }
        }
        val searching = query != null
        carousel.visibility = if (searching) View.GONE else View.VISIBLE
        name.visibility = if (searching || nameField != null) View.GONE else View.VISIBLE
        subtitle.visibility = if (searching) View.GONE else View.VISIBLE
        showsEmpty = searching && !query.isNullOrEmpty() && rows.isEmpty()
        val text = heldName?.takeIf { it.first == profile.id }?.second ?: profile.name
        cross(name, text, animated)
        cross(subtitle, profile.subtitle, animated)
        name.contentDescription = text
        while (cards.size < rows.size) StemCellsSettingsCardView(context).also { scroll.content.addView(it); cards += it }
        while (cards.size > rows.size) cards.removeAt(cards.size - 1).also {
            scroll.content.frames.remove(it)
            scroll.content.removeView(it)
        }
        if (animated) for (card in cards) {
            card.alpha = 0f
            card.animate().alpha(1f).setDuration(200).start()
        }
        root.requestLayout()
    }

    private fun cross(label: Label, value: String, animated: Boolean) {
        if (label.text.toString() == value) return
        if (!animated) {
            label.text = value
            return
        }
        label.animate().cancel()
        label.animate().alpha(0f).setDuration(100).withEndAction {
            label.text = value
            label.animate().alpha(1f).setDuration(100).start()
        }.start()
    }

    // Search

    /** A field takes the bar's row, as Telegram's does. */
    private fun beginSearch() {
        endEditingName(keep = true)
        query = ""
        bar.searchField.setText("")
        rebuild(animated = false)
        bar.isSearching = true
        bar.searchField.focus()
    }

    private fun endSearch() {
        query = null
        bar.searchField.dismissKeyboard()
        rebuild(animated = false)
        bar.isSearching = false
    }

    // Rename

    /** The name, in place, as a field; Done or a tap away keeps it. */
    private fun beginRename() {
        val profile = shown ?: return
        if (nameField != null || query != null || !profile.isDirect) return
        val field = TextField(context, name.style, StemCellsPalette.stemCellsSettingsText)
        field.gravity = android.view.Gravity.CENTER
        field.isSingleLine = true
        field.imeOptions = EditorInfo.IME_ACTION_DONE
        field.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS
        field.tag = "stem-settings-rename"
        field.setText(name.text)
        field.onReturn = { finishRename(field.string) }
        scroll.content.addView(field)
        nameField = field
        fieldOwner = profile.id
        name.visibility = View.GONE
        root.requestLayout()
        field.focus()
        field.selectAll()
    }

    private fun endEditingName(keep: Boolean) {
        val field = nameField ?: return
        finishRename(if (keep) field.string else null)
    }

    private fun finishRename(kept: String?) {
        val field = nameField ?: return
        val id = fieldOwner ?: return
        nameField = null
        fieldOwner = null
        field.onReturn = null
        field.dismissKeyboard()
        scroll.content.frames.remove(field)
        scroll.content.removeView(field)
        name.visibility = if (query != null) View.GONE else View.VISIBLE
        root.requestLayout()
        val previous = profiles.firstOrNull { it.id == id }?.name
        val title = kept?.trim().orEmpty()
        if (title.isEmpty() || title == previous) return
        // Shown at once and kept until the computer says the same: no flicker back.
        heldName = id to title
        if (shownId == id) name.text = title
        StemCellsHaptics.play(root, StemCellsHaptics.Moment.Renamed)
        scope.launch {
            try {
                host.rename(id, title)
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                heldName = null
                reload()
                Alerts.show(context, context.getString(R.string.stem_could_not_rename), e.message)
            }
        }
    }

    // Acts

    private fun setProjectsHidden(id: String, on: Boolean) {
        projectsHidden = on
        val session = host.session(id) ?: return
        scope.launch { runCatching { session.engine.setProjectsHidden(on) } }
    }

    private fun setStories(id: String, on: Boolean) {
        val session = host.session(id) ?: return
        stories = if (on) StoriesSnapshot.Availability.On else StoriesSnapshot.Availability.Off
        session.stories.store.setEnabled(on)
    }

    /** Adaptive, Light, Dark, and round again, as the Appearance page does. */
    private fun cycleTheme() {
        host.theme = when (host.theme) {
            ThemePreference.Adaptive -> ThemePreference.Light
            ThemePreference.Light -> ThemePreference.Dark
            ThemePreference.Dark -> ThemePreference.Adaptive
        }
        rebuild(animated = false)
    }

    private fun themeName(theme: ThemePreference) = context.getString(
        when (theme) {
            ThemePreference.Adaptive -> R.string.settings_appearance_theme_options_adaptive
            ThemePreference.Light -> R.string.settings_appearance_theme_options_light
            ThemePreference.Dark -> R.string.settings_appearance_theme_options_dark
        },
    )

    /** Ten taps toggle developer mode, as on Happy's settings. */
    private fun versionTapped() {
        if (!versionClicks.click(System.currentTimeMillis())) return
        val enabled = host.toggleDeveloperMode()
        Alerts.show(
            context, context.getString(R.string.modals_developer_mode),
            context.getString(if (enabled) R.string.modals_developer_mode_enabled else R.string.modals_developer_mode_disabled),
        )
    }

    private fun showQR() {
        val profile = shown ?: return
        endEditingName(keep = true)
        if (qr != null) return
        val sheet = StemCellsQRSheet(context, profile.name, profile.picture, profile.isDirect)
        sheet.onScan = { host.scan(this) }
        sheet.onEnterLink = { host.enterLink(this) }
        sheet.onGone = {
            qr = null
            root.blurBehind(false)
            stack?.backStateChanged()
        }
        qr = sheet
        sheet.safeArea = safeArea
        sheet.translationZ = 64 * context.resources.displayMetrics.density
        root.addView(sheet)
        sheet.setFrame(Rect(0.0, 0.0, px(root.width), px(root.height)))
        root.blurBehind(true)
        sheet.present()
        stack?.backStateChanged()
    }

    private fun remove(id: String, direct: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val confirmed = Alerts.confirm(
                    context, context.getString(if (direct) R.string.stem_disconnect_title else R.string.stem_sign_out_title),
                    context.getString(R.string.stem_remove_detail),
                    confirmTitle = context.getString(if (direct) R.string.stem_disconnect else R.string.stem_sign_out), destructive = true,
                )
                if (!confirmed) return@launch
                host.remove(id)
                StemCellsHaptics.play(root, StemCellsHaptics.Moment.PutAway)
                shownId = null
                watchedId = null
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Alerts.show(context, context.getString(R.string.stem_could_not_remove), e.message)
            } finally {
                busy = false
            }
        }
    }

    override val interceptsBack: Boolean get() = qr != null || query != null

    override fun onBackPressed(): Boolean {
        qr?.let {
            it.dismiss()
            return true
        }
        if (query != null) {
            endSearch()
            return true
        }
        return false
    }

    // Layout

    private inner class Root(context: Context) : ManualLayout(context), ThemeAware {
        init {
            // Where focus goes when a field lets it go, so its cursor goes too.
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
        }

        /** What is under the QR sheet, blurred while it is up (Android 12 and later). */
        fun blurBehind(on: Boolean) {
            if (android.os.Build.VERSION.SDK_INT < 31) return
            val effect = if (on) android.graphics.RenderEffect.createBlurEffect(dp(60f), dp(60f), android.graphics.Shader.TileMode.CLAMP) else null
            scroll.setRenderEffect(effect)
            bar.setRenderEffect(effect)
        }

        private fun dp(value: Float) = value * resources.displayMetrics.density

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            // A tap away from the field keeps what it says; the tap goes on.
            val field = nameField
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && field != null) {
                val at = IntArray(2).also { field.getLocationOnScreen(it) }
                val inside = ev.rawX >= at[0] && ev.rawX < at[0] + field.width && ev.rawY >= at[1] && ev.rawY < at[1] + field.height
                if (!inside) endEditingName(keep = true)
            }
            return false
        }

        override fun layoutSubviews(width: Double, height: Double) {
            bar.safeTop = safeArea.top
            bar.setFrame(Rect(0.0, 0.0, width, height))
            val contentTop = bar.layout.contentTop
            val atTop = scroll.contentOffset <= -scroll.insetTop
            scroll.setInsets(contentTop, safeArea.bottom + keyboard)
            if (atTop) scroll.setContentOffset(-contentTop)
            scroll.setFrame(Rect(0.0, 0.0, width, height))
            val searching = query != null
            val specs = rows.map { card ->
                card.map { StemCellsSettingsLayout.RowSpec(it.icon != null, StemCellsSettingsCardView.valueWidth(it.value), it.accessory) }
            }
            val layout = StemCellsSettingsLayout(width, ceil(name.lineHeight), ceil(subtitle.lineHeight), specs, searching)
            val frames = scroll.content.frames
            frames[carousel] = if (searching) null else layout.strip
            frames[name] = if (searching || nameField != null) null else layout.name
            nameField?.let { frames[it] = layout.name }
            frames[subtitle] = if (searching) null else layout.subtitle
            frames[empty] = if (showsEmpty) layout.empty else null
            for ((index, card) in cards.withIndex()) {
                val placed = layout.cards.getOrNull(index)
                frames[card] = placed?.frame
                if (placed != null) card.show(rows[index], placed)
            }
            // "Nothing found" sits below a search's empty content, where UIKit draws it and Android would clip it.
            val drawn = maxOf(layout.contentHeight, if (showsEmpty) layout.empty.maxY else 0.0)
            scroll.contentHeight = drawn
            scroll.content.setFrame(Rect(0.0, 0.0, width, drawn))
            scroll.content.layoutSubviews(width, drawn)
            qr?.let {
                it.safeArea = safeArea
                it.setFrame(Rect(0.0, 0.0, width, height))
            }
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(StemCellsPalette.stemCellsSettingsGround))
            bar.applyTheme()
        }
    }
}
