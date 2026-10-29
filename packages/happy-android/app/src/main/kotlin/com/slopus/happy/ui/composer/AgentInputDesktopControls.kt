package com.slopus.happy.ui.composer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import com.slopus.happy.R
import com.slopus.happy.chat.AgentInputDesktopSend
import com.slopus.happy.chat.ComposerChipSet
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.buttonSecondaryTint
import com.slopus.happy.layout.composer.AgentInputDesktopLayout
import com.slopus.happy.layout.composerSendDisabled
import com.slopus.happy.layout.composerSendFill
import com.slopus.happy.layout.composerSendIcon
import com.slopus.happy.ui.components.ChoiceMenu
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame

// AgentInput.tsx in a window wider than 700 (happy-ios
// Composer/AgentInputDesktopControls.swift): the card is the flat
// `unifiedPanel`, and `desktopActionControls` stand in for its add button,
// chips and round button
//
//     [gear] [stop] [image] ···························· [send]
//
// The text view and the attachment strip stay the card's, so the same text,
// caret and composition carry across the 700 line. Only the chat's composer
// asks for it (AgentInputCardView.Configuration.desktop); HomeDock.tsx has no
// such branch. AgentInputDesktopLayout places it.

/** What the flat panel's action row shows. The image button is the card's `showsAdd`, blue while pictures are attached. */
data class AgentInputDesktopConfiguration(
    /** The gear's menu (`onPermissionModeChange`), a section per choice under its heading; null hides the gear. */
    val settings: List<ComposerChipSet.SettingsSection>? = null,
    /** Stop (`onAbort`): there whenever the agent can be stopped, working or not. */
    val showsStop: Boolean = false,
    /** A spinner instead of the stop glyph, and no presses (`isAborting`). */
    val isStopBusy: Boolean = false,
    val send: AgentInputDesktopSend,
)

