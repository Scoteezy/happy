package com.slopus.happy.stemcells

import com.slopus.happy.direct.ClientId
import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectAgentError
import com.slopus.happy.direct.DirectEvent
import com.slopus.happy.direct.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.Base64

/**
 * One entry in a Stem Cells conversation as the phone shows it: what a
 * person wrote, or what the agent said. Reasoning, tool calls and the
 * daemon's housekeeping are never shown; a tool call is kept only for the
 * pictures and videos it presented. happy-ios `StemCellChatItem`.
 */
data class StemCellChatItem(val id: String, val role: Role, val pieces: List<Piece>, val delivery: Delivery) {
    sealed class Role {
        data object User : Role()
        data object Agent : Role()
        /**
         * Another agent reporting to this one (a collaborator, a subtask),
         * not the one that manages it: one compact row naming the sender,
         * opened to read what it said (happy-desktop's `agentMessage` activity).
         */
        data class Collaborator(val agentId: String, val explanation: String?) : Role()
    }

    sealed class Delivery {
        data object Sent : Delivery()
        /** Not yet accepted by the daemon. */
        data object Sending : Delivery()
        /** Accepted, waiting for the agent to take it up. */
        data object Pending : Delivery()
        data class Failed(val reason: String) : Delivery()
    }

    sealed class Piece {
        data class Text(val text: String) : Piece()
        /** A picture sent with a message, inline. */
        class Picture(val id: String, val mimeType: String, val data: ByteArray) : Piece() {
            override fun equals(other: Any?) = other is Picture && other.id == id && other.mimeType == mimeType && other.data.contentEquals(data)
            override fun hashCode() = id.hashCode()
        }
        /** Pictures and videos an agent's tool presented, in `columns` (0: justified rows); read on demand. */
        data class Gallery(val media: List<StemCellMedia>, val columns: Int) : Piece()
        /**
         * A layout the agent wrote for media it showed: a presentation's
         * `tree`, or a block in its text. The tree's item indexes are
         * positions in `media`.
         */
        data class Tree(val media: List<StemCellMedia>, val tree: StemCellMediaTree) : Piece()
        /** A document or other file a tool presented, as a card that opens it. */
        data class File(val media: StemCellMedia) : Piece()
        /** An artifact a tool made or changed, as a card that opens it. */
        data class Artifact(val artifact: StemCellArtifact) : Piece()
        /** A story a tool drafted (`draft_story`), by its ID: drawn as the story itself, following it live. */
        data class Story(val id: String) : Piece()
        /** A sidebar Prioritizing proposed (a `sidebar_proposal` artifact), drawn by the sidebar's own rows and answered where it stands. */
        data class SidebarProposal(val artifactId: String) : Piece()
    }

    val text: String get() = pieces.filterIsInstance<Piece.Text>().joinToString("\n\n") { it.text }

    /** The sidebar proposals it shows, by artifact ID. */
    val proposalIds: List<String> get() = pieces.filterIsInstance<Piece.SidebarProposal>().map { it.artifactId }
}

/**
 * One picture or video a tool call presented: its bytes are
 * `GET /v0/agents/:agentId/tool-calls/:toolCallId/media/:index`.
 */
data class StemCellMedia(val toolCallId: String, val index: Int, val item: MediaItem) {
    val isVideo: Boolean get() = item.kind == "video"

    /** A picture or recording; anything else is a document or file, and an unknown kind is a file. */
    val isVisual: Boolean get() = item.kind == "image" || item.kind == "video"

    /** What a screen knows it by. */
    val key: String get() = "tool/$toolCallId/$index"

    companion object {
        /**
         * The shape a thumbhash's header carries, width over height
         * (`thumbHashToApproximateAspectRatio`); null when there is none.
         */
        fun hashedRatio(thumbhash: String?): Double? {
            if (thumbhash.isNullOrEmpty()) return null
            var base64 = thumbhash.replace('-', '+').replace('_', '/')
            base64 += "=".repeat((4 - base64.length % 4) % 4)
            val hash = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: return null
            if (hash.size < 5) return null
            val header = hash[3].toInt() and 0xff
            val hasAlpha = hash[2].toInt() and 0x80 != 0
            val isLandscape = hash[4].toInt() and 0x80 != 0
            val lx = if (isLandscape) (if (hasAlpha) 5 else 7) else header and 7
            val ly = if (isLandscape) header and 7 else (if (hasAlpha) 5 else 7)
            if (lx <= 0 || ly <= 0) return null
            return lx.toDouble() / ly
        }
    }
}

/**
 * An artifact a call created, changed or took up (`artifact`
 * presentation): `shows` `latest` follows it, else it is exactly `version`.
 */
data class StemCellArtifact(
    val id: String,
    val version: Int,
    val type: String,
    val title: String,
    val followsLatest: Boolean,
    /** A reference named this version: it opens that one, never the latest. */
    val pinned: Boolean = false,
) {
    /** Desktop's card follows the artifact from the call that created it, whose files are written after it. */
    val opensLatest: Boolean get() = followsLatest || (version == 1 && !pinned)
}

/**
 * What desktop's working line reports (`AgentWorkingStatus`), projected
 * as its chat store projects it (`workingPhaseProject`,
 * `workingLabelProject`, `activityOf`): a question waiting on the person
 * first, then delegated work, a scheduled wait, a retry, a model that has
 * not answered yet, and otherwise the agent's own status.
 */
data class StemCellActivity(
    val phase: Phase,
    /** Unix milliseconds the turn began; null where no clock is shown. */
    val startedAt: Double?,
) {
    sealed class Phase {
        data object WaitingForModel : Phase()
        data object Thinking : Phase()
        data object PreparingTools : Phase()
        data object RunningTools : Phase()
        data object Working : Phase()
        data class Retrying(val retrying: AgentRetrying) : Phase()
        data class Waiting(val wait: AgentWait) : Phase()
        data object AwaitingAnswer : Phase()
        data object Delegating : Phase()
    }

    companion object {
        /**
         * The projection itself, from the agent's state and the turn's.
         * `produced` is whether the running turn has shown anything yet.
         */
        fun project(
            working: Boolean, status: String, wait: AgentWait?, retrying: AgentRetrying?, question: Boolean,
            subagentsRunning: Int, produced: Boolean, startedAt: Double?,
        ): StemCellActivity? {
            if (question) return StemCellActivity(Phase.AwaitingAnswer, null)
            if (!working) return if (subagentsRunning > 0) StemCellActivity(Phase.Delegating, null) else null
            val phase = when {
                wait != null -> Phase.Waiting(wait)
                retrying != null -> Phase.Retrying(retrying)
                !produced && status in listOf("idle", "queued", "thinking") -> Phase.WaitingForModel
                else -> when (status) {
                    "thinking" -> Phase.Thinking
                    "generating_tools" -> Phase.PreparingTools
                    "running_tools" -> Phase.RunningTools
                    else -> Phase.Working
                }
            }
            return StemCellActivity(phase, startedAt)
        }
    }
}

