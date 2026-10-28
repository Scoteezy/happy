package com.slopus.happy.home

import android.content.Context
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.model.Machine
import com.slopus.happy.model.NewSessionLabels
import com.slopus.happy.model.Session
import com.slopus.happy.projects.GitStatusServices
import com.slopus.happy.projects.ProjectServices
import com.slopus.happy.projects.SessionAvatarServices
import com.slopus.happy.sync.AccountSync
import com.slopus.happy.sync.MachinePresence
import com.slopus.happy.sync.SessionPresence
import com.slopus.happy.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * Home's long-lived state, one per [AppGraph] (happy-ios `AccountSession`'s
 * home feed and its derivation): the snapshot of sessions, machines and
 * projects with presence on top, the list derived from it off the main
 * thread and kept warm between visits, unread chats and archives in flight.
 */
class HomeServices private constructor(private val graph: AppGraph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sessionActions: SessionActions = LiveSessionActions { graph.account.value?.sessionOps }
    val archiver = SessionArchiver(sessionActions)

    @OptIn(ExperimentalCoroutinesApi::class)
    val snapshots: StateFlow<HomeSnapshot?> = graph.account
        .flatMapLatest { account ->
            homeSnapshots(
                graph.store,
                account?.presence?.sessions ?: flowOf(emptyMap<String, SessionPresence>()),
                account?.presence?.machines ?: flowOf(emptyMap<String, MachinePresence>()),
                // A full session listing has been stored once: every sync after the first.
                synced = (account?.isSyncing ?: flowOf(false))
                    .map { graph.store.cursor(SyncEngine.CURSOR_SESSIONS_LISTED_AT) != null }
                    .flowOn(Dispatchers.IO),
                projects = ProjectServices.of(graph).projects,
                sessionAvatars = SessionAvatarServices.of(graph).avatars,
                gitStatuses = GitStatusServices.of(graph).statuses,
                // storage.ts updateSessionDraft: a blank draft is no draft.
                drafts = account?.composer?.mirrors
                    ?.map { mirrors -> mirrors.mapNotNull { (id, mirror) -> mirror.draft?.takeIf { it.isNotBlank() }?.let { id to it } }.toMap() }
                    ?: flowOf(emptyMap()),
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Unread chats and the chat on screen; per account, in memory. */
    val reads = SessionReadState()

    val list = HomeListModel(
        snapshots = snapshots,
        settings = graph.settings.settings,
        localSettings = graph.localSettings.values,
        unreadSessionIds = reads.unreadSessionIds,
        archivingSessionIds = archiver.archiving,
        scope = scope,
    )

    init {
        scope.launch {
            var account: AccountSync? = null
            graph.account.collect { next -> if (next !== account) { account = next; reads.reset() } }
        }
        scope.launch { snapshots.collect { snapshot -> if (snapshot != null) reads.observe(snapshot.sessions) } }
    }

    private var newSessionServices: NewSessionServices? = null

    /**
     * The dock's draft, pickers and starter (HomeDockController's
     * NewSessionComposer and SessionStarter), made on first use and kept,
     * as the draft outlives home. Logout starts the draft over.
     */
    fun newSession(context: Context): NewSessionServices = synchronized(this) {
        newSessionServices ?: makeNewSession(context.applicationContext).also { made ->
            newSessionServices = made
            graph.afterLogoutWipe { made.resetDraft() }
        }
    }

    private fun makeNewSession(context: Context): NewSessionServices {
        val drafts = NewSessionDraftStore(context.getSharedPreferences(NewSessionDraftStore.PREFS_NAME, Context.MODE_PRIVATE))
        val machineActions = LiveMachineActions { graph.account.value?.machineOps }
        val starter = SessionStarter(
            machines = { snapshots.value?.machines.orEmpty() },
            sessions = { snapshots.value?.sessions.orEmpty() },
            settings = { graph.settings.settings.value },
            drafts = drafts,
            machineActions = machineActions,
            sessionActions = sessionActions,
            sender = LiveFirstMessageSender({ graph.account.value }),
            awaitSession = { id -> graph.account.value?.sender?.ensureSessionReady(id) ?: false },
            scope = scope,
        )
        val labels = NewSessionLabels(
            selectMachine = context.getString(R.string.home_dock_select_machine),
            project = context.getString(R.string.home_dock_picker_project),
            projects = context.getString(R.string.home_dock_picker_projects),
            noWorktree = context.getString(R.string.home_dock_no_worktree),
            main = context.getString(R.string.home_dock_main),
            createWorkspace = context.getString(R.string.home_dock_create_workspace),
            createWorktree = context.getString(R.string.home_dock_create_worktree),
            enterCustomPath = context.getString(R.string.machine_launcher_enter_custom_path),
            online = context.getString(R.string.status_online),
            notSupportedBy = { context.getString(R.string.home_dock_not_supported_by, it) },
        )
        return NewSessionServices(drafts, snapshots, graph.settings.settings, machineActions, starter, labels, context::getString, scope)
    }

    fun session(id: String): Session? = snapshots.value?.sessions?.find { it.id == id }

    fun machine(id: String?): Machine? = id?.let { machineId -> snapshots.value?.machines?.find { it.id == machineId } }

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)

    companion object {
        private val instances = WeakHashMap<AppGraph, HomeServices>()

        fun of(graph: AppGraph): HomeServices = synchronized(instances) { instances.getOrPut(graph) { HomeServices(graph) } }
    }
}
