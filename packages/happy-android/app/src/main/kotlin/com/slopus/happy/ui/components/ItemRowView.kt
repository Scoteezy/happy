package com.slopus.happy.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.IconLayout
import com.slopus.happy.layout.ItemRowLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame

/** What sits in a row's 29dp icon slot: one of happy-app's icons, or a picture (happy-ios `ItemIcon`). */
sealed interface ItemIcon {
    /** One of happy-app's icons, centred in the slot. [size] is its RN size: 29, or 28 on a few screens. */
    data class Glyph(val icon: Icon, val tint: ColorToken, val size: Double = ItemRowLayout.Metrics.iconSize) : ItemIcon

    /** A picture; [round] clips it to a circle (a GitHub avatar). */
    data class Picture(val drawable: Drawable, val round: Boolean = false, val tint: ColorToken? = null) : ItemIcon
}

/**
 * components/Item.tsx (happy-ios `ItemRowView`). It measures its parts and
 * applies [ItemRowLayout]; it has no layout rules of its own. Pressed, it
 * shows the platform's ripple where iOS highlights.
 */
class ItemRowView(
    context: Context,
    title: String,
    subtitle: String? = null,
    detail: String? = null,
    icon: ItemIcon? = null,
    isDestructive: Boolean = false,
    onTap: (() -> Unit)? = null,
) : ManualLayout(context), ThemeAware {
    var title: String
        get() = titleLabel.text.toString()
        set(value) { titleLabel.text = value; requestLayout() }

    var subtitle: String? = subtitle
        set(value) { field = value; apply() }

    var detail: String? = detail
        set(value) { field = value; apply() }

    var icon: ItemIcon? = icon
        set(value) { field = value; apply() }

    var isDestructive: Boolean = isDestructive
        set(value) { field = value; apply() }

    /** Shown only when the row is tappable and has no trailing view, like happy-app. */
    var showsChevron = true
        set(value) { field = value; apply() }

    /** Item.tsx `rightElement`: a switch, checkmark or dropdown value. It replaces the chevron and hides the detail. */
    var trailingView: View? = null
        set(value) {
            if (field !== value) field?.let(::removeView)
            field = value
            value?.let { if (it.parent !== this) addView(it) }
            apply()
        }

    /** Item.tsx `loading`: a spinner, and taps are ignored. */
    var isLoading = false
        set(value) { field = value; apply() }

    /** Set by the group: every row but the last. */
    var showsDivider = false
        set(value) { if (field != value) { field = value; apply() } }

    var onTap: (() -> Unit)? = onTap
        set(value) { field = value; apply() }

    /** Item.tsx's long press, which it uses to copy. */
    var onLongPress: (() -> Unit)? = null
        set(value) { field = value; apply() }

    val titleLabel = Label(context, TextStyles.itemTitle, Palette.text, maxLines = 2)
    val subtitleLabel = Label(context, TextStyles.itemSubtitle, Palette.textSecondary, maxLines = 2)
    val detailLabel = Label(context, TextStyles.itemDetail, Palette.textSecondary, maxLines = 1)
    private val pictureView = ImageView(context)
    private val glyphView = IconView(context, null, ItemRowLayout.Metrics.iconSize)
    /** Item.tsx: `<Ionicons name="chevron-forward" size={17} />` on iOS. */
    private val chevronView = IconView(context, Icon.ionicons("chevron-forward"), 17.0, Palette.groupedChevron)
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val dividerView = View(context)
    private var round = false

    init {
        this.title = title
        pictureView.clipToOutline = true
        pictureView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (round) outline.setOval(0, 0, view.width, view.height) else outline.setRect(0, 0, view.width, view.height)
            }
        }
        listOf(pictureView, glyphView, titleLabel, subtitleLabel, detailLabel, chevronView, spinner, dividerView).forEach {
            it.isClickable = false
            it.isFocusable = false
            it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(it)
        }
        setOnClickListener {
            if (isLoading) return@setOnClickListener
            // A switch row with nothing else to do toggles, as Android's settings rows do.
            this.onTap?.invoke() ?: (trailingView as? Switch)?.toggle()
        }
        setOnLongClickListener { this.onLongPress?.let { it(); true } ?: false }
        apply()
        applyTheme()
    }

    private val isInteractive get() = onTap != null || onLongPress != null
    private val showsAccessory get() = isInteractive && showsChevron && trailingView == null

    /** The spinner while loading, otherwise the trailing view. */
    private fun trailingSize(): Size? {
        if (isLoading) return Size(SPINNER, SPINNER)
        val view = trailingView ?: return null
        if (view is ManualLayout) return view.sizeThatFits(Double.MAX_VALUE, Double.MAX_VALUE)
        if (view is IconView) return view.intrinsicSize()
        view.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return Size(px(view.measuredWidth), px(view.measuredHeight))
    }

    /** The pure layout for this row's current content at [width]. */
    fun layout(width: Double): ItemRowLayout = ItemRowLayout(
        ItemRowLayout.Input(
            width = width,
            hasIcon = icon != null,
            title = titleLabel.measurable,
            subtitle = if (subtitle == null) null else subtitleLabel.measurable,
            detail = if (detail == null || trailingView != null) null else detailLabel.measurable,
            trailing = trailingSize(),
            accessory = if (showsAccessory) chevronView.intrinsicSize() else null,
            showsDivider = showsDivider,
        ),
    )

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        layout.icon?.let {
            pictureView.setFrame(it)
            glyphView.setFrame(it)
        }
        titleLabel.setFrame(layout.title)
        layout.subtitle?.let { subtitleLabel.setFrame(it) }
        detailLabel.visibility = if (layout.detail == null) GONE else VISIBLE
        layout.detail?.let { detailLabel.setFrame(it) }
        layout.trailing?.let { (if (isLoading) spinner else trailingView)?.setFrame(it) }
        chevronView.visibility = if (layout.accessory == null) GONE else VISIBLE
        layout.accessory?.let { chevronView.setFrame(IconLayout.textFrame(it, Theme.density.toDouble())) }
        layout.divider?.let { dividerView.setFrame(it) }
    }

    /** Brings every subview in line with the properties above. */
    private fun apply() {
        subtitleLabel.text = subtitle.orEmpty()
        subtitleLabel.visibility = if (subtitle == null) GONE else VISIBLE
        // Item.tsx: two lines, unless the subtitle sets its own breaks.
        subtitleLabel.maxLines = if (subtitle?.contains('\n') == true) 0 else 2
        // A subtitle caps the title at one line.
        titleLabel.maxLines = if (subtitle == null) 2 else 1
        titleLabel.color = if (isDestructive) Palette.textDestructive else Palette.text
        detailLabel.text = detail.orEmpty()
        detailLabel.visibility = if (detail == null || trailingView != null) GONE else VISIBLE
        when (val icon = icon) {
            is ItemIcon.Glyph -> {
                glyphView.icon = icon.icon
                glyphView.size = icon.size
                glyphView.tint = icon.tint
            }
            is ItemIcon.Picture -> {
                round = icon.round
                pictureView.setImageDrawable(icon.drawable)
                pictureView.scaleType = ImageView.ScaleType.CENTER_CROP
                pictureView.imageTintList = icon.tint?.let { ColorStateList.valueOf(Theme.color(it)) }
                pictureView.invalidateOutline()
            }
            null -> {}
        }
        pictureView.visibility = if (icon is ItemIcon.Picture) VISIBLE else GONE
        glyphView.visibility = if (icon is ItemIcon.Glyph) VISIBLE else GONE
        if (icon !is ItemIcon.Glyph) glyphView.icon = null
        spinner.visibility = if (isLoading) VISIBLE else GONE
        trailingView?.visibility = if (isLoading) GONE else VISIBLE
        dividerView.visibility = if (showsDivider) VISIBLE else GONE
        isClickable = isInteractive || trailingView is Switch
        isLongClickable = onLongPress != null
        isFocusable = isClickable
        isEnabled = !isLoading
        contentDescription = listOfNotNull(title, subtitle, if (trailingView == null) detail else null).joinToString(", ")
        applyRipple()
        requestLayout()
    }

    private fun applyRipple() {
        background = if (onTap != null || trailingView is Switch) RippleDrawable(ColorStateList.valueOf(Theme.color(Palette.surfacePressedOverlay)), null, ColorDrawableMask) else null
    }

    override fun applyTheme() {
        dividerView.setBackgroundColor(Theme.color(Palette.itemDivider))
        spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(Palette.textSecondary))
        applyRipple()
    }

    companion object {
        /** UIActivityIndicatorView's medium size; Android's small progress bar is drawn into it. */
        const val SPINNER = 20.0

        private val ColorDrawableMask = android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt())

        /** The platform's switch, in the app's appearance rather than the system's. */
        fun switch(context: Context): Switch = Switch(
            android.view.ContextThemeWrapper(
                context,
                if (Theme.isDark) android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light,
            ),
        )

        /** A row with a switch, the way appearance.tsx builds most of its rows. The switch changes on its own; [onChange] hears about it. */
        fun toggle(
            context: Context,
            title: String,
            subtitle: String? = null,
            icon: ItemIcon? = null,
            isOn: Boolean,
            onChange: (Boolean) -> Unit,
        ): ItemRowView {
            val row = ItemRowView(context, title, subtitle, icon = icon)
            val toggle = switch(context).apply {
                isChecked = isOn
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            }
            row.trailingView = toggle
            return row
        }

        /** A tappable row whose trailing slot shows a checkmark when selected (settings/language.tsx and agents.tsx). */
        fun choice(
            context: Context,
            title: String,
            subtitle: String? = null,
            icon: ItemIcon? = null,
            selected: Boolean,
            checkTint: ColorToken = Palette.iconBlue,
            onTap: () -> Unit,
        ): ItemRowView {
            val row = ItemRowView(context, title, subtitle, icon = icon, onTap = onTap)
            row.showsChevron = false
            if (selected) {
                row.trailingView = IconView(context, Icon.ionicons("checkmark"), 20.0, checkTint).apply { contentDescription = "selected" }
            }
            return row
        }
    }
}
