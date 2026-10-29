package com.slopus.happy.ui.home

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ComposerShadow
import com.slopus.happy.layout.HomeDockFade
import com.slopus.happy.layout.HomeDockLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.composerCapsuleFill
import com.slopus.happy.layout.composerNewSessionPrimaryFill
import com.slopus.happy.layout.composerNewSessionPrimaryIcon
import com.slopus.happy.layout.homeDockEntry
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame

/**
 * Home's resting dock (HomeDock.tsx with focus mode closed; happy-ios
 * `HomeDockView`): a capsule holding the prompt and a send button, over a
 * scrim that rises from the screen's foot. A tap on the capsule opens the
 * composer; the button sends what was typed there. It fills home and takes
 * touches only on the capsule; [HomeDockLayout] places everything.
 *
 * iOS frosts the capsule over a blur; Android draws it solid in the frosted
 * colour with the composer's rim and an elevation shadow.
 */
class HomeDockView(context: Context) : ManualLayout(context), ThemeAware {
    val scrim = HeaderScrimView(context, fromTop = false)
    val entryButton = EntryButton(context)
    val sendButton = HomeDockSendButton(context)
    private val shell = View(context)
    private val shellShape = GradientDrawable()
    private val entryLabel = Label(context, TextStyles.homeDockEntry, Palette.textSecondary, maxLines = 1).apply { followsTextSize = false }
    private var composerFrame = Rect.Zero
    private var fadeAnimator: ValueAnimator? = null

    /** The dock's safe-area bottom: the navigation bar it sits on. */
    var safeBottom: Double = 0.0
        set(value) { if (field != value) { field = value; requestLayout() } }

    /** What was typed in the composer; the placeholder while empty. */
    var prompt: String = ""
        set(value) { if (field != value) { field = value; applyPrompt() } }

    /** Drawn in the capsule just before send (Stem Cells' model control); the entry gives up its room. */
    var accessory: View? = null
        set(value) {
            if (field === value) return
            field?.let { removeView(it) }
            field = value
            value?.let { addView(it) }
            applyTheme()
            requestLayout()
        }

    /** The scrim shows only over a list that can scroll under it. */
    var showsScrim = false
        set(value) { field = value; scrim.visibility = if (value) VISIBLE else GONE }

    /** How present the capsule's surface is while the dock fades in ([HomeDockFade]). */
    private var presence = 1f
    /** How present the prompt, the send button and the scrim are. */
    private var contentPresence = 1f

    init {
        clipChildren = false
        scrim.visibility = GONE
        addView(scrim)
        shell.background = shellShape
        shell.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        shell.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(shell)
        entryLabel.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        entryButton.addView(entryLabel)
        addView(entryButton)
        addView(sendButton)
        applyPrompt()
        applyTheme()
    }

    override fun applyTheme() {
        val dark = Theme.isDark
        shellShape.setColor(Theme.color(Palette.composerCapsuleFill))
        shellShape.setStroke(1, Theme.color(Palette.glassBorder))
        shell.elevation = dp(if (dark) ComposerShadow.darkRadius / 2 else ComposerShadow.lightRadius / 2)
        // Elevation also orders drawing: what sits on the capsule rides at its height, casting nothing.
        for (view in listOfNotNull(entryButton, sendButton, accessory)) {
            view.outlineProvider = null
            view.elevation = shell.elevation
        }
        applyPresence()
    }

    private fun applyPrompt() {
        entryLabel.text = prompt.ifEmpty { context.getString(R.string.home_dock_placeholder) }
        entryLabel.color = if (prompt.isEmpty()) Palette.textSecondary else Palette.text
        entryButton.contentDescription = entryLabel.text
        requestLayout()
    }

