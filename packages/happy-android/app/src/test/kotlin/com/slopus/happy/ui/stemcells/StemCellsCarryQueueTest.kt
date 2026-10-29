package com.slopus.happy.ui.stemcells

import com.slopus.happy.direct.*
import com.slopus.happy.harness.StemCellsHarnessTransport
import com.slopus.happy.stemcells.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StemCellsCarryQueueTest {
    @Test fun cancellingTheCarryReleasesRemoteEditsWithoutMovingAnything() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = StemCellsHarnessTransport(File("src/debug/assets/harness/stem-cells-computer.json").readText(), backgroundScope)
        val client = DirectAgentClient(fixture, "fixture")
        val engine = DirectSyncEngine(client, null, backgroundScope, EmptyCoroutineContext)
        val session = StemCellsSession.session("carry-cancel-test", engine)
        try {
            session.store.start()
            val ready = withTimeout(60_000) { session.store.updates.first { it.live && it.document != null } }
            session.setDragging(true)
            runCurrent()
            client.applySidebarOperations("remote-on-cancel", client.sidebar(false).version,
                listOf(SpaceSidebarOperation.Set(SpaceSidebarSet("personal", label = "Remote personal"))))
            runCurrent()
            assertEquals("Personal", session.store.updates.value.rows.first { it.nodeId == "personal" }.label)
            session.setDragging(false)
            val released = withTimeout(60_000) { session.store.updates.first { it.rows.any { row -> row.label == "Remote personal" } } }
            assertEquals(ready.rows.map { it.nodeId }, released.rows.map { it.nodeId })
        } finally {
            session.store.stop()
            StemCellsSession.forget("carry-cancel-test")
            Dispatchers.resetMain()
        }
    }

    @Test fun remoteDocumentWaitsForTheQueuedDropAndRelease() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = StemCellsHarnessTransport(File("src/debug/assets/harness/stem-cells-computer.json").readText(), backgroundScope)
        val releaseWrite = CompletableDeferred<Unit>()
        var blockWrite = false
        var reads = 0
        val transport = object : DirectTransport by fixture {
            override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
                if (method == "GET" && path.startsWith("/v0/spaces/sidebar")) reads++
                if (blockWrite && method == "POST" && path == "/v0/spaces/sidebar/operations") releaseWrite.await()
                return fixture.request(method, path, credential, body, headers)
            }
        }
        val engine = DirectSyncEngine(DirectAgentClient(transport, "fixture"), null, backgroundScope, EmptyCoroutineContext)
        val session = StemCellsSession.session("carry-queue-test", engine)
        val store = session.store
        try {
            store.start()
            val ready = withTimeout(60_000) { store.updates.first { it.live && it.document != null } }
            val drawn = StemCellsHomeView.drawn(ready.rows)
            val moving = drawn.first { it.nodeId == "splogin" }
            val drag = StemCellsDrag.holding(moving.id, drawn, ready.allRows, ready.document!!)!!
            session.setDragging(true)
            runCurrent()
            // Another client changes the document while the real session owns the carry.
            val remote = DirectAgentClient(fixture, "fixture")
            remote.applySidebarOperations("remote-rename", remote.sidebar(false).version,
                listOf(SpaceSidebarOperation.Set(SpaceSidebarSet("personal", label = "Remote personal"))))
            runCurrent()
            assertEquals("Personal", store.updates.value.rows.first { it.nodeId == "personal" }.label)
            val readsBeforeDrop = reads
            blockWrite = true
            session.drop(drag, drag.from - 1)
            session.setDragging(false) // queued immediately, as touch-up then screen teardown does
            runCurrent()
            assertEquals(readsBeforeDrop, reads)
            assertEquals("Personal", store.updates.value.rows.first { it.nodeId == "personal" }.label)
            releaseWrite.complete(Unit)
            store.flush()
            val caughtUp = withTimeout(60_000) { store.updates.first { snapshot -> snapshot.rows.any { it.nodeId == "personal" && it.label == "Remote personal" } } }
            val order = StemCellsHomeView.drawn(caughtUp.rows).map { it.nodeId }
            assertEquals(drag.order(drag.from - 1).map { it.nodeId }, order)
            assertTrue(fixture.sidebarHtml().contains("Remote personal"))
        } finally {
            releaseWrite.complete(Unit)
            store.stop()
            StemCellsSession.forget("carry-queue-test")
            Dispatchers.resetMain()
        }
    }
}