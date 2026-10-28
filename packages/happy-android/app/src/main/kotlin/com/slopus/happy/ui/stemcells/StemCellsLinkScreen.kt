package com.slopus.happy.ui.stemcells

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.view.View
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.layout.ChecklistRowLayout
import com.slopus.happy.layout.FontFace
import com.slopus.happy.layout.LinkComputerContentLayout
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.RoundButtonDisplay
import com.slopus.happy.layout.RoundButtonSize
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.stemcells.StemCellsLinkContentLayout
import com.slopus.happy.layout.stemcells.StemCellsLinkSwitchLayout
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.RoundButton
import com.slopus.happy.ui.components.SegmentedControl
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.nav.NavigationBar
import com.slopus.happy.ui.nav.ScreenStack
import com.slopus.happy.ui.onboarding.OnboardingScreenView
import com.slopus.happy.ui.onboarding.ThemeColorSpan
import com.slopus.happy.ui.onboarding.WelcomePane
import com.slopus.happy.ui.onboarding.WelcomeScreen
import com.slopus.happy.ui.server.ServerScreen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.launch

/**
 * Linking a computer (happy-ios `StemCellsLinkViewController`): the first
 * screen in development and preview builds when nothing is signed in. A
 * switch just above the screen's buttons chooses the way: Relay, the
 * default, through a Happy account (Welcome); or Direct, the experiment
 * (tagged Beta), whose code scanned or link pasted connects this phone
 * straight to the computer. Choosing Direct turns the experiment on, Relay
 * turns it off. The gear opens the server.
 */
class StemCellsLinkScreen(context: Context, private val graph: AppGraph) : BarScreen(context) {
    private enum class Method { Relay, Direct }

    private val control = SegmentedControl(context, listOf(context.getString(R.string.stem_relay), context.getString(R.string.stem_direct)))
    private val betaTag = BetaTag(context)
    private var method = if (graph.stemCells.directConnection) Method.Direct else Method.Relay
    private val relay by lazy { WelcomePane(context, graph, this).view }
    private val direct by lazy { DirectPane(context).view }
    private var shown: OnboardingScreenView? = null
    private val root = Root(context)
    private var reservesSteps = false

    override fun createContent(): View {
        bar.actions = listOf(NavigationBar.Action(WelcomeScreen.SETTINGS_ICON, context.getString(R.string.onboarding_settings_title), 22.0) {
            push(ServerScreen(context, graph))
        })
        control.contentDescription = "stem-link-method"
        control.onChange = { chosen(if (it == 1) Method.Direct else Method.Relay) }
        show(method, animated = false)
        return root
    }

    override fun onAppear() {
        super.onAppear()
        val now = if (graph.stemCells.directConnection) Method.Direct else Method.Relay
        if (now != method) {
            method = now
            show(now, animated = false)
        }
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        shown?.safeArea = contentSafeArea
    }

    private fun chosen(to: Method) {
        if (to == method) return
        method = to
        graph.stemCells.directConnection = to == Method.Direct
        show(to, animated = true)
    }

