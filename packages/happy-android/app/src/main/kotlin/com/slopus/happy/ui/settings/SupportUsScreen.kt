package com.slopus.happy.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.Palette
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.GroupedListView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.components.ItemIcon
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.nav.BarScreen

/**
 * Support us (happy-ios `SupportUsViewController`): a useful destination
 * before purchases are configured. No purchase SDK is touched and no
 * paywall, restore or network request runs on opening it; subscription
 * access stays the server's decision. happy-app's SettingsView opens its
 * hosted voluntary_support paywall; its title, subtitle, heart and
 * unavailable copy are kept here. The store is Google Play's.
 */
class SupportUsScreen(context: Context) : BarScreen(context) {
    private val list = GroupedListView(context)

    override fun createContent(): View {
        bar.title = string(R.string.settings_support_us)
        val support = ItemRowView(
            context,
            string(R.string.settings_support_us),
            subtitle = string(R.string.settings_support_us_subtitle),
            icon = ItemIcon.Glyph(Icon.ionicons("heart"), Palette.iconRed),
        )
        val subscribe = unavailableRow(string(R.string.support_us_subscribe), string(R.string.settings_support_unavailable))
        val restore = unavailableRow(string(R.string.support_us_restore), string(R.string.support_us_restore_unavailable))
        val manage = linkRow(
            string(R.string.support_us_manage),
            string(R.string.support_us_store),
            ItemIcon.Glyph(Icon.ionicons("card-outline"), Palette.iconBlue),
            MANAGE_SUBSCRIPTIONS_URL,
        )
        list.sections = listOf(
            ItemGroupView(context, footer = string(R.string.settings_support_unavailable), rows = listOf(support)),
            ItemGroupView(context, title = string(R.string.support_us_subscriptions), footer = string(R.string.support_us_status_unavailable), rows = listOf(subscribe, restore)),
            ItemGroupView(context, footer = string(R.string.support_us_manage_footer), rows = listOf(manage)),
            ItemGroupView(
                context,
                rows = listOf(
                    linkRow(string(R.string.settings_github), string(R.string.links_github_detail), ItemIcon.Glyph(Icon.ionicons("logo-github"), Palette.text), SettingsScreen.LINK_GITHUB),
                    linkRow(string(R.string.settings_privacy_policy), null, ItemIcon.Glyph(Icon.ionicons("shield-checkmark-outline"), Palette.iconBlue), SettingsScreen.LINK_PRIVACY),
                    linkRow(string(R.string.settings_terms_of_service), null, ItemIcon.Glyph(Icon.ionicons("document-text-outline"), Palette.iconBlue), SettingsScreen.LINK_TERMS),
                ),
            ),
        )
        // Applied after the groups configure their rows.
        for (row in listOf(subscribe, restore)) {
            row.isEnabled = false
            row.titleLabel.color = Palette.textSecondary
        }
        // These short groups can grow: every word stays visible at large text sizes instead of Item's usual caps.
        for (group in list.sections) {
            for (row in (group as ItemGroupView).rows) {
                (row as? ItemRowView)?.let {
                    it.titleLabel.maxLines = 0
                    it.subtitleLabel.maxLines = 0
                }
            }
        }
        return list
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea
    }

    private fun string(id: Int) = context.getString(id)

    private fun unavailableRow(title: String, explanation: String) =
        ItemRowView(context, title, subtitle = string(R.string.support_us_not_available)).apply {
            contentDescription = "$title. ${string(R.string.support_us_not_available)}. $explanation"
        }

    private fun linkRow(title: String, subtitle: String?, icon: ItemIcon, url: String) = ItemRowView(context, title, subtitle = subtitle, icon = icon) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Alerts.show(context, string(R.string.common_error), string(R.string.support_us_link_failed))
        }
    }

    companion object {
        /** Google Play's account-wide subscriptions page; it says nothing about this Happy account's status. */
        const val MANAGE_SUBSCRIPTIONS_URL = "https://play.google.com/store/account/subscriptions"
    }
}
