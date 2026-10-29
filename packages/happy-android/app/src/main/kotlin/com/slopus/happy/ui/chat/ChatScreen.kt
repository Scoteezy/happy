package com.slopus.happy.ui.chat

import com.slopus.happy.ui.nav.SessionRoute
import kotlinx.coroutines.flow.distinctUntilChanged
import com.slopus.happy.data.OutboxKind
import com.slopus.happy.data.OutboxState
import com.slopus.happy.attachments.AttachmentServices
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.PopupMenu
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.chat.ChatEmptyState
import com.slopus.happy.chat.ChatFeed
import com.slopus.happy.chat.ChatOlderPaging
import com.slopus.happy.chat.ChatSnapshot
import com.slopus.happy.layout.chat.CliWarningPillLayout
import com.slopus.happy.layout.chat.WorktreeTabStripLayout
import com.slopus.happy.model.CliVersionWarning
import com.slopus.happy.model.ProjectWorktree
import com.slopus.happy.model.SessionState
import com.slopus.happy.model.PendingChat
import com.slopus.happy.model.PendingChatStatus
import com.slopus.happy.model.isPendingChatId
import com.slopus.happy.model.newSessionLikeSession
import com.slopus.happy.model.pendingChatsBeside
import com.slopus.happy.model.resolveWorktreeTabs
import com.slopus.happy.chat.ChatActions
import com.slopus.happy.chat.PendingChatDetail
import com.slopus.happy.model.neighbouringTabId
import com.slopus.happy.ui.home.HomeSessionActions
import com.slopus.happy.chat.ChatMessagesSource
import com.slopus.happy.chat.ChatRow
import com.slopus.happy.chat.ChatState
import com.slopus.happy.chat.AgentWorkGroup
import com.slopus.happy.chat.ChatListItem
import com.slopus.happy.chat.WorkGroupExpansion
import com.slopus.happy.chat.WorkHeaderPlacement
import com.slopus.happy.chat.chatListItems
import com.slopus.happy.chat.groupChatRowsForDisplay
import com.slopus.happy.chat.isCurrentTurnComplete
import com.slopus.happy.chat.InactiveSessionHint
import com.slopus.happy.chat.InlineQuestionForm
import com.slopus.happy.chat.PendingAgentCommunication
import com.slopus.happy.chat.goalDispatch
import com.slopus.happy.chat.shouldUseAgentQuestionFallback
import com.slopus.happy.home.HomeServices
import com.slopus.happy.model.GitStatus
import com.slopus.happy.model.gitStatusKey
import com.slopus.happy.projects.GitStatusServices
import com.slopus.happy.model.AgentGoalAction
import com.slopus.happy.model.Machine
import com.slopus.happy.model.rigActivityIndicators
import com.slopus.happy.model.visibleAgentGoal
import com.slopus.happy.resume.ChatResumeOps
import com.slopus.happy.resume.ResumeOutcome
import com.slopus.happy.resume.SessionResumer
import com.slopus.happy.resume.resumeTexts
import com.slopus.happy.chat.PermissionAnswer
import com.slopus.happy.chat.PermissionDecision
import com.slopus.happy.chat.NoLivePresence
import com.slopus.happy.chat.StoreChatMessagesSource
import com.slopus.happy.chat.StoreChatSessionSource
import com.slopus.happy.chat.SyncChatActions
import com.slopus.happy.chat.SyncChatMessagesSource
import com.slopus.happy.chat.SyncChatPresenceSource
import com.slopus.happy.chat.resolveChatHeaderGit
import com.slopus.happy.chat.tools.PermissionChoice
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.BubblePalette
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.chat.ChatScreenLayout
import com.slopus.happy.model.Settings
import com.slopus.happy.model.avatarId
import com.slopus.happy.model.resolveAvatarHarness
import com.slopus.happy.sync.AgentModeField
import com.slopus.happy.sync.ComposerMirror
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.AvatarStyle
import com.slopus.happy.ui.components.AvatarView
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.autocomplete.AutocompleteServices
import com.slopus.happy.autocomplete.SessionSuggestions
import com.slopus.happy.ui.composer.ChatComposerView
import com.slopus.happy.ui.voice.VoiceCoordinator
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.tablet.ContentWidth
import com.slopus.happy.ui.tablet.TabletShell
import com.slopus.happy.layout.TabletBranches
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.layout.chat.MarkdownLayout
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The session screen (SessionView.tsx with AgentContentView's floating dock;
 * happy-ios `ChatViewController`): the conversation runs under the floating
 * header and the dock, which rides the keyboard frame by frame. Rows are
 * planned off the main thread and only drawn here. [ChatScreenLayout] places
 * everything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatScreen(context: Context, private val graph: AppGraph, override val sessionId: String) : Screen(context), SessionRoute {
    /** A chat opened from the strip's `+` before the machine has answered: it stands in for itself under this id. */
    private val standIn = isPendingChatId(sessionId)
    /** A stand-in's composer queues on its record; a real chat's sends. */
    private val actions: ChatActions = (if (standIn) HomeServices.of(graph).pendingChatsIfMade?.actions(sessionId) else null)
        ?: SyncChatActions { graph.account.value }
    private val messages: ChatMessagesSource
        get() = graph.account.value?.let { SyncChatMessagesSource(it.messages, it.sender) } ?: StoreChatMessagesSource(graph.store)

    private val list = ChatListView(context)
    private val header = ChatHeaderView(context)
    private val scrim = ChatScrimView(context)
    private val composer = ChatComposerView(context, sessionId, actions, scope)
    private val scrollButton = ScrollButton(context)
    private val placeholder = ChatPlaceholderView(context)
    private val tabStrip = ChatWorktreeTabStrip(context)
    private val cliWarning = ChatCliWarningPill(context)
    /** The Session alert a tab's long press opens. */
    private val sessionActions = HomeSessionActions(this, graph, scope).also { actions ->
        actions.onBeforeArchive = ::tabArchiving
    }
    private val inactiveHint = ChatInactiveHintView(context, scope)
    private val goalBar = ChatGoalBarView(context)
    private val questionBanner = ChatQuestionBannerView(context)
    private val rigActivity = ChatRigActivityView(context)
    private val renderer = ChatRowRenderer(context.resources)
    private val root = Root(context)

    private var state = ChatState()
    private var settings = Settings()
    private var expandedTools = setOf<String>()
    private var copiedMessageId: String? = null
    private var copiedJob: Job? = null
    /** Tools whose permission answer is on its way, by message id, with the button tapped. */
    private var permissionBusy = mapOf<String, PermissionChoice>()
    /** Diff files the reader opened in full: by tool, by diff, the files. */
    private var diffExpanded = mapOf<String, Map<Int, Set<Int>>>()
    /** Question forms the reader has touched, by tool: picks, and answers on their way. */
    private var questionDrafts = mapOf<String, ChatQuestionState>()
    /** How tall each diagram's page said it is: by row id, then by its place in the row. For as long as the screen lives. */
    private var mermaidHeights = mapOf<String, Map<Int, Double>>()
    private var dockDetailsVisible = true
    private var mirror = ComposerMirror()
    private var machine: Machine? = null
    private var resuming = false
    private var goalInFlight: AgentGoalAction? = null
    private var questionSheet: AgentQuestionSheetScreen? = null
    /** Which folded turns are open, and the turns seen running. */
    private var workGroups = WorkGroupExpansion()
    /** The work groups on screen, by id, for a header's tap. */
    private var shownGroups = mapOf<String, AgentWorkGroup>()
    private var listWidth = 0.0
    /** The chat's paging past its newest page (happy-ios `ChatHistoryPager` over [ChatOlderPaging]). */
    private val paging = ChatOlderPaging()
    /** EmptyMessages pages on its own; the list after it starts over. */
    private var showsEmpty = false
    /** A page that moved the history back, until the snapshot holds it. */
    private var joining: Joining? = null
    /** Moves when paging starts over: answers to fetches from before drop. */
    private var pagingGeneration = 0
    private var oldestDistance = Double.MAX_VALUE
    /** [ChatOlderPaging.boundary] of the rows on screen. */
    private var oldestBoundary: String? = null
    /** Plans asked for and not on screen yet: until then the rows on screen are not the snapshot's. */
    private var plansRequested = 0
    private var plansShown = 0
    private var shown = false
    /** Grouped by project, every chat's checkout with its chats as tabs; empty while the list is flat. */
    private var worktrees: Map<String, ProjectWorktree> = emptyMap()
    /** The checkout the header speaks for; null when the list is flat or the chat is in none. */
    private val checkout: ProjectWorktree? get() = worktrees[headerSessionId]
    /** The chats being opened from the strip, by stand-in id. */
    private var pendingChats: Map<String, PendingChat> = emptyMap()
    /** This stand-in's record. */
    private var pendingChat: PendingChat? = null
    /** The record named a session, or gave up, while another screen was on top: acted on when this one is back. */
    private var pendingMove = false
    /** Where the strip of the screen this one took over from was scrolled to. */
    private var inheritedStripOffset: Double? = null
    /** The screen this one took over from was being typed into: the keyboard stays with the composer. */
    private var inheritedFocus = false
    /** A stand-in's text and caret as it became this chat, ahead of its draft reaching the session. */
    private var inheritedText: Pair<String, Int>? = null
    private val planRequests = MutableStateFlow<PlanRequest?>(null)
    /** The plans made so far, by row id, with what each was made from; touched only on [planner]. */
    private val plans = HashMap<String, Pair<PlanKey, ChatRowPlan>>()
    private val planner = Dispatchers.Default.limitedParallelism(1)

    /** A row's [ChatRowInput], or a work header's [HeaderInput], at a width. */
    private data class PlanKey(val input: Any, val width: Double)

    /** An older page, asked for before [before] (the oldest stored seq then), that has not reached the snapshot; [empty] if EmptyMessages asked. */
    private data class Joining(val request: Int, val before: Long?, val empty: Boolean)

    private data class HeaderInput(val group: AgentWorkGroup, val placement: WorkHeaderPlacement, val expanded: Boolean, val style: ChatRowStyle)

    private data class PlanRequest(
        val rows: List<ChatRow>,
        /** `groupToolCalls`: a finished turn's work folds under "Worked …". */
        val groupWork: Boolean,
        val currentTurnComplete: Boolean,
        val groups: WorkGroupExpansion,
        val expanded: Set<String>,
        val copied: String?,
        val permissionBusy: Map<String, PermissionChoice>,
        val diffExpanded: Map<String, Map<Int, Set<Int>>>,
        val questions: Map<String, ChatQuestionState>,
        /** How tall each diagram's page said it is: by row, then by its place in the row. */
        val diagrams: Map<String, Map<Int, Double>>,
        val style: ChatRowStyle,
        val width: Double,
        /** A row the reader just opened or closed. */
        val pinning: String? = null,
        val number: Int = 0,
    )

    private class Planned(
        val footer: ChatRowPlan,
        val rows: List<ChatRowPlan>,
        val pinning: String?,
        val groups: Map<String, AgentWorkGroup>,
        val boundary: String?,
        val number: Int,
    )

    override fun createView(): View = root

    override fun viewCreated(view: View) {
        inheritedText?.let { (text, cursor) -> composer.card.setText(text, cursor) }
        inheritedText = null
        // The session's pictures, for the attachments in the list, kept with the account's.
        val attachments = AttachmentServices.of(graph)
        attachments.pictures?.let { pictures ->
            view.setTag(R.id.chat_attachments, ChatAttachmentImages(pictures, sessionId) { ref ->
                // A picture still in the outbox names no blob yet: it shows as one that can't be had, as on iOS.
                if (ref.isEmpty()) return@ChatAttachmentImages null
                runCatching { attachments.transfers?.load(sessionId, ref) }.getOrNull()
            })
        }
        header.backButton.setOnClickListener { pop() }
        header.titleControl.setOnClickListener { openInfo() }
        header.avatarControl.setOnClickListener { openInfo() }
        header.plainControl.setOnClickListener { openInfo() }
        applyTabletBranch()
        list.onScroll = ::scrolled
        list.onAction = ::perform
        list.onOlderAction = ::olderAction
        placeholder.onAction = ::olderAction
        composer.onHeightChanged = { root.requestLayout() }
        composer.onSent = { list.scrollToNewest(animated = true) }
        composer.onSuggestionsChanged = { root.requestLayout() }
        composer.suggestionSource = SessionSuggestions(sessionId, { state.session?.metadata }, AutocompleteServices.of(graph).files)
        // The microphone, and the status row during a call: the voice screen over this chat.
        val voice = VoiceCoordinator.of(graph)
        composer.onVoice = { voice.start(sessionId, this) }
        scope.launch { voice.state.collect { composer.voiceStatus = voice.statusText(context) } }
        scrollButton.setOnClickListener { list.scrollToNewest(animated = true) }
        scrollButton.alpha = 0f
        scrollButton.visibility = View.GONE
        cliWarning.setOnClickListener { dismissCliWarning() }
        tabStrip.onSelect = ::selectTab
        tabStrip.onAdd = ::newTab
        tabStrip.showsAdd = true
        tabStrip.onLongPress = sessionActions::present
        questionBanner.onOpen = ::openQuestionSheet
        questionBanner.onDismiss = ::dismissUnsupportedRequest
        inactiveHint.onResume = ::resume
        goalBar.onAction = ::performGoalAction
        for (bar in dockBarViews) bar.visibility = View.GONE

        val feed = graph.account.flatMapLatest { account ->
            val presence = account?.let { SyncChatPresenceSource(it.presence) } ?: NoLivePresence
            ChatFeed(messages, StoreChatSessionSource(graph.store), presence).state(sessionId)
        }
        val mirror = graph.account.flatMapLatest { it?.composer?.mirror(sessionId) ?: flowOf(ComposerMirror()) }
        scope.launch {
            combine(feed, graph.settings.settings, mirror) { state, settings, mirror -> Triple(state, settings, mirror) }
                .collect { (state, settings, mirror) -> apply(state, settings, mirror) }
        }
        scope.launch {
            // Pictures the outbox gave up on: the composer says so once each.
            graph.account.flatMapLatest { it?.sender?.outbox(sessionId) ?: flowOf(emptyList()) }
                .map { rows -> rows.count { it.kind == OutboxKind.ATTACHMENT && it.state == OutboxState.FAILED } }
                .distinctUntilChanged()
                .collect(composer::updateFailedAttachments)
        }
        scope.launch {
            // The header's branch and line counts: this chat's checkout, asked now that it is open.
            val git = GitStatusServices.of(graph)
            git.sessionVisible(sessionId)
            git.statuses.collect { statuses ->
                gitStatuses = statuses
                updateHeader()
            }
        }
        scope.launch {
            // useProjectWorktreeSummary: the checkout from home's grouped list, empty while it is flat.
            HomeServices.of(graph).list.state.map { it?.worktrees.orEmpty() }.distinctUntilChanged().collect { next ->
                worktrees = next
                updateHeader()
                updateTabStrip()
            }
        }
        scope.launch {
            HomeServices.of(graph).pendingChatRecords.collect { chats ->
                pendingChats = chats
                if (standIn) pendingChatChanged(chats[sessionId])
                updateHeader()
                updateTabStrip()
            }
        }
        scope.launch {
            HomeServices.of(graph).snapshots.map { it?.machines }.collect { machines ->
                val id = state.session?.metadata?.machineId
                machine = machines?.find { it.id == id }
                updateDockBars()
            }
        }
        scope.launch {
            planRequests.filterNotNull()
                .map { request -> plan(request) }
                .flowOn(planner)
                .collect { planned ->
                    shownGroups = planned.groups
                    list.submit(planned.footer, planned.rows, planned.pinning) {
                        oldestBoundary = planned.boundary
                        plansShown = maxOf(plansShown, planned.number)
                        checkPaging()
                    }
                }
        }
    }

    private fun apply(state: ChatState, settings: Settings, mirror: ComposerMirror) {
        this.state = state
        this.settings = settings
        this.mirror = mirror
        val machineId = state.session?.metadata?.machineId
        if (machine?.id != machineId) machine = HomeServices.of(graph).machine(machineId)
        updateHeader()
        updateCliWarning()
        composer.update(state, mirror, settings.agentDefaultOverrides)
        receivePaging(state.snapshot)
        requestPlans()
        updateDockBars()
        updatePaging()
        if (showsEmpty) checkPaging()
        root.requestLayout()
    }

    /**
     * SessionView's states around the conversation: a spinner until
     * sessions have been fetched, the deleted state once they have and this
     * one is gone, over the whole screen; in a session, a spinner until its
     * messages are in and EmptyMessages if there are none. The first page
     * never fails here (the catch-up retries on its own), so the loading
     * placeholder's Retry never shows.
     */
    private fun updatePlaceholder() {
        val record = state.session
        val snapshot = state.snapshot
        val content = when {
            record != null -> when {
                snapshot.rows.isNotEmpty() -> null
                snapshot.loaded -> ChatPlaceholderView.Content.Empty(
                    ChatEmptyState.of(record.metadata, record.session.createdAt, System.currentTimeMillis()),
                    paging.emptyStatus(snapshot.hasMoreOlder),
                )
                else -> ChatPlaceholderView.Content.Loading
            }
            // The composer stays: it is the one thing on this screen that survives the chat's arrival.
            standIn -> pendingChat?.let { ChatPlaceholderView.Content.PendingChat(PendingChatDetail.of(it.queued, anchorMetadata)) }
                ?: ChatPlaceholderView.Content.Loading
            state.sessionLoaded -> ChatPlaceholderView.Content.Deleted
            else -> ChatPlaceholderView.Content.Loading
        }
        content?.let(placeholder::show)
        placeholder.visibility = if (content == null) View.GONE else View.VISIBLE
        val fullScreen = record == null && !standIn
        list.visibility = if (fullScreen) View.INVISIBLE else View.VISIBLE
        val dock = if (fullScreen) View.GONE else View.VISIBLE
        if (composer.visibility != dock) {
            composer.visibility = dock
            scrim.visibility = dock
            root.requestLayout()
        }
    }

    // History paging

    /**
     * The snapshot's facts as its rows come in (`ChatHistoryPager.receive`):
     * a change between EmptyMessages and the list starts paging over, and a
     * page still joining settles once the snapshot holds it.
     */
    private fun receivePaging(snapshot: ChatSnapshot) {
        val empty = snapshot.loaded && snapshot.rows.isEmpty()
        if (empty != showsEmpty) {
            startPagingOver()
            showsEmpty = empty
        }
        val page = joining ?: return
        val oldest = snapshot.oldestSeq ?: return
        if (page.before != null && oldest >= page.before) return
        joining = null
        if (page.empty) paging.settledEmpty(page.request, advanced = true) else paging.settled(page.request, advanced = true)
    }

    private fun startPagingOver() {
        paging.reset()
        joining = null
        pagingGeneration += 1
    }

    /** `fillOlder` for the list once its rows are on screen, or EmptyMessages' own paging. A screen not shown asks for nothing. */
    private fun checkPaging() {
        val snapshot = state.snapshot
        if (!snapshot.loaded) return
        val step = if (showsEmpty) {
            if (!shown) return
            paging.checkEmpty(snapshot.hasMoreOlder, snapshot.isLoadingOlder)
        } else {
            if (plansShown < plansRequested) return
            paging.check(
                ChatOlderPaging.Reading(
                    laidOut = list.height > 0 && listWidth > 0,
                    storeHasMessages = true,
                    growthPending = false,
                    nearOldest = oldestDistance < px(list.height),
                    // The list renders all the store holds.
                    windowCanGrow = false,
                    oldestBoundary = oldestBoundary,
                    hasMore = snapshot.hasMoreOlder,
                    loading = snapshot.isLoadingOlder,
                    active = shown,
                ),
            )
        }
        if (step is ChatOlderPaging.Step.FetchOlder) fetchOlder(step.request, showsEmpty)
    }

    private fun fetchOlder(request: Int, empty: Boolean) {
        val generation = pagingGeneration
        val before = state.snapshot.oldestSeq
        updatePaging()
        scope.launch {
            val result = runCatching { messages.loadOlder(sessionId) }
            if (generation != pagingGeneration) return@launch
            val advanced = result.getOrNull()
            when {
                // Whichever way it failed, Retry asks for an older page again.
                advanced == null -> paging.failed(request)
                !advanced -> if (empty) paging.settledEmpty(request, advanced = false) else paging.settled(request, advanced = false)
                // Counted, and followed by the next page, once the snapshot holds its rows.
                else -> {
                    joining = Joining(request, before, empty)
                    receivePaging(state.snapshot)
                    if (joining == null) checkPaging()
                }
            }
            updatePaging()
        }
    }

    /** The older end's or EmptyMessages' button: Retry after a failure, Load more after five pages that showed nothing. */
    private fun olderAction() {
        when (paging.blocked) {
            ChatOlderPaging.Block.Error -> paging.retry()
            ChatOlderPaging.Block.Limit -> paging.loadMore()
            null -> return
        }
        updatePaging()
        checkPaging()
    }

    /** The older end's status and the placeholder, which read the paging. */
    private fun updatePaging() {
        val snapshot = state.snapshot
        list.olderStatus = paging.status(snapshot.hasMoreOlder, snapshot.isLoadingOlder, windowReachesOldest = true)
        updatePlaceholder()
    }

    private val dockBarViews: List<View> get() = listOf(inactiveHint, goalBar, questionBanner, rigActivity)

    /** The bars shown above the composer, in order: inset in the composer's column, RigActivityBar full width. */
    private val dockBars: List<Pair<View, ChatScreenLayout.Accessory>>
        get() = dockBarViews.filter { it.visibility != View.GONE }.map { view ->
            val measurable = (view as ChatDockBar).measurable
            view to if (view === rigActivity) ChatScreenLayout.Accessory.fullWidth(measurable) else ChatScreenLayout.Accessory.inset(measurable)
        }

    /**
     * SessionView's input stack: InactiveArchivedHint, AgentGoalBar,
     * AgentQuestionBanner and RigActivityBar. While the reader is scrolled
     * back they stay faded.
     */
    private fun updateDockBars() {
        val session = state.session
        val hint = InactiveSessionHint.of(session?.session, state.online, machine)
        hint?.let { inactiveHint.show(it, resuming) }
        inactiveHint.visibility = if (hint == null) View.GONE else View.VISIBLE
        val goal = visibleAgentGoal(session?.agentState?.agentGoal, state.online, session?.metadata)
        goal?.let { goalBar.show(it, goalInFlight) }
        goalBar.visibility = if (goal == null) View.GONE else View.VISIBLE
        updateQuestionBanner()
        val indicators = session?.metadata.rigActivityIndicators()
        rigActivity.show(indicators)
        rigActivity.visibility = if (indicators.isEmpty()) View.GONE else View.VISIBLE
        root.requestLayout()
    }

    /** AgentQuestionBanner: the first request the chat can't show inline. An answer from another device closes the sheet here too. */
    private fun updateQuestionBanner() {
        val pending = state.pendingCommunications
        val fallback = pending.firstOrNull(::shouldUseAgentQuestionFallback)
        questionSheet?.let { sheet -> if (pending.none { it.id == sheet.pending.id }) sheet.close() }
        fallback?.let(questionBanner::show)
        questionBanner.visibility = if (fallback == null) View.GONE else View.VISIBLE
    }

    private fun openQuestionSheet() {
        val pending = questionBanner.pending as? PendingAgentCommunication.Form ?: return
        if (questionSheet?.stack != null) return
        val sheet = AgentQuestionSheetScreen(
            context,
            pending,
            submit = { answers -> actions.answerQuestion(sessionId, pending.id, answers, FORM_KIND) },
            // The agent asks again if the dismissal never lands.
            cancel = { scope.launch { runCatching { actions.cancelQuestion(sessionId, pending.id, FORM_KIND) } } },
        )
        questionSheet = sheet
        push(sheet)
    }

    private fun dismissUnsupportedRequest() {
        val pending = questionBanner.pending as? PendingAgentCommunication.Unsupported ?: return
        scope.launch { runCatching { actions.cancelQuestion(sessionId, pending.id, pending.rawKind) } }
    }

    /** agentGoalActionHandler.ts: Edit asks for the new goal first; the button spins until the request is done. */
    private fun performGoalAction(action: AgentGoalAction) {
        val current = goalBar.goal?.text ?: return
        if (goalInFlight != null) return
        scope.launch {
            val edited = if (action == AgentGoalAction.Edit) {
                Alerts.prompt(
                    context,
                    context.getString(R.string.components_agent_goal_bar_edit_goal),
                    placeholder = context.getString(R.string.components_agent_goal_bar_current_goal),
                    confirmTitle = context.getString(R.string.common_save),
                    initial = current,
                )
            } else {
                null
            }
            val dispatch = goalDispatch(action, current, edited) ?: return@launch
            goalInFlight = action
            updateDockBars()
            // Failures are only logged in happy-app; the bar stays as it was.
            runCatching { actions.goalAction(sessionId, dispatch.first.wire, dispatch.second) }
            goalInFlight = null
            updateDockBars()
        }
    }

    /**
     * useSessionQuickActions' performResume: one at a time, spinning until it
     * is done; a failure is an alert. The session reconnects under its own
     * id, so the hint just goes once it is back.
     */
    private fun resume() {
        val session = state.session ?: return
        val machineId = session.metadata?.machineId ?: return
        if (resuming) return
        resuming = true
        updateDockBars()
        scope.launch {
            val ops = ChatResumeOps(actions, { mirror }, { settings.agentDefaultOverrides })
            when (val outcome = SessionResumer.of(graph).resume(ops, sessionId, machineId, session.metadata, resumeTexts(context.resources))) {
                is ResumeOutcome.Resumed -> if (outcome.sessionId != sessionId) push(ChatScreen(context, graph, outcome.sessionId))
                is ResumeOutcome.Failed -> Alerts.show(context, context.getString(R.string.common_error), outcome.message)
                ResumeOutcome.InFlight -> Unit
            }
            resuming = false
            updateDockBars()
        }
    }

    /** Every checkout's git status, by `machineId:path`. */
    private var gitStatuses: Map<String, GitStatus> = emptyMap()

    private fun updateHeader() {
        val session = state.session
        val title = when {
            !state.sessionLoaded -> ""
            // A stand-in is "New chat" for as long as it stands.
            session == null && standIn -> context.getString(R.string.session_new_chat)
            session == null -> context.getString(R.string.errors_session_deleted)
            else -> session.title ?: context.getString(R.string.session_new_chat)
        }
        // A chat that doesn't exist yet can't speak for its checkout: the header reads the chat it was started beside.
        val metadata = session?.metadata ?: if (standIn) anchorMetadata else null
        val git = resolveChatHeaderGit(metadata, gitStatusKey(metadata?.machineId, metadata?.path)?.let(gitStatuses::get))
        var headerTitle = title
        var subtitle = git.subtitle
        // Grouped by project the header names the checkout, and says how many tabs the strip under it holds.
        checkout?.let { checkout ->
            headerTitle = checkout.workspaceName ?: git.subtitle ?: checkout.projectName
            val count = stripTabs(checkout).size
            subtitle = context.resources.getQuantityString(R.plurals.sessions_filter_worktree_tabs, count, count)
        }
        val avatar = session?.let {
            val metadata = it.metadata
            AvatarView.Content(
                seed = it.session.avatarId(),
                style = AvatarStyle.normalize(settings.avatarStyle),
                monochrome = !state.online || settings.avatarMonochrome,
                isBot = metadata?.bot != null,
                harness = if (settings.showHarnessIconInSessionHeader) resolveAvatarHarness(metadata?.flavor, metadata?.client?.id) else null,
            )
        }
        header.update(headerTitle, subtitle, git.changes, avatar)
    }

    /** SessionView's CLI-outdated pill, until it is dismissed for this version on this machine. */
    private fun updateCliWarning() {
        val warning = CliVersionWarning.of(state.session?.metadata, graph.localSettings.values.value.acknowledgedCliVersions)
        cliWarning.warning = warning
        val visibility = if (warning == null) View.GONE else View.VISIBLE
        if (cliWarning.visibility == visibility) return
        cliWarning.visibility = visibility
        root.requestLayout()
    }

    private fun dismissCliWarning() {
        val acknowledged = cliWarning.warning?.dismissed(graph.localSettings.values.value.acknowledgedCliVersions) ?: return
        graph.localSettings.change { it.copy(acknowledgedCliVersions = acknowledged) }
        updateCliWarning()
    }

    // Worktree tabs

    /** The chat the header speaks for: this one once it exists, the one a stand-in was started beside until then. */
    private val headerSessionId: String
        get() = if (state.session != null) sessionId else pendingChat?.anchorSessionId ?: sessionId

    /** The chip that stands for the open chat: the stand-in while the chat is unnamed, its session from the moment it is not. */
    private val selectedTabId: String get() = pendingChat?.sessionId ?: sessionId

    private val anchorMetadata get() = pendingChat?.anchorSessionId?.let { HomeServices.of(graph).session(it)?.metadata }

    /** `resolveWorktreeTabs`: the checkout's chats, then the ones still being opened beside them, each chat once. */
    private fun resolvedTabs(checkout: ProjectWorktree) =
        resolveWorktreeTabs(checkout.tabs, pendingChatsBeside(pendingChats.values, checkout.tabs.map { it.id }))

    private fun stripTabs(checkout: ProjectWorktree): List<ChatWorktreeTabStrip.Item> {
        val resolved = resolvedTabs(checkout)
        val newChat = context.getString(R.string.session_new_chat)
        return resolved.tabs.map { tab ->
            ChatWorktreeTabStrip.Item(
                id = tab.id,
                title = tab.name ?: newChat,
                shimmers = tab.state == SessionState.Thinking,
                blocked = tab.state == SessionState.PermissionRequired || tab.state == SessionState.InputRequired,
                unread = tab.hasUnread,
                pending = false,
            )
        } + resolved.pending.map { chat ->
            ChatWorktreeTabStrip.Item(id = chat.id, title = newChat, shimmers = true, blocked = false, unread = false, pending = true)
        }
    }

    /**
     * The strip's `+`: a sibling chat, started where the open one runs and
     * configured like it. Its tab and screen are the person's on the press;
     * the start runs underneath, behind any other.
     */
    private fun newTab() {
        val checkout = checkout ?: return
        val openId = if (state.session != null) sessionId else pendingChat?.anchorSessionId ?: return
        val anchor = HomeServices.of(graph).session(openId) ?: return
        val starts = HomeServices.of(graph).pendingChats(context) ?: return
        val picks = if (openId == sessionId) mirror else graph.account.value?.composer?.mirrors?.value?.get(openId)
        val chat = starts.open(context, openId, checkout.tabs.map { it.id }, newSessionLikeSession(anchor, picks))
        switchChat(chat.id)
    }

    /**
     * The stand-in's record changed. Once it names its session the screen
     * becomes that chat: the handover has written the session's draft, and
     * the chat's screen takes this one's place with it. A start that isn't
     * coming has said why, and the writing went back to the chat this was
     * opened beside, which is where the screen goes.
     */
    private fun pendingChatChanged(chat: PendingChat?) {
        if (chat == pendingChat && !pendingMove) return
        pendingChat = chat
        updatePlaceholder()
        val target = when {
            chat?.sessionId != null -> chat.sessionId
            chat?.status == PendingChatStatus.Failed -> chat.anchorSessionId
            else -> null
        } ?: return
        if (stack?.top !== this) {
            pendingMove = true
            return
        }
        pendingMove = false
        switchChat(target)
    }

    /** SessionView's `showTabStrip`. */
    private fun updateTabStrip() {
        val checkout = checkout
        val shown = checkout != null
        if (checkout != null) {
            if (tabStrip.visibility != View.VISIBLE) {
                tabStrip.restore(inheritedStripOffset ?: stripOffsets()[checkout.id] ?: 0.0)
                inheritedStripOffset = null
            }
            tabStrip.update(stripTabs(checkout), selectedTabId)
        }
        val visibility = if (shown) View.VISIBLE else View.GONE
        if (tabStrip.visibility == visibility) return
        tabStrip.visibility = visibility
        root.requestLayout()
    }

    /** Switching swaps the chat in place rather than pushing a screen, so back still returns to the list. */
    private fun selectTab(id: String) {
        if (id == sessionId || id == selectedTabId) return
        switchChat(id)
    }

    private fun switchChat(id: String) {
        val stack = stack ?: return
        keepStripOffset()
        val next = ChatScreen(context, graph, id)
        next.inheritedStripOffset = if (tabStrip.visibility == View.VISIBLE) tabStrip.offset else null
        // A stand-in becoming its chat keeps the person typing, as iOS retargets the one composer.
        next.inheritedFocus = standIn && composer.card.isTextFocused
        if (standIn && composer.card.text.isNotEmpty()) next.inheritedText = composer.card.text to composer.card.selection.end
        stack.replaceTop(next, animated = false)
    }

    /**
     * Archiving the open tab moves the screen to its neighbour first, the
     * tab to its left, so the chat is never seen coming apart; with no tab
     * left the screen goes back.
     */
    private fun tabArchiving(archivedId: String) {
        val checkout = checkout ?: return
        if (archivedId != selectedTabId) return
        val neighbour = neighbouringTabId(resolvedTabs(checkout).tabs, archivedId)
        if (neighbour != null) switchChat(neighbour) else pop()
    }

    /** Where each checkout's strip was last scrolled to, for this account. */
    private fun stripOffsets(): MutableMap<String, Double> {
        val account = graph.account.value ?: return HashMap()
        return synchronized(STRIP_OFFSETS) { STRIP_OFFSETS.getOrPut(account) { HashMap() } }
    }

    private fun keepStripOffset() {
        val checkout = checkout ?: return
        if (tabStrip.visibility != View.VISIBLE) return
        stripOffsets()[checkout.id] = tabStrip.offset
    }

    private fun style(): ChatRowStyle {
        val bubble = BUBBLE_KEYS.indexOf(settings.userMessageBubbleColor).takeIf { it >= 0 }?.let(BubblePalette.All::get) ?: BubblePalette.Gray
        return ChatRowStyle(
            bubble = bubble,
            compactTools = settings.compactToolCalls,
            showLineNumbers = settings.showLineNumbersInToolViews,
            // layout.ts maxWidth (happy-ios ContentWidth): 800 only on a large tablet.
            maxContentWidth = ContentWidth.maxWidth(context),
            root = state.session?.metadata?.path,
            flavor = state.session?.metadata?.flavor,
            env = TextLayouts.Env(Theme.density, Theme.textScale),
            tablet = isTabletLayout,
        )
    }

    private fun requestPlans(pinning: String? = null) {
        if (listWidth <= 0) return
        val rows = state.snapshot.rows
        val currentTurnComplete = isCurrentTurnComplete(state.thinking, state.session?.agentState)
        workGroups = workGroups.observed(sessionId, foreground = true, rows, currentTurnComplete)
        plansRequested += 1
        planRequests.value = PlanRequest(
            rows, settings.groupToolCalls, currentTurnComplete, workGroups,
            expandedTools, copiedMessageId, permissionBusy, diffExpanded, questionStates(), mermaidHeights, style(), listWidth, pinning,
            plansRequested,
        )
    }

    /**
     * On [planner]: a finished turn's work folded under its header (when
     * `groupToolCalls` is on), then every row's plan, made again only where
     * its input or the width changed.
     */
    private fun plan(request: PlanRequest): Planned {
        val display = groupChatRowsForDisplay(request.rows, request.groupWork, collapseCurrentTurn = request.currentTurnComplete)
        val items = chatListItems(display, request.groups::isExpanded)
        val seen = HashSet<String>(items.size)
        val groups = HashMap<String, AgentWorkGroup>()
        val rows = items.map { item ->
            seen += item.key
            val input: Any = when (item) {
                is ChatListItem.Row -> {
                    val row = item.row
                    ChatRowInput(
                        row, expanded = row.key in request.expanded, copied = row.key == request.copied,
                        permissionBusy = request.permissionBusy[row.key], diffExpanded = request.diffExpanded[row.key].orEmpty(),
                        question = request.questions[row.key], diagrams = request.diagrams[row.key].orEmpty(), style = request.style,
                    )
                }
                is ChatListItem.WorkHeader -> {
                    groups[item.group.id] = item.group
                    HeaderInput(item.group, item.placement, request.groups.isExpanded(item.group), request.style)
                }
            }
            val key = PlanKey(input, request.width)
            plans[item.key]?.takeIf { it.first == key }?.second ?: when (input) {
                is HeaderInput -> renderer.workHeader(item.key, input.group, input.placement, input.expanded, request.width, input.style)
                else -> renderer.plan(input as ChatRowInput, request.width)
            }.also { plans[item.key] = key to it }
        }
        plans.keys.retainAll(seen)
        return Planned(renderer.footer(request.width), rows, request.pinning, groups, ChatOlderPaging.boundary(items), request.number)
    }

    // List

    private fun scrolled(distanceFromNewest: Double, distanceFromOldest: Double) {
        header.underlap = ChatScreenLayout.headerBackdropVisible(distanceFromOldest)
        // A docked composer (a tablet, `usesFloatingMobileDock` false) keeps its status rows and the bars above it however far back the reader is.
        val visible = branches.isTablet || ChatScreenLayout.dockDetailsVisible(distanceFromNewest, dockDetailsVisible)
        if (visible != dockDetailsVisible) {
            dockDetailsVisible = visible
            composer.setDetailsVisible(visible, animated = true)
            for (bar in dockBarViews) bar.animate().alpha(if (visible) 1f else 0f).setDuration(DETAILS_FADE_MS).start()
        }
        scrollButton.shown = ChatScreenLayout.showsScrollButton(distanceFromNewest)
        // The next page of history goes on once the oldest row is within a screen.
        oldestDistance = distanceFromOldest
        checkPaging()
    }

    private fun perform(action: ChatRowAction, frame: Rect, hold: Boolean) {
        when (action) {
            is ChatRowAction.CopyMessage -> {
                copy(action.text)
                copiedMessageId = action.id
                requestPlans()
                copiedJob?.cancel()
                copiedJob = scope.launch {
                    delay(COPIED_MS)
                    copiedMessageId = null
                    requestPlans()
                }
            }
            is ChatRowAction.CopyMenu -> showCopyMenu(action.text, frame)
            is ChatRowAction.MarkdownSelectable -> if (graph.localSettings.values.value.markdownCopyV2) {
                push(TextSelectionScreen(context, action.markdown))
            } else {
                showCopyMenu(action.paragraph, frame)
            }
            is ChatRowAction.ToggleTool -> {
                expandedTools = if (action.id in expandedTools) expandedTools - action.id else expandedTools + action.id
                requestPlans(pinning = action.id)
            }
            is ChatRowAction.ToggleGroup -> {
                val group = shownGroups[action.id] ?: return
                workGroups = workGroups.toggled(group)
                requestPlans(pinning = action.id)
            }
            is ChatRowAction.OpenLink -> open(action.url)
            is ChatRowAction.Option -> scope.launch { runCatching { actions.send(sessionId, action.text) } }
            is ChatRowAction.Retry -> scope.launch { runCatching { actions.retry(sessionId, action.localId) } }
            is ChatRowAction.Permission -> answer(action)
            is ChatRowAction.QuestionOption -> {
                val current = questionState(action.toolId) ?: return
                val question = current.form.questions.firstOrNull { it.id == action.questionId } ?: return
                if (!current.canInteract || current.submitting) return
                questionDrafts = questionDrafts + (action.toolId to current.copy(selections = current.selections.toggle(question, action.option)))
                requestPlans()
            }
            is ChatRowAction.QuestionSubmit -> submitQuestion(action.toolId)
            is ChatRowAction.DiffShowMore -> {
                val tool = diffExpanded[action.toolId].orEmpty()
                diffExpanded = diffExpanded + (action.toolId to tool + (action.chunk to tool[action.chunk].orEmpty() + action.file))
                requestPlans(pinning = action.toolId)
            }
            is ChatRowAction.MermaidHeight -> {
                // Planned again off the main thread, only if the box grows.
                val row = mermaidHeights[action.row].orEmpty()
                val kept = MarkdownLayout.mermaidHeightToKeep(action.height, row[action.index], Theme.density.toDouble()) ?: return
                mermaidHeights = mermaidHeights + (action.row to row + (action.index to kept))
                requestPlans()
            }
            is ChatRowAction.OpenMessage -> push(MessageDetailScreen(context, graph, sessionId, action.id))
            is ChatRowAction.OpenFile -> push(com.slopus.happy.ui.details.SessionFileScreen(context, graph, sessionId, action.path))
            // Stem Cells only.
            is ChatRowAction.OpenMedia, is ChatRowAction.OpenArtifact -> Unit
        }
    }

    /** Every question tool's form, with the reader's picks laid over it. */
    private fun questionStates(): Map<String, ChatQuestionState> {
        val agentState = state.session?.agentState
        val states = HashMap<String, ChatQuestionState>()
        for (row in state.snapshot.rows) {
            val tool = (row as? ChatRow.Tool)?.message?.tool ?: continue
            val form = InlineQuestionForm.of(tool, agentState) ?: continue
            states[row.key] = (questionDrafts[row.key] ?: ChatQuestionState(form)).copy(form = form)
        }
        return states
    }

    private fun questionState(toolId: String): ChatQuestionState? {
        val tool = state.snapshot.rows.firstOrNull { it.key == toolId }?.let { (it as? ChatRow.Tool)?.message?.tool } ?: return null
        val form = InlineQuestionForm.of(tool, state.session?.agentState) ?: return null
        return (questionDrafts[toolId] ?: ChatQuestionState(form)).copy(form = form)
    }

    /** Sends a question form's answers. The summary shows at once; if the answer fails, the form comes back as it was. */
    private fun submitQuestion(toolId: String) {
        val current = questionState(toolId) ?: return
        val reply = current.form.reply ?: return
        val tool = (state.snapshot.rows.first { it.key == toolId } as ChatRow.Tool).message.tool
        if (!current.canInteract || current.submitting || !current.selections.allAnswered(current.form.questions)) return
        val answers = current.selections.answers(current.form.questions)
        questionDrafts = questionDrafts + (toolId to current.copy(sent = answers, submitting = true))
        requestPlans()
        scope.launch {
            val result = runCatching {
                when (reply) {
                    is InlineQuestionForm.Reply.Permission -> actions.answerPermission(
                        sessionId,
                        PermissionAnswer(reply.id, approved = true, decision = PermissionDecision.Approved, updatedInput = InlineQuestionForm.askUserQuestionInput(tool, answers)),
                    )
                    is InlineQuestionForm.Reply.Communication ->
                        actions.answerQuestion(sessionId, reply.id, InlineQuestionForm.communicationAnswers(current.form.questions, answers), reply.kind)
                }
            }
            val latest = questionDrafts[toolId] ?: return@launch
            questionDrafts = questionDrafts + (toolId to latest.copy(submitting = false, sent = if (result.isFailure) null else latest.sent))
            requestPlans()
        }
    }

    /**
     * Sends a permission answer, then switches the session's mode when the
     * button asks for it. A failed answer leaves the buttons as they were,
     * so the reader can try again.
     */
    private fun answer(action: ChatRowAction.Permission) {
        if (action.toolId in permissionBusy) return
        permissionBusy = permissionBusy + (action.toolId to action.choice)
        requestPlans()
        scope.launch {
            val sent = runCatching { actions.answerPermission(sessionId, action.answer) }
            if (sent.isSuccess && action.mode != null) {
                runCatching { actions.setModes(sessionId, mapOf(AgentModeField.PERMISSION_MODE to action.mode)) }
            }
            permissionBusy = permissionBusy - action.toolId
            requestPlans()
        }
    }

    private fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    /** LongPressCopyable: the platform's popup with Copy, over the message. */
    private fun showCopyMenu(text: String, frame: Rect) {
        val anchor = View(context)
        root.addView(anchor)
        val inList = frame.offsetBy(px(list.left), px(list.top))
        anchor.setFrame(Rect(inList.maxX - 1, inList.minY, 1.0, 1.0))
        val popup = PopupMenu(context, anchor, Gravity.END)
        popup.menu.add(context.getString(R.string.common_copy))
        popup.setOnMenuItemClickListener {
            copy(text)
            true
        }
        popup.setOnDismissListener { root.removeView(anchor) }
        popup.show()
    }

    private fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Alerts.show(context, context.getString(R.string.common_error), url)
        }
    }

    private fun openInfo() {
        if (state.session == null) return
        push(com.slopus.happy.ui.details.SessionInfoScreen(context, graph, sessionId))
    }

    /** happy-app's tablet branches for this window (happy-ios `tabletBranches`). */
    private val branches: TabletBranches
        get() = TabletBranches(isTabletLayout, isLandscape = root.width > root.height, headerHeight = TabletShell.HEADER_HEIGHT)

    override fun onTabletLayoutChanged() {
        super.onTabletLayoutChanged()
        applyTabletBranch()
        requestPlans()
    }

    /**
     * ChatHeaderView's `glassEnabled` and SessionView's `headerRight`: a
     * phone's capsule and avatar over the scrim, or a tablet's opaque bar
     * with the plain title and no back or avatar.
     */
    private fun applyTabletBranch() {
        header.tablet = !branches.chatHeaderGlass
        header.headerMaxWidth = ContentWidth.headerMaxWidth(context)
        tabStrip.glass = branches.worktreeStripGlass
        composer.zenMode = isZenMode
        if (branches.isTablet && !dockDetailsVisible) {
            dockDetailsVisible = true
            composer.setDetailsVisible(true, animated = false)
            for (bar in dockBarViews) bar.alpha = 1f
        }
        root.requestLayout()
    }

    override fun onBackPressed(): Boolean {
        if (composer.card.isTextFocused) {
            composer.card.blur()
        }
        return false
    }

    override fun onAppear() {
        super.onAppear()
        // voiceHooks.onSessionFocus: a running call follows the chat on screen.
        VoiceCoordinator.of(graph).focus(sessionId)
        shown = true
        checkPaging()
        if (pendingMove) pendingChatChanged(pendingChat)
        if (inheritedFocus) {
            inheritedFocus = false
            composer.card.focus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The screen standing on a record that has settled or failed retires it on the way out.
        val chat = pendingChat ?: return
        if (chat.sessionId != null || chat.status == PendingChatStatus.Failed) HomeServices.of(graph).pendingChatsIfMade?.dismiss(chat.id)
    }

    override fun onDisappear() {
        super.onDisappear()
        shown = false
        keepStripOffset()
        composer.card.blur()
    }

    private inner class Root(context: Context) : ManualLayout(context), ThemeAware {
        init {
            addView(list)
            addView(placeholder)
            addView(scrim)
            addView(header)
            addView(tabStrip)
            addView(cliWarning)
            for (bar in dockBarViews) addView(bar)
            addView(composer)
            addView(scrollButton)
            addView(composer.suggestionList)
            applyTheme()
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(Palette.surface))
        }

        override fun layoutSubviews(width: Double, height: Double) {
            header.safeTop = safeArea.top
            val keyboard = keyboard
            val bars = dockBars
            val branches = branches
            val layout = ChatScreenLayout(
                ChatScreenLayout.Input(
                    size = Size(width, height),
                    safeArea = LayoutInsets(header.headerBottom, safeArea.left, safeArea.bottom, safeArea.right),
                    keyboardTop = if (keyboard > 0) height - safeArea.bottom - keyboard else null,
                    accessories = bars.map { it.second },
                    composerHeight = composer.composerHeight(width),
                    composerCardOffset = composer.cardOffset(width),
                    maxContentWidth = ContentWidth.maxWidth(context),
                    // TabletBranches: chatFloatingDock and chatInputPaddingH.
                    docked = branches.isTablet,
                    accessoryPaddingH = branches.chatInputPaddingH,
                    headerAccessory = if (tabStrip.visibility == View.VISIBLE) WorktreeTabStripLayout.Metrics.height else 0.0,
                ),
            )
            if (cliWarning.visibility == View.VISIBLE) cliWarning.place(CliWarningPillLayout(width, layout.headerBottom, cliWarning.measurable))
            layout.headerAccessory?.takeIf { tabStrip.visibility == View.VISIBLE }?.let { frame ->
                tabStrip.maxWidth = ContentWidth.headerMaxWidth(context)
                tabStrip.setFrame(frame)
            }
            // Insets first, so the list lays out with them in this pass.
            list.setEdgeInsets(layout.newestEdgeInset, layout.oldestEdgeInset)
            list.setFrame(layout.list)
            header.setFrame(Rect(0.0, 0.0, width, header.headerBottom))
            scrim.setFrame(layout.scrim)
            val composerFrame = layout.composer.offsetBy(layout.dock.minX, layout.dock.minY)
            composer.setFrame(composerFrame)
            composer.suggestionFrame(composerFrame.width)?.let { composer.suggestionList.setFrame(it.offsetBy(composerFrame.minX, composerFrame.minY)) }
            for ((bar, frame) in bars.zip(layout.accessories)) bar.first.setFrame(frame.offsetBy(layout.dock.minX, layout.dock.minY))
            scrollButton.setFrame(layout.scrollButton)
            placeholder.setFrame(if (composer.visibility == View.GONE) Rect(0.0, 0.0, width, height) else layout.placeholder)
            if (layout.list.width != listWidth) {
                listWidth = layout.list.width
                requestPlans()
            }
        }
    }

    /** AgentContentView's LinearGradient: the fade behind the dock that lets messages scroll under it. */
    private class ChatScrimView(context: Context) : View(context), ThemeAware {
        private val paint = Paint()
        private var shaderKey: Pair<Int, Boolean>? = null

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun applyTheme() {
            shaderKey = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (height <= 0) return
            val key = height to Theme.isDark
            if (shaderKey != key) {
                val dark = Theme.isDark
                val base = if (dark) 0x000000 else 0xFFFFFF
                val stops = if (dark) listOf(0.0, 0.20, 0.66) else listOf(0.0, 0.18, 0.74)
                val colors = stops.map { ((it * 255).toInt() shl 24) or base }.toIntArray()
                paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), colors, floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
                shaderKey = key
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    /** ChatList's jump-to-newest button: a 32dp circle with a hairline ring, fading in and out. */
    private class ScrollButton(context: Context) : ManualLayout(context), ThemeAware {
        private val shape = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        private val icon = IconView(context, Icon.octicons("arrow-down"), 14.0, Palette.text)

        var shown = false
            set(value) {
                if (field == value) return
                field = value
                animate().cancel()
                if (value) visibility = VISIBLE
                animate().alpha(if (value) 1f else 0f).setDuration(200).withEndAction { if (!shown) visibility = GONE }.start()
            }

        init {
            background = shape
            addView(icon)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.chat_scroll_to_bottom)
            elevation = dp(1.0)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                    outline.alpha = 0.1f
                }
            }
            applyTheme()
        }

        override fun applyTheme() {
            applyFill()
            shape.setStroke(1, Theme.color(Palette.divider))
        }

        private fun applyFill() {
            val surface = Theme.color(Palette.surface)
            val alpha = if (isPressed) 0.7 else 0.9
            shape.setColor(((alpha * 255).toInt() shl 24) or (surface and 0xFFFFFF))
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            applyFill()
        }

        override fun layoutSubviews(width: Double, height: Double) = icon.setFrame(Rect(0.0, 0.0, width, height))
    }

    private companion object {
        /** USER_MESSAGE_BUBBLE_COLORS, in [BubblePalette.All]'s order. */
        val BUBBLE_KEYS = listOf("gray", "blue", "green", "purple", "rose", "sand")
        /** Where each checkout's strip was last scrolled to, per account, so a screen on the same checkout comes back where the finger left it. */
        val STRIP_OFFSETS = java.util.WeakHashMap<com.slopus.happy.sync.AccountSync, MutableMap<String, Double>>()
        const val COPIED_MS = 2000L
        const val DETAILS_FADE_MS = 200L
        const val FORM_KIND = "form"
        const val NOT_PORTED = "Not available in this Android build yet."
    }
}
