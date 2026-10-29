package com.slopus.happy.ui.stemcells

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.view.View
import android.widget.Switch
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.stemcells.StemCellsSettingsLayout
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame

/** A row in a settings card: what it says and does (happy-ios `StemCellsSettingsRow`). */
data class StemCellsSettingsRow(
    val id: String,
    val title: String,
    val icon: Pair<String, Tile>? = null,
    val value: String? = null,
    val accessory: StemCellsSettingsLayout.Accessory = StemCellsSettingsLayout.Accessory.Chevron,
    val destructive: Boolean = false,
    /** A switch's state, for a toggle. */
    val isOn: Boolean = false,
    val action: (() -> Unit)? = null,
    val toggled: ((Boolean) -> Unit)? = null,
) {
    enum class Tile(val color: ColorToken) {
        Orange(StemCellsPalette.stemCellsSettingsTileOrange),
        Gray(StemCellsPalette.stemCellsSettingsTileGray),
        Blue(StemCellsPalette.stemCellsSettingsTileBlue),
        Purple(StemCellsPalette.stemCellsSettingsTilePurple),
        Green(StemCellsPalette.stemCellsSettingsTileGreen),
        Red(StemCellsPalette.stemCellsSettingsTileRed),
        Teal(StemCellsPalette.stemCellsSettingsTileTeal),
        Indigo(StemCellsPalette.stemCellsSettingsTileIndigo),
    }
}

/** A rounded rectangle of `token` behind a view, `radius` round. */
internal class RoundFill(private val token: ColorToken, radius: Double) : GradientDrawable() {
    init {
        cornerRadius = dp(radius)
        apply()
    }

    fun apply() = setColor(Theme.color(token))
}

/**
 * A grouped card of rows (happy-ios `StemCellsSettingsCardView`),
 * Telegram's inset style: a coloured square with a white glyph, the title,
 * a quieter value, a chevron or a switch, hairlines between rows from the
 * title's start.
 */
class StemCellsSettingsCardView(context: Context) : ManualLayout(context), ThemeAware {
    private val fill = RoundFill(StemCellsPalette.stemCellsSettingsCard, StemCellsSettingsLayout.Metrics.radius)
    private val views = ArrayList<RowView>()

    init {
        background = fill
        clipToOutline = true
    }

    fun show(rows: List<StemCellsSettingsRow>, layout: StemCellsSettingsLayout.Card) {
        while (views.size < rows.size) RowView(context).also { addView(it); views += it }
        while (views.size > rows.size) removeView(views.removeAt(views.size - 1))
        for ((view, pair) in views.zip(rows.zip(layout.rows))) view.show(pair.first, pair.second)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        for (view in views) view.place()
    }

    override fun applyTheme() {
        fill.apply()
        for (view in views) view.applyTheme()
    }

