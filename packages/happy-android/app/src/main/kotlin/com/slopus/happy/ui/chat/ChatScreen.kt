package com.slopus.happy.ui.chat

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
import android.widget.ProgressBar
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.chat.ChatFeed
import com.slopus.happy.chat.ChatMessagesSource
import com.slopus.happy.chat.ChatRow
import com.slopus.happy.chat.ChatState
import com.slopus.happy.chat.InlineQuestionForm
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
import com.slopus.happy.ui.composer.ChatComposerView
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
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
class ChatScreen(context: Context, private val graph: AppGraph, val sessionId: String) : Screen(context) {
    private val actions = SyncChatActions { graph.account.value }
    private val messages: ChatMessagesSource
        get() = graph.account.value?.let { SyncChatMessagesSource(it.messages, it.sender) } ?: StoreChatMessagesSource(graph.store)

    private val list = ChatListView(context)
    private val header = ChatHeaderView(context)
    private val scrim = ChatScrimView(context)
    private val composer = ChatComposerView(context, sessionId, actions, scope)
    private val scrollButton = ScrollButton(context)
    private val spinner = ProgressBar(context)
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
    private var dockDetailsVisible = true
    private var loadingOlder = false
    private var listWidth = 0.0
    private val planRequests = MutableStateFlow<PlanRequest?>(null)
    /** The plans made so far, by row id, with what each was made from; touched only on [planner]. */
    private val plans = HashMap<String, Pair<PlanKey, ChatRowPlan>>()
    private val planner = Dispatchers.Default.limitedParallelism(1)

    private data class PlanKey(val input: ChatRowInput, val width: Double)

    private data class PlanRequest(
        val rows: List<ChatRow>,
        val expanded: Set<String>,
        val copied: String?,
        val permissionBusy: Map<String, PermissionChoice>,
        val diffExpanded: Map<String, Map<Int, Set<Int>>>,
        val questions: Map<String, ChatQuestionState>,
        val style: ChatRowStyle,
        val width: Double,
        /** A row the reader just opened or closed. */
        val pinning: String? = null,
    )

    private class Planned(val footer: ChatRowPlan, val rows: List<ChatRowPlan>, val pinning: String?)

    override fun createView(): View = root

