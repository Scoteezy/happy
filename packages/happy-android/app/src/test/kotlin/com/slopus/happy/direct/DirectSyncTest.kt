package com.slopus.happy.direct

import com.slopus.happy.stemcells.AgentMode
import com.slopus.happy.stemcells.DirectQueuedMessage
import com.slopus.happy.stemcells.OutgoingImage
import com.slopus.happy.stemcells.StemCellsRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

/** happy-ios `DirectSyncStorageTests`, on framework SQLite. */
@RunWith(RobolectricTestRunner::class)
class DirectSyncStorageTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun settingTimestampUsesDaemonIntegerMilliseconds() {
        val pending = DirectSyncStorage().enqueueSetting("key", JsonPrimitive(true))
        assertEquals(Math.floor(pending.updatedAt), pending.updatedAt, 0.0)
        assertTrue(Math.abs(pending.updatedAt - System.currentTimeMillis()) < 1_000)
    }

    @Test fun oldBootstrapCannotOverwriteNewerAcknowledgementOrBootstrap() {
        val storage = DirectSyncStorage()
        val revision = storage.confirmedSettingsRevision()
        val pending = storage.enqueueSetting("key", JsonPrimitive(true))
        val winner = DirectUserSetting("key", JsonPrimitive(true), 2.0, "v2")
        storage.acknowledge(pending, winner)
        try {
            storage.commit(DirectSyncStorage.Checkpoint(JsonNull, "old"), "desktop", emptyList(), revision)
            fail("Old bootstrap erased the PUT winner")
        } catch (_: DirectSyncStorage.ChangedDuringRead) {
        }
        val next = storage.confirmedSettingsRevision()
        storage.commit(DirectSyncStorage.Checkpoint(JsonNull, "new"), "desktop", listOf(winner), next)
        try {
            storage.commit(DirectSyncStorage.Checkpoint(JsonNull, "old"), "desktop", emptyList(), next)
            fail("Old bootstrap erased the newer checkpoint")
        } catch (_: DirectSyncStorage.ChangedDuringRead) {
        }
        assertEquals("new", storage.checkpoint("desktop")?.cursor)
        assertEquals(JsonPrimitive(true), storage.settings()["key"])
    }

    @Test fun settingVersionsAndAuthoritativeStaleWriteWinner() {
        val storage = DirectSyncStorage()
        storage.receiveSetting("key", JsonPrimitive(true), "0002")
        storage.receiveSetting("key", JsonPrimitive(false), "0001")
        val pending = storage.enqueueSetting("key", JsonPrimitive(false), updatedAt = 1.0)
        storage.acknowledge(pending, DirectUserSetting("key", JsonPrimitive(true), 2.0, "0002"))
        assertEquals(JsonPrimitive(true), storage.settings()["key"])
        assertTrue(storage.pendingSettings().isEmpty())
    }

    @Test fun checkpointAndPendingSettingsSurviveRelaunch() {
        val file = File(folder.root, "account/direct.sqlite")
        val storage = DirectSyncStorage(file)
        val checkpoint = DirectSyncStorage.Checkpoint(JsonObject(mapOf("rows" to JsonArray(listOf(JsonPrimitive("one"))))), "cursor-2")
        storage.commit(checkpoint, "home")
        val original = storage.enqueueSetting("visibility", JsonArray(listOf(JsonPrimitive("project"))))
        storage.close()
        val reopened = DirectSyncStorage(file)
        assertEquals(checkpoint, reopened.checkpoint("home"))
        assertEquals(JsonArray(listOf(JsonPrimitive("project"))), reopened.settings()["visibility"])
        // Relaunch keeps the original timestamp and mutation ID.
        assertEquals(listOf(original), reopened.pendingSettings())
        reopened.close()
    }

    @Test fun oldAcknowledgementDoesNotEraseNewIntentOrRemoteOverwriteIt() {
        val storage = DirectSyncStorage()
        val old = storage.enqueueSetting("key", JsonPrimitive(false))
        val new = storage.enqueueSetting("key", JsonPrimitive(true))
        storage.receiveSetting("key", JsonPrimitive(false))
        storage.acknowledge(old)
        assertEquals(JsonPrimitive(true), storage.settings()["key"])
        assertEquals(listOf(new), storage.pendingSettings())
        storage.acknowledge(new)
        assertTrue(storage.pendingSettings().isEmpty())
    }

    @Test fun accountIsolationAndClosedStoreRejectsLateResponse() {
        val first = DirectSyncStorage()
        val second = DirectSyncStorage()
        first.enqueueSetting("key", JsonPrimitive(true))
        assertTrue(second.settings().isEmpty())
        first.close()
        try {
            first.receiveSetting("key", JsonPrimitive(false))
            fail("A closed account accepted a late write")
        } catch (_: CancellationException) {
        }
    }

    @Test fun outboxKeepsTheFirstPayloadOfAnId() {
        val storage = DirectSyncStorage()
        val message = DirectQueuedMessage("m", "chat", "First", listOf(OutgoingImage("image/png", byteArrayOf(1, 2))), null)
        storage.enqueueMessage(message)
        storage.enqueueMessage(message.copy(text = "Second"))
        val queued = storage.queuedMessages().single()
        assertEquals("First", queued.text)
        assertEquals(listOf<Byte>(1, 2), queued.pictures.single().data.toList())
        storage.updateMessage(queued.copy(failure = "Refused"))
        assertEquals("Refused", storage.queuedMessages().single().failure)
        storage.acknowledgeMessage("m")
        assertTrue(storage.queuedMessages().isEmpty())
    }
}

