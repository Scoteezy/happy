package com.slopus.happy.ui.onboarding

import android.content.Context
import android.view.View
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.RoundButtonDisplay
import com.slopus.happy.layout.RoundButtonSize
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.RoundButton
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.nav.NavigationBar
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.server.ServerScreen
import kotlinx.coroutines.CancellationException

/**
 * Welcome's screen, wherever it shows: on its own (signed out in
 * production) or under the link screen's Relay (happy-ios
 * `WelcomeViewController`'s content). Pushes through [host].
 */
class WelcomePane(context: Context, private val graph: AppGraph, private val host: Screen) {
    val view = OnboardingScreenView(
        context,
        WelcomeContentView(context).also { content = it },
        { content.layout(it).size },
        RoundButton(context, context.getString(R.string.onboarding_create_account)),
        RoundButton(context, context.getString(R.string.onboarding_restore_existing), RoundButtonSize.Normal, RoundButtonDisplay.Inverted),
    )
    private lateinit var content: WelcomeContentView

    init {
        view.primary.action = { createAccount() }
        view.secondary.onTap = { host.push(RestoreQrScreen(context, graph)) }
        view.help.onTap = { Alerts.help(context) }
    }

    private suspend fun createAccount() {
        try {
            graph.auth.createAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Alerts.show(view.context, view.context.getString(R.string.common_error), view.context.getString(R.string.errors_authentication_failed))
        }
    }
}

/** Signed out: create an account or restore one. app/(app)/index.tsx (happy-ios `WelcomeViewController`). */
class WelcomeScreen(context: Context, private val graph: AppGraph, private val offersServer: Boolean = true) : BarScreen(context) {
    private val pane = WelcomePane(context, graph, this)

    override fun createContent(): View {
        // Signed out, the gear leads straight to the server: self-hosters set it before creating an account.
        if (offersServer) {
            bar.actions = listOf(NavigationBar.Action(SETTINGS_ICON, context.getString(R.string.server_title), 22.0) { push(ServerScreen(context, graph)) })
        }
        return pane.view
    }

    override fun onAppear() {
        super.onAppear()
        bar.subtitle = if (offersServer) graph.serverConfig.customHostLabel else null
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        pane.view.safeArea = contentSafeArea
    }

    companion object {
        /** HomeHeader.tsx: settings-outline at 22. */
        val SETTINGS_ICON = Icon.ionicons("settings-outline")
    }
}
