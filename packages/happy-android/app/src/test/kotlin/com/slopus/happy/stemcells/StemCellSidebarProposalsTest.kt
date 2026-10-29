package com.slopus.happy.stemcells

import com.slopus.happy.direct.DirectAgentError
import com.slopus.happy.direct.DirectEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StemCellSidebarProposalsTest {
    private fun proposal(status: String = "open", version: Int = 3) = SidebarProposal("p", version, 1, status, 1.0, null, null)
    private class Source : StemCellSidebarProposals.Source {
        val events = MutableSharedFlow<DirectEvent>(extraBufferCapacity = 8)
        var reads = 0
        var read: suspend () -> SidebarProposalResponse = { error("Set read") }
        var answer: suspend (StemCellProposalShown.Answer, String) -> SidebarProposal = { _, _ -> error("Set answer") }
        var rows = emptyList<StemCellsRow>()
        val mutations = mutableListOf<Pair<StemCellProposalShown.Answer, String>>()
        override suspend fun read(id: String): SidebarProposalResponse { reads++; return read() }
        override suspend fun rows(html: String) = rows
        override suspend fun answer(id: String, answer: StemCellProposalShown.Answer, mutationId: String): SidebarProposal {
            mutations += answer to mutationId
            return this.answer(answer, mutationId)
        }
        override fun events() = events
    }

    @Test fun aMissingOrOfflineProposalIsReadAgainWhenTheComputerReturns() = runTest {
        val source = Source()
        source.read = { throw IOException("offline") }
        val owner = StemCellSidebarProposals(backgroundScope, source, "Try again")
        owner.want(listOf("p", "p"))
        runCurrent()
        assertEquals(1, source.reads)
        assertEquals(StemCellProposalShown.Status.Loading, owner.shown["p"]?.status)
        source.read = { throw DirectAgentError.Status(404, null, null) }
        source.events.emit(DirectEvent.Resync)
        runCurrent()
        assertEquals(StemCellProposalShown.Status.Missing, owner.shown["p"]?.status)
        source.read = { SidebarProposalResponse(proposal(), "doc") }
        owner.reconnected()
        runCurrent()
        assertEquals(StemCellProposalShown.Status.Open, owner.shown["p"]?.status)
        assertEquals(2, owner.shown["p"]?.revision)
        owner.stop()
    }

    @Test fun foldsStayInsideThisCardAndNamesFollowCurrentSpaces() = runTest {
        val source = Source()
        source.read = { SidebarProposalResponse(proposal(), "doc") }
        source.rows = listOf(
            StemCellsRow("search", "search", StemCellsRow.Kind.Search, label = "Search"),
            StemCellsRow("section", "section", StemCellsRow.Kind.Section, label = "Work", collapsed = true),
            StemCellsRow("task", "task", StemCellsRow.Kind.Space, depth = 1, label = "Old name"),
            StemCellsRow("create", "create", StemCellsRow.Kind.Create, label = "New"),
        )
        val owner = StemCellSidebarProposals(backgroundScope, source, "Try again")
        owner.want(listOf("p"))
        runCurrent()
        assertEquals(listOf("section"), owner.shown["p"]?.rows?.map { it.id })
        owner.turn("p", "section")
        assertEquals(listOf("section", "task"), owner.shown["p"]?.rows?.map { it.id })
        assertEquals(true, source.rows[1].collapsed)
        assertTrue(source.mutations.isEmpty())
        source.rows = source.rows.map { if (it.id == "task") it.copy(label = "Renamed") else it }
        owner.spacesChanged()
        runCurrent()
        assertEquals("Renamed", owner.shown["p"]?.rows?.last()?.label)
        assertEquals(false, owner.shown["p"]?.rows?.first()?.collapsed)
        owner.stop()
    }

    @Test fun concurrentReadsCoalesceAndAnOldReadCannotReopenAnAcceptedProposal() = runTest {
        val source = Source()
        source.read = { SidebarProposalResponse(proposal(), "doc") }
        source.answer = { _, _ -> proposal("accepted") }
        val owner = StemCellSidebarProposals(backgroundScope, source, "Try again")
        owner.want(listOf("p"))
        runCurrent()
        val pending = CompletableDeferred<SidebarProposalResponse>()
        source.read = { pending.await() }
        owner.reconnected()
        owner.reconnected()
        owner.reconnected()
        runCurrent()
        assertEquals(2, source.reads)
        owner.accept("p")
        runCurrent()
        assertEquals(StemCellProposalShown.Status.Accepted, owner.shown["p"]?.status)
        source.read = { SidebarProposalResponse(proposal(version = 2), "older") }
        pending.complete(SidebarProposalResponse(proposal(), "old-open"))
        runCurrent()
        assertEquals(3, source.reads)
        assertEquals(StemCellProposalShown.Status.Accepted, owner.shown["p"]?.status)
        assertEquals(2, owner.shown["p"]?.revision)
        owner.stop()
    }

    @Test fun answersRetryWithOneMutationIdAndDisableBothChoicesWhilePending() = runTest {
        val source = Source()
        source.read = { SidebarProposalResponse(proposal(), "doc") }
        source.answer = { _, _ ->
            if (source.mutations.size < 3) throw IOException("lost answer")
            proposal("accepted")
        }
        var haptics = 0
        val owner = StemCellSidebarProposals(backgroundScope, source, "Try again") { haptics++ }
        owner.want(listOf("p"))
        runCurrent()
        owner.accept("p")
        owner.decline("p")
        runCurrent()
        assertEquals(StemCellProposalShown.Answer.Accept, owner.shown["p"]?.answering)
        advanceTimeBy(400); runCurrent()
        advanceTimeBy(800); runCurrent()
        assertEquals(3, source.mutations.size)
        assertEquals(1, source.mutations.map { it.second }.distinct().size)
        assertTrue(source.mutations.all { it.first == StemCellProposalShown.Answer.Accept })
        assertEquals(StemCellProposalShown.Status.Accepted, owner.shown["p"]?.status)
        assertNull(owner.shown["p"]?.answering)
        assertEquals(1, haptics)
        owner.stop()
    }

    @Test fun aRefusalDoesNotRetryAndItsNoticeRemainsUntilTheNextAnswer() = runTest {
        val source = Source()
        source.read = { SidebarProposalResponse(proposal(), "doc") }
        source.answer = { _, _ -> throw DirectAgentError.Status(409, "stale", "Sidebar moved") }
        val owner = StemCellSidebarProposals(backgroundScope, source, "Try again")
        owner.want(listOf("p"))
        runCurrent()
        owner.decline("p")
        runCurrent()
        assertEquals(1, source.mutations.size)
        assertEquals(2, source.reads)
        assertEquals("Sidebar moved", owner.shown["p"]?.notice)
        assertNull(owner.shown["p"]?.answering)
        source.answer = { _, _ -> proposal("declined") }
        owner.decline("p")
        assertNull(owner.shown["p"]?.notice)
        runCurrent()
        assertEquals(StemCellProposalShown.Status.Declined, owner.shown["p"]?.status)
        assertNotEquals(source.mutations[0].second, source.mutations[1].second)
        owner.stop()
    }

    @Test fun onlyWantedEventsRefreshAndStoppingCancelsPendingPublication() = runTest {
        val source = Source()
        source.read = { SidebarProposalResponse(proposal(), "doc") }
        val owner = StemCellSidebarProposals(backgroundScope, source, "Try again")
        owner.want(listOf("p"))
        runCurrent()
        source.events.emit(DirectEvent.Event(Json.parseToJsonElement("""{"payload":{"proposal":{"artifactId":"other"}}}""").jsonObject))
        runCurrent()
        assertEquals(1, source.reads)
        val pending = CompletableDeferred<SidebarProposalResponse>()
        source.read = { pending.await() }
        source.events.emit(DirectEvent.Event(Json.parseToJsonElement("""{"payload":{"proposal":{"artifactId":"p"}}}""").jsonObject))
        runCurrent()
        assertEquals(2, source.reads)
        var changes = 0
        owner.onChange = { changes++ }
        owner.stop()
        pending.complete(SidebarProposalResponse(proposal("accepted", 4), "new"))
        owner.accept("p")
        owner.reconnected()
        runCurrent()
        assertEquals(0, changes)
        assertTrue(source.mutations.isEmpty())
        assertEquals(StemCellProposalShown.Status.Open, owner.shown["p"]?.status)
    }
}