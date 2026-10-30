package com.slopus.happy.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.home.BotFaceLeg
import com.slopus.happy.home.BotFacePainter
import com.slopus.happy.home.BotFaceRetry
import com.slopus.happy.home.LiveBotFaceWearer
import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.testing.TaskRelayScenario
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BotFaceTransportTest {
    private val machine = buildJsonObject {
        put("host", "fixture.local"); put("machineKind", "rig"); put("rigOnly", true)
        putJsonObject("capabilities") { put("newSession", true); put("bots", true) }
    }

    @Test fun realEncryptedUploadAndAvatarRetryNeverSpawnAgain() = runBlocking {
        val fixture = TaskRelayScenario(machine, bots = true, failFirstAvatar = true).start()
        val h = AccountHarness(fixture.server, System.currentTimeMillis())
        try {
            h.startAndSync()
            val spawned = h.account.machineOps.spawnSession(SpawnSessionOptions(machineId = fixture.machineId, directory = "", agent = "rig",
                clientRequestId = "face-request", providerId = "codex", modelId = "fixture-model",
                happyAgentTarget = HappyAgentSpawnTarget.Bot("Fixture Bot"))) as SpawnSessionResult.Success
            val id = spawned.sessionId
            assertTrue(h.account.sender.ensureSessionReady(id))
            assertEquals("Fixture Bot", SessionMetadata.parse(h.store.session(id)!!.metadataJson)?.bot?.name)
            val painter = BotFacePainter.of(ApplicationProvider.getApplicationContext<Context>())
            val wearer = LiveBotFaceWearer(painter::png,
                upload = { session, bytes -> h.account.uploadSessionBlob(session, "face.png", bytes) },
                setAvatar = h.account.sessionOps::setAvatar, isCurrent = { true })
            assertEquals(BotFaceLeg.Wearing, wearer.wear(id, "happy")?.leg)
            assertTrue(fixture.worn.isEmpty())
            assertNull(BotFaceRetry(id, "happy", wearer) { true }.attempt())
            assertEquals(listOf(id), fixture.worn)
            assertEquals(1, fixture.spawnPayloads.size)
            assertEquals(2, fixture.avatarPayloads.size)
            val expected = painter.png("happy")
            fixture.avatarPngs.forEach { assertArrayEquals(expected, it) }
            assertTrue(fixture.server.sentRecords(id).isEmpty())
            eventually { h.store.session(id)?.avatarJson != null }
            val uploads = fixture.server.requestsTo("/v1/sessions/$id/attachments/request-upload")
            assertEquals(2, uploads.size)
            uploads.forEach { request ->
                val payload = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                assertEquals("face.png", payload.getValue("filename").jsonPrimitive.content)
                assertEquals(expected.size + 40L, payload.getValue("size").jsonPrimitive.long)
            }
        } finally { h.close() }
    }

    @Test fun uploadRefusalHasItsOwnLegAndRetryUsesSameSession() = runBlocking {
        val fixture = TaskRelayScenario(machine, bots = true).start()
        val h = AccountHarness(fixture.server, System.currentTimeMillis())
        try {
            fixture.server.addSession("bot", System.currentTimeMillis())
            h.startAndSync()
            val painter = BotFacePainter.of(ApplicationProvider.getApplicationContext<Context>())
            var wore = 0
            val wearer = LiveBotFaceWearer(painter::png,
                upload = { id, bytes -> h.account.uploadSessionBlob(id, "face.png", bytes) },
                setAvatar = { _, _, _, _ -> wore++ }, isCurrent = { true })
            fixture.server.failAttachmentUploads.add(403)
            assertEquals(BotFaceLeg.Uploading, wearer.wear("bot", "happy")?.leg)
            assertEquals(0, wore)
            assertNull(wearer.wear("bot", "happy"))
            assertEquals(1, wore)
        } finally { h.close() }
    }

    @Test fun staleOwnerAndBackgroundRefuseBeforeUploadRequest() = runBlocking {
        val h = AccountHarness()
        try {
            h.server.addSession("bot", 100)
            h.startAndSync()
            try { h.account.uploadSessionBlob("bot", "face.png", byteArrayOf(1)) { false }; fail("Must cancel") } catch (_: CancellationException) {}
            h.foreground.value = false
            val wearer = LiveBotFaceWearer(paint = { byteArrayOf(1) },
                upload = { id, bytes -> h.account.uploadSessionBlob(id, "face.png", bytes) },
                setAvatar = { _, _, _, _ -> fail("Must not wear in background") }, isCurrent = { true })
            assertEquals("Background must keep the bot for a picture retry, not cancel its start", BotFaceLeg.Uploading, wearer.wear("bot", "seed")?.leg)
            assertTrue(h.server.requestsTo("/v1/sessions/bot/attachments/request-upload").isEmpty())
        } finally { h.close() }
    }
}