    private fun show(method: Method, animated: Boolean) {
        control.selectedIndex = if (method == Method.Direct) 1 else 0
        val next = if (method == Method.Direct) direct else relay
        if (next === shown) return
        val previous = shown
        shown = next
        next.usesGlass = true
        next.accessoryHeight = StemCellsLinkSwitchLayout.Metrics.height
        // Direct's steps are the tallest: Relay's screen holds their room too,
        // so the switch and the buttons stay put as the two cross.
        if (method == Method.Relay && !reservesSteps) {
            reservesSteps = true
            val steps = StemCellsLinkContentView(context)
            next.reserve(Measurable { steps.layout(it).size })
        }
        next.safeArea = contentSafeArea
        next.onLayout = { if (shown === next) root.placeSwitch() }
        next.scroll.setOnScrollChangeListener { _, _, _, _, _ -> if (shown === next) root.placeSwitch() }
        if (next.parent == null) root.addView(next, 0)
        if (!animated || previous == null) {
            previous?.let(root::removeView)
            root.requestLayout()
            return
        }
        next.alpha = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = ScreenStack.EASE
            addUpdateListener {
                val t = it.animatedValue as Float
                next.alpha = t
                previous.alpha = 1 - t
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    previous.alpha = 1f
                    if (previous !== shown) root.removeView(previous)
                }
            })
            start()
        }
        root.requestLayout()
    }

    /** The shown screen under the switch and its tag. */
    private inner class Root(context: Context) : ManualLayout(context), ThemeAware {
        init {
            addView(control)
            addView(betaTag)
            applyTheme()
        }

        override fun layoutSubviews(width: Double, height: Double) {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child is OnboardingScreenView) child.setFrame(Rect(0.0, 0.0, width, height))
            }
            placeSwitch()
        }

        /** Where the shown screen keeps room for it, as that screen scrolls; hidden once it scrolls under the bar. */
        fun placeSwitch() {
            val screen = shown ?: return
            val frame = screen.accessoryFrame ?: return
            val layout = StemCellsLinkSwitchLayout.inAccessory(frame, betaTag.intrinsicSize())
            control.setFrame(layout.control)
            betaTag.setFrame(layout.tag)
            val visible = layout.tag.minY >= contentSafeArea.top
            control.visibility = if (visible) VISIBLE else INVISIBLE
            betaTag.visibility = control.visibility
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(Palette.groupedBackground))
        }
    }

    /** Direct's way: the computer's code scanned, or its link pasted (happy-ios `StemCellsDirectLinkViewController`). */
    private inner class DirectPane(context: Context) {
        private val content = StemCellsLinkContentView(context)
        val view = OnboardingScreenView(
            context,
            content,
            { content.layout(it).size },
            RoundButton(context, context.getString(R.string.onboarding_scan_button)),
            RoundButton(context, context.getString(R.string.onboarding_paste_link), RoundButtonSize.Normal, RoundButtonDisplay.Inverted),
        )

        init {
            view.usesGlass = true
            view.primary.onTap = { scan() }
            view.secondary.onTap = { paste() }
            view.secondary.contentDescription = "stem-link-paste"
            content.onOpenWebsite = { Alerts.open(context, DESKTOP_URL) }
            view.help.onTap = { Alerts.help(context) }
        }

        private fun scan() {
            scope.launch {
                val result = com.slopus.happy.connect.GmsQrScanner(context).scan()
                val text = (result as? com.slopus.happy.connect.ScanResult.Scanned)?.value ?: return@launch
                open(text)
            }
        }

        private fun paste() {
            scope.launch {
                val text = Alerts.prompt(
                    context,
                    context.getString(R.string.onboarding_paste_link_title),
                    context.getString(R.string.onboarding_paste_link_message),
                    placeholder = "happy://…",
                    confirmTitle = context.getString(R.string.onboarding_paste_link_confirm),
                )?.trim()
                if (text.isNullOrEmpty()) return@launch
                open(text)
            }
        }

        /** StemCellsScan.open: pairing straight to a computer is not ported yet (Stem Cells, build stage 8). */
        private fun open(@Suppress("UNUSED_PARAMETER") link: String) {
            Alerts.show(context, context.getString(R.string.stem_direct_connection), DIRECT_NOT_PORTED)
        }
    }

    companion object {
        const val DESKTOP_URL = "https://happy.engineering"
        private const val DIRECT_NOT_PORTED = "Direct pairing is not available in this Android build yet."
    }
}

