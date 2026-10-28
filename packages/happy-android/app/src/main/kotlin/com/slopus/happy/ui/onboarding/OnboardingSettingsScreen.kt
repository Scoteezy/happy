package com.slopus.happy.ui.onboarding

import android.content.Context
import android.view.View
import android.widget.Switch
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.auth.AuthState
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.Palette
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.GroupedListView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.components.ItemIcon
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.server.ServerScreen
import kotlinx.coroutines.launch

/**
 * The gear on Link Your Computer (happy-ios `OnboardingSettingsViewController`):
 * the server, the Direct connection experiment where a computer can be a
 * home, and "Log out and start over" while signed in.
 */
class OnboardingSettingsScreen(
    context: Context,
    private val graph: AppGraph,
    /** Opened from home's gear until Settings is ported: the footer, about an empty account, does not apply. */
    private val standIn: Boolean = false,
) : BarScreen(context) {
    private val list = GroupedListView(context)

    override fun createContent(): View {
        bar.title = context.getString(R.string.onboarding_settings_title)
        return list
    }

    override fun onAppear() {
        super.onAppear()
        // Rebuilt on every return, so a changed server shows.
        rebuild()
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea
    }

    private fun rebuild() {
        val server = ItemRowView(
            context,
            context.getString(R.string.onboarding_settings_server),
            detail = graph.serverConfig.customHostLabel ?: context.getString(R.string.onboarding_settings_server_default),
            icon = ItemIcon.Glyph(Icon.ionicons("server-outline"), Palette.textSecondary, 28.0),
        ) { push(ServerScreen(context, graph)) }
        val sections = mutableListOf<View>(ItemGroupView(context, rows = listOf(server)))
        if (graph.stemCells.isAvailable) {
            val toggle = Switch(context).apply {
                isChecked = graph.stemCells.directConnection
                contentDescription = "stem-settings-toggle:direct-connection"
                setOnCheckedChangeListener { _, on -> graph.stemCells.directConnection = on }
            }
            val direct = ItemRowView(
                context,
                context.getString(R.string.stem_direct_connection),
                subtitle = context.getString(R.string.stem_beta),
                icon = ItemIcon.Glyph(Icon.ionicons("flask-outline"), Palette.textSecondary, 28.0),
            ).apply { trailingView = toggle }
            sections += ItemGroupView(context, title = context.getString(R.string.stem_experiments), rows = listOf(direct))
        }
        // Signed out, there is nothing to log out of.
        if (graph.auth.state.value is AuthState.SignedIn) {
            val logout = ItemRowView(
                context,
                context.getString(R.string.onboarding_logout_start_over),
                icon = ItemIcon.Glyph(Icon.ionicons("log-out-outline"), Palette.textDestructive, 28.0),
                isDestructive = true,
            ) { logout() }
            logout.showsChevron = false
            sections += ItemGroupView(context, footer = if (standIn) null else context.getString(R.string.onboarding_logout_footer), rows = listOf(logout))
        }
        list.sections = sections
    }

    private fun logout() {
        scope.launch {
            val confirmed = Alerts.confirm(
                context,
                context.getString(R.string.onboarding_logout_confirm_title),
                context.getString(R.string.onboarding_logout_confirm_body),
                confirmTitle = context.getString(R.string.common_logout),
                destructive = true,
            )
            // The coordinator swaps back to Welcome.
            if (confirmed) graph.logout()
        }
    }
}
