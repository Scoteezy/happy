package com.slopus.happy.ui.settings

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.crypto.AccountLink
import com.slopus.happy.auth.AuthState
import com.slopus.happy.auth.InvalidLinkException
import com.slopus.happy.auth.TERMINAL_URL_PREFIX
import com.slopus.happy.auth.TerminalNeedsUpdateException
import com.slopus.happy.auth.approveAccountLink
import com.slopus.happy.auth.approveTerminal
import com.slopus.happy.connect.GmsQrScanner
import com.slopus.happy.connect.acceptScan
import com.slopus.happy.ui.components.Alerts
import kotlinx.coroutines.CancellationException

/**
 * Scanning and answering pairing codes: connecting a terminal
 * (useConnectTerminal.ts) and linking another device (useConnectAccount.ts)
 * (happy-ios `PairingFlow`).
 */
object PairingFlow {
    enum class Kind(val prefix: String) {
        Terminal(TERMINAL_URL_PREFIX),
        Account(AccountLink.URL_PREFIX),
    }

    enum class Outcome {
        Approved,
        /** Not a pairing link of the expected kind. */
        InvalidLink,
        /** A terminal that only speaks auth v1. */
        TerminalNeedsUpdate,
        Failed,
    }

    /** The camera's first code of [kind]; null when it was closed. Other codes are ignored, as happy-app's scanner ignores them. */
    suspend fun scan(context: Context, kind: Kind): String? = acceptScan(GmsQrScanner(context).scan(), kind.prefix)

    /** Approves a scanned or pasted link of the expected kind. */
    suspend fun approve(graph: AppGraph, text: String, kind: Kind): Outcome {
        val link = text.trim()
        if (!link.startsWith(kind.prefix)) return Outcome.InvalidLink
        val signedIn = graph.auth.state.value as? AuthState.SignedIn ?: return Outcome.Failed
        return try {
            when (kind) {
                Kind.Terminal -> approveTerminal(graph.authApi, signedIn.credentials, signedIn.encryption, link)
                Kind.Account -> approveAccountLink(graph.authApi, signedIn.credentials, link)
            }
            Outcome.Approved
        } catch (e: CancellationException) {
            throw e
        } catch (_: InvalidLinkException) {
            Outcome.InvalidLink
        } catch (_: TerminalNeedsUpdateException) {
            Outcome.TerminalNeedsUpdate
        } catch (_: Exception) {
            Outcome.Failed
        }
    }

    /**
     * What happy-app tells the person. A connected terminal is felt, not
     * announced: the computer turns up on screen. A linked device and every
     * failure get an alert.
     */
    fun report(view: View, outcome: Outcome, kind: Kind) {
        val context = view.context
        val error = context.getString(R.string.common_error)
        when (outcome) {
            Outcome.Approved -> when (kind) {
                Kind.Terminal -> view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                Kind.Account -> Alerts.show(context, context.getString(R.string.common_success), context.getString(R.string.modals_device_linked_successfully))
            }
            Outcome.InvalidLink -> Alerts.show(context, error, context.getString(R.string.modals_invalid_auth_url))
            Outcome.TerminalNeedsUpdate -> Alerts.show(context, error, context.getString(R.string.modals_terminal_needs_update))
            Outcome.Failed -> Alerts.show(
                context,
                error,
                context.getString(if (kind == Kind.Terminal) R.string.modals_failed_to_connect_terminal else R.string.modals_failed_to_link_device),
            )
        }
    }
}

/**
 * What Settings' connection rows do (SettingsView.tsx, useConnectTerminal.ts),
 * for whichever settings page shows them: connecting a terminal by its code
 * or its URL, and connecting or disconnecting Claude and GitHub. Each returns
 * once it is over; the page keeps its own busy state (happy-ios
 * `SettingsConnections`).
 */
object SettingsConnections {
    /** A terminal's URL, pasted; null when nothing was. */
    suspend fun promptTerminalUrl(context: Context): String? = Alerts.prompt(
        context,
        context.getString(R.string.modals_authenticate_terminal),
        context.getString(R.string.modals_paste_url_from_terminal),
        placeholder = "happy://terminal?...",
        confirmTitle = context.getString(R.string.common_authenticate),
    )?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun approveTerminal(graph: AppGraph, text: String, view: View) {
        PairingFlow.report(view, PairingFlow.approve(graph, text, PairingFlow.Kind.Terminal), PairingFlow.Kind.Terminal)
    }

    suspend fun disconnectClaude(graph: AppGraph, context: Context) {
        val service = "Claude"
        val confirmed = Alerts.confirm(
            context,
            context.getString(R.string.modals_disconnect_service, service),
            context.getString(R.string.modals_disconnect_service_confirm, service),
            confirmTitle = context.getString(R.string.modals_disconnect),
            destructive = true,
        )
        if (!confirmed) return
        attempt(context) {
            graph.connect.disconnectService("anthropic")
            graph.account.value?.syncNow()
        }
    }

    suspend fun connectGitHub(graph: AppGraph, context: Context) {
        attempt(context) { Alerts.open(context, graph.connect.gitHubOAuthUrl()) }
    }

    suspend fun disconnectGitHub(graph: AppGraph, context: Context) {
        val confirmed = Alerts.confirm(
            context,
            context.getString(R.string.modals_disconnect_github),
            context.getString(R.string.modals_disconnect_github_confirm),
            confirmTitle = context.getString(R.string.modals_disconnect),
            destructive = true,
        )
        if (!confirmed) return
        // The server also sends an account update; pulling the profile covers a socket that is down.
        attempt(context) {
            graph.connect.disconnectGitHub()
            graph.account.value?.syncNow()
        }
    }

    /** useHappyAction.ts's catch-all: any failure reads "Unknown error". */
    suspend fun attempt(context: Context, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            unknownError(context)
        }
    }

    fun unknownError(context: Context) =
        Alerts.show(context, context.getString(R.string.common_error), context.getString(R.string.happy_action_unknown_error))
}
