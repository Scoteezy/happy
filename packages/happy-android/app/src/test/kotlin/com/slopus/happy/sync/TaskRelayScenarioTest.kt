package com.slopus.happy.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.home.LiveFirstMessageSender
import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.model.task
import com.slopus.happy.testing.TaskRelayScenario
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI

@RunWith(AndroidJUnit4::class)
class TaskRelayScenarioTest {
    private val machine = buildJsonObject {
        put("host", "fixture.local"); put("machineKind", "rig"); put("rigOnly", true)
        putJsonObject("capabilities") { put("newSession", true); put("tasks", true) }
    }

    @Test fun realEncryptedTransportAssociatesTaskAndFirstMessage() = runBlocking {
        val fixture = TaskRelayScenario(machine).start()
        val h = AccountHarness(fixture.server, now = System.currentTimeMillis())
        try {
            h.startAndSync()
            val options = SpawnSessionOptions(machineId = fixture.machineId, directory = "", agent = "rig",
                clientRequestId = "fixture-request", providerId = "codex", modelId = "fixture-model",
                happyAgentTarget = HappyAgentSpawnTarget.Task())
            val result = h.account.machineOps.spawnSession(options) as SpawnSessionResult.Success
            assertEquals(fixture.created.single(), result.sessionId)
            assertTrue(h.account.sender.ensureSessionReady(result.sessionId))
            val metadata = SessionMetadata.parse(h.store.session(result.sessionId)!!.metadataJson)!!
            assertNotNull(metadata.task)
            assertEquals(fixture.machineId, metadata.machineId)
            val sent = LiveFirstMessageSender({ h.account }).send(result.sessionId, "Fixture task prompt", emptyList()) { true }
            assertTrue(sent.sent)
            eventually(message = "encrypted first message stored") { fixture.server.sentRecords(result.sessionId).size == 1 }
            val record = fixture.server.sentRecords(result.sessionId).single().second
            assertEquals("user", record.getValue("role").jsonPrimitive.content)
            assertEquals("Fixture task prompt", record.getValue("content").jsonObject.getValue("text").jsonPrimitive.content)
            // An RPC retry keeps the association; it cannot create an ordinary session.
            assertEquals(result, h.account.machineOps.spawnSession(options))
            assertEquals(1, fixture.created.size)
            assertTrue(fixture.evidence().getValue("taskTargetsOnly").jsonPrimitive.boolean)
        } finally { h.close() }
    }

    @Test fun restartUsesNewKeysAccountAndBoundPortAndCloseStopsListener() {
        val first = TaskRelayScenario(machine).start()
        val second = TaskRelayScenario(machine).start()
        val firstUrl = first.server.url
        try {
            assertEquals("127.0.0.1", URI(firstUrl).host)
            assertEquals("127.0.0.1", URI(second.server.url).host)
            assertNotEquals(URI(firstUrl).port, URI(second.server.url).port)
            assertFalse(first.server.secret.contentEquals(second.server.secret))
            assertFalse(first.server.dataKey(first.machineId).contentEquals(second.server.dataKey(second.machineId)))
            assertNotEquals(first.server.encryption.anonId, second.server.encryption.anonId)
            assertNotEquals(first.server.token, second.server.token)
            assertTrue(second.created.isEmpty())
        } finally { first.close(); second.close() }
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        assertThrows(java.io.IOException::class.java) {
            client.newCall(Request.Builder().url(firstUrl).build()).execute().close()
        }
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test fun fixtureRejectsOrdinarySessionBeforeCreatingAnything() {
        TaskRelayScenario(machine).start().use { fixture ->
            val payload = buildJsonObject {
                put("type", "happy-agent-spawn"); put("clientRequestId", "fixture-request")
                putJsonObject("target") { put("kind", "session") }
                putJsonObject("agentConfiguration") {}
            }
            assertThrows(IllegalStateException::class.java) {
                fixture.server.rpc.getValue("${fixture.machineId}:spawn-happy-session")(fixture.server.sealRpc(fixture.machineId, payload))
            }
            assertTrue(fixture.created.isEmpty())
        }
    }
}