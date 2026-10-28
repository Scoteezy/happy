package com.slopus.happy.ui.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Layout
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator

import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.ComposerShadow
import com.slopus.happy.layout.FontFace
import com.slopus.happy.layout.HomeDockFocusLayout
import com.slopus.happy.layout.HomeDockFocusMotion
import com.slopus.happy.layout.HomeDockHeaderLayout
import com.slopus.happy.layout.HomeDockRefusal
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.RGBA
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.composer.AgentInputCardReveal
import com.slopus.happy.layout.composer.ComposerShake
import com.slopus.happy.layout.composer.MobileComposerMetrics
import com.slopus.happy.layout.composerCapsuleFill
import com.slopus.happy.layout.homeDockBackdropDim
import com.slopus.happy.layout.homeDockLede
import com.slopus.happy.layout.homeDockProgress
import com.slopus.happy.layout.homeDockRowValue
import com.slopus.happy.model.CUSTOM_PROJECT_PATH_KEY
import com.slopus.happy.model.NewSessionPage
import com.slopus.happy.model.PickerOption
import com.slopus.happy.model.StartButton
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.ChoiceMenu
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.SegmentedControl
import com.slopus.happy.ui.composer.AgentInputCardView
import com.slopus.happy.ui.composer.AgentInputChip
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * The open composer (HomeDock.tsx's focus mode; happy-ios
 * `HomeDockFocusViewController`): laid over home, it dims the list, stacks
 * the machine, project, worktree and harness pickers over the composer
 * card, and rides on the keyboard. Where bots can be made, Session | Bot
 * heads the screen; a bot keeps the machine row only.
 *
 * Every frame comes from [HomeDockFocusLayout], every opening and closing
 * value from [HomeDockFocusMotion], driven frame by frame. The pickers and
 * the composer sit in [dock], a screen-sized box moved onto the keyboard as
 * the IME animates. The pickers open the platform's popup menu where iOS
 * opens a UIMenu.
 */
class HomeDockFocusView(context: Context, private val controller: HomeDockController) : ManualLayout(context), ThemeAware {
    private enum class Row { Machine, Project, Worktree, Harness }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val backdrop = View(context)
    private val dock = Dock(context)
    private val rows = Row.entries.map { HomeDockRowView(context) }
    private val header = Dock(context)
    private val kindControl = SegmentedControl(context, listOf(context.getString(R.string.home_dock_kind_session), context.getString(R.string.home_dock_kind_bot)))
    private val lede = Label(context, TextStyles.homeDockLede, Palette.textSecondary, alignment = Layout.Alignment.ALIGN_CENTER)
    private val progressDot = StatusDotView(context)
    private val progressLabel = Label(context, TextStyles.homeDockProgress, Palette.textSecondary, maxLines = 1)
    private val progressHint = Label(context, TextStyles.homeDockProgress, Palette.textSecondary, maxLines = 1)
    private val shell = Shell(context)
    private val shellShape = GradientDrawable()
    val card = AgentInputCardView(context)
    /** Over the text while a session starts (`composerPressBlocker`): a press shakes the whole composer. */
    private val textBlocker = View(context)

    /** The safe area the screen lays out against, in dp. */
    var safeTop = 0.0
        set(value) { if (field != value) { field = value; requestLayout() } }
    var safeBottom = 0.0
        set(value) { if (field != value) { field = value; requestLayout() } }

    /** The IME's height over the safe bottom, in dp; the dock rides it. */
    var keyboard = 0.0
        set(value) {
            if (field == value) return
            field = value
            applyKeyboard()
        }

    /** How far the backdrop is in (0…1), for home's blur under it. */
    var onBackdrop: ((Float) -> Unit)? = null

    private var progress = 0.0
    private var motion: ValueAnimator? = null
    private var flash: ValueAnimator? = null
    private var flashAmount = 0.0
    private var closing = false
    private var opened = false
    private var reverting = false
    /** Which text the card holds: the prompt, or the bot's name. */
    private var cardHoldsBotName: Boolean? = null

