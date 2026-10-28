package com.slopus.happy.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.slopus.happy.AppGraph
import com.slopus.happy.auth.AuthState
import com.slopus.happy.ui.home.HomeScreen
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.nav.ScreenStack
import com.slopus.happy.ui.onboarding.WelcomeScreen
import com.slopus.happy.ui.stemcells.StemCellsLinkScreen
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the window's stack and swaps its root when the account changes
 * (happy-ios `AppCoordinator`). Signed in, home is the root; signed out, the
 * link screen in development and preview builds, Welcome in production.
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
                graph.auth.state
                    .map { (it as? AuthState.SignedIn)?.credentials?.token }
                    .distinctUntilChanged()
                    .collect { token -> show(token) }
            }
        }
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
    }

    private companion object {
        val UNSET = Any()
    }
}
