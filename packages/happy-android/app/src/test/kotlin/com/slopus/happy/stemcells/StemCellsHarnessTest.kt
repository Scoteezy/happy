package com.slopus.happy.stemcells

import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.harness.StemCellsHarnessTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The store and a conversation against the harness's fake computer, as
 * happy-ios `StemCellsDaemonTests` runs them against a throwaway daemon:
 * the sidebar read and streamed, a fold heard back as its own echo, new work
 * made below Happy and talking back. Also checks the shared fixture reads
 * as the phone needs it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StemCellsHarnessTest {
    private val fixture = File("src/debug/assets/harness/stem-cells-computer.json").readText()

    private fun TestScope.computer(): Pair<StemCellsHarnessTransport, DirectAgentClient> {
        val transport = StemCellsHarnessTransport(fixture, backgroundScope, now = { NOW + testScheduler.currentTime })
        return transport to DirectAgentClient(transport, "t")
    }

    private fun TestScope.store(client: DirectAgentClient) = StemCellsStore(client, scope = backgroundScope, now = { NOW + testScheduler.currentTime })

    private suspend fun StemCellsStore.next(condition: (StemCellsSnapshot) -> Boolean) = withTimeout(60_000) { updates.first(condition) }

    private suspend fun StemCellConversation.next(condition: (StemCellChatSnapshot) -> Boolean) = withTimeout(60_000) { updates.first(condition) }

    @Test fun theSidebarReadsAndStreams() = runTest {
        val (_, client) = computer()
        val store = store(client)
        store.start()
        val ready = store.next { it.live && it.availability == StemCellsSnapshot.Availability.Available && it.rows.any { row -> row.isHappy } }
        val rows = ready.rows
        assertEquals(StemCellsRow.Kind.Search, rows[0].kind)
        assertEquals(StemCellsRow.Kind.Create, rows[1].kind)
        assertEquals("sphappy", ready.happySpaceId)
        assertEquals(true, ready.prioritizing)
        // Happy over Prioritizing, the surfaces, then the sections with their rows.
        val drawn = rows.filter { !StemCellsDrop.isHidden(it) }.map { it.nodeId }
        assertEquals(
            listOf("sphappy", "agprio", "spgroceries", "divider", "bots", "spresearch", "tasks", "spandroid", "agscreens", "agnotes", "splogin", "spchecklist", "personal", "splisbon", "spbike"),
            drawn,
        )
        val android = rows.first { it.nodeId == "spandroid" }
        assertEquals(StemCellsRow.Status.Working, android.status)
        assertTrue(rows.first { it.nodeId == "splogin" }.unread)
        store.stop()
    }

    @Test fun aFoldIsHeardBackAsItsOwnEcho() = runTest {
        val (transport, client) = computer()
        val store = store(client)
        store.start()
        store.next { it.live && it.rows.any { row -> row.nodeId == "personal" } }
        store.fold("personal")
        val folded = store.next { it.rows.firstOrNull { row -> row.nodeId == "personal" }?.collapsed == true }
        assertFalse("its rows fold away", folded.rows.any { it.nodeId == "splisbon" })
        store.flush()
        assertTrue(transport.sidebarHtml().contains("""<happy-section id="personal" label="Personal" collapsed="true"/>"""))
        // The echo changes nothing drawn.
        val echoed = transport.events().last { it["type"].toString().contains("space.sidebar.changed") }
        assertNotNull(echoed)
        assertEquals(folded.rows, store.updates.value.rows)
        store.stop()
    }

    @Test fun newWorkTalksBack() = runTest {
        val (_, client) = computer()
        val store = store(client)
        store.start()
        store.next { it.live && it.happySpaceId != null }
        val creation = store.createStemCell()
        // Drawn at once, right below Happy and what it holds, before the daemon answers.
        val drawn = store.next { snapshot -> snapshot.rows.any { it.spaceId == creation.spaceId } }.rows
        val happy = drawn.indexOfFirst { it.isHappy }
        assertEquals(creation.spaceId, drawn.drop(happy + 1).first { it.depth == 0 }.spaceId)
        assertTrue(drawn.first { it.spaceId == creation.spaceId }.isUntouchedStemCell)

        val conversation = StemCellConversation(client, store, creation.agentId, creation.created, scope = backgroundScope, now = { (NOW + testScheduler.currentTime).toDouble() })
        conversation.start()
        conversation.send("Plan a bike ride along the river")
        val answered = conversation.next { snapshot ->
            !snapshot.working && snapshot.items.any { it.role == StemCellChatItem.Role.Agent && it.text.startsWith("On it.") }
        }
        val first = answered.items.first()
        assertEquals(StemCellChatItem.Role.User, first.role)
        assertEquals("Plan a bike ride along the river", first.text)
        assertEquals(StemCellChatItem.Delivery.Sent, first.delivery)
        assertEquals(creation.agentId, creation.created.await())
        // It named itself after its first answer, and the row follows.
        val named = store.next { snapshot -> snapshot.rows.firstOrNull { it.spaceId == creation.spaceId }?.label == "Plan a bike ride" }
        assertFalse(named.rows.first { it.spaceId == creation.spaceId }.isUntouchedStemCell)
        conversation.stop()
        store.stop()
    }

    @Test fun aConversationReadsItsHistoryWithMediaAndArtifacts() = runTest {
        val (_, client) = computer()
        val store = store(client)
        store.start()
        store.next { it.live }
        val conversation = StemCellConversation(client, store, "agandroid", scope = backgroundScope)
        conversation.start()
        val loaded = conversation.next { it.loaded && it.items.isNotEmpty() }
        assertEquals(4, loaded.items.size)
        val pieces = loaded.items[1].pieces
        assertTrue(pieces[0] is StemCellChatItem.Piece.Text)
        val gallery = pieces[1] as StemCellChatItem.Piece.Gallery
        assertEquals(listOf("home.png", "chat.png", "settings.png"), gallery.media.map { it.item.name })
        val artifact = loaded.items[3].pieces.filterIsInstance<StemCellChatItem.Piece.Artifact>().single().artifact
        assertEquals("Store notes", artifact.title)
        assertTrue(artifact.opensLatest)
        // Its run is still going: the line says so.
        assertTrue(loaded.working)
        assertNotNull(loaded.activity)
        conversation.stop()
        store.stop()
    }

    @Test fun anotherAgentsReportIsACollaboratorsRow() = runTest {
        val (_, client) = computer()
        val store = store(client)
        store.start()
        store.next { it.live }
        val conversation = StemCellConversation(client, store, "aghappy", scope = backgroundScope)
        conversation.start()
        val loaded = conversation.next { it.loaded && it.items.size == 3 }
        val report = loaded.items[2]
        assertEquals(StemCellChatItem.Role.Collaborator("agprio", "Tidied the sidebar"), report.role)
        assertEquals("Moved Plan the Lisbon trip to Personal.", report.text)
        assertEquals(mapOf("agprio" to "Prioritizing"), loaded.subagentTitles)
        conversation.stop()
        store.stop()
    }

    @Test fun aDraftOpensWithTheConversation() = runTest {
        val (_, client) = computer()
        val store = store(client)
        store.start()
        store.next { it.live }
        val conversation = StemCellConversation(client, store, "agresearch", scope = backgroundScope)
        conversation.start()
        val loaded = conversation.next { it.loaded && it.draft.isNotEmpty() }
        assertEquals("Compare the three e-bike shops near ", loaded.draft)
        assertEquals("openai/sol", loaded.mode?.modelId)
        conversation.stop()
        store.stop()
    }

    companion object {
        private const val NOW = 1_793_000_000_000L
    }
}
