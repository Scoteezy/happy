package com.slopus.happy.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallStateTest {
    private val disconnected = VoiceState()

    @Test fun theLiveStateWinsWhileACallIsStartingOrOpen() {
        assertEquals(VoiceCallState.Connecting, VoiceCallState.of(VoiceState(VoiceStatus.Connecting), VoiceStart.Unavailable))
        assertEquals(
            VoiceCallState.Active(VoiceCallState.Activity.Listening, muted = false),
            VoiceCallState.of(VoiceState(VoiceStatus.Connected), null),
        )
        assertEquals(
            VoiceCallState.Active(VoiceCallState.Activity.AgentSpeaking, muted = true),
            VoiceCallState.of(VoiceState(VoiceStatus.Connected, VoiceMode.AgentSpeaking, muted = true), null),
        )
        assertEquals(
            VoiceCallState.Active(VoiceCallState.Activity.UserSpeaking, muted = false),
            VoiceCallState.of(VoiceState(VoiceStatus.Connected, VoiceMode.UserSpeaking), null),
        )
    }

    @Test fun onceDisconnectedTheLastStartSaysWhy() {
        assertEquals(VoiceCallState.Ended, VoiceCallState.of(disconnected, null))
        assertEquals(VoiceCallState.Ended, VoiceCallState.of(disconnected, VoiceStart.Started("c")))
        assertEquals(
            VoiceCallState.Failed(VoiceCallState.Failure.MicrophonePermission),
            VoiceCallState.of(disconnected, VoiceStart.MicrophoneDenied(canAskAgain = false)),
        )
        assertEquals(VoiceCallState.Failed(VoiceCallState.Failure.Authentication), VoiceCallState.of(disconnected, VoiceStart.NotSignedIn))
        assertEquals(VoiceCallState.Failed(VoiceCallState.Failure.Unavailable), VoiceCallState.of(disconnected, VoiceStart.Unavailable))
        assertEquals(VoiceCallState.Denied(VoiceCallState.Reason.ConversationLimitReached), VoiceCallState.of(disconnected, VoiceStart.LimitReached))
        assertEquals(VoiceCallState.Denied(VoiceCallState.Reason.SubscriptionRequired), VoiceCallState.of(disconnected, VoiceStart.Declined))
        assertEquals(VoiceCallState.Failed(VoiceCallState.Failure.Unavailable), VoiceCallState.of(VoiceState(VoiceStatus.Error), null))
    }

    @Test fun onlyAStartingOrOpenCallIsRunning() {
        assertTrue(VoiceCallState.Connecting.isRunning)
        assertTrue(VoiceCallState.Active(VoiceCallState.Activity.Listening, false).isRunning)
        assertFalse(VoiceCallState.Ended.isRunning)
        assertFalse(VoiceCallState.Failed(VoiceCallState.Failure.Unavailable).isRunning)
    }
}
