package com.slopus.happy.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import com.slopus.happy.R
import com.slopus.happy.chat.InactiveSessionHint
import com.slopus.happy.chat.PendingAgentCommunication
import com.slopus.happy.chat.RigActivityKind
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.agentEventText
import com.slopus.happy.layout.buttonSecondaryTint
import com.slopus.happy.layout.chat.GoalBarLayout
import com.slopus.happy.layout.chat.InactiveHintLayout
import com.slopus.happy.layout.chat.QuestionBannerLayout
import com.slopus.happy.layout.chat.RigActivityLayout
import com.slopus.happy.layout.surfacePressed
import com.slopus.happy.model.AgentGoal
import com.slopus.happy.model.AgentGoalAction
import com.slopus.happy.model.RigActivityIndicator
import com.slopus.happy.model.offeredActions
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The bars between the conversation and the composer (happy-ios
// ChatDockBarViews.swift and ChatQuestionBannerView): InactiveArchivedHint,
// AgentGoalBar, AgentQuestionBanner and RigActivityBar. Each answers its
// height at the width the dock offers through [ChatDockBar.measurable] and
// places its pieces from the same layout.

/** A bar in the dock: its height at a width. */
interface ChatDockBar {
    val measurable: Measurable
}

/** A rounded card: [fill], a [border]-wide rim in [rim], [radius] round. */
private class CardDrawable(private val radius: Double, private val border: Double) : GradientDrawable() {
    fun apply(fill: ColorToken, rim: ColorToken?) {
        cornerRadius = dp(radius)
        setColor(Theme.color(fill))
        if (rim != null) setStroke(dp(border).toInt().coerceAtLeast(1), Theme.color(rim)) else setStroke(0, 0)
    }
}

/**
 * AgentQuestionBanner: the card above the composer for a request the chat
 * can't show inline. A form opens the answer sheet; an unknown kind can only
 * be dismissed.
 */
class ChatQuestionBannerView(context: Context) : ManualLayout(context), ThemeAware, ChatDockBar {
    var onOpen: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    var pending: PendingAgentCommunication? = null
        private set

    private val card = View(context)
    private val cardShape = CardDrawable(QuestionBannerLayout.Metrics.radius, QuestionBannerLayout.Metrics.border)
    private val icon = IconView(context, null, QuestionBannerLayout.Metrics.iconSize)
    private val title = Label(context, ChatTextStyles.bannerTitle, Palette.text, maxLines = 1)
    // Two lines in happy-app, but its truncating paragraph style draws one, as happy-ios does.
    private val subtitle = Label(context, ChatTextStyles.bannerSubtitle, Palette.textSecondary, maxLines = 1)
    private val dismiss = Label(context, ChatTextStyles.bannerDismiss, Palette.textLink, maxLines = 1)
    private val chevron = IconView(context, Icon.ionicons("chevron-forward"), CHEVRON, Palette.textSecondary)
    private var dismissTarget = Rect(0.0, 0.0, 0.0, 0.0)

    private val isForm get() = pending is PendingAgentCommunication.Form

    init {
        card.background = cardShape
        addView(card)
        for (view in listOf(icon, title, subtitle, dismiss, chevron)) {
            view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(view)
        }
        isClickable = true
        applyTheme()
    }

    fun show(pending: PendingAgentCommunication) {
        if (pending == this.pending) return
        this.pending = pending
        val res = context.resources
        when (pending) {
            is PendingAgentCommunication.Unsupported -> {
                icon.icon = Icon.ionicons("alert-circle-outline")
                icon.tint = Palette.textSecondary
                title.text = pending.title ?: res.getString(R.string.agent_question_unsupported_title)
                subtitle.text = res.getString(R.string.agent_question_unsupported_description, pending.rawKind)
                dismiss.text = res.getString(R.string.agent_question_dismiss)
            }
            is PendingAgentCommunication.Form -> {
                icon.icon = Icon.ionicons("help-circle-outline")
                icon.tint = Palette.textLink
                val first = pending.questions.firstOrNull()
                val remaining = pending.questions.size - 1
                title.text = first?.header ?: res.getString(R.string.agent_question_title)
                subtitle.text = if (remaining > 0) {
                    res.getQuantityString(R.plurals.agent_question_more_questions, remaining, remaining)
                } else {
                    first?.question.orEmpty()
                }
                dismiss.text = ""
            }
        }
        contentDescription = "${title.text}, ${subtitle.text}"
        dismiss.visibility = if (isForm) GONE else VISIBLE
        chevron.visibility = if (isForm) VISIBLE else GONE
        applyTheme()
        requestLayout()
    }

    private fun layout(width: Double) = QuestionBannerLayout(
        width, title.measurable, subtitle.measurable,
        if (isForm) Measurable.fixed(Size(CHEVRON, CHEVRON)) else dismiss.measurable,
    )

    override val measurable: Measurable get() = Measurable { if (pending == null) Size.Zero else layout(it).size }

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        card.setFrame(layout.card)
        icon.setFrame(layout.icon)
        title.setFrame(layout.title)
        subtitle.setFrame(layout.subtitle)
        if (isForm) chevron.setFrame(layout.trailing) else dismiss.setFrame(layout.trailing)
        dismissTarget = layout.trailing.insetBy(-10.0, -10.0)
    }

    override fun applyTheme() {
        cardShape.apply(Palette.surface, if (isForm) Palette.textLink else Palette.divider)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (isForm) {
                onOpen?.invoke()
            } else if (dismissTarget.contains(px(event.x), px(event.y))) {
                onDismiss?.invoke()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (isForm) onOpen?.invoke() else onDismiss?.invoke()
        return true
    }

    private companion object {
        /** AgentQuestionBanner's chevron-forward. */
        const val CHEVRON = 18.0
    }
}

/**
 * The hint's Resume button and command block: [fill], surfacePressed while
 * held, a hairline divider rim, 18 round.
 */
private class HintCard(context: Context, private val fill: ColorToken) : ManualLayout(context), ThemeAware {
    private val shape = CardDrawable(InactiveHintLayout.Metrics.radius, 0.0)
    var place: (Double, Double) -> Unit = { _, _ -> }

    init {
        background = shape
        isClickable = true
        isFocusable = true
        applyTheme()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        applyTheme()
    }

    override fun applyTheme() {
        shape.cornerRadius = dp(InactiveHintLayout.Metrics.radius)
        shape.setColor(Theme.color(if (isPressed) Palette.surfacePressed else fill))
        shape.setStroke(1, Theme.color(Palette.divider))
    }

    override fun layoutSubviews(width: Double, height: Double) = place(width, height)
}

/**
 * InactiveArchivedHint: "This session is inactive.", then Resume (a spinner
 * while it runs), or "To resume it from the terminal:" and the commands,
 * which a tap copies; their glyph ticks for two seconds.
 */
class ChatInactiveHintView(context: Context, private val scope: CoroutineScope) : ManualLayout(context), ChatDockBar {
    var onResume: (() -> Unit)? = null

    var hint: InactiveSessionHint? = null
        private set
    private var resuming = false
    private var copied = false
    private var copiedReset: Job? = null

    private val notice = Label(context, ChatTextStyles.inactiveHint, Palette.agentEventText)
    private val terminalNotice = Label(context, ChatTextStyles.inactiveHint, Palette.agentEventText)
    private val button = HintCard(context, Palette.surfaceHigh)
    private val buttonLabel = Label(context, ChatTextStyles.resumeButton, Palette.text, maxLines = 1)
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val command = HintCard(context, Palette.surface)
    private val commandText = Label(context, ChatTextStyles.resumeCommand, Palette.text)
    private val commandIcon = IconView(context, null, InactiveHintLayout.Metrics.iconSize)
    private var placed: InactiveHintLayout? = null

    init {
        addView(notice)
        addView(terminalNotice)
        button.addView(buttonLabel)
        button.addView(spinner)
        button.contentDescription = context.getString(R.string.session_info_resume_session)
        button.setOnClickListener { onResume?.invoke() }
        button.place = { _, _ ->
            val layout = placed
            val frame = layout?.button
            if (layout != null && frame != null) {
                layout.buttonLabel?.let { buttonLabel.setFrame(it.offsetBy(-frame.minX, -frame.minY)) }
                val s = 20.0
                spinner.setFrame(Rect(frame.width / 2 - s / 2, frame.height / 2 - s / 2, s, s))
            }
        }
        addView(button)
        command.addView(commandText)
        command.addView(commandIcon)
        command.setOnClickListener { copyCommand() }
        command.place = { _, _ ->
            val layout = placed
            val frame = layout?.command
            if (layout != null && frame != null) {
                layout.commandText?.let { commandText.setFrame(it.offsetBy(-frame.minX, -frame.minY)) }
                layout.commandIcon?.let { commandIcon.setFrame(it.offsetBy(-frame.minX, -frame.minY)) }
            }
        }
        for (view in listOf(buttonLabel, commandText)) view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(command)
    }

    fun show(hint: InactiveSessionHint, resuming: Boolean) {
        if (hint == this.hint && resuming == this.resuming) return
        if (hint != this.hint) {
            copied = false
            copiedReset?.cancel()
            (hint.action as? InactiveSessionHint.Action.Command)?.let { command.contentDescription = it.lines.joinToString(", ") }
        }
        this.hint = hint
        this.resuming = resuming
        notice.text = context.getString(R.string.session_inactive_archived)
        terminalNotice.text = if (hint.copyText != null) context.getString(R.string.session_resume_from_terminal) else ""
        buttonLabel.text = context.getString(R.string.session_info_resume_session)
        (hint.action as? InactiveSessionHint.Action.Command)?.let { commandText.text = it.lines.joinToString("\n") }
        button.isEnabled = !resuming
        button.alpha = if (resuming) InactiveHintLayout.Metrics.resumingOpacity.toFloat() else 1f
        buttonLabel.visibility = if (resuming) INVISIBLE else VISIBLE
        spinner.visibility = if (resuming) VISIBLE else GONE
        spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(Palette.text))
        updateIcon()
        requestLayout()
    }

    private fun layout(width: Double): InactiveHintLayout {
        val hint = hint
        val action = when (val a = hint?.action) {
            InactiveSessionHint.Action.Resume -> InactiveHintLayout.Action.Resume(buttonLabel.measurable)
            is InactiveSessionHint.Action.Command -> InactiveHintLayout.Action.Command(commandText.measurable)
            else -> InactiveHintLayout.Action.None
        }
        return InactiveHintLayout(width, notice.measurable, if (hint?.copyText != null) terminalNotice.measurable else null, action)
    }

    override val measurable: Measurable get() = Measurable { if (hint == null) Size.Zero else layout(it).size }

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        placed = layout
        notice.setFrame(layout.notice)
        terminalNotice.visibility = if (layout.terminalNotice == null) GONE else VISIBLE
        layout.terminalNotice?.let { terminalNotice.setFrame(it) }
        button.visibility = if (layout.button == null) GONE else VISIBLE
        layout.button?.let { button.setFrame(it) }
        command.visibility = if (layout.command == null) GONE else VISIBLE
        layout.command?.let { command.setFrame(it) }
    }

    private fun copyCommand() {
        val text = hint?.copyText ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
        copied = true
        updateIcon()
        copiedReset?.cancel()
        copiedReset = scope.launch {
            delay(2000)
            copied = false
            updateIcon()
        }
    }

    private fun updateIcon() {
        commandIcon.icon = Icon.ionicons(if (copied) "checkmark" else "copy-outline")
        commandIcon.tint = if (copied) COPIED else Palette.textSecondary
    }

    private companion object {
        /** SecondaryPalette.copied */
        val COPIED = ColorToken("#30D158")
    }
}