data class StemCellChatSnapshot(
    /** History has been read, or there is none to read yet. */
    val loaded: Boolean = false,
    val items: List<StemCellChatItem> = emptyList(),
    /** The agent is at work on a run. */
    val working: Boolean = false,
    /** The working line; null when there is nothing to report. */
    val activity: StemCellActivity? = null,
    /** Older runs exist before the first one shown. */
    val hasMore: Boolean = false,
    /** The agents this one spawned, by ID, as titled (a sender a collaborator row names when the sidebar doesn't hold it). */
    val subagentTitles: Map<String, String> = emptyMap(),
    /** Why the conversation could not be read. */
    val error: String? = null,
    /** The model, account and effort the next message goes with: the last one sent, a pick, or the computer's default. */
    val mode: AgentMode? = null,
    /**
     * The composer's draft, and a count that moves only when it came from
     * elsewhere (the computer, the person's other device, or this phone's
     * kept copy): the screen sets the composer then, and never has its
     * own typing echoed back.
     */
    val draft: String = "",
    val draftArrivals: Int = 0,
    /** When `draft` was written (ms since 1970): a draft from elsewhere older than what is being typed here isn't shown over it. */
    val draftUpdatedAt: Double = 0.0,
    /**
     * The person's steering at the end of `items` that the running turn
     * hasn't taken yet: desktop draws it under the working line, as next
     * in the queue, until the agent takes it up.
     */
    val queued: Int = 0,
)

/**
 * One conversation over the paired computer's API: its history, read by
 * run, and the store's events applied on top (`message.*`, `run.*`,
 * `agent.updated`). Sends are drawn at once and confirmed by their own
 * `message.created` echo, by ID. happy-ios `StemCellConversation`.
 *
 * `scope` must run one coroutine at a time, as `StemCellsStore`'s does: it
 * plays the Swift actor, and every public call hops onto it.
 */
