package com.slopus.happy.ui.details

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupMenu
import com.slopus.happy.AppGraph
import com.slopus.happy.BuildConfig
import com.slopus.happy.R
import com.slopus.happy.data.MachineRecord
import com.slopus.happy.data.SessionRecord
import com.slopus.happy.home.ArchiveResult
import com.slopus.happy.home.HomeServices
import com.slopus.happy.home.maybeCleanupWorktree
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.SecondaryPalette
import com.slopus.happy.layout.SecondaryTextStyles
import com.slopus.happy.model.GitStatus
import com.slopus.happy.model.Session
import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.model.details.SessionDetails
import com.slopus.happy.model.details.SessionInfoFrame
import com.slopus.happy.model.details.SessionInfoModel
import com.slopus.happy.model.details.SessionInfoRawBlock
import com.slopus.happy.model.details.SessionInfoSection
import com.slopus.happy.model.details.SessionInfoText
import com.slopus.happy.model.gitStatusKey
import com.slopus.happy.model.parseJsonObject
import com.slopus.happy.projects.GitStatusServices
import com.slopus.happy.resume.AccountResumeOps
import com.slopus.happy.resume.ResumeOutcome
import com.slopus.happy.resume.SessionResumer
import com.slopus.happy.resume.resumeTexts
import com.slopus.happy.sync.SessionPresence
import com.slopus.happy.sync.SpawnSessionResult
import com.slopus.happy.sync.SyncEngine
import com.slopus.happy.ui.chat.ChatScreen
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.GroupedListView
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.components.ItemIcon
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.machine.MachineScreen
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.nav.NavigationBar
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * app/(app)/session/[id]/info.tsx (happy-ios `SessionInfoViewController`):
 * quick actions, the session's ids and state, its metadata, agent state and
 * activity, and in debug builds its raw JSON.
 *
 * What it shows is worked out off the main thread from the stored session,
 * its computer, git status, presence and the composer's picks; a group is
 * built again only when what it shows changed ([SessionInfoFrame.sectionContent]),
 * so a heartbeat touches nothing. iOS makes its groups a few rows a frame;
 * here a group is a handful of rows and is made at once.
 *
 * Until the session is stored a "Loading..." group shows; if nothing is on
 * screen 5 s after it appeared, the group says it is taking longer and
 * offers Retry and Copy diagnostics, as the bar's menu always does.
 */
class SessionInfoScreen(context: Context, private val graph: AppGraph, private val sessionId: String) : BarScreen(context) {
    private val root = FrameLayout(context)
    private val list = GroupedListView(context)
    private var stateView: View? = null

    private var frame: SessionInfoFrame? = null
    private val groups = mutableMapOf<SessionInfoSection, ItemGroupView>()
    private val groupContent = mutableMapOf<SessionInfoSection, Any?>()
    private var loadStatusGroup: ItemGroupView? = null
    private var loadState = LoadState.Loading
    private var firstFailure: JsonObject? = null

    /** What only this screen changes: the fork spinner and the resume command's check mark. */
    private data class Local(val forking: Boolean = false, val copiedResumeCommand: Boolean = false)
    private val local = MutableStateFlow(Local())

    private var observation: Job? = null
    private var watchdog: Job? = null
    private var copiedReset: Job? = null
    private val busy = mutableSetOf<Action>()

    private enum class LoadState { Loading, Delayed, Failed, Ready }
    private enum class Action { Archive, Delete, Resume, Fork, Leave }

    override fun createContent(): View {
        root.addView(list)
        return root
    }

    override fun viewCreated(view: View) {
        super.viewCreated(view)
        bar.actions = listOf(NavigationBar.Action(Icon.materialIcons("more-horiz"), string(R.string.session_actions_title), 22.0) { showMenu() })
        // The title home already shows, as the screen slides in.
        graph.store.session(sessionId)?.let { bar.title = SessionDetails.title(parseJsonObject(it.metadataJson)) ?: string(R.string.session_new_chat) }
        showLoadStatus(LoadState.Loading)
        beginObservation()
    }

    override fun onAppear() {
        super.onAppear()
        startWatchdog()
    }

    override fun onDisappear() {
        super.onDisappear()
        watchdog?.cancel()
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea
    }

    private fun string(id: Int) = context.getString(id)

    // Observing

    private class Inputs(
        val record: SessionRecord?,
        val machine: MachineRecord?,
        val listed: Boolean,
        val git: Map<String, GitStatus>,
        val presence: Map<String, SessionPresence>,
    )