/** One of the goal bar's 30dp round buttons (hitSlop 8): a spinner while its action is on its way. */
private class GoalActionButton(context: Context, val action: AgentGoalAction) : ManualLayout(context), ThemeAware {
    private val icon = IconView(
        context,
        Icon.ionicons(
            when (action) {
                AgentGoalAction.Edit -> "create-outline"
                AgentGoalAction.Stop -> "pause-outline"
                AgentGoalAction.Clear -> "trash-outline"
            },
        ),
        GoalBarLayout.Metrics.actionIconSize,
        Palette.buttonSecondaryTint,
    )
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val shape = GradientDrawable().apply { shape = GradientDrawable.OVAL }

    var busy = false
        set(value) {
            if (field == value) return
            field = value
            isEnabled = !value
            alpha = if (value) 0.6f else 1f
            icon.visibility = if (value) INVISIBLE else VISIBLE
            spinner.visibility = if (value) VISIBLE else GONE
        }

    init {
        background = shape
        addView(icon)
        spinner.visibility = GONE
        addView(spinner)
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(
            when (action) {
                AgentGoalAction.Edit -> R.string.components_agent_goal_bar_edit_goal
                AgentGoalAction.Stop -> R.string.components_agent_goal_bar_stop_goal
                AgentGoalAction.Clear -> R.string.components_agent_goal_bar_clear_goal
            },
        )
        applyTheme()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        applyTheme()
    }

