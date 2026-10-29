package com.slopus.happy.stemcells

import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectAgentError
import com.slopus.happy.direct.DirectEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** One proposal as a conversation shows it (happy-ios `StemCellProposalShown`). */
data class StemCellProposalShown(
    val status: Status = Status.Loading,
    val revision: Int = 1,
    val rows: List<StemCellsRow> = emptyList(),
    val answering: Answer? = null,
    val notice: String? = null,
) {
    enum class Status { Loading, Missing, Open, Accepted, Declined }
    enum class Answer { Accept, Decline }
}

/**
 * Main-thread-confined owner of a conversation's proposal reads and answers.
 * Mirrors happy-ios `StemCellSidebarProposals`: refresh on events/reconnect,
 * reader-local folds, version guards and one mutation ID across answer retries.
 */
class StemCellSidebarProposals internal constructor(
    parent: CoroutineScope,
    private val source: Source,
    private val failureNotice: String,
    private val accepted: () -> Unit = {},
) {
    internal interface Source {
        suspend fun read(id: String): SidebarProposalResponse
        suspend fun rows(html: String): List<StemCellsRow>?
        suspend fun answer(id: String, answer: StemCellProposalShown.Answer, mutationId: String): SidebarProposal
        fun events(): Flow<DirectEvent>
    }

    constructor(parent: CoroutineScope, client: DirectAgentClient, store: StemCellsStore, failureNotice: String, accepted: () -> Unit = {}) :
        this(parent, object : Source {
            override suspend fun read(id: String) = client.sidebarProposal(id)
            override suspend fun rows(html: String) = store.rows(html)
            override suspend fun answer(id: String, answer: StemCellProposalShown.Answer, mutationId: String) = when (answer) {
                StemCellProposalShown.Answer.Accept -> client.acceptSidebarProposal(id, mutationId)
                StemCellProposalShown.Answer.Decline -> client.declineSidebarProposal(id, mutationId)
            }
            override fun events() = store.events(listOf("space.sidebar.proposal."))
        }, failureNotice, accepted)

    private data class Held(val proposal: SidebarProposal, val html: String, val rows: List<StemCellsRow>)
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private var stopped = false
    private val held = mutableMapOf<String, Held>()
    private val turned = mutableMapOf<String, Set<String>>()
    private val reading = mutableSetOf<String>()
    private val again = mutableSetOf<String>()
    private var following: Job? = null
    private var refreshing: Job? = null
    var shown: Map<String, StemCellProposalShown> = emptyMap()
        private set
    var onChange: (() -> Unit)? = null

    fun stop() {
        stopped = true
        onChange = null
        scope.cancel()
    }

    fun reconnected() { if (!stopped) shown.keys.toList().forEach(::read) }

    fun want(ids: List<String>) {
        if (stopped) return
        for (id in ids) if (id !in shown) {
            shown = shown + (id to StemCellProposalShown())
            read(id)
        }
        if (following == null && shown.isNotEmpty()) following = scope.launch {
            source.events().collect { event ->
                when (event) {
                    DirectEvent.Resync -> reconnected()
                    is DirectEvent.Event -> {
                        val id = runCatching { event.envelope["payload"]?.jsonObject?.get("proposal")?.jsonObject?.get("artifactId")?.jsonPrimitive?.content }.getOrNull()
                        if (id != null && id in shown) read(id)
                    }
                }
            }
        }
    }

    fun spacesChanged() {
        if (stopped) return
        refreshing?.cancel()
        val documents = held.mapValues { it.value.html }
        refreshing = scope.launch {
            for ((id, html) in documents) {
                val rows = source.rows(html) ?: continue
                val current = held[id] ?: continue
                if (current.html != html) continue
                held[id] = current.copy(rows = rows)
                show(id)
            }
        }
    }

    fun turn(id: String, rowId: String) {
        if (stopped) return
        val folds = turned[id].orEmpty()
        turned[id] = if (rowId in folds) folds - rowId else folds + rowId
        show(id)
    }

    fun accept(id: String) = answer(id, StemCellProposalShown.Answer.Accept)
    fun decline(id: String) = answer(id, StemCellProposalShown.Answer.Decline)

    private fun read(id: String) {
        if (stopped) return
        if (!reading.add(id)) { again += id; return }
        scope.launch {
            try {
                val response = source.read(id)
                val rows = source.rows(response.html).orEmpty()
                if (!stopped && !isOlder(id, response.proposal)) {
                    held[id] = Held(response.proposal, response.html, rows)
                    show(id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stopped && (e as? DirectAgentError.Status)?.status == 404) {
                    held.remove(id)
                    publish(id, (shown[id] ?: StemCellProposalShown()).copy(status = StemCellProposalShown.Status.Missing, answering = null, notice = null))
                }
                // Otherwise the computer is away; reconnect reads it again.
            } finally {
                reading -= id
                if (again.remove(id) && !stopped) read(id)
            }
        }
    }

    private fun isOlder(id: String, proposal: SidebarProposal): Boolean {
        val current = held[id]?.proposal ?: return false
        return current.version > proposal.version ||
            (current.version == proposal.version && current.status != "open" && proposal.status == "open")
    }

    private fun show(id: String) {
        val entry = held[id] ?: return
        val status = when (entry.proposal.status) {
            "accepted" -> StemCellProposalShown.Status.Accepted
            "declined" -> StemCellProposalShown.Status.Declined
            else -> StemCellProposalShown.Status.Open
        }
        val folds = turned[id].orEmpty()
        val rows = entry.rows.filter { it.kind != StemCellsRow.Kind.Search && it.kind != StemCellsRow.Kind.Create }.map {
            if (it.id in folds) it.copy(collapsed = it.collapsed != true) else it
        }
        val previous = shown[id] ?: StemCellProposalShown()
        publish(id, previous.copy(
            status = status, revision = maxOf(1, entry.proposal.version - 1), rows = StemCellsRows.visible(rows),
            answering = previous.answering.takeIf { status == StemCellProposalShown.Status.Open },
            notice = previous.notice.takeIf { status == StemCellProposalShown.Status.Open },
        ))
    }

    private fun publish(id: String, value: StemCellProposalShown) {
        if (stopped || shown[id] == value) return
        shown = shown + (id to value)
        onChange?.invoke()
    }

    private fun answer(id: String, answer: StemCellProposalShown.Answer) {
        if (stopped) return
        val entry = shown[id] ?: return
        if (entry.status != StemCellProposalShown.Status.Open || entry.answering != null) return
        publish(id, entry.copy(answering = answer, notice = null))
        val mutationId = UUID.randomUUID().toString()
        scope.launch {
            try {
                val proposal = attempt { source.answer(id, answer, mutationId) }
                if (stopped) return@launch
                held[id]?.let { if (!isOlder(id, proposal)) held[id] = it.copy(proposal = proposal) }
                shown[id]?.let { shown = shown + (id to it.copy(answering = null)) }
                if (answer == StemCellProposalShown.Answer.Accept) accepted()
                val before = shown[id]
                show(id)
                if (shown[id] == before) onChange?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (stopped) return@launch
                val notice = (e as? DirectAgentError.Status)?.error?.takeIf { it.isNotEmpty() } ?: failureNotice
                shown[id]?.let { publish(id, it.copy(answering = null, notice = notice)) }
                read(id)
            }
        }
    }

    private suspend fun attempt(action: suspend () -> SidebarProposal): SidebarProposal {
        var tries = 0
        while (true) {
            try {
                return action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val status = (e as? DirectAgentError.Status)?.status
                if (tries >= 2 || e == DirectAgentError.Revoked || (status != null && status < 500)) throw e
                tries++
                delay(400L * tries)
            }
        }
    }
}