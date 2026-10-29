package com.slopus.happy.testing

import com.slopus.happy.crypto.AccountEncryption
import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.net.HappyJson
import com.slopus.happy.sync.ApiSession
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Encrypted protocol fixture shared by JVM tests and ONLY the shell-harness APK. */
class TaskRelayScenario(machineMetadata: JsonObject) : AutoCloseable {
    val server = FakeHappyServer(AccountEncryption.generateDataKey(), UUID.randomUUID().toString(), System.currentTimeMillis())
    val machineId = "fixture-task-relay-machine"
    val created = CopyOnWriteArrayList<String>()
    val spawnPayloads = CopyOnWriteArrayList<JsonObject>()
    private val byRequest = mutableMapOf<String, String>()

    init {
        server.addMachine(machineId, machineMetadata, System.currentTimeMillis())
        server.rpc["$machineId:spawn-happy-session"] = { encrypted ->
            val payload = server.openRpc(machineId, encrypted).jsonObject
            check(payload["target"] == buildJsonObject { put("kind", "task") }) { "Expected unnamed Task target" }
            check(payload["type"]?.jsonPrimitive?.content == "happy-agent-spawn") { "Expected Happy Agent spawn request" }
            check("directory" !in payload && "directory" !in payload.getValue("agentConfiguration").jsonObject)
            val requestId = payload.getValue("clientRequestId").jsonPrimitive.content.also { check(it.isNotBlank()) }
            spawnPayloads += payload
            val id = byRequest.getOrPut(requestId) {
                val id = "fixture-created-task-${UUID.randomUUID()}"
                server.addSession(id, System.currentTimeMillis(), buildJsonObject {
                    put("path", "/fixture/Tasks/created")
                    put("host", "fixture.local")
                    put("machineId", machineId)
                    putJsonObject("client") { put("id", "rig"); put("name", "Happy Agent"); put("version", "1") }
                    putJsonObject("task") {
                        put("id", id); put("name", "Created fixture task"); put("orderKey", "1"); put("canArchive", true)
                    }
                    putJsonObject("capabilities") {
                        put("abort", false); put("modelSelection", false); put("reasoningSelection", false)
                        put("permissionModeSelection", false); put("resume", false); put("shell", false); put("steering", false)
                        putJsonObject("attachments") {
                            put("enabled", false); put("maxBytes", 0); putJsonArray("mediaTypes") {}
                        }
                        putJsonObject("files") {
                            put("browse", false); put("read", false); put("search", false); put("write", false)
                        }
                        putJsonArray("rpcMethods") { add("killSession"); add("leaveTask") }
                    }
                }.also { check(SessionMetadata.parse(it.toString()) != null) { "Fixture must satisfy the real metadata schema" } })
                created += id
                val record = HappyJson.encodeToJsonElement(ApiSession.serializer(), server.session(id)!!).jsonObject
                server.emitUpdate(JsonObject(record + ("t" to JsonPrimitive("new-session"))))
                id
            }
            server.sealRpc(machineId, buildJsonObject { put("type", "success"); put("sessionId", id) })
        }
    }

    fun start(): TaskRelayScenario { server.start(); return this }
    override fun close() = server.close()

    /** Deliberately excludes credentials, encrypted blobs, and private keys. */
    fun evidence(): JsonObject = buildJsonObject {
        put("loopbackOnly", java.net.URI(server.url).host == "127.0.0.1")
        put("spawnCount", spawnPayloads.size)
        put("taskTargetsOnly", spawnPayloads.all { it["target"] == buildJsonObject { put("kind", "task") } })
        putJsonArray("created") {
            created.forEach { id -> addJsonObject {
                put("sessionId", id)
                put("firstMessages", JsonArray(server.sentRecords(id).map { it.second }))
            } }
        }
    }
}