    private fun beginObservation() {
        observation?.cancel()
        val account = graph.account.value
        val store = graph.store
        val stored = store.changes.map {
            val record = store.session(sessionId)
            val machineId = record?.let { SessionDetails.truthyString(parseJsonObject(it.metadataJson)?.get("machineId")) }
            Triple(record, machineId?.let(store::machine), store.cursor(SyncEngine.CURSOR_SESSIONS_LISTED_AT) != null)
        }.flowOn(Dispatchers.IO)
        val inputs = combine(
            stored,
            GitStatusServices.of(graph).statuses,
            account?.presence?.sessions ?: flowOf(emptyMap()),
        ) { (record, machine, listed), git, presence -> Inputs(record, machine, listed, git, presence) }
        val machines = account?.presence?.machines ?: flowOf(emptyMap())
        val picks = account?.composer?.mirrors?.map { it[sessionId]?.permissionMode } ?: flowOf(null)
        observation = scope.launch {
            combine(inputs, machines, picks, graph.settings.settings, local) { input, machinePresence, permissionMode, settings, local ->
                derive(input, machinePresence[input.machine?.id]?.active, permissionMode, settings.expResumeSession, local)
            }
                .flowOn(Dispatchers.Default)
                .distinctUntilChanged()
                .catch { e ->
                    if (e is CancellationException) throw e
                    recordFailure("store-failed")
                    showLoadStatus(LoadState.Failed)
                }
                .collect(::apply)
        }
        record(sessionId)
    }

    private fun record(sessionId: String) = GitStatusServices.of(graph).sessionVisible(sessionId)

    private fun derive(input: Inputs, machineOnline: Boolean?, permissionMode: String?, experiments: Boolean, local: Local): SessionInfoFrame {
        val stored = input.record ?: return SessionInfoFrame(if (input.listed) SessionInfoFrame.Content.Deleted else SessionInfoFrame.Content.Loading)
        // Presence is newer than the stored row: online, and thinking.
        val presence = input.presence[sessionId]
        val record = presence?.let { stored.copy(active = it.active, activeAt = maxOf(stored.activeAt, it.activeAt)) } ?: stored
        val machine = input.machine?.let { m -> machineOnline?.let { m.copy(active = it) } ?: m }
        val metadata = parseJsonObject(record.metadataJson)
        val git = gitStatusKey(SessionDetails.truthyString(metadata?.get("machineId")), (metadata?.get("path") as? JsonPrimitive)?.content)?.let(input.git::get)
        val thinking = presence?.thinking ?: false
        val thinkingAt = presence?.thinkingAt ?: 0
        return SessionInfoFrame(
            content = SessionInfoFrame.Content.Shown(SessionInfoModel(record, machine, experiments, git, permissionMode, thinking, thinkingAt)),
            forking = local.forking,
            copiedResumeCommand = local.copiedResumeCommand,
            raw = if (BuildConfig.DEBUG) SessionInfoRawBlock.blocks(record, thinking, thinkingAt) else emptyList(),
        )
    }

    // Building

    private fun apply(next: SessionInfoFrame) {
        frame = next
        when (next.content) {
            SessionInfoFrame.Content.Loading -> if (stateView != null || groups.isNotEmpty()) {
                clearGroups()
                showLoadStatus(LoadState.Loading)
            }
            SessionInfoFrame.Content.Deleted -> {
                loadState = LoadState.Ready
                loadStatusGroup = null
                watchdog?.cancel()
                if (stateView == null) showDeleted()
            }
            is SessionInfoFrame.Content.Shown -> show(next, next.content.model)
        }
    }

    private fun show(frame: SessionInfoFrame, model: SessionInfoModel) {
        stateView?.let { root.removeView(it) }
        stateView = null
        list.visibility = View.VISIBLE
        bar.title = model.title ?: string(R.string.session_new_chat)
        val order = frame.sections
        groups.keys.filter { it !in order }.forEach { groups.remove(it); groupContent.remove(it) }
        for (section in order) {
            val content = frame.sectionContent(section)
            if (section in groups && groupContent[section] == content) continue
            groups[section] = build(section, model, frame)
            groupContent[section] = content
        }
        if (loadState != LoadState.Failed) {
            loadState = LoadState.Ready
            loadStatusGroup = null
            watchdog?.cancel()
        }
        list.sections = listOfNotNull(loadStatusGroup) + order.mapNotNull(groups::get)
    }

