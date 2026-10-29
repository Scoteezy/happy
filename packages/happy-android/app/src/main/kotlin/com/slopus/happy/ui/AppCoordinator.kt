package com.slopus.happy.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.auth.AuthState
import com.slopus.happy.connect.DeepLink
import com.slopus.happy.connect.DeepLinks
import com.slopus.happy.ui.chat.ChatScreen
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.home.HomeScreen
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.nav.ScreenStack
import com.slopus.happy.ui.onboarding.WelcomeScreen
import com.slopus.happy.ui.settings.PairingFlow
import com.slopus.happy.ui.stemcells.StemCellsLinkScreen
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the window's stack and swaps its root when the account changes
 * (happy-ios `AppCoordinator`). Signed in, home is the root; signed out, the
 * link screen in development and preview builds, Welcome in production.
 * Pairing links and tapped notifications land here too ([DeepLinks]).
 */
class AppCoordinator(private val activity: ComponentActivity, private val graph: AppGraph) {
    val root = ScreenStack(activity)

    /** The account the root was built for; null while signed out, unset before the first. */
    private var rootAccount: Any? = UNSET

    fun start() {
        Theme.install(activity)
        Theme.setPreference(graph.localSettings.themePreference.value)
        activity.onBackPressedDispatcher.addCallback(activity, root.backCallback)
        activity.lifecycleScope.launch {
            graph.localSettings.themePreference.collect { preference ->
                Theme.setPreference(preference)
                Theme.applyTree(root)
                applySystemBars()
            }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.CREATED) {
                graph.storeAccount
                    .map { (it as? AuthState.SignedIn)?.credentials?.token }
                    .distinctUntilChanged()
                    .collect { token -> show(token) }
            }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DeepLinks.pending.filterNotNull().collect(::open)
            }
        }
    }

    private fun open(link: DeepLink) {
        when (link) {
            is DeepLink.Pairing -> if (DeepLinks.consume(link)) openPairing(link)
            is DeepLink.Session -> openNotificationSession(link)
        }
    }

    /**
     * happy-ios `AppCoordinator.open`: asks first, since a link can come from
     * anywhere and approving it hands a terminal or a device the account.
     */
    private fun openPairing(link: DeepLink.Pairing) {
        if (graph.auth.state.value !is AuthState.SignedIn) {
            Alerts.show(activity, activity.getString(R.string.common_error), activity.getString(R.string.modals_failed_to_connect_terminal))
            return
        }
        val kind = if (link.isTerminal) PairingFlow.Kind.Terminal else PairingFlow.Kind.Account
        activity.lifecycleScope.launch {
            val confirmed = Alerts.confirm(
                activity,
                activity.getString(if (link.isTerminal) R.string.modals_authenticate_terminal else R.string.settings_account_link_new_device),
                confirmTitle = activity.getString(R.string.common_continue),
            )
            if (!confirmed || graph.auth.state.value !is AuthState.SignedIn) return@launch
            PairingFlow.report(root, PairingFlow.approve(graph, link.link, kind), kind)
        }
    }

    /**
     * happy-ios `openNotificationSession` and `showNotificationSession`: home,
     * then the chat; nothing changes when that chat is already on top. Signed
     * out there is nothing to open it in; a root still to come opens it once in.
     */
    private fun openNotificationSession(link: DeepLink.Session) {
        if (graph.auth.state.value !is AuthState.SignedIn) {
            DeepLinks.consume(link)
            return
        }
        val home = root.all.firstOrNull() as? HomeScreen ?: return
        if (!DeepLinks.consume(link)) return
        if ((root.top as? ChatScreen)?.sessionId == link.id) return
        root.setScreens(listOf(home, ChatScreen(activity, graph, link.id)), animated = true)
    }

    /** The system's configuration changed (dark mode, font scale, density) without the activity restarting. */
    fun configurationChanged(@Suppress("UNUSED_PARAMETER") config: Configuration) {
        if (Theme.configurationChanged(activity)) {
            Theme.applyTree(root)
            root.requestLayout()
            applySystemBars()
        }
    }

    /** Dark icons on a light screen and light on a dark one, whatever the system's own mode. */
    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(activity.window, root)
        controller.isAppearanceLightStatusBars = !Theme.isDark
        controller.isAppearanceLightNavigationBars = !Theme.isDark
    }

    private fun show(token: String?) {
        if (rootAccount != UNSET && rootAccount == token) return
        val first = rootAccount == UNSET
        rootAccount = token
        val screen: Screen = when {
            token != null -> HomeScreen(activity, graph)
            graph.stemCells.isAvailable -> StemCellsLinkScreen(activity, graph)
            else -> WelcomeScreen(activity, graph)
        }
        root.setScreens(listOf(screen), animated = !first)
        DeepLinks.pending.value?.let(::open)
    }

    private companion object {
        val UNSET = Any()
    }
}
