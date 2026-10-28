package com.slopus.happy.ui.settings

import android.content.Context
import android.view.View
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.model.Settings
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.GroupedListView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.components.ItemIcon
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.components.TextField
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.voice.VoiceLanguages
import com.slopus.happy.voice.VoiceUsage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Settings › Voice Assistant (app/(app)/settings/voice.tsx, happy-ios
 * `VoiceSettingsViewController`): the last 30 days' usage, upgrading, the
 * voice language, and bringing your own ElevenLabs agent.
 */
class VoiceSettingsScreen(context: Context, private val graph: AppGraph) : BarScreen(context) {
    private val list = GroupedListView(context)
    private val toggles = SettingsToggleRows(context)
    private var settings: Settings = graph.settings.settings.value
    private var usage: VoiceUsage? = null
    private var loading = false
    private var usageJob: Job? = null

    override fun createContent(): View {
        bar.title = context.getString(R.string.settings_voice_assistant)
        return list
    }

    override fun viewCreated(view: View) {
        super.viewCreated(view)
        scope.launch {
            graph.settings.settings.collect {
                settings = it
                rebuild()
            }
        }
        loadUsage()
    }

    override fun onAppear() {
        super.onAppear()
        rebuild()
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea
    }

    private fun string(id: Int) = context.getString(id)

    private fun loadUsage() {
        usageJob?.cancel()
        loading = true
        rebuild()
        usageJob = scope.launch {
            usage = graph.voice.usage()
            loading = false
            rebuild()
        }
    }

    private fun rebuild() {
        val usage = usage
        val usageRows = if (usage != null) {
            listOf(
                ItemRowView(context, string(R.string.settings_voice_usage_label), detail = "${time(usage.usedSeconds)} / ${time(usage.limitSeconds)}"),
                ItemRowView(context, string(R.string.settings_voice_conversations_label), detail = "${usage.conversationCount} / ${usage.conversationLimit}"),
            )
        } else {
            listOf(
                ItemRowView(context, if (loading) string(R.string.voice_status_bar_connecting) else string(R.string.errors_voice_service_unavailable)) { loadUsage() }
                    .apply { isLoading = loading },
            )
        }
        val language = ItemRowView(
            context,
            string(R.string.settings_voice_preferred_language),
            subtitle = string(R.string.settings_voice_preferred_language_subtitle),
            detail = (VoiceLanguages.find(settings.voiceAssistantLanguage) ?: VoiceLanguages.all[0]).displayName,
            icon = ItemIcon.Glyph(Icon.ionicons("language-outline"), Palette.iconBlue),
        ) { push(VoiceLanguageScreen(context, graph)) }
        val upgrade = ItemRowView(
            context,
            string(R.string.settings_voice_support_title),
            subtitle = string(R.string.settings_voice_support_subtitle),
            icon = ItemIcon.Glyph(Icon.ionicons("heart-outline"), Palette.iconRed),
        ) { push(SupportUsScreen(context)) }
        val custom = ItemRowView(
            context,
            string(R.string.settings_voice_custom_agent_id),
            subtitle = settings.voiceCustomAgentId ?: string(R.string.settings_voice_custom_agent_id_not_set),
            icon = ItemIcon.Glyph(Icon.ionicons("key-outline"), Palette.iconOrange),
        ) { editAgent() }
        val direct = toggles.row(
            "voiceBypassToken",
            string(R.string.settings_voice_bypass_token),
            string(R.string.settings_voice_bypass_token_subtitle),
            ItemIcon.Glyph(Icon.ionicons("flash-outline"), Palette.iconRed),
            settings.voiceBypassToken,
        ) { on -> set { it.copy(voiceBypassToken = on) } }
        list.sections = listOf(
            ItemGroupView(context, title = string(R.string.settings_voice_usage_title), footer = string(R.string.settings_voice_usage_footer), rows = usageRows),
            ItemGroupView(context, rows = listOf(upgrade)),
            ItemGroupView(context, title = string(R.string.settings_voice_language_title), footer = string(R.string.settings_voice_language_description), rows = listOf(language)),
            ItemGroupView(context, title = string(R.string.settings_voice_byo_title), footer = string(R.string.settings_voice_byo_description), rows = listOf(custom, direct)),
        )
        for (group in list.sections) {
            for (row in (group as ItemGroupView).rows) {
                (row as? ItemRowView)?.let {
                    it.titleLabel.maxLines = 0
                    it.subtitleLabel.maxLines = 0
                }
            }
        }
    }

