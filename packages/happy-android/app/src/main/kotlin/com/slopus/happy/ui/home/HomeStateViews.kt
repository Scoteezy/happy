package com.slopus.happy.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.Spanned
import android.view.View
import android.widget.ProgressBar
import androidx.core.view.ViewCompat
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ChecklistRowLayout
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.LoadingLayout
import com.slopus.happy.layout.NoSessionsLayout
import com.slopus.happy.layout.OfflineChecklistLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TerminalBlockLayout
import com.slopus.happy.layout.TextActionLayout
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.RoundButton
import com.slopus.happy.layout.RoundButtonDisplay
import com.slopus.happy.layout.RoundButtonSize
import com.slopus.happy.ui.onboarding.ThemeColorSpan
import com.slopus.happy.ui.onboarding.showChecklistBox
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.ScrollContainer
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame

/**
 * A view home shows in place of its list, laid out against the safe area home
 * gives it: the system bars plus the floating header and the dock (happy-ios
 * sets these as the child's additional safe area).
 */
interface HomeContentView {
    var safeArea: LayoutInsets
}

/** A spinner on the grouped background while the account's machines load (happy-ios `LoadingView`). */
class LoadingView(context: Context) : ManualLayout(context), ThemeAware, HomeContentView {
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyle)

    override var safeArea = LayoutInsets.Zero
        set(value) { if (field != value) { field = value; requestLayout() } }

    init {
        addView(spinner)
        applyTheme()
    }

    override fun applyTheme() {
        setBackgroundColor(Theme.color(Palette.groupedBackground))
        spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(Palette.textSecondary))
    }

    override fun layoutSubviews(width: Double, height: Double) {
        // UIActivityIndicatorView's medium style is 20dp.
        val layout = LoadingLayout(Size(width, height), safeArea, Size(20, 20))
        spinner.setFrame(layout.spinner)
    }
}

/** A text-only button (EmptyMainScreen.tsx `secondaryAction`; happy-ios `TextActionButton`). */
class TextActionButton(context: Context, title: String) : ManualLayout(context), ThemeAware {
    val label = Label(context, TextStyles.secondaryAction, Palette.textSecondary, maxLines = 1).apply { text = title }
    var onTap: (() -> Unit)? = null

    init {
        addView(label)
        label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = title
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap?.invoke() }
        applyTheme()
    }

    override fun applyTheme() {
        val mask = GradientDrawable().apply { cornerRadius = dp(TextActionLayout.Metrics.cornerRadius); setColor(-1) }
        background = RippleDrawable(ColorStateList.valueOf(Theme.color(Palette.surfacePressedOverlay)), null, mask)
    }

    override fun sizeThatFits(width: Double, height: Double) = TextActionLayout(width, label.measurable).size

    override fun layoutSubviews(width: Double, height: Double) {
        label.setFrame(TextActionLayout(width, label.measurable).label)
    }
}

/**
 * EmptyMainScreen.tsx with a computer linked and nothing run on it yet
 * (happy-ios `NoSessionsView`): an icon, a title and a line, centred, with
 * "Start New Session" and the archive link when there is an archive.
 */
class NoSessionsView(context: Context) : ManualLayout(context), ThemeAware, HomeContentView {
    private val scroll = ScrollContainer(context)
    private val icon = IconView(context, Icon.ionicons("terminal-outline"), NoSessionsLayout.Metrics.iconSize, Palette.textSecondary)
    private val titleLabel = Label(context, TextStyles.emptyStateTitle, Palette.text, alignment = android.text.Layout.Alignment.ALIGN_CENTER)
    private val descriptionLabel = Label(context, TextStyles.emptyStateDescription, Palette.textSecondary, alignment = android.text.Layout.Alignment.ALIGN_CENTER)
    val startNewSession = RoundButton(context, context.getString(R.string.home_start_new_session))
    val showArchived = TextActionButton(context, context.getString(R.string.sidebar_show_archived))

    override var safeArea = LayoutInsets.Zero
        set(value) { if (field != value) { field = value; requestLayout() } }

    var showsArchivedAction = false
        set(value) { if (field != value) { field = value; requestLayout() } }

    init {
        titleLabel.text = context.getString(R.string.home_no_sessions_title)
        ViewCompat.setAccessibilityHeading(titleLabel, true)
        descriptionLabel.text = context.getString(R.string.home_no_sessions_description)
        listOf(icon, titleLabel, descriptionLabel, startNewSession, showArchived).forEach(scroll::addContent)
        addView(scroll)
        applyTheme()
    }

    override fun applyTheme() = setBackgroundColor(Theme.color(Palette.groupedBackground))

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = NoSessionsLayout(
            NoSessionsLayout.Input(
                size = Size(width, height),
                safeArea = safeArea,
                title = titleLabel.measurable,
                description = descriptionLabel.measurable,
                buttonHeight = startNewSession.layout(width - safeArea.horizontal).size.height,
                action = if (showsArchivedAction) showArchived.label.measurable.let { m -> com.slopus.happy.layout.Measurable { TextActionLayout(it, m).size } } else null,
            ),
        )
        scroll.setFrame(Rect(0.0, 0.0, width, height))
        scroll.place(
            Size(width, layout.contentHeight),
            mapOf(
                icon to layout.icon,
                titleLabel to layout.title,
                descriptionLabel to layout.description,
                startNewSession to layout.button,
                showArchived to if (showsArchivedAction) layout.action else null,
            ),
        )
    }
}

