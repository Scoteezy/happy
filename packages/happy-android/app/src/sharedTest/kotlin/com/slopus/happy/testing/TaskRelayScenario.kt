package com.slopus.happy.testing

import com.slopus.happy.crypto.AccountEncryption
import com.slopus.happy.crypto.decryptBlob
import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.model.isRigV1
import com.slopus.happy.net.HappyJson
import com.slopus.happy.sync.ApiSession
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Encrypted protocol fixture shared by JVM tests and ONLY the shell-harness APK. */
class TaskRelayScenario(machineMetadata: JsonObject, private val bots: Boolean = false, private val failFirstAvatar: Boolean = false, private val pictures: Boolean = false) : AutoCloseable {
    val server = FakeHappyServer(AccountEncryption.generateDataKey(), UUID.randomUUID().toString(), System.currentTimeMillis())
    val machineId = "fixture-task-relay-machine"
    val created = CopyOnWriteArrayList<String>()
    val spawnPayloads = CopyOnWriteArrayList<JsonObject>()
    val avatarPayloads = CopyOnWriteArrayList<JsonObject>()
    val avatarPngs = CopyOnWriteArrayList<ByteArray>()
    val worn = CopyOnWriteArrayList<String>()
    private val byRequest = mutableMapOf<String, String>()

    init {
        server.addMachine(machineId, machineMetadata, System.currentTimeMillis())
        server.rpc["$machineId:spawn-happy-session"] = { encrypted ->
            val payload = server.openRpc(machineId, encrypted).jsonObject
            val target = payload.getValue("target").jsonObject
            if (bots) {
                check(target["kind"] == JsonPrimitive("bot") && target["name"]?.jsonPrimitive?.content?.isNotBlank() == true) { "Expected named Bot target" }
            } else check(target == buildJsonObject { put("kind", "task") }) { "Expected unnamed Task target" }
            check(payload["type"]?.jsonPrimitive?.content == "happy-agent-spawn") { "Expected Happy Agent spawn request" }
            check("directory" !in payload && "directory" !in payload.getValue("agentConfiguration").jsonObject)
            val requestId = payload.getValue("clientRequestId").jsonPrimitive.content.also { check(it.isNotBlank()) }
            spawnPayloads += payload
            val id = byRequest.getOrPut(requestId) {
                val id = "fixture-created-${if (bots) "bot" else "task"}-${UUID.randomUUID()}"
                server.addSession(id, System.currentTimeMillis(), buildJsonObject {
                    put("path", "/fixture/Tasks/created")
                    put("host", "fixture.local")
                    put("machineId", machineId)
                    put("rigMetadataVersion", 1)
                    putJsonObject("client") { put("id", "rig"); put("name", "Happy Agent"); put("version", "1") }
                    if (bots) putJsonObject("bot") {
                        put("id", id); put("name", target.getValue("name")); put("username", "fixturebot")
                        put("workspaceId", "fixture-workspace"); put("orderKey", "1")
                    } else putJsonObject("task") {
                        put("id", id); put("name", "Created fixture task"); put("orderKey", "1"); put("canArchive", true)
                    }
                    putJsonObject("capabilities") {
                        put("abort", false); put("modelSelection", false); put("reasoningSelection", false)
                        put("permissionModeSelection", false); put("resume", false); put("shell", false); put("steering", false)
                        putJsonObject("attachments") {
                            put("enabled", pictures); put("maxBytes", if (pictures) 10 * 1024 * 1024 else 0)
                            putJsonArray("mediaTypes") { if (pictures) add("image/jpeg") }
                        }
                        putJsonObject("files") {
                            put("browse", false); put("read", false); put("search", false); put("write", false)
                        }
                        putJsonArray("rpcMethods") { add("killSession"); add("leaveTask"); if (bots) add("setAvatar") }
                    }
                }.also { check(SessionMetadata.parse(it.toString()).isRigV1()) { "Fixture must satisfy the latest Happy Agent metadata schema" } })
                created += id
                if (bots) server.rpc["$id:setAvatar"] = { encrypted -> wear(id, encrypted) }
                val record = HappyJson.encodeToJsonElement(ApiSession.serializer(), server.session(id)!!).jsonObject
                server.emitUpdate(JsonObject(record + ("t" to JsonPrimitive("new-session"))))
                id
            }
            server.sealRpc(machineId, buildJsonObject { put("type", "success"); put("sessionId", id) })
        }
    }