    private fun clearGroups() {
        groups.clear()
        groupContent.clear()
    }

    private fun build(section: SessionInfoSection, model: SessionInfoModel, frame: SessionInfoFrame): ItemGroupView = when (section) {
        SessionInfoSection.QuickActions -> quickActions(model, frame.forking)
        SessionInfoSection.CliWarning -> cliWarning()
        SessionInfoSection.Details -> details(model, frame.copiedResumeCommand)
        SessionInfoSection.Metadata -> metadataGroup(model.metadata!!, model)
        SessionInfoSection.AgentState -> agentStateGroup(model.agentState!!)
        SessionInfoSection.Activity -> activityGroup(model)
        SessionInfoSection.Raw -> rawJSON(frame.raw)
    }

    private fun showDeleted() {
        bar.title = string(R.string.errors_session_deleted)
        clearGroups()
        list.sections = emptyList()
        list.visibility = View.GONE
        val view = DetailMessageView(
            context,
            IconView(context, Icon.ionicons("trash-outline"), 48.0, Palette.textSecondary),
            listOf(
                DetailMessageView.Line(string(R.string.errors_session_deleted), SecondaryTextStyles.infoDeletedTitle, Palette.text, spacingBefore = 16.0),
                DetailMessageView.Line(
                    string(R.string.errors_session_deleted_description), SecondaryTextStyles.infoDeletedBody, Palette.textSecondary,
                    spacingBefore = 8.0, horizontalPadding = 32.0, centered = true,
                ),
            ),
        )
        stateView = view
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun icon(name: String, tint: ColorToken, size: Double = 29.0) = ItemIcon.Glyph(Icon.ionicons(name), tint, size)

    private fun row(title: String, subtitle: String? = null, icon: ItemIcon? = null, onTap: () -> Unit) =
        ItemRowView(context, title, subtitle, icon = icon, onTap = onTap)

    private fun info(title: String, subtitle: String? = null, detail: String? = null, icon: ItemIcon) =
        ItemRowView(context, title, subtitle, detail, icon).apply { showsChevron = false }

    private fun quickActions(model: SessionInfoModel, forking: Boolean): ItemGroupView {
        val rows = mutableListOf<View>()
        val changes = row(string(R.string.files_changes), icon = ItemIcon.Glyph(Icon.octicons("file-diff"), SecondaryPalette.blue, 26.0)) { openChanges() }
        if (model.git.hasAccessory) changes.trailingView = DetailChangesAccessoryView(context, model.git)
        rows += changes
        model.machineId?.let { machineId ->
            rows += row(string(R.string.session_info_view_machine), string(R.string.session_info_view_machine_subtitle), icon("server-outline", SecondaryPalette.blue)) {
                push(MachineScreen(context, graph, machineId))
            }
        }
        if (model.resume.showsRow) {
            val subtitle = if (model.resume.canResume) string(R.string.session_info_resume_session_subtitle) else resumeMessage(model.resume)
            rows += row(string(R.string.session_info_resume_session), subtitle, icon("play-circle-outline", SecondaryPalette.blue)) { resume() }
        }
        if (model.canFork) {
            rows += row(string(R.string.session_fork_action), string(R.string.session_fork_subtitle), icon("git-branch-outline", SecondaryPalette.blue)) { fork() }
                .apply { isLoading = forking }
            rows += row(string(R.string.session_duplicate_action), string(R.string.session_duplicate_subtitle), icon("time-outline", SecondaryPalette.blue)) { openDuplicateSheet() }
        }
        model.parentSessionId?.let { parent ->
            rows += row(string(R.string.session_forked_from_label), string(R.string.session_forked_from_subtitle), icon("return-up-back-outline", SecondaryPalette.purple)) {
                push(ChatScreen(context, graph, parent))
            }
        }
        if (model.canLeaveTask) {
            // Out of this person's list; the task runs on for the rest.
            rows += row(string(R.string.tasks_leave), icon = icon("exit-outline", SecondaryPalette.red)) { leaveTask() }
        }
        // A task is archived only by its owner or the team's.
        if (model.canArchive) {
            rows += row(
                string(if (model.isTask) R.string.tasks_archive else R.string.session_info_archive_session),
                if (model.isTask) null else string(R.string.session_info_archive_session_subtitle),
                icon("archive-outline", SecondaryPalette.red),
            ) { archive() }
        }
        if (!model.keepsConversation) {
            rows += row(string(R.string.session_info_delete_session), string(R.string.session_info_delete_session_subtitle), icon("trash-outline", SecondaryPalette.red)) {
                confirmDelete()
            }
        }
        return ItemGroupView(context, string(R.string.session_info_quick_actions), rows = rows).apply { keepsLastDivider(model.keepsConversation) }
    }

    private fun cliWarning(): ItemGroupView {
        val command = string(R.string.session_info_update_command)
        val row = row(string(R.string.session_info_cli_version_outdated), string(R.string.session_info_update_cli_instructions), icon("warning-outline", SecondaryPalette.orange)) {
            copy(command)
            Alerts.show(context, string(R.string.common_success), command)
        }
        row.showsChevron = false
        return ItemGroupView(context, rows = listOf(row))
    }

    private fun details(model: SessionInfoModel, copied: Boolean): ItemGroupView {
        val rows = mutableListOf<View>()
        rows += row(string(R.string.session_info_happy_session_id), model.sessionIdShort, icon("finger-print-outline", SecondaryPalette.blue)) {
            copy(model.sessionId, R.string.session_info_happy_session_id_copied)
        }
        model.claudeSessionId?.let { claude ->
            rows += row(string(R.string.session_info_claude_code_session_id), SessionInfoText.shortId(claude), icon("code-outline", SecondaryPalette.claude)) {
                copy(claude, R.string.session_info_claude_code_session_id_copied)
            }
        }
        model.codexThreadId?.let { codex ->
            rows += row(string(R.string.session_info_codex_thread_id), SessionInfoText.shortId(codex), icon("terminal-outline", SecondaryPalette.codex)) {
                copy(codex, R.string.session_info_codex_thread_id_copied)
            }
        }
        model.resumeCommand?.let { command ->
            // info.tsx CopyableItem: copies quietly and shows a check for 2 s.
            rows += row(string(R.string.session_info_resume_command), command, icon("play-circle-outline", SecondaryPalette.copied)) { copyResumeCommand(command) }.apply {
                showsChevron = false
                trailingView = if (copied) IconView(context, Icon.ionicons("checkmark"), 18.0, SecondaryPalette.copied)
                else IconView(context, Icon.ionicons("copy-outline"), 18.0, SecondaryPalette.gray)
            }
        }
        rows += info(
            string(R.string.session_info_connection_status),
            detail = string(if (model.isConnected) R.string.status_online else R.string.status_offline),
            icon = icon("pulse-outline", if (model.isConnected) SecondaryPalette.green else SecondaryPalette.gray),
        )
        rows += info(string(R.string.session_info_created), SessionDetails.localeDateTime(model.createdAt), icon = icon("calendar-outline", SecondaryPalette.blue))
        rows += info(string(R.string.session_info_last_updated), SessionDetails.localeDateTime(model.updatedAt), icon = icon("time-outline", SecondaryPalette.blue))
        rows += info(string(R.string.session_info_sequence), detail = model.seq.toString(), icon = icon("git-commit-outline", SecondaryPalette.blue))
        return ItemGroupView(context, rows = rows)
    }

    private fun metadataGroup(metadata: SessionInfoModel.Metadata, model: SessionInfoModel): ItemGroupView {
        val purple = SecondaryPalette.purple
        val rows = mutableListOf<View>()
        rows += info(string(R.string.session_info_host), metadata.host, icon = icon("desktop-outline", purple))
        rows += info(string(R.string.session_info_path), metadata.path, icon = icon("folder-outline", purple))
        metadata.cliVersion?.let {
            rows += info(
                string(R.string.session_info_cli_version), it, if (model.cliOutdated) "⚠️" else null,
                icon("git-branch-outline", if (model.cliOutdated) SecondaryPalette.orange else purple),
            )
        }
        metadata.operatingSystem?.let { rows += info(string(R.string.session_info_operating_system), it, icon = icon("hardware-chip-outline", purple)) }
        metadata.client?.let { rows += info(string(R.string.session_info_client), it, icon = icon("terminal-outline", purple)) }
        rows += info(string(R.string.session_info_ai_provider), metadata.aiProvider, icon = icon("sparkles-outline", purple))
        metadata.model?.let { rows += info(string(R.string.session_info_model), it, icon = icon("hardware-chip-outline", purple)) }
        metadata.sandbox?.let { rows += info(string(R.string.session_info_sandbox), it, icon = icon("shield-outline", purple)) }
        metadata.dangerouslySkipPermissions?.let { rows += info(string(R.string.session_info_dangerously_skip_permissions), it, icon = icon("warning-outline", purple)) }
        metadata.processId?.let { rows += info(string(R.string.session_info_process_id), it, icon = icon("terminal-outline", purple)) }
        metadata.happyHome?.let { rows += info(string(R.string.session_info_happy_home), it, icon = icon("home-outline", purple)) }
        rows += row(string(R.string.session_info_copy_metadata), icon = icon("copy-outline", SecondaryPalette.blue)) { copyMetadata(withLogs = false) }
        rows += row(string(R.string.session_info_copy_metadata) + string(R.string.session_info_client_logs_suffix), icon = icon("document-text-outline", SecondaryPalette.blue)) {
            copyMetadata(withLogs = true)
        }
        return ItemGroupView(context, string(R.string.session_info_metadata), rows = rows)
    }

    private fun agentStateGroup(state: SessionInfoModel.AgentState): ItemGroupView {
        val orange = SecondaryPalette.orange
        val rows = mutableListOf<View>(
            info(string(R.string.session_info_controlled_by_user), detail = string(if (state.controlledByUser) R.string.common_yes else R.string.common_no), icon = icon("person-outline", orange)),
        )
        if (state.pendingRequests > 0) {
            rows += info(string(R.string.session_info_pending_requests), detail = state.pendingRequests.toString(), icon = icon("hourglass-outline", orange))
        }
        return ItemGroupView(context, string(R.string.session_info_agent_state), rows = rows).apply { keepsLastDivider(state.pendingRequests == 0) }
    }

    private fun activityGroup(model: SessionInfoModel): ItemGroupView {
        val purple = SecondaryPalette.purple
        val rows = mutableListOf<View>(
            info(
                string(R.string.session_info_thinking), detail = string(if (model.thinking) R.string.common_yes else R.string.common_no),
                icon = icon("bulb-outline", if (model.thinking) SecondaryPalette.yellow else SecondaryPalette.gray),
            ),
        )
        if (model.thinking) {
            rows += info(string(R.string.session_info_thinking_since), SessionDetails.localeDateTime(model.thinkingAt), icon = icon("timer-outline", SecondaryPalette.yellow))
        }
        model.activity.subagents?.let { rows += info(string(R.string.session_info_subagents), detail = it, icon = icon("people-outline", purple)) }
        model.activity.workflows?.let { rows += info(string(R.string.session_info_workflows), detail = it, icon = icon("git-network-outline", purple)) }
        model.activity.processes?.let { rows += info(string(R.string.session_info_background_processes), detail = it, icon = icon("terminal-outline", purple)) }
        model.activity.tasks?.let { rows += info(string(R.string.session_info_tasks), detail = it, icon = icon("checkbox-outline", purple)) }
        return ItemGroupView(context, string(R.string.session_info_activity), rows = rows).apply { keepsLastDivider(model.activity.tasks == null) }
    }

    /** info.tsx's "Raw JSON (Dev Mode)" group, shown in `__DEV__` builds; here in debug builds. */
    private fun rawJSON(blocks: List<SessionInfoRawBlock>): ItemGroupView {
        val rows = mutableListOf<View>()
        for (block in blocks) {
            rows += when (block.kind) {
                SessionInfoRawBlock.Kind.AgentState -> info(string(R.string.session_info_raw_agent_state), icon = icon("code-working-outline", SecondaryPalette.orange))
                SessionInfoRawBlock.Kind.Metadata -> info(string(R.string.session_info_raw_metadata), icon = icon("information-circle-outline", SecondaryPalette.purple))
                SessionInfoRawBlock.Kind.Session -> info(string(R.string.session_info_raw_session), icon = icon("document-text-outline", SecondaryPalette.green))
            }
            rows += CodeBlockView(context, block.code)
        }
        // The rows sit in fragments, so every one keeps Item's default divider.
        return ItemGroupView(context, string(R.string.session_info_raw_json), rows = rows).apply { keepsLastDivider(true) }
    }

    // Loading and local diagnostics

    /**
     * Status is a separate group, so a failure or retry never throws away
     * rows already on screen. Building continues behind it and removes the
     * waiting state when it finishes.
     */
    private fun showLoadStatus(state: LoadState) {
        loadState = state
        val message = when (state) {
            LoadState.Loading -> string(R.string.common_loading)
            LoadState.Delayed -> string(R.string.session_info_load_delayed)
            LoadState.Failed -> string(R.string.session_info_load_failed)
            LoadState.Ready -> return
        }
        val rows = mutableListOf<View>(ItemRowView(context, message).apply { showsChevron = false })
        if (state != LoadState.Loading) {
            rows += ItemRowView(context, string(R.string.common_retry), onTap = ::retry)
            rows += ItemRowView(context, string(R.string.session_info_copy_diagnostics), onTap = ::copyDiagnostics)
        }
        loadStatusGroup = ItemGroupView(context, rows = rows)
        stateView?.let { root.removeView(it) }
        stateView = null
        list.visibility = View.VISIBLE
        val order = frame?.sections.orEmpty()
        list.sections = listOfNotNull(loadStatusGroup) + order.mapNotNull(groups::get)
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(FIRST_PAINT_TIMEOUT_MS)
            if (frame?.content == SessionInfoFrame.Content.Deleted) return@launch
            if (groups.isNotEmpty() && list.visibility == View.VISIBLE) return@launch
            if (loadState == LoadState.Failed) return@launch
            recordFailure("first-paint-timeout")
            showLoadStatus(LoadState.Delayed)
        }
    }

