package com.slopus.happy.ui.newsession

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.Layout
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.ProgressBar
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.NewSessionComposerLayout
import com.slopus.happy.layout.NewSessionMaterial
import com.slopus.happy.layout.NewSessionPickerContentLayout
import com.slopus.happy.layout.NewSessionPickerLayout
import com.slopus.happy.layout.NewSessionScreenLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.buttonSecondaryTint
import com.slopus.happy.layout.composerInput
import com.slopus.happy.layout.glassHighlight
import com.slopus.happy.layout.glassShadow
import com.slopus.happy.layout.newSessionAgent
import com.slopus.happy.layout.newSessionGlassFill
import com.slopus.happy.layout.newSessionOfflineBody
import com.slopus.happy.layout.newSessionOfflineTitle
import com.slopus.happy.layout.newSessionOption
import com.slopus.happy.layout.newSessionOptionDetail
import com.slopus.happy.layout.newSessionPickerTitle
import com.slopus.happy.layout.newSessionPopoverFill
import com.slopus.happy.layout.newSessionRow
import com.slopus.happy.layout.newSessionSendFill
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.TextField
import com.slopus.happy.ui.home.HeaderControlView
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.ScrollContainer
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame
import kotlin.math.min

// The new-session screen's pieces (happy-ios NewSessionViews.swift). Liquid
// glass has no Android counterpart: the composer, Send and the popovers are
// solid surfaces in the colour the glass shows over the page, with its rim
// and the platform's elevation shadow tinted glass.shadow.

/**
 * A rounded surface standing in for MobileGlassSurface: [fill] inside a
 * hairline [border], over the elevation shadow. Content goes on top as
 * siblings placed by the owner.
 */
class NewSessionGlassView(
    context: Context,
    private val fill: ColorToken,
    private val border: ColorToken?,
    private val shadow: Double?,
) : View(context), ThemeAware {
    var cornerRadius = 0.0
        set(value) { field = value; invalidateOutline(); applyTheme() }

    private val shape = GradientDrawable()

    init {
        background = shape
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, min(dp(cornerRadius), view.height / 2f))
                outline.alpha = 1f
            }
        }
        shadow?.let { elevation = dp(it) }
        applyTheme()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shape.cornerRadius = min(dp(cornerRadius), h / 2f)
    }

    override fun applyTheme() {
        shape.setColor(Theme.color(fill))
        shape.cornerRadius = min(dp(cornerRadius), height / 2f).takeIf { height > 0 } ?: dp(cornerRadius)
        if (border != null) shape.setStroke(1, Theme.color(border)) else shape.setStroke(0, 0)
        if (shadow != null) {
            // glass.shadow carries its own alpha.
            outlineAmbientShadowColor = Theme.color(Palette.glassShadow)
            outlineSpotShadowColor = Theme.color(Palette.glassShadow)
        }
        invalidate()
    }
}

/**
 * The header's title: "Start New Session" in the capsule the home header's
 * controls use, beside back (Header.tsx's `mobileTitlePill`).
 */
class NewSessionTitleView(context: Context, title: String) : ManualLayout(context) {
    private val capsule = HeaderControlView(context)
    val label = Label(context, TextStyles.headerTitle, Palette.text, maxLines = 1)

    init {
        addView(capsule)
        label.text = title
        // Over the capsule's elevation.
        label.elevation = capsule.elevation
        label.outlineProvider = null
        addView(label)
        contentDescription = title
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun size(): Size = Size(kotlin.math.ceil(label.measure(Double.MAX_VALUE).width) + 2 * PADDING, CONTROL)

    override fun layoutSubviews(width: Double, height: Double) {
        capsule.setFrame(Rect(0.0, 0.0, width, height))
        // The label gets the whole line it measured: a frame snapped to the
        // pixel a hair narrower would cut its last letter off.
        val text = label.measure(Double.MAX_VALUE)
        label.setFrame(Rect(PADDING, (height - text.height) / 2, kotlin.math.ceil(text.width) + 1, text.height))
    }

    companion object {
        /** `mobileTitlePill`'s padding. */
        const val PADDING = 14.0
        /** HomeHeaderLayout's control size. */
        const val CONTROL = 44.0
    }
}

/** Fades to [NewSessionMaterial.pressedOpacity] while held, as BubblePressable and `configRowPressed` do. */
abstract class NewSessionPressable(context: Context) : ManualLayout(context) {
    init {
        isClickable = true
        isFocusable = true
    }