    private val env get() = TextLayouts.Env(Theme.density, Theme.textScale)

    init {
        clipChildren = false
        backdrop.setOnClickListener { backdropTapped() }
        backdrop.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(backdrop)
        addView(dock)
        rows.forEachIndexed { index, row ->
            val which = Row.entries[index]
            row.onTap = { tapped(which, row) }
            row.onRefuse = { refuse() }
            dock.addView(row)
        }

        kindControl.contentDescription = context.getString(R.string.home_dock_bot_kind)
        kindControl.onChange = { index -> controller.setCreatesBot(index == 1) }
        lede.text = context.getString(R.string.home_dock_bot_lede)
        header.addView(kindControl)
        header.addView(lede)
        header.visibility = GONE
        addView(header)

        progressDot.isPulsing = true
        progressHint.text = context.getString(R.string.home_dock_interrupt_hint)
        for (view in listOf(progressDot, progressLabel, progressHint)) {
            view.visibility = GONE
            dock.addView(view)
        }

        shell.background = shellShape
        shell.clipToOutline = true
        shell.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, shellShape.cornerRadius)
            }
        }
        dock.addView(shell)
        card.showsSurface = false
        card.onTextChanged = ::textChanged
        card.onHeightChanged = { layoutContent() }
        card.onPrimary = ::primaryPressed
        card.onReturn = {
            // Return makes the bot (`submitBehavior="submit"`); a session's prompt takes the newline.
            if (controller.createsBot) submitFromFocus()
            controller.createsBot
        }
        card.onRefuse = { refuse() }
        card.onAdd = { Alerts.show(context, context.getString(R.string.home_dock_add_image), NOT_PORTED) }
        card.onSelect = { chip, key ->
            if (!controller.isStarting) {
                when (chip) {
                    AgentInputChip.Permission -> controller.services.select(NewSessionPage.Permission, key)
                    AgentInputChip.Model -> controller.services.select(NewSessionPage.Model, key)
                    AgentInputChip.Effort -> controller.services.select(NewSessionPage.Effort, key)
                }
            }
        }
        shell.addView(card)
        textBlocker.visibility = GONE
        textBlocker.setOnClickListener { refuseComposer() }
        shell.addView(textBlocker)
        applyTheme()
        render()
    }

    override fun applyTheme() {
        backdrop.setBackgroundColor(Theme.color(Palette.homeDockBackdropDim))
        progressDot.color = Theme.color(Palette.statusConnecting)
        val dark = Theme.isDark
        shellShape.setColor(Theme.color(Palette.composerCapsuleFill))
        shellShape.setStroke(1, Theme.color(Palette.glassBorder))
        shell.elevation = dp(if (dark) ComposerShadow.darkRadius / 2 else ComposerShadow.lightRadius / 2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // As the resting capsule: the platform's spot shadow is far lighter than a CALayer's.
            val opacity = if (dark) ComposerShadow.darkOpacity else ComposerShadow.lightOpacity
            val alpha = (minOf(1.0, opacity * 4) * 255).toInt()
            shell.outlineSpotShadowColor = alpha shl 24
            shell.outlineAmbientShadowColor = alpha shl 24
        }
        applyFlash()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (opened) return
        opened = true
        // The shell grows from the capsule to the card's height: a frame for
        // the overlay to land, then 340ms ease-out. The card shows throughout.
        animate(to = 1.0, delay = HomeDockFocusMotion.openDelay, duration = HomeDockFocusMotion.openDuration, easing = HomeDockFocusMotion::easeOut)
        scope.launch {
            delay(50)
            if (!closing) card.focus()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        motion?.cancel()
        flash?.cancel()
        scope.cancel()
    }

    // State

    /** Everything that follows the draft, the store and the start. */
    fun render() {
        val options = controller.options
        val createsBot = controller.createsBot
        val starting = controller.isStarting

        header.visibility = if (options?.supportsBots == true) VISIBLE else GONE
        kindControl.selectedIndex = if (createsBot) 1 else 0
        lede.visibility = if (createsBot) VISIBLE else GONE

        // One field for both: switching keeps the keyboard up.
        val text = if (createsBot) controller.services.bot.value.name else controller.prompt
        if (cardHoldsBotName != createsBot || (createsBot && card.text != text)) {
            cardHoldsBotName = createsBot
            reverting = true
            card.setText(text)
            reverting = false
        }

        val hasHarness = options?.hasHarness == true
        val values = mapOf(
            Row.Machine to (options?.machine?.name ?: context.getString(R.string.home_dock_select_machine)),
            Row.Project to (options?.project?.name ?: context.getString(R.string.home_dock_picker_project)),
            Row.Worktree to (
                options?.worktree?.name
                    ?: context.getString(if (options?.picksWorkspaces == true) R.string.home_dock_main else R.string.home_dock_no_worktree)
                ),
            Row.Harness to (if (hasHarness) options!!.agent.name else context.getString(R.string.home_dock_harness_help)),
        )
        val icons = mapOf(
            Row.Machine to "desktop-outline",
            Row.Project to "folder-outline",
            Row.Worktree to "git-branch-outline",
            Row.Harness to if (hasHarness) "hardware-chip-outline" else "help-circle-outline",
        )
        for (row in Row.entries) {
            val view = rows[row.ordinal]
            // A bot lives on a computer and nowhere else; Happy Agent runs it.
            view.visibility = if (createsBot && row != Row.Machine) GONE else VISIBLE
            view.value = values.getValue(row)
            view.icon = Icon.ionicons(icons.getValue(row))
            view.refusing = starting
            val isHelp = row == Row.Harness && !hasHarness
            view.isLink = isHelp
            view.contentDescription = if (isHelp) context.getString(R.string.home_dock_harness_help_label) else "${title(row)}: ${view.value}"
        }

        val label = controller.progressLabel()
        val primary = controller.primary()
        progressLabel.text = label ?: ""
        progressDot.visibility = if (label == null) GONE else VISIBLE
        progressLabel.visibility = progressDot.visibility
        progressHint.visibility = if (label == null || primary != StartButton.Stop) GONE else VISIBLE

        card.configuration = controller.cardConfiguration()
        textBlocker.visibility = if (starting) VISIBLE else GONE
        layoutContent()
    }

    private fun title(row: Row): String = context.getString(
        when (row) {
            Row.Machine -> R.string.home_dock_picker_machine
            Row.Project -> R.string.home_dock_picker_project
            Row.Worktree -> if (controller.options?.picksWorkspaces == true) R.string.home_dock_picker_workspace else R.string.home_dock_picker_worktree
            Row.Harness -> R.string.home_dock_picker_harness
        },
    )

    private fun textChanged() {
        if (reverting) return
        val createsBot = controller.createsBot
        // The keyboard stays up while a session starts, so the text is
        // locked by refusing each change rather than by disabling it.
        if (controller.isStarting) {
            reverting = true
            card.setText(if (createsBot) controller.services.bot.value.name else controller.prompt)
            reverting = false
            refuseComposer()
            return
        }
        if (createsBot) {
            val typed = card.text
            val kept = controller.setBotName(typed)
            if (kept != typed) {
                reverting = true
                card.setText(kept)
                reverting = false
            }
            return
        }
        controller.setPrompt(card.text)
    }

    private fun primaryPressed() {
        if (controller.isStarting) {
            // Stop is about this screen: the composer comes back now.
            controller.cancelStart()
            close()
            return
        }
        if (!controller.createsBot) controller.setPrompt(card.text)
        submitFromFocus()
    }

    /** The composer stays up until the session exists, so a failure lands where it was asked for. */
    private fun submitFromFocus() {
        if (!controller.canSubmit) return
        scope.launch { if (controller.submit()) close() }
    }

    // Pickers

    private fun tapped(row: Row, view: HomeDockRowView) {
        if (row == Row.Harness && view.isLink) {
            // The harness row with nothing to run: setup help, not a menu.
            card.blur()
            Alerts.open(context, HARNESS_HELP_URL)
            return
        }
        showMenu(row, view)
    }

    /** A picker row's menu, built as it opens so it shows the draft as it is then. The worktree menu lists git again first. */
    private fun showMenu(row: Row, anchor: HomeDockRowView) {
        val options = controller.options ?: return
        val page = when (row) {
            Row.Machine -> NewSessionPage.Machine
            Row.Project -> NewSessionPage.Project
            Row.Worktree -> NewSessionPage.Worktree
            Row.Harness -> NewSessionPage.Agent
        }
        controller.services.onOpenPicker(page)
        val draft = controller.services.draft.value
        val sections: List<Pair<String?, List<PickerOption>>> = when (row) {
            Row.Machine -> listOf(title(row) to options.machines)
            Row.Project -> listOf(
                context.getString(R.string.home_dock_picker_projects) to options.projects,
                null to listOf(PickerOption(CUSTOM_PROJECT_PATH_KEY, context.getString(R.string.machine_launcher_enter_custom_path), action = true)),
            )
            Row.Worktree -> listOf(title(row) to options.worktrees)
            Row.Harness -> listOf(title(row) to options.agents)
        }
        val selected = when (row) {
            Row.Machine -> draft.selectedMachineId
            Row.Project -> options.project?.key
            Row.Worktree -> options.worktreeSelection
            Row.Harness -> draft.agentType.wire
        }
        val menu = sections.map { (title, choices) ->
            ChoiceMenu.Section(title, choices.map { ChoiceMenu.Option(it.key, it.name, it.disabled, it.action) })
        }
        ChoiceMenu.show(anchor.valueAnchor, menu, selected) { key ->
            if (row == Row.Project && key == CUSTOM_PROJECT_PATH_KEY) askForCustomPath() else controller.services.select(page, key)
        }
    }

    /** "Enter custom path", starting from the draft's directory. */
    private fun askForCustomPath() {
        val current = controller.services.draft.value.selectedPath ?: "~"
        scope.launch {
            val path = Alerts.prompt(
                context,
                context.getString(R.string.machine_launcher_enter_custom_path),
                placeholder = context.getString(R.string.home_dock_custom_path_placeholder),
                confirmTitle = context.getString(R.string.common_ok),
                initial = current,
            )?.trim()
            if (!path.isNullOrEmpty()) controller.services.selectPath(path)
        }
    }

    // Closing and refusing

    /** `resolveHomeDockBackdropPressAction`: a tap outside closes, except while a session is starting, when Stop is the way out. */
    fun backdropTapped() {
        if (controller.isStarting) {
            refuse()
            return
        }
        close()
    }

    /** The composer's own close (`closeFocusMode`): the keyboard goes, the presentation runs back in 180ms ease-in, then it leaves. */
    fun close() {
        if (closing) return
        closing = true
        card.blur()
        animate(to = 0.0, delay = 0.0, duration = HomeDockFocusMotion.closeDuration, easing = HomeDockFocusMotion::easeIn) {
            controller.focusDidClose(this)
        }
    }

    /** Two beats on the hint and on Stop, and an error tap. */
    private fun refuse() {
        performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
        flash?.cancel()
        flash = ValueAnimator.ofFloat(0f, HomeDockRefusal.flashDuration.toFloat()).apply {
            duration = (HomeDockRefusal.flashDuration * 1000).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                flashAmount = HomeDockRefusal.flash((it.animatedValue as Float).toDouble())
                applyFlash()
            }
            start()
        }
    }

    /** A refused edit of the text: the whole composer shakes (Shaker). */
    private fun refuseComposer() {
        refuse()
        AgentInputCardView.shake(shell)
    }

    private fun applyFlash() {
        progressHint.color = blend(Palette.textSecondary, Palette.text, flashAmount)
        card.setPrimaryFlash(flashAmount)
    }

    private fun blend(from: ColorToken, to: ColorToken, amount: Double): ColorToken {
        fun mix(a: RGBA, b: RGBA) = RGBA(
            a.red + (b.red - a.red) * amount,
            a.green + (b.green - a.green) * amount,
            a.blue + (b.blue - a.blue) * amount,
            a.alpha + (b.alpha - a.alpha) * amount,
        )
        return ColorToken(mix(from.light, to.light), mix(from.dark, to.dark))
    }

    // Motion

    private fun animate(to: Double, delay: Double, duration: Double, easing: (Double) -> Double, completion: (() -> Unit)? = null) {
        motion?.cancel()
        val from = progress
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = (duration * 1000).toLong()
            startDelay = (delay * 1000).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = from + (to - from) * easing((it.animatedValue as Float).toDouble())
                layoutContent()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    progress = to
                    layoutContent()
                    completion?.invoke()
                }
            })
            start()
        }
    }

    // Layout

    private fun applyKeyboard() {
        val size = Size(px(width), px(height))
        val keyboardTop = size.height - safeBottom - keyboard
        dock.translationY = dp(HomeDockFocusLayout.keyboardShift(keyboardTop, size, safeBottom))
    }

    private val composerWidth: Double
        get() = maxOf(0.0, px(width) - 2 * HomeDockFocusLayout.Metrics.composerPaddingHorizontal)

    override fun layoutSubviews(width: Double, height: Double) {
        backdrop.setFrame(Rect(0.0, 0.0, width, height))
        dock.setFrame(Rect(0.0, 0.0, width, height))
        applyKeyboard()
        layoutContent()
    }

    /** The dock's contents at the present progress. */
    private fun layoutContent() {
        val size = Size(px(width), px(height))
        if (size.width <= 0) return
        val motion = HomeDockFocusMotion(progress)
        backdrop.alpha = motion.backdropAlpha.toFloat()
        onBackdrop?.invoke(motion.backdropAlpha.toFloat())

        val cardLayout = card.layout(composerWidth)
        val cardHeight = cardLayout.size.height
        val shownRows = rows.filter { it.visibility == VISIBLE }
        val showsProgress = progressLabel.visibility == VISIBLE
        val layout = HomeDockFocusLayout(
            HomeDockFocusLayout.Input(
                size = size,
                safeBottom = safeBottom,
                composerHeight = motion.composerHeight(cardHeight),
                rows = shownRows.map { it.valueLabel.measurable },
                triggerLine = TextLayouts.lineHeight(ROW_VALUE, env),
                progress = if (showsProgress) progressLabel.measurable else null,
                hint = if (showsProgress && progressHint.visibility == VISIBLE) progressHint.measurable else null,
            ),
        )

        // Each line reveals in turn from the top.
        shownRows.forEachIndexed { index, row ->
            val frame = layout.rows[index]
            val (alpha, offset) = motion.row(index)
            row.setFrame(frame.frame)
            row.apply(frame)
            row.translationY = dp(offset)
            row.alpha = alpha.toFloat()
        }

        // Session | Bot holds the top of the screen, off the keyboard, and reveals with the first row.
        if (header.visibility == VISIBLE) {
            val headerLayout = HomeDockHeaderLayout(
                HomeDockHeaderLayout.Input(width = size.width, safeTop = safeTop, lede = if (lede.visibility == VISIBLE) lede.measurable else null),
            )
            val content = headerLayout.content
            val (alpha, offset) = motion.row(0)
            header.setFrame(content)
            header.translationY = dp(offset)
            header.alpha = alpha.toFloat()
            kindControl.setFrame(headerLayout.segment.offsetBy(-content.minX, -content.minY))
            headerLayout.lede?.let { lede.setFrame(it.offsetBy(-content.minX, -content.minY)) }
        }

        layout.progressDot?.let { progressDot.setFrame(it) }
        layout.progressLabel?.let { progressLabel.setFrame(it) }
        layout.progressHint?.let { progressHint.setFrame(it) }

        // The shell grows from the capsule, its card anchored to its foot.
        val composer = layout.composer
        shell.setFrame(composer)
        shell.scaleX = motion.composerScaleX.toFloat()
        shell.alpha = motion.composerAlpha.toFloat()
        val radius = dp(min(MobileComposerMetrics.mobile.shellRadius, composer.height / 2))
        if (shellShape.cornerRadius != radius) {
            shellShape.cornerRadius = radius
            shell.invalidateOutline()
        }
        val cardFrame = Rect(0.0, composer.height - cardHeight, composer.width, cardHeight)
        card.setFrame(cardFrame)
        card.setReveal(AgentInputCardReveal.input(progress), AgentInputCardReveal.actions(progress))
        textBlocker.setFrame(Rect(0.0, cardFrame.minY, cardFrame.width, cardLayout.actionRow.minY))
    }

    /** A box that takes no touches itself, only its children's. */
    private class Dock(context: Context) : ManualLayout(context) {
        override fun layoutSubviews(width: Double, height: Double) {}

        override fun onTouchEvent(event: MotionEvent): Boolean = false
    }

    /** The composer's shell: the capsule the card sits in, clipped to its corners. */
    private class Shell(context: Context) : ManualLayout(context) {
        override fun layoutSubviews(width: Double, height: Double) {}
    }

    private companion object {
        /** The native menu trigger's value: the platform's body font, as iOS's UIButton menu draws it. */
        val ROW_VALUE = TextStyle(FontFace.System, 17)
        const val HARNESS_HELP_URL = "https://happy.engineering/docs/quick-start/"
        const val NOT_PORTED = "Not available in this Android build yet."
    }
}