/**
 * The computer happy-ios `DirectSyncEngineTests` talks to: one desktop
 * bootstrap, settings written back as the daemon resolves them, sends that
 * can lose their answer, and an event stream to push into.
 */
private class DirectSyncFixture : DirectTransport {
    var value = DirectAgentClient.parse(FIXTURE.toByteArray()).jsonObject
    var online = true
    val writes = ArrayList<JsonObject>()
    val streamHeaders = ArrayList<Map<String, String>>()
    private val streams = ArrayList<Channel<ByteArray>>()
    private var version = 0
    private var bootstrapFailures = 0
    val sends = ArrayList<JsonObject>()
    var loseSendAck = false

    fun renameProjectAfterOneFailedRead() {
        bootstrapFailures = 1
        val project = JsonObject(value["projects"]!!.let { it as JsonArray }.first().jsonObject + ("name" to JsonPrimitive("Renamed remotely")))
        value = JsonObject(value + ("projects" to JsonArray(listOf(project))))
        emit(JsonObject(mapOf("type" to JsonPrimitive("project.updated"), "payload" to JsonObject(mapOf("projectId" to JsonPrimitive("p"))))))
    }

    fun setProjectsHidden(hidden: Boolean, stamp: Double = System.currentTimeMillis().toDouble()) {
        version += 1
        val setting = JsonObject(mapOf(
            "key" to JsonPrimitive(DirectSettings.PROJECTS_VISIBILITY), "value" to JsonObject(mapOf("projectsHidden" to JsonPrimitive(hidden))),
            "version" to JsonPrimitive("%08d".format(version)), "updatedAt" to JsonPrimitive(stamp),
        ))
        value = JsonObject(value + ("userSettings" to JsonArray(listOf(setting))))
        emit(JsonObject(mapOf("type" to JsonPrimitive("user_settings.updated"), "payload" to JsonObject(mapOf("setting" to setting)))))
    }

    fun emit(event: JsonElement) {
        for (stream in streams) stream.trySend("event: message\ndata: $event\n\n".toByteArray())
    }

