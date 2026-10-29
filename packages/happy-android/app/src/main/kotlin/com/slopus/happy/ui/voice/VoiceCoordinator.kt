package com.slopus.happy.ui.voice

import android.content.Context
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.voice.MicrophonePermission
import com.slopus.happy.voice.VoiceCallState
import com.slopus.happy.voice.VoiceStart
import com.slopus.happy.voice.VoiceStatus
import com.slopus.happy.ui.nav.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * happy-ios `VoiceCoordinator`: the composer's microphone starts a call
 * for its chat and opens [VoiceStatusScreen] over it. The call outlives the
 * screen (closing it leaves the call running; the composer's status row
 * opens it again) and the chat: it ends from the screen, on sign-out
 * (AppGraph's account switch stops the engine) and when Happy goes to the
 * background. A press while a call runs refocuses it on that chat.
 */
class VoiceCoordinator private constructor(private val graph: AppGraph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** How the last start ended; null while one is on its way or none was made. */
    private val outcome = MutableStateFlow<VoiceStart?>(null)
    private var starting: Job? = null
    private var screen: VoiceStatusScreen? = null

    /** The activity's permission prompt, set while it exists. */
    var microphone: MicrophonePermission? = null

    val state: StateFlow<VoiceCallState> = combine(graph.voice.state, outcome, VoiceCallState::of)
        .stateIn(scope, SharingStarted.Eagerly, VoiceCallState.Ended)

    /** The voice part of the composer's status row ("Connecting…", "Listening", …); null with no call. */
    fun statusText(context: Context): String? = when (val state = state.value) {
        VoiceCallState.Connecting -> context.getString(R.string.voice_status_bar_connecting)
        is VoiceCallState.Active -> context.getString(activityText(state))
        else -> null
    }

    fun start(sessionId: String, presenter: Screen) {
        if (state.value.isRunning) {
            focus(sessionId)
            show(presenter)
            return
        }
        val microphone = microphone ?: return
        starting?.cancel()
        outcome.value = null
        show(presenter)
        starting = scope.launch { outcome.value = graph.voice.start(sessionId, microphone) }
    }

    /** voiceHooks.onSessionFocus: a chat came on screen; a running call takes its context. */
    fun focus(sessionId: String) {
        if (state.value.isRunning) graph.voice.focus(sessionId)
    }

    fun stop() {
        starting?.cancel()
        starting = null
        outcome.value = null
        scope.launch { graph.voice.stop() }
    }

    fun toggleMute() = graph.voice.setMuted(!graph.voice.state.value.muted)

    fun openMicrophoneSettings() = microphone?.openSettings()

    /**
     * Happy left the foreground. iOS ends the call there; here only an open
     * call ends, since a start may be showing the purchase screen, which is
     * an activity of its own.
     */
    fun backgrounded() {
        if (graph.voice.state.value.status == VoiceStatus.Connected) stop()
    }

    private fun show(presenter: Screen) {
        val open = screen
        if (open != null && open.stack != null) return
        val made = VoiceStatusScreen(presenter.context, this)
        screen = made
        presenter.push(made)
    }

    companion object {
        private val instances = WeakHashMap<AppGraph, VoiceCoordinator>()

        fun of(graph: AppGraph): VoiceCoordinator = synchronized(instances) { instances.getOrPut(graph) { VoiceCoordinator(graph) } }

        fun activityText(state: VoiceCallState.Active): Int = when {
            state.muted -> R.string.voice_call_muted
            state.activity == VoiceCallState.Activity.AgentSpeaking -> R.string.voice_call_agent_speaking
            state.activity == VoiceCallState.Activity.UserSpeaking -> R.string.voice_call_user_speaking
            else -> R.string.voice_call_listening
        }
    }
}
