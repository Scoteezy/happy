package com.slopus.happy.ui.stemcells

import android.content.Context
import android.text.Layout
import android.view.View
import com.slopus.happy.AppGraph
import com.slopus.happy.auth.AuthState
import com.slopus.happy.connect.GmsQrScanner
import com.slopus.happy.connect.ScanResult
import com.slopus.happy.direct.*
import com.slopus.happy.layout.*
import com.slopus.happy.layout.stemcells.DirectIdentityLayout
import com.slopus.happy.ui.components.*
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.onboarding.OnboardingScreenView
import com.slopus.happy.ui.settings.PairingFlow
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.*

/** Classify Direct first, including malformed codes: never hand them to relay auth. */
object DirectPairingFlow {
    fun open(text: String, graph: AppGraph, from: Screen) {
        from.scope.launch {
            if (!DirectPairingLink.isDirect(text)) {
                if (graph.auth.state.value !is AuthState.SignedIn) {
                    Alerts.show(from.context, "Sign in to Happy", "Sign in to a Happy account before connecting this kind of computer code.")
                } else PairingFlow.report(from.view, PairingFlow.approve(graph, text, PairingFlow.Kind.Terminal), PairingFlow.Kind.Terminal)
                return@launch
            }
            if (!graph.stemCells.showsComputers) {
                if (!Alerts.confirm(from.context, "Direct connection", "Turn on the Direct connection experiment to pair this computer?", confirmTitle = "Turn on")) return@launch
                graph.stemCells.directConnection = true
            }
            try {
                from.push(DirectPairingScreen(from.context, graph, DirectPairingLink.parse(text)))
            } catch (e: DirectPairingError) {
                Alerts.show(from.context, "Could not pair", errorMessage(e))
            }
        }
    }
    fun scan(graph: AppGraph, from: Screen) {
        from.scope.launch {
            (GmsQrScanner(from.context).scan() as? ScanResult.Scanned)?.value?.let { open(it, graph, from) }
        }
    }
    fun enterLink(graph: AppGraph, from: Screen) {
        from.scope.launch {
            Alerts.prompt(from.context, "Enter link manually", "Paste the link from your computer.", placeholder = "happy://…", confirmTitle = "Continue")
                ?.trim()?.takeIf { it.isNotEmpty() }?.let { open(it, graph, from) }
        }
    }
    fun errorMessage(error: Throwable): String = when ((error as? DirectPairingError)?.kind) {
        DirectPairingError.Kind.InvalidCode -> "This computer code is not valid. Scan a new code from your computer."
        DirectPairingError.Kind.UpdateRequired -> "Update Happy to pair with this computer."
        DirectPairingError.Kind.Expired -> "This pairing code has expired. Open a new code on your computer."
        DirectPairingError.Kind.Rejected -> "Pairing was rejected. Try again with a new code."
        DirectPairingError.Kind.InvalidProof -> "The computer could not be verified. Pair again and compare all four emoji."
        DirectPairingError.Kind.Revoked -> "This computer has revoked the connection. Pair it again."
        DirectPairingError.Kind.InvalidResponse -> "The computer sent an invalid pairing response."
        else -> "The computer could not be reached. Check that it is online and try again."
    }
}

/** iOS DirectPairingViewController: no credentials are saved before both proofs match. */
class DirectPairingScreen(context: Context, private val graph: AppGraph, private val link: DirectPairingLink) : BarScreen(context) {
    private val service = DirectPairingService(link, graph.directTransport(DirectDeviceCredentials(link.address, link.port, "", "")))
    private val identity = Identity(context)
    private val screen = OnboardingScreenView(context, identity, { identity.layout(it).size },
        RoundButton(context, "Connecting…"), RoundButton(context, "Cancel", RoundButtonSize.Normal, RoundButtonDisplay.Inverted))
    private var ready = false
    private var finished = false
    private var job: Job? = null
    private var expiry: Job? = null

