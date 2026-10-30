package com.slopus.happy.sync

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.attachments.AttachmentNotice
import com.slopus.happy.attachments.AttachmentPreview
import com.slopus.happy.attachments.ImageNormalizer
import com.slopus.happy.home.LiveFirstMessageSender
import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.testing.TaskRelayScenario
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DockPicturesTransportTest {
    private val machine = buildJsonObject {
        put("host", "fixture.local"); put("machineKind", "rig"); put("rigOnly", true)
        putJsonObject("capabilities") { put("newSession", true); put("tasks", true) }
    }

    @Test fun firstPictureSurvivesStagingRemovalAndUploadsBeforeTextWithoutRespawn() = runBlocking {
        for (text in listOf("", "Look at this")) {
            val fixture = TaskRelayScenario(machine, pictures = true).start()
            val h = AccountHarness(fixture.server, System.currentTimeMillis())
            val directory = Files.createTempDirectory("dock-picture").toFile()
            try {
                h.startAndSync()
                val id = spawn(h, fixture)
                val picture = picture(directory)
                val original = File(Uri.parse(picture.uri).path!!)
                val expected = original.readBytes()
                val sender = LiveFirstMessageSender({ h.account }) { SessionMetadata.parse(h.store.session(it)!!.metadataJson) }
                assertFalse(sender.send(id, text, listOf(picture)) { false }.sent)
                assertTrue(h.outboxFiles.listFiles()!!.isEmpty())
                fixture.server.failAttachmentUploads += 503
                assertTrue(sender.send(id, text, listOf(picture)) { true }.sent)
                assertTrue("Home can now remove its staging copy", original.delete())
                assertEquals(1, h.outboxFiles.listFiles()!!.size)
                eventually(timeoutMs = 15_000, message = "picture then first text") { fixture.server.sentRecords(id).size == 2 }
                val event = fixture.pictureEvents(id).single()
                assertArrayEquals(expected, fixture.pictureBytes(id, event))
                assertEquals(expected.size.toLong(), event.getValue("size").jsonPrimitive.long)
                assertEquals(32, event.getValue("image").jsonObject.getValue("width").jsonPrimitive.int)
                assertEquals(16, event.getValue("image").jsonObject.getValue("height").jsonPrimitive.int)
                assertEquals(text, fixture.server.sentRecords(id).last().second.getValue("content").jsonObject.getValue("text").jsonPrimitive.content)
                assertEquals(1, fixture.spawnPayloads.size)
                assertEquals(2, fixture.server.requestsTo("/v1/sessions/$id/attachments/request-upload").size)
                val proof = fixture.evidence().getValue("created").jsonArray.single().jsonObject
                assertTrue(proof.getValue("picturesBeforeText").jsonPrimitive.boolean)
                assertTrue(proof.getValue("picturesValidJpeg").jsonPrimitive.boolean)
                eventually { h.outboxFiles.listFiles()!!.isEmpty() }
            } finally { h.close(); directory.deleteRecursively() }
        }
    }

    @Test fun unsupportedPictureOnlySendStaysUnsentButTextCanGoWithANotice() = runBlocking {
        val fixture = TaskRelayScenario(machine).start()
        val h = AccountHarness(fixture.server, System.currentTimeMillis())
        val directory = Files.createTempDirectory("dock-picture").toFile()
        try {
            h.startAndSync()
            val id = spawn(h, fixture)
            val picture = picture(directory)
            val sender = LiveFirstMessageSender({ h.account }) { SessionMetadata.parse(h.store.session(it)!!.metadataJson) }
            val refused = sender.send(id, "", listOf(picture)) { true }
            assertFalse(refused.sent)
            assertEquals(listOf(AttachmentNotice.NotSupported), refused.notices)
            assertTrue(File(Uri.parse(picture.uri).path!!).isFile)
            assertTrue(fixture.server.sentRecords(id).isEmpty())
            val sent = sender.send(id, "Keep the text", listOf(picture)) { true }
            assertTrue(sent.sent)
            assertEquals(listOf(AttachmentNotice.NotSupported), sent.notices)
            eventually { fixture.server.sentRecords(id).size == 1 }
            assertTrue(fixture.pictureEvents(id).isEmpty())
            assertTrue(fixture.server.requestsTo("/v1/sessions/$id/attachments/request-upload").isEmpty())
            assertEquals(1, fixture.spawnPayloads.size)
        } finally { h.close(); directory.deleteRecursively() }
    }

    private suspend fun spawn(h: AccountHarness, fixture: TaskRelayScenario): String {
        val result = h.account.machineOps.spawnSession(SpawnSessionOptions(machineId = fixture.machineId, directory = "", agent = "rig",
            clientRequestId = "picture-request", providerId = "codex", modelId = "fixture-model", happyAgentTarget = HappyAgentSpawnTarget.Task())) as SpawnSessionResult.Success
        assertTrue(h.account.sender.ensureSessionReady(result.sessionId))
        return result.sessionId
    }

    private fun picture(directory: File): AttachmentPreview {
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val png = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        bitmap.recycle()
        val image = ImageNormalizer.normalize({ ByteArrayInputStream(png) }, "fixture.png", directory)
        return AttachmentPreview("picture", Uri.fromFile(image.file).toString(), image.width, image.height, image.mimeType, image.size, image.name)
    }
}