    /** The popover it opened is showing (`composerControlActive`). */
    var isActive = false
        set(value) {
            field = value
            activeShape.setColor(if (value) Theme.color(Palette.optionSelected) else 0)
            background = if (value) activeShape else null
        }

    private val activeShape = GradientDrawable()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        activeShape.cornerRadius = h / 2f
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        pressedChanged(pressed)
    }

    protected open fun pressedChanged(pressed: Boolean) {
        alpha = if (pressed) NewSessionMaterial.pressedOpacity.toFloat() else 1f
    }

    fun onTap(action: () -> Unit) = setOnClickListener { action() }
}

/** A place row (`configRow`): a 15dp glyph, the value in one line, a chevron. Pressing fades it. */
class NewSessionConfigRowView(context: Context, icon: Icon) : NewSessionPressable(context) {
    val iconView = IconView(context, icon, NewSessionScreenLayout.Metrics.rowIconSize, Palette.textSecondary)
    val label = Label(context, TextStyles.newSessionRow, Palette.text, maxLines = 1)
    private val chevron = IconView(context, Icon.ionicons("chevron-down"), NewSessionScreenLayout.Metrics.rowChevronSize, Palette.textSecondary)
    private var frames: NewSessionScreenLayout.RowFrame? = null

    init {
        addView(iconView)
        addView(label)
        addView(chevron)
    }

    var value: String
        get() = label.text.toString()
        set(value) {
            if (label.text.toString() == value) return
            label.text = value
            contentDescription = value
            requestLayout()
        }

    /** Off while the computer is offline: faded, and presses go nowhere. */
    var isLive = true
        set(value) {
            field = value
            isEnabled = value
            isClickable = value
            alpha = if (value) 1f else NewSessionMaterial.offlineOpacity.toFloat()
        }

    override fun pressedChanged(pressed: Boolean) {
        if (isLive) super.pressedChanged(pressed)
    }

    fun apply(frame: NewSessionScreenLayout.RowFrame) {
        frames = frame
        setFrame(frame.frame)
        layoutSubviews(frame.frame.width, frame.frame.height)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val frame = frames ?: return
        iconView.setFrame(frame.icon)
        label.setFrame(frame.label)
        frame.chevron?.let { chevron.setFrame(it) }
    }
}

/** "Machine is offline" and what to try (`offlineHelp`). */
class NewSessionOfflineView(context: Context) : ManualLayout(context) {
    private val icon = IconView(context, Icon.ionicons("cloud-offline-outline"), NewSessionScreenLayout.Metrics.offlineIconSize, Palette.statusDisconnected)
    val title = Label(context, TextStyles.newSessionOfflineTitle, Palette.statusDisconnected)
    val body = Label(context, TextStyles.newSessionOfflineBody, Palette.textSecondary)
    private var frames: NewSessionScreenLayout.RowFrame? = null

    init {
        title.text = context.getString(R.string.new_session_machine_offline)
        addView(icon)
        addView(title)
        addView(body)
    }

    fun apply(frame: NewSessionScreenLayout.RowFrame) {
        frames = frame
        setFrame(frame.frame)
        layoutSubviews(frame.frame.width, frame.frame.height)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val frame = frames ?: return
        icon.setFrame(frame.icon)
        title.setFrame(frame.label)
        frame.body?.let { body.setFrame(it) }
    }
}

/** A round control in the composer's row: a glyph in a 38dp circle that fades when pressed. */
class NewSessionComposerButton(context: Context, icon: Icon, size: Double, label: String) : NewSessionPressable(context) {
    private val iconView = IconView(context, icon, size, Palette.textSecondary)

    init {
        addView(iconView)
        contentDescription = label
    }

    override fun layoutSubviews(width: Double, height: Double) = iconView.setFrame(Rect(0.0, 0.0, width, height))
}

/** The harness button (`composerAgentButton`): its logo, its name and a chevron in a 36dp capsule. */
class NewSessionAgentButton(context: Context) : NewSessionPressable(context), ThemeAware {
    private val logo = LogoView(context)
    val label = Label(context, TextStyles.newSessionAgent, Palette.buttonSecondaryTint, maxLines = 1)
    private val chevron = IconView(context, Icon.ionicons("chevron-down"), NewSessionComposerLayout.Metrics.agentChevronSize, Palette.textSecondary)
    private var frames: NewSessionComposerLayout? = null

