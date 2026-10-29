package com.slopus.happy.ui.composer

import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ProgressBar
import com.slopus.happy.chat.AgentInputPrimaryAction
import com.slopus.happy.chat.ComposerChipSet
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.ComposerShadow
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.composer.AgentInputCardLayout
import com.slopus.happy.layout.composer.AgentInputDesktopLayout
import com.slopus.happy.layout.composer.AgentInputPrimaryFlash
import com.slopus.happy.layout.composer.ComposerShake
import com.slopus.happy.layout.composer.MobileComposerMetrics
import com.slopus.happy.layout.composerCapsuleFill
import com.slopus.happy.layout.composerChip
import com.slopus.happy.layout.composerNewSessionPrimaryFill
import com.slopus.happy.layout.composerNewSessionPrimaryIcon
import com.slopus.happy.layout.composerInput
import com.slopus.happy.layout.composerPrimaryFlash
import com.slopus.happy.layout.composerPrimaryInactive
import com.slopus.happy.layout.composerStopFill
import com.slopus.happy.layout.composerStopIcon
import com.slopus.happy.ui.components.ChoiceMenu
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.TextField
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame

/** Which of the card's chips. */
enum class AgentInputChip { Permission, Model, Effort }

/** A control in the action row that can refuse a press ([AgentInputCardView.Configuration.refusesControls]). */
enum class AgentInputControl { Add, Permission, Model, Effort }

/** How the round button is painted. */
enum class AgentInputPrimaryStyle {
    /** AgentInput.tsx's chat composer: send on `surfaceHighest`, stop black (light) or #F5F5F5 (dark), idle grey. */
    Standard,

    /**
     * HomeDock.tsx's focused composer: send and stop share the solid fill
     * with the contrasting icon; idle is `surfaceHighest` with a
     * `textSecondary` arrow (Ionicons); busy is a spinner on the unfilled button.
     */
    NewSession,
}

/**
 * The compact composer card (AgentInput.tsx `mobileUnifiedPanel`; happy-ios
 * `AgentInputCardView`): the growing text, then the row of add, the
 * permission, model and effort chips, and the round primary button, all
 * where [AgentInputCardLayout] puts them. iOS draws it on a frosted
 * material; Android has no backdrop blur behind a view, so the capsule is
 * solid ([composerCapsuleFill]) with the same rim and shadow. The chips open
 * the platform's popup menu where iOS opens a UIMenu.
 *
 * In a window wider than 700 the chat's card is the flat panel instead
 * ([Configuration.desktop], [AgentInputDesktopControlsView]), keeping the
 * text view.
 */