    override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
        if (!online) throw IOException("offline")
        if (path == "/v0/bootstrap/desktop") {
            if (bootstrapFailures > 0) { bootstrapFailures -= 1; throw IOException("timed out") }
            return DirectHttpResponse(200, body = value.toString().toByteArray())
        }
        if (method == "PUT" && path.startsWith("/v0/user-settings/")) {
            val request = DirectAgentClient.parse(body!!).jsonObject
            writes += request
            val held = (value["userSettings"] as? JsonArray)?.firstOrNull() as? JsonObject
            val stamp = (request["updatedAt"] as JsonPrimitive).doubleOrNull!!
            if (((held?.get("updatedAt") as? JsonPrimitive)?.doubleOrNull ?: 0.0) <= stamp) {
                setProjectsHidden((request["value"] as JsonObject)["projectsHidden"] == JsonPrimitive(true), stamp)
            }
            val answer = JsonObject(mapOf("setting" to (value["userSettings"] as JsonArray).first()))
            return DirectHttpResponse(200, body = answer.toString().toByteArray())
        }
        if (method == "POST" && path.endsWith("/send")) {
            sends += DirectAgentClient.parse(body!!).jsonObject
            if (loseSendAck) { loseSendAck = false; throw IOException("connection lost") }
            return DirectHttpResponse(200, body = "{}".toByteArray())
        }
        return DirectHttpResponse(404, body = "{}".toByteArray())
    }

    override suspend fun stream(path: String, credential: String, headers: Map<String, String>): DirectHttpStream {
        if (!online) throw IOException("offline")
        streamHeaders += headers
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        streams += channel
        channel.trySend("event: hello\ndata: {\"cursor\":\"cursor-1\",\"resumed\":true}\n\n".toByteArray())
        return DirectHttpStream(200, body = channel.consumeAsFlow())
    }

    override suspend fun close() {
        streams.forEach { it.close() }
        streams.clear()
    }

    companion object {
        const val FIXTURE = """
        {"cursor":"cursor-1","profile":{"userId":null},"userSettings":[],
         "projects":[{"id":"p","name":"App","status":"active","orderKey":"a","agents":[{"id":"root","workspaceId":"p","title":"Root chat","status":"idle"}]}],
         "workspaces":[
           {"id":"work","projectId":"p","parentId":"p","name":"Feature","kind":"worktree","status":"active","orderKey":"b","agents":[]},
           {"id":"delegated","projectId":"p","parentId":"p","name":"Duplicated worktree","kind":"worktree","status":"active","orderKey":"a","agents":[]}],
         "bots":[{"id":"happy","name":"Happy","systemKey":"chief_of_staff","status":"active","agent":{"id":"bot-agent","workspaceId":"bot-work","status":"idle","subtasks":[{"id":"child","workspaceId":"delegated","parentAgentId":"bot-agent","title":"Delegated task","status":"running"}]}}],
         "tasks":[{"id":"unjoined","name":"Not joined","agent":{"id":"unjoined-agent","workspaceId":"x","status":"idle"}},
           {"id":"first","name":"First created","agent":{"id":"first-agent","workspaceId":"one","status":"idle"}},
           {"id":"last","name":"Last created","agent":{"id":"last-agent","workspaceId":"two","status":"idle"}}],
         "taskMemberships":[{"taskId":"last"},{"taskId":"first"}],
         "surfaces":[{"id":"other","name":"Other person's document","type":"document","members":[{"userId":"other-user","orderKey":"a"}]},
           {"id":"todo","name":"Groceries","type":"todo","members":[{"userId":null,"orderKey":"b"}]},
           {"id":"doc","name":"Notes","type":"document","members":[{"userId":null,"orderKey":"a"}]}]}
        """
    }
}

/** happy-ios `DirectSyncEngineTests`: one account's checkpoint, settings outbox, message outbox and stream. */
@RunWith(RobolectricTestRunner::class)
class DirectSyncEngineTest {
    @get:Rule val folder = TemporaryFolder()

    private fun TestScope.engine(transport: DirectSyncFixture, directory: File? = null) =
        DirectSyncEngine(DirectAgentClient(transport, "test"), directory, backgroundScope, EmptyCoroutineContext)

    private suspend fun DirectSyncEngine.next(predicate: (DirectHomeSnapshot) -> Boolean) = withTimeout(4_000) { updates.first(predicate) }

    private suspend fun until(condition: suspend () -> Boolean) {
        repeat(100) {
            if (condition()) return
            delay(10)
        }
    }

    @Test fun transientReconcileFailureRetriesWithoutAnotherEvent() = runTest {
        val transport = DirectSyncFixture()
        val engine = engine(transport)
        engine.start()
        engine.next { it.live }
        transport.renameProjectAfterOneFailedRead()
        val changed = engine.next { snapshot -> snapshot.rows.any { it.label == "Renamed remotely" } }
        assertTrue(changed.live)
        engine.stop()
    }

