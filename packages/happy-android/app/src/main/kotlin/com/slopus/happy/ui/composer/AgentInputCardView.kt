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
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.PopupMenu
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
import com.slopus.happy.layout.composer.ComposerShake
import com.slopus.happy.layout.composer.MobileComposerMetrics
import com.slopus.happy.layout.composerCapsuleFill
import com.slopus.happy.layout.composerChip
import com.slopus.happy.layout.composerInput
import com.slopus.happy.layout.composerPrimaryFlash
import com.slopus.happy.layout.composerPrimaryInactive
import com.slopus.happy.layout.composerStopFill
import com.slopus.happy.layout.composerStopIcon
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

/**
 * The compact composer card (AgentInput.tsx `mobileUnifiedPanel`; happy-ios
 * `AgentInputCardView`): the growing text, then the row of add, the
 * permission, model and effort chips, and the round primary button, all
 * where [AgentInputCardLayout] puts them. iOS draws it on a frosted
 * material; Android has no backdrop blur behind a view, so the capsule is
 * solid ([composerCapsuleFill]) with the same rim and shadow. The chips open
 * the platform's popup menu where iOS opens a UIMenu.
 */
class AgentInputCardView(context: Context, private val metrics: MobileComposerMetrics = MobileComposerMetrics.mobile) :
    ManualLayout(context), ThemeAware {
    data class Configuration(
        val placeholder: String = "",
        val isEditable: Boolean = true,
        val showsAdd: Boolean = false,
        val permission: ComposerChipSet.Chip? = null,
        val model: ComposerChipSet.Chip? = null,
        val effort: ComposerChipSet.Chip? = null,
        val primaryAction: AgentInputPrimaryAction = AgentInputPrimaryAction.Idle,
        val isPrimaryBusy: Boolean = false,
        val primaryAccessibilityLabel: String? = null,
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
    var onAdd: (() -> Unit)? = null
    var onSelect: ((AgentInputChip, String) -> Unit)? = null
    var onFocusChanged: ((Boolean) -> Unit)? = null

    private val surface = View(context)
    private val surfaceShape = GradientDrawable()
    val textView = TextField(context, TextStyles.composerInput, multiline = true)
    private val addButton = ComposerIconButton(context, Icon.ionicons("add"), metrics.addIconSize)
    private val middle = Box(context)
    private val permissionChip = ChipButton(context, AgentInputChip.Permission)
    private val modelChip = ChipButton(context, AgentInputChip.Model)
    private val separator = Label(context, TextStyles.composerChip, Palette.textSecondary, maxLines = 1)
    private val effortChip = ChipButton(context, AgentInputChip.Effort)
    private val primaryButton = PrimaryButton(context)
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
        addView(textView)
        addButton.contentDescription = context.getString(com.slopus.happy.R.string.composer_add_photo)
        addButton.setOnClickListener { onAdd?.invoke() }
        addView(addButton)
        addView(middle)
        separator.text = "·"
        for (chip in listOf(permissionChip, modelChip, effortChip)) {
            chip.onSelect = { which, key -> onSelect?.invoke(which, key) }
            middle.addView(chip)
        }
        middle.addView(separator)
        primaryButton.setOnClickListener { onPrimary?.invoke() }
        addView(primaryButton)
        apply(null)
        applyTheme()
    }

    override fun applyTheme() {
        val dark = Theme.isDark
        surfaceShape.setColor(Theme.color(Palette.composerCapsuleFill))
        surfaceShape.setStroke(1, Theme.color(Palette.glassBorder))
        surfaceShape.cornerRadius = dp(metrics.shellRadius)
        surface.elevation = dp(if (dark) ComposerShadow.darkRadius / 2 else ComposerShadow.lightRadius / 2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // As the home dock's capsule: the platform's spot shadow is far lighter than a CALayer's.
            val opacity = if (dark) ComposerShadow.darkOpacity else ComposerShadow.lightOpacity
            val alpha = (minOf(1.0, opacity * 4) * 255).toInt()
            surface.outlineSpotShadowColor = alpha shl 24
            surface.outlineAmbientShadowColor = alpha shl 24
        }
        // What sits on the capsule rides at its height, casting nothing.
        for (view in listOf(textView, addButton, middle, primaryButton)) {
            view.outlineProvider = null
            view.elevation = surface.elevation
        }
        textView.highlightColor = Theme.color(Palette.radioActive) and 0x40FFFFFF
        if (Build.VERSION.SDK_INT >= 29) textView.textCursorDrawable?.setTintList(ColorStateList.valueOf(Theme.color(Palette.radioActive)))
        primaryButton.applyTheme()
        separator.applyTheme()
    }

    private fun apply(previous: Configuration?) {
        val config = configuration
        textView.hint = config.placeholder
        textView.isEnabled = config.isEditable
        textView.alpha = if (config.isEditable) 1f else AgentInputCardLayout.Metrics.readOnlyTextOpacity.toFloat()
        addButton.visibility = if (config.showsAdd) VISIBLE else GONE
        permissionChip.configure(config.permission)
        modelChip.configure(config.model)
        effortChip.configure(config.effort)
        separator.visibility = if (config.effort == null) GONE else VISIBLE
        primaryButton.configure(config.primaryAction, config.isPrimaryBusy, config.primaryAccessibilityLabel)
        if (previous == null || previous.permission != config.permission || previous.model != config.model ||
            previous.effort != config.effort || previous.showsAdd != config.showsAdd
        ) {
            requestLayout()
            reportHeight()
        }
    }

    fun setText(text: String) {
        if (text == this.text) return
        textView.setText(text)
        textView.setSelection(text.length)
    }

    fun focus() = textView.focus()

    fun blur() = textView.dismissKeyboard()

    /** A refused send: the card shakes (ComposerShake.shaker). */
    fun shake() {
        val shake = ComposerShake.steps(ComposerShake.shakerKeyframes(), 1.0)
        ObjectAnimator.ofFloat(this, TRANSLATION_X, *shake.values.map { dp(it) }.toFloatArray()).apply {
            duration = (shake.duration * 1000).toLong()
            start()
        }
    }

    // Layout

    private fun textContentHeight(width: Double): Double {
        val text = textView.string
        if (text.isEmpty()) return 0.0
        // A trailing newline starts a line StaticLayout already counts.
        val measured = TextLayouts.measure(text, TextStyles.composerInput, width, env).height
        return measured + metrics.inputPaddingTop + metrics.inputPaddingBottom
    }

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
            permission = config.permission?.let { AgentInputCardLayout.Chip(permissionChip.contentSize) },
            model = config.model?.let { AgentInputCardLayout.Chip(modelChip.contentSize) },
            modelIsMenu = config.model?.sections != null,
            effort = config.effort?.let { AgentInputCardLayout.Chip(effortChip.contentSize) },
            separator = if (config.effort == null) Size.Zero else separator.measure(Double.MAX_VALUE),
        )
    }

    /** The text view's width in a card [width] wide: the layout's, which does not depend on the text. */
    private fun cardTextWidth(width: Double): Double {
        val border = 1.0 / Theme.density
        val offset = (metrics.actionSize - metrics.addIconSize) / 2
        return maxOf(0.0, width - 2 * border - 2 * metrics.shellInset - 2 * offset)
    }

    fun height(width: Double): Double = layout(width).size.height

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
        val l = layout(width)
        lastHeight = l.size.height
        surface.setFrame(Rect(0.0, 0.0, width, l.size.height))
        textView.setFrame(l.text)
        textView.isVerticalScrollBarEnabled = l.textScrolls
        l.add?.let { addButton.setFrame(it) }
        middle.setFrame(l.middle)
        val content = l.middleContent
        permissionChip.place(content.permission)
        modelChip.place(content.model)
        effortChip.place(content.effort)
        content.separator?.let { separator.setFrame(it) }
        primaryButton.setFrame(l.primary)
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
        private val label = Label(context, TextStyles.composerChip, Palette.text, maxLines = 1)
        private val iconView = IconView(context, null, AgentInputCardLayout.Metrics.permissionIconSize, Palette.text)
        private var config: ComposerChipSet.Chip? = null
        private var labelFrame = Rect.Zero

        init {
            addView(label)
            addView(iconView)
            isClickable = true
            isFocusable = true
            setOnClickListener { openMenu() }
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
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val popup = PopupMenu(context, this)
            val keys = ArrayList<String>()
            sections.forEachIndexed { group, section ->
                section.title?.let { popup.menu.add(group, Menu.NONE, Menu.NONE, it).isEnabled = false }
                for (option in section.options) {
                    val item = popup.menu.add(group, keys.size, Menu.NONE, option.label)
                    item.isCheckable = true
                    item.isChecked = option.key == config?.selectedKey
                    item.isEnabled = !option.isDisabled
                    keys += option.key
                }
            }
            popup.setOnMenuItemClickListener { item ->
                keys.getOrNull(item.itemId)?.let { onSelect?.invoke(chip, it) }
                true
            }
            popup.show()
        }
    }

    /** The round send / stop / voice button (`mobilePrimaryButton`): 42dp. */
    private class PrimaryButton(context: Context) : ManualLayout(context) {
        private val shape = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        private val iconView = IconView(context, null, AgentInputCardLayout.Metrics.primaryIconSize)
        private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
        private var action = AgentInputPrimaryAction.Idle
        private var busy = false

        init {
            background = shape
            addView(iconView)
            spinner.visibility = GONE
            addView(spinner)
            isClickable = true
            isFocusable = true
        }

        fun configure(action: AgentInputPrimaryAction, busy: Boolean, accessibilityLabel: String?) {
            this.action = action
            this.busy = busy
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
                    iconView.icon = Icon.octicons("arrow-up")
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

        fun applyTheme() {
            shape.setStroke(0, 0)
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
            iconView.setFrame(Rect(0.0, 0.0, width, height))
            val s = 20.0
            spinner.setFrame(Rect((width - s) / 2, (height - s) / 2, s, s))
        }
    }
}
