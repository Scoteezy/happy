package com.slopus.happy.ui.stemcells

import com.slopus.happy.AppGraph
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.slopus.happy.images.HappyImageLoader
import com.slopus.happy.BuildConfig
import com.slopus.happy.config.ThemePreference
import com.slopus.happy.model.Profile
import com.slopus.happy.model.settings.SettingsIndexModel
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.onboarding.WelcomeScreen
import com.slopus.happy.ui.settings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Real settings actions, scoped to the selected account, never the fixture host. */
class AppStemCellsSettingsHost(private val graph: AppGraph, private val scope: CoroutineScope, private val context: Context) : StemCellsSettingsHost {
    private val sessions = HashMap<String, StemCellsSession>()
    private var profile: Profile? = null
    private val pictureKeys = HashMap<String, Any?>()
    private val pictures = HashMap<String, Bitmap?>()

    override val profiles: List<StemCellsSettingsHost.Profile>
        get() = (if (graph.stemCells.showsComputers) graph.direct.state.value.accounts.sortedBy { it.addedAt }.map {
            StemCellsSettingsHost.Profile(it.id, it.name, it.status.name, true, graph.shellAccount.value?.directId == it.id, pictures[it.id])
        } else emptyList()) + graph.auth.registry.value.ordered.map {
            StemCellsSettingsHost.Profile(it.id, it.display?.name ?: "Happy", it.display?.username?.let { name -> "@$name" } ?: "", false,
                graph.shellAccount.value?.directId == null && graph.auth.registry.value.activeId == it.id, pictures[it.id])
        }

    override fun watch(changed: () -> Unit): () -> Unit {
        val job = scope.launch {
            combine(graph.direct.state, graph.auth.registry, graph.shellAccount, graph.profiles()) { _, _, shell, profile -> shell to profile }
                .collectLatest { (shell, currentProfile) ->
                    profile = currentProfile
                    val ids = graph.direct.state.value.accounts.map { it.id }.toSet()
                    (sessions.keys - ids).forEach { sessions.remove(it); StemCellsSession.forget(it) }
                    val allIds = ids + graph.auth.registry.value.entries.map { it.id }
                    (pictureKeys.keys - allIds).forEach { pictureKeys.remove(it); pictures.remove(it) }
                    shell?.directId?.let { id ->
                        val engine = graph.direct.syncEngine(id)
                        // Selection/removal may change while the confined engine
                        // request is pending. Never reinsert a forgotten session.
                        if (engine != null && graph.shellAccount.value?.directId == id && graph.direct.state.value.accounts.any { it.id == id }) {
                            sessions[id] = StemCellsSession.session(id, engine)
                        }
                    }
                    changed()
                    fun picture(id: String, key: Any?, load: suspend () -> Bitmap?) {
                        if (pictureKeys.containsKey(id) && pictureKeys[id] == key) return
                        pictureKeys[id] = key
                        pictures.remove(id)
                        if (key == null) return
                        launch {
                            val image = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }
                            if (pictureKeys[id] == key) { pictures[id] = image; changed() }
                        }
                    }
                    graph.direct.state.value.accounts.forEach { account ->
                        val bytes = account.avatar
                        picture(account.id, bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                    }
                    graph.auth.registry.value.entries.forEach { account ->
                        val url = account.display?.avatarUrl
                        picture(account.id, url) {
                            url?.let { HappyImageLoader.get(context).execute(ImageRequest.Builder(context).data(it).allowHardware(false).build()).image?.toBitmap() }
                        }
                    }
                }
        }
        return { job.cancel() }
    }

    override fun session(id: String) = sessions[id]
    override fun connections(id: String): StemCellsSettingsHost.Connections {
        if (graph.auth.registry.value.activeId != id) return StemCellsSettingsHost.Connections()
        val model = SettingsIndexModel(profile)
        return StemCellsSettingsHost.Connections(model.claudeConnected, model.githubLogin)
    }

    override suspend fun switchTo(id: String) {
        if (graph.direct.state.value.accounts.any { it.id == id }) graph.selectDirect(id) else graph.switchAccount(id)
    }
    override suspend fun rename(id: String, name: String) = graph.direct.rename(id, name)
    override suspend fun remove(id: String) {
        if (graph.direct.state.value.accounts.any { it.id == id }) {
            graph.removeDirect(id)
            StemCellsSession.forget(id)
        } else graph.signOut(id).join()
    }
    override fun addComputer(from: Screen) {
        graph.stemCells.directConnection = true
        from.push(StemCellsLinkScreen(from.context, graph))
    }
    override fun addHappyAccount(from: Screen) {
        graph.beginAddingAccount()
        from.push(WelcomeScreen(from.context, graph, addingAccount = true))
    }
    override fun open(page: StemCellsSettingsHost.Page, id: String, from: Screen) {
        scope.launch {
            SettingsConnections.attempt(from.context) {
                if (page != StemCellsSettingsHost.Page.WhatsNew) graph.switchAccount(id)
                from.push(when (page) {
                    StemCellsSettingsHost.Page.Account -> AccountScreen(from.context, graph)
                    StemCellsSettingsHost.Page.Appearance -> AppearanceScreen(from.context, graph)
                    StemCellsSettingsHost.Page.Language -> LanguageScreen(from.context, graph)
                    StemCellsSettingsHost.Page.Voice -> VoiceSettingsScreen(from.context, graph)
                    StemCellsSettingsHost.Page.Agents -> AgentsScreen(from.context, graph)
                    StemCellsSettingsHost.Page.ConnectClaude -> ConnectClaudeScreen(from.context)
                    StemCellsSettingsHost.Page.SupportUs -> SupportUsScreen(from.context)
                    StemCellsSettingsHost.Page.WhatsNew -> ChangelogScreen(from.context, graph)
                })
            }
        }
    }
    override fun connect(connection: StemCellsSettingsHost.Connection, connected: Boolean, id: String, from: Screen) {
        scope.launch {
            SettingsConnections.attempt(from.context) {
                graph.switchAccount(id)
                when (connection) {
                    StemCellsSettingsHost.Connection.Claude -> if (connected) SettingsConnections.disconnectClaude(graph, from.context) else from.push(ConnectClaudeScreen(from.context))
                    StemCellsSettingsHost.Connection.GitHub -> if (connected) SettingsConnections.disconnectGitHub(graph, from.context) else SettingsConnections.connectGitHub(graph, from.context)
                    StemCellsSettingsHost.Connection.ScanTerminal -> scan(from)
                    StemCellsSettingsHost.Connection.EnterTerminal -> enterLink(from)
                }
            }
        }
    }
    override fun scan(from: Screen) = DirectPairingFlow.scan(graph, from)
    override fun enterLink(from: Screen) = DirectPairingFlow.enterLink(graph, from)
    override var directConnection: Boolean
        get() = graph.stemCells.directConnection
        set(value) {
            graph.stemCells.directConnection = value
            scope.launch {
                if (!graph.stemCells.showsComputers) graph.direct.select(null)
                else if (graph.auth.registry.value.activeId == null) graph.direct.state.value.accounts.firstOrNull()?.let { graph.selectDirect(it.id) }
            }
        }
    override var theme: ThemePreference
        get() = graph.localSettings.themePreference.value
        set(value) = graph.localSettings.setThemePreference(value)
    override val version: String get() = "${BuildConfig.VERSION_NAME} / ${BuildConfig.BUILD_TYPE}"
    override fun toggleDeveloperMode(): Boolean {
        val enabled = !graph.localSettings.values.value.devModeEnabled
        graph.localSettings.change { it.copy(devModeEnabled = enabled) }
        return enabled
    }
}