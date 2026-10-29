package com.slopus.happy.ui.home

import android.content.Context
import android.provider.Settings
import com.slopus.happy.R
import com.slopus.happy.chat.AgentInputPrimaryAction
import com.slopus.happy.chat.ComposerChipSet
import com.slopus.happy.home.NewSessionServices
import com.slopus.happy.home.StartResult
import com.slopus.happy.layout.HomeDockFade
import com.slopus.happy.model.BotRequest
import com.slopus.happy.model.HomeDockKind
import com.slopus.happy.model.NewSessionOptions
import com.slopus.happy.model.StartButton
import com.slopus.happy.model.StartProgress
import com.slopus.happy.model.isValidBotName
import com.slopus.happy.model.resolveStartButton
import com.slopus.happy.model.resolveStartProgress
import com.slopus.happy.model.sanitizeBotName
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.composer.AgentInputCardView
import com.slopus.happy.ui.composer.AgentInputPrimaryStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Home's dock (HomeDock.tsx with MainView.tsx's `handleHomePromptSubmit`;
 * happy-ios `HomeDockController`): the resting capsule, the composer it
 * opens, and the start they share.
 *
 * The prompt is home's own and lives while home does; the pickers write the
 * account's new-session draft, which outlives it. A start runs through the
 * [NewSessionServices.starter]; the composer stays open, reporting each
 * step, until the session is open, and Stop gives it back at once.
 *
 * Where the computer's Happy Agent can make bots, the composer makes one
 * instead: the text is the bot's name.
 */
