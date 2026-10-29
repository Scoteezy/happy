package com.slopus.happy.stemcells

import com.slopus.happy.direct.ClientId
import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectAgentError
import com.slopus.happy.direct.DirectEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** What the Stem Cells sidebar shows (happy-ios `StemCellsSnapshot`). */
data class StemCellsSnapshot(
    val availability: Availability = Availability.Loading,
    /** Whether the events stream is open, so what shows is live. */
    val live: Boolean = false,
    /** The rows a fold leaves showing, in order. */
    val rows: List<StemCellsRow> = emptyList(),
    /** Every row, folded away or not, for search. */
    val allRows: List<StemCellsRow> = emptyList(),
    /** The document those rows are drawn from, for gestures that edit it. */
    val document: SpaceSidebarDocument? = null,
    /** Happy's own row, below which new work goes. */
    val happySpaceId: String? = null,
    /** The person's Stem Cells setting, which runs Prioritizing; null until read. */
    val prioritizing: Boolean? = null,
    /** The last act the daemon refused: its reason, empty when it gave none. */
    val error: String? = null,
    /** Why the last new work could not be made, as `error`. */
    val stemCellError: String? = null,
    /** What the references in messages read as and open. */
    val references: StemCellReferences = StemCellReferences(),
) {
    enum class Availability {
        /** Nothing read yet. */
        Loading,
        /** The daemon's sidebar, or the last one kept on this phone until it answers. */
        Available,
        /** A daemon from before the sidebar document. */
        Unsupported,
    }
}

/** New work, named on this phone before the daemon answers. */
class StemCellCreation(
    val spaceId: String,
    val agentId: String,
    val workspaceId: String,
    /**
     * The conversation's ID once the daemon has made it (`agentId` when the
     * daemon takes the phone's names); a refusal takes the row away again.
     */
    val created: Deferred<String>,
)

/**
 * One paired computer's Stem Cells sidebar, a port of happy-ios
 * `StemCellsStore` (desktop's `spacesStore.ts` in its document mode): the
 * document the daemon was last heard to hold, with this phone's unconfirmed
 * edits applied on top by the same function the daemon applies them with,
 * so a confirmation draws exactly what was already drawn and nothing moves.
 *
 * `scope` must run one coroutine at a time (a single-threaded or
 * `limitedParallelism(1)` dispatcher): it plays the Swift actor. Every
 * public call hops onto it.
 */
