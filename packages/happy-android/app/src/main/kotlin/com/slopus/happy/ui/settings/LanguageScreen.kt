package com.slopus.happy.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.Palette
import com.slopus.happy.model.settings.Languages
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.GroupedListView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.components.ItemIcon
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.nav.BarScreen
import kotlinx.coroutines.launch

/**
 * Settings › Appearance › Language (app/(app)/settings/language.tsx,
 * happy-ios `SettingsLanguageViewController`): Automatic and every language
 * happy-app ships. The choice is synced, so happy-app on the account's other
 * devices follows it. After the same alert, where happy-app reloads, the
 * activity is made again in the new language, from the root.
 */
class LanguageScreen(context: Context, private val graph: AppGraph) : BarScreen(context) {
    private val list = GroupedListView(context)
    private var preferred: String? = graph.settings.settings.value.preferredLanguage

    override fun createContent(): View {
        bar.title = context.getString(R.string.settings_language_title)
        return list
    }

    override fun viewCreated(view: View) {
        super.viewCreated(view)
        scope.launch {
            graph.settings.settings.collect {
                preferred = it.preferredLanguage
                rebuild()
            }
        }
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea
    }

    private val selection get() = Languages.byCode(preferred)?.code

    private fun rebuild() {
        val detected = Languages.detectedName(graph.language.deviceLocales())
        val options = listOf<Triple<String?, String, String?>>(
            Triple(null, context.getString(R.string.settings_language_automatic), "${context.getString(R.string.settings_language_automatic_subtitle)} ($detected)"),
        ) + Languages.supported.map { Triple(it.code, it.nativeName, null) }
        val rows = options.map { (code, title, subtitle) ->
            ItemRowView(context, title, subtitle = subtitle, icon = ItemIcon.Glyph(Icon.ionicons("language-outline"), Palette.iconBlue)) { choose(code) }.apply {
                showsChevron = false
                if (code == selection) trailingView = SettingsCheckmark.view(context, Palette.iconBlue)
            }
        }
        list.sections = listOf(
            ItemGroupView(
                context,
                title = context.getString(R.string.settings_language_current_language),
                footer = context.getString(R.string.settings_language_description),
                rows = rows,
            ),
        )
    }

    private fun choose(code: String?) {
        if (code == selection) return
        scope.launch {
            val confirmed = Alerts.confirm(
                context,
                context.getString(R.string.settings_language_needs_restart),
                context.getString(R.string.settings_language_needs_restart_message),
                confirmTitle = context.getString(R.string.common_ok),
            )
            if (!confirmed) return@launch
            preferred = code
            graph.settings.change { it.copy(preferredLanguage = code) }
            rebuild()
            // language.tsx reloads 100 ms after saving. Android 13 and later
            // make the activity again themselves once the app's locale is
            // set; before that, it is made again here.
            graph.language.apply(code)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) activity()?.recreate()
        }
    }

    private fun activity(): Activity? {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
