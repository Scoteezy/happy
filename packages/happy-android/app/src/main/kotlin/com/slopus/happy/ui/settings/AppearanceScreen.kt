package com.slopus.happy.ui.settings

import android.content.Context
import android.view.View
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.config.ThemePreference
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.BubblePalette
import com.slopus.happy.layout.BubblePreviewLayout
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Size
import com.slopus.happy.model.SessionListGrouping
import com.slopus.happy.model.Settings
import com.slopus.happy.model.settings.Languages
import com.slopus.happy.ui.components.AvatarStyle
import com.slopus.happy.ui.components.AvatarView
import com.slopus.happy.ui.components.GroupedListView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.components.ItemIcon
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.nav.BarScreen
import kotlinx.coroutines.launch

/**
 * Settings › Appearance (app/(app)/settings/appearance.tsx, happy-ios
 * `SettingsAppearanceViewController`), phone rows only: theme, language,
 * chat, avatars, input and display. The web-only rows (Enter to send,
 * command palette) are left out, as on a phone. iOS's two renderer rows
 * pick between its own UIKit lists; Android has one list, so they are not
 * here.
 */
class AppearanceScreen(context: Context, private val graph: AppGraph) : BarScreen(context) {
    private enum class Dropdown { BubbleColor, AvatarStyle }

    private val list = GroupedListView(context)
    private val toggles = SettingsToggleRows(context)
    private var settings: Settings = graph.settings.settings.value
    private var openDropdown: Dropdown? = null

    override fun createContent(): View {
        bar.title = context.getString(R.string.settings_appearance)
        return list
    }

    override fun viewCreated(view: View) {
        super.viewCreated(view)
        scope.launch {
            graph.settings.settings.collect {
                if (it != settings) {
                    settings = it
                    rebuild()
                }
            }
        }
        scope.launch { graph.localSettings.themePreference.collect { rebuild() } }
    }

    override fun onAppear() {
        super.onAppear()
        // The language may have changed on the page pushed from here.
        rebuild()
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea
    }

    private fun string(id: Int) = context.getString(id)

    private fun set(transform: (Settings) -> Settings) {
        // Shown at once; the store echoes the same value back.
        settings = transform(settings)
        graph.settings.change(transform)
    }

    private fun toggle(key: String, title: Int, subtitle: Int, icon: String, tint: ColorToken, get: (Settings) -> Boolean, put: Settings.(Boolean) -> Settings) =
        toggles.row(key, string(title), string(subtitle), ItemIcon.Glyph(Icon.ionicons(icon), tint), get(settings)) { on -> set { it.put(on) } }

