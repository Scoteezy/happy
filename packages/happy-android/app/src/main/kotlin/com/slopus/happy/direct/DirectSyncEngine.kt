package com.slopus.happy.direct

import com.slopus.happy.stemcells.DirectDeliveryEvent
import com.slopus.happy.stemcells.DirectQueuedMessage
import com.slopus.happy.stemcells.StemCellPersistence
import com.slopus.happy.stemcells.StemCellsOfflineFile
import com.slopus.happy.stemcells.StemCellsRow
import com.slopus.happy.stemcells.StemCellsRows
import com.slopus.happy.stemcells.StemCellsStore
import com.slopus.happy.stemcells.defaultMode
import com.slopus.happy.stemcells.sendMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.coroutines.CoroutineContext

/** happy-ios `DirectHomeSnapshot`. */
data class DirectHomeSnapshot(
    val loaded: Boolean = false,
    val live: Boolean = false,
    val usesSpaces: Boolean = false,
    val rows: List<StemCellsRow> = emptyList(),
    val allRows: List<StemCellsRow> = emptyList(),
    val settings: Map<String, JsonElement> = emptyMap(),
    val error: String? = null,
)

/**
 * One direct account, one tunnel client, one durable checkpoint and one
 * event stream (happy-ios `DirectSyncEngine`). Views only observe
 * projections. The relay sync is not involved in this account's identity,
 * persistence, settings or transport.
 *
 * `scope` runs one coroutine at a time; it plays the Swift actor, and the
 * Stem Cells store shares it. SQLite calls run on `io`.
 */