/** The action row's controls, filling the card so their frames are the card's; it takes no touches itself. */
internal class AgentInputDesktopControlsView(context: Context) : ManualLayout(context) {
    var onSelect: ((AgentInputChip, String) -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onImage: (() -> Unit)? = null
    var onSend: (() -> Unit)? = null

    /** `actionButtonsLeft`: clips what doesn't fit. */
    private val left = Group(context)
    private val settingsButton = SettingsButton(context)
    private val stopButton = ActionButton(context, Icon.octicons("stop"))
    private val imageButton = ActionButton(context, Icon.ionicons("image-outline"))
    private val sendButton = SendButton(context)

    var configuration: AgentInputDesktopConfiguration? = null
        private set

    init {
        clipChildren = false
        left.clipChildren = true
        addView(left)
        settingsButton.contentDescription = context.getString(R.string.settings_title)
        settingsButton.onSelect = { chip, key -> onSelect?.invoke(chip, key) }
        left.addView(settingsButton)
        stopButton.contentDescription = context.getString(R.string.composer_stop)
        stopButton.setOnClickListener { onStop?.invoke() }
        left.addView(stopButton)
        imageButton.setOnClickListener { onImage?.invoke() }
        left.addView(imageButton)
        sendButton.contentDescription = context.getString(R.string.composer_send)
        sendButton.setOnClickListener { onSend?.invoke() }
        addView(sendButton)
    }

    /** What the stop button shows, for the layout: its glyph or spinner. */
    val stopContentWidth: Double
        get() = if (configuration?.isStopBusy == true) AgentInputDesktopLayout.Metrics.spinnerSize else stopButton.glyphWidth

    val imageContentWidth: Double get() = imageButton.glyphWidth

    fun configure(configuration: AgentInputDesktopConfiguration, showsImage: Boolean, hasAttachments: Boolean, imageAccessibilityLabel: String) {
        this.configuration = configuration
        settingsButton.visibility = if (configuration.settings == null) GONE else VISIBLE
        settingsButton.settings = configuration.settings
        stopButton.visibility = if (configuration.showsStop) VISIBLE else GONE
        stopButton.busy = configuration.isStopBusy
        imageButton.visibility = if (showsImage) VISIBLE else GONE
        // `selectedImages.length > 0`: radio.active, else the secondary tint.
        imageButton.tint = if (hasAttachments) Palette.radioActive else Palette.buttonSecondaryTint
        imageButton.contentDescription = imageAccessibilityLabel
        sendButton.send = configuration.send
    }

    private var placed: AgentInputDesktopLayout? = null

    fun place(layout: AgentInputDesktopLayout) {
        placed = layout
        requestLayout()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = placed ?: return
        val frame = layout.left
        left.visibility = if (frame == null) GONE else VISIBLE
        frame?.let { left.setFrame(it) }
        val content = layout.leftContent
        content.settings?.let { settingsButton.setFrame(it) }
        content.stop?.let { stopButton.setFrame(it) }
        content.image?.let { imageButton.setFrame(it) }
        sendButton.setFrame(layout.send)
    }

    fun applyTheme() {
        settingsButton.applyTheme()
        stopButton.applyTheme()
        imageButton.applyTheme()
        sendButton.applyTheme()
    }

    /** Only stop shakes on a stop that failed (`shakerRef`). */
    fun shakeStop() = AgentInputCardView.shake(stopButton)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Stop, image and send: `hitSlop={{ top: 5, bottom: 10 }}`, a touch just above or below one still lands on it.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = px(event.x)
            val y = px(event.y)
            val slop = AgentInputDesktopLayout.Metrics.actionHitSlop
            val near = listOf(stopButton, imageButton, sendButton).firstOrNull { button ->
                if (button.visibility != VISIBLE || button.parent is Group && left.visibility != VISIBLE) return@firstOrNull false
                val frame = frameInSelf(button)
                val slopped = Rect(frame.minX - slop.left, frame.minY - slop.top, frame.width + slop.left + slop.right, frame.height + slop.top + slop.bottom)
                slopped.contains(x, y) && !frame.contains(x, y)
            }
            if (near != null) {
                val frame = frameInSelf(near)
                event.setLocation(dp(frame.midX), dp(frame.midY))
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun frameInSelf(view: View): Rect {
        val offsetX = if (view.parent === left) left.left else 0
        val offsetY = if (view.parent === left) left.top else 0
        return Rect(px(view.left + offsetX), px(view.top + offsetY), px(view.width), px(view.height))
    }

    private class Group(context: Context) : ManualLayout(context) {
        override fun layoutSubviews(width: Double, height: Double) {}
    }

    /** A Pressable that only dims its content while pressed (`opacity: pressed ? 0.7 : 1`). */
    private abstract class Pressable(context: Context) : ManualLayout(context) {
        abstract val pressedContent: List<View>

        init {
            isClickable = true
            isFocusable = true
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            val alpha = if (pressed && isEnabled) AgentInputDesktopLayout.Metrics.pressedOpacity.toFloat() else 1f
            for (view in pressedContent) view.alpha = alpha
        }
    }

    /** Stop and image: a 16 glyph 8 in from either side, or a spinner. */
    private class ActionButton(context: Context, icon: Icon) : Pressable(context) {
        private val iconView = IconView(context, icon, AgentInputDesktopLayout.Metrics.actionIconSize, Palette.buttonSecondaryTint)
        private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
        override val pressedContent: List<View> get() = listOf(iconView, spinner)

        var tint: ColorToken = Palette.buttonSecondaryTint
            set(value) { field = value; applyTheme() }

        var busy = false
            set(value) {
                if (field == value) return
                field = value
                // `disabled={isAborting}`.
                isEnabled = !value
                iconView.visibility = if (value) GONE else VISIBLE
                spinner.visibility = if (value) VISIBLE else GONE
            }

        val glyphWidth: Double get() = iconView.intrinsicSize().width

        init {
            addView(iconView)
            spinner.visibility = GONE
            addView(spinner)
            applyTheme()
        }

        fun applyTheme() {
            iconView.tint = tint
            spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(tint))
        }

        override fun layoutSubviews(width: Double, height: Double) {
            iconView.setFrame(Rect(0.0, 0.0, width, height))
            val s = AgentInputDesktopLayout.Metrics.spinnerSize
            spinner.setFrame(Rect((width - s) / 2, (height - s) / 2, s, s))
        }
    }

    /**
     * The gear: Octicons `gear` 16 in a 40 square that opens the settings
     * menu, a section per choice under its heading, with no press state of
     * its own (iOS's menu over the glyph has none).
     */
    private class SettingsButton(context: Context) : ManualLayout(context) {
        var onSelect: ((AgentInputChip, String) -> Unit)? = null
        var settings: List<ComposerChipSet.SettingsSection>? = null
        private val iconView = IconView(context, Icon.octicons("gear"), AgentInputDesktopLayout.Metrics.actionIconSize, Palette.buttonSecondaryTint)

        init {
            addView(iconView)
            isClickable = true
            isFocusable = true
            setOnClickListener { openMenu() }
        }

        fun applyTheme() = iconView.invalidate()

        private fun openMenu() {
            val settings = settings ?: return
            if (settings.isEmpty()) return
            // Each row's key names its section, as one key can be in two (a model and an effort called the same).
            val sections = settings.mapIndexed { index, section ->
                ChoiceMenu.Section(
                    section.title,
                    section.options.map { ChoiceMenu.Option("$index:${it.key}", it.label, it.isDisabled) },
                    section.selectedKey?.let { "$index:$it" },
                )
            }
            ChoiceMenu.show(this, sections, null) { picked ->
                val index = picked.substringBefore(':').toIntOrNull() ?: return@show
                val section = settings.getOrNull(index) ?: return@show
                val chip = when (section.kind) {
                    ComposerChipSet.SettingsSection.Kind.Permission -> AgentInputChip.Permission
                    ComposerChipSet.SettingsSection.Kind.Model -> AgentInputChip.Model
                    ComposerChipSet.SettingsSection.Kind.Effort -> AgentInputChip.Effort
                }
                onSelect?.invoke(chip, picked.substringAfter(':'))
            }
        }

        override fun layoutSubviews(width: Double, height: Double) = iconView.setFrame(Rect(0.0, 0.0, width, height))
    }

    /** `sendButton`: 32 round, filled by the send's state; the press dims the glyph, not the fill. */
    private class SendButton(context: Context) : Pressable(context) {
        private val shape = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        private val iconView = IconView(context, Icon.octicons("arrow-up"), AgentInputDesktopLayout.Metrics.sendIconSize)
        private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
        override val pressedContent: List<View> get() = listOf(iconView, spinner)

        var send = AgentInputDesktopSend(hasText = false, hasComposerContent = false, isSendBlocked = false)
            set(value) {
                if (field == value) return
                field = value
                apply()
            }

        init {
            background = shape
            addView(iconView)
            spinner.visibility = GONE
            addView(spinner)
            apply()
        }

        private fun apply() {
            isEnabled = send.isEnabled
            when (send.glyph) {
                AgentInputDesktopSend.Glyph.Spinner -> {
                    iconView.visibility = GONE
                    spinner.visibility = VISIBLE
                }
                AgentInputDesktopSend.Glyph.Lock -> {
                    spinner.visibility = GONE
                    iconView.visibility = VISIBLE
                    iconView.icon = Icon.ionicons("lock-closed")
                    iconView.size = AgentInputDesktopLayout.Metrics.lockIconSize
                }
                AgentInputDesktopSend.Glyph.Arrow -> {
                    spinner.visibility = GONE
                    iconView.visibility = VISIBLE
                    iconView.icon = Icon.octicons("arrow-up")
                    iconView.size = AgentInputDesktopLayout.Metrics.sendIconSize
                }
            }
            applyTheme()
        }

        fun applyTheme() {
            shape.setStroke(0, 0)
            val fill = when (send.fill) {
                AgentInputDesktopSend.Fill.Locked -> {
                    shape.setStroke(dp(1), Theme.color(Palette.divider))
                    Palette.surfaceHigh
                }
                AgentInputDesktopSend.Fill.Active -> Palette.composerSendFill
                AgentInputDesktopSend.Fill.Inactive -> Palette.composerSendDisabled
            }
            shape.setColor(Theme.color(fill))
            iconView.tint = if (send.glyph == AgentInputDesktopSend.Glyph.Lock) Palette.textSecondary else Palette.composerSendIcon
            spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(Palette.composerSendIcon))
        }

        override fun layoutSubviews(width: Double, height: Double) {
            iconView.setFrame(Rect(0.0, 0.0, width, height))
            val s = AgentInputDesktopLayout.Metrics.spinnerSize
            spinner.setFrame(Rect((width - s) / 2, (height - s) / 2, s, s))
        }
    }
}