    init {
        addView(logo)
        addView(label)
        addView(chevron)
    }

    fun update(key: String, text: String) {
        logo.resource = logo(key)
        if (label.text.toString() != text) label.text = text
        contentDescription = context.getString(R.string.home_new_session_agent, text)
    }

    fun apply(layout: NewSessionComposerLayout) {
        frames = layout
        setFrame(layout.agent)
        layoutSubviews(layout.agent.width, layout.agent.height)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = frames ?: return
        logo.setFrame(layout.agentIcon)
        label.setFrame(layout.agentLabel)
        chevron.setFrame(layout.agentChevron)
    }

    override fun applyTheme() = logo.invalidate()

    /** new/index.tsx's `agentIcons`, drawn as a template in textSecondary. */
    private fun logo(key: String): Int = when (key) {
        "rig" -> R.drawable.happy_logo
        "codex" -> R.drawable.icon_gpt
        "agy" -> R.drawable.icon_agy
        else -> R.drawable.icon_claude
    }

    private class LogoView(context: Context) : View(context) {
        var resource = 0
            set(value) {
                if (field == value) return
                field = value
                bitmap = BitmapFactory.decodeResource(resources, value)
                invalidate()
            }
        private var bitmap: Bitmap? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dst = RectF()

        override fun onDraw(canvas: Canvas) {
            val image = bitmap ?: return
            paint.colorFilter = PorterDuffColorFilter(Theme.color(Palette.textSecondary), PorterDuff.Mode.SRC_IN)
            // scaleAspectFit
            val scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
            val w = image.width * scale
            val h = image.height * scale
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            canvas.drawBitmap(image, null, dst, paint)
        }
    }
}

/**
 * Send (`mobileSendButton`): the glass circle with a light rim while it can
 * be pressed; a bare rim at 56% while it can't; a spinner while a session
 * starts.
 */
class NewSessionSendButton(context: Context) : NewSessionPressable(context), ThemeAware {
    private val shape = GradientDrawable()
    private val arrow = IconView(context, Icon.octicons("arrow-up"), NewSessionComposerLayout.Metrics.sendIconSize, Palette.text)
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)

    init {
        background = shape
        addView(arrow)
        spinner.visibility = GONE
        addView(spinner)
        contentDescription = context.getString(R.string.composer_send)
        apply()
    }

    var canSend = false
        set(value) { if (field != value) { field = value; apply() } }

    var isBusy = false
        set(value) { if (field != value) { field = value; apply() } }

    private fun apply() {
        isEnabled = canSend
        alpha = if (canSend) 1f else NewSessionMaterial.sendInactiveOpacity.toFloat()
        spinner.visibility = if (isBusy) VISIBLE else GONE
        arrow.visibility = if (isBusy) GONE else VISIBLE
        applyTheme()
    }

    // `sendButtonInnerPressed` fades the glyph, not the glass.
    override fun pressedChanged(pressed: Boolean) {
        arrow.alpha = if (pressed) 0.7f else 1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shape.cornerRadius = h / 2f
    }

    override fun applyTheme() {
        // Glass only while it can be pressed (`nativeEffect = interactive`).
        shape.setColor(if (canSend) Theme.color(Palette.newSessionSendFill) else 0)
        shape.setStroke(1, Theme.color(Palette.glassHighlight))
        spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(Palette.text))
    }

    override fun layoutSubviews(width: Double, height: Double) {
        arrow.setFrame(Rect(0.0, 0.0, width, height))
        val s = 20.0
        spinner.setFrame(Rect((width - s) / 2, (height - s) / 2, s, s))
    }
}

/**
 * The phone composer (`mobileInputBox`): the prompt, then the harness,
 * settings, the microphone and Send, on the glass card with its shadow.
 */
class NewSessionComposerView(context: Context) : ManualLayout(context) {
    private val glass = NewSessionGlassView(context, Palette.newSessionGlassFill, Palette.glassBorder, GLASS_ELEVATION).apply {
        cornerRadius = NewSessionComposerLayout.Metrics.radius
    }
    val textView = TextField(context, TextStyles.composerInput, multiline = true)
    val agentButton = NewSessionAgentButton(context)
    val settingsButton = NewSessionComposerButton(context, Icon.ionicons("settings-outline"), NewSessionComposerLayout.Metrics.settingsIconSize, context.getString(R.string.settings_title))
    val micButton = NewSessionComposerButton(context, Icon.ionicons("mic-outline"), NewSessionComposerLayout.Metrics.micIconSize, context.getString(R.string.home_new_session_voice_input))
    val sendButton = NewSessionSendButton(context)