    private fun set(transform: (Settings) -> Settings) {
        settings = transform(settings)
        graph.settings.change(transform)
        rebuild()
    }

    private fun editAgent() {
        scope.launch {
            val value = Alerts.prompt(
                context,
                string(R.string.settings_voice_custom_agent_id),
                string(R.string.settings_voice_custom_agent_id_description),
                placeholder = settings.voiceCustomAgentId ?: "agent_…",
                confirmTitle = string(R.string.common_save),
            ) ?: return@launch
            val trimmed = value.trim()
            set { it.copy(voiceCustomAgentId = trimmed.ifEmpty { null }, voiceBypassToken = trimmed.isNotEmpty()) }
        }
    }

    private fun time(seconds: Int) = "${maxOf(0, seconds) / 60}m ${maxOf(0, seconds) % 60}s"
}

/**
 * Settings › Voice Assistant › Language (settings/voice/language.tsx,
 * happy-ios `VoiceLanguageViewController`): every language with a search
 * field over them; a pick is synced and the page closes.
 */
class VoiceLanguageScreen(context: Context, private val graph: AppGraph) : BarScreen(context) {
    private val list = GroupedListView(context)
    private val search = TextField(context, TextStyles.itemTitle).apply {
        hint = context.getString(R.string.settings_voice_language_search_placeholder)
        onChange = { rebuild() }
    }
    private val searchRow = SearchRow(context, search)
    private var preferred: String? = graph.settings.settings.value.voiceAssistantLanguage

    override fun createContent(): View {
        bar.title = context.getString(R.string.settings_voice_language_title)
        return list
    }

    override fun viewCreated(view: View) {
        super.viewCreated(view)
        scope.launch {
            graph.settings.settings.collect {
                preferred = it.voiceAssistantLanguage
                rebuild()
            }
        }
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea
    }

    private fun rebuild() {
        val query = search.string.trim()
        val rows = VoiceLanguages.all.filter { it.matches(query) }.map { language ->
            ItemRowView(
                context,
                language.displayName,
                subtitle = language.code,
                icon = ItemIcon.Glyph(Icon.ionicons("language-outline"), Palette.textSecondary, 24.0),
            ) {
                graph.settings.change { it.copy(voiceAssistantLanguage = language.code) }
                pop()
            }.apply {
                showsChevron = false
                if (language.code == preferred) trailingView = SettingsCheckmark.view(context, Palette.iconBlue)
            }
        }
        list.sections = listOf(
            ItemGroupView(context, rows = listOf(searchRow)),
            ItemGroupView(context, footer = context.resources.getQuantityString(R.plurals.settings_voice_language_footer, rows.size, rows.size), rows = rows),
        )
    }

    /** The search field in a card of its own, as a list's search bar. */
    private class SearchRow(context: Context, private val field: TextField) : ManualLayout(context) {
        private val icon = com.slopus.happy.ui.components.IconView(context, Icon.ionicons("search"), 18.0, Palette.textSecondary)

        init {
            addView(icon)
            addView(field)
        }

        override fun sizeThatFits(width: Double, height: Double) = com.slopus.happy.layout.Size(width, 44.0)

        override fun layoutSubviews(width: Double, height: Double) {
            icon.setFrame(Rect(16.0, (height - 18) / 2, 18.0, 18.0))
            field.setFrame(Rect(44.0, 0.0, maxOf(0.0, width - 60), height))
        }
    }
}
