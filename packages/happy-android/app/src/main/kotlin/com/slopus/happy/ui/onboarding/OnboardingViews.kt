package com.slopus.happy.ui.onboarding

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.View
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ChecklistRowLayout
import com.slopus.happy.layout.GetHelpButtonLayout
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.LinkComputerContentLayout
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.OnboardingLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.RoundButtonDisplay
import com.slopus.happy.layout.RoundButtonSize
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.WelcomeContentLayout
import com.slopus.happy.layout.Wordmark
import com.slopus.happy.layout.WordmarkMetrics
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.RoundButton
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.ScrollContainer
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.toAndroidPath

/** The stacked "Happy / Engineering" wordmark, drawn from its outlines (happy-ios `WordmarkView`). */
class WordmarkView(context: Context) : View(context), ThemeAware {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        contentDescription = "Happy Engineering"
    }

    override fun applyTheme() = invalidate()

    override fun onDraw(canvas: Canvas) {
        val d = Theme.density.toDouble()
        for (layer in Wordmark.graphic.fitted(Rect(0.0, 0.0, px(width), px(height)))) {
            layer.path.toAndroidPath(d.toFloat(), path)
            paint.color = Theme.color(layer.color)
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        val size: Size get() = WordmarkMetrics.size()
    }
}

/** Get help: a help icon and its label in a pill that highlights when pressed (OnboardingScreen.tsx). */
class GetHelpButton(context: Context) : ManualLayout(context), ThemeAware {
    private val icon = IconView(context, Icon.ionicons("help-circle-outline"), GetHelpButtonLayout.Metrics.iconSize, Palette.textSecondary)
    private val label = Label(context, TextStyles.getHelp, Palette.textSecondary, maxLines = 1).apply {
        text = context.getString(R.string.onboarding_get_help)
    }
    var onTap: (() -> Unit)? = null

    init {
        addView(icon)
        addView(label)
        isClickable = true
        contentDescription = label.text
        setOnClickListener { onTap?.invoke() }
        applyTheme()
    }

    val layout: GetHelpButtonLayout get() = GetHelpButtonLayout(label.measure(Double.MAX_VALUE))

    override fun sizeThatFits(width: Double, height: Double) = layout.size

    override fun layoutSubviews(width: Double, height: Double) {
        val l = layout
        icon.setFrame(l.icon)
        label.setFrame(l.label)
    }

    override fun applyTheme() {
        val mask = GradientDrawable().apply { cornerRadius = dp(GetHelpButtonLayout.Metrics.cornerRadius); setColor(0xFFFFFFFF.toInt()) }
        background = RippleDrawable(ColorStateList.valueOf(Theme.color(Palette.surfacePressedOverlay)), null, mask)
    }
}

/**
 * The first-run template (OnboardingScreen.tsx, happy-ios
 * `OnboardingScreenView`): content centred over a primary button and a text
 * action, Get help in the corner. The content and secondary slots reserve
 * the room of every alternative, so the buttons never move between screens.
 */