    override fun createContent(): View {
        bar.title = "Pair computer"
        identity.heading.text = link.name
        identity.body.text = "Name from pairing code · not verified yet\nConnecting to your computer…"
        screen.primary.isLoading = true
        screen.primary.isEnabled = false
        screen.primary.onTap = { confirm() }
        screen.secondary.onTap = { pop() }
        return screen
    }
    override fun viewCreated(view: View) {
        super.viewCreated(view)
        job = scope.launch {
            try {
                val emoji = withContext(Dispatchers.IO) { service.start(android.os.Build.MODEL) }
                identity.emoji.text = emoji.joinToString(" ")
                identity.emoji.contentDescription = "Verification emoji: ${emoji.joinToString(", ")}"
                identity.body.text = "Name from pairing code · unverified\n\nCompare these four emoji with the computer. Continue only when all four match. Confirm on both screens."
                screen.primary.title = "Emoji match"
                screen.primary.isLoading = false
                screen.primary.isEnabled = true
                screen.secondary.title = "They don’t match"
                ready = true
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
        }
        expiry = scope.launch { delay((link.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)); fail(DirectPairingError(DirectPairingError.Kind.Expired)) }
        scope.launch {
            var wasForeground = false
            graph.isForeground.collect {
                if (it) wasForeground = true
                else if (wasForeground && !finished) fail(DirectPairingError(DirectPairingError.Kind.Expired))
            }
        }
    }
    private fun confirm() {
        if (!ready || finished) return
        ready = false
        screen.primary.title = "Waiting for computer…"
        screen.primary.isEnabled = false
        screen.primary.isLoading = true
        identity.body.text = "Name from pairing code · unverified\n\nConfirm the same emoji on your computer to finish pairing."
        job = scope.launch {
            try {
                val credentials = withContext(Dispatchers.IO) { service.confirm() }
                expiry?.cancel()
                screen.primary.title = "Loading computer…"
                graph.addDirect(credentials)
                finished = true
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
        }
    }
    private fun fail(error: Throwable) {
        if (finished) return
        finished = true
        job?.cancel(); expiry?.cancel()
        cleanup()
        screen.primary.isLoading = false
        screen.primary.isEnabled = false
        screen.primary.title = "Pairing ended"
        screen.secondary.title = "Back"
        identity.body.text = DirectPairingFlow.errorMessage(error)
        identity.emoji.text = ""
    }
    private fun cleanup() {
        // Cleanup must outlive popping the screen; bounded by the service's two-second rejection.
        CoroutineScope(Dispatchers.IO).launch { service.cancel() }
    }
    override fun onDestroy() { job?.cancel(); expiry?.cancel(); cleanup() }
    override fun onInsetsChanged() { super.onInsetsChanged(); screen.safeArea = contentSafeArea }

    private class Identity(context: Context) : ManualLayout(context) {
        val heading = Label(context, TextStyles.restoreInstruction, Palette.text, alignment = Layout.Alignment.ALIGN_CENTER)
        val body = Label(context, TextStyles.restorePath, Palette.textSecondary, alignment = Layout.Alignment.ALIGN_CENTER)
        val emoji = Label(context, TextStyle(FontFace.Regular, 36.0), Palette.text, alignment = Layout.Alignment.ALIGN_CENTER)
        private val mark = HappyMarkView(context).apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setOval(0, 0, view.width, view.height)
            }
        }
        init { addView(mark); addView(heading); addView(body); addView(emoji) }
        fun layout(width: Double) = DirectIdentityLayout(width, heading.measurable, body.measurable, emoji.measurable)
        override fun sizeThatFits(width: Double, height: Double) = layout(width).size
        override fun layoutSubviews(width: Double, height: Double) {
            val layout = layout(width)
            mark.setFrame(layout.image); heading.setFrame(layout.title); body.setFrame(layout.body); emoji.setFrame(layout.emoji)
        }
    }
}