    fun start(): TaskRelayScenario { server.start(); return this }
    override fun close() = server.close()

    private fun fileEvent(record: JsonObject): JsonObject? =
        ((record["content"] as? JsonObject)?.get("data") as? JsonObject)?.get("ev")?.let { it as? JsonObject }
            ?.takeIf { it["t"] == JsonPrimitive("file") }

    fun pictureEvents(id: String): List<JsonObject> = server.sentRecords(id).mapNotNull { fileEvent(it.second) }

    fun pictureBytes(id: String, event: JsonObject): ByteArray? {
        val ref = event["ref"]?.jsonPrimitive?.content ?: return null
        if (!ref.startsWith("sessions/$id/attachments/")) return null
        val sealed = server.attachments[ref] ?: return null
        return decryptBlob(sealed, AccountEncryption.blobKeyForDataKey(server.dataKey(id)))
    }

    private fun wear(id: String, encrypted: JsonElement): JsonElement {
        val payload = server.openRpc(id, encrypted).jsonObject
        val ref = payload.getValue("ref").jsonPrimitive.content
        check(ref.startsWith("sessions/$id/attachments/"))
        val sealed = server.attachments.getValue(ref)
        val png = requireNotNull(decryptBlob(sealed, AccountEncryption.blobKeyForDataKey(server.dataKey(id))))
        check(payload["mimeType"] == JsonPrimitive("image/png"))
        check(payload.getValue("size").jsonPrimitive.long == png.size.toLong())
        check(png.size > 24 && png.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        check(java.nio.ByteBuffer.wrap(png, 16, 8).let { it.int == 256 && it.int == 256 })
        avatarPayloads += payload
        avatarPngs += png
        if (failFirstAvatar && avatarPayloads.size == 1) {
            return server.sealRpc(id, buildJsonObject { put("success", false); put("error", "Fixture avatar refused once") })
        }
        val version = worn.count { it == id } + 1
        val avatar = buildJsonObject {
            put("ref", ref); put("version", version)
            put("preview", server.seal(id, buildJsonObject {
                // Synthetic placeholder only; the downloaded PNG is the real painted face.
                put("thumbhash", "HwgKPxxwd3dweHiHiHiHd4h3Bwd4gI8I"); put("mimeType", "image/png")
            }))
        }
        synchronized(server) {
            val index = server.sessions.indexOfFirst { it.id == id }
            server.sessions[index] = server.sessions[index].copy(avatar = avatar, avatarVersion = JsonPrimitive(version))
        }
        worn += id
        server.emitUpdate(buildJsonObject { put("t", "update-session"); put("id", id); put("avatar", avatar); put("avatarVersion", version) })
        return server.sealRpc(id, buildJsonObject { put("success", true) })
    }

    /** Deliberately excludes credentials, encrypted blobs, and private keys. */
    fun evidence(): JsonObject = buildJsonObject {
        put("loopbackOnly", java.net.URI(server.url).host == "127.0.0.1")
        put("spawnCount", spawnPayloads.size)
        put("taskTargetsOnly", spawnPayloads.all { it["target"] == buildJsonObject { put("kind", "task") } })
        put("botTargetsOnly", spawnPayloads.all { it["target"]?.jsonObject?.get("kind") == JsonPrimitive("bot") })
        put("avatarAttempts", avatarPayloads.size)
        put("avatarsWorn", worn.size)
        put("allAvatarsValidPng256", avatarPngs.size == avatarPayloads.size)
        putJsonArray("created") {
            created.forEach { id -> addJsonObject {
                val records = server.sentRecords(id).map { it.second }
                val files = records.mapNotNull(::fileEvent)
                put("sessionId", id)
                put("firstMessages", JsonArray(records))
                put("pictureEvents", files.size)
                put("picturesBeforeText", records.drop(files.size).none { fileEvent(it) != null })
                put("picturesValidJpeg", files.all { event ->
                    val bytes = pictureBytes(id, event)
                    bytes != null && bytes.size >= 4 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() &&
                        bytes[bytes.lastIndex - 1] == (-1).toByte() && bytes.last() == (-39).toByte() &&
                        event["size"]?.jsonPrimitive?.long == bytes.size.toLong()
                })
            } }
        }
    }
}