class AgentInputCardView(context: Context, private val metrics: MobileComposerMetrics = MobileComposerMetrics.mobile) :
    ManualLayout(context), ThemeAware {
    data class Configuration(
        val placeholder: String = "",
        val isEditable: Boolean = true,
        val showsAdd: Boolean = false,
        /** The pictures waiting to go, in the strip over the text. */
        val attachments: List<AgentInputAttachmentPreview> = emptyList(),
        val permission: ComposerChipSet.Chip? = null,
        val model: ComposerChipSet.Chip? = null,
        val effort: ComposerChipSet.Chip? = null,
        val primaryAction: AgentInputPrimaryAction = AgentInputPrimaryAction.Idle,
        val isPrimaryBusy: Boolean = false,
        val primaryAccessibilityLabel: String? = null,
        val primaryStyle: AgentInputPrimaryStyle = AgentInputPrimaryStyle.Standard,
        /** A session is starting: add and the chips shake instead of acting (only Home's dock refuses). */
        val refusesControls: Boolean = false,
        /** A bot's name: words capitalised, Return makes it. */
        val capitalizesWords: Boolean = false,
        /**
         * AgentInput.tsx `zenMode` (the tablet shell's zen control): the add
         * button and the chips go; the text, any picked pictures and the
         * round button stay.
         */
        val zenMode: Boolean = false,
        /**
         * The flat panel and its `desktopActionControls`, AgentInput.tsx in a
         * window wider than 700; null is the compact card. The chips and the
         * primary action are then unused, [showsAdd] is the image button, and
         * [refusesControls] is not consulted (only Home's dock refuses, and it
         * has no panel).
         */
        val desktop: AgentInputDesktopConfiguration? = null,
    )

    var configuration = Configuration()
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            apply(previous)
        }

    /** The text changed (typed, pasted or set). */
    var onTextChanged: (() -> Unit)? = null
    /** The card's height may have changed. */
    var onHeightChanged: (() -> Unit)? = null
    var onPrimary: (() -> Unit)? = null
    /** The flat panel's stop and send ([Configuration.desktop]). */
    var onStop: (() -> Unit)? = null
    var onSend: (() -> Unit)? = null
    var onAdd: (() -> Unit)? = null
    var onRemoveAttachment: ((String) -> Unit)? = null
    /** Pictures pasted or inserted by the keyboard into the text, while [Configuration.showsAdd]. */
    var onReceiveImages: ((List<android.net.Uri>) -> Unit)? = null
    var onSelect: ((AgentInputChip, String) -> Unit)? = null
    var onFocusChanged: ((Boolean) -> Unit)? = null
    /** A control was pressed while [Configuration.refusesControls] is on; it has shaken. */
    var onRefuse: ((AgentInputControl) -> Unit)? = null
    /** Return was pressed in a card that capitalises words (a bot's name): true when it was taken. */
    var onReturn: (() -> Boolean)? = null

    /**
     * Whether the card draws its own capsule. Home's dock draws it on the
     * shell that grows around the card, and turns this off.
     */
    var showsSurface = true
        set(value) { field = value; surface.visibility = if (value) VISIBLE else GONE }

    private val surface = View(context)
    private val surfaceShape = GradientDrawable()
    val textView = TextField(context, TextStyles.composerInput, multiline = true)
    private val addButton = ComposerIconButton(context, Icon.ionicons("add"), metrics.addIconSize)
    private val attachmentStrip = AttachmentStripView(context)
    private val middle = Box(context)
    private val permissionChip = ChipButton(context, AgentInputChip.Permission)
    private val modelChip = ChipButton(context, AgentInputChip.Model)
    private val separator = Label(context, TextStyles.composerChip, Palette.textSecondary, maxLines = 1)
    private val effortChip = ChipButton(context, AgentInputChip.Effort)
    private val primaryButton = PrimaryButton(context)
    private var installedDesktopControls: AgentInputDesktopControlsView? = null
    /** A phone's compact card never builds the desktop row; kept after the first wide layout so resizing keeps it. */
    private val desktopControls: AgentInputDesktopControlsView
        get() = installedDesktopControls ?: AgentInputDesktopControlsView(context).also { controls ->
            controls.onSelect = { chip, key -> onSelect?.invoke(chip, key) }
            controls.onStop = { onStop?.invoke() }
            controls.onImage = { onAdd?.invoke() }
            controls.onSend = { onSend?.invoke() }
            installedDesktopControls = controls
            addView(controls)
        }
    private var lastHeight = -1.0

    val text: String get() = textView.string

    val hasContent: Boolean get() = text.isNotBlank()

    val isTextFocused: Boolean get() = textView.hasFocus()

    private val env get() = TextLayouts.Env(Theme.density, Theme.textScale)

    init {
        clipChildren = false
        surface.background = surfaceShape
        surface.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(metrics.shellRadius))
            }
        }
        surface.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(surface)
        textView.setPadding(0, dp(metrics.inputPaddingTop.toInt()), 0, dp(metrics.inputPaddingBottom.toInt()))
        textView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        textView.isVerticalScrollBarEnabled = false
        textView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onTextChanged?.invoke()
                reportHeight()
            }
        })
        textView.setOnFocusChangeListener { _, focused -> onFocusChanged?.invoke(focused) }
        // A pasted or keyboard-inserted picture is attached, as iOS's paste does; text goes on as text.
        androidx.core.view.ViewCompat.setOnReceiveContentListener(textView, arrayOf("image/*")) { _, payload ->
            if (!configuration.showsAdd || onReceiveImages == null) return@setOnReceiveContentListener payload
            val split = payload.partition { it.uri != null }
            val clip = split.first?.clip
            if (clip != null) onReceiveImages?.invoke((0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri })
            split.second
        }
        addView(textView)
        attachmentStrip.onRemove = { id -> onRemoveAttachment?.invoke(id) }
        attachmentStrip.visibility = GONE
        addView(attachmentStrip)
        addButton.contentDescription = context.getString(com.slopus.happy.R.string.composer_add_photo)
        addButton.setOnClickListener {
            if (configuration.refusesControls) refuse(addButton, AgentInputControl.Add) else onAdd?.invoke()
        }
        addView(addButton)
        addView(middle)
        separator.text = "·"
        for (chip in listOf(permissionChip, modelChip, effortChip)) {
            chip.onSelect = { which, key -> onSelect?.invoke(which, key) }
            chip.refuses = {
                if (configuration.refusesControls) {
                    refuse(chip, AgentInputControl.valueOf(chip.chip.name))
                    true
                } else {
                    false
                }
            }
            middle.addView(chip)
        }
        textView.setOnEditorActionListener { _, _, _ -> onReturn?.invoke() ?: false }
        middle.addView(separator)
        primaryButton.setOnClickListener { onPrimary?.invoke() }
        addView(primaryButton)
        apply(null)
        applyTheme()
    }

    override fun applyTheme() {
        val dark = Theme.isDark
        // The flat panel (`unifiedPanel`) has no shadow and no rim; its fill is input.background.
        val isDesktop = configuration.desktop != null
        surfaceShape.setColor(Theme.color(if (isDesktop) Palette.inputBackground else Palette.composerCapsuleFill))
        surfaceShape.setStroke(if (isDesktop) 0 else 1, Theme.color(Palette.glassBorder))
        surfaceShape.cornerRadius = dp(if (isDesktop) AgentInputDesktopLayout.Metrics.panelRadius else metrics.shellRadius)
        surface.elevation = if (isDesktop) 0f else dp(if (dark) ComposerShadow.darkRadius / 2 else ComposerShadow.lightRadius / 2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // As the home dock's capsule: the platform's spot shadow is far lighter than a CALayer's.
            val opacity = if (dark) ComposerShadow.darkOpacity else ComposerShadow.lightOpacity
            val alpha = (minOf(1.0, opacity * 4) * 255).toInt()
            surface.outlineSpotShadowColor = alpha shl 24
            surface.outlineAmbientShadowColor = alpha shl 24
        }
        // What sits on the capsule rides at its height, casting nothing.
        for (view in listOf(textView, attachmentStrip, addButton, middle, primaryButton)) {
            view.outlineProvider = null
            view.elevation = surface.elevation
        }
        textView.highlightColor = Theme.color(Palette.radioActive) and 0x40FFFFFF
        if (Build.VERSION.SDK_INT >= 29) textView.textCursorDrawable?.setTintList(ColorStateList.valueOf(Theme.color(Palette.radioActive)))
        primaryButton.applyTheme()
        separator.applyTheme()
        attachmentStrip.applyTheme()
        installedDesktopControls?.applyTheme()
    }

    private fun apply(previous: Configuration?) {
        val config = configuration
        textView.hint = config.placeholder
        textView.isEnabled = config.isEditable
        textView.alpha = if (config.isEditable) 1f else AgentInputCardLayout.Metrics.readOnlyTextOpacity.toFloat()
        val isDesktop = config.desktop != null
        if (previous == null || isDesktop != (previous.desktop != null)) applyPanel()
        addButton.visibility = if (config.showsAdd && !config.zenMode && !isDesktop) VISIBLE else GONE
        // The glyph turns blue while pictures are attached.
        addButton.iconView.tint = if (config.attachments.isEmpty()) Palette.text else Palette.radioActive
        attachmentStrip.attachments = config.attachments
        permissionChip.configure(if (config.zenMode) null else config.permission)
        modelChip.configure(if (config.zenMode) null else config.model)
        effortChip.configure(if (config.zenMode) null else config.effort)
        separator.visibility = if (config.zenMode || config.effort == null) GONE else VISIBLE
        primaryButton.configure(config.primaryAction, config.isPrimaryBusy, config.primaryAccessibilityLabel, config.primaryStyle)
        config.desktop?.let { desktop ->
            desktopControls.configure(
                desktop,
                showsImage = config.showsAdd,
                hasAttachments = config.attachments.isNotEmpty(),
                imageAccessibilityLabel = context.getString(com.slopus.happy.R.string.composer_add_photo),
            )
        }
        if (previous == null || previous.capitalizesWords != config.capitalizesWords) {
            // A bot's name is one line that Return makes; a prompt takes newlines.
            textView.inputType = if (config.capitalizesWords) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            }
            textView.imeOptions = if (config.capitalizesWords) android.view.inputmethod.EditorInfo.IME_ACTION_DONE else 0
        }
        if (previous == null || previous.permission != config.permission || previous.model != config.model ||
            previous.effort != config.effort || previous.showsAdd != config.showsAdd ||
            previous.attachments.size != config.attachments.size || previous.zenMode != config.zenMode ||
            panelShape(previous.desktop) != panelShape(config.desktop)
        ) {
            requestLayout()
            reportHeight()
        }
    }

    /** What of the flat panel moves its frames: whether it is there, the gear, stop and stop's spinner. */
    private fun panelShape(desktop: AgentInputDesktopConfiguration?): List<Boolean>? =
        desktop?.let { listOf(it.settings != null, it.showsStop, it.isStopBusy) }

    /** The compact card's capsule and its controls, or the flat panel's fill and its row. The text view stays, with its padding. */
    private fun applyPanel() {
        val isDesktop = configuration.desktop != null
        middle.visibility = if (isDesktop) GONE else VISIBLE
        primaryButton.visibility = if (isDesktop) GONE else VISIBLE
        installedDesktopControls?.visibility = if (isDesktop) VISIBLE else GONE
        val (top, bottom) = if (isDesktop) {
            AgentInputDesktopLayout.Metrics.textPaddingTop to AgentInputDesktopLayout.Metrics.textPaddingBottom
        } else {
            metrics.inputPaddingTop to metrics.inputPaddingBottom
        }
        textView.setPadding(0, dp(top.toInt()), 0, dp(bottom.toInt()))
        applyTheme()
    }

    /** The flat panel's stop failed: only it shakes (`shakerRef`). */
    fun shakeStop() = installedDesktopControls?.shakeStop()

    fun setText(text: String) {
        if (text == this.text) return
        textView.setText(text)
        textView.setSelection(text.length)
    }

    fun focus() = textView.focus()

    fun blur() = textView.dismissKeyboard()

    /** A refused send: the card shakes (ComposerShake.shaker). */
    fun shake() = shake(this)

    /**
     * HomeDock.tsx's refusal flash, driven by home (0…1): the round button
     * swells by a tenth and, on Stop, a lighter wash rises under the glyph.
     */
    fun setPrimaryFlash(amount: Double) {
        val scale = AgentInputPrimaryFlash.scale(amount).toFloat()
        primaryButton.scaleX = scale
        primaryButton.scaleY = scale
        primaryButton.flash = AgentInputPrimaryFlash.overlayAlpha(amount).toFloat()
    }

    /**
     * HomeDock.tsx's open animation: [input] (alpha, offsetY) moves the
     * text, [actions] the action row. Transform and alpha only
     * ([com.slopus.happy.layout.composer.AgentInputCardReveal] has the ranges).
     */
    fun setReveal(input: Pair<Double, Double>, actions: Pair<Double, Double>) {
        textView.alpha = input.first.toFloat() * if (configuration.isEditable) 1f else AgentInputCardLayout.Metrics.readOnlyTextOpacity.toFloat()
        textView.translationY = dp(input.second)
        attachmentStrip.alpha = input.first.toFloat()
        attachmentStrip.translationY = dp(input.second)
        for (view in listOf(addButton, middle, primaryButton)) {
            view.alpha = actions.first.toFloat()
            view.translationY = dp(actions.second)
        }
    }

    private fun refuse(view: View, control: AgentInputControl) {
        shake(view, ComposerShake.refusal)
        onRefuse?.invoke(control)
    }

    // Layout

    private fun textContentHeight(width: Double, desktop: Boolean = false): Double {
        val text = textView.string
        if (text.isEmpty()) return 0.0
        // A trailing newline starts a line StaticLayout already counts.
        val measured = TextLayouts.measure(text, TextStyles.composerInput, width, env).height
        return measured + if (desktop) {
            AgentInputDesktopLayout.Metrics.textPaddingTop + AgentInputDesktopLayout.Metrics.textPaddingBottom
        } else {
            metrics.inputPaddingTop + metrics.inputPaddingBottom
        }
    }

    /** The flat panel's input at a panel [width] wide; null for the compact card. */
    fun desktopLayoutInput(width: Double): AgentInputDesktopLayout.Input? {
        val config = configuration
        val desktop = config.desktop ?: return null
        return AgentInputDesktopLayout.Input(
            width = width,
            attachmentCount = config.attachments.size,
            textContentHeight = textContentHeight(AgentInputDesktopLayout.textWidth(width), desktop = true),
            hasText = textView.string.isNotEmpty(),
            lineHeight = TextLayouts.lineHeight(TextStyles.composerInput, env),
            showsSettings = desktop.settings != null,
            stopContentWidth = if (desktop.showsStop) desktopControls.stopContentWidth else null,
            imageContentWidth = if (config.showsAdd) desktopControls.imageContentWidth else null,
            zenMode = config.zenMode,
        )
    }

    fun desktopLayout(width: Double): AgentInputDesktopLayout? = desktopLayoutInput(width)?.let { AgentInputDesktopLayout(it) }

    fun layout(width: Double): AgentInputCardLayout = AgentInputCardLayout(layoutInput(width))

    fun layoutInput(width: Double): AgentInputCardLayout.Input {
        val config = configuration
        val textWidth = cardTextWidth(width)
        return AgentInputCardLayout.Input(
            width = width,
            metrics = metrics,
            borderWidth = 1.0 / Theme.density,
            textContentHeight = textContentHeight(textWidth),
            hasText = textView.string.isNotEmpty(),
            lineHeight = TextLayouts.lineHeight(TextStyles.composerInput, env),
            showsAdd = config.showsAdd,
            attachmentCount = config.attachments.size,
            permission = config.permission?.let { AgentInputCardLayout.Chip(permissionChip.contentSize) },
            model = config.model?.let { AgentInputCardLayout.Chip(modelChip.contentSize) },
            modelIsMenu = config.model?.sections != null,
            effort = config.effort?.let { AgentInputCardLayout.Chip(effortChip.contentSize) },
            separator = if (config.effort == null) Size.Zero else separator.measure(Double.MAX_VALUE),
            zenMode = config.zenMode,
        )
    }

    /** The text view's width in a card [width] wide: the layout's, which does not depend on the text. */
    private fun cardTextWidth(width: Double): Double {
        val border = 1.0 / Theme.density
        val offset = (metrics.actionSize - metrics.addIconSize) / 2
        return maxOf(0.0, width - 2 * border - 2 * metrics.shellInset - 2 * offset)
    }

    fun height(width: Double): Double = desktopLayout(width)?.size?.height ?: layout(width).size.height

    override fun sizeThatFits(width: Double, height: Double): Size = Size(width, height(width))

    private fun reportHeight() {
        if (width <= 0) return
        val h = height(com.slopus.happy.ui.view.px(width))
        if (h != lastHeight) {
            lastHeight = h
            onHeightChanged?.invoke()
        }
    }

    override fun layoutSubviews(width: Double, height: Double) {
        desktopLayout(width)?.let { return layoutDesktop(it, width) }
        val l = layout(width)
        lastHeight = l.size.height
        surface.setFrame(Rect(0.0, 0.0, width, l.size.height))
        textView.setFrame(l.text)
        textView.isVerticalScrollBarEnabled = l.textScrolls
        val strip = l.attachments
        attachmentStrip.visibility = if (strip == null) GONE else VISIBLE
        if (strip != null) {
            attachmentStrip.insets = l.attachmentInsets
            attachmentStrip.setFrame(strip)
        }
        l.add?.let { addButton.setFrame(it) }
        middle.setFrame(l.middle)
        val content = l.middleContent
        permissionChip.place(content.permission)
        modelChip.place(content.model)
        effortChip.place(content.effort)
        content.separator?.let { separator.setFrame(it) }
        primaryButton.setFrame(l.primary)
    }

    /** The flat panel: the strip and text where [AgentInputDesktopLayout] puts them, and its own action row. */
    private fun layoutDesktop(l: AgentInputDesktopLayout, width: Double) {
        lastHeight = l.size.height
        surface.setFrame(Rect(0.0, 0.0, width, l.size.height))
        textView.setFrame(l.text)
        textView.isVerticalScrollBarEnabled = l.textScrolls
        val strip = l.attachments
        attachmentStrip.visibility = if (strip == null) GONE else VISIBLE
        if (strip != null) {
            attachmentStrip.insets = AgentInputCardLayout.AttachmentInsets.panel
            attachmentStrip.setFrame(strip)
        }
        desktopControls.setFrame(Rect(0.0, 0.0, width, l.size.height))
        desktopControls.place(l)
    }

    /** A plain box whose children are clipped to it (the middle of the action row). */
    private class Box(context: Context) : ManualLayout(context) {
        override fun layoutSubviews(width: Double, height: Double) {}
    }

    /** A round icon button that dims when pressed (`ComposerIconButton`). */
    private class ComposerIconButton(context: Context, icon: Icon, size: Double) : ManualLayout(context) {
        val iconView = IconView(context, icon, size, Palette.text)

        init {
            addView(iconView)
            isClickable = true
            isFocusable = true
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            iconView.alpha = if (pressed) 0.5f else 1f
        }

        override fun layoutSubviews(width: Double, height: Double) = iconView.setFrame(Rect(0.0, 0.0, width, height))
    }

    /** A chip: its label (or the shield) where the layout says, opening its menu on tap. */
    private class ChipButton(context: Context, val chip: AgentInputChip) : ManualLayout(context) {
        var onSelect: ((AgentInputChip, String) -> Unit)? = null
        /** Asked first on a tap: true when the press was refused instead. */
        var refuses: () -> Boolean = { false }
        private val label = Label(context, TextStyles.composerChip, Palette.text, maxLines = 1)
        private val iconView = IconView(context, null, AgentInputCardLayout.Metrics.permissionIconSize, Palette.text)
        private var config: ComposerChipSet.Chip? = null
        private var labelFrame = Rect.Zero

        init {
            addView(label)
            addView(iconView)
            isClickable = true
            isFocusable = true
            setOnClickListener { if (!refuses()) openMenu() }
        }

        val contentSize: Size
            get() = when {
                config == null -> Size.Zero
                config?.label == null -> iconView.intrinsicSize()
                else -> label.measure(Double.MAX_VALUE)
            }

        fun configure(config: ComposerChipSet.Chip?) {
            if (config == this.config) return
            this.config = config
            visibility = if (config == null) GONE else VISIBLE
            if (config == null) return
            if (config.label != null) {
                label.text = config.label
                label.visibility = VISIBLE
                iconView.visibility = GONE
            } else {
                iconView.icon = Icon.ionicons("shield-outline")
                iconView.visibility = VISIBLE
                label.visibility = GONE
            }
            contentDescription = config.accessibilityLabel
            alpha = if (config.sections != null) 1f else AgentInputCardLayout.Metrics.disabledChipOpacity.toFloat()
            isEnabled = config.sections != null
        }

        fun place(frame: AgentInputCardLayout.ChipFrame?) {
            frame ?: return
            labelFrame = frame.label
            setFrame(frame.frame)
        }

        override fun layoutSubviews(width: Double, height: Double) {
            label.setFrame(labelFrame)
            iconView.setFrame(labelFrame)
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            label.alpha = if (pressed) 0.7f else 1f
            iconView.alpha = label.alpha
        }

        /** The chip's menu: one group per section, its title a disabled row, the current pick checked. */
        private fun openMenu() {
            val sections = config?.sections ?: return
            val menu = sections.map { section ->
                ChoiceMenu.Section(section.title, section.options.map { ChoiceMenu.Option(it.key, it.label, it.isDisabled) })
            }
            ChoiceMenu.show(this, menu, config?.selectedKey) { onSelect?.invoke(chip, it) }
        }
    }

    /** The round send / stop / voice button (`mobilePrimaryButton`): 42dp. */
    private class PrimaryButton(context: Context) : ManualLayout(context) {
        private val shape = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        private val iconView = IconView(context, null, AgentInputCardLayout.Metrics.primaryIconSize)
        private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
        /** The refusal flash's wash, over the fill and under the glyph. */
        private val flashOverlay = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
        private var action = AgentInputPrimaryAction.Idle
        private var busy = false
        private var style = AgentInputPrimaryStyle.Standard

        /** The wash's alpha ([AgentInputPrimaryFlash.overlayAlpha]). */
        var flash = 0f
            set(value) { field = value; applyFlash() }

        init {
            background = shape
            flashOverlay.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(flashOverlay)
            addView(iconView)
            spinner.visibility = GONE
            addView(spinner)
            isClickable = true
            isFocusable = true
        }

        fun configure(action: AgentInputPrimaryAction, busy: Boolean, accessibilityLabel: String?, style: AgentInputPrimaryStyle) {
            this.action = action
            this.busy = busy
            this.style = style
            when (action) {
                AgentInputPrimaryAction.Stop -> {
                    iconView.icon = Icon.octicons("stop")
                    iconView.size = AgentInputCardLayout.Metrics.primaryIconSize
                }
                AgentInputPrimaryAction.Blocked -> {
                    iconView.icon = Icon.ionicons("lock-closed")
                    iconView.size = AgentInputCardLayout.Metrics.lockIconSize
                }
                AgentInputPrimaryAction.Voice -> {
                    iconView.icon = Icon.ionicons("mic-outline")
                    iconView.size = AgentInputCardLayout.Metrics.primaryIconSize
                }
                AgentInputPrimaryAction.Send, AgentInputPrimaryAction.Idle -> {
                    // HomeDock draws Ionicons' arrow; AgentInput Octicons'.
                    iconView.icon = if (style == AgentInputPrimaryStyle.NewSession) Icon.ionicons("arrow-up") else Icon.octicons("arrow-up")
                    iconView.size = AgentInputCardLayout.Metrics.primaryIconSize
                }
            }
            // `disabled={!canPressSendButton}`: idle can't be pressed, nor can anything while a stop is in flight.
            isEnabled = !busy && action != AgentInputPrimaryAction.Idle
            spinner.visibility = if (busy) VISIBLE else GONE
            iconView.visibility = if (busy) GONE else VISIBLE
            contentDescription = accessibilityLabel ?: context.getString(
                if (action == AgentInputPrimaryAction.Stop) com.slopus.happy.R.string.composer_stop else com.slopus.happy.R.string.composer_send,
            )
            applyTheme()
        }

        private fun applyFlash() {
            flashOverlay.visibility = if (action != AgentInputPrimaryAction.Stop || (busy && style == AgentInputPrimaryStyle.NewSession)) GONE else VISIBLE
            flashOverlay.alpha = flash
        }

        fun applyTheme() {
            shape.setStroke(0, 0)
            (flashOverlay.background as GradientDrawable).setColor(Theme.color(Palette.composerPrimaryFlash))
            applyFlash()
            if (style == AgentInputPrimaryStyle.NewSession) {
                // HomeDock's 'busy' is its own action, on the unfilled button.
                val filled = !busy && (action == AgentInputPrimaryAction.Send || action == AgentInputPrimaryAction.Stop)
                shape.setColor(Theme.color(if (filled) Palette.composerNewSessionPrimaryFill else Palette.surfaceHighest))
                iconView.tint = if (filled) Palette.composerNewSessionPrimaryIcon else Palette.textSecondary
                spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(Palette.textSecondary))
                return
            }
            val (fill, tint) = when (action) {
                AgentInputPrimaryAction.Stop -> Palette.composerStopFill to Palette.composerStopIcon
                AgentInputPrimaryAction.Blocked -> {
                    shape.setStroke(dp(1), Theme.color(Palette.divider))
                    Palette.surfaceHigh to Palette.textSecondary
                }
                AgentInputPrimaryAction.Send, AgentInputPrimaryAction.Voice -> Palette.surfaceHighest to Palette.text
                AgentInputPrimaryAction.Idle -> Palette.composerPrimaryInactive to Palette.textSecondary
            }
            shape.setColor(Theme.color(fill))
            iconView.tint = tint
            val spinnerTint: ColorToken = if (action == AgentInputPrimaryAction.Stop && Theme.isDark) Palette.composerPrimaryFlash else Palette.text
            spinner.indeterminateTintList = ColorStateList.valueOf(
                if (action == AgentInputPrimaryAction.Stop && Theme.isDark) 0xFF000000.toInt() else Theme.color(spinnerTint),
            )
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            iconView.alpha = if (pressed) 0.5f else 1f
        }

        override fun layoutSubviews(width: Double, height: Double) {
            flashOverlay.setFrame(Rect(0.0, 0.0, width, height))
            iconView.setFrame(Rect(0.0, 0.0, width, height))
            val s = 20.0
            spinner.setFrame(Rect((width - s) / 2, (height - s) / 2, s, s))
        }
    }

    companion object {
        /** Shaker.tsx: [view] slides through [shake]'s keyframes, ease in-out per step, added to where it is. */
        fun shake(view: View, shake: ComposerShake = ComposerShake.shaker) {
            val base = view.translationX
            ObjectAnimator.ofFloat(view, TRANSLATION_X, *shake.values.map { base + dp(it) }.toFloatArray()).apply {
                duration = (shake.duration * 1000).toLong()
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
        }
    }
}