    private fun applyPresence() {
        val dark = Theme.isDark
        val opacity = (if (dark) ComposerShadow.darkOpacity else ComposerShadow.lightOpacity) * presence
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // The platform's spot shadow is far lighter than a CALayer's at
            // the same opacity; a fuller black lands near iOS's.
            val alpha = (minOf(1.0, opacity * 4) * 255).toInt()
            shell.outlineSpotShadowColor = alpha shl 24
            shell.outlineAmbientShadowColor = alpha shl 24
        }
        shell.alpha = presence
        scrim.presence = contentPresence
        entryButton.alpha = contentPresence
        sendButton.presence = contentPresence
        accessory?.alpha = contentPresence
    }

    /** Runs [fade], or shows the dock in full at once when it is null. */
    fun fadeIn(fade: HomeDockFade?) {
        fadeAnimator?.cancel()
        if (fade == null) return finishFade()
        apply(fade.presence(0.0))
        fadeAnimator = ValueAnimator.ofFloat(0f, fade.duration.toFloat()).apply {
            duration = (fade.duration * 1000).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { apply(fade.presence((it.animatedValue as Float).toDouble())) }
            start()
        }
    }

    /** Ends a fade where it would have ended: in full. */
    fun finishFade() {
        fadeAnimator?.cancel()
        fadeAnimator = null
        apply(1.0 to 1.0)
    }

    private fun apply(presence: Pair<Double, Double>) {
        this.presence = presence.first.toFloat()
        contentPresence = presence.second.toFloat()
        applyPresence()
    }

    /** A dock that leaves the screen mid-fade is in full when it is back. */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (fadeAnimator != null) finishFade()
    }

    /** The capsule, in this view. */
    val fieldFrame: Rect get() = composerFrame

    override fun layoutSubviews(width: Double, height: Double) {
        val accessorySize = accessory?.let {
            if (it.visibility == GONE) Size.Zero else (it as? ManualLayout)?.sizeThatFits(width, height) ?: Size.Zero
        } ?: Size.Zero
        val layout = HomeDockLayout(
            HomeDockLayout.Input(
                size = Size(width, height),
                safeBottom = safeBottom,
                hairline = 1.0 / Theme.density,
                entry = entryLabel.measurable,
                accessory = accessorySize,
            ),
        )
        composerFrame = layout.composer
        scrim.setFrame(layout.scrim)
        shell.setFrame(layout.composer)
        shellShape.cornerRadius = dp(layout.composer.height / 2)
        entryButton.setFrame(layout.entry)
        entryLabel.setFrame(layout.entryText.offsetBy(-layout.entry.minX, -layout.entry.minY))
        sendButton.setFrame(layout.send)
        accessory?.setFrame(layout.accessory)
    }

    /** Only the capsule takes touches; the list scrolls everywhere else. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = ev.x / Theme.density
            val y = ev.y / Theme.density
            if (!composerFrame.contains(x.toDouble(), y.toDouble())) return false
        }
        return super.dispatchTouchEvent(ev)
    }

    /** The capsule's text: a tap opens the composer (`inputEntry`). */
    class EntryButton(context: Context) : ManualLayout(context) {
        init {
            isClickable = true
            isFocusable = true
            accessibilityDelegate = object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            }
        }

        override fun layoutSubviews(width: Double, height: Double) {}
    }
}

/**
 * The dock's round send button (`sendButton`): grey and dimmed with nothing
 * to send, filled once there is, a spinner while sending.
 */
class HomeDockSendButton(context: Context) : ManualLayout(context), ThemeAware {
    enum class Appearance { Idle, Send, Busy }

    var appearance = Appearance.Idle
        set(value) { if (field != value) { field = value; applyAppearance() } }

    /** The dock's fade, under the press's 0.7. */
    var presence = 1f
        set(value) { if (field != value) { field = value; applyAlpha() } }

    private val shape = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val iconView = IconView(context, Icon.ionicons("arrow-up"), HomeDockLayout.Metrics.sendIconSize)
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)

    init {
        background = shape
        addView(iconView)
        spinner.visibility = GONE
        addView(spinner)
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.home_dock_send)
        applyAppearance()
    }

    override fun applyTheme() = applyAppearance()

    private fun applyAppearance() {
        isEnabled = appearance == Appearance.Send
        shape.setColor(Theme.color(if (appearance == Appearance.Send) Palette.composerNewSessionPrimaryFill else Palette.surfaceHighest))
        iconView.tint = if (appearance == Appearance.Send) Palette.composerNewSessionPrimaryIcon else Palette.textSecondary
        spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(Palette.textSecondary))
        spinner.visibility = if (appearance == Appearance.Busy) VISIBLE else GONE
        iconView.visibility = if (appearance == Appearance.Busy) GONE else VISIBLE
        applyAlpha()
    }

    private fun applyAlpha() {
        alpha = (if (isPressed && isEnabled) 0.7f else 1f) * presence
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        applyAlpha()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        iconView.setFrame(Rect(0.0, 0.0, width, height))
        val s = 20.0
        spinner.setFrame(Rect((width - s) / 2, (height - s) / 2, s, s))
    }
}