    private fun retry() {
        clearGroups()
        local.update { Local() }
        showLoadStatus(LoadState.Loading)
        beginObservation()
        startWatchdog()
    }

    private fun recordFailure(reason: String) {
        if (firstFailure == null) firstFailure = diagnosticSnapshot(reason)
    }

    private fun copyDiagnostics() {
        copy(loadingDiagnostics())
        Alerts.show(context, string(R.string.common_copied), "")
    }

    /** Only the explicit Copy actions put this on the clipboard; the fields never read session data. */
    private fun loadingDiagnostics(): String {
        val report = buildJsonObject {
            put("schema", 1)
            put("current", diagnosticSnapshot("manual-copy"))
            firstFailure?.let { put("firstFailure", it) }
        }
        return SessionInfoText.prettyJson(report)
    }

    private fun diagnosticSnapshot(reason: String): JsonObject {
        val content = when (frame?.content) {
            null -> "none"
            SessionInfoFrame.Content.Loading -> "loading"
            SessionInfoFrame.Content.Deleted -> "deleted"
            is SessionInfoFrame.Content.Shown -> "shown"
        }
        val stage = when {
            groups.isNotEmpty() && list.visibility == View.VISIBLE -> "body-visible"
            frame == null -> "no-snapshot-or-apply"
            else -> "no-installed-groups"
        }
        return buildJsonObject {
            put("reason", reason)
            put("stage", stage)
            put("content", content)
            put("loadState", loadState.name.lowercase())
            put("version", BuildConfig.VERSION_NAME)
            put("build", BuildConfig.VERSION_CODE)
            put("os", Build.VERSION.RELEASE)
            put("model", Build.MODEL)
            put("dark", Theme.isDark)
            put("installedGroups", groups.size)
            put("listSections", list.sections.size)
            put("accountSignedIn", graph.account.value != null)
        }
    }

