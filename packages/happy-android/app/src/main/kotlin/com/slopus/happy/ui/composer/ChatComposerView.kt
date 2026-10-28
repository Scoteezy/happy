package com.slopus.happy.ui.composer

import android.content.Context
import android.view.HapticFeedbackConstants
import com.slopus.happy.R
import com.slopus.happy.attachments.AttachmentNotice
import com.slopus.happy.attachments.planAttachmentSend
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

/**
 * The chat's composer (AgentInput.tsx under SessionView; happy-ios
 * `SessionComposerView`): the session's status over the card, which sends,
 * stops, and switches the permission, model and effort. [AgentInputLayout]
 * places the status row and the card.
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
            if (restoredDraft) scope.launch { runCatching { actions.setDraft(sessionId, card.text) } }
        }
        card.onPrimary = ::primaryPressed
        card.onAdd = images::attach
        card.onRemoveAttachment = images::remove
        card.onReceiveImages = images::receive
        images.onChange = { applyConfiguration() }
        // The picked files live as long as the screen's composer.
        scope.coroutineContext[Job]?.invokeOnCompletion { images.close() }
        card.onSelect = ::select
        card.configuration = card.configuration.copy(placeholder = context.getString(R.string.session_input_placeholder))
    }

    override fun applyTheme() {
        applyStatus()
        card.applyTheme()
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
        )
    }

    private fun applyStatus() {
        val status = status
        statusDot.visibility = if (status == null) GONE else VISIBLE
        statusLabel.visibility = statusDot.visibility
        status ?: return
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
        val state = state ?: return
        val action = card.configuration.primaryAction
        if (!hasComposerContent && action == AgentInputPrimaryAction.Stop) return abort()
        if (!hasComposerContent && action == AgentInputPrimaryAction.Voice) return
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
            } finally {
                isAborting = false
                applyConfiguration()
            }
        }
    }

    // Layout

    fun layout(width: Double): AgentInputLayout {
        val screenWidth = rootView?.width?.takeIf { it > 0 }?.let { com.slopus.happy.ui.view.px(it) } ?: width
        val paddingH = AgentInputLayout.paddingH(screenWidth)
        return AgentInputLayout(
            AgentInputLayout.Input(
                width = width,
                paddingH = paddingH,
                screenWidth = screenWidth,
                statusText = if (status == null) null else statusLabel.measure(Double.MAX_VALUE),
                card = card.layoutInput(AgentInputLayout.innerWidth(width, paddingH, Double.MAX_VALUE)),
            ),
        )
    }

    fun composerHeight(width: Double): Double = layout(width).size.height

    /** From the composer's top to its card's. */
    fun cardOffset(width: Double): Double = layout(width).cardOffset

    override fun sizeThatFits(width: Double, height: Double): Size = Size(width, composerHeight(width))

    override fun layoutSubviews(width: Double, height: Double) {
        val l = layout(width)
        l.status?.let {
            statusDot.setFrame(it.dot)
            statusLabel.setFrame(it.text)
        }
        card.setFrame(l.card)
    }
}
