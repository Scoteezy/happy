package com.slopus.happy.connect

import android.content.Intent
import com.slopus.happy.auth.TERMINAL_URL_PREFIX
import com.slopus.happy.crypto.AccountLink
import com.slopus.happy.push.ACTION_OPEN_PUSH
import com.slopus.happy.push.EXTRA_PUSH_SESSION_ID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the app is handed from outside (happy-ios `SceneDelegate`). */
sealed interface DeepLink {
    data class Direct(val link: String) : DeepLink
    /** `happy://terminal?<key>` or `happy:///account?<key>`: a pairing to confirm and approve. */
    data class Pairing(val link: String, val isTerminal: Boolean) : DeepLink

    /** A tapped notification's session. */
    data class Session(val id: String) : DeepLink
}

/**
 * happy-ios `PairingLink`: the app's `happy` links are the two pairing
 * links and nothing else; every other link is not the app's to open, as
 * iOS's `AppCoordinator.open` turns it down.
 */
fun parseDeepLink(link: String): DeepLink? {
    val text = link.trim()
    return when {
        com.slopus.happy.direct.DirectPairingLink.isDirect(text) -> DeepLink.Direct(text)
        text.startsWith(TERMINAL_URL_PREFIX) -> DeepLink.Pairing(text, isTerminal = true)
        text.startsWith(AccountLink.URL_PREFIX) -> DeepLink.Pairing(text, isTerminal = false)
        else -> null
    }
}

/**
 * Links handed to the app from outside, waiting for the UI to take them.
 * MainActivity offers every launching and new intent (links, and taps on
 * push notifications, see [com.slopus.happy.push.pushTapIntent]);
 * `AppCoordinator` takes them once there is a root to open them on.
 */
object DeepLinks {
    private val inbox = MutableStateFlow<DeepLink?>(null)

    val pending: StateFlow<DeepLink?> = inbox.asStateFlow()

    /** Takes a VIEW intent's link or a notification tap's session. True if it was one the app handles. */
    fun offer(intent: Intent?): Boolean {
        if (intent?.action == ACTION_OPEN_PUSH) {
            val sessionId = intent.getStringExtra(EXTRA_PUSH_SESSION_ID) ?: return false
            offer(DeepLink.Session(sessionId))
            return true
        }
        if (intent?.action != Intent.ACTION_VIEW) return false
        val link = intent.dataString?.let(::parseDeepLink) ?: return false
        offer(link)
        return true
    }

    fun offer(link: DeepLink) {
        inbox.value = link
    }

    /** Takes [link] off the inbox if it is still the pending one. */
    fun consume(link: DeepLink): Boolean = inbox.compareAndSet(link, null)
}