    override fun viewCreated(view: View) {
        header.backButton.setOnClickListener { pop() }
        header.titleControl.setOnClickListener { openInfo() }
        header.avatarControl.setOnClickListener { openInfo() }
        list.onScroll = ::scrolled
        list.onAction = ::perform
        composer.onHeightChanged = { root.requestLayout() }
        composer.onSent = { list.scrollToNewest(animated = true) }
        scrollButton.setOnClickListener { list.scrollToNewest(animated = true) }
        scrollButton.alpha = 0f
        scrollButton.visibility = View.GONE

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
            planRequests.filterNotNull()
                .map { request -> plan(request) }
                .flowOn(planner)
                .collect { list.submit(it.footer, it.rows, it.pinning) }
        }
    }

    private fun apply(state: ChatState, settings: Settings, mirror: ComposerMirror) {
        this.state = state
        this.settings = settings
        list.isLoadingOlder = state.snapshot.isLoadingOlder
        updateHeader()
        composer.update(state, mirror, settings.agentDefaultOverrides)
        spinner.visibility = if (!state.snapshot.loaded && state.snapshot.rows.isEmpty()) View.VISIBLE else View.GONE
        requestPlans()
        root.requestLayout()
    }

    private fun updateHeader() {
        val session = state.session
        val title = when {
            !state.sessionLoaded -> ""
            session == null -> context.getString(R.string.errors_session_deleted)
            else -> session.title ?: context.getString(R.string.session_new_chat)
        }
        val git = resolveChatHeaderGit(session?.metadata, null)
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
        header.update(title, git.subtitle, git.changes, avatar)
    }

    private fun style(): ChatRowStyle {
        val bubble = BUBBLE_KEYS.indexOf(settings.userMessageBubbleColor).takeIf { it >= 0 }?.let(BubblePalette.All::get) ?: BubblePalette.Gray
        return ChatRowStyle(
            bubble = bubble,
            compactTools = settings.compactToolCalls,
            showLineNumbers = settings.showLineNumbersInToolViews,
            maxContentWidth = ChatScreenLayout.Metrics.regularMaxContentWidth,
            root = state.session?.metadata?.path,
            flavor = state.session?.metadata?.flavor,
            env = TextLayouts.Env(Theme.density, Theme.textScale),
        )
    }

    private fun requestPlans(pinning: String? = null) {
        if (listWidth <= 0) return
        planRequests.value = PlanRequest(state.snapshot.rows, expandedTools, copiedMessageId, permissionBusy, diffExpanded, questionStates(), style(), listWidth, pinning)
    }

    /** On [planner]: every row's plan, made again only where its input or the width changed. */
    private fun plan(request: PlanRequest): Planned {
        val seen = HashSet<String>(request.rows.size)
        val rows = request.rows.map { row ->
            val input = ChatRowInput(
                row, expanded = row.key in request.expanded, copied = row.key == request.copied,
                permissionBusy = request.permissionBusy[row.key], diffExpanded = request.diffExpanded[row.key].orEmpty(),
                question = request.questions[row.key], style = request.style,
            )
            val key = PlanKey(input, request.width)
            seen += row.key
            plans[row.key]?.takeIf { it.first == key }?.second ?: renderer.plan(input, request.width).also { plans[row.key] = key to it }
        }
        plans.keys.retainAll(seen)
        return Planned(renderer.footer(request.width), rows, request.pinning)
    }

    // List

    private fun scrolled(distanceFromNewest: Double, distanceFromOldest: Double) {
        header.underlap = ChatScreenLayout.headerBackdropVisible(distanceFromOldest)
        val visible = ChatScreenLayout.dockDetailsVisible(distanceFromNewest, dockDetailsVisible)
        if (visible != dockDetailsVisible) {
            dockDetailsVisible = visible
            composer.setDetailsVisible(visible, animated = true)
        }
        scrollButton.shown = ChatScreenLayout.showsScrollButton(distanceFromNewest)
        // The next page of history goes on once the oldest row is within a screen.
        val snapshot = state.snapshot
        if (!loadingOlder && snapshot.hasMoreOlder && !snapshot.isLoadingOlder && distanceFromOldest < px(list.height)) {
            loadingOlder = true
            scope.launch {
                runCatching { messages.loadOlder(sessionId) }
                loadingOlder = false
            }
        }
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
            is ChatRowAction.ToggleTool -> {
                expandedTools = if (action.id in expandedTools) expandedTools - action.id else expandedTools + action.id
                requestPlans(pinning = action.id)
            }
            is ChatRowAction.ToggleGroup -> {
                expandedTools = if (action.id in expandedTools) expandedTools - action.id else expandedTools + action.id
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
            is ChatRowAction.OpenMessage -> Unit
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
        Alerts.show(context, state.session?.title ?: context.getString(R.string.session_new_chat), NOT_PORTED)
    }

    override fun onBackPressed(): Boolean {
        if (composer.card.isTextFocused) {
            composer.card.blur()
        }
        return false
    }

    override fun onDisappear() {
        super.onDisappear()
        composer.card.blur()
    }

    private inner class Root(context: Context) : ManualLayout(context), ThemeAware {
        init {
            addView(list)
            addView(spinner)
            addView(scrim)
            addView(header)
            addView(composer)
            addView(scrollButton)
            applyTheme()
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(Palette.surface))
            spinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(Theme.color(Palette.textSecondary))
        }

        override fun layoutSubviews(width: Double, height: Double) {
            header.safeTop = safeArea.top
            val keyboard = keyboard
            val layout = ChatScreenLayout(
                ChatScreenLayout.Input(
                    size = Size(width, height),
                    safeArea = LayoutInsets(header.headerBottom, safeArea.left, safeArea.bottom, safeArea.right),
                    keyboardTop = if (keyboard > 0) height - safeArea.bottom - keyboard else null,
                    composerHeight = composer.composerHeight(width),
                    composerCardOffset = composer.cardOffset(width),
                ),
            )
            // Insets first, so the list lays out with them in this pass.
            list.setEdgeInsets(layout.newestEdgeInset, layout.oldestEdgeInset)
            list.setFrame(layout.list)
            header.setFrame(Rect(0.0, 0.0, width, header.headerBottom))
            scrim.setFrame(layout.scrim)
            composer.setFrame(layout.composer.offsetBy(layout.dock.minX, layout.dock.minY))
            scrollButton.setFrame(layout.scrollButton)
            val s = 32.0
            spinner.setFrame(Rect(layout.placeholder.midX - s / 2, layout.placeholder.midY - s / 2, s, s))
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
        const val COPIED_MS = 2000L
        const val NOT_PORTED = "Not available in this Android build yet."
    }
}