/**
 * One picker row over the open composer (HomeDock.tsx `renderPickerRow`;
 * happy-ios `HomeDockRowView`): an Ionicon in a 24dp box, then the value,
 * which opens the row's menu. The help row, a link rather than a menu,
 * draws its value in the app's own font. While a session starts a press
 * shakes the row instead (`FocusConfigRevealRow`'s blocker).
 */
class HomeDockRowView(context: Context) : ManualLayout(context), ThemeAware {
    private val iconView = IconView(context, null, HomeDockFocusLayout.Metrics.iconSize, Palette.text)
    val valueLabel = Label(context, TextStyle(FontFace.System, 17), Palette.text, maxLines = 1)
    private var local: HomeDockFocusLayout.Row? = null

    /** Where the menu hangs from: the value. */
    val valueAnchor: View get() = valueLabel

    var onTap: (() -> Unit)? = null
    var onRefuse: (() -> Unit)? = null

    var icon: Icon? = null
        set(value) { field = value; iconView.icon = value }

    var value: String = ""
        set(value) { if (field != value) { field = value; valueLabel.text = value; requestLayout() } }

    /** A link rather than a menu: the value is the app's text. */
    var isLink = false
        set(value) {
            if (field == value) return
            field = value
            valueLabel.style = if (value) TextStyles.homeDockRowValue else TextStyle(FontFace.System, 17)
            requestLayout()
        }

    var refusing = false

    init {
        iconView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        valueLabel.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(iconView)
        addView(valueLabel)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (refusing) {
                AgentInputCardView.shake(this, ComposerShake.refusal)
                onRefuse?.invoke()
            } else {
                onTap?.invoke()
            }
        }
    }

    override fun applyTheme() = Unit

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        valueLabel.alpha = if (pressed && !refusing) 0.5f else 1f
    }

    /** The row's frames, in its own space. */
    fun apply(row: HomeDockFocusLayout.Row) {
        val x = row.frame.minX
        val y = row.frame.minY
        local = HomeDockFocusLayout.Row(
            frame = Rect(0.0, 0.0, row.frame.width, row.frame.height),
            icon = row.icon.offsetBy(-x, -y),
            trigger = row.trigger.offsetBy(-x, -y),
            value = row.value.offsetBy(-x, -y),
        )
        requestLayout()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val row = local ?: return
        iconView.setFrame(row.icon)
        valueLabel.setFrame(row.value)
    }
}
