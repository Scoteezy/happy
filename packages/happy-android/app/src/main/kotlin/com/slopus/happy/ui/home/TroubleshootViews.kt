package com.slopus.happy.ui.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.View
import android.util.TypedValue
import android.widget.TextView
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.*
import com.slopus.happy.ui.chat.ChatStrings
import com.slopus.happy.ui.chat.MeasuredText
import com.slopus.happy.ui.chat.TokenColorSpan
import com.slopus.happy.ui.components.*
import com.slopus.happy.ui.settings.PlannedTextView
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame

/** Immutable copy captured on main; all guide text and terminal measurement happens off main. */
internal class TroubleshootGuideCopy(private val context: Context) {
    private fun string(id: Int) = context.getString(id)
    val heading = string(R.string.troubleshoot_heading)
    val intro = string(R.string.troubleshoot_intro)
    val titles = listOf(R.string.troubleshoot_awake_step, R.string.troubleshoot_desktop_step, R.string.troubleshoot_terminal_step).map(::string)
    val link = string(R.string.troubleshoot_desktop_body_link)
    val bodies = listOf(string(R.string.troubleshoot_awake_body),
        string(R.string.troubleshoot_desktop_body_prefix) + link + string(R.string.troubleshoot_desktop_body_suffix),
        string(R.string.troubleshoot_terminal_body))
    val terminal = listOf(string(R.string.troubleshoot_terminal_comment),
        string(R.string.onboarding_terminal_install), string(R.string.onboarding_terminal_run))
}