    @Test fun accountDrainsPersistedSendWithoutOpeningConversation() = runTest {
        val directory = folder.newFolder()
        val transport = DirectSyncFixture()
        val mode = AgentMode("test", "test-model", "low", null, "auto")
        val first = engine(transport, directory)
        first.enqueueMessage(DirectQueuedMessage("stable-message", "never-opened", "Offline text", emptyList(), mode))
        first.stop()
        val reopened = engine(transport, directory)
        reopened.start()
        reopened.next { it.live }
        until { transport.sends.isNotEmpty() }
        assertEquals(1, transport.sends.size)
        assertEquals(JsonPrimitive("stable-message"), transport.sends[0]["id"])
        assertEquals(JsonPrimitive("Offline text"), transport.sends[0]["text"])
        assertEquals(JsonPrimitive("test-model"), (transport.sends[0]["mode"] as JsonObject)["modelId"])
        until { reopened.queuedMessages("never-opened").isEmpty() }
        assertTrue(reopened.queuedMessages("never-opened").isEmpty())
        reopened.stop()
    }

    @Test fun ambiguousSendAcknowledgementRetainsExactPayloadAndPausesRetries() = runTest {
        val transport = DirectSyncFixture()
        transport.loseSendAck = true
        val engine = engine(transport)
        engine.start()
        engine.next { it.live }
        val mode = AgentMode("test", "chosen-model", "high", null, "auto")
        engine.enqueueMessage(DirectQueuedMessage("same-message", "chat", "Keep this exact text", listOf(OutgoingImage("image/png", byteArrayOf(1, 2, 3))), mode))
        until { transport.sends.isNotEmpty() }
        engine.pause()
        delay(1_100)
        assertEquals(1, transport.sends.size)
        engine.start()
        engine.next { it.live }
        until { transport.sends.size == 2 }
        assertEquals(2, transport.sends.size)
        assertEquals(transport.sends.first(), transport.sends.last())
        engine.stop()
    }