class OnboardingScreenView(
    context: Context,
    val content: View,
    private val contentMeasure: (Double) -> Size,
    val primary: RoundButton,
    val secondary: RoundButton,
    val tertiary: RoundButton? = null,
) : ManualLayout(context), ThemeAware {
    val scroll = ScrollContainer(context)
    val help = GetHelpButton(context)

    /** Includes the bar at the top and the gesture area at the bottom. */
    var safeArea: LayoutInsets = LayoutInsets.Zero
        set(value) { if (field != value) { field = value; requestLayout() } }

    var accessoryHeight: Double? = null
        set(value) { if (field != value) { field = value; requestLayout() } }

    /** The accessory's frame in this view's coordinates, after the last layout. */
    var accessoryFrame: Rect? = null
        private set

    var onLayout: (() -> Unit)? = null

    private val reservedContent = mutableListOf<Measurable>()
    private val reservedSecondary: List<RoundButton> = listOf(
        R.string.onboarding_restore_existing, R.string.onboarding_create_account, R.string.onboarding_paste_link,
    ).map { RoundButton(context, context.getString(it), RoundButtonSize.Normal, RoundButtonDisplay.Inverted) }

    var usesGlass = false
        set(value) {
            if (field == value) return
            field = value
            primary.glass = if (value) RoundButton.Glass.Prominent else null
            (listOf(secondary) + listOfNotNull(tertiary)).forEach { it.glass = if (value) RoundButton.Glass.Regular else null }
            requestLayout()
        }

    init {
        reservedContent += WelcomeContentView.measurable(context)
        reservedContent += LinkComputerContentView.measurable(context)
        listOf(content, primary, secondary).forEach(scroll::addContent)
        tertiary?.let(scroll::addContent)
        addView(scroll)
        addView(help)
        applyTheme()
    }

    /** Holds the room of another content too (Direct's steps under Relay's screens). */
    fun reserve(measure: Measurable) {
        reservedContent += measure
        requestLayout()
    }

    fun layout(width: Double, height: Double): OnboardingLayout = OnboardingLayout(
        OnboardingLayout.Input(
            Size(width, height),
            safeArea,
            Measurable.largest(listOf(Measurable(contentMeasure)) + reservedContent),
            Measurable { primary.layout(it).size },
            Measurable.largest(listOf(Measurable { secondary.layout(it).size }) + reservedSecondary.map { b -> Measurable { b.layout(it).size } }),
            tertiary?.let { t -> Measurable { t.layout(it).size } },
            help.layout.size,
            usesGlass,
            accessoryHeight,
        ),
    )

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width, height)
        scroll.setFrame(layout.scroll)
        scroll.place(
            layout.contentSize,
            buildMap {
                put(content, layout.content)
                put(primary, layout.primary)
                put(secondary, layout.secondary)
                tertiary?.let { put(it, layout.tertiary) }
            },
        )
        help.setFrame(layout.help)
        accessoryFrame = layout.accessory?.offsetBy(layout.scroll.x, layout.scroll.y - px(scroll.scrollY))
        onLayout?.invoke()
    }

    override fun applyTheme() {
        setBackgroundColor(Theme.color(Palette.groupedBackground))
    }
}

/** Welcome's content: the wordmark, the headline and the three value props (WelcomeContent). */
class WelcomeContentView(context: Context) : ManualLayout(context) {
    private val wordmark = WordmarkView(context)
    private val title = Label(context, TextStyles.onboardingTitle, Palette.text).apply { text = context.getString(R.string.onboarding_headline) }
    private val props = listOf(R.string.onboarding_prop_encrypted, R.string.onboarding_prop_no_password, R.string.onboarding_prop_open_source).map {
        Label(context, TextStyles.onboardingProp, Palette.textSecondary).apply { text = context.getString(it) }
    }

    init {
        addView(wordmark)
        addView(title)
        props.forEach(::addView)
    }

    fun layout(width: Double) = WelcomeContentLayout(width, title.measurable, props.map { it.measurable })

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val l = layout(width)
        wordmark.setFrame(l.wordmark)
        title.setFrame(l.title)
        props.zip(l.props).forEach { (label, frame) -> label.setFrame(frame) }
    }

    companion object {
        /** Welcome's size at a width, measured without a view, for the screens that reserve its room. */
        fun measurable(context: Context): Measurable {
            val view = WelcomeContentView(context)
            return Measurable { view.layout(it).size }
        }
    }
}

/** Link Your Computer: the title, a two-step checklist and a reserved error line (LinkComputerContent). */
class LinkComputerContentView(context: Context) : ManualLayout(context) {
    enum class Step { Install, Open }

    var ticked: Set<Step> = emptySet()
        set(value) { field = value; updateBoxes() }

    var error: String? = null
        set(value) { field = value; errorLabel.text = value.orEmpty() }

