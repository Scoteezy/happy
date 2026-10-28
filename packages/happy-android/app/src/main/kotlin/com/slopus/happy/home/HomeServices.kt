package com.slopus.happy.home

import com.slopus.happy.AppGraph
import com.slopus.happy.model.Machine
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

    fun session(id: String): Session? = snapshots.value?.sessions?.find { it.id == id }

    fun machine(id: String?): Machine? = id?.let { machineId -> snapshots.value?.machines?.find { it.id == machineId } }

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)

    companion object {
        private val instances = WeakHashMap<AppGraph, HomeServices>()

        fun of(graph: AppGraph): HomeServices = synchronized(instances) { instances.getOrPut(graph) { HomeServices(graph) } }
    }
}