    @Test fun refusedSendFailsOnlyItsConversationAndRetryClearsIt() = runTest {
        val transport = object : DirectTransport by DirectSyncFixture() {
            val sent = ArrayList<String>()
            override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
                if (method == "POST" && path.endsWith("/send")) {
                    val id = (DirectAgentClient.parse(body!!).jsonObject["id"] as JsonPrimitive).content
                    sent += id
                    if (id == "refused" && sent.count { it == id } == 1) return DirectHttpResponse(422, body = """{"error":"Too long."}""".toByteArray())
                    return DirectHttpResponse(200, body = "{}".toByteArray())
                }
                return DirectHttpResponse(200, body = DirectSyncFixture.FIXTURE.toByteArray())
            }
        }
        val engine = DirectSyncEngine(DirectAgentClient(transport, "test"), null, backgroundScope, EmptyCoroutineContext)
        val mode = AgentMode("test", "m", "low", null, "auto")
        val events = ArrayList<com.slopus.happy.stemcells.DirectDeliveryEvent>()
        val watched = engine.deliveryEvents("chat")
        backgroundScope.launch { watched.collect { events += it } }
        engine.enqueueMessage(DirectQueuedMessage("refused", "chat", "One", emptyList(), mode))
        engine.enqueueMessage(DirectQueuedMessage("behind", "chat", "Two", emptyList(), mode))
        engine.enqueueMessage(DirectQueuedMessage("elsewhere", "other", "Three", emptyList(), mode))
        engine.start()
        until { "elsewhere" in transport.sent }
        // The refusal holds the rest of its conversation, never another's.
        assertEquals(listOf("refused", "elsewhere"), transport.sent)
        assertEquals(listOf(com.slopus.happy.stemcells.DirectDeliveryEvent.Failed("refused", "Too long.")), events)
        assertEquals("Too long.", engine.queuedMessages("chat").first().failure)
        engine.retryMessage("refused")
        until { "behind" in transport.sent }
        assertEquals(listOf("refused", "elsewhere", "refused", "behind"), transport.sent)
        assertTrue(engine.queuedMessages("chat").isEmpty())
        engine.stop()
    }

    @Test fun canonicalDesktopOrderMembershipHierarchyAndVisibility() {
        val desktop = DirectDesktopSnapshot.from(DirectAgentClient.parse(DirectSyncFixture.FIXTURE.toByteArray()))
        val all = DirectSidebarRows.make(desktop, emptyMap())
        assertEquals(
            listOf("Artifacts", "Notes", "Groceries", "Bots", "Happy", "Delegated task", "Tasks", "Last created", "First created", "Projects", "App", "Root chat", "Feature"),
            all.map { it.label },
        )
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(1, all.first { it.label == "Delegated task" }.depth)
        val settings = mapOf(
            DirectSettings.PROJECTS_VISIBILITY to JsonObject(mapOf("projectsHidden" to JsonPrimitive(true))),
            "happy-desktop.bot.happy" to JsonObject(mapOf("hidden" to JsonPrimitive(true))),
        )
        assertEquals(listOf("Artifacts", "Notes", "Groceries", "Bots", "Tasks", "Last created", "First created"), DirectSidebarRows.make(desktop, settings).map { it.label })
        val folded = StemCellsRows.visible(DirectSidebarRows.make(desktop, emptyMap(), setOf("project:p")))
        assertFalse(folded.any { it.label == "Root chat" || it.label == "Feature" })
        assertTrue(folded.any { it.label == "App" })
    }

    @Test fun offlineCacheAndSettingsSurviveAccountRelaunchThenReplay() = runTest {
        val directory = folder.newFolder()
        val transport = DirectSyncFixture()
        val engine = engine(transport, directory)
        engine.start()
        engine.next { it.live && it.loaded }
        engine.pause()
        transport.online = false
        engine.setProjectsHidden(true)
        val optimistic = engine.next { DirectSettings.projectsHidden(it.settings) }
        assertFalse(optimistic.rows.any { it.label == "Projects" })
        engine.stop()

        val reopened = engine(transport, directory)
        reopened.start()
        val offline = reopened.next { it.loaded }
        assertFalse(offline.live)
        assertTrue(DirectSettings.projectsHidden(offline.settings))
        assertTrue(offline.rows.any { it.label == "Last created" })
        reopened.pause()
        transport.online = true
        reopened.start()
        reopened.next { it.live }
        until { transport.writes.isNotEmpty() }
        assertEquals(1, transport.writes.size)
        assertNotNull((transport.writes[0]["mutationId"] as? JsonPrimitive)?.content)
        assertNotNull((transport.writes[0]["updatedAt"] as? JsonPrimitive)?.doubleOrNull)
        assertTrue(transport.streamHeaders.all { it["Last-Event-ID"] == "cursor-1" })
        reopened.stop()
    }

    @Test fun remoteSettingChangesHomeAndAStaleOfflineEditAdoptsServerWinner() = runTest {
        val transport = DirectSyncFixture()
        val engine = engine(transport)
        engine.start()
        engine.next { it.live }
        transport.setProjectsHidden(true)
        val remote = engine.next { DirectSettings.projectsHidden(it.settings) }
        assertFalse(remote.rows.any { it.label == "App" })
        engine.pause()
        engine.setProjectsHidden(false)
        transport.setProjectsHidden(true, System.currentTimeMillis().toDouble() + 1_000)
        engine.start()
        engine.next { it.live && DirectSettings.projectsHidden(it.settings) }
        engine.stop()
    }

    @Test fun anotherOwnerStopsTheAccount() = runTest {
        val transport = DirectSyncFixture()
        val engine = engine(transport)
        engine.start()
        engine.next { it.live }
        engine.pause()
        transport.value = JsonObject(transport.value + ("profile" to JsonObject(mapOf("userId" to JsonPrimitive("someone-else")))))
        engine.start()
        val stopped = engine.next { it.error == DirectSyncEngine.OWNER_CHANGED }
        assertFalse(stopped.live)
        assertFalse(engine.deliveryAllowed())
    }
}
