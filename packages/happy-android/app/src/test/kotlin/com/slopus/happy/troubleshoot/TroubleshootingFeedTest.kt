package com.slopus.happy.troubleshoot

import com.slopus.happy.data.TroubleshootingMachine
import com.slopus.happy.data.TroubleshootingSnapshot
import com.slopus.happy.sync.MachinePresence
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TroubleshootingFeedTest {
    private val machine = TroubleshootingMachine("m", 1, false, 100, "Laptop", "host", null, false, "/logs")
    private fun snapshot(project: String = "project") = TroubleshootingSnapshot(listOf(machine), TroubleshootSession(100, "m", path = "/work/$project"))

    @Test fun sevenVisibleSubscribersShareOneReadAndReuseComputerDerivation() = runTest {
        val owner = SupervisorJob()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(owner + dispatcher)
        val source = MutableStateFlow(snapshot())
        var reads = 0
        val feed = TroubleshootingFeed(source.onEach { reads++ }, MutableStateFlow(emptyMap()), scope, dispatcher)
        val results = List(7) { mutableListOf<TroubleshootState>() }
        val consumers = results.map { result -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { feed.snapshots().toList(result) } }
        try {
            runCurrent()
            assertTrue(results.all { it.size == 1 && it.single().aiPrompt.contains("/logs") })
            assertEquals(1, reads)
            assertEquals(1, feed.computerDerivationCount)
            source.value = snapshot("changed")
            runCurrent()
            assertTrue(results.all { it.size == 2 && it.last().aiPrompt.contains("changed") })
            assertEquals(1, feed.computerDerivationCount)
            consumers.forEach { it.cancel() }
            runCurrent()
            source.value = snapshot("not-visible")
            runCurrent()
            assertEquals(2, reads)
        } finally { owner.cancel(); runCurrent() }
    }

    @Test fun unchangedPromptAndUnrenderedTimestampDoNotPublish() = runTest {
        val owner = SupervisorJob()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = MutableStateFlow(snapshot())
        val feed = TroubleshootingFeed(source, MutableStateFlow(emptyMap()), CoroutineScope(owner + dispatcher), dispatcher)
        val states = mutableListOf<TroubleshootState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { feed.snapshots().toList(states) }
        try {
            runCurrent()
            source.value = snapshot().copy(recentSession = snapshot().recentSession!!.copy(updatedAt = 200))
            runCurrent()
            assertEquals(1, states.size)
            assertEquals(1, feed.computerDerivationCount)
        } finally { owner.cancel(); runCurrent() }
    }

    @Test fun readFailureRetainsLastStateAndNextSubscriptionRecovers() = runTest {
        val owner = SupervisorJob()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = MutableStateFlow(snapshot())
        var fails = false
        val feed = TroubleshootingFeed(source.map { if (fails) error("projection failed") else it }, MutableStateFlow(emptyMap()),
            CoroutineScope(owner + dispatcher), dispatcher)
        val states = mutableListOf<TroubleshootState>()
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { feed.snapshots().toList(states) }
        try {
            runCurrent()
            fails = true
            source.value = snapshot("recovered")
            runCurrent()
            assertTrue(first.isCompleted)
            assertEquals(1, states.size)
            fails = false
            val next = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { feed.snapshots().toList(states) }
            runCurrent()
            assertEquals(2, states.size)
            assertTrue(states.last().aiPrompt.contains("recovered"))
            owner.cancel()
            runCurrent()
            assertTrue(next.isCompleted)
            assertTrue(feed.snapshots().toList().isEmpty())
        } finally { owner.cancel(); runCurrent() }
    }

    @Test fun accountStopEndsObserversAndPresenceDoesNotExpireByWallClock() = runTest {
        val owner = SupervisorJob()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val presence = MutableStateFlow<Map<String, MachinePresence>>(emptyMap())
        val feed = TroubleshootingFeed(MutableStateFlow(snapshot()), presence, CoroutineScope(owner + dispatcher), dispatcher)
        val states = mutableListOf<TroubleshootState>()
        val consumer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { feed.snapshots().toList(states) }
        try {
            runCurrent()
            assertFalse(states.single().computers.single().online)
            presence.value = mapOf("m" to MachinePresence(active = true, activeAt = 1))
            runCurrent()
            assertTrue(states.last().computers.single().online)
            owner.cancel()
            runCurrent()
            assertTrue(consumer.isCompleted)
            presence.value = emptyMap()
            runCurrent()
            assertEquals(2, states.size)
        } finally { owner.cancel(); runCurrent() }
    }
}