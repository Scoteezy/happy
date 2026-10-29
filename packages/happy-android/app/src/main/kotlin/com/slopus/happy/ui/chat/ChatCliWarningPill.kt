package com.slopus.happy.ui.chat

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.chat.CliWarningPillLayout
import com.slopus.happy.layout.cliWarningFill
import com.slopus.happy.layout.cliWarningIcon
import com.slopus.happy.layout.cliWarningText
import com.slopus.happy.model.CliVersionWarning
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame

/**
 * SessionView's CLI-outdated pill (happy-ios `ChatCliWarningPill`): under
 * the header, a warning glyph, "CLI Update Required" and a close glyph on
 * the yellow fill with a soft shadow. A tap dismisses it for this version
 * on this machine. [CliWarningPillLayout] places it, from the screen.
 */
class ChatCliWarningPill(context: Context) : ManualLayout(context), ThemeAware {
    var warning: CliVersionWarning? = null

    private val shape = GradientDrawable()
    private val icon = IconView(context, Icon.ionicons("warning-outline"), CliWarningPillLayout.Metrics.iconSize, Palette.cliWarningIcon)
    private val label = Label(context, ChatTextStyles.cliWarning, Palette.cliWarningText, maxLines = 1)
    private val close = IconView(context, Icon.ionicons("close"), CliWarningPillLayout.Metrics.iconSize, Palette.cliWarningText)

    /** The label as the screen measures it for the pill. */
    val measurable: Measurable get() = label.measurable

    init {
        background = shape
        // iOS's shadow: black at 0.15, 4 radius, 2 down; the platform's elevation shadow is its stand-in.
        elevation = dp(CliWarningPillLayout.Metrics.shadowOffsetY)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                outline.alpha = 1f
            }
        }
        label.text = context.getString(R.string.session_info_cli_version_outdated)
        for (view in listOf(icon, label, close)) {
            view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(view)
        }
        contentDescription = label.text
        isClickable = true
        isFocusable = true
        visibility = GONE
        applyTheme()
    }

    override fun applyTheme() {
        shape.setColor(Theme.color(Palette.cliWarningFill))
        icon.applyTheme()
        close.applyTheme()
    }

    /** Places the pill and its insides, from the screen's layout. */
    fun place(layout: CliWarningPillLayout) {
        setFrame(layout.pill)
        shape.cornerRadius = dp(layout.pill.height / 2)
        val origin = layout.pill
        icon.setFrame(layout.icon.offsetBy(-origin.minX, -origin.minY))
        label.setFrame(layout.label.offsetBy(-origin.minX, -origin.minY))
        close.setFrame(layout.close.offsetBy(-origin.minX, -origin.minY))
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alpha = if (pressed) 0.7f else 1f
    }

    override fun layoutSubviews(width: Double, height: Double) = Unit
}