    var onTextChange: ((String) -> Unit)? = null
    /** The text grew or shrank a line. */
    var onHeightChange: (() -> Unit)? = null

    var showsSettings = true
        set(value) { if (field != value) { field = value; requestLayout() } }

    private var settingText = false
    private var lastHeight = -1.0
    private val env get() = TextLayouts.Env(Theme.density, Theme.textScale)

    init {
        clipChildren = false
        addView(glass)
        textView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        textView.imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
        textView.isVerticalScrollBarEnabled = false
        textView.onChange = { text ->
            if (!settingText) onTextChange?.invoke(text)
            reportHeight()
        }
        // Above the card's elevation, so the shadow stays under them.
        for (view in listOf(textView, agentButton, settingsButton, micButton, sendButton)) {
            view.elevation = glass.elevation
            view.outlineProvider = null
            addView(view)
        }
    }

    var placeholderText: String
        get() = textView.hint?.toString().orEmpty()
        set(value) { if (textView.hint?.toString() != value) textView.hint = value }

    val text: String get() = textView.string

    /** The draft's text, when it changed somewhere else. */
    fun setText(text: String) {
        if (text == this.text) return
        settingText = true
        textView.setText(text)
        textView.setSelection(text.length)
        settingText = false
    }

    private fun inputWidth(width: Double): Double {
        val m = NewSessionComposerLayout.Metrics
        return maxOf(0.0, width - 2 * hairline - m.paddingLeft - m.paddingRight - m.fieldPaddingLeft - m.fieldPaddingRight)
    }

    private val hairline get() = 1.0 / Theme.density

    fun layout(width: Double): NewSessionComposerLayout {
        val text = textView.string
        val textHeight = if (text.isEmpty()) 0.0 else TextLayouts.measure(text, TextStyles.composerInput, inputWidth(width), env).height
        return NewSessionComposerLayout(NewSessionComposerLayout.Input(
            width = width,
            hairline = hairline,
            textHeight = textHeight,
            agentLabel = agentButton.label.measurable,
            showsSettings = showsSettings,
            lineHeight = TextLayouts.lineHeight(TextStyles.composerInput, env),
        ))
    }

    override fun sizeThatFits(width: Double, height: Double): Size = layout(width).size

    private fun reportHeight() {
        if (width <= 0) return
        val h = layout(com.slopus.happy.ui.view.px(width)).size.height
        if (h != lastHeight) {
            lastHeight = h
            onHeightChange?.invoke()
        }
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val l = layout(width)
        lastHeight = l.size.height
        glass.setFrame(Rect(0.0, 0.0, width, height))
        textView.setFrame(l.input)
        textView.isVerticalScrollBarEnabled = l.textScrolls
        agentButton.apply(l)
        settingsButton.visibility = if (l.settings == null) GONE else VISIBLE
        l.settings?.let { settingsButton.setFrame(it) }
        micButton.setFrame(l.mic)
        sendButton.setFrame(l.send)
    }

    companion object {
        /** `mobileComposerShadow`'s 24dp blur, as the platform's elevation. */
        const val GLASS_ELEVATION = 8.0
    }
}

/** The popover a picker opens in, on its glass. Its rows come from [content]. */
class NewSessionPickerView(context: Context, val content: NewSessionPickerContentView) : ManualLayout(context) {
    private val glass = NewSessionGlassView(context, Palette.newSessionPopoverFill, Palette.glassBorder, POPOVER_ELEVATION).apply {
        cornerRadius = NewSessionPickerLayout.Metrics.radius
    }

    init {
        clipChildren = false
        addView(glass)
        content.elevation = glass.elevation
        content.outlineProvider = null
        addView(content)
        isClickable = true
    }

    override fun layoutSubviews(width: Double, height: Double) {
        glass.setFrame(Rect(0.0, 0.0, width, height))
        val m = NewSessionPickerLayout.Metrics
        content.setFrame(Rect(m.paddingHorizontal, m.paddingVertical, maxOf(0.0, width - 2 * m.paddingHorizontal), maxOf(0.0, height - 2 * m.paddingVertical)))
    }

    companion object {
        /** `nativePopoverSurface`'s 32dp blur, as the platform's elevation. */
        const val POPOVER_ELEVATION = 12.0
    }
}