/** Direct's "Beta" tag on the switch: small capitals in a capsule (happy-ios `StemCellsBetaTag`). */
class BetaTag(context: Context) : View(context), ThemeAware {
    private val label = context.getString(R.string.stem_beta).uppercase()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        contentDescription = context.getString(R.string.stem_beta)
        applyTheme()
    }

    /** The text's width and 14, 18 high. */
    fun intrinsicSize(): Size {
        text.typeface = com.slopus.happy.ui.theme.Fonts.typeface(FontFace.SemiBold)
        text.textSize = dp(10.0)
        return Size(px(text.measureText(label)) + 14, 18.0)
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, dp(9.0), dp(9.0), fill)
        val fm = text.fontMetrics
        canvas.drawText(label, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2, text)
    }

    override fun applyTheme() {
        text.color = 0xFFFFFFFF.toInt()
        text.typeface = com.slopus.happy.ui.theme.Fonts.typeface(FontFace.SemiBold)
        text.textSize = dp(10.0)
        fill.color = Theme.color(StemCellsPalette.stemCellsSettingsTilePurple)
        invalidate()
    }
}

/**
 * The title, centred, then what to do on the computer in numbered steps: the
 * relay checklist's two (install, open Link Mobile App), then the scan
 * (happy-ios `StemCellsLinkContentView`).
 */
class StemCellsLinkContentView(context: Context) : ManualLayout(context), ThemeAware {
    var onOpenWebsite: (() -> Unit)? = null

    private val title = Label(context, TextStyles.onboardingTitle, Palette.text, alignment = Layout.Alignment.ALIGN_CENTER).apply {
        text = context.getString(R.string.stem_link_title)
    }
    private val numbers = (1..3).map { NumberCircle(context, it) }
    private val stepTitles = (1..3).map { Label(context, TextStyles.checklistTitle, Palette.text) }
    private val bodies = (1..3).map { Label(context, TextStyles.checklistBody, Palette.textSecondary) }

    init {
        addView(title)
        val link = context.getString(R.string.onboarding_install_body_link)
        val install = context.getString(R.string.onboarding_install_body_prefix) + link + context.getString(R.string.onboarding_install_body_suffix)
        val steps = listOf(
            context.getString(R.string.onboarding_install_step) to install,
            context.getString(R.string.onboarding_open_step) to context.getString(R.string.onboarding_open_body),
            context.getString(R.string.stem_link_scan_step) to context.getString(R.string.stem_link_scan_body),
        )
        steps.forEachIndexed { index, (stepTitle, body) ->
            stepTitles[index].text = stepTitle
            stepTitles[index].contentDescription = "${index + 1}. $stepTitle"
            bodies[index].text = body
            addView(numbers[index])
            addView(stepTitles[index])
            addView(bodies[index])
        }
        val start = install.indexOf(link)
        if (start >= 0) {
            bodies[0].text = android.text.SpannableString(install).apply {
                setSpan(android.text.style.UnderlineSpan(), start, start + link.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ThemeColorSpan(Palette.text), start, start + link.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        bodies[0].setOnClickListener { onOpenWebsite?.invoke() }
    }

    fun layout(width: Double) = StemCellsLinkContentLayout(
        width,
        title.measurable,
        stepTitles.indices.map { LinkComputerContentLayout.Row(stepTitles[it].measurable, bodies[it].measurable) },
    )

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        title.setFrame(layout.title)
        layout.steps.forEachIndexed { index, step ->
            numbers[index].setFrame(step.box)
            stepTitles[index].setFrame(step.title)
            step.body?.let { bodies[index].setFrame(it) }
        }
    }

    override fun applyTheme() = numbers.forEach { it.invalidate() }

    /** A step's number in a 1.5 ring. */
    private class NumberCircle(context: Context, private val number: Int) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            ring.strokeWidth = dp(1.5)
            ring.color = Theme.color(Palette.textSecondary)
            val r = dp(ChecklistRowLayout.Metrics.boxSize / 2)
            canvas.drawCircle(width / 2f, height / 2f, r - ring.strokeWidth / 2, ring)
            text.typeface = com.slopus.happy.ui.theme.Fonts.typeface(NUMBER.face)
            text.textSize = dp(NUMBER.size)
            text.color = Theme.color(Palette.text)
            val fm = text.fontMetrics
            canvas.drawText(number.toString(), width / 2f, height / 2f - (fm.ascent + fm.descent) / 2, text)
        }

        companion object {
            val NUMBER = TextStyle(FontFace.SemiBold, 14.0)
        }
    }
}