class HomeDockController(
    private val context: Context,
    val restingView: HomeDockView,
    val services: NewSessionServices,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    /** Where the composer is shown and what a start opens. */
    interface Host {
        /** Puts the open composer over home. */
        fun present(focus: HomeDockFocusView)

        /** Takes it away again. */
        fun dismiss(focus: HomeDockFocusView)

        /** Home is on screen and nothing covers it. */
        val canPresent: Boolean

        fun openSession(id: String)
    }

    private val starter = services.starter

    /** What was typed in the composer: home's own, kept while home is. */
    var prompt = ""
        private set

    /** Send was tapped and the start is running. */
    private var launching = false

    private var focus: HomeDockFocusView? = null

    val options: NewSessionOptions? get() = services.options.value

    init {
        restingView.entryButton.setOnClickListener { openFocus() }
        restingView.sendButton.setOnClickListener { scope.launch { submit() } }
        scope.launch {
            combine(services.options, services.draft, services.bot, services.createsTask, starter.phase) { _, _, _, _, _ -> }.collect { render() }
        }
        render()
    }

    /** Home shows the dock: false on Link Your Computer. */
    var isAvailable = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) focus?.close()
            updateVisibility()
        }

    val isOpen: Boolean get() = focus != null

    // A capability refresh must not silently reinterpret a Task/Bot press as
    // a session. Reconciliation changes the selection; start validates it again.
    val createsBot: Boolean get() = services.bot.value.creating
    val createsTask: Boolean get() = !createsBot && services.createsTask.value
    val offeredKinds: List<HomeDockKind> get() = HomeDockKind.offered(options?.supportsBots == true, options?.supportsTasks == true)
    val kind: HomeDockKind get() = HomeDockKind.current(createsBot, createsTask)

    val isStarting: Boolean get() = launching || starter.phase.value != null

    /** [origin] is what a dock that comes into view comes back from. */
    private fun updateVisibility(origin: HomeDockFade.Origin = HomeDockFade.Origin.Appearing) {
        // The capsule is replaced rather than covered while the composer is
        // open: the composer sits higher, and the capsule's button showed below it.
        val hidden = !isAvailable || focus != null
        val appears = restingView.visibility != android.view.View.VISIBLE && !hidden
        restingView.visibility = if (hidden) android.view.View.GONE else android.view.View.VISIBLE
        if (hidden) {
            restingView.finishFade()
        } else if (appears) {
            val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            restingView.fadeIn(HomeDockFade.shown(origin, onWindow = restingView.isAttachedToWindow, reduceMotion = reduceMotion))
        }
    }

    /** A bot needs a name the daemon takes; a session, a prompt. */
    val canSubmit: Boolean
        get() {
            if (isStarting) return false
            return if (createsBot) isValidBotName(services.bot.value.name) else prompt.isNotBlank()
        }

    fun setPrompt(text: String) {
        if (text == prompt) return
        prompt = text
        render()
    }

    /** The name field, as typed: a pasted line break becomes a space and the daemon's ceiling is kept. */
    fun setBotName(name: String): String {
        val sanitized = sanitizeBotName(name)
        services.setBotName(sanitized)
        return sanitized
    }

    /** Session | Bot. */
    fun setCreatesBot(creating: Boolean) {
        if (isStarting) return
        services.setCreatesBot(creating)
    }

    fun setKind(kind: HomeDockKind) {
        if (isStarting || kind !in offeredKinds) return
        when (kind) {
            HomeDockKind.Session -> { services.setCreatesBot(false); services.setCreatesTask(false) }
            HomeDockKind.Bot -> services.setCreatesBot(true)
            HomeDockKind.Task -> services.setCreatesTask(true)
        }
    }

    fun render() {
        restingView.prompt = prompt
        restingView.sendButton.appearance = when {
            isStarting -> HomeDockSendButton.Appearance.Busy
            canSubmit -> HomeDockSendButton.Appearance.Send
            else -> HomeDockSendButton.Appearance.Idle
        }
        focus?.render()
    }

    // Focus

    /** Opens the composer; false when it can't be (`requestHomeDockFocus`). */
    fun openFocus(): Boolean {
        if (focus != null) return true
        if (!isAvailable || !host.canPresent) return false
        val view = HomeDockFocusView(context, this)
        focus = view
        updateVisibility()
        host.present(view)
        return true
    }

    /** The composer finished closing. */
    fun focusDidClose(view: HomeDockFocusView) {
        if (focus !== view) return
        host.dismiss(view)
        focus = null
        updateVisibility(HomeDockFade.Origin.Focus)
        render()
    }

    /** Back with the composer open: it closes, or, while a session starts, refuses. */
    fun onBackPressed(): Boolean {
        val view = focus ?: return false
        view.backdropTapped()
        return true
    }

    // Start

    /**
     * `handleHomePromptSubmit`: the prompt trimmed into the draft, then the
     * start. The prompt is cleared once the session is open; true then.
     */
    suspend fun submit(): Boolean {
        if (!canSubmit) return false
        if (createsBot) {
            val bot = services.bot.value
            val sessionId = start(BotRequest(bot.name.trim(), bot.faceSeed)) ?: return false
            // The next bot starts from a clean name and fresh faces; the
            // composer goes back to offering a session, the ordinary case.
            services.botCreated(bot)
            services.setCreatesBot(false)
            open(sessionId)
            return true
        }
        val text = prompt.trim()
        if (text.isEmpty()) return false
        services.setInput(text)
        val task = createsTask
        val sessionId = start(null, createsTask = task) ?: return false
        if (task) services.setCreatesTask(false)
        prompt = ""
        open(sessionId)
        return true
    }

    /** Runs a start; the session's id once it is open. One at a time: Send is busy meanwhile. */
    private suspend fun start(bot: BotRequest?, createsTask: Boolean = false): String? {
        if (launching) return null
        launching = true
        val startedDraft = services.draft.value.copy(input = "", updatedAt = 0)
        val startedBot = services.bot.value
        val startedTask = services.createsTask.value
        fun current() = services.draft.value.copy(input = "", updatedAt = 0) == startedDraft &&
            services.bot.value == startedBot && services.createsTask.value == startedTask
        render()
        val result = try {
            starter.start(bot = bot, createsTask = createsTask, isCurrentTarget = ::current, confirmDirectory = ::confirmDirectoryCreation)
        } finally {
            launching = false
        }
        render()
        if (!current()) return null
        return when (result) {
            is StartResult.Started -> result.sessionId
            is StartResult.Refused -> {
                showError(result.refusal.message)
                null
            }
            is StartResult.Failed -> {
                showError(result.message)
                null
            }
            is StartResult.NotSent -> {
                Alerts.show(context, context.getString(R.string.image_upload_not_supported_title), context.getString(R.string.image_upload_not_supported_message))
                null
            }
            StartResult.Abandoned -> null
        }
    }

    private fun open(sessionId: String) {
        render()
        host.openSession(sessionId)
    }

    /** Stop: the composer back now, the session put down unwatched. */
    fun cancelStart() {
        starter.cancel()
        render()
    }

    private fun showError(message: String) = Alerts.show(context, context.getString(R.string.common_error), message)

    private suspend fun confirmDirectoryCreation(directory: String): Boolean = Alerts.confirm(
        context,
        context.getString(R.string.home_create_directory_title),
        context.getString(R.string.home_create_directory_message, directory),
        confirmTitle = context.getString(R.string.common_create),
    )

    // What the open composer draws

    /** The status line while a start runs ("Starting Claude Code…"), or null. */
    fun progressLabel(): String? {
        val options = options ?: return null
        return when (val progress = resolveStartProgress(starter.phase.value, options.agent.name, options.picksWorkspaces, createsBot, createsTask)) {
            null -> null
            is StartProgress.CreatingWorkspace -> context.getString(if (progress.workspace) R.string.home_dock_creating_workspace else R.string.home_dock_creating_worktree)
            is StartProgress.Starting -> context.getString(R.string.home_dock_starting, progress.agentName)
            StartProgress.CreatingBot -> context.getString(R.string.home_dock_creating_bot)
            StartProgress.CreatingTask -> context.getString(R.string.home_dock_creating_task)
            StartProgress.Avatar -> context.getString(R.string.home_dock_bot_avatar_progress)
            is StartProgress.Opening -> context.getString(when {
                progress.bot -> R.string.home_dock_opening_bot
                progress.task -> R.string.home_dock_opening_task
                else -> R.string.home_dock_opening
            })
        }
    }

    /** The one button at the end of the composer. */
    fun primary(): StartButton = when {
        // Resolving, before the starter names its first step.
        launching && starter.phase.value == null -> StartButton.Busy
        else -> resolveStartButton(canSubmit, starter.phase.value, canCancel = true)
    }

    /** The card for the draft (`renderFocusedComposer`), or for a bot's name. */
    fun cardConfiguration(): AgentInputCardView.Configuration {
        val options = options
        val primary = primary()
        val starting = starter.phase.value != null || launching
        val action = when (primary) {
            StartButton.Send -> AgentInputPrimaryAction.Send
            StartButton.Stop -> AgentInputPrimaryAction.Stop
            StartButton.Busy -> AgentInputPrimaryAction.Send
            StartButton.Idle -> AgentInputPrimaryAction.Idle
        }
        val stopLabel = context.getString(R.string.composer_stop)
        if (createsBot) {
            return AgentInputCardView.Configuration(
                placeholder = context.getString(R.string.home_dock_bot_name_placeholder),
                primaryAction = action,
                isPrimaryBusy = primary == StartButton.Busy,
                primaryAccessibilityLabel = if (primary == StartButton.Stop) stopLabel else context.getString(R.string.home_dock_create_bot),
                primaryStyle = AgentInputPrimaryStyle.NewSession,
                refusesControls = starting,
                capitalizesWords = true,
            )
        }
        if (createsTask) {
            return AgentInputCardView.Configuration(
                placeholder = context.getString(R.string.home_dock_task_placeholder),
                showsAdd = true,
                primaryAction = action,
                isPrimaryBusy = primary == StartButton.Busy,
                primaryAccessibilityLabel = if (primary == StartButton.Stop) stopLabel else context.getString(R.string.home_dock_create_task),
                primaryStyle = AgentInputPrimaryStyle.NewSession,
                refusesControls = starting,
            )
        }
        val hasHarness = options?.hasHarness == true
        fun option(o: com.slopus.happy.model.PickerOption) = ComposerChipSet.Option(o.key, o.name, o.disabled)
        val permission = if (hasHarness && options?.permission != null) {
            options.permissionLabel?.let { label ->
                ComposerChipSet.Chip(
                    label,
                    context.getString(R.string.agent_input_permission_mode_title),
                    listOf(ComposerChipSet.Section(null, options.permissions.map(::option))),
                    options.permission.key,
                )
            }
        } else {
            null
        }
        val modelTitle = context.getString(R.string.agent_input_model_title)
        val modelSections = if (hasHarness && options?.model != null && options.models.any { it.options.isNotEmpty() }) {
            options.models.map { section -> ComposerChipSet.Section(section.title ?: modelTitle, section.options.map(::option)) }
        } else {
            null
        }
        val model = options?.let {
            ComposerChipSet.Chip(ComposerChipSet.truncateModelLabel(it.modelChip), modelTitle, modelSections, it.model?.key)
        }
        val effort = if (hasHarness) {
            options?.effort?.let { current ->
                ComposerChipSet.Chip(
                    current.name,
                    context.getString(R.string.agent_input_effort_title),
                    listOf(ComposerChipSet.Section(null, options.efforts.map(::option))),
                    current.key,
                )
            }
        } else {
            null
        }
        return AgentInputCardView.Configuration(
            placeholder = options?.let { promptPlaceholder(it) } ?: "",
            showsAdd = true,
            permission = permission,
            model = model,
            effort = effort,
            primaryAction = action,
            isPrimaryBusy = primary == StartButton.Busy,
            primaryAccessibilityLabel = if (primary == StartButton.Stop) stopLabel else context.getString(R.string.composer_send),
            primaryStyle = AgentInputPrimaryStyle.NewSession,
            refusesControls = starting,
        )
    }

    /** NewSessionChoices `promptPlaceholder`. */
    private fun promptPlaceholder(options: NewSessionOptions): String = when (options.agent.key) {
        "claude" -> context.getString(R.string.home_dock_ask, "Claude Code")
        "codex" -> context.getString(R.string.home_dock_ask, "Codex")
        else -> context.getString(R.string.home_dock_ask, options.agent.name)
    }
}