    override fun applyTheme() {
        shape.setColor(if (isPressed) Theme.color(Palette.surfacePressed) else 0)
        spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(Palette.textSecondary))
    }

    override fun layoutSubviews(width: Double, height: Double) {
        icon.setFrame(Rect(0.0, 0.0, width, height))
        val s = 18.0
        spinner.setFrame(Rect((width - s) / 2, (height - s) / 2, s, s))
    }
}

/** AgentGoalBar: the goal the agent is working towards, with the actions the agent allows. */
class ChatGoalBarView(context: Context) : ManualLayout(context), ThemeAware, ChatDockBar {
    var onAction: ((AgentGoalAction) -> Unit)? = null

    var goal: AgentGoal? = null
        private set
    private var inFlight: AgentGoalAction? = null

    private val card = View(context)
    private val cardShape = CardDrawable(GoalBarLayout.Metrics.radius, GoalBarLayout.Metrics.border)
    private val icon = IconView(context, Icon.ionicons("locate-outline"), GoalBarLayout.Metrics.iconSize, Palette.textSecondary)
    private val label = Label(context, ChatTextStyles.goalLabel, Palette.textSecondary, maxLines = 1)
    private val text = Label(context, ChatTextStyles.goalText, Palette.text, maxLines = 1)
    private var buttons = listOf<GoalActionButton>()

