package com.slopus.happy.direct

import com.slopus.happy.harness.StemCellsHarnessTransport
import com.slopus.happy.stemcells.StemCellsSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A paired computer's whole path on the harness's fake one: its identity,
 * the desktop bootstrap and stream the sync engine follows, and the Stem
 * Cells store it feeds, as a real pairing would run them.
 */
@RunWith(RobolectricTestRunner::class)
class DirectHarnessTest {
    private val fixture = File("src/debug/assets/harness/stem-cells-computer.json").readText()

    private fun TestScope.transport() = StemCellsHarnessTransport(fixture, backgroundScope, now = { NOW + testScheduler.currentTime })

    @Test fun identityReadsTheComputersName() = runTest {
        val account = DirectIdentity.refresh(DirectAccount("direct-harness", "Happy Agent"), transport(), "t")
        assertEquals("Studio Mac", account.name)
        assertEquals(DirectConnectionStatus.Connected, account.status)
    }

    @Test fun theEngineFollowsTheComputerAndFeedsStemCells() = runTest {
        val transport = transport()
        val engine = DirectSyncEngine(DirectAgentClient(transport, "t"), null, backgroundScope, EmptyCoroutineContext)
        engine.start()
        val home = withTimeout(10_000) { engine.updates.first { it.live && it.loaded } }
        assertTrue(home.usesSpaces)
        assertEquals(listOf("Bots", "Happy", "Tasks"), home.rows.map { it.label }.filter { it in setOf("Bots", "Happy", "Tasks") }.distinct())
        val spaces = withTimeout(10_000) { engine.spaces.updates.first { it.live && it.availability == StemCellsSnapshot.Availability.Available && it.rows.any { row -> row.isHappy } } }
        assertEquals("sphappy", spaces.happySpaceId)

        // A setting written here reaches the computer and comes back as its own.
        engine.setProjectsHidden(true)
        withTimeout(10_000) { engine.updates.first { DirectSettings.projectsHidden(it.settings) } }
        withTimeout(10_000) {
            while (transport.events().none { it["type"] == JsonPrimitive("user_settings.updated") }) kotlinx.coroutines.delay(50)
        }
        engine.stop()
    }

    private companion object {
        const val NOW = 1_793_000_000_000L
    }
}