    private fun rebuild() {
        val connecting = Palette.statusConnecting
        val purple = Palette.iconPurple
        val sections = mutableListOf<View>()

        val theme = graph.localSettings.themePreference.value
        sections += ItemGroupView(
            context,
            title = string(R.string.settings_appearance_theme),
            footer = string(R.string.settings_appearance_theme_description),
            rows = listOf(
                ItemRowView(
                    context,
                    string(R.string.settings_appearance),
                    subtitle = themeDescription(theme),
                    detail = themeName(theme),
                    icon = ItemIcon.Glyph(Icon.ionicons("contrast-outline"), connecting),
                ) { cycleTheme() },
            ),
        )

        val language = graph.language
        val languageDetail = Languages.byCode(settings.preferredLanguage)?.nativeName
            ?: "${string(R.string.settings_language_automatic)} (${Languages.detectedName(language.deviceLocales())})"
        sections += ItemGroupView(
            context,
            title = string(R.string.settings_language_title),
            footer = string(R.string.settings_language_description),
            rows = listOf(
                ItemRowView(
                    context,
                    string(R.string.settings_language_current_language),
                    detail = languageDetail,
                    icon = ItemIcon.Glyph(Icon.ionicons("language-outline"), Palette.iconBlue),
                ) { push(LanguageScreen(context, graph)) },
            ),
        )

        val bubbleKey = settings.userMessageBubbleColor.takeIf { it in BUBBLE_KEYS } ?: Settings.DEFAULT_USER_MESSAGE_BUBBLE_COLOR
        val bubbleOpen = openDropdown == Dropdown.BubbleColor
        val bubbleValue = DropdownValueView(context, BubblePreviewView(context, bubble(bubbleKey)), BubblePreviewLayout.size, bubbleName(bubbleKey))
        bubbleValue.isExpanded = bubbleOpen
        val bubbleRow = ItemRowView(
            context,
            string(R.string.settings_appearance_user_message_bubble_color),
            subtitle = string(R.string.settings_appearance_user_message_bubble_color_description),
            icon = ItemIcon.Glyph(Icon.ionicons("chatbubble-ellipses-outline"), bubble(bubbleKey).indicator),
        ) { toggleDropdown(Dropdown.BubbleColor) }
        bubbleRow.trailingView = bubbleValue
        val chatRows = mutableListOf<View>(
            toggle(
                "usageLimitShowRemaining",
                R.string.settings_appearance_usage_limit_show_remaining,
                R.string.settings_appearance_usage_limit_show_remaining_description,
                "speedometer-outline",
                connecting,
                { it.usageLimitShowRemaining },
                { copy(usageLimitShowRemaining = it) },
            ),
            bubbleRow,
        )
        if (bubbleOpen) {
            chatRows += OptionListView(
                context,
                BUBBLE_KEYS.map { key ->
                    OptionRowView(context, BubblePreviewView(context, bubble(key)), BubblePreviewLayout.size, bubbleName(key), key == bubbleKey) {
                        openDropdown = null
                        set { it.copy(userMessageBubbleColor = key) }
                        rebuild()
                    }
                },
            )
        }
        sections += ItemGroupView(context, title = string(R.string.settings_appearance_chat), footer = string(R.string.settings_appearance_chat_description), rows = chatRows)
        // `showDivider={open}`: the line under the row only while it is open.
        bubbleRow.showsDivider = bubbleOpen

        val avatarStyle = AvatarStyle.normalize(settings.avatarStyle)
        val monochrome = settings.avatarMonochrome
        val avatarOpen = openDropdown == Dropdown.AvatarStyle
        val avatarValue = DropdownValueView(context, avatarPreview(avatarStyle, monochrome), AVATAR_PREVIEW_SIZE, avatarStyleName(avatarStyle))
        avatarValue.isExpanded = avatarOpen
        val avatarRow = ItemRowView(
            context,
            string(R.string.settings_appearance_avatar_style),
            icon = ItemIcon.Glyph(Icon.ionicons("person-circle-outline"), connecting),
        ) { toggleDropdown(Dropdown.AvatarStyle) }
        avatarRow.trailingView = avatarValue
        val avatarRows = mutableListOf<View>(avatarRow)
        if (avatarOpen) {
            avatarRows += OptionListView(
                context,
                AvatarStyle.entries.map { style ->
                    OptionRowView(context, avatarPreview(style, monochrome), AVATAR_PREVIEW_SIZE, avatarStyleName(style), style == avatarStyle) {
                        openDropdown = null
                        set { it.copy(avatarStyle = style.key) }
                        rebuild()
                    }
                },
            )
        }
        avatarRows += toggle(
            "avatarMonochrome",
            R.string.settings_appearance_avatar_monochrome,
            R.string.settings_appearance_avatar_monochrome_description,
            "contrast-outline",
            connecting,
            { it.avatarMonochrome },
            { copy(avatarMonochrome = it) },
        )
        sections += ItemGroupView(context, title = string(R.string.settings_appearance_avatars), footer = string(R.string.settings_appearance_avatars_description), rows = avatarRows)
        // `showDivider={open}` here too: closed, the style row has no line above Black & White Avatars in happy-app.
        avatarRow.showsDivider = avatarOpen

        val contextSize = toggle(
            "alwaysShowContextSize",
            R.string.settings_appearance_always_show_context_size,
            R.string.settings_appearance_always_show_context_size_description,
            "analytics-outline",
            purple,
            { it.alwaysShowContextSize },
            { copy(alwaysShowContextSize = it) },
        )
        sections += ItemGroupView(context, title = string(R.string.settings_appearance_input), footer = string(R.string.settings_appearance_input_description), rows = listOf(contextSize))
        // ItemGroup counts the empty web-only slot after it, so the row keeps its divider.
        contextSize.showsDivider = true

        val grouping = settings.sessionListGrouping
        sections += ItemGroupView(
            context,
            title = string(R.string.settings_appearance_display),
            footer = string(R.string.settings_appearance_display_description),
            rows = listOf(
                ItemRowView(
                    context,
                    string(R.string.sessions_filter_grouping_title),
                    detail = string(if (grouping == SessionListGrouping.Project) R.string.sessions_filter_group_by_project else R.string.sessions_filter_flat_list),
                    icon = ItemIcon.Glyph(Icon.ionicons("list-outline"), purple),
                ) {
                    set { it.copy(sessionListGrouping = if (grouping == SessionListGrouping.Project) SessionListGrouping.Flat else SessionListGrouping.Project) }
                    rebuild()
                },
                toggle("compactToolCalls", R.string.settings_appearance_compact_tool_calls, R.string.settings_appearance_compact_tool_calls_description, "contract-outline", purple, { it.compactToolCalls }, { copy(compactToolCalls = it) }),
                toggle("fileDiffsSidebar", R.string.settings_appearance_file_diffs_sidebar, R.string.settings_appearance_file_diffs_sidebar_description, "git-branch-outline", Palette.iconTeal, { it.fileDiffsSidebar }, { copy(fileDiffsSidebar = it) }),
                toggle("groupToolCalls", R.string.settings_features_group_tool_calls, R.string.settings_features_group_tool_calls_subtitle, "layers-outline", Palette.iconViolet, { it.groupToolCalls }, { copy(groupToolCalls = it) }),
                toggle(
                    "showLineNumbersInToolViews",
                    R.string.settings_appearance_show_line_numbers_in_tool_views,
                    R.string.settings_appearance_show_line_numbers_in_tool_views_description,
                    "code-working-outline",
                    purple,
                    { it.showLineNumbersInToolViews },
                    { copy(showLineNumbersInToolViews = it) },
                ),
                toggle(
                    "showHarnessIconInSessionHeader",
                    R.string.settings_appearance_show_harness_icon_in_session_header,
                    R.string.settings_appearance_show_harness_icon_in_session_header_description,
                    "apps-outline",
                    purple,
                    { it.showHarnessIconInSessionHeader },
                    { copy(showHarnessIconInSessionHeader = it) },
                ),
                toggle(
                    "showFlavorIcons",
                    R.string.settings_appearance_show_harness_icons_in_session_list,
                    R.string.settings_appearance_show_harness_icons_in_session_list_description,
                    "apps-outline",
                    purple,
                    { it.showFlavorIcons },
                    { copy(showFlavorIcons = it) },
                ),
            ),
        )
        list.sections = sections
    }

