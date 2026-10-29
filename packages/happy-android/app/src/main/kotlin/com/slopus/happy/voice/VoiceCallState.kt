package com.slopus.happy.voice

/**
 * What the voice screen and the composer's status row show (happy-ios
 * `VoiceState` as VoiceStatusViewController draws it): the engine's live
 * state while a call is starting or open, and once it is not, how the last
 * start ended.
 */
sealed interface VoiceCallState {
    /** No call: none was started, or the last one ended. */
    data object Ended : VoiceCallState

    data object Connecting : VoiceCallState

    data class Active(val activity: Activity, val muted: Boolean) : VoiceCallState

    /** The server would not give a call. */
    data class Denied(val reason: Reason) : VoiceCallState

    data class Failed(val failure: Failure) : VoiceCallState

    enum class Activity { Listening, UserSpeaking, AgentSpeaking }

    enum class Reason {
        /** The free time is used and the paywall was closed without buying: Upgrade Voice is offered. */
        SubscriptionRequired,
        ConversationLimitReached,
    }

    enum class Failure { MicrophonePermission, Authentication, Unavailable }

    /** Connecting or open: the composer's status row says so and reopens the screen. */
    val isRunning: Boolean get() = this is Connecting || this is Active

    companion object {
        /** [outcome] is the last start's, null while one is on its way or none was made. */
        fun of(state: VoiceState, outcome: VoiceStart?): VoiceCallState = when (state.status) {
            VoiceStatus.Connecting -> Connecting
            VoiceStatus.Connected -> Active(
                when (state.mode) {
                    VoiceMode.Idle -> Activity.Listening
                    VoiceMode.UserSpeaking -> Activity.UserSpeaking
                    VoiceMode.AgentSpeaking -> Activity.AgentSpeaking
                },
                state.muted,
            )
            VoiceStatus.Error -> Failed(Failure.Unavailable)
            VoiceStatus.Disconnected -> when (outcome) {
                null, is VoiceStart.Started -> Ended
                is VoiceStart.MicrophoneDenied -> Failed(Failure.MicrophonePermission)
                VoiceStart.NotSignedIn -> Failed(Failure.Authentication)
                VoiceStart.LimitReached -> Denied(Reason.ConversationLimitReached)
                VoiceStart.Declined -> Denied(Reason.SubscriptionRequired)
                VoiceStart.Unavailable -> Failed(Failure.Unavailable)
            }
        }
    }
}