internal data class TroubleshootGuidePlan(
    val layout: TroubleshootGuideLayout,
    val heading: MeasuredText,
    val intro: MeasuredText,
    val titles: List<MeasuredText>,
    val bodies: List<MeasuredText>,
    val terminal: List<MeasuredText>,
    val terminalLayout: TerminalBlockLayout,
) {
    companion object {
        fun make(copy: TroubleshootGuideCopy, width: Double, env: TextLayouts.Env): TroubleshootGuidePlan {
            fun text(value: String, style: TextStyle, color: ColorToken) = MeasuredText(ChatStrings.string(value, color), style, env)
            val heading = text(copy.heading, TextStyles.emptyStateTitle, Palette.text)
            val intro = text(copy.intro, TextStyles.checklistBody, Palette.textSecondary)
            val titles = copy.titles.map { text(it, TextStyles.checklistTitle, Palette.text) }
            val bodies = copy.bodies.mapIndexed { index, value ->
                val styled = SpannableStringBuilder(ChatStrings.string(value, Palette.textSecondary))
                if (index == 1) {
                    val start = value.indexOf(copy.link)
                    if (start >= 0) {
                        styled.setSpan(UnderlineSpan(), start, start + copy.link.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        styled.setSpan(TokenColorSpan(Palette.text), start, start + copy.link.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                MeasuredText(styled, TextStyles.checklistBody, env)
            }
            val terminal = copy.terminal.mapIndexed { index, value ->
                val styled = SpannableStringBuilder(ChatStrings.string(if (index == 0) value else "$ $value",
                    if (index == 0) Palette.terminalComment else Palette.terminalText))
                if (index > 0) styled.setSpan(TokenColorSpan(Palette.terminalPrompt), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                MeasuredText(styled, TextStyles.terminalLine, env)
            }
            val layout = TroubleshootGuideLayout(width, heading = heading.measurable, intro = intro.measurable,
                steps = titles.indices.map { index -> TroubleshootGuideLayout.Step(titles[index].measurable, bodies[index].measurable,
                    if (index == 2) Measurable { TerminalBlockLayout(it, terminal.map { line -> line.measurable }).size } else null) })
            val terminalLayout = TerminalBlockLayout(checkNotNull(layout.steps.last().terminal).width, terminal.map { it.measurable })
            return TroubleshootGuidePlan(layout, heading, intro, titles, bodies, terminal, terminalLayout)
        }
    }
}

/** HappyUI/TroubleshootGuideView. Frames and premeasured text come from its plan. */
internal class TroubleshootGuideView(context: Context) : ManualLayout(context) {
    var onOpenWebsite: (() -> Unit)? = null
    private fun label() = PlannedTextView(context).also { it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; addView(it) }
    private val heading = label().apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) }
    private val intro = label()
    private val icons = listOf("moon-outline", "desktop-outline", "terminal-outline").map {
        IconView(context, Icon.ionicons(it), 22.0, Palette.textSecondary).also(::addView)
    }
    private val titles = List(3) { label() }
    private val bodies = List(3) { label() }
    private val terminalBackground = View(context).apply {
        background = GradientDrawable().apply {
            setColor(Theme.color(Palette.terminalBackground))
            cornerRadius = dp(TerminalBlockLayout.Metrics.cornerRadius)
            setStroke(dp(TerminalBlockLayout.Metrics.borderWidth).toInt().coerceAtLeast(1), Theme.color(Palette.terminalBorder))
        }
        this@TroubleshootGuideView.addView(this)
    }
    private val terminal = List(3) { SelectableTerminalLine(context).also(::addView) }
    var plan: TroubleshootGuidePlan? = null
        set(value) {
            field = value
            fun apply(view: PlannedTextView, text: MeasuredText) { view.text = text; view.contentDescription = text.string }
            value?.let {
                apply(heading, it.heading); apply(intro, it.intro)
                titles.zip(it.titles).forEach { (view, text) -> apply(view, text) }
                bodies.zip(it.bodies).forEach { (view, text) -> apply(view, text) }
                terminal.indices.forEach { index -> terminal[index].bind(it.terminal[index], it.terminalLayout.lines[index], if (index == 0) 0 else 2) }
            }
            requestLayout()
        }

    init { bodies[1].setOnClickListener { onOpenWebsite?.invoke() } }

    override fun sizeThatFits(width: Double, height: Double) = plan?.layout?.size ?: Size(width, 0.0)
    override fun layoutSubviews(width: Double, height: Double) {
        val plan = plan ?: return
        heading.setFrame(plan.layout.heading); intro.setFrame(plan.layout.intro)
        plan.layout.steps.forEachIndexed { index, frame ->
            icons[index].setFrame(frame.icon); titles[index].setFrame(frame.title); bodies[index].setFrame(frame.body)
        }
        val frame = checkNotNull(plan.layout.steps.last().terminal)
        terminalBackground.setFrame(frame)
        terminal.zip(plan.terminalLayout.lines).forEach { (view, line) -> view.setFrame(Rect(frame.x + line.x, frame.y + line.y, line.width, line.height)) }
    }
}

/** Android's selection handles/menu, with the native terminal's unselectable "$ " prefix. */
internal class SelectableTerminalLine(context: Context) : TextView(context) {
    private var promptLength = 0
    init {
        setTextIsSelectable(true)
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        background = null
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        setLineSpacing(0f, 1f)
    }

    fun bind(measured: MeasuredText, frame: Rect, prefix: Int) {
        promptLength = prefix
        // Reuse the off-main exact-line-height spans as well as its font. TextView
        // owns Android's selection rendering; no different terminal typography.
        val layout = measured.layout(frame)
        typeface = layout.paint.typeface
        setTextSize(TypedValue.COMPLEX_UNIT_PX, layout.paint.textSize)
        letterSpacing = layout.paint.letterSpacing
        setText(layout.text, BufferType.SPANNABLE)
        contentDescription = measured.string.drop(prefix)
    }

    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        if (promptLength == 0 || start < 0 || end < 0 || start == end) return
        val content = text as? Spannable ?: return
        val floor = promptLength.coerceAtMost(content.length)
        if (start < floor || end < floor) Selection.setSelection(content, start.coerceAtLeast(floor), end.coerceAtLeast(floor))
    }
}

/** HappyUI/CopyPromptView: inverted normal button over a centred hint. */
internal class CopyPromptView(context: Context) : ManualLayout(context) {
    val button = RoundButton(context, context.getString(R.string.troubleshoot_copy_ai_prompt), RoundButtonSize.Normal, RoundButtonDisplay.Inverted)
    private val hint = Label(context, TextStyles.promptHint, Palette.textSecondary, alignment = Layout.Alignment.ALIGN_CENTER).apply {
        text = context.getString(R.string.troubleshoot_copy_ai_prompt_hint)
    }
    init { addView(button); addView(hint) }
    fun layout(width: Double) = CopyPromptLayout(width, button = button.measurable, hint = hint.measurable)
    override fun sizeThatFits(width: Double, height: Double) = layout(width).size
    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        button.setFrame(layout.button); hint.setFrame(layout.hint)
    }
}