    companion object {
        val titleStyle = StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 17.0)
        val valueStyle = StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 17.0)

        /** A value's width as a row draws it. */
        fun valueWidth(text: String?): Double? = text?.let { TextLayouts.measure(it, valueStyle, 100_000.0, TextLayouts.Env(Theme.density, com.slopus.happy.layout.TextScale.Standard), 1).width }
    }

    /** One row: Settings' own, and the detail's folds and "Show more". */
    class RowView(context: Context) : ManualLayout(context), ThemeAware {
        private val tile = View(context)
        private val tileFill = GradientDrawable().apply { cornerRadius = dp(StemCellsSettingsLayout.Metrics.tileRadius) }
        private val glyph = IconView(context, null, StemCellsSettingsLayout.Metrics.glyph, StemCellsPalette.stemCellsSettingsGlyph)
        private val title = Label(context, titleStyle, StemCellsPalette.stemCellsSettingsText, maxLines = 1).apply { followsTextSize = false }
        private val value = Label(context, valueStyle, StemCellsPalette.stemCellsSettingsSecondary, maxLines = 1, alignment = Layout.Alignment.ALIGN_OPPOSITE).apply { followsTextSize = false }
        private val chevron = IconView(context, StemCellsGlyphs.icon("chevron-right"), 14.0, StemCellsPalette.stemCellsSettingsSecondary)
        private val toggle: Switch = ItemRowView.switch(context)
        private val separator = View(context)
        private var row: StemCellsSettingsRow? = null
        private var frames: StemCellsSettingsLayout.Row? = null
        private var updating = false

        /** A fold's chevron, turned down while what it folds is open. */
        var chevronTurned = false
            set(value) {
                field = value
                chevron.icon = StemCellsGlyphs.icon(if (value) "chevron-down" else "chevron-right")
            }

        init {
            tile.background = tileFill
            for (view in listOf(tile, glyph, title, value, chevron, separator)) {
                view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(view)
            }
            toggle.setOnCheckedChangeListener { _, on ->
                if (updating) return@setOnCheckedChangeListener
                StemCellsHaptics.play(this, StemCellsHaptics.Moment.Pick)
                row?.toggled?.invoke(on)
            }
            addView(toggle)
            setOnClickListener { row?.action?.invoke() }
            applyTheme()
        }

        fun show(row: StemCellsSettingsRow, frames: StemCellsSettingsLayout.Row) {
            this.row = row
            this.frames = frames
            row.icon?.let { (name, color) ->
                tileFill.setColor(Theme.color(color.color))
                glyph.icon = StemCellsGlyphs.icon(name)
            }
            title.text = row.title
            title.color = if (row.destructive) StemCellsPalette.stemCellsSettingsDestructive else StemCellsPalette.stemCellsSettingsText
            value.text = row.value ?: ""
            if (toggle.isChecked != row.isOn && !toggle.isPressed) {
                updating = true
                toggle.isChecked = row.isOn
                updating = false
            }
            tag = "stem-settings:${row.id}"
            toggle.tag = "stem-settings-toggle:${row.id}"
            toggle.contentDescription = row.title
            val isToggle = row.accessory == StemCellsSettingsLayout.Accessory.Toggle
            importantForAccessibility = if (isToggle) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = listOfNotNull(row.title, row.value).joinToString(", ")
            isClickable = row.action != null
            isFocusable = row.action != null
            setFrame(frames.box)
            place()
        }

        /** Its parts where the row's frames put them, in its own space. */
        fun place() {
            val frames = frames ?: return
            val origin = frames.box
            fun local(rect: Rect) = Rect(rect.minX - origin.minX, rect.minY - origin.minY, rect.width, rect.height)
            val icon = frames.icon?.takeIf { row?.icon != null }
            tile.visibility = if (icon == null) GONE else VISIBLE
            glyph.visibility = tile.visibility
            icon?.let {
                tile.setFrame(local(it))
                glyph.setFrame(local(it))
            }
            title.setFrame(local(frames.title))
            value.visibility = if (frames.value == null) GONE else VISIBLE
            frames.value?.let { value.setFrame(local(it)) }
            chevron.visibility = if (frames.chevron == null) GONE else VISIBLE
            frames.chevron?.let { chevron.setFrame(local(it)) }
            toggle.visibility = if (frames.toggle == null) GONE else VISIBLE
            frames.toggle?.let { toggle.setFrame(local(it)) }
            separator.visibility = if (frames.separator == null) GONE else VISIBLE
            frames.separator?.let { separator.setFrame(local(it)) }
        }

        override fun layoutSubviews(width: Double, height: Double) = place()

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            applyPressed()
        }

        private fun applyPressed() {
            setBackgroundColor(if (isPressed && row?.action != null) Theme.color(StemCellsPalette.stemCellsRowPressed) else 0)
        }

        override fun applyTheme() {
            row?.icon?.let { tileFill.setColor(Theme.color(it.second.color)) }
            separator.setBackgroundColor(Theme.color(StemCellsPalette.stemCellsSettingsSeparator))
            for (view in listOf(glyph, chevron)) view.applyTheme()
            title.applyTheme()
            value.applyTheme()
            applyPressed()
        }
    }
}