    private fun showMenu() {
        val anchor = bar.actionView(0) ?: return
        val themed = ContextThemeWrapper(context, if (Theme.isDark) android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light)
        val popup = PopupMenu(themed, anchor)
        popup.menu.add(0, MENU_RETRY, 0, string(R.string.common_retry))
        popup.menu.add(0, MENU_DIAGNOSTICS, 1, string(R.string.session_info_copy_diagnostics))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RETRY -> retry()
                MENU_DIAGNOSTICS -> copyDiagnostics()
            }
            true
        }
        popup.show()
    }

    // Actions

    private fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    private fun copy(text: String, success: Int) {
        copy(text)
        Alerts.show(context, string(R.string.common_success), string(success))
    }

    private fun copyResumeCommand(command: String) {
        copy(command)
        local.update { it.copy(copiedResumeCommand = true) }
        copiedReset?.cancel()
        copiedReset = scope.launch {
            delay(2_000)
            local.update { it.copy(copiedResumeCommand = false) }
        }
    }

    /** The metadata as stored now, printed off the main thread. */
    private fun copyMetadata(withLogs: Boolean) {
        scope.launch {
            val text = withContext(Dispatchers.Default) {
                graph.store.session(sessionId)?.let { parseJsonObject(it.metadataJson) }?.let { SessionInfoText.metadataClipboard(it, withLogs) }
            }
            if (text == null) {
                Alerts.show(context, string(R.string.common_error), string(R.string.session_info_failed_to_copy_metadata))
                return@launch
            }
            copy(text)
            Alerts.show(context, string(R.string.common_success), string(R.string.session_info_metadata_copied))
        }
    }

    private fun resumeMessage(resume: SessionInfoText.Resume): String = when (resume) {
        SessionInfoText.Resume.MissingMachine -> string(R.string.session_info_resume_session_missing_machine)
        SessionInfoText.Resume.MissingBackendId -> string(R.string.session_info_resume_session_missing_backend_id)
        SessionInfoText.Resume.SameMachineOnly -> string(R.string.session_info_resume_session_same_machine_only)
        SessionInfoText.Resume.MachineOffline -> string(R.string.session_info_resume_session_machine_offline)
        SessionInfoText.Resume.Hidden, SessionInfoText.Resume.Available -> ""
    }

    /** happy-app's HappyError: a failure with the words to show. */
    private class Failure(message: String) : Exception(message)

    /**
     * useHappyAction: one run at a time; a failure is an alert. Runs past
     * this screen (archive and delete leave it first), so not in its scope.
     */
    private fun perform(action: Action, work: suspend () -> Unit) {
        if (!busy.add(action)) return
        val appContext = context
        detached.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = (e as? Failure)?.message ?: appContext.getString(R.string.session_info_unknown_error)
                Alerts.show(appContext, appContext.getString(R.string.session_info_action_error), message)
            } finally {
                busy.remove(action)
            }
        }
    }

    /** The stored session as it is now; null once it is gone. */
    private suspend fun current(): SessionRecord? = withContext(Dispatchers.IO) { graph.store.session(sessionId) }

    /** The worktree prompt (useWorktreeCleanup.ts), asked over this screen. */
    private suspend fun cleanupWorktree(record: SessionRecord) {
        val account = graph.account.value ?: return
        val metadata = SessionMetadata.parse(parseJsonObject(record.metadataJson) ?: return)
        val active = withContext(Dispatchers.IO) { graph.store.sessions() }.map(Session::from).filter { it.active }
        maybeCleanupWorktree(
            sessionId = record.id,
            sessionPath = metadata?.path,
            machineId = metadata?.machineId,
            activeSessions = active,
            bash = { machineId, command, cwd -> account.machineOps.bash(machineId, command, cwd) },
            confirm = {
                Alerts.confirm(
                    context,
                    string(R.string.session_info_worktree_cleanup_title),
                    string(R.string.session_info_worktree_cleanup_message),
                    confirmTitle = string(R.string.session_info_worktree_cleanup_delete),
                    cancelTitle = string(R.string.session_info_worktree_cleanup_keep),
                    destructive = true,
                )
            },
        )
    }

    /**
     * info.tsx `performArchive`: the worktree prompt (not for bots or tasks),
     * then stop the agent, falling back to archiving on the server.
     */
    private fun archive() = perform(Action.Archive) {
        val record = current() ?: return@perform
        val session = Session.from(record)
        val isTask = parseJsonObject(record.metadataJson)?.get("task") is JsonObject
        // The archiver kills a bot only; a task keeps its worktree.
        val result = HomeServices.of(graph).archiver.archive(session, cleanup = { if (!isTask) cleanupWorktree(record) })
        if (result is ArchiveResult.Failed) {
            throw Failure(
                when {
                    result.bot -> BOT_ARCHIVE_MESSAGE
                    isTask -> TASK_ARCHIVE_MESSAGE
                    else -> result.message?.takeIf { it.isNotEmpty() } ?: string(R.string.session_info_failed_to_archive_session)
                },
            )
        }
        // Leave this screen and the chat behind it.
        leaveSession()
    }

    private fun leaveTask() = perform(Action.Leave) {
        current() ?: return@perform
        val ops = graph.account.value?.sessionOps ?: throw Failure(TASK_LEAVE_MESSAGE)
        val result = ops.leaveTask(sessionId)
        if (!result.success) throw Failure(result.message ?: TASK_LEAVE_MESSAGE)
        // It is no longer in this account: leave the screen and the chat.
        leaveSession()
    }

    private fun confirmDelete() {
        scope.launch {
            val confirmed = Alerts.confirm(
                context,
                string(R.string.session_info_delete_session),
                string(R.string.session_info_delete_session_warning),
                confirmTitle = string(R.string.session_info_delete_session),
                destructive = true,
            )
            if (confirmed) delete()
        }
    }

    /** info.tsx `performDelete`: the worktree question, then leave, then stop the agent if it may still run and delete. */
    private fun delete() = perform(Action.Delete) {
        val record = current() ?: return@perform
        val metadata = parseJsonObject(record.metadataJson)
        if (metadata?.get("bot") is JsonObject) throw Failure(BOT_DELETE_MESSAGE)
        if (metadata?.get("task") is JsonObject) throw Failure(TASK_DELETE_MESSAGE)
        cleanupWorktree(record)
        leaveSession()
        val actions = HomeServices.of(graph).sessionActions
        if (record.active) runCatching { actions.kill(record.id) }
        if (!actions.delete(record.id).success) throw Failure(string(R.string.session_info_failed_to_delete_session))
    }

    /** useSessionQuickActions.ts `performResume`; the same session reconnects, so a new id opens. */
    private fun resume() {
        val model = frame?.model ?: return
        if (!model.resume.canResume) {
            Alerts.show(context, string(R.string.session_info_action_error), resumeMessage(model.resume))
            return
        }
        perform(Action.Resume) {
            val record = current() ?: return@perform
            val metadata = parseJsonObject(record.metadataJson)?.let(SessionMetadata::parse)
            val machineId = metadata?.machineId?.takeIf { it.isNotEmpty() } ?: throw Failure(string(R.string.session_info_resume_session_missing_machine))
            val account = graph.account.value
            val ops = account?.let { AccountResumeOps(it) { graph.settings.settings.value } }
            when (val outcome = SessionResumer.of(graph).resume(ops, sessionId, machineId, metadata, resumeTexts(context.resources))) {
                is ResumeOutcome.Resumed -> if (stack != null) push(ChatScreen(context, graph, outcome.sessionId))
                is ResumeOutcome.Failed -> throw Failure(outcome.message)
                ResumeOutcome.InFlight -> Unit
            }
        }
    }

    /** useSessionQuickActions.ts `performFork`: the transcript copied into a new session on the same machine. */
    private fun fork() {
        if (Action.Fork in busy) return
        local.update { it.copy(forking = true) }
        perform(Action.Fork) {
            try {
                val record = current() ?: return@perform
                val source = frame?.model?.takeIf { it.canFork }?.let { SessionInfoText.forkSource(record.id, parseJsonObject(record.metadataJson)) }
                    ?: throw Failure(string(R.string.session_fork_error_missing_metadata))
                val machineOps = graph.account.value?.machineOps ?: throw Failure(string(R.string.session_fork_error_generic))
                when (val result = machineOps.forkAndSpawn(source)) {
                    is SpawnSessionResult.Success -> if (stack != null) push(ChatScreen(context, graph, result.sessionId))
                    is SpawnSessionResult.Error -> throw Failure(result.errorMessage)
                    else -> throw Failure(string(R.string.session_fork_error_generic))
                }
            } finally {
                local.update { it.copy(forking = false) }
            }
        }
    }

    private fun openDuplicateSheet() {
        DuplicateSheet.present(this, graph, sessionId)
    }

    private fun openChanges() {
        push(SessionChangesScreen(context, graph, sessionId))
    }

    /** router.back() twice: off this screen and off the chat it came from. */
    private fun leaveSession() {
        val stack = stack ?: return
        val screens = stack.all
        val index = screens.indexOf(this)
        if (index < 0) return
        stack.popTo(screens[maxOf(0, index - 2)])
    }

    private companion object {
        const val FIRST_PAINT_TIMEOUT_MS = 5_000L
        const val MENU_RETRY = 1
        const val MENU_DIAGNOSTICS = 2

        /** Hard-coded in useSessionQuickActions.ts / info.tsx, not in en.ts (happy-ios SessionDetailActions). */
        const val BOT_ARCHIVE_MESSAGE = "Connect to the bot’s machine to archive it."
        const val BOT_DELETE_MESSAGE = "Archive the bot instead of deleting its conversation."
        const val TASK_ARCHIVE_MESSAGE = "Connect to the task’s machine to archive it."
        const val TASK_LEAVE_MESSAGE = "Connect to the task’s machine to leave it."
        const val TASK_DELETE_MESSAGE = "A task’s conversation can’t be deleted. Leave the task instead."

        /** Actions that outlive the screen: an archive or delete finishes after it has gone. */
        val detached = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
