package com.slopus.happy.ui.composer

import android.content.Context
import android.view.HapticFeedbackConstants
import com.slopus.happy.R
import com.slopus.happy.attachments.AttachmentNotice
import com.slopus.happy.attachments.planAttachmentSend
import com.slopus.happy.autocomplete.COMPOSER_PREFIXES
import com.slopus.happy.autocomplete.SuggestionList
import com.slopus.happy.autocomplete.SuggestionSource
import com.slopus.happy.autocomplete.applySuggestion
import com.slopus.happy.autocomplete.findActiveWord
import com.slopus.happy.chat.AgentInputDesktopSend
import com.slopus.happy.chat.AgentInputPrimaryAction
import com.slopus.happy.chat.ChatActions
import com.slopus.happy.chat.ChatState
import com.slopus.happy.chat.ComposerChipSet
import com.slopus.happy.chat.ComposerModeResolver
import com.slopus.happy.chat.ComposerModes
import com.slopus.happy.chat.ComposerStatus
import com.slopus.happy.chat.SessionComposerRules
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.composer.AgentInputLayout
import com.slopus.happy.layout.composerStatus
import com.slopus.happy.layout.sessionStatusAttention
import com.slopus.happy.layout.sessionStatusDisconnected
import com.slopus.happy.layout.sessionStatusOnline
import com.slopus.happy.layout.sessionStatusThinking
import com.slopus.happy.model.SessionState
import com.slopus.happy.model.settings.AgentDefaultOverrides
import com.slopus.happy.sync.AgentModeField
import com.slopus.happy.sync.ComposerMirror
import com.slopus.happy.text.plural
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.home.StatusDotView
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

/**
 * The chat's composer (AgentInput.tsx under SessionView; happy-ios
 * `SessionComposerView`): the session's status over the card, which sends,
 * stops, and switches the permission, model and effort. [AgentInputLayout]
 * places the status row and the card.
 *
 * In a window wider than 700 (happy-app's `screenWidth`, the window's, not
 * the chat column's) the card is the flat panel with the gear, Stop, image
 * and send (`desktopActionControls`). The same text view carries across the
 * line as the window is resized.
 */
