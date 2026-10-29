package com.slopus.happy.ui.composer

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import com.slopus.happy.R
import com.slopus.happy.autocomplete.FileItem
import com.slopus.happy.autocomplete.Suggestion
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.composer.AgentInputLayout
import com.slopus.happy.layout.composer.ComposerSuggestionListLayout
import com.slopus.happy.layout.composer.ComposerSuggestionRowLayout
import com.slopus.happy.layout.composerSuggestionFill
import com.slopus.happy.layout.glassShadow
import com.slopus.happy.layout.suggestionCommand
import com.slopus.happy.layout.suggestionDescription
import com.slopus.happy.layout.suggestionFile
import com.slopus.happy.layout.suggestionLabel
import com.slopus.happy.layout.surfacePressed
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.ScrollContainer
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame

/**
 * AgentInputAutocomplete.tsx (happy-ios `ComposerSuggestionListView`): the
 * suggestions on a glass card (24 corners, a hairline rim, the glass's
 * shadow), 48 rows, scrolling past 320. The highlighted row is
 * `surfaceSelected`, a pressed one `surfacePressed`. It fades and rises in
 * (AnimatedPopup).
 *
 * The glass is a solid fill in the colour it shows over the chat, as the
 * new-session popovers are; iOS's LocalBlurHalo, a blur of the chat around
 * the list, has no Android counterpart (nothing blurs what is behind a view)
 * and is left out. Rows don't take focus, so a tap keeps the keyboard up
 * (`keyboardShouldPersistTaps="handled"`).
 */
class ComposerSuggestionListView(context: Context) : ManualLayout(context), ThemeAware {
    var onSelect: ((Int) -> Unit)? = null

    private val glass = GradientDrawable().apply { cornerRadius = dp(AgentInputLayout.Metrics.autocompleteRadius) }
    private val scroll = ScrollContainer(context)
    private val rows = mutableListOf<RowView>()

    var suggestions: List<Suggestion> = emptyList()
        private set
    var selectedIndex = -1
        private set

    /** The rim, one device pixel. */
    val border: Double get() = 1.0 / Theme.density

