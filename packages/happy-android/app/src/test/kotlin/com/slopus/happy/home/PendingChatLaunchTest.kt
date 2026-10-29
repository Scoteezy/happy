package com.slopus.happy.home

import com.slopus.happy.model.NewSessionDraft
import com.slopus.happy.model.PendingChatStatus
import com.slopus.happy.model.SessionStartRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// After happy-ios Tests/HappyCoreTests/PendingChatLaunchTests.swift: the
// strip's `+`, its stand-in's writing, and the handover when the machine answers.

@OptIn(ExperimentalCoroutinesApi::class)
class PendingChatLaunchTest {
    private val request = SessionStartRequest(NewSessionDraft())

    private class Sessions(drafts: Map<String, String> = emptyMap(), private val refuses: String? = null) : PendingChatSessions {
        val drafts = drafts.toMutableMap()
        val sent = mutableListOf<String>()
        val alerts = mutableListOf<String>()

        override suspend fun send(sessionId: String, text: String): Boolean {
            if (text == refuses) throw IllegalStateException("Session not ready")
            sent += text
            return true
        }

        override fun draft(sessionId: String): String? = drafts[sessionId]

        override suspend fun writeDraft(sessionId: String, text: String) {
            drafts[sessionId] = text
        }

        override fun exists(sessionId: String) = sessionId == "anchor"

        override fun alert(message: String) {
            alerts += message
        }
    }

    @Test fun theComposerWritesEveryKeystrokeOntoTheStandIn() {
        val chats = PendingChatStore()
        val chat = chats.open("anchor", listOf("anchor"))
        chats.setDraft(chat.id, "h")
        chats.setDraft(chat.id, "hello")
        assertEquals("hello", chats[chat.id]?.draft)
        // A send waits in line and clears the draft.
        chats.submit(chat.id, "hello")
        assertEquals(listOf("hello"), chats[chat.id]?.queued)
        assertEquals("", chats[chat.id]?.draft)
        // Once named, the session's own composer owns the text.
        chats.settle(chat.id, "real")
        chats.setDraft(chat.id, "late")
        chats.submit(chat.id, "late")
        assertEquals("", chats[chat.id]?.draft)
        assertEquals(listOf("hello"), chats[chat.id]?.queued)
    }

    @Test fun aStartThatOpensHandsTheWritingOverThenNamesTheChat() = runTest {
        val chats = PendingChatStore()
        val sessions = Sessions()
        val gate = CompletableDeferred<Unit>()
        val starter = PendingChatStarter(chats, PendingChatHandover(chats, sessions), this, start = {
            gate.await()
            StartResult.Started("real")
        }, failed = {})
        val chat = starter.open("anchor", listOf("anchor"), request)
        // Typed and sent while the machine is still thinking.
        chats.submit(chat.id, "first message")
        chats.setDraft(chat.id, "still typing")
        runCurrent()
        assertNull(chats[chat.id]?.sessionId)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("first message"), sessions.sent)
        assertEquals("still typing", sessions.drafts["real"])
        assertEquals("real", chats[chat.id]?.sessionId)
    }

    @Test fun aStartThatFailsGivesTheWritingBackAheadOfTheAnchorsDraft() = runTest {
        val chats = PendingChatStore()
        val sessions = Sessions(drafts = mapOf("anchor" to "the other thought"))
        val failures = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val starter = PendingChatStarter(chats, PendingChatHandover(chats, sessions), this, start = {
            gate.await()
            StartResult.Failed("Machine said no")
        }, failed = { failures += it })
        val chat = starter.open("anchor", listOf("anchor"), request)
        chats.submit(chat.id, "sent too soon")
        chats.setDraft(chat.id, "typing")
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("sent too soon\n\ntyping\n\nthe other thought", sessions.drafts["anchor"])
        assertEquals(PendingChatStatus.Failed, chats[chat.id]?.status)
        assertNull(chats[chat.id]?.sessionId)
        assertEquals(emptyList<String>(), sessions.sent)
        assertEquals(listOf("Machine said no"), failures)
    }

    @Test fun aFirstMessageTheSessionRefusesIsSaidAndKept() = runTest {
        val chats = PendingChatStore()
        val sessions = Sessions(refuses = "first")
        val gate = CompletableDeferred<Unit>()
        val starter = PendingChatStarter(chats, PendingChatHandover(chats, sessions), this, start = {
            gate.await()
            StartResult.Started("real")
        }, failed = {})
        val chat = starter.open("anchor", emptyList(), request)
        chats.submit(chat.id, "first")
        chats.submit(chat.id, "second")
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("Session not ready"), sessions.alerts)
        // The second never overtakes the first: both go back into the draft.
        assertEquals("first\n\nsecond", sessions.drafts["real"])
        assertEquals("real", chats[chat.id]?.sessionId)
    }

    @Test fun startsRunOneAtATimeInTheOrderAsked() = runTest {
        val chats = PendingChatStore()
        val sessions = Sessions()
        val gate = CompletableDeferred<Unit>()
        val asked = mutableListOf<String>()
        var next = 0
        val starter = PendingChatStarter(chats, PendingChatHandover(chats, sessions), this, start = {
            next += 1
            asked += "start $next"
            gate.await()
            StartResult.Started(if (next == 1) "one" else "two")
        }, failed = {})
        val first = starter.open("anchor", emptyList(), request)
        val second = starter.open("anchor", emptyList(), request)
        runCurrent()
        // Both tabs exist at once; only the first start is running.
        assertEquals(listOf("start 1"), asked)
        assertEquals(PendingChatStatus.Starting, chats[second.id]?.status)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("start 1", "start 2"), asked)
        assertEquals("one", chats[first.id]?.sessionId)
        assertEquals("two", chats[second.id]?.sessionId)
    }

    @Test fun aStartThatIsDeclinedRetiresTheStandInWithoutAWord() = runTest {
        val chats = PendingChatStore()
        val failures = mutableListOf<String>()
        val starter = PendingChatStarter(chats, PendingChatHandover(chats, Sessions()), this, start = { StartResult.Abandoned }, failed = { failures += it })
        val chat = starter.open("anchor", emptyList(), request)
        advanceUntilIdle()
        assertEquals(PendingChatStatus.Failed, chats[chat.id]?.status)
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun openingAnotherSweepsTheFailedOnes() {
        val chats = PendingChatStore()
        val failed = chats.open("anchor", emptyList())
        chats.fail(failed.id)
        val next = chats.open("anchor", emptyList())
        assertNull(chats[failed.id])
        assertEquals(next, chats[next.id])
    }
}