class ChatComposerView(
    context: Context,
    private val sessionId: String,
    private val actions: ChatActions,
    private val scope: CoroutineScope,
) : ManualLayout(context), ThemeAware {
    val card = AgentInputCardView(context)
    private val statusDot = StatusDotView(context)
    private val statusLabel = Label(context, TextStyles.composerStatus, Palette.textSecondary, maxLines = 1)

    /** The composer's height may have changed. */
    var onHeightChanged: (() -> Unit)? = null
    /** A message was queued. */
    var onSent: (() -> Unit)? = null
    /** The microphone (the primary with nothing typed), or the status row during a call: open the voice screen. */
    var onVoice: (() -> Unit)? = null

    /**
     * The call's state while one runs ("Connecting…", "Listening", …): the
     * status row says "Voice Assistant · …" in blue over the session's own
     * status, and a tap on it opens the voice screen.
     */
    var voiceStatus: String? = null
        set(value) {
            if (field == value) return
            field = value
            applyStatus()
            requestLayout()
            onHeightChanged?.invoke()
        }

    private var state: ChatState? = null
    private var mirror = ComposerMirror()
    /** Picks shown at once, ahead of the mirror catching up. */
    private var picks: Map<AgentModeField, String?> = emptyMap()
    private var overrides: AgentDefaultOverrides? = null
    private var modes = ComposerModes.None
    private var status: ComposerStatus? = null
    private var vibingWord = ComposerStatus.randomVibingWord()
    private var restoredDraft = false
    private var isSending = false
    private var isAborting = false
    private var stopRequested = false
    private var detailsVisible = true
    private val images = ComposerImagePicker(context, scope)

    /**
     * The suggestions for the word at the caret (`/` commands, `@` files).
     * It hangs above the composer, outside its bounds, so the screen hosts
     * it where touches reach it and places it at [suggestionFrame].
     */
    val suggestionList = ComposerSuggestionListView(context).apply { visibility = GONE }
    /** Where suggestions come from; none, none show. */
    var suggestionSource: SuggestionSource? = null
    /** The suggestion list showed, hid or changed its rows: the screen places it again. */
    var onSuggestionsChanged: (() -> Unit)? = null
    /** useActiveWord: the prefixed word up to the caret, the query. */
    private var activeQuery: String? = null
    /** useActiveSuggestions: the last list and its highlighted row, kept while a newer query runs. */
    private var suggestions = SuggestionList()
    private var suggestionJob: Job? = null
    private var isSuggestionListShown = false

    /** Text, or a picture waiting. */
    private val hasComposerContent get() = card.hasContent || images.images.isNotEmpty()

    init {
        clipChildren = false
        addView(statusDot)
        addView(statusLabel)
        addView(card)
        card.onHeightChanged = { requestLayout(); onHeightChanged?.invoke() }
        card.onTextChanged = {
            applyConfiguration()
            updateActiveWord()
            if (restoredDraft) scope.launch { runCatching { actions.setDraft(sessionId, card.text) } }
        }
        card.onSelectionChanged = ::updateActiveWord
        // Return, or a hardware Tab, picks the highlighted suggestion instead.
        card.shouldInsertNewline = { !pickSuggestionOnKey() }
        card.textView.onTab = ::pickSuggestionOnKey
        suggestionList.onSelect = ::selectSuggestion
        card.onPrimary = ::primaryPressed
        // The flat panel's send and stop are buttons of their own.
        card.onSend = ::sendPressed
        card.onStop = ::abort
        card.onAdd = images::attach
        card.onRemoveAttachment = images::remove
        card.onReceiveImages = images::receive
        images.onChange = { applyConfiguration() }
        // The picked files live as long as the screen's composer.
        scope.coroutineContext[Job]?.invokeOnCompletion { images.close() }
        card.onSelect = ::select
        statusLabel.setOnClickListener { if (voiceStatus != null) onVoice?.invoke() }
        statusLabel.isClickable = false
        card.configuration = card.configuration.copy(placeholder = context.getString(R.string.session_input_placeholder))
    }

    override fun applyTheme() {
        applyStatus()
        card.applyTheme()
    }

    /** localSettings.zenMode, as SessionView passes it (the tablet shell's zen control). */
    var zenMode = false
        set(value) {
            if (field == value) return
            field = value
            applyConfiguration()
        }

    /** useLayoutDimensions' width: the window's. Before the composer is in one, its own. */
    private val screenWidth: Double
        get() = rootView?.width?.takeIf { it > 0 }?.let { com.slopus.happy.ui.view.px(it) } ?: com.slopus.happy.ui.view.px(width)

    private val isWide get() = AgentInputLayout.isWide(screenWidth)

    /**
     * The window crossed 700 (split screen, a rotation, the composer arriving
     * in its window): the panel follows. True if it changed.
     */
    private fun followWindow(): Boolean {
        if (state == null || isWide == (card.configuration.desktop != null)) return false
        applyConfiguration()
        return true
    }

    fun update(state: ChatState, mirror: ComposerMirror, overrides: AgentDefaultOverrides?) {
        val previousState = this.state?.sessionState
        this.state = state
        if (mirror != this.mirror) {
            // The mirror has caught up with what was picked here.
            picks = picks.filter { (field, value) -> mirror.mode(field) != value }
            this.mirror = mirror
        }
        this.overrides = overrides
        if (!restoredDraft && state.sessionLoaded) {
            restoredDraft = true
            mirror.draft?.takeIf { it.isNotEmpty() && card.text.isEmpty() }?.let(card::setText)
        }
        if (state.sessionState != previousState) vibingWord = ComposerStatus.randomVibingWord()
        status = state.session?.let { ComposerStatus.of(state.sessionState, state.activeAt, System.currentTimeMillis(), vibingWord) }
        if (!SessionComposerRules.showsAbortButton(state)) stopRequested = false
        // Nothing to attach to: a picked image would be dropped at send.
        if (!SessionComposerRules.canUseAttachments(state) && images.images.isNotEmpty()) images.clear()
        recomputeModes()
        applyStatus()
        applyConfiguration()
        requestLayout()
    }

    private fun recomputeModes() {
        val shown = mirror.withModes(picks)
        modes = ComposerModeResolver.resolve(state?.session?.metadata, shown, overrides) { context.getString(it) }
    }

    private val titles
        get() = ComposerChipSet.Titles(
            permission = context.getString(R.string.agent_input_permission_mode_title),
            codexPermission = context.getString(R.string.agent_input_codex_permission_mode_title),
            geminiPermission = context.getString(R.string.agent_input_gemini_permission_mode_title),
            model = context.getString(R.string.agent_input_model_title),
            effort = context.getString(R.string.agent_input_effort_title),
        )

    private fun applyConfiguration() {
        val state = state ?: return
        val chips = ComposerChipSet.of(modes, state.session, titles)
        val primary = AgentInputPrimaryAction.resolve(
            hasComposerContent = hasComposerContent,
            isSendBlocked = SessionComposerRules.isSendBlocked(state),
            isSendDisabled = false,
            showAbortButton = SessionComposerRules.showsAbortButton(state),
            canAbort = SessionComposerRules.canAbort(state) && !stopRequested,
            canVoice = state.session != null,
        )
        card.configuration = card.configuration.copy(
            showsAdd = SessionComposerRules.canUseAttachments(state),
            attachments = if (SessionComposerRules.canUseAttachments(state)) images.images.map { AgentInputAttachmentPreview(it.id, it.image.file) } else emptyList(),
            permission = chips.permission,
            model = chips.model,
            effort = chips.effort,
            primaryAction = primary,
            isPrimaryBusy = isAborting,
            primaryAccessibilityLabel = if (primary == AgentInputPrimaryAction.Voice) context.getString(R.string.settings_voice_assistant) else null,
            zenMode = zenMode,
            desktop = if (isWide) {
                AgentInputDesktopConfiguration(
                    settings = if (chips.showsSettings) chips.settings else null,
                    showsStop = SessionComposerRules.canAbort(state),
                    isStopBusy = isAborting,
                    // SessionView passes no `isSending`: a send never spins here.
                    send = AgentInputDesktopSend(
                        hasText = card.hasContent,
                        hasComposerContent = hasComposerContent,
                        isSendBlocked = SessionComposerRules.isSendBlocked(state),
                    ),
                )
            } else {
                null
            },
        )
    }

    private fun applyStatus() {
        val status = status
        val voice = voiceStatus
        statusDot.visibility = if (status == null && voice == null) GONE else VISIBLE
        statusLabel.visibility = statusDot.visibility
        statusLabel.isClickable = voice != null
        statusLabel.contentDescription = null
        if (voice != null) {
            statusLabel.text = context.getString(R.string.voice_call_status, context.getString(R.string.settings_voice_assistant), voice)
            statusLabel.color = Palette.iconBlue
            statusDot.color = Theme.color(Palette.iconBlue)
            statusDot.isPulsing = true
        }
        if (voice == null && status != null) {
            val color: ColorToken = when (status.tone) {
                ComposerStatus.Tone.Disconnected -> Palette.sessionStatusDisconnected
                ComposerStatus.Tone.Attention -> Palette.sessionStatusAttention
                ComposerStatus.Tone.Thinking -> Palette.sessionStatusThinking
                ComposerStatus.Tone.Online -> Palette.sessionStatusOnline
            }
            statusLabel.text = text(status.text)
            statusLabel.color = color
            statusDot.color = Theme.color(color)
            statusDot.isPulsing = status.isPulsing
        }
        if (status == null && voice == null) return
        val alpha = if (detailsVisible) 1f else 0f
        statusDot.alpha = alpha
        statusLabel.alpha = alpha
    }

    /** ChatList's hysteresis: the status row hides while the reader is back in history. */
    fun setDetailsVisible(visible: Boolean, animated: Boolean) {
        if (visible == detailsVisible) return
        detailsVisible = visible
        val alpha = if (visible) 1f else 0f
        for (view in listOf(statusDot, statusLabel)) {
            if (animated) {
                view.animate().alpha(alpha).setDuration(
                    ((if (visible) AgentInputLayout.Metrics.fadeInDuration else AgentInputLayout.Metrics.fadeOutDuration) * 1000).toLong(),
                ).start()
            } else {
                view.animate().cancel()
                view.alpha = alpha
            }
        }
    }

    private fun text(text: ComposerStatus.Text): String = when (text) {
        is ComposerStatus.Text.LastSeen -> context.getString(R.string.status_last_seen, lastSeen(text.lastSeen))
        ComposerStatus.Text.PermissionRequired -> context.getString(R.string.status_permission_required)
        ComposerStatus.Text.InputRequired -> context.getString(R.string.status_input_required)
        is ComposerStatus.Text.Thinking -> text.word
        ComposerStatus.Text.Online -> context.getString(R.string.status_online)
    }

    private fun lastSeen(lastSeen: ComposerStatus.LastSeen): String = when (lastSeen) {
        ComposerStatus.LastSeen.JustNow -> context.getString(R.string.time_just_now)
        is ComposerStatus.LastSeen.Minutes -> resources.getQuantityString(R.plurals.time_minutes_ago, lastSeen.count, lastSeen.count)
        is ComposerStatus.LastSeen.Hours -> resources.getQuantityString(R.plurals.time_hours_ago, lastSeen.count, lastSeen.count)
        is ComposerStatus.LastSeen.Days -> resources.getQuantityString(R.plurals.session_history_days_ago, lastSeen.count, lastSeen.count)
        is ComposerStatus.LastSeen.Date -> {
            val date = Date(lastSeen.ms)
            val year = Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
            val sameYear = year == Calendar.getInstance().get(Calendar.YEAR)
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(resources.configuration.locales[0], if (sameYear) "MMMd" else "yMMMd")
            java.text.SimpleDateFormat(pattern, resources.configuration.locales[0]).format(date)
        }
    }

    // Actions

    private fun select(chip: AgentInputChip, key: String) {
        val state = state ?: return
        val metadata = state.session?.metadata ?: return
        val (field, options) = when (chip) {
            AgentInputChip.Permission -> AgentModeField.PERMISSION_MODE to modes.availableModes
            AgentInputChip.Model -> AgentModeField.MODEL_MODE to modes.availableModels
            AgentInputChip.Effort -> AgentModeField.EFFORT_LEVEL to modes.availableEffortLevels
        }
        val option = options.firstOrNull { it.key == key } ?: return
        val patch = ComposerModeResolver.patch(field, option, metadata, mirror.withModes(picks))
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        // Shown at once; the composer writes its mirror, then the metadata.
        picks = picks + patch
        recomputeModes()
        applyConfiguration()
        scope.launch {
            try {
                actions.setModes(sessionId, patch)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                picks = picks - patch.keys
                recomputeModes()
                applyConfiguration()
            }
        }
    }

    private fun primaryPressed() {
        state ?: return
        val action = card.configuration.primaryAction
        if (!hasComposerContent && action == AgentInputPrimaryAction.Stop) return abort()
        if (!hasComposerContent && action == AgentInputPrimaryAction.Voice) {
            card.blur()
            onVoice?.invoke()
            return
        }
        sendPressed()
    }

    private fun sendPressed() {
        val state = state ?: return
        if (SessionComposerRules.isSendBlocked(state)) {
            // The agent refuses steering while it works.
            if (!hasComposerContent || isSending) return
            performHapticFeedback(HapticFeedbackConstants.REJECT)
            card.shake()
            return
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (!hasComposerContent) return
        stopRequested = false
        send()
    }

    private fun send() {
        if (isSending) return
        val text = card.text
        val picked = images.images
        isSending = true
        scope.launch {
            try {
                // sync.ts: images the agent can't take are named before anything goes; with nothing else to send, nothing does.
                val plan = planAttachmentSend(state?.session?.metadata, text, picked.map { it.preview })
                plan.notices.forEach(::notice)
                if (!plan.send) {
                    isSending = false
                    return@launch
                }
                // The outbox copies the pictures and uploads them when it flushes.
                actions.send(sessionId, text, files = plan.accepted)
                isSending = false
                // Only clear what was sent: typing may have gone on.
                if (card.text == text) card.setText("")
                images.removeSent(picked.map { it.id })
                runCatching { actions.setDraft(sessionId, "") }
                applyConfiguration()
                onSent?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                isSending = false
                performHapticFeedback(HapticFeedbackConstants.REJECT)
                card.shake()
            }
        }
    }

    /**
     * The session's failed pictures in the outbox (happy-ios
     * `newlyFailedAttachments`): the count when the chat opened is known,
     * and each one that fails after is said once.
     */
    fun updateFailedAttachments(count: Int) {
        val known = knownFailedAttachments
        knownFailedAttachments = count
        if (known != null && count > known) notice(AttachmentNotice.UploadFailed(count - known))
    }

    private var knownFailedAttachments: Int? = null

    private fun notice(notice: AttachmentNotice) = when (notice) {
        AttachmentNotice.NotSupported -> Alerts.show(
            context,
            context.getString(R.string.image_upload_not_supported_title),
            context.getString(R.string.image_upload_not_supported_message),
        )
        is AttachmentNotice.UploadFailed -> Alerts.show(
            context,
            context.getString(R.string.image_upload_upload_failed_title),
            context.resources.plural(R.plurals.image_upload_upload_failed_message, notice.count, notice.count),
        )
    }

    private fun abort() {
        if (isAborting) return
        performHapticFeedback(HapticFeedbackConstants.REJECT)
        stopRequested = true
        isAborting = true
        applyConfiguration()
        scope.launch {
            try {
                actions.abort(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                stopRequested = false
                // A stop that failed: the panel's stop shakes (`shakerRef`).
                card.shakeStop()
            } finally {
                isAborting = false
                applyConfiguration()
            }
        }
    }

    // Autocomplete

    /**
     * useActiveWord and useActiveSuggestions: the prefixed word at the caret
     * is the query; its suggestions replace the list when they arrive (a
     * newer query drops older answers). A draft that ends in "/co" opens on
     * its suggestions, as its text change asks.
     */
    private fun updateActiveWord() {
        val source = suggestionSource
        val query = if (source == null) null else findActiveWord(card.text, card.selection, COMPOSER_PREFIXES)?.activeWord
        if (query == activeQuery) return
        activeQuery = query
        suggestionJob?.cancel()
        suggestionJob = null
        applySuggestions()
        if (query == null || source == null) return
        suggestionJob = scope.launch {
            // The search runs off main; only its list comes back.
            val results = withContext(Dispatchers.Default) { source.suggestions(query) }
            if (activeQuery != query) return@launch
            suggestions = suggestions.replaced(results)
            applySuggestions()
        }
    }

    private fun applySuggestions() {
        val visible = if (activeQuery == null) emptyList() else suggestions.suggestions
        if (visible.isEmpty()) {
            if (!isSuggestionListShown) return
            isSuggestionListShown = false
            suggestionList.animateOut {
                if (!isSuggestionListShown) suggestionList.visibility = GONE
            }
            return
        }
        suggestionList.set(visible, suggestions.selected)
        onSuggestionsChanged?.invoke()
        if (!isSuggestionListShown) {
            isSuggestionListShown = true
            suggestionList.visibility = VISIBLE
            suggestionList.animateIn()
        }
    }

    /** Picks the highlighted suggestion, or the first; false with no list. */
    private fun pickSuggestionOnKey(): Boolean {
        if (activeQuery == null || suggestions.suggestions.isEmpty()) return false
        val index = maxOf(0, suggestions.selected)
        // Asked from inside the text's own edit: the text changes after it.
        post { selectSuggestion(index) }
        return true
    }

    /** `handleSuggestionSelect`: the whole active word becomes the pick and a space, the caret after it. */
    private fun selectSuggestion(index: Int) {
        if (activeQuery == null) return
        val suggestion = suggestions.suggestions.getOrNull(index) ?: return
        val result = applySuggestion(card.text, card.selection, suggestion.text, COMPOSER_PREFIXES, addSpace = true)
        card.setText(result.text, result.cursorPosition)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** The suggestion list's frame in the composer's bounds, while it shows. */
    fun suggestionFrame(width: Double): Rect? {
        if (suggestionList.visibility != VISIBLE) return null
        return layout(width).autocomplete(suggestionList.suggestions.size, suggestionList.border)
    }

    // Layout

    fun layout(width: Double): AgentInputLayout {
        val screenWidth = rootView?.width?.takeIf { it > 0 }?.let { com.slopus.happy.ui.view.px(it) } ?: width
        val paddingH = AgentInputLayout.paddingH(screenWidth)
        val innerWidth = AgentInputLayout.innerWidth(width, paddingH, Double.MAX_VALUE)
        val desktop = card.desktopLayoutInput(innerWidth)
        return AgentInputLayout(
            AgentInputLayout.Input(
                width = width,
                paddingH = paddingH,
                screenWidth = screenWidth,
                statusText = if (status == null && voiceStatus == null) null else statusLabel.measure(Double.MAX_VALUE),
                card = if (desktop == null) card.layoutInput(innerWidth) else null,
                desktop = desktop,
            ),
        )
    }

    fun composerHeight(width: Double): Double = layout(width).size.height

    /** From the composer's top to its card's. */
    fun cardOffset(width: Double): Double = layout(width).cardOffset

    override fun sizeThatFits(width: Double, height: Double): Size = Size(width, composerHeight(width))

    override fun layoutSubviews(width: Double, height: Double) {
        if (followWindow()) {
            // The panel changed without the screen asking for a height: it hears of it after this pass.
            post { requestLayout(); onHeightChanged?.invoke() }
        }
        val l = layout(width)
        l.status?.let {
            statusDot.setFrame(it.dot)
            statusLabel.setFrame(it.text)
        }
        card.setFrame(l.card)
    }
}
