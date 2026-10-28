package com.slopus.happy.ui.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import com.slopus.happy.R
import com.slopus.happy.layout.ConnectCommandLayout
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame
import kotlin.math.max

/**
 * Settings › Connected Accounts › Claude Code (settings/connect/claude.tsx,
 * happy-ios `SettingsConnectClaudeViewController`). happy-app's OAuth flow
 * is commented out; the page tells you to connect from a terminal.
 */
class ConnectClaudeScreen(context: Context) : BarScreen(context) {
    private val content = ConnectCommandView(
        context,
        context.getString(R.string.connect_claude_title),
        context.getString(R.string.connect_claude_instruction),
        context.getString(R.string.connect_claude_command),
    )

    override fun createContent(): View {
        bar.title = context.getString(R.string.connect_claude_screen_title)
        return content
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        content.safeArea = contentSafeArea
    }
}

/** settings/connect/claude.tsx `OAuthViewUnsupported`: a title, one line of instruction and the command in a terminal card, centred. */
class ConnectCommandView(context: Context, title: String, body: String, private val command: String) : ManualLayout(context), ThemeAware {
    var safeArea: LayoutInsets = LayoutInsets.Zero
        set(value) { if (field != value) { field = value; requestLayout() } }

    private val titleLabel = Label(context, TextStyles.connectTitle, Palette.text, alignment = Layout.Alignment.ALIGN_CENTER)
    private val bodyLabel = Label(context, TextStyles.connectBody, Palette.textSecondary, alignment = Layout.Alignment.ALIGN_CENTER)
    private val terminal = View(context)
    private val terminalShape = GradientDrawable()
    private val commandLabel = Label(context, TextStyles.connectCommand, Palette.text)

    init {
        titleLabel.text = title
        bodyLabel.text = body
        terminal.background = terminalShape
        listOf(titleLabel, bodyLabel, terminal, commandLabel).forEach(::addView)
        applyTheme()
    }

    override fun applyTheme() {
        setBackgroundColor(Theme.color(Palette.groupedBackground))
        // On iOS the glass surface is transparent; only its hairline shows.
        terminalShape.cornerRadius = dp(ConnectCommandLayout.Metrics.terminalCornerRadius)
        terminalShape.setStroke(max(1, dp(0.5).toInt()), Theme.color(Palette.glassBorder))
        // happy-app draws the command white on a transparent card, which
        // vanishes in light mode; the text colour reads in both, as on iOS.
        // Cleared first: the label keeps text that reads the same, whatever its colours.
        commandLabel.text = ""
        commandLabel.text = SpannableStringBuilder("$ ").apply {
            setSpan(ForegroundColorSpan(Theme.color(Palette.commandPrompt)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(command)
        }
        commandLabel.contentDescription = command
    }

    fun layout(width: Double, height: Double) = ConnectCommandLayout(
        Size(width, height),
        safeArea,
        titleLabel.measurable,
        bodyLabel.measurable,
        commandLabel.measurable,
    )

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width, height)
        titleLabel.setFrame(layout.title)
        bodyLabel.setFrame(layout.body)
        terminal.setFrame(layout.terminal)
        commandLabel.setFrame(layout.command.offsetBy(layout.terminal.x, layout.terminal.y))
    }
}