    init {
        card.background = cardShape
        addView(card)
        addView(icon)
        addView(label)
        addView(text)
        label.text = context.getString(R.string.components_agent_goal_bar_current_goal)
        applyTheme()
    }

    fun show(goal: AgentGoal, inFlight: AgentGoalAction?) {
        if (goal == this.goal && inFlight == this.inFlight) return
        val actions = goal.offeredActions()
        if (actions != this.goal?.offeredActions() || buttons.isEmpty() && actions.isNotEmpty()) {
            buttons.forEach(::removeView)
            buttons = actions.map { action ->
                GoalActionButton(context, action).also { button ->
                    button.setOnClickListener { onAction?.invoke(action) }
                    addView(button)
                }
            }
        }
        this.goal = goal
        this.inFlight = inFlight
        text.text = goal.text
        for (button in buttons) button.busy = button.action == inFlight
        card.contentDescription = context.getString(R.string.components_agent_goal_bar_accessibility_label, goal.text)
        requestLayout()
    }

    private fun layout(width: Double) = GoalBarLayout(width, label.measurable, text.measurable, buttons.size)

    override val measurable: Measurable get() = Measurable { if (goal == null) Size.Zero else layout(it).size }

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        card.setFrame(layout.card)
        icon.setFrame(layout.icon)
        label.setFrame(layout.label)
        text.setFrame(layout.text)
        for ((button, frame) in buttons.zip(layout.actions)) button.setFrame(frame)
    }

    override fun applyTheme() {
        cardShape.apply(Palette.surfaceHigh, Palette.divider)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // hitSlop 8: a touch just outside a button still lands on it.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = px(event.x)
            val y = px(event.y)
            val near = buttons.firstOrNull { b ->
                Rect(px(b.left), px(b.top), px(b.width), px(b.height)).insetBy(-8.0, -8.0).contains(x, y) &&
                    !Rect(px(b.left), px(b.top), px(b.width), px(b.height)).contains(x, y)
            }
            if (near != null) {
                event.setLocation((near.left + near.width / 2).toFloat(), (near.top + near.height / 2).toFloat())
            }
        }
        return super.dispatchTouchEvent(event)
    }
}

/**
 * RigActivityBar: "2 agents · 1 processes", each with its glyph, wrapping
 * onto more lines when it must. Full width, under the other bars.
 */
class ChatRigActivityView(context: Context) : ManualLayout(context), ChatDockBar {
    var indicators: List<RigActivityIndicator> = emptyList()
        private set
    private var icons = listOf<IconView>()
    private var labels = listOf<Label>()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun show(indicators: List<RigActivityIndicator>) {
        if (indicators == this.indicators) return
        this.indicators = indicators
        removeAllViews()
        val res = context.resources
        val words = indicators.map { indicator ->
            val count = if ((indicator.queued ?: 0) > 0) {
                res.getString(R.string.chat_rig_activity_count_queued, indicator.count.toInt(), (indicator.queued ?: 0).toInt())
            } else {
                indicator.count.toString()
            }
            when (RigActivityKind.of(indicator)) {
                RigActivityKind.Subagents -> res.getString(R.string.chat_rig_activity_agents, count)
                RigActivityKind.Workflows -> res.getString(R.string.chat_rig_activity_workflows, count)
                RigActivityKind.Processes -> res.getString(R.string.chat_rig_activity_processes, count)
                RigActivityKind.Tasks -> res.getString(R.string.chat_rig_activity_tasks, count)
                null -> "$count ${indicator.key}"
            }
        }
        icons = indicators.map { indicator ->
            IconView(context, Icon.ionicons(RigActivityKind.of(indicator)?.icon ?: "ellipse-outline"), RigActivityLayout.Metrics.iconSize, Palette.textSecondary)
                .also { addView(it) }
        }
        labels = words.map { word ->
            Label(context, ChatTextStyles.rigActivity, Palette.textSecondary, maxLines = 1).also {
                it.text = word
                it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(it)
            }
        }
        contentDescription = words.joinToString(", ")
        requestLayout()
    }

    private fun layout(width: Double) = RigActivityLayout(width, labels.map { it.measurable })

    override val measurable: Measurable get() = Measurable { if (labels.isEmpty()) Size.Zero else layout(it).size }

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        for ((icon, frame) in icons.zip(layout.icons)) icon.setFrame(frame)
        for ((label, frame) in labels.zip(layout.labels)) label.setFrame(frame)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = false
}