class StemCellConversation(
    private val client: DirectAgentClient,
    private val store: StemCellsStore,
    agentId: String,
    creation: Deferred<String>? = null,
    mode: AgentMode? = null,
    private val persistence: StemCellPersistence? = null,
    private val scope: CoroutineScope,
    private val now: () -> Double = { System.currentTimeMillis().toDouble() },
) {
    private val confined = scope.coroutineContext.minusKey(Job)
    private val updatesFlow = MutableStateFlow(StemCellChatSnapshot(mode = mode))

    /** Every change, the latest kept. */
    val updates: StateFlow<StemCellChatSnapshot> = updatesFlow.asStateFlow()

    private var agentId = agentId
    /** New work the daemon may not have made yet; sends wait for it. */
    private var creation = creation
    private var snapshot = StemCellChatSnapshot(mode = mode)

    private var keeping: Job? = null
    private val delivering = HashMap<String, Job>()
    private var conversationDirty = false
    private var deliveryWatch: Job? = null
    private val outgoingModes = HashMap<String, AgentMode>()
    private var lastKept: JsonObject? = null

    private var following: Job? = null
    /** The agents this one spawns or retitles while it is open. */
    private var children: Job? = null
    private var reading: Job? = null
    private var reloadSoon: Job? = null
    /** Events that came while history was being read, applied after it. */
    private var heldEvents: MutableList<JsonObject>? = null
    /** Subagents made or retitled while history was being read, applied over the bootstrap's list. */
    private val heldChildren = ArrayList<JsonObject>()

    private class Run(val id: String, val messageIds: MutableList<String>)
    private val runs = ArrayList<Run>()
    private val messages = HashMap<String, AgentMessage>()
    /** User messages the daemon holds that no run has taken yet, oldest first. */
    private val pending = ArrayList<String>()

    /**
     * Sends the daemon has not echoed yet, oldest first. One it accepted
     * stays here until history or an event carries it, so no read that
     * began before the send can take it off the screen.
     */
    private class Send(val id: String, val text: String, val pictures: List<OutgoingImage>, var accepted: Boolean, var failure: String?)
    private val outgoing = ArrayList<Send>()
    /** Pictures already decoded, by message and block. */
    private val pictures = HashMap<String, ByteArray>()
    private var mode: AgentMode? = mode
    /** A mode the person chose here; what the daemon last ran with never replaces it. */
    private var picked: AgentMode? = mode
    private var defaultMode: Deferred<Result<AgentMode>>? = null
    // The agent's own state, for the working line.
    private var status = "idle"
    private var wait: AgentWait? = null
    private var retrying: AgentRetrying? = null
    private var question = false
    private var subagentsRunning = 0
    /** The agent that manages this one: its messages are dialogue, never a collaborator's row. */
    private var parentAgentId: String? = null
    /** The running turn and when it began (or when a send was taken, before its run is known). */
    private class ActiveRun(val id: String?, val startedAt: Double?)
    private var activeRun: ActiveRun? = null
    private var visible = false

    /*
     * The draft as desktop keeps it (`connectHappyAgent` `setDraft`): this
     * phone's copy first, saved to the computer 300 ms after the last
     * keystroke (a clear at once), last write wins by `updatedAt` across
     * the person's devices, and `agent.draft.updated` brings theirs.
     */
    private var drafts: StemCellDrafts? = null
    private var draft = StemCellDraft("", 0.0, true)
    /** Moves with every draft taken or typed: a save answered after it is stale. */
    private var draftRevision = 0
    private var draftSave: Job? = null
    /** The newest keystroke's stamp, so one arriving out of order is dropped. */
    private var lastDraftEdit = 0.0

    // Life

    fun start() {
        scope.launch { begin() }
    }

    private fun begin() {
        if (following != null) return
        val creation = creation
        if (creation != null) {
            // New work has nothing to read yet: it is shown at once, empty.
            snapshot = snapshot.copy(loaded = true)
            publish()
            defaultMode = readDefaultMode()
            following = scope.launch {
                val id = try { creation.await() } catch (e: Exception) { return@launch }
                created(id)
            }
            return
        }
        following = scope.launch {
            restoreConversation()
            follow()
        }
    }

    suspend fun stop() = withContext(confined) {
        following?.cancel(); following = null
        children?.cancel(); children = null
        reading?.cancel(); reading = null
        reloadSoon?.cancel(); reloadSoon = null
        deliveryWatch?.cancel(); deliveryWatch = null
        val sends = delivering.values.toList()
        sends.forEach { it.cancel() }
        sends.forEach { it.join() }
        delivering.clear()
        saveConversationNow()
        Unit
    }

    /** The conversation is on screen: what arrives is read. */
    fun setVisible(visible: Boolean) {
        scope.launch {
            this@StemCellConversation.visible = visible
            if (visible && runs.isNotEmpty()) markRead()
        }
    }

    private suspend fun created(id: String) {
        creation = null
        agentId = id
        // Typed while the daemon made it: newer than the draft it was made
        // with, which would otherwise take its place on the first read.
        if (!draft.synced) {
            draft = draft.copy(updatedAt = stamp(after = draft.updatedAt))
            snapshot = snapshot.copy(draftUpdatedAt = draft.updatedAt)
            draftRevision += 1
            saveDraftSoon(0)
        }
        follow()
    }

    private suspend fun follow() {
        val persistence = persistence
        if (persistence != null) {
            val deliveryEvents = persistence.deliveryEvents(agentId)
            deliveryWatch?.cancel()
            deliveryWatch = scope.launch {
                deliveryEvents.collect { event ->
                    when (event) {
                        is DirectDeliveryEvent.Accepted -> sent(event.id)
                        is DirectDeliveryEvent.Failed -> failed(event.id, event.reason)
                    }
                }
            }
            val queued = try { persistence.queuedMessages(agentId) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            for (message in queued) {
                val copy = Send(message.id, message.text, message.pictures, false, message.failure)
                val index = outgoing.indexOfFirst { it.id == message.id }
                if (index >= 0) outgoing[index] = copy else outgoing += copy
                keepMode(message.id, message.mode)
            }
        }
        val events = store.subscribe(agentId)
        val spawned = try {
            store.subscribe(null, listOf("agent.created", "agent.updated"))
        } catch (e: Throwable) {
            events.cancel()
            throw e
        }
        try {
            // Stopped while those were asked for: nothing is followed.
            currentCoroutineContext().ensureActive()
            children?.cancel()
            children = scope.launch {
                for (event in spawned) if (event is DirectEvent.Event) child(event.envelope)
            }
            heldEvents = ArrayList()
            reload()
            deliverWaiting()
            for (event in events) {
                when (event) {
                    is DirectEvent.Resync -> {
                        reload()
                        deliverWaiting()
                    }
                    is DirectEvent.Event -> heldEvents?.add(event.envelope) ?: receive(event.envelope)
                }
            }
        } finally {
            events.cancel()
            spawned.cancel()
        }
    }

    private fun deliverWaiting() {
        for (send in outgoing.toList()) if (!send.accepted && send.failure == null) deliver(send.id)
    }

    // Reads

    /** The newest runs and what is pending, read whole; events that arrive meanwhile are applied after. */
    private fun reload() {
        reading?.cancel()
        if (heldEvents == null) heldEvents = ArrayList()
        val agentId = agentId
        reading = scope.launch {
            try {
                val (start, history) = coroutineScope {
                    val bootstrap = async { client.bootstrap(agentId) }
                    val page = async { client.messages(agentId) }
                    bootstrap.await() to page.await()
                }
                adopt(start, history)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snapshot = snapshot.copy(error = StemCellsStore.describe(e), loaded = true)
                val held = heldEvents.orEmpty()
                heldEvents = null
                for (event in held) receive(event, publishes = false)
                publish()
            }
        }
    }

    private fun adopt(start: Bootstrap, history: MessagesResponse) {
        mode = picked ?: start.mode ?: mode
        snapshot = snapshot.copy(mode = mode)
        start.draft?.let { adoptDraft(it, publishes = false) }
        if (mode == null && defaultMode == null) defaultMode = readDefaultMode()
        // A newest-page refresh is not a deletion of older pages already
        // downloaded. Retain them until a complete history says otherwise.
        val refreshed = history.runs.map { it.id }.toSet()
        if (persistence != null && history.hasMore) {
            runs.removeAll { it.id in refreshed }
            val retained = runs.flatMap { it.messageIds }.toSet()
            messages.keys.retainAll(retained)
        } else {
            runs.clear()
            messages.clear()
        }
        pending.clear()
        for (run in history.runs) {
            val list = run.messages.orEmpty()
            runs += Run(run.id, list.map { it.id }.toMutableList())
            for (message in list) messages[message.id] = message
        }
        for (message in start.pending.orEmpty()) {
            messages[message.id] = message
            pending += message.id
        }
        outgoing.removeAll { it.id in messages }
        // A send taken before the daemon started its run keeps the line
        // and the send's clock until the run's own events come.
        val awaiting = activeRun != null && activeRun?.id == null && (outgoing.any { it.accepted } || pending.isNotEmpty())
        snapshot = snapshot.copy(working = start.agent.isWorking || awaiting)
        adoptAgent(start.agent)
        val last = history.runs.lastOrNull()
        if (start.agent.isWorking && last != null && last.status == "running") {
            activeRun = ActiveRun(last.id, last.startedAt)
        } else if (!awaiting) {
            activeRun = null
        }
        var titles = snapshot.subagentTitles
        start.subagents?.let { subagents ->
            // desktop's `projectSubagents`: an untitled child is "Subagent".
            val map = LinkedHashMap<String, String>()
            for (agent in subagents) map.putIfAbsent(agent.id, agent.title ?: "")
            titles = map
        }
        snapshot = snapshot.copy(hasMore = history.hasMore, subagentTitles = titles, loaded = true, error = null)
        val held = heldEvents.orEmpty()
        heldEvents = null
        for (event in held) receive(event, publishes = false)
        val early = heldChildren.toList()
        heldChildren.clear()
        for (event in early) child(event, publishes = false)
        publish()
        if (visible) markRead()
    }

    /** Runs older than the first one shown. */
    suspend fun loadOlder() = withContext(confined) {
        if (!snapshot.hasMore || heldEvents != null) return@withContext
        val first = runs.firstOrNull()?.id ?: return@withContext
        try {
            val page = client.messages(agentId, before = first)
            if (runs.firstOrNull()?.id != first) return@withContext
            val known = runs.map { it.id }.toSet()
            val older = page.runs.filter { it.id !in known }
            runs.addAll(0, older.map { run -> Run(run.id, run.messages.orEmpty().map { it.id }.toMutableList()) })
            for (run in older) for (message in run.messages.orEmpty()) messages[message.id] = message
            snapshot = snapshot.copy(hasMore = page.hasMore)
            publish()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** History disagreed with an event: read it again, once things settle. */
    private fun reconcile() {
        reloadSoon?.cancel()
        reloadSoon = scope.launch {
            delay(300)
            reload()
        }
    }

    // Events

    private fun receive(event: JsonObject, publishes: Boolean = true) {
        val type = event["type"].str ?: return
        val payload = event["payload"]
        when (type) {
            "message.created", "message.updated" -> {
                val message = payload["message"]?.let(::decodeMessage) ?: return
                messages[message.id] = message
                outgoing.removeAll { it.id == message.id }
                val runId = payload["runId"].str
                if (runId != null) {
                    place(message.id, runId)
                } else if (message.id !in pending && runs.none { message.id in it.messageIds }) {
                    pending += message.id
                }
            }
            "message.delta" -> {
                val id = payload["messageId"].str ?: return
                val index = payload["blockIndex"].num ?: return
                val offset = payload["offset"].num ?: return
                val append = payload["append"].str ?: return
                val grown = messages[id]?.let { grow(it, index.toInt(), offset.toInt(), append) }
                if (grown == null) {
                    reconcile()
                    return
                }
                messages[id] = grown
                payload["runId"].str?.let { place(id, it) }
            }
            "message.deleted" -> {
                val id = payload["messageId"].str ?: return
                messages.remove(id)
                pending.remove(id)
                for (run in runs) run.messageIds.remove(id)
            }
            "run.started" -> {
                val runId = payload["run"]["id"].str ?: return
                accept(payload["acceptedMessageIds"].strings(), runId)
                snapshot = snapshot.copy(working = true)
                activeRun = ActiveRun(runId, payload["run"]["startedAt"].num)
            }
            "run.boundary" -> {
                val runId = payload["startedRun"]["id"].str ?: return
                accept(payload["acceptedMessageIds"].strings(), runId)
                snapshot = snapshot.copy(working = true)
                activeRun = ActiveRun(runId, payload["startedRun"]["startedAt"].num)
            }
            "run.finished" -> {
                snapshot = snapshot.copy(working = false)
                settle()
                if (visible) markRead()
            }
            "agent.updated" -> {
                val changes = payload["changes"] as? JsonObject ?: return
                changes["status"].str?.let { status ->
                    this.status = status
                    snapshot = snapshot.copy(working = status != "idle")
                    if (status == "idle") settle()
                }
                // Present and null clears; absent leaves it.
                changes["wait"]?.let { wait = if (it is JsonNull) null else runCatching { AgentWait.from(it) }.getOrNull() }
                changes["retrying"]?.let { retrying = if (it is JsonNull) null else runCatching { AgentRetrying.from(it) }.getOrNull() }
                changes["pendingQuestionId"]?.let { question = it.str != null }
                changes["subagents"]["running"].num?.let { subagentsRunning = it.toInt() }
            }
            "agent.draft.updated" -> {
                val draft = payload["draft"]?.let { runCatching { AgentDraft.from(it) }.getOrNull() } ?: return
                adoptDraft(draft, publishes = false)
            }
            else -> return
        }
        if (publishes) publish()
    }

    /**
     * A subagent of this one made or retitled: what its references and
     * its messages are called by, without reading the conversation again.
     */
    private fun child(event: JsonObject, publishes: Boolean = true) {
        // Mid-read, the bootstrap's list would replace it: it waits for it.
        if (heldEvents != null) {
            heldChildren += event
            return
        }
        val payload = event["payload"]
        when (event["type"].str) {
            "agent.created" -> {
                val agent = payload["agent"]
                if (agent["parentAgentId"].str != agentId) return
                val id = agent["id"].str ?: return
                snapshot = snapshot.copy(subagentTitles = snapshot.subagentTitles + (id to (agent["title"].str ?: "")))
            }
            "agent.updated" -> {
                val id = payload["agentId"].str ?: return
                if (id !in snapshot.subagentTitles) return
                val title = (payload["changes"] as? JsonObject)?.get("title") ?: return
                snapshot = snapshot.copy(subagentTitles = snapshot.subagentTitles + (id to (title.str ?: "")))
            }
            else -> return
        }
        if (publishes) publish()
    }

    private fun place(messageId: String, runId: String) {
        pending.remove(messageId)
        val run = runs.firstOrNull { it.id == runId }
        if (run != null) {
            if (messageId !in run.messageIds) run.messageIds += messageId
        } else {
            runs += Run(runId, mutableListOf(messageId))
        }
    }

    private fun accept(ids: List<String>, runId: String) {
        val run = runs.firstOrNull { it.id == runId } ?: Run(runId, ArrayList()).also { runs += it }
        // The accepted messages open their run, in the order they were taken.
        val accepted = ids.filter { it !in run.messageIds }
        run.messageIds.addAll(0, accepted)
        pending.removeAll { it in ids }
        for (id in ids) messages[id]?.let { messages[id] = it.copy(status = "accepted") }
    }

    // Sending

    /** Draws the message at once and sends it, after the new work it is in has been made. */
    suspend fun send(text: String, pictures: List<OutgoingImage> = emptyList(), id: String = ClientId.make()) = withContext(confined) {
        if (outgoing.any { it.id == id } || id in messages) return@withContext
        outgoing += Send(id, text, pictures, false, null)
        keepMode(id, mode)
        if (!saveConversationNow()) {
            failed(id, "Could not save this message on this device. Retry when storage is available.")
            return@withContext
        }
        val persistence = persistence
        if (persistence != null && creation == null) {
            try {
                persistence.enqueueMessage(queuedMessage(id, text, pictures))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(id, NOT_SAVED)
                return@withContext
            }
            publish()
            return@withContext
        }
        publish()
        deliver(id)
    }

    fun retry(id: String) {
        scope.launch {
            val send = outgoing.firstOrNull { it.id == id } ?: return@launch
            if (send.failure == null) return@launch
            send.failure = null
            publish()
            val persistence = persistence
            if (persistence == null) {
                deliver(id)
                return@launch
            }
            val payload = queuedMessage(id, send.text, send.pictures)
            if (!saveConversationNow()) {
                failed(id, NOT_SAVED)
                return@launch
            }
            try {
                persistence.enqueueMessage(payload)
                persistence.retryMessage(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(id, NOT_SAVED)
            }
        }
    }

    private fun deliver(id: String) {
        if (delivering[id] != null) return
        delivering[id] = scope.launch {
            try {
                deliverNow(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (persistence != null && (e !is DirectAgentError || (e is DirectAgentError.Status && e.status >= 500))) {
                    // Offline sends stay queued and resume on the account's
                    // next resync, with the same daemon message ID.
                    publish()
                } else {
                    failed(id, StemCellsStore.describe(e))
                }
            } finally {
                delivering.remove(id)
            }
        }
    }

    private suspend fun deliverNow(id: String) {
        val persistence = persistence
        if (persistence != null && !persistence.deliveryAllowed()) return
        if (!saveConversationNow()) return failed(id, NOT_SAVED)
        // New work: its conversation exists once the daemon has made it,
        // under the ID the daemon gave it.
        creation?.let { agentId = it.await() }
        if (persistence != null && !persistence.deliveryAllowed()) return
        val send = outgoing.firstOrNull { it.id == id } ?: return
        if (persistence != null) {
            persistence.enqueueMessage(queuedMessage(id, send.text, send.pictures))
            return
        }
        val mode = outgoingModes[id] ?: sendingMode().also {
            outgoingModes[id] = it
            if (!saveConversationNow()) return failed(id, NOT_SAVED)
        }
        // The same ID again is the same message: a retry never doubles it.
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                client.sendMessage(agentId, id, send.text, send.pictures, mode)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (StemCellsStore.refusal(e)) throw e
                attempt += 1
                if (attempt == 3) throw e
                delay(600L * attempt)
            }
        }
        sent(id)
    }

    private fun queuedMessage(id: String, text: String, pictures: List<OutgoingImage>) =
        DirectQueuedMessage(id, agentId, text, pictures, outgoingModes[id])

    private fun keepMode(id: String, mode: AgentMode?) {
        if (mode != null) outgoingModes[id] = mode else outgoingModes.remove(id)
    }

    // Kept on this phone

    private suspend fun restoreConversation() {
        val persistence = persistence ?: return
        val value = try {
            persistence.cachedResource("conversation:$agentId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        val kept = runCatching { Kept.from(value) }.getOrNull() ?: return
        runs.clear(); runs.addAll(kept.runs)
        messages.clear(); messages.putAll(kept.messages)
        pending.clear(); pending.addAll(kept.pending)
        outgoing.clear(); outgoing.addAll(kept.outgoing)
        outgoingModes.clear(); outgoingModes.putAll(kept.modes)
        mode = picked ?: kept.mode
        snapshot = snapshot.copy(mode = mode, hasMore = kept.hasMore, loaded = true)
        lastKept = value as? JsonObject
        publish()
    }

    private fun keepConversation() {
        conversationDirty = true
        if (persistence == null || keeping != null) return
        keeping = scope.launch {
            while (conversationDirty) {
                delay(500)
                conversationDirty = false
                persistConversation()
            }
            keeping = null
        }
    }

    private suspend fun saveConversationNow(): Boolean {
        keeping?.let {
            it.cancel()
            it.join()
        }
        keeping = null
        conversationDirty = false
        val saved = persistConversation()
        if (conversationDirty) keepConversation()
        return saved
    }

    private suspend fun persistConversation(): Boolean {
        val persistence = persistence ?: return true
        if (!snapshot.loaded && outgoing.isEmpty()) return true
        val kept = keptJson()
        if (kept == lastKept) return true
        return try {
            persistence.keepResource("conversation:$agentId", kept)
            lastKept = kept
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            snapshot = snapshot.copy(error = "Could not save this conversation on this device.")
            false
        }
    }

    /** What is kept of it, in happy-ios `KeptConversation`'s shape. */
    private fun keptJson(): JsonObject = Wire.objectOf(
        "runs" to JsonArray(runs.map { run -> JsonObject(mapOf("id" to JsonPrimitive(run.id), "messageIds" to JsonArray(run.messageIds.map(::JsonPrimitive)))) }),
        "messages" to JsonObject(messages.mapValues { it.value.json() }),
        "pending" to JsonArray(pending.map(::JsonPrimitive)),
        "outgoing" to JsonArray(outgoing.map { send ->
            Wire.objectOf(
                "id" to JsonPrimitive(send.id), "text" to JsonPrimitive(send.text),
                "pictures" to JsonArray(send.pictures.map {
                    JsonObject(mapOf("mimeType" to JsonPrimitive(it.mimeType), "data" to JsonPrimitive(Base64.getEncoder().encodeToString(it.data))))
                }),
                "accepted" to JsonPrimitive(send.accepted), "failure" to send.failure?.let(::JsonPrimitive), "mode" to outgoingModes[send.id]?.json(),
            )
        }),
        "mode" to mode?.json(),
        "hasMore" to JsonPrimitive(snapshot.hasMore),
    )

    private class Kept(
        val runs: List<Run>,
        val messages: Map<String, AgentMessage>,
        val pending: List<String>,
        val outgoing: List<Send>,
        val modes: Map<String, AgentMode>,
        val mode: AgentMode?,
        val hasMore: Boolean,
    ) {
        companion object {
            fun from(value: JsonElement): Kept {
                val o = Wire.obj(value)
                val modes = HashMap<String, AgentMode>()
                val outgoing = Wire.list(o, "outgoing") { element ->
                    val send = Wire.obj(element)
                    val id = Wire.string(send, "id")
                    send["mode"]?.takeIf { it != JsonNull }?.let { modes[id] = AgentMode.from(it) }
                    Send(
                        id, Wire.string(send, "text"),
                        Wire.list(send, "pictures") { Wire.obj(it).let { p -> OutgoingImage(Wire.string(p, "mimeType"), Base64.getDecoder().decode(Wire.string(p, "data"))) } },
                        Wire.bool(send, "accepted"), Wire.stringOrNull(send, "failure"),
                    )
                }
                return Kept(
                    runs = Wire.list(o, "runs") { Wire.obj(it).let { r -> Run(Wire.string(r, "id"), Wire.list(r, "messageIds") { id -> id.str!! }.toMutableList()) } },
                    messages = Wire.obj(o["messages"] ?: JsonNull).mapValues { AgentMessage.from(it.value) },
                    pending = Wire.list(o, "pending") { it.str!! },
                    outgoing = outgoing,
                    modes = modes,
                    mode = o["mode"]?.takeIf { it != JsonNull }?.let(AgentMode::from),
                    hasMore = Wire.bool(o, "hasMore"),
                )
            }
        }
    }

    // Mode

    /** The next message goes with this model, account and effort. */
    fun setMode(mode: AgentMode) {
        scope.launch {
            picked = mode
            this@StemCellConversation.mode = mode
            snapshot = snapshot.copy(mode = mode)
            publish()
            // A draft is its text with the mode it goes with, empty or not
            // (desktop's `saveMode`): saved at once with this one.
            draft = StemCellDraft(draft.text, stamp(after = draft.updatedAt), false)
            snapshot = snapshot.copy(draftUpdatedAt = draft.updatedAt)
            draftRevision += 1
            saveDraftSoon(0)
        }
    }

    private fun readDefaultMode(): Deferred<Result<AgentMode>> = scope.async { runCatching { client.defaultMode() } }

    private suspend fun sendingMode(): AgentMode {
        mode?.let { return it }
        val task = defaultMode ?: readDefaultMode().also { defaultMode = it }
        val read = task.await().getOrElse {
            defaultMode = null
            throw it
        }
        // A pick made while the default was read wins.
        mode?.let { return it }
        mode = read
        if (snapshot.mode == null) {
            snapshot = snapshot.copy(mode = read)
            publish()
        }
        return read
    }

    /** Accepted: it shows as waiting until its echo or its run arrives. */
    private fun sent(id: String) {
        val send = outgoing.firstOrNull { it.id == id } ?: return
        send.accepted = true
        // Until the run says when it began, the clock counts from the send.
        if (activeRun == null) activeRun = ActiveRun(null, now())
        snapshot = snapshot.copy(working = true)
        publish()
    }

    private fun failed(id: String, reason: String) {
        val send = outgoing.firstOrNull { it.id == id } ?: return
        send.failure = reason
        publish()
    }

    // Draft

    /** This phone's kept drafts: the one kept for this conversation shows at once, and goes to the computer if it never did. */
    suspend fun useDrafts(drafts: StemCellDrafts) = withContext(confined) {
        this@StemCellConversation.drafts = drafts
        if (creation != null) return@withContext
        val kept = drafts.draft(agentId) ?: return@withContext
        if (kept.updatedAt <= draft.updatedAt) return@withContext
        draft = kept
        snapshot = snapshot.copy(draft = kept.text, draftUpdatedAt = kept.updatedAt, draftArrivals = snapshot.draftArrivals + 1)
        publish()
        if (!kept.synced) saveDraftSoon(0)
    }

    /** Typed here, at `at` (ms since 1970, never going back): kept and saved after the keystrokes pause, a clear at once. */
    fun setDraft(text: String, at: Double) {
        scope.launch {
            if (at <= lastDraftEdit) return@launch
            lastDraftEdit = at
            if (text == draft.text) return@launch
            draft = StemCellDraft(text, maxOf(at, draft.updatedAt + 1), false)
            snapshot = snapshot.copy(draft = text, draftUpdatedAt = draft.updatedAt)
            draftRevision += 1
            saveDraftSoon(if (text.isEmpty()) 0 else DRAFT_DEBOUNCE_MS)
        }
    }

    /** The screen goes: what waits for the pause is saved now. */
    fun flushDraft() {
        scope.launch { if (!draft.synced) saveDraftSoon(0) }
    }

    private fun stamp(after: Double): Double = maxOf(Math.round(now()).toDouble(), after + 1)

    private fun saveDraftSoon(delayMs: Long) {
        draftSave?.cancel()
        val revision = draftRevision
        draftSave = scope.launch {
            if (delayMs > 0) delay(delayMs)
            saveDraft(revision)
        }
    }

    private suspend fun saveDraft(revision: Int) {
        val local = draft
        try {
            // New work's draft is under the ID the daemon gives it.
            creation?.let { agentId = it.await() }
            drafts?.keep(agentId, local)
            // Cleared, it is still the mode the next message goes with.
            val value = DraftValue(local.text, sendingMode())
            val saved = client.saveDraft(agentId, value, local.updatedAt, ClientId.make())
            // Typed or taken meanwhile: that one is saved next.
            if (revision != draftRevision) return
            if ((saved.updatedAt ?: 0.0) > local.updatedAt) {
                // Written later on another device: it stands.
                adoptDraft(saved, publishes = true)
            } else {
                draft = draft.copy(synced = true)
                drafts?.keep(agentId, draft)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Kept unsynced here: it goes on the next keystroke, opening or read.
        }
    }

    /**
     * The computer's draft, from a read, an event or a save's answer: it
     * replaces this phone's unless this phone's is newer, which goes up.
     */
    private fun adoptDraft(remote: AgentDraft, publishes: Boolean) {
        val stamp = remote.updatedAt ?: 0.0
        val text = remote.value?.text ?: ""
        // Older, or this phone's own coming back: this phone's stands, and
        // goes up with its own save.
        if (stamp < draft.updatedAt || (stamp == draft.updatedAt && text == draft.text)) {
            if (stamp == draft.updatedAt) draft = draft.copy(synced = true)
            return
        }
        draftSave?.cancel()
        draftRevision += 1
        draft = StemCellDraft(text, stamp, true)
        var next = snapshot
        // Newer than anything picked here: its mode is the one now.
        remote.value?.mode?.let {
            if (picked != null) picked = it
            mode = it
            next = next.copy(mode = it)
        }
        next = next.copy(draftUpdatedAt = stamp)
        // Only new words are set in the composer; a mode alone isn't.
        if (next.draft != text) next = next.copy(draft = text, draftArrivals = next.draftArrivals + 1)
        snapshot = next
        if (creation == null) drafts?.keep(agentId, draft)
        if (publishes) publish()
    }

    suspend fun abort() = withContext(confined) {
        try {
            client.abort(agentId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun markRead() {
        val agentId = agentId
        scope.launch {
            try {
                client.markRead(agentId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // Snapshot

    private fun publish() {
        keepConversation()
        val items = ArrayList<StemCellChatItem>()
        // What a layout block's `<img src>` can name: media shown earlier.
        val shown = HashMap<String, StemCellMedia>()
        for (run in runs) {
            // The newest run while the agent works may end mid-block.
            val streaming = snapshot.working && run.id == runs.lastOrNull()?.id
            for (id in run.messageIds) messages[id]?.let { message -> item(message, shown, streaming)?.let(items::add) }
        }
        for (id in pending) messages[id]?.let { message -> item(message, shown, false)?.let(items::add) }
        for (send in outgoing) {
            val pieces = ArrayList<StemCellChatItem.Piece>()
            if (send.text.isNotEmpty()) pieces += StemCellChatItem.Piece.Text(send.text)
            send.pictures.forEachIndexed { offset, picture ->
                pieces += StemCellChatItem.Piece.Picture("${send.id}:${offset + 1}", picture.mimeType, picture.data)
            }
            val delivery = send.failure?.let { StemCellChatItem.Delivery.Failed(it) }
                ?: if (send.accepted) StemCellChatItem.Delivery.Pending else StemCellChatItem.Delivery.Sending
            items += StemCellChatItem(send.id, StemCellChatItem.Role.User, pieces, delivery)
        }
        // Only steering for a run already going: a send that starts one is
        // its first message, not next in line.
        var queued = 0
        if (snapshot.working) {
            for (item in items.asReversed()) {
                if (item.role != StemCellChatItem.Role.User) break
                if (item.delivery != StemCellChatItem.Delivery.Pending && item.delivery != StemCellChatItem.Delivery.Sending) break
                queued += 1
            }
            // Before the run says it began, the first of them is what starts it.
            if (activeRun?.id == null) queued = maxOf(0, queued - 1)
        }
        snapshot = snapshot.copy(
            items = items,
            queued = queued,
            activity = StemCellActivity.project(
                working = snapshot.working, status = status, wait = wait, retrying = retrying, question = question,
                subagentsRunning = subagentsRunning, produced = produced, startedAt = activeRun?.startedAt,
            ),
        )
        updatesFlow.value = snapshot
    }

    /**
     * The running turn has shown something of the agent's: a thought, a
     * line, a tool call. What another agent sent it is work coming in, not
     * its own.
     */
    private val produced: Boolean
        get() {
            val id = activeRun?.id ?: return false
            val run = runs.firstOrNull { it.id == id } ?: return false
            return run.messageIds.any { messageId ->
                messages[messageId]?.let { it.role != "user" && it.content.isNotEmpty() && (it.metadata?.senderAgentId ?: agentId) == agentId } ?: false
            }
        }

    /** The turn is over: nothing of it carries into the next one. */
    private fun settle() {
        activeRun = null
        status = "idle"
        wait = null
        retrying = null
    }

    private fun adoptAgent(agent: Agent) {
        // A read says only working or idle; which kind of work is in the
        // events, so a read mid-turn keeps what they last said.
        if (!(agent.status == "working" && status != "idle")) status = agent.status
        wait = agent.wait
        retrying = agent.retrying
        question = agent.pendingQuestionId != null
        subagentsRunning = agent.subagents?.running ?: 0
        if (agent.id == agentId) parentAgentId = agent.parentAgentId
    }

    private fun item(message: AgentMessage, shown: MutableMap<String, StemCellMedia>, streaming: Boolean): StemCellChatItem? {
        if (message.role == "user" || message.role == "agent") {
            val sender = collaborator(message, agentId, parentAgentId)
            if (sender != null) {
                // Its words as they came, read only when opened; desktop trims the
                // daemon's addressing line from an agent-role message alone.
                val text = message.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
                val body = if (message.role == "agent") inboundText(text, sender.agentId) else text
                val trimmed = body.trim()
                return StemCellChatItem(
                    message.id, StemCellChatItem.Role.Collaborator(sender.agentId, sender.explanation),
                    if (trimmed.isEmpty()) emptyList() else listOf(StemCellChatItem.Piece.Text(trimmed)), StemCellChatItem.Delivery.Sent,
                )
            }
        }
        // The agent that manages this one gives it its work: desktop reads
        // that as dialogue, in the lane the person's own words take.
        val manager = if (message.role == "agent") message.metadata?.senderAgentId?.takeIf { it != agentId } else null
        val role = when (message.role) {
            "user" -> StemCellChatItem.Role.User
            "agent" -> if (manager == null) StemCellChatItem.Role.Agent else StemCellChatItem.Role.User
            else -> return null
        }
        val pieces = ArrayList<StemCellChatItem.Piece>()
        message.content.forEachIndexed { index, block ->
            when (block) {
                is Block.Text -> {
                    val trimmed = (manager?.let { inboundText(block.text, it) } ?: block.text).trim()
                    if (trimmed.isEmpty()) return@forEachIndexed
                    pieces += if (role == StemCellChatItem.Role.Agent) {
                        Companion.pieces(trimmed, shown, streaming || message.status == "streaming")
                    } else {
                        listOf(StemCellChatItem.Piece.Text(trimmed))
                    }
                }
                is Block.Image -> {
                    val key = "${message.id}:$index"
                    if (pictures[key] == null) runCatching { Base64.getDecoder().decode(block.data) }.getOrNull()?.let { pictures[key] = it }
                    pictures[key]?.let { pieces += StemCellChatItem.Piece.Picture(key, block.mimeType, it) }
                }
                is Block.ToolCall -> {
                    val presentation = block.presentation ?: return@forEachIndexed
                    val artifactId = presentation.artifactId
                    if (presentation.type == "artifact" && artifactId != null) {
                        when (presentation.artifactType) {
                            // One card across every version: a revision redraws its rows.
                            "sidebar_proposal" -> {
                                val proposal = StemCellChatItem.Piece.SidebarProposal(artifactId)
                                if (proposal !in pieces) pieces += proposal
                            }
                            // Stories: a `story` artifact is the story, by its ID.
                            "story" -> pieces += StemCellChatItem.Piece.Story(artifactId)
                            else -> pieces += StemCellChatItem.Piece.Artifact(
                                StemCellArtifact(
                                    id = artifactId, version = presentation.version ?: 1, type = presentation.artifactType ?: "unknown",
                                    title = presentation.title ?: "", followsLatest = presentation.shows == "latest",
                                ),
                            )
                        }
                        return@forEachIndexed
                    }
                    if (presentation.type != "media") return@forEachIndexed
                    val media = presentation.items.orEmpty().mapIndexed { offset, item -> StemCellMedia(block.id, offset, item) }
                    for (one in media) {
                        // Named by a later layout block, by either path; the latest wins.
                        one.item.source?.let {
                            shown[it.path] = one
                            shown[it.absolutePath] = one
                        }
                    }
                    val tree = StemCellMediaTree.decode(presentation.layout, media.size)
                    if (tree != null) {
                        pieces += StemCellChatItem.Piece.Tree(media, tree)
                        return@forEachIndexed
                    }
                    val visual = media.filter { it.isVisual }
                    if (visual.isNotEmpty()) pieces += StemCellChatItem.Piece.Gallery(visual, columns(presentation.layout, visual.size))
                    for (one in media) if (!one.isVisual) pieces += StemCellChatItem.Piece.File(one)
                }
                is Block.Reasoning, is Block.Unknown -> Unit
            }
        }
        if (pieces.isEmpty()) return null
        val delivery = if (role == StemCellChatItem.Role.User && message.status == "pending") StemCellChatItem.Delivery.Pending else StemCellChatItem.Delivery.Sent
        return StemCellChatItem(message.id, role, pieces, delivery)
    }

    /** Who sent a message, when it is another agent. */
    data class Sender(val agentId: String, val explanation: String?)

    companion object {
        const val DRAFT_DEBOUNCE_MS = 300L
        private const val NOT_SAVED = "Could not save this message on this device."

        internal fun decodeMessage(value: JsonElement): AgentMessage? = runCatching { AgentMessage.from(value) }.getOrNull()

        /**
         * `message.delta` applied exactly as the API says, or null where the
         * message must be read again. Offsets count UTF-16 units, as a Kotlin
         * string does.
         */
        fun grow(message: AgentMessage, block: Int, offset: Int, append: String): AgentMessage? {
            val content = message.content
            if (block !in content.indices) return null
            val current = when (val it = content[block]) {
                is Block.Text -> it.text
                is Block.Reasoning -> it.text
                else -> return null
            }
            if (offset < 0 || current.length < offset) return null
            val after = current.substring(offset)
            val grown = when {
                after.isEmpty() -> current + append
                after.length >= append.length -> return if (after.startsWith(append)) message else null
                else -> {
                    if (!append.startsWith(after)) return null
                    current + append.substring(after.length)
                }
            }
            val replaced = if (content[block] is Block.Reasoning) Block.Reasoning(grown) else Block.Text(grown)
            return message.copy(content = content.toMutableList().also { it[block] = replaced })
        }

        /**
         * `columns` as the presentation asks, or 0 for desktop's justified
         * rows when it asks for none or for a layout the phone doesn't know.
         */
        fun columns(layout: JsonElement?, count: Int): Int {
            if (layout["type"].str == "columns") {
                val columns = layout["columns"].num
                if (columns == 1.0 || columns == 2.0) return columns.toInt()
            }
            return 0
        }

        /**
         * An agent's text with its layout blocks as the trees they make of
         * media shown earlier. A block that places nothing shown, or lost
         * content to a limit, is the Markdown it is; one still arriving is held
         * back until it closes.
         */
        fun pieces(text: String, shown: Map<String, StemCellMedia>, streaming: Boolean): List<StemCellChatItem.Piece> {
            val pieces = ArrayList<StemCellChatItem.Piece>()
            fun markdown(text: String) {
                val trimmed = text.trim()
                if (trimmed.isEmpty()) return
                val previous = pieces.lastOrNull() as? StemCellChatItem.Piece.Text
                if (previous != null) pieces[pieces.size - 1] = StemCellChatItem.Piece.Text(previous.text + "\n\n" + trimmed)
                else pieces += StemCellChatItem.Piece.Text(trimmed)
            }
            // A reference tag still arriving is held back, not flashed as raw text.
            val source = if (streaming) HappyReferences.droppingPartialTag(text) else text
            for (segment in StemCellMediaBlocks.split(source)) {
                when (segment) {
                    is StemCellMediaBlocks.Segment.Markdown -> markdown(segment.text)
                    is StemCellMediaBlocks.Segment.Layout -> {
                        if (!segment.complete) {
                            if (!streaming) markdown(segment.raw)
                            continue
                        }
                        // Each `src` names one item; the same item twice is the same index.
                        val media = ArrayList<StemCellMedia>()
                        val parsed = StemCellMediaTree.parse(segment.raw) { src ->
                            val found = shown[src] ?: return@parse null
                            val index = media.indexOfFirst { it.key == found.key }
                            if (index >= 0) return@parse index
                            media += found
                            media.size - 1
                        }
                        if (parsed.placed.isEmpty() || parsed.truncated) {
                            markdown(segment.raw)
                            continue
                        }
                        pieces += StemCellChatItem.Piece.Tree(media, parsed.tree)
                    }
                }
            }
            return pieces
        }

        /**
         * The agent that sent `message`, when it is neither this one nor the
         * one that manages it (desktop's `senderAgentOf`, relation "other"),
         * and its one line on what it sent.
         */
        fun collaborator(message: AgentMessage, agentId: String, parentAgentId: String?): Sender? {
            val sender = message.metadata?.senderAgentId ?: return null
            if (sender == agentId || sender == parentAgentId) return null
            return Sender(sender, message.metadata.senderAgentExplanation?.takeIf { it.isNotEmpty() })
        }

        /**
         * What another agent said, without the line the daemon puts ahead of
         * it for the receiving model (`inboundAgentText`): the row names the
         * sender already.
         */
        fun inboundText(text: String, agentId: String): String {
            val lead = "Message from agent $agentId:"
            if (!text.startsWith(lead)) return text
            return text.substring(lead.length).trimStart()
        }

        private operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
        private val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        private val JsonElement?.num: Double? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        private fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.mapNotNull { it.str } ?: emptyList()
    }
}