class DirectSyncEngine(
    val client: DirectAgentClient,
    directory: File?,
    private val scope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
    private val labels: DirectSidebarRows.Labels = DirectSidebarRows.Labels(),
    storeLabels: StemCellsRows.Labels = StemCellsRows.Labels(),
) : StemCellPersistence {
    private val file: File? = directory?.let { File(it, "direct.sqlite") }
    val offline: StemCellsOfflineFile? = directory?.let { StemCellsOfflineFile(DirectOfflineFile(it)) }
    val spaces = StemCellsStore(client, offline, scope, storeLabels, io = io)

    private val confined = scope.coroutineContext.minusKey(Job)
    private val updatesFlow = MutableStateFlow(DirectHomeSnapshot())

    /** Every change, the latest kept. */
    val updates: StateFlow<DirectHomeSnapshot> = updatesFlow.asStateFlow()

    private var storage: DirectSyncStorage? = null
    private var desktop: DirectDesktopSnapshot? = null
    private var snapshot = DirectHomeSnapshot()
    private var collapsed = HashSet<String>()
    private var runner: Job? = null
    private var refresh: Job? = null
    private var delivery: Job? = null
    private var messageDelivery: Job? = null
    private class MessageListener(val agentId: String, val channel: Channel<DirectDeliveryEvent>)
    private val messageListeners = HashMap<Long, MessageListener>()
    private var listenerIds = 0L
    private var verified = false
    private var settingsDeliveryDirty = false
    private var messageDeliveryDirty = false
    private var generation = 0
    private var stopped = false
    private var cursor: String? = null
    private var dirty = false
    private var revision = 0

    private val allowed: Boolean get() = !stopped && runner != null && verified

    override suspend fun deliveryAllowed(): Boolean = withContext(confined) { allowed }

    suspend fun start() = withContext(confined) {
        if (stopped || runner != null) return@withContext
        generation += 1
        verified = false
        val generation = generation
        runner = scope.launch {
            try {
                run(generation)
            } finally {
                if (this@DirectSyncEngine.generation == generation) runner = null
            }
        }
    }

    suspend fun pause() = withContext(confined) {
        generation += 1
        verified = false
        val paused = generation
        val jobs = listOfNotNull(runner, refresh, delivery, messageDelivery)
        runner = null; refresh = null; delivery = null; messageDelivery = null
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
        if (generation != paused) return@withContext
        spaces.pause().join()
        if (generation != paused) return@withContext
        snapshot = snapshot.copy(live = false)
        publish()
    }

    suspend fun stop() = withContext(confined) {
        stopped = true
        pause()
        spaces.stop()
        storage?.let { opened -> runCatching { withContext(io) { opened.close() } } }
        synchronized(messageListeners) {
            for (listener in messageListeners.values) listener.channel.close()
            messageListeners.clear()
        }
    }

    private suspend fun database(): DirectSyncStorage {
        storage?.let { return it }
        val opened = withContext(io) { DirectSyncStorage(file) }
        storage?.let { opened.close(); return it }
        storage = opened
        return opened
    }

    private suspend fun <T> db(body: (DirectSyncStorage) -> T): T {
        val database = database()
        return withContext(io) { body(database) }
    }

    private suspend fun run(generation: Int) {
        try {
            if (desktop == null) {
                db { it.checkpoint("desktop") }?.let { cached ->
                    desktop = DirectDesktopSnapshot.from(cached.value)
                    cursor = cached.cursor
                }
            }
            snapshot = snapshot.copy(settings = db { it.settings() })
            (db { it.checkpoint("folds") }?.value as? JsonArray)?.let { folds ->
                collapsed = folds.mapNotNullTo(HashSet()) { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
            }
            if (!current(generation)) return
            derive()
            spaces.startExternal().join()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            snapshot = snapshot.copy(error = StemCellsStore.describe(e))
            publish()
            return
        }
        var attempt = 0
        while (current(generation)) {
            try {
                reconcile(generation)
                beginDelivery(generation)
                beginMessageDelivery(generation)
                client.events(cursor).collect { frame ->
                    if (!current(generation)) throw CancellationException("Superseded.")
                    if (frame.event == "hello") {
                        snapshot = snapshot.copy(live = true, error = null); attempt = 0
                        publish()
                        // The bootstrap cursor precedes its reads. Opening from
                        // it is safe even after a crash, replay or daemon replacement.
                        val hello = DirectAgentClient.parse(frame.data.toByteArray())
                        if ((hello as? JsonObject)?.get("gap") == JsonPrimitive(true)) scheduleReconcile(generation)
                        spaces.externalResync(snapshot.usesSpaces).join()
                        return@collect
                    }
                    val event = runCatching { DirectAgentClient.parse(frame.data.toByteArray()) as? JsonObject }.getOrNull() ?: return@collect
                    spaces.externalEvent(event).join()
                    if (!current(generation)) throw CancellationException("Superseded.")
                    val type = (event["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (type == "user_settings.updated") {
                        ((event["payload"] as? JsonObject)?.get("setting"))?.let(DirectUserSetting::from)?.let { setting ->
                            db { it.receiveSetting(setting.key, setting.value, setting.version) }
                            snapshot = snapshot.copy(settings = db { it.settings() })
                            derive()
                        }
                    }
                    // Events are delivery hints, never a durable checkpoint.
                    // Coalesce catalog invalidations; message token events go
                    // straight to the open conversation without rereading Home.
                    if (type != null && type != "agent.draft.updated" && RECONCILING.any(type::startsWith)) scheduleReconcile(generation)
                }
            } catch (_: DirectAgentError.Revoked) {
                snapshot = snapshot.copy(live = false, error = REVOKED)
                publish()
                return
            } catch (e: Exception) {
                if (!current(generation)) return
                snapshot = snapshot.copy(live = false, error = StemCellsStore.describe(e))
                publish()
            }
            if (!current(generation)) return
            snapshot = snapshot.copy(live = false); publish()
            delay(backoff(attempt))
            attempt += 1
        }
    }

    private suspend fun current(generation: Int): Boolean =
        !stopped && currentCoroutineContext().isActive && this.generation == generation

    private fun scheduleReconcile(generation: Int) {
        dirty = true
        revision += 1
        if (refresh != null) return
        refresh = scope.launch {
            try {
                var failures = 0
                while (dirty && current(generation)) {
                    try {
                        delay(200)
                        dirty = false
                        reconcile(generation)
                        failures = 0
                    } catch (e: Exception) {
                        if (!current(generation)) break
                        dirty = true
                        delay(backoff(failures))
                        failures += 1
                    }
                }
            } finally {
                if (this@DirectSyncEngine.generation == generation) refresh = null
            }
        }
    }

    private suspend fun reconcile(generation: Int) {
        val revision = revision
        val settingRevision = db { it.confirmedSettingsRevision() }
        val value = client.decode("GET", "/v0/bootstrap/desktop") { it }
        val desktop = try {
            DirectDesktopSnapshot.from(value)
        } catch (_: Exception) {
            throw DirectAgentError.InvalidResponse
        }
        if (!current(generation)) throw CancellationException("Superseded.")
        // A device token is paired to one owner. A replacement daemon must
        // never receive the previous owner's pending writes or cached state.
        val owner = ((value as? JsonObject)?.get("profile") as? JsonObject)?.get("userId")
        if (owner != null && (owner == JsonNull || (owner as? JsonPrimitive)?.isString == true)) {
            val bound = db { it.checkpoint("owner") }?.value
            if (bound != null && bound != owner) {
                stopped = true; verified = false; this.generation += 1
                listOfNotNull(runner, refresh, delivery, messageDelivery).forEach { it.cancel() }
                snapshot = snapshot.copy(live = false, error = OWNER_CHANGED); publish()
                withContext(NonCancellable) { client.close() }
                throw DirectAgentError.Status(409, "account_identity_changed", OWNER_CHANGED)
            }
            if (bound == null) db { it.commit(DirectSyncStorage.Checkpoint(owner, null), "owner") }
        } else if (desktop.userSettings != null) {
            throw DirectAgentError.InvalidResponse
        }
        // An event during the read may be newer than part of this composed
        // snapshot. The coalesced next read owns that invalidation.
        if (this.revision != revision) return
        db {
            it.commit(DirectSyncStorage.Checkpoint(value, desktop.cursor), "desktop", desktop.userSettings.orEmpty(), settingRevision)
        }
        if (!current(generation)) return
        this.desktop = desktop
        verified = true
        cursor = desktop.cursor
        snapshot = snapshot.copy(settings = db { it.settings() })
        val previouslySpaces = snapshot.usesSpaces
        derive()
        if (snapshot.usesSpaces && !previouslySpaces) spaces.externalResync(true).join()
    }

    suspend fun setProjectsHidden(hidden: Boolean) =
        setSetting(DirectSettings.PROJECTS_VISIBILITY, JsonObject(mapOf("projectsHidden" to JsonPrimitive(hidden))))

    override suspend fun cachedResource(key: String): JsonElement? = withContext(confined) { db { it.checkpoint(key) }?.value }

    override suspend fun keepResource(key: String, value: JsonElement) = withContext(confined) {
        if (stopped) throw CancellationException("This account was stopped.")
        db { it.commit(DirectSyncStorage.Checkpoint(value, null), key) }
    }

    suspend fun setSetting(key: String, value: JsonElement) = withContext(confined) {
        if (stopped) throw CancellationException("This account was stopped.")
        db { it.enqueueSetting(key, value) }
        snapshot = snapshot.copy(settings = db { it.settings() })
        derive()
        if (allowed) beginDelivery(generation)
    }

    private fun beginDelivery(generation: Int) {
        settingsDeliveryDirty = true
        if (!allowed || delivery != null) return
        delivery = scope.launch {
            try {
                do {
                    settingsDeliveryDirty = false
                    deliver(generation)
                } while (current(generation) && settingsDeliveryDirty)
            } finally {
                if (this@DirectSyncEngine.generation == generation) delivery = null
            }
        }
    }

    private suspend fun deliver(generation: Int) {
        var attempt = 0
        while (true) {
            val change = try {
                db { it.pendingSettings().firstOrNull() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            } ?: return
            if (!current(generation) || !allowed) return
            try {
                val body = JsonObject(mapOf("mutationId" to JsonPrimitive(change.mutationId), "value" to change.value, "updatedAt" to Wire.number(change.updatedAt)))
                val setting = client.decode("PUT", "/v0/user-settings/${DirectAgentClient.escape(change.key)}", body) {
                    DirectUserSetting.from(Wire.obj(it)["setting"] ?: JsonNull) ?: throw WireException("setting")
                }
                if (!current(generation) || !allowed) return
                // Invalidate a bootstrap that began before this response.
                // Its full settings snapshot must not erase this newer winner.
                scheduleReconcile(generation)
                db { it.acknowledge(change, setting) }
                attempt = 0
                snapshot = snapshot.copy(settings = db { it.settings() })
                derive()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Retain the original timestamp and ID, including across
                // suspension and relaunch. Reconnect retries the same intent.
                snapshot = snapshot.copy(error = StemCellsStore.describe(e))
                publish()
                if (refused(e)) return
                delay(backoff(attempt))
                attempt += 1
            }
        }
    }

    suspend fun fold(id: String) = withContext(confined) {
        if (!collapsed.add(id)) collapsed.remove(id)
        if (collapsed.size > 512) collapsed.remove(collapsed.minOf { it })
        val folds = JsonArray(collapsed.sorted().map(::JsonPrimitive))
        db { it.commit(DirectSyncStorage.Checkpoint(folds, null), "folds") }
        derive()
    }

    private fun derive() {
        snapshot = snapshot.copy(loaded = desktop != null, usesSpaces = snapshot.settings[DirectSettings.STEM_CELLS] == JsonPrimitive(true))
        desktop?.let { desktop ->
            val all = DirectSidebarRows.make(desktop, snapshot.settings, collapsed, labels)
            snapshot = snapshot.copy(allRows = all, rows = StemCellsRows.visible(all))
        }
        publish()
    }

    private fun publish() {
        updatesFlow.value = snapshot
    }

    /** Listens from this call, as the iOS stream does; the flow ends with the engine. */
    override fun deliveryEvents(agentId: String): Flow<DirectDeliveryEvent> {
        val channel = Channel<DirectDeliveryEvent>(Channel.UNLIMITED)
        val id = synchronized(messageListeners) {
            val id = ++listenerIds
            messageListeners[id] = MessageListener(agentId, channel)
            id
        }
        return flow {
            try {
                for (event in channel) emit(event)
            } finally {
                synchronized(messageListeners) { messageListeners.remove(id) }
                channel.cancel()
            }
        }
    }

    private fun announce(agentId: String, event: DirectDeliveryEvent) {
        val listeners = synchronized(messageListeners) { messageListeners.values.filter { it.agentId == agentId } }
        for (listener in listeners) listener.channel.trySend(event)
    }

    override suspend fun enqueueMessage(message: DirectQueuedMessage) = withContext(confined) {
        if (stopped) throw CancellationException("This account was stopped.")
        db { it.enqueueMessage(message) }
        beginMessageDelivery(generation)
    }

    override suspend fun retryMessage(id: String) = withContext(confined) {
        val message = db { it.queuedMessages() }.firstOrNull { it.id == id } ?: return@withContext
        db { it.updateMessage(message.copy(failure = null)) }
        beginMessageDelivery(generation)
    }

    override suspend fun queuedMessages(agentId: String): List<DirectQueuedMessage> =
        withContext(confined) { db { it.queuedMessages() }.filter { it.agentId == agentId } }

    private fun beginMessageDelivery(generation: Int) {
        messageDeliveryDirty = true
        if (!allowed || messageDelivery != null) return
        messageDelivery = scope.launch {
            try {
                do {
                    messageDeliveryDirty = false
                    drainMessages(generation)
                } while (current(generation) && messageDeliveryDirty)
            } finally {
                if (this@DirectSyncEngine.generation == generation) messageDelivery = null
            }
        }
    }

    private suspend fun drainMessages(generation: Int) {
        var attempt = 0
        while (current(generation) && allowed) {
            val queued = try {
                db { it.queuedMessages() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return
            }
            val blocked = HashSet<String>()
            var candidate: DirectQueuedMessage? = null
            for (message in queued) {
                if (message.failure != null) blocked += message.agentId
                else if (message.agentId !in blocked) { candidate = message; break }
            }
            var message = candidate ?: return
            try {
                if (message.mode == null) {
                    message = message.copy(mode = client.defaultMode())
                    if (!current(generation)) return
                    val updated = message
                    db { it.updateMessage(updated) }
                }
                val mode = message.mode
                if (!current(generation) || mode == null) return
                client.sendMessage(message.agentId, message.id, message.text, message.pictures, mode)
                if (!current(generation)) return
                db { it.acknowledgeMessage(message.id) }
                announce(message.agentId, DirectDeliveryEvent.Accepted(message.id))
                attempt = 0
            } catch (e: Exception) {
                if (!current(generation)) return
                if (refused(e)) {
                    val failed = message.copy(failure = StemCellsStore.describe(e))
                    runCatching { db { it.updateMessage(failed) } }
                    announce(message.agentId, DirectDeliveryEvent.Failed(message.id, failed.failure.orEmpty()))
                    continue
                }
                delay(backoff(attempt))
                attempt += 1
            }
        }
    }

    companion object {
        const val REVOKED = "This device was disconnected. Pair it again."
        const val OWNER_CHANGED = "This connection belongs to a different person. Pair it again."
        private val RECONCILING = listOf("project.", "workspace.", "bot.", "task.", "agent.", "surface.", "user_settings.")

        /** 1, 2, 4 … 30 seconds. */
        internal fun backoff(attempt: Int): Long = minOf(30L, 1L shl minOf(attempt, 5)) * 1000L

        /** Revoked, or a 4xx the daemon will refuse again: never retried on its own. */
        internal fun refused(error: Throwable): Boolean =
            error is DirectAgentError.Revoked || (error is DirectAgentError.Status && error.status in 400 until 500)
    }
}