    init {
        // The list is the glass, so its shadow is drawn by the screen, not cut at the list's edge.
        background = glass
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(AgentInputLayout.Metrics.autocompleteRadius))
                outline.alpha = 1f
            }
        }
        clipToOutline = true
        elevation = dp(ELEVATION)
        scroll.isVerticalScrollBarEnabled = true
        addView(scroll)
        applyTheme()
    }

    fun set(suggestions: List<Suggestion>, selected: Int) {
        if (suggestions != this.suggestions) {
            this.suggestions = suggestions
            while (rows.size < suggestions.size) {
                val row = RowView(context)
                row.setOnClickListener { rows.indexOf(row).takeIf { it in this.suggestions.indices }?.let { onSelect?.invoke(it) } }
                scroll.addContent(row)
                rows += row
            }
            rows.forEachIndexed { index, row ->
                row.visibility = if (index < suggestions.size) VISIBLE else GONE
                if (index < suggestions.size) row.suggestion = suggestions[index]
            }
            requestLayout()
        }
        selectedIndex = selected
        rows.forEachIndexed { index, row -> row.isSelectedRow = index == selected }
    }

    override fun applyTheme() {
        glass.setColor(Theme.color(Palette.composerSuggestionFill))
        glass.setStroke(1, Theme.color(Palette.glassBorder))
        // glass.shadow carries its own alpha.
        outlineAmbientShadowColor = Theme.color(Palette.glassShadow)
        outlineSpotShadowColor = Theme.color(Palette.glassShadow)
        rows.forEach { it.applyTheme() }
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val rim = border
        scroll.setFrame(Rect(rim, rim, maxOf(0.0, width - 2 * rim), maxOf(0.0, height - 2 * rim)))
        val frames = ComposerSuggestionListLayout.rows(suggestions.size, width, rim)
        scroll.place(
            Size(maxOf(0.0, width - 2 * rim), frames.lastOrNull()?.maxY ?: 0.0),
            rows.mapIndexed { index, row -> row as View to frames.getOrNull(index) }.toMap(),
        )
    }

    /** FadeIn 190 ms out-cubic from 0, scale 0.96, 8 lower. */
    fun animateIn() {
        animate().cancel()
        alpha = 0f
        translationY = dp(8.0)
        scaleX = 0.96f
        scaleY = 0.96f
        animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(190)
            .setInterpolator(PathInterpolator(0.215f, 0.61f, 0.355f, 1f))
            .withEndAction(null)
            .start()
    }

    /** FadeOut 140 ms in-cubic. */
    fun animateOut(completion: () -> Unit) {
        animate().cancel()
        animate().alpha(0f)
            .setDuration(140)
            .setInterpolator(PathInterpolator(0.55f, 0.055f, 0.675f, 0.19f))
            .withEndAction(completion)
            .start()
    }

    /** AgentInputSuggestionView.tsx: CommandSuggestion or FileMentionSuggestion. */
    private class RowView(context: Context) : ManualLayout(context), ThemeAware {
        private val nameLabel = Label(context, TextStyles.suggestionCommand, Palette.text, maxLines = 1)
        private val detailLabel = Label(context, TextStyles.suggestionDescription, Palette.textSecondary, maxLines = 1)
        private val iconCircle = View(context)
        private val iconShape = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        private val iconView = IconView(context, null, ComposerSuggestionRowLayout.Metrics.iconGlyphSize, Palette.textSecondary)
        private val tagLabel = Label(context, TextStyles.suggestionLabel, Palette.textSecondary, maxLines = 1)
        private val background = GradientDrawable()

        var suggestion: Suggestion? = null
            set(value) {
                if (field == value) return
                field = value
                applyContent()
            }

        var isSelectedRow = false
            set(value) {
                if (field == value) return
                field = value
                applyBackground()
            }

        init {
            isClickable = true
            isFocusable = false
            iconCircle.background = iconShape
            for (view in listOf(nameLabel, detailLabel, iconCircle, iconView, tagLabel)) {
                view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(view)
            }
            setBackground(background)
            applyTheme()
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            applyBackground()
        }

        override fun applyTheme() {
            iconShape.setColor(Theme.color(Palette.surfaceHigh))
            applyBackground()
        }

        private fun applyBackground() {
            background.setColor(
                when {
                    isPressed -> Theme.color(Palette.surfacePressed)
                    isSelectedRow -> Theme.color(Palette.surfaceSelected)
                    else -> 0
                },
            )
        }

        private fun applyContent() {
            when (val suggestion = suggestion) {
                is Suggestion.Command -> {
                    nameLabel.text = "/${suggestion.command}"
                    detailLabel.style = TextStyles.suggestionDescription
                    detailLabel.text = suggestion.description.orEmpty()
                    nameLabel.visibility = VISIBLE
                    detailLabel.visibility = if (suggestion.description == null) GONE else VISIBLE
                    iconCircle.visibility = GONE
                    iconView.visibility = GONE
                    tagLabel.visibility = GONE
                    contentDescription = suggestion.description?.let { "/${suggestion.command}, $it" } ?: "/${suggestion.command}"
                }
                is Suggestion.FileMention -> {
                    val isFolder = suggestion.fileType == FileItem.FileType.Folder
                    val tag = context.getString(if (isFolder) R.string.agent_input_suggestion_folder_label else R.string.agent_input_suggestion_file_label)
                    detailLabel.style = TextStyles.suggestionFile
                    detailLabel.text = suggestion.filePath + suggestion.fileName
                    tagLabel.text = tag
                    iconView.icon = Icon.ionicons(if (isFolder) "folder" else "document-text")
                    nameLabel.visibility = GONE
                    detailLabel.visibility = VISIBLE
                    iconCircle.visibility = VISIBLE
                    iconView.visibility = VISIBLE
                    tagLabel.visibility = VISIBLE
                    contentDescription = "${suggestion.filePath + suggestion.fileName}, $tag"
                }
                null -> Unit
            }
            requestLayout()
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val natural = Double.MAX_VALUE
            when (val suggestion = suggestion) {
                is Suggestion.Command -> {
                    val layout = ComposerSuggestionRowLayout(
                        width,
                        ComposerSuggestionRowLayout.Input.Command(
                            nameLabel.measure(natural),
                            if (suggestion.description == null) null else detailLabel.measure(natural),
                        ),
                    )
                    nameLabel.setFrame(layout.name ?: Rect.Zero)
                    layout.description?.let(detailLabel::setFrame)
                }
                is Suggestion.FileMention -> {
                    val layout = ComposerSuggestionRowLayout(
                        width,
                        ComposerSuggestionRowLayout.Input.File(detailLabel.measure(natural), tagLabel.measure(natural)),
                    )
                    iconCircle.setFrame(layout.icon ?: Rect.Zero)
                    iconView.setFrame(layout.iconGlyph ?: Rect.Zero)
                    detailLabel.setFrame(layout.path ?: Rect.Zero)
                    tagLabel.setFrame(layout.label ?: Rect.Zero)
                }
                null -> Unit
            }
        }
    }

    companion object {
        /** glass.shadow 0/16 radius 36, as the platform's elevation. */
        const val ELEVATION = 12.0
    }
}
