package com.slopus.happy.ui.nav

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.Layout
import android.view.View
import android.widget.FrameLayout
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.NavigationBarLayout
import com.slopus.happy.layout.NavigationTitleLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.FontFace
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.setFrame

/**
 * A screen's top bar: back, the title (and the custom server's host under
 * it, happy-ios `NavigationTitleView`), and icon actions. Material's app bar
 * stands in for UIKit's navigation bar; the title stays centred as on iOS.
 */
class NavigationBar(context: Context) : ManualLayout(context), ThemeAware {
    /** An icon button, or a text button when [text] is set (UIBarButtonItem's title). */
    class Action(val icon: Icon?, val label: String, val size: Double = 24.0, val text: String? = null, val onClick: () -> Unit) {
        companion object {
            fun text(text: String, onClick: () -> Unit) = Action(null, text, text = text, onClick = onClick)
        }
    }

    var safeArea: LayoutInsets = LayoutInsets.Zero
        set(value) { if (field != value) { field = value; requestLayout() } }

    var onBack: (() -> Unit)? = null
        set(value) { field = value; rebuild() }

    var actions: List<Action> = emptyList()
        set(value) { field = value; rebuild() }

    var leadingActions: List<Action> = emptyList()
        set(value) { field = value; rebuild() }

    var title: String? = null
        set(value) { field = value; titleLabel.text = value.orEmpty(); requestLayout() }

    /** How a title too long for the bar is cut. */
    var titleEllipsize: android.text.TextUtils.TruncateAt
        get() = titleLabel.ellipsize
        set(value) { titleLabel.ellipsize = value }

    var subtitle: String? = null
        set(value) { field = value; subtitleLabel.text = value.orEmpty(); requestLayout() }

    /** Replaces the title and subtitle. */
    var titleView: View? = null
        set(value) {
            field?.let { removeView(it) }
            field = value
            value?.let { addView(it) }
            requestLayout()
        }

    /** The bar's fill; clear lets the screen show through. */
    var fill: ColorToken = ColorToken.Clear
        set(value) { field = value; applyTheme() }

    var tint: ColorToken = Palette.headerTint
        set(value) { field = value; rebuild() }

    private val titleLabel = Label(context, TextStyles.nativeHeaderTitle, Palette.text, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER)
    private val subtitleLabel = Label(context, TextStyles.headerSubtitle, Palette.textSecondary, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER)
    private val leadingButtons = mutableListOf<View>()
    private val trailingButtons = mutableListOf<View>()

    /** The button drawn for [actions]' item at [index], for a menu to hang from. */
    fun actionView(index: Int): View? = trailingButtons.getOrNull(index)

    init {
        addView(titleLabel)
        addView(subtitleLabel)
        applyTheme()
    }

    private fun rebuild() {
        (leadingButtons + trailingButtons).forEach(::removeView)
        widths.clear()
        leadingButtons.clear()
        trailingButtons.clear()
        onBack?.let { back -> leadingButtons += button(Action(BACK_ICON, "Back", onClick = back)) }
        leadingActions.forEach { leadingButtons += button(it) }
        actions.forEach { trailingButtons += button(it) }
        (leadingButtons + trailingButtons).forEach(::addView)
        requestLayout()
    }

    private val widths = HashMap<View, () -> Double>()

    private fun button(action: Action): View = FrameLayout(context).apply {
        val text = action.text
        if (text != null) {
            val label = Label(context, TEXT_ACTION, tint, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER)
            label.text = text
            addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
            widths[this] = { NavigationBarLayout.textButtonWidth(label.measure(Double.MAX_VALUE).width) }
        } else {
            val icon = IconView(context, action.icon, action.size, tint)
            addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        contentDescription = action.label
        isClickable = true
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(Theme.color(Palette.surfacePressedOverlay)), null, null)
        setOnClickListener { action.onClick() }
    }

    fun layout(width: Double): NavigationBarLayout {
        val titleSize = titleView?.let { v -> Size(com.slopus.happy.ui.view.px(v.measuredWidth.coerceAtLeast(0)), com.slopus.happy.ui.view.px(v.measuredHeight)) }
            ?: run {
                val t = title?.takeIf { it.isNotEmpty() }?.let { titleLabel.measure(width) }
                val s = subtitle?.takeIf { it.isNotEmpty() }?.let { subtitleLabel.measure(width) }
                if (t == null && s == null) null else NavigationTitleLayout(t, s, width).size
            }
        fun width(v: View) = widths[v]?.invoke() ?: NavigationBarLayout.Metrics.button
        return NavigationBarLayout(
            width, safeArea, leadingButtons.size, trailingButtons.size, titleSize,
            trailingWidths = trailingButtons.map(::width), leadingWidths = leadingButtons.map(::width),
        )
    }

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        titleView?.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        layout.leading.forEachIndexed { i, r -> leadingButtons[i].setFrame(r) }
        layout.trailing.forEachIndexed { i, r -> trailingButtons[i].setFrame(r) }
        val titleFrame = layout.title
        val custom = titleView
        if (custom != null) {
            titleLabel.visibility = GONE
            subtitleLabel.visibility = GONE
            titleFrame?.let { custom.setFrame(it) }
            return
        }
        val t = title?.takeIf { it.isNotEmpty() }?.let { titleLabel.measure(width) }
        val s = subtitle?.takeIf { it.isNotEmpty() }?.let { subtitleLabel.measure(width) }
        if (titleFrame == null) {
            titleLabel.visibility = GONE
            subtitleLabel.visibility = GONE
            return
        }
        val inner = NavigationTitleLayout(t, s, titleFrame.width)
        titleLabel.visibility = if (inner.title != null) VISIBLE else GONE
        subtitleLabel.visibility = if (inner.subtitle != null) VISIBLE else GONE
        inner.title?.let { titleLabel.setFrame(it.offsetBy(titleFrame.x, titleFrame.y)) }
        inner.subtitle?.let { subtitleLabel.setFrame(it.offsetBy(titleFrame.x, titleFrame.y)) }
    }

    override fun applyTheme() {
        setBackgroundColor(Theme.color(fill))
        (leadingButtons + trailingButtons).forEach {
            it.background = RippleDrawable(ColorStateList.valueOf(Theme.color(Palette.surfacePressedOverlay)), null, null)
        }
    }

    companion object {
        /** Material's back arrow, the platform's idiom for UIKit's chevron. */
        val BACK_ICON = Icon.ionicons("arrow-back")

        /** A text action's label: Material's text button, in the app's face. */
        val TEXT_ACTION = TextStyle(FontFace.SemiBold, 15.0, 20.0)
    }
}

/** The bar's bottom edge under [safeArea]: where a screen's content begins. */
fun barBottom(safeArea: LayoutInsets) = NavigationBarLayout.height(safeArea.top)

fun Rect.belowBar(safeArea: LayoutInsets) = this