    private fun avatarPreview(style: AvatarStyle, monochrome: Boolean) =
        AvatarPreviewView(context, AvatarView.Content(AVATAR_PREVIEW_ID, style, monochrome), AVATAR_PREVIEW_SIZE)

    /** Opening one dropdown closes the other. */
    private fun toggleDropdown(dropdown: Dropdown) {
        openDropdown = if (openDropdown == dropdown) null else dropdown
        rebuild()
    }

    /** Adaptive, Light, Dark, and round again. The choice stays on this device. */
    private fun cycleTheme() {
        val next = when (graph.localSettings.themePreference.value) {
            ThemePreference.Adaptive -> ThemePreference.Light
            ThemePreference.Light -> ThemePreference.Dark
            ThemePreference.Dark -> ThemePreference.Adaptive
        }
        graph.localSettings.setThemePreference(next)
    }

    private fun themeName(theme: ThemePreference) = string(
        when (theme) {
            ThemePreference.Adaptive -> R.string.settings_appearance_theme_options_adaptive
            ThemePreference.Light -> R.string.settings_appearance_theme_options_light
            ThemePreference.Dark -> R.string.settings_appearance_theme_options_dark
        },
    )

    private fun themeDescription(theme: ThemePreference) = string(
        when (theme) {
            ThemePreference.Adaptive -> R.string.settings_appearance_theme_descriptions_adaptive
            ThemePreference.Light -> R.string.settings_appearance_theme_descriptions_light
            ThemePreference.Dark -> R.string.settings_appearance_theme_descriptions_dark
        },
    )

    private fun bubble(key: String) = BubblePalette.All[BUBBLE_KEYS.indexOf(key).coerceAtLeast(0)]

    private fun bubbleName(key: String) = string(
        when (key) {
            "blue" -> R.string.settings_appearance_user_message_bubble_color_options_blue
            "green" -> R.string.settings_appearance_user_message_bubble_color_options_green
            "purple" -> R.string.settings_appearance_user_message_bubble_color_options_purple
            "rose" -> R.string.settings_appearance_user_message_bubble_color_options_rose
            "sand" -> R.string.settings_appearance_user_message_bubble_color_options_sand
            else -> R.string.settings_appearance_user_message_bubble_color_options_gray
        },
    )

    private fun avatarStyleName(style: AvatarStyle) = string(
        when (style) {
            AvatarStyle.Brutalist -> R.string.settings_appearance_avatar_style_options_brutalist
            AvatarStyle.Pixelated -> R.string.settings_appearance_avatar_style_options_pixelated
            AvatarStyle.Gradient -> R.string.settings_appearance_avatar_style_options_gradient
        },
    )

    companion object {
        /** utils/userMessageBubbleColor.ts's presets, in [BubblePalette.All]'s order. */
        val BUBBLE_KEYS = listOf("gray", "blue", "green", "purple", "rose", "sand")

        /** AvatarStylePreview: every style drawn from one fixed id, so the three compare like for like. */
        const val AVATAR_PREVIEW_ID = "avatar-style-preview"
        val AVATAR_PREVIEW_SIZE = Size(28.0, 28.0)
    }
}