class StemCellsStore(
    private val client: DirectAgentClient,
    private val offline: StemCellsOfflineFile? = null,
    private val scope: CoroutineScope,
    private val labels: StemCellsRows.Labels = StemCellsRows.Labels(),
    /** A monotonic clock in milliseconds, for attention. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val confined = scope.coroutineContext.minusKey(Job)
    private val updatesFlow = MutableStateFlow(StemCellsSnapshot())

    /** Every change, the latest kept. */
    val updates: StateFlow<StemCellsSnapshot> = updatesFlow.asStateFlow()

    private var snapshot = StemCellsSnapshot()
    private var keeping: Job? = null
    private var following: Job? = null
    /** The last Prioritizing write; each waits for the one before, so the daemon ends on the last choice. */
    private var prioritizingWrite: Job? = null
    private var prioritizingWrites = 0
    /** What the daemon last said Prioritizing is; a refused write, the last one asked for, shows this again. */
    private var prioritizingDaemon: Boolean? = null
    private var cursor: String? = null
    private var capabilities = AgentCapabilities.none

    // The document.
    private var artifactId: String? = null
    /** The document the daemon was last heard to hold. */
    private var held: Held? = null
    private class Held(val version: Int, val html: String)
    private class Batch(val mutationId: String, val operations: List<SpaceSidebarOperation>, val base: SpaceSidebarDocument)
    /** This phone's edits the daemon has not confirmed yet, oldest first. */
    private val batches = ArrayList<Batch>()
    /** New work drawn where it was made until the held document has it. */
    private class Insert(val spaceId: String, val sectionId: String?, val happySpaceId: String?)
    private val inserts = ArrayList<Insert>()
    private val creating = HashSet<String>()
    /** New work being made, by space: what putting it away waits for. */
    private val creations = HashMap<String, Deferred<String>>()
    /** New work put away here: off the sidebar at once, before the daemon hears, and back if it refuses. */
    private val awayIds = LinkedHashSet<String>()
    /** Of those, the ones the computer has closed: let go once its document no longer has them. */
    private val closedIds = HashSet<String>()
    private var writes = 0
    private var dragging = false
    /** An event or read waited for the hand to lift. */
    private var stale = false
    private var loading: Job? = null
    /** Events that arrived while the authoritative read was in flight. */
    private var heldEvents: MutableList<JsonObject>? = null
    private var writeQueue: Job? = null

    // What the rows are drawn from.
    private val spaces = HashMap<String, Space>()
    private var agentIcons: Map<String, String> = emptyMap()
    private var bots: List<JsonElement> = emptyList()
    private var tasks: List<JsonElement> = emptyList()
    private var surfaces: MutableList<Surface> = ArrayList()
    private var agents = StemCellsAgents()
    /** Artifact titles references asked for, as read; and those being read. */
    private val artifactTitles = HashMap<String, StemCellReferences.Artifact>()
    private val artifactReads = HashSet<String>()
    private var places = StemCellsPlaces()
    private var placesReload: Job? = null
    /** Names given here whose rename no read has carried yet. */
    private class TitleIntent(val title: String, var base: String?)
    private val titleIntents = HashMap<String, TitleIntent>()

    // Attention: Prioritizing holds its batches while the person is in the list.
    private var attention: Pair<String, Long>? = null
    private var attentionIdle: Job? = null
    private var attentionVisible = true

    // Life

    /** A sync engine owns the stream; the store only restores what was kept. */
    fun startExternal() = scope.launch { restoreOffline() }

    fun externalResync(loadSidebar: Boolean) = scope.launch {
        for (listener in listeners) listener.channel.trySend(DirectEvent.Resync)
        snapshot = snapshot.copy(live = true)
        if (loadSidebar) load() else publish()
    }

    fun externalEvent(envelope: JsonObject) = scope.launch { dispatch(envelope) }

    /** Follows the event stream itself, for a computer no sync engine owns. */
    fun start() = scope.launch {
        if (following != null) return@launch
        following = scope.launch {
            restoreOffline()
            follow()
        }
    }

    /**
     * Closes the events stream while the app is in the background; what
     * shows stays, and [start] resumes from the last cursor.
     */
    fun pause() = scope.launch {
        following?.cancel(); following = null
        // Kept now rather than after the app may have been closed.
        keeping?.let { it.cancel(); keeping = null; saveOffline() }
        if (snapshot.live) { snapshot = snapshot.copy(live = false); publish() }
    }

    /** Waits for every edit already made to be written or refused. */
    suspend fun flush() = withContext(confined) {
        while (true) {
            val queue = writeQueue ?: break
            queue.join()
            if (writeQueue == queue) break
        }
    }

    suspend fun stop() {
        flush()
        withContext(confined) {
            following?.cancel(); following = null
            loading?.cancel(); loading = null
            placesReload?.cancel(); placesReload = null
            attentionIdle?.cancel(); attentionIdle = null
            keeping?.cancel(); keeping = null
            for (listener in listeners) listener.channel.close()
            listeners.clear()
        }
        client.close()
    }

    private suspend fun follow() {
        var attempt = 0
        while (currentActive()) {
            try {
                client.events(cursor).collect { frame ->
                    if (frame.event == "hello") {
                        attempt = 0
                        val hello = runCatching { DirectAgentClient.parse(frame.data.toByteArray()) as? JsonObject }.getOrNull()
                        val resumed = hello.bool("resumed") == true && hello.bool("gap") != true
                        hello.string("cursor")?.let { next -> if (!resumed || cursor == null) cursor = next }
                        snapshot = snapshot.copy(live = true)
                        if (!resumed) for (listener in listeners) listener.channel.trySend(DirectEvent.Resync)
                        if (!resumed || held == null) load() else publish()
                        return@collect
                    }
                    frame.id?.let { cursor = it }
                    val envelope = runCatching { DirectAgentClient.parse(frame.data.toByteArray()) as? JsonObject }.getOrNull() ?: return@collect
                    dispatch(envelope)
                }
            } catch (_: DirectAgentError.Revoked) {
                snapshot = snapshot.copy(live = false); publish()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            if (!currentActive()) return
            if (snapshot.live) { snapshot = snapshot.copy(live = false); publish() }
            val seconds = minOf(30L, 1L shl minOf(attempt, 5))
            attempt++
            delay(seconds * 1000)
        }
    }

    private suspend fun currentActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    private fun dispatch(envelope: JsonObject) {
        val payload = envelope["payload"] as? JsonObject
        val key = payload.string("agentId") ?: payload.string("surfaceId") ?: (payload?.get("todo") as? JsonObject).string("surfaceId")
        val type = envelope.string("type")
        for (listener in listeners) {
            val matches = if (listener.key != null) listener.key == key else type != null && listener.prefixes.any { type.startsWith(it) }
            if (matches) listener.channel.trySend(DirectEvent.Event(envelope))
        }
        receive(envelope)
    }

    // Conversations

    private class Listener(val key: String?, val prefixes: List<String>, val channel: SendChannel<DirectEvent>)
    private val listeners = ArrayList<Listener>()

    private fun listen(key: String?, prefixes: List<String>): Flow<DirectEvent> = callbackFlow {
        val listener = Listener(key, prefixes, channel)
        withContext(confined) { listeners += listener }
        awaitClose { scope.launch { listeners -= listener } }
    }.buffer(Channel.UNLIMITED)

    /**
     * One conversation's events off this store's stream, so an open chat
     * needs no stream of its own. `key` is a conversation's agent ID, or a
     * Surface's ID for its todos. `Resync` means some were lost: read again.
     */
    fun conversationEvents(key: String): Flow<DirectEvent> = listen(key, emptyList())

    /** Every event whose type starts with one of `prefixes` (`"story."`), for what no conversation or Surface key names. */
    fun events(prefixes: List<String>): Flow<DirectEvent> = listen(null, prefixes)

    // Reads

    /** Everything the sidebar is drawn from, read whole. Events that arrive meanwhile are applied after it, in order. */
    private suspend fun load() {
        loading?.let { it.join(); return }
        heldEvents = ArrayList()
        val job = scope.launch { read() }
        loading = job
        job.join()
        loading = null
    }

    private suspend fun read() {
        try {
            coroutineScope {
                val health = async { runCatching { client.health() } }
                val sidebar = async { runCatching { client.sidebar(widgets = true) } }
                val botsRead = async { runCatching { client.bots() } }
                val tasksRead = async { runCatching { client.tasks() } }
                val setting = async { runCatching { client.userSetting(STEM_CELLS_SETTING_KEY) } }
                val surfaceList = async { runCatching { client.surfaces() } }
                val read = health.await().getOrNull()
                capabilities = read?.let { AgentCapabilities(it.stemCellIdentities, it.sidebarOperations, it.sidebarProposals, it.sidebarAttachments) } ?: AgentCapabilities.none
                if (capabilities.sidebarOperations != true) {
                    snapshot = snapshot.copy(availability = StemCellsSnapshot.Availability.Unsupported)
                    heldEvents = null
                    publish()
                    return@coroutineScope
                }
                val answer = sidebar.await().getOrThrow()
                val botList = botsRead.await().getOrThrow()
                val taskList = tasksRead.await().getOrThrow()
                val prioritizing = setting.await().getOrNull().isTrue()
                snapshot = snapshot.copy(prioritizing = prioritizing)
                prioritizingDaemon = prioritizing
                surfaces = surfaceList.await().getOrNull()?.toMutableList() ?: ArrayList()
                bots = botList; tasks = taskList
                agents = StemCellsAgents()
                for (owner in botList + taskList) owner.field("agent")?.let(agents::absorb)
                spaces.clear()
                for (space in answer.spaces) adopt(space)
                agentIcons = answer.agentIcons ?: emptyMap()
                artifactId = answer.artifactId
                if (!(writes > 0 || dragging)) {
                    if (held.let { it == null || it.version <= answer.version }) held = Held(answer.version, answer.html)
                } else {
                    stale = true
                }
                snapshot = snapshot.copy(availability = StemCellsSnapshot.Availability.Available)
                rebuildPlaces()
                val pending = heldEvents.orEmpty()
                heldEvents = null
                // What changed while away is read again when next asked for.
                artifactTitles.clear()
                for (event in pending) receive(event)
                publish()
                keeping?.cancel()
                saveOffline()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            heldEvents = null
            if (snapshot.availability == StemCellsSnapshot.Availability.Loading && e is DirectAgentError.Status && e.status == 404) {
                snapshot = snapshot.copy(availability = StemCellsSnapshot.Availability.Unsupported)
            }
            publish()
        }
    }

    /** Reads the document again, unless a hand or a write holds it. */
    private fun reloadSidebar() {
        if (writes > 0 || dragging) { stale = true; return }
        scope.launch {
            val answer = runCatching { client.sidebar(widgets = true) }.getOrNull() ?: return@launch
            adoptSidebar(answer)
        }
    }

    private fun adoptSidebar(answer: SidebarResponse) {
        if (writes > 0 || dragging) { stale = true; return }
        held?.let { if (it.version > answer.version) return }
        held = Held(answer.version, answer.html)
        artifactId = answer.artifactId
        agentIcons = answer.agentIcons ?: agentIcons
        for (space in answer.spaces) adopt(space)
        publish()
    }

    private fun schedulePlacesReload() {
        placesReload?.cancel()
        placesReload = scope.launch {
            delay(300)
            val bots = runCatching { client.bots() }.getOrNull() ?: return@launch
            val tasks = runCatching { client.tasks() }.getOrNull() ?: return@launch
            adoptPlaces(bots, tasks)
        }
    }

    private fun adoptPlaces(bots: List<JsonElement>, tasks: List<JsonElement>) {
        this.bots = bots; this.tasks = tasks
        for (owner in bots + tasks) owner.field("agent")?.let(agents::absorb)
        rebuildPlaces()
        publish()
    }

    /** The bots and tasks, each with its conversation composed from the newest copies. */
    private fun composed(owners: List<JsonElement>): List<JsonElement> = owners.map { owner ->
        val o = owner as? JsonObject ?: return@map owner
        val id = o["agent"].string("id") ?: return@map owner
        agents.tree(id)?.let { JsonObject(o + ("agent" to it)) } ?: owner
    }

    /** Reads the titles of artifacts messages name, once each; what is read is published with the references. */
    fun readArtifacts(ids: List<String>) = scope.launch {
        val wanted = ids.filter { it !in artifactTitles && it !in artifactReads }.distinct()
        if (wanted.isEmpty()) return@launch
        artifactReads += wanted
        for (id in wanted) {
            val read = try {
                StemCellReferences.artifact(client.artifact(id))
            } catch (e: DirectAgentError.Status) {
                if (e.status == 404) StemCellReferences.Artifact.Missing else null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            artifactReads -= id
            if (read != null) {
                artifactTitles[id] = read
                publish()
            }
        }
    }

    /** The bots and tasks with their conversations composed from the newest copies. */
    private fun rebuildPlaces() {
        places = StemCellsPlaces(
            bots = composed(bots).mapNotNull { runCatching { Bot.from(it) }.getOrNull() },
            tasks = composed(tasks).mapNotNull { runCatching { AgentTask.from(it) }.getOrNull() },
            surfaces = surfaces.toList(),
        )
    }

    // Events

    private fun receive(event: JsonObject) {
        heldEvents?.let { it += event; return }
        val type = event.string("type") ?: return
        val payload = event["payload"] as? JsonObject
        when (type) {
            "space.created", "space.updated" -> payload?.get("space")?.let { runCatching { Space.from(it) }.getOrNull() }?.let(::adopt)
            "space.removed" -> payload.string("spaceId")?.let { id ->
                spaces.remove(id)
                inserts.removeAll { it.spaceId == id }
            }
            "space.sidebar.changed" -> sidebarChanged(payload)
            "agent.updated" -> {
                val id = payload.string("agentId") ?: return
                val followed = agents.update(id, payload.string("previousVersion"), payload.string("version"), (payload?.get("changes") as? JsonObject).orEmpty())
                if (followed) rebuildPlaces() else schedulePlacesReload()
            }
            "agent.created" -> payload?.get("agent")?.let { agents.created(it); rebuildPlaces() }
            "bot.created", "bot.updated", "task.created", "task.updated", "task.joined", "task.left" -> schedulePlacesReload()
            "surface.created" -> payload?.get("surface")?.let { runCatching { Surface.from(it) }.getOrNull() }?.let { surface ->
                surfaces.removeAll { it.id == surface.id }
                surfaces += surface
                rebuildPlaces()
            }
            "surface.updated" -> {
                val id = payload.string("surfaceId") ?: payload?.get("surface").string("id")
                val name = payload?.get("changes").string("name") ?: payload.string("name")
                val index = surfaces.indexOfFirst { it.id == id }
                if (id != null && name != null && index >= 0) {
                    surfaces[index] = surfaces[index].copy(name = name)
                    rebuildPlaces()
                }
            }
            "artifact.updated", "artifact.deleted" -> {
                // Only the ones a message named are kept.
                val id = payload.string("artifactId") ?: return
                val heldTitle = artifactTitles[id] ?: return
                val changes = payload?.get("changes")
                if (type == "artifact.deleted" || changes.string("status") == "deleted") {
                    val title = when (heldTitle) {
                        is StemCellReferences.Artifact.Active -> heldTitle.title
                        is StemCellReferences.Artifact.Deleted -> heldTitle.title
                        StemCellReferences.Artifact.Missing -> ""
                    }
                    artifactTitles[id] = StemCellReferences.Artifact.Deleted(title)
                } else if (heldTitle is StemCellReferences.Artifact.Active) {
                    artifactTitles[id] = StemCellReferences.Artifact.Active(
                        changes.string("title") ?: heldTitle.title, heldTitle.type,
                        changes.number("latestVersion")?.toInt() ?: heldTitle.latestVersion,
                    )
                }
            }
            "user_settings.updated" -> {
                val setting = payload?.get("setting")
                if (setting.string("key") == STEM_CELLS_SETTING_KEY) {
                    val on = setting.field("value").isTrue()
                    snapshot = snapshot.copy(prioritizing = on)
                    prioritizingDaemon = on
                }
            }
            else -> return
        }
        publish()
    }

    private fun sidebarChanged(payload: JsonObject?) {
        val artifactId = artifactId ?: return
        val held = held ?: return
        if (payload.string("artifactId") != artifactId) return
        val version = payload.number("version")?.toInt() ?: return
        if (version <= held.version) return
        // Nothing lands under a hand: the read after the release catches up.
        if (dragging) { stale = true; return }
        val operations = (payload?.get("operations") as? JsonArray)?.mapNotNull { SpaceSidebarOperation.from(it) }
        val applied = if (payload.number("previousVersion")?.toInt() == held.version && operations != null) {
            runCatching { SpaceSidebar.apply(SpaceSidebar.parse(held.html), operations) }.getOrNull()
        } else {
            null
        }
        if (applied == null) { reloadSidebar(); return }
        this.held = Held(version, SpaceSidebar.format(applied.document))
        // This phone's own edit, confirmed: it is already on screen.
        payload.string("mutationId")?.let { mutationId -> batches.removeAll { it.mutationId == mutationId } }
    }

    private fun adopt(incoming: Space) {
        spaces[incoming.id]?.let { if (it.version > incoming.version) return }
        var space = incoming
        titleIntents[space.id]?.let { intent ->
            if (space.title == intent.title) {
                titleIntents.remove(space.id)
            } else {
                intent.base = space.title
                space = space.copy(title = intent.title)
            }
        }
        spaces[space.id] = space
    }

    /**
     * Another sidebar document (a proposal's) as this sidebar's rows, with
     * the spaces, places and icons held now: every row, folds included.
     */
    suspend fun rows(html: String): List<StemCellsRow>? = withContext(confined) {
        val document = runCatching { SpaceSidebar.parse(html) }.getOrNull() ?: return@withContext null
        StemCellsRows.all(document, spaces, places, agentIcons, labels)
    }

    // The document on screen

    /** The held document with every unconfirmed edit on top, without new work. */
    private fun confirmedShown(): SpaceSidebarDocument? {
        val held = held ?: return null
        var document = runCatching { SpaceSidebar.parse(held.html) }.getOrNull() ?: return null
        for (batch in batches) {
            // Already in the held document, or no longer possible: the
            // daemon's answer for it decides, and it draws nothing now.
            runCatching { SpaceSidebar.apply(document, batch.operations, batch.base) }.getOrNull()?.let { document = it.document }
        }
        return document
    }

    /** What is on screen: the confirmed document and new work where it was made. */
    private fun shown(): SpaceSidebarDocument? {
        var document = confirmedShown() ?: return null
        for (id in closedIds.toList()) {
            if (SpaceSidebar.locate(document, id) == null) {
                closedIds.remove(id)
                awayIds.remove(id)
            }
        }
        val confirmed = document
        inserts.removeAll { it.spaceId !in creating && SpaceSidebar.locate(confirmed, it.spaceId) != null }
        for (insert in inserts) {
            if (SpaceSidebar.locate(document, insert.spaceId) != null) continue
            document = SidebarDocumentEdits.insert(document, insert.spaceId, insert.sectionId, insert.happySpaceId) ?: document
        }
        for (id in awayIds) {
            runCatching { SpaceSidebar.apply(document, listOf(SpaceSidebarOperation.Remove(id))) }.getOrNull()?.let { document = it.document }
        }
        return document
    }

    private fun publish() {
        shown()?.let { document ->
            val all = StemCellsRows.all(document, spaces, places, agentIcons, labels)
            snapshot = snapshot.copy(rows = StemCellsRows.visible(all), allRows = all, document = document)
        }
        val references = StemCellReferences.entities(composed(bots + tasks)).copy(artifacts = HashMap(artifactTitles))
        snapshot = snapshot.copy(happySpaceId = spaces.values.firstOrNull { places.isHappy(it) }?.id, references = references)
        updatesFlow.value = snapshot
        // What the events changed is kept too, once they settle.
        if (snapshot.live && held != null) keepSoon()
    }

    private fun keepSoon() {
        keeping?.cancel()
        keeping = scope.launch {
            delay(2_000)
            keeping = null
            saveOffline()
        }
    }

    // Edits

    /**
     * One edit of the document, sent as the operations that make it. It is
     * on screen at once and stays exactly there when the daemon confirms it;
     * a refusal takes only this edit back off.
     */
    private fun edit(claims: List<String> = emptyList(), change: (SpaceSidebarDocument) -> SpaceSidebarDocument?) {
        if (held == null) return
        val base = confirmedShown() ?: return
        val proposed = change(base) ?: return
        val operations = runCatching { SpaceSidebar.diff(base, proposed) }.getOrNull()?.toMutableList() ?: return
        if (operations.isEmpty()) return
        for (id in claims) {
            if (operations.none { it is SpaceSidebarOperation.Set && it.fields.id == id && it.fields.expanded != null }) {
                operations += SpaceSidebarOperation.Set(SpaceSidebarSet(id, expanded = true))
            }
        }
        val batch = Batch(ClientId.make(), operations, base)
        batches += batch
        snapshot = snapshot.copy(error = null)
        publish()
        writes++
        val previous = writeQueue
        writeQueue = scope.launch {
            previous?.join()
            write(batch)
        }
    }

    private suspend fun write(batch: Batch) {
        try {
            var answer: OperationsResponse? = null
            for (attempt in 0 until 3) {
                try {
                    // Made against what was on screen, sent against what the daemon last said; it rebases anything in between.
                    answer = client.applySidebarOperations(batch.mutationId, held?.version ?: 0, batch.operations)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Only a request that was not heard is sent again, under the same mutation ID.
                    if (refusal(e) || attempt == 2) {
                        batches.removeAll { it.mutationId == batch.mutationId }
                        snapshot = snapshot.copy(error = describe(e))
                        stale = true
                        publish()
                        return
                    }
                    delay(400L * (attempt + 1))
                }
            }
            val done = answer ?: return
            batches.removeAll { it.mutationId == batch.mutationId }
            if (held.let { it == null || done.version > it.version }) held = Held(done.version, done.html)
            publish()
        } finally {
            writes--
            released()
        }
    }

    private fun released() {
        if (dragging || writes != 0) return
        attentionRestart()
        if (stale) { stale = false; reloadSidebar() }
    }

    fun move(nodeId: String, after: String?) = scope.launch { edit { SidebarDocumentEdits.move(it, nodeId, after) } }

    /** Sets a space down under a row, or back at the top level for null. */
    fun place(nodeId: String, parent: String?, after: String?) = scope.launch { edit { SidebarDocumentEdits.place(it, nodeId, parent, after) } }

    fun fold(nodeId: String) = scope.launch { edit { SidebarDocumentEdits.fold(it, nodeId) } }

    fun removeSection(sectionId: String) = scope.launch { edit { SidebarDocumentEdits.removeSection(it, sectionId) } }

    fun relabel(sectionId: String, label: String) = scope.launch { edit { SidebarDocumentEdits.relabel(it, sectionId, label) } }

    /** Shows a row's subtasks under it, opening the row as the person's own choice. */
    fun revealSessions(nodeId: String, sessions: List<SidebarDocumentEdits.SessionTree>) =
        scope.launch { edit(listOf(nodeId)) { SidebarDocumentEdits.revealSessions(it, nodeId, sessions) } }

    fun hideSessions(nodeId: String) = scope.launch {
        val known = spaces.keys.toSet()
        edit { SidebarDocumentEdits.hideSessions(it, nodeId) { id -> id in known } }
    }

    /** Holds the document and the daemon's edits through a drag and its drop. */
    fun setDragging(active: Boolean) = scope.launch {
        dragging = active
        if (active) attentionSend("interacting") else released()
    }

    // New work

    /**
     * New work: below Happy, or at the top of the section `sectionId`. The
     * row and its conversation exist here at once and the daemon is told
     * after; the first message waits for `created`.
     */
    suspend fun createStemCell(sectionId: String? = null): StemCellCreation = withContext(confined) {
        val spaceId = ClientId.make()
        val mutationId = ClientId.make()
        val identity = StemCellIdentity.make()
        val happySpaceId = snapshot.happySpaceId
        val named = capabilities.stemCellIdentities == true
        creating += spaceId
        inserts += Insert(spaceId, sectionId, happySpaceId)
        val time = now().toDouble()
        // Version 0 is older than anything the daemon says about it.
        spaces[spaceId] = Space(
            id = spaceId, title = null, icon = SpaceIcon.StemCell,
            target = SpaceTarget("task", identity.taskId, identity.agentId, identity.workspaceId),
            version = 0, createdAt = time, updatedAt = time,
        )
        snapshot = snapshot.copy(stemCellError = null)
        publish()
        val created = CompletableDeferred<String>()
        creations[spaceId] = created
        scope.launch {
            try {
                var response: CreateStemCellResponse? = null
                for (attempt in 0 until 4) {
                    try {
                        response = client.createStemCell(spaceId, mutationId, sectionId, if (named) identity else null)
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (refusal(e) || attempt == 3) throw e
                        delay(500L * (attempt + 1))
                    }
                }
                val answer = response ?: throw DirectAgentError.InvalidResponse
                created(spaceId, answer)
                created.complete(answer.agent.id)
            } catch (e: Exception) {
                refused(spaceId, e)
                created.completeExceptionally(e)
            }
        }
        StemCellCreation(spaceId, identity.agentId, identity.workspaceId, created)
    }

    private fun created(spaceId: String, response: CreateStemCellResponse) {
        creating -= spaceId
        creations.remove(spaceId)
        adopt(response.space)
        agents.absorb(response.agentJson)
        schedulePlacesReload()
        publish()
    }

    private fun refused(spaceId: String, error: Exception) {
        creating -= spaceId
        creations.remove(spaceId)
        inserts.removeAll { it.spaceId == spaceId }
        if (spaces[spaceId]?.version == 0) spaces.remove(spaceId)
        // The reason alone; the screen says what failed.
        snapshot = snapshot.copy(stemCellError = describe(error))
        publish()
        // Whatever the daemon did keep is read back.
        if (creating.isEmpty()) { if (dragging) stale = true else reloadSidebar() }
    }

    fun dismissStemCellError() = scope.launch {
        snapshot = snapshot.copy(stemCellError = null)
        publish()
    }

    /** Takes new work off the sidebar here only, or puts it back: leaving it, while the leaving may still be undone. */
    fun putAway(spaceId: String, away: Boolean) = scope.launch { setAway(spaceId, away) }

    private fun setAway(spaceId: String, away: Boolean) {
        if ((spaceId in awayIds) == away) return
        if (away) awayIds += spaceId else awayIds -= spaceId
        publish()
    }

    /**
     * Closes new work nobody wrote in: off the sidebar at once, then off the
     * computer, once it is made there if it is still being made. The daemon
     * refuses once its chat has a message, which brings it back.
     */
    fun discard(spaceId: String) = scope.launch {
        setAway(spaceId, true)
        val mutationId = ClientId.make()
        val creation = creations[spaceId]
        // Never made there: nothing to close.
        if (creation != null && runCatching { creation.await() }.isFailure) return@launch
        for (attempt in 0 until 3) {
            try {
                client.removeSpace(spaceId, mutationId)
                return@launch closed(spaceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An earlier try closed it.
                if (e is DirectAgentError.Status && e.status == 404) return@launch closed(spaceId)
                if (refusal(e)) break
                delay(500L * (attempt + 1))
            }
        }
        setAway(spaceId, false)
    }

    private fun closed(spaceId: String) {
        inserts.removeAll { it.spaceId == spaceId }
        closedIds += spaceId
        publish()
    }

    /** Leaves a task, or archives a task or bot; the row goes when the daemon says so. */
    fun leave(taskId: String) = scope.launch { attempt { client.leaveTask(taskId) } }

    fun archive(kind: String, id: String) = scope.launch { attempt { if (kind == "bot") client.archiveBot(id) else client.archiveTask(id) } }

    /** Archives a subtask; its row goes when the daemon says so. */
    fun archiveSubtask(agentId: String) = scope.launch { attempt { client.archiveAgent(agentId) } }

    private suspend fun attempt(body: suspend () -> Unit) {
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            snapshot = snapshot.copy(error = describe(e))
            publish()
        }
    }

    // Names

    /** Shows the name just given to a row's task or bot at once, and keeps it through any read that does not carry it yet, until one does. */
    fun rename(spaceId: String, title: String) = scope.launch {
        val space = spaces[spaceId] ?: return@launch
        val base = titleIntents[spaceId]?.base ?: space.title
        if (base == title) titleIntents.remove(spaceId) else titleIntents[spaceId] = TitleIntent(title, base)
        spaces[spaceId] = space.copy(title = title)
        publish()
        try {
            when (space.target.kind) {
                "task" -> client.renameTask(space.target.id, title)
                "bot" -> client.renameBot(space.target.id, title)
                else -> return@launch
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            withdraw(spaceId, title)
        }
    }

    private fun withdraw(spaceId: String, title: String) {
        val intent = titleIntents[spaceId]?.takeIf { it.title == title } ?: return
        titleIntents.remove(spaceId)
        spaces[spaceId]?.let { spaces[spaceId] = it.copy(title = intent.base) }
        publish()
    }

    // Prioritizing

    /** Turns Prioritizing on or off, from Settings' switch; only ever the person's choice. */
    fun setPrioritizing(on: Boolean) = scope.launch {
        snapshot = snapshot.copy(prioritizing = on)
        publish()
        prioritizingWrites++
        val write = prioritizingWrites
        val previous = prioritizingWrite
        prioritizingWrite = scope.launch {
            previous?.join()
            try {
                client.setUserSetting(STEM_CELLS_SETTING_KEY, JsonPrimitive(on))
                prioritizingDaemon = on
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A later choice still on its way decides what shows; otherwise the daemon's own value comes back.
                if (write == prioritizingWrites) snapshot = snapshot.copy(prioritizing = prioritizingDaemon)
                snapshot = snapshot.copy(error = describe(e))
                publish()
            }
        }
    }

    fun tidy() = scope.launch { runCatching { client.tidy(ClientId.make()) } }

    // Attention

    /** The person touched the list. Prioritizing's queued changes wait for five minutes without any of this. */
    fun attentionInput() = scope.launch { input() }

    private fun input() {
        if (!attentionVisible) return
        attentionSend("interacting")
        attentionRestart()
    }

    /** The list went out of sight, or came back: hidden lets queued changes land at once. */
    fun attentionVisibleUpdate(visible: Boolean) = scope.launch {
        if (attentionVisible == visible) return@launch
        attentionVisible = visible
        if (visible) { input(); return@launch }
        attentionIdle?.cancel(); attentionIdle = null
        attentionSend("calm")
    }

    private fun attentionRestart() {
        attentionIdle?.cancel(); attentionIdle = null
        if (!attentionVisible) return
        attentionIdle = scope.launch {
            delay(ATTENTION_CALM_MS)
            attentionSend("calm")
        }
    }

    private fun attentionSend(state: String) {
        if (snapshot.availability != StemCellsSnapshot.Availability.Available) return
        if (state == "calm" && (dragging || writes > 0)) return
        val at = clock()
        attention?.let { (last, since) -> if (last == state && (state == "calm" || at - since < ATTENTION_RENEW_MS)) return }
        attention = state to at
        scope.launch { runCatching { client.reportAttention(state) } }
    }

    // Offline

    /**
     * The sidebar as last read, drawn at once while the daemon is asked. The
     * live read that follows replaces it row for row, so a row that has not
     * changed does not move.
     */
    private suspend fun restoreOffline() {
        val offline = offline ?: return
        if (held != null) return
        val kept = withContext(Dispatchers.IO) { offline.read() }?.sidebar ?: return
        if (held != null) return
        artifactId = kept.artifactId
        held = Held(kept.version, kept.html)
        for (space in kept.spaces) adopt(space)
        agentIcons = kept.agentIcons
        bots = kept.bots; tasks = kept.tasks
        surfaces = kept.surfaces?.toMutableList() ?: ArrayList()
        kept.capabilities?.let { capabilities = it }
        if (snapshot.prioritizing == null) snapshot = snapshot.copy(prioritizing = kept.prioritizing)
        for (owner in bots + tasks) owner.field("agent")?.let(agents::absorb)
        rebuildPlaces()
        snapshot = snapshot.copy(availability = StemCellsSnapshot.Availability.Available)
        publish()
    }

    /**
     * Kept only as the daemon has it: never while a hand or a write holds
     * the document (the rows may be ahead of it, or a rename not yet taken);
     * the publish that ends those keeps it then.
     */
    private fun saveOffline() {
        val offline = offline ?: return
        val artifactId = artifactId ?: return
        val held = held ?: return
        if (writes != 0 || dragging || stale || titleIntents.isNotEmpty()) return
        val sidebar = StemCellsOfflineSnapshot.Sidebar(
            artifactId, held.version, held.html,
            spaces.values.filter { it.version > 0 }.sortedBy { it.id },
            agentIcons, bots, tasks, surfaces.toList(), capabilities, prioritizingDaemon,
        )
        offline.update { it.copy(sidebar = sidebar) }
    }

    companion object {
        const val ATTENTION_RENEW_MS = 60_000L
        const val ATTENTION_CALM_MS = 300_000L

        /** The daemon's own words for a refusal, or empty where it gave none (the screens say it in theirs). */
        fun describe(error: Throwable): String = (error as? DirectAgentError.Status)?.error ?: ""

        /** A refusal the daemon heard (a status under 500): never sent again. */
        internal fun refusal(error: Throwable): Boolean =
            error is DirectAgentError && ((error as? DirectAgentError.Status)?.status ?: 500) < 500

        private fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)
        private fun JsonElement?.string(key: String): String? = (field(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonElement?.bool(key: String): Boolean? = (field(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        private fun JsonElement?.number(key: String): Double? = (field(key) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        private fun JsonElement?.isTrue(): Boolean = (this as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
        private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
    }
}
