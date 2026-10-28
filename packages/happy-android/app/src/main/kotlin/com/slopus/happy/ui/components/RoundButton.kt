package com.slopus.happy.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ProgressBar
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.RoundButtonDisplay
import com.slopus.happy.layout.RoundButtonLayout
import com.slopus.happy.layout.RoundButtonMetrics
import com.slopus.happy.layout.RoundButtonSize
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.viewScope
import kotlinx.coroutines.launch

/**
 * components/RoundButton.tsx (happy-ios `RoundButton`): a filled pill or a
 * text action, stretched to the width it gets. An async [action] shows the
 * spinner until it finishes. Where iOS draws liquid glass ([glass]), Android
 * draws a solid pill with the platform's elevation shadow.
 */
class RoundButton(
    context: Context,
    title: String,
    val size: RoundButtonSize = RoundButtonSize.Large,
    val display: RoundButtonDisplay = RoundButtonDisplay.Filled,
) : ManualLayout(context), ThemeAware {
    enum class Glass { Prominent, Regular }

    var title: String = title
        set(value) { field = value; updateLabel() }

    var loadingTitle: String? = null
        set(value) { field = value; updateLabel() }

    var onTap: (() -> Unit)? = null
    var action: (suspend () -> Unit)? = null

    var isLoading = false
        set(value) { field = value; updateLabel(); updateSpinner() }

    var glass: Glass? = null
        set(value) { if (field != value) { field = value; applyTheme() } }

    val label = Label(
        context,
        TextStyles.roundButton(size, display),
        if (display == RoundButtonDisplay.Filled) Palette.roundButtonText else Palette.textSecondary,
        maxLines = if (display == RoundButtonDisplay.Filled) 1 else 0,
        alignment = Layout.Alignment.ALIGN_CENTER,
    )
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply { visibility = View.GONE }
    private val shape = GradientDrawable()
    private var cornerRadius = 0.0

    init {
        addView(label)
        addView(spinner)
        isClickable = true
        isFocusable = true
        clipToPadding = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(cornerRadius))
            }
        }
        setOnClickListener { tapped() }
        updateLabel()
        applyTheme()
    }

    private val inline get() = isLoading && loadingTitle != null

    fun layout(width: Double): RoundButtonLayout {
        val spinnerSize = Size(20, 20)
        val loading = when {
            !isLoading -> RoundButtonLayout.Loading.None
            inline -> RoundButtonLayout.Loading.Inline(spinnerSize)
            else -> RoundButtonLayout.Loading.Overlay(spinnerSize)
        }
        return RoundButtonLayout(RoundButtonLayout.Input(width, size, display, label.measurable, loading))
    }

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        if (cornerRadius != layout.cornerRadius) {
            cornerRadius = layout.cornerRadius
            shape.cornerRadius = dp(cornerRadius)
            invalidateOutline()
        }
        label.setFrame(layout.label)
        layout.spinner?.let { spinner.setFrame(it) }
    }

    override fun applyTheme() {
        val prominent = glass == Glass.Prominent || (glass == null && display == RoundButtonDisplay.Filled)
        label.color = when {
            prominent -> Palette.roundButtonText
            glass == Glass.Regular -> Palette.text
            else -> Palette.textSecondary
        }
        spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(label.color))
        val fill = when {
            prominent -> Theme.color(Palette.roundButtonFill)
            glass == Glass.Regular -> Theme.color(Palette.surface)
            else -> 0
        }
        shape.setColor(fill)
        shape.cornerRadius = dp(cornerRadius)
        val ripple = Theme.color(if (prominent) Palette.roundButtonFillPressed else Palette.surfacePressedOverlay)
        background = RippleDrawable(ColorStateList.valueOf(ripple), shape, shape)
        // The platform's shadow stands in for the glass pill's.
        elevation = if (glass != null) dp(if (Theme.isDark) 0.0 else 3.0) else 0f
        label.applyTheme()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.5f
    }

    /** Touches count a little beyond the frame (`hitSlop`). */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = super.dispatchTouchEvent(event)

    private fun updateLabel() {
        label.text = if (inline) loadingTitle ?: title else title
        // The overlay spinner hides the title but keeps its space.
        label.alpha = if (isLoading && !inline) 0f else 1f
        contentDescription = label.text
        requestLayout()
    }

    private fun updateSpinner() {
        spinner.visibility = if (isLoading) View.VISIBLE else View.GONE
        requestLayout()
    }

    private fun tapped() {
        if (isLoading) return
        onTap?.let { it(); return }
        val action = action ?: return
        isLoading = true
        viewScope.launch {
            try {
                action()
            } finally {
                isLoading = false
            }
        }
    }

    companion object {
        fun hitSlop(size: RoundButtonSize) = RoundButtonMetrics.of(size).hitSlop
    }
}