    var onOpenWebsite: (() -> Unit)? = null
    var onToggle: ((Set<Step>) -> Unit)? = null

    private val title = Label(context, TextStyles.onboardingTitle, Palette.text).apply { text = context.getString(R.string.onboarding_link_title) }
    private val heads = Step.entries.map { View(context) }
    private val boxes = Step.entries.map { IconView(context, null, ChecklistRowLayout.Metrics.boxSize) }
    private val rowTitles = Step.entries.map { Label(context, TextStyles.checklistTitle, Palette.text) }
    private val bodies = Step.entries.map { Label(context, TextStyles.checklistBody, Palette.textSecondary) }
    private val errorLabel = Label(context, TextStyles.onboardingError, Palette.warningCritical, maxLines = 1, alignment = android.text.Layout.Alignment.ALIGN_CENTER)

    init {
        rowTitles[0].text = context.getString(R.string.onboarding_install_step)
        rowTitles[1].text = context.getString(R.string.onboarding_open_step)
        bodies[0].text = installBody(context)
        bodies[1].text = context.getString(R.string.onboarding_open_body)
        addView(title)
        Step.entries.indices.forEach { i ->
            heads[i].isClickable = true
            heads[i].contentDescription = rowTitles[i].text
            heads[i].setOnClickListener { toggle(Step.entries[i]) }
            addView(heads[i]); addView(boxes[i]); addView(rowTitles[i]); addView(bodies[i])
        }
        bodies[0].setOnClickListener { onOpenWebsite?.invoke() }
        addView(errorLabel)
        updateBoxes()
    }

    var isEnabledSteps: Boolean = true
        set(value) { field = value; heads.forEach { it.isEnabled = value } }

    fun layout(width: Double) = LinkComputerContentLayout(
        width,
        title.measurable,
        Step.entries.indices.map { LinkComputerContentLayout.Row(rowTitles[it].measurable, bodies[it].measurable) },
        errorLabel.lineHeight,
    )

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val l = layout(width)
        title.setFrame(l.title)
        l.rows.forEachIndexed { i, row ->
            heads[i].setFrame(row.head)
            boxes[i].setFrame(row.box)
            rowTitles[i].setFrame(row.title)
            row.body?.let { bodies[i].setFrame(it) }
        }
        errorLabel.setFrame(l.error)
    }

    private fun toggle(step: Step) {
        ticked = if (step in ticked) ticked - step else ticked + step
        onToggle?.invoke(ticked)
    }

    private fun updateBoxes() {
        Step.entries.forEachIndexed { i, step -> boxes[i].showChecklistBox(step in ticked) }
    }

    companion object {
        fun measurable(context: Context): Measurable {
            val view = LinkComputerContentView(context)
            return Measurable { view.layout(it).size }
        }

        /** "Download Happy from happy.engineering." with the link underlined in the text colour. */
        fun installBody(context: Context): CharSequence {
            val prefix = context.getString(R.string.onboarding_install_body_prefix)
            val link = context.getString(R.string.onboarding_install_body_link)
            val text = SpannableString(prefix + link + context.getString(R.string.onboarding_install_body_suffix))
            text.setSpan(UnderlineSpan(), prefix.length, prefix.length + link.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ThemeColorSpan(Palette.text), prefix.length, prefix.length + link.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return text
        }
    }
}

fun IconView.showChecklistBox(checked: Boolean) {
    icon = Icon.ionicons(if (checked) "checkmark-circle" else "ellipse-outline")
    tint = if (checked) Palette.success else Palette.textSecondary
}

/** A foreground colour that follows the theme when the text is drawn again. */
class ThemeColorSpan(private val token: com.slopus.happy.layout.ColorToken) : android.text.style.CharacterStyle() {
    override fun updateDrawState(tp: android.text.TextPaint) {
        tp.color = Theme.color(token)
    }
}