/** One row of a picker (`embeddedOption`): a glyph, a label with an optional line under it, an optional trailing glyph. */
class NewSessionOptionRow(
    context: Context,
    icon: Icon,
    val iconSize: Double,
    iconTint: ColorToken,
    label: Text,
    detail: Text?,
    trailing: Icon? = null,
    trailingSize: Double? = null,
    trailingTint: ColorToken = Palette.text,
) : NewSessionPressable(context) {
    /** 0 lines wraps. */
    data class Text(val string: String, val style: TextStyle, val color: ColorToken, val lines: Int) {
        companion object {
            fun option(string: String) = Text(string, TextStyles.newSessionOption, Palette.text, 1)
            fun detail(string: String) = Text(string, TextStyles.newSessionOptionDetail, Palette.textSecondary, 1)
        }
    }

    private val iconView = IconView(context, icon, iconSize, iconTint)
    val label = Label(context, label.style, label.color, label.lines).also { it.text = label.string }
    val detail = detail?.let { text -> Label(context, text.style, text.color, text.lines).also { it.text = text.string } }
    private val trailingView = trailing?.let { IconView(context, it, trailingSize ?: 16.0, trailingTint) }
    val trailingSize: Double? = if (trailing == null) null else trailingSize ?: 16.0
    private var frames: NewSessionPickerContentLayout.ElementFrame? = null

    init {
        listOfNotNull(iconView, this.label, this.detail, trailingView).forEach(::addView)
        contentDescription = listOfNotNull(label.string, detail?.string).joinToString(", ")
    }

    val element: NewSessionPickerContentLayout.Element
        get() = NewSessionPickerContentLayout.Element.Option(iconSize, label.measurable, detail?.measurable, trailingSize)

    var isDimmed = false
        set(value) { field = value; applyAlpha(isPressed) }

    override fun pressedChanged(pressed: Boolean) = applyAlpha(pressed)

    private fun applyAlpha(pressed: Boolean) {
        alpha = ((if (isDimmed) NewSessionMaterial.dimmedOpacity else 1.0) * (if (pressed) NewSessionMaterial.pressedOpacity else 1.0)).toFloat()
    }

    fun apply(frame: NewSessionPickerContentLayout.ElementFrame) {
        frames = frame
        setFrame(frame.frame)
        layoutSubviews(frame.frame.width, frame.frame.height)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val frame = frames ?: return
        frame.icon?.let { iconView.setFrame(it) }
        frame.label?.let { label.setFrame(it) }
        frame.detail?.let { detail?.setFrame(it) }
        frame.trailing?.let { trailingView?.setFrame(it) }
    }
}

/** A one-line field with a glyph before it: the search row, or the path. */
class NewSessionFieldRow(context: Context, icon: Icon, placeholder: String, val isPath: Boolean) : ManualLayout(context) {
    private val iconView = IconView(context, icon, NewSessionPickerContentLayout.Metrics.searchIconSize, Palette.textSecondary)
    val field = TextField(context, TextStyles.newSessionOption, Palette.text, Palette.textSecondary)
    private var frames: NewSessionPickerContentLayout.ElementFrame? = null

    init {
        addView(iconView)
        field.hint = placeholder
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_URI
        field.imeOptions = if (isPath) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NONE
        addView(field)
    }

    /** The field's one line at the text size. */
    val fieldHeight: Double get() = kotlin.math.ceil(TextLayouts.lineHeight(TextStyles.newSessionOption, TextLayouts.Env(Theme.density, Theme.textScale)))

    val element: NewSessionPickerContentLayout.Element
        get() = if (isPath) NewSessionPickerContentLayout.Element.PathField(fieldHeight) else NewSessionPickerContentLayout.Element.Search(fieldHeight)

    fun apply(frame: NewSessionPickerContentLayout.ElementFrame) {
        frames = frame
        setFrame(frame.frame)
        layoutSubviews(frame.frame.width, frame.frame.height)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val frame = frames ?: return
        frame.icon?.let { iconView.setFrame(it) }
        frame.label?.let { field.setFrame(it) }
    }
}

/** A settings page's header: back, and the page's title. */
class NewSessionBackRow(context: Context, title: String) : ManualLayout(context) {
    val back = NewSessionComposerButton(context, Icon.ionicons("chevron-back"), NewSessionPickerContentLayout.Metrics.backIconSize, context.getString(R.string.common_back))
    val title = Label(context, TextStyles.newSessionPickerTitle, Palette.text, maxLines = 1).also { it.text = title }
    private var frames: NewSessionPickerContentLayout.ElementFrame? = null