/**
 * components/onboarding/TerminalBlock.tsx (happy-ios `TerminalBlockView`):
 * shell lines in the website's terminal colours, dark in both appearances.
 * Lines can be selected and copied.
 */
class TerminalBlockView(context: Context, commands: List<String>) : ManualLayout(context) {
    private val lines = commands.map { command ->
        Label(context, TextStyles.terminalLine, Palette.terminalText).apply {
            val text = SpannableString("$ $command")
            text.setSpan(ThemeColorSpan(Palette.terminalPrompt), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            this.text = text
            contentDescription = command
        }
    }
    private val shape = GradientDrawable()

    init {
        shape.setColor(Theme.color(Palette.terminalBackground))
        shape.cornerRadius = dp(TerminalBlockLayout.Metrics.cornerRadius)
        shape.setStroke(dp(TerminalBlockLayout.Metrics.borderWidth).toInt().coerceAtLeast(1), Theme.color(Palette.terminalBorder))
        background = shape
        lines.forEach(::addView)
    }

    fun layout(width: Double) = TerminalBlockLayout(width, lines.map { it.measurable })

    val measurable get() = com.slopus.happy.layout.Measurable { layout(it).size }

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        lines.zip(layout(width).lines).forEach { (line, frame) -> line.setFrame(frame) }
    }
}

/**
 * LinkComputerChecklist from components/onboarding/LinkComputer.tsx (happy-ios
 * `OfflineChecklistView`): what home shows when every linked computer is
 * offline. The install box is already ticked; the job now is to get Happy
 * running again.
 */
class OfflineChecklistView(context: Context) : ManualLayout(context), ThemeAware, HomeContentView {
    private val scroll = ScrollContainer(context)
    val troubleshoot = RoundButton(context, context.getString(R.string.onboarding_offline_troubleshoot))
    val linkAnother = RoundButton(context, context.getString(R.string.onboarding_link_another), RoundButtonSize.Normal, RoundButtonDisplay.Inverted)
    /** Shown when the account has archived sessions to look at instead. */
    val showArchived = RoundButton(context, context.getString(R.string.sidebar_show_archived), RoundButtonSize.Normal, RoundButtonDisplay.Inverted)
    private val titleLabel = Label(context, TextStyles.emptyStateTitle, Palette.text)
    private val linkedBox = IconView(context, null, ChecklistRowLayout.Metrics.boxSize)
    private val linkedLabel = Label(context, TextStyles.checklistTitle, Palette.text)
    private val openBox = IconView(context, null, ChecklistRowLayout.Metrics.boxSize)
    private val openLabel = Label(context, TextStyles.checklistTitle, Palette.text)
    private val openBody = Label(context, TextStyles.checklistBody, Palette.textSecondary)
    private val terminal = TerminalBlockView(context, listOf(context.getString(R.string.onboarding_terminal_run)))

    override var safeArea = LayoutInsets.Zero
        set(value) { if (field != value) { field = value; requestLayout() } }

    var showsArchivedButton = false
        set(value) { field = value; requestLayout() }

    /** "MacBook is offline", or the many-computers form. */
    var title: CharSequence
        get() = titleLabel.text
        set(value) { titleLabel.text = value; requestLayout() }

    /** "Linked MacBook", or "Linked 2 computers". */
    var linkedTitle: CharSequence
        get() = linkedLabel.text
        set(value) { linkedLabel.text = value; requestLayout() }

    init {
        ViewCompat.setAccessibilityHeading(titleLabel, true)
        linkedBox.showChecklistBox(true)
        openBox.showChecklistBox(false)
        openLabel.text = context.getString(R.string.onboarding_offline_open_step)
        openBody.text = context.getString(R.string.onboarding_offline_open_body)
        listOf<View>(titleLabel, linkedBox, linkedLabel, openBox, openLabel, openBody, terminal, troubleshoot, linkAnother, showArchived)
            .forEach(scroll::addContent)
        addView(scroll)
        applyTheme()
    }

    override fun applyTheme() = setBackgroundColor(Theme.color(Palette.groupedBackground))

    private fun Label.lines() = measurable

    override fun layoutSubviews(width: Double, height: Double) {
        val buttons = listOf(troubleshoot, linkAnother) + (if (showsArchivedButton) listOf(showArchived) else emptyList())
        val layout = OfflineChecklistLayout(
            OfflineChecklistLayout.Input(
                size = Size(width, height),
                safeArea = safeArea,
                title = titleLabel.lines(),
                linkedTitle = linkedLabel.lines(),
                openTitle = openLabel.lines(),
                openBody = openBody.lines(),
                terminal = terminal.measurable,
                buttons = buttons.map { b -> com.slopus.happy.layout.Measurable { b.layout(it).size } },
            ),
        )
        scroll.setFrame(Rect(0.0, 0.0, width, height))
        scroll.place(
            layout.contentSize,
            mapOf(
                titleLabel to layout.title,
                linkedBox to layout.linked.box,
                linkedLabel to layout.linked.title,
                openBox to layout.open.box,
                openLabel to layout.open.title,
                openBody to layout.openBody,
                terminal to layout.terminal,
                troubleshoot to layout.buttons[0],
                linkAnother to layout.buttons[1],
                showArchived to layout.buttons.getOrNull(2),
            ),
        )
    }
}