    init {
        addView(back)
        addView(this.title)
    }

    val element: NewSessionPickerContentLayout.Element get() = NewSessionPickerContentLayout.Element.Back(title.measurable)

    fun apply(frame: NewSessionPickerContentLayout.ElementFrame) {
        frames = frame
        setFrame(frame.frame)
        layoutSubviews(frame.frame.width, frame.frame.height)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val frame = frames ?: return
        frame.icon?.let { back.setFrame(it) }
        frame.label?.let { title.setFrame(it) }
    }
}

/**
 * The rows of one picker: a few fixed at the top, the rest in a list that
 * scrolls past 176dp. Built by the screen for each picker and page.
 */
class NewSessionPickerContentView(context: Context) : ManualLayout(context) {
    sealed interface Row {
        val view: View

        class Option(override val view: NewSessionOptionRow) : Row
        class Field(override val view: NewSessionFieldRow) : Row
        class Text(override val view: Label) : Row
        class Divider(override val view: View) : Row
        class Empty(override val view: Label) : Row
        class Back(override val view: NewSessionBackRow) : Row

        val element: NewSessionPickerContentLayout.Element
            get() = when (this) {
                is Option -> view.element
                is Field -> view.element
                is Text -> NewSessionPickerContentLayout.Element.Text(view.measurable)
                is Divider -> NewSessionPickerContentLayout.Element.Divider
                is Empty -> NewSessionPickerContentLayout.Element.Empty(view.measurable)
                is Back -> view.element
            }
    }

    var header: List<Row> = emptyList()
        private set
    var list: List<Row> = emptyList()
        private set
    private val scroll = ScrollContainer(context).apply { overScrollMode = OVER_SCROLL_NEVER }
    var listMaxHeight: Double? = NewSessionPickerContentLayout.Metrics.listMaxHeight
    var paddingTop = 0.0
    var paddingBottom = NewSessionPickerContentLayout.Metrics.embeddedPaddingBottom

    init {
        addView(scroll)
    }

    /** Replaces the rows. Header views that are handed back keep their place, so a focused field stays focused. */
    fun setRows(header: List<Row>, list: List<Row>) {
        val keep = header.map { it.view }.toSet()
        for (row in this.header) if (row.view !in keep) removeView(row.view)
        for (row in this.list) scroll.content.removeView(row.view)
        this.header = header
        this.list = list
        for (row in header) if (row.view.parent !== this) addView(row.view)
        for (row in list) scroll.addContent(row.view)
        requestLayout()
    }

    fun layout(width: Double) = NewSessionPickerContentLayout(NewSessionPickerContentLayout.Input(
        width = width,
        header = header.map { it.element },
        list = list.map { it.element },
        listMaxHeight = listMaxHeight,
        paddingTop = paddingTop,
        paddingBottom = paddingBottom,
    ))

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        header.zip(layout.header).forEach { (row, frame) -> place(row, frame) }
        scroll.setFrame(layout.listFrame)
        val frames = LinkedHashMap<View, Rect?>()
        list.zip(layout.list).forEach { (row, frame) -> frames[row.view] = when (row) {
            is Row.Text, is Row.Empty -> frame.label?.offsetBy(frame.frame.minX, frame.frame.minY)
            else -> frame.frame
        } }
        scroll.place(Size(layout.listFrame.width, layout.listContentHeight), frames)
        list.zip(layout.list).forEach { (row, frame) -> place(row, frame, inList = true) }
    }

    private fun place(row: Row, frame: NewSessionPickerContentLayout.ElementFrame, inList: Boolean = false) {
        when (row) {
            is Row.Option -> row.view.apply(frame)
            is Row.Field -> row.view.apply(frame)
            is Row.Back -> row.view.apply(frame)
            is Row.Text, is Row.Empty -> if (!inList) frame.label?.let { row.view.setFrame(it.offsetBy(frame.frame.minX, frame.frame.minY)) }
            is Row.Divider -> if (!inList) row.view.setFrame(frame.frame)
        }
    }
}

/** A heading, a line of meta text or "no results" for a picker. */
fun newSessionLabel(context: Context, style: TextStyle, text: String, lines: Int = 1, centred: Boolean = false) =
    Label(context, style, Palette.textSecondary, lines, if (centred) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL).also { it.text = text }
