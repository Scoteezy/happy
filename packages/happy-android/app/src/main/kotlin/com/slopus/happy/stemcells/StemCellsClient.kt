package com.slopus.happy.stemcells

import com.slopus.happy.direct.ClientId
import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectAgentClient.Companion.escape
import com.slopus.happy.direct.DirectAgentError
import com.slopus.happy.direct.DirectHttpResponse
import com.slopus.happy.direct.Wire
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/*
 * The Stem Cells routes (happy-agent `API.md` "Spaces", "Agents",
 * "Messages and runs", "Surfaces", "Artifacts"), over the paired device's
 * tunnel: happy-ios `DirectAgentClient.swift`'s Stem Cells half.
 */

/** The opaque user setting that runs Prioritizing. */
const val STEM_CELLS_SETTING_KEY = "happy-desktop.experiments.stemCells"

/** `POST /v0/spaces/sidebar/operations`: the document after the batch, and the operations as they applied. */
data class OperationsResponse(val artifactId: String, val version: Int, val html: String, val operations: List<SpaceSidebarOperation>)

/** The task, workspace and conversation a client names for new work. */
data class StemCellIdentity(val taskId: String, val workspaceId: String, val agentId: String) {
    companion object {
        fun make() = StemCellIdentity(ClientId.make(), ClientId.make(), ClientId.make())
    }
}

private fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)
private val JsonElement?.string: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun mutation(): Pair<String, JsonElement> = "mutationId" to JsonPrimitive(ClientId.make())
private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
private val empty = JsonObject(emptyMap())

private suspend fun DirectAgentClient.value(method: String, path: String, json: JsonElement? = null): JsonElement = decode(method, path, json) { it }

// Artifacts and settings

suspend fun DirectAgentClient.artifacts(pageCursor: String? = null): JsonElement =
    value("GET", "/v0/artifacts" + (pageCursor?.let { "?pageCursor=${escape(it)}" } ?: ""))

// Spaces

suspend fun DirectAgentClient.sidebar(widgets: Boolean): SidebarResponse =
    decode("GET", if (widgets) "/v0/spaces/sidebar?widgets=true" else "/v0/spaces/sidebar", read = SidebarResponse::from)

suspend fun DirectAgentClient.applySidebarOperations(mutationId: String, baseVersion: Int, operations: List<SpaceSidebarOperation>): OperationsResponse {
    val json = obj("mutationId" to JsonPrimitive(mutationId), "baseVersion" to JsonPrimitive(baseVersion), "operations" to JsonArray(operations.map { it.json() }))
    val value = value("POST", "/v0/spaces/sidebar/operations", json)
    val artifactId = value.field("artifactId").string
    val version = (value.field("version") as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
    val html = value.field("html").string
    if (artifactId == null || version == null || html == null) throw DirectAgentError.InvalidResponse
    val applied = (value.field("operations") as? JsonArray).orEmpty().map { SpaceSidebarOperation.from(it) ?: throw DirectAgentError.InvalidResponse }
    return OperationsResponse(artifactId, version.toInt(), html, applied)
}

suspend fun DirectAgentClient.createStemCell(id: String, mutationId: String, sectionId: String?, identity: StemCellIdentity?): CreateStemCellResponse {
    val body = linkedMapOf<String, JsonElement>("id" to JsonPrimitive(id), "mutationId" to JsonPrimitive(mutationId), "sectionId" to (sectionId?.let(::JsonPrimitive) ?: JsonNull))
    if (identity != null) {
        body["taskId"] = JsonPrimitive(identity.taskId)
        body["workspaceId"] = JsonPrimitive(identity.workspaceId)
        body["agentId"] = JsonPrimitive(identity.agentId)
    }
    return decode("POST", "/v0/spaces/stem-cells", JsonObject(body), CreateStemCellResponse::from)
}

/** Closes new work nobody wrote in; the daemon refuses once it has a message. */
suspend fun DirectAgentClient.removeSpace(spaceId: String, mutationId: String) {
    send("PATCH", "/v0/spaces/${escape(spaceId)}", json = obj("removed" to JsonPrimitive(true), "mutationId" to JsonPrimitive(mutationId)))
}

suspend fun DirectAgentClient.reportAttention(state: String) {
    send("POST", "/v0/spaces/attention", json = obj("state" to JsonPrimitive(state)))
}

suspend fun DirectAgentClient.tidy(mutationId: String) {
    send("POST", "/v0/spaces/tidy", json = obj("mutationId" to JsonPrimitive(mutationId)))
}

// Bots and tasks

/** Every bot as sent, so its embedded agent can be normalized. */
suspend fun DirectAgentClient.bots(): List<JsonElement> = (value("GET", "/v0/bots").field("bots") as? JsonArray).orEmpty()

/** The tasks the person joined, as sent. */
suspend fun DirectAgentClient.tasks(): List<JsonElement> = (value("GET", "/v0/tasks?scope=joined").field("tasks") as? JsonArray).orEmpty()

suspend fun DirectAgentClient.renameTask(taskId: String, name: String) = rename("/v0/tasks/${escape(taskId)}", "task", name)

suspend fun DirectAgentClient.renameBot(botId: String, name: String) = rename("/v0/bots/${escape(botId)}", "bot", name)

suspend fun DirectAgentClient.leaveTask(taskId: String) {
    send("POST", "/v0/tasks/${escape(taskId)}/leave", json = obj(mutation()))
}

suspend fun DirectAgentClient.archiveTask(taskId: String) = archive("/v0/tasks/${escape(taskId)}", "task")

suspend fun DirectAgentClient.archiveBot(botId: String) = archive("/v0/bots/${escape(botId)}", "bot")

/** A subtask's conversation (`POST /v0/agents/:agentId/archive`). */
suspend fun DirectAgentClient.archiveAgent(agentId: String) {
    send("POST", "/v0/agents/${escape(agentId)}/archive", json = obj(mutation()))
}

/** Archival names the version it archives (`If-Match`). */
private suspend fun DirectAgentClient.archive(path: String, key: String) {
    val version = value("GET", path).field(key).field("version").string ?: throw DirectAgentError.InvalidResponse
    send("POST", "$path/archive", json = empty, headers = mapOf("If-Match" to version))
}

/** A rename names the version it renames (`If-Match`); one that lost a race reads the newer version and renames that. */
private suspend fun DirectAgentClient.rename(path: String, key: String, name: String) {
    val mutationId = ClientId.make()
    for (attempt in 0 until 2) {
        val version = value("GET", path).field(key).field("version").string ?: throw DirectAgentError.InvalidResponse
        try {
            send("PATCH", path, json = obj("name" to JsonPrimitive(name), "mutationId" to JsonPrimitive(mutationId)), headers = mapOf("If-Match" to version))
            return
        } catch (e: DirectAgentError.Status) {
            if (e.status == 409 && attempt == 0) continue
            throw e
        }
    }
}

// Surfaces

/** The joined Surfaces; null where the daemon serves none (`404`, `501`). */
suspend fun DirectAgentClient.surfaces(): List<Surface>? = try {
    decode("GET", "/v0/surfaces") { json -> Wire.array(Wire.obj(json)["surfaces"]).mapNotNull { runCatching { Surface.from(it) }.getOrNull() } }
} catch (e: DirectAgentError.Status) {
    if (e.status == 404 || e.status == 501) null else throw e
}

/** A todo list's active todos, then its completed ones. */
suspend fun DirectAgentClient.todos(surfaceId: String): List<Todo> = coroutineScope {
    val path = "/v0/surfaces/todo/${escape(surfaceId)}"
    fun read(json: JsonElement) = Wire.array(Wire.obj(json)["todos"]).mapNotNull { runCatching { Todo.from(it) }.getOrNull() }
    val active = async { decode("GET", path, read = ::read) }
    val completed = async { decode("GET", "$path?scope=completed", read = ::read) }
    active.await() + completed.await()
}

/** A new todo at the top of the list; `id` is the retry key. */
suspend fun DirectAgentClient.addTodo(surfaceId: String, id: String, text: String) {
    send("POST", "/v0/surfaces/todo/${escape(surfaceId)}", json = obj("id" to JsonPrimitive(id), "text" to JsonPrimitive(text), mutation()))
}

/** A document as Markdown, its read-only projection. */
suspend fun DirectAgentClient.document(surfaceId: String): String =
    value("GET", "/v0/surfaces/document/${escape(surfaceId)}").field("document").field("text").string ?: ""

// Conversations

suspend fun DirectAgentClient.agent(agentId: String): Agent = decode("GET", "/v0/agents/${escape(agentId)}", read = Agent::from)

suspend fun DirectAgentClient.bootstrap(agentId: String): Bootstrap = decode("GET", "/v0/agents/${escape(agentId)}/bootstrap", read = Bootstrap::from)

/** The same snapshot, read for a conversation's detail. */
suspend fun DirectAgentClient.sessionDetail(agentId: String): AgentDetail = decode("GET", "/v0/agents/${escape(agentId)}/bootstrap", read = AgentDetail::from)

/** Newest runs first page, or the runs before `before`, or the messages after `after`. */
suspend fun DirectAgentClient.messages(agentId: String, before: String? = null, after: String? = null, limit: Int = 50): MessagesResponse {
    var query = "limit=$limit&omitToolData=true"
    if (before != null) query += "&before=${escape(before)}"
    if (after != null) query += "&after=${escape(after)}"
    return decode("GET", "/v0/agents/${escape(agentId)}/messages?$query", read = MessagesResponse::from)
}

/** The catalog with the response it was read from, to keep offline. */
suspend fun DirectAgentClient.modelCatalogResponse(): Pair<StemCellModelCatalog, String> {
    val body = send("GET", "/v0/config").body
    val catalog = runCatching { StemCellModelCatalog.from(DirectAgentClient.parse(body)) }.getOrNull() ?: throw DirectAgentError.InvalidResponse
    return catalog to body.decodeToString()
}

/** The models every account offers, in the daemon's order. */
suspend fun DirectAgentClient.modelCatalog(): StemCellModelCatalog = modelCatalogResponse().first

/** What new work is sent with when nothing was picked: desktop's default selection. */
suspend fun DirectAgentClient.defaultMode(): AgentMode = modelCatalog().defaultMode()

/** Each account's plan usage, by provider ID. */
suspend fun DirectAgentClient.accountUsage(): Map<String, StemCellAccountUsage> = StemCellAccountUsage.project(value("GET", "/v0/usage"))

/** Asks for fresher readings of these accounts; they arrive as `provider.usage.updated`. */
suspend fun DirectAgentClient.refreshAccountUsage(providerIds: List<String>) {
    send("POST", "/v0/usage/refresh", json = obj("providerIds" to Wire.strings(providerIds)))
}

/** The mode the agent's last message carried; null before its first. */
suspend fun DirectAgentClient.agentMode(agentId: String): AgentMode? =
    decode("GET", "/v0/agents/${escape(agentId)}/mode") { json -> Wire.obj(json)["mode"]?.takeIf { it != JsonNull }?.let(AgentMode::from) }

/** An image a message carries. */
class OutgoingImage(val mimeType: String, val data: ByteArray)

/**
 * Always steering, as desktop sends: mid-turn it is taken at the next
 * boundary rather than waiting behind the run; idle, it starts one.
 */
suspend fun DirectAgentClient.sendMessage(agentId: String, id: String, text: String, images: List<OutgoingImage>, mode: AgentMode) {
    val body = linkedMapOf<String, JsonElement>(
        "id" to JsonPrimitive(id), "text" to JsonPrimitive(text), "delivery" to JsonPrimitive("steer"),
        "clientMetadata" to obj("composer" to JsonPrimitive("mobile")),
    )
    if (images.isNotEmpty()) {
        body["content"] = JsonArray(images.map {
            obj("type" to JsonPrimitive("image"), "mimeType" to JsonPrimitive(it.mimeType), "data" to JsonPrimitive(Base64.getEncoder().encodeToString(it.data)))
        })
    }
    body["mode"] = mode.json()
    send("POST", "/v0/agents/${escape(agentId)}/send", json = JsonObject(body))
}

/** The composer's draft as the computer keeps it for this person. */
suspend fun DirectAgentClient.draft(agentId: String): AgentDraft =
    decode("GET", "/v0/agents/${escape(agentId)}/draft") { AgentDraft.from(Wire.obj(it)["draft"] ?: JsonNull) }

/**
 * Saves the draft, or clears it with null. A write older than the one kept
 * is ignored; the answer is what is kept either way.
 */
suspend fun DirectAgentClient.saveDraft(agentId: String, value: DraftValue?, updatedAt: Double, mutationId: String): AgentDraft {
    val body = obj("draft" to (value?.json() ?: JsonNull), "updatedAt" to Wire.number(updatedAt), "mutationId" to JsonPrimitive(mutationId))
    return decode("PUT", "/v0/agents/${escape(agentId)}/draft", body) { AgentDraft.from(Wire.obj(it)["draft"] ?: JsonNull) }
}

suspend fun DirectAgentClient.abort(agentId: String) {
    send("POST", "/v0/agents/${escape(agentId)}/abort", json = empty)
}

suspend fun DirectAgentClient.markRead(agentId: String) {
    send("POST", "/v0/agents/${escape(agentId)}/read", json = empty)
}

/** A media item's bytes, whole or one range of them (inclusive). */
suspend fun DirectAgentClient.media(agentId: String, toolCallId: String, index: Int, range: IntRange? = null): DirectHttpResponse =
    send(
        "GET", "/v0/agents/${escape(agentId)}/tool-calls/${escape(toolCallId)}/media/$index",
        headers = range?.let { mapOf("Range" to "bytes=${it.first}-${it.last}") } ?: emptyMap(),
    )

// Sidebar proposals

/** A proposal Prioritizing made, with its latest document. */
suspend fun DirectAgentClient.sidebarProposal(artifactId: String): SidebarProposalResponse =
    decode("GET", "/v0/spaces/sidebar/proposals/${escape(artifactId)}", read = SidebarProposalResponse::from)

/** Takes the proposal as the person's own change of the sidebar. */
suspend fun DirectAgentClient.acceptSidebarProposal(artifactId: String, mutationId: String): SidebarProposal =
    decode("POST", "/v0/spaces/sidebar/proposals/${escape(artifactId)}/accept", obj("mutationId" to JsonPrimitive(mutationId))) {
        SidebarProposal.from(Wire.obj(it)["proposal"] ?: JsonNull)
    }

/** Keeps the sidebar as it is. */
suspend fun DirectAgentClient.declineSidebarProposal(artifactId: String, mutationId: String): SidebarProposal =
    decode("POST", "/v0/spaces/sidebar/proposals/${escape(artifactId)}/decline", obj("mutationId" to JsonPrimitive(mutationId))) {
        SidebarProposal.from(Wire.obj(it)["proposal"] ?: JsonNull)
    }

// Artifacts

/** An artifact object, a deleted one's too (`GET /v0/artifacts/:artifactId`). */
suspend fun DirectAgentClient.artifact(artifactId: String): JsonElement =
    value("GET", "/v0/artifacts/${escape(artifactId)}").field("artifact") ?: throw DirectAgentError.InvalidResponse

/** One version of an artifact, null for the latest: its entry, its files and how it wants to be shown. */
suspend fun DirectAgentClient.artifactVersion(artifactId: String, version: Int?): ArtifactVersion =
    decode("GET", "/v0/artifacts/${escape(artifactId)}/versions/${version?.toString() ?: "latest"}") { ArtifactVersion.from(Wire.obj(it)["version"] ?: JsonNull) }

/**
 * One file of a numbered version, whole or one range of it. `path` is the
 * manifest's, each segment escaped on its own. `range` is a `Range` header
 * as a player sent it.
 */
suspend fun DirectAgentClient.artifactFile(artifactId: String, version: Int, path: String, range: String? = null): DirectHttpResponse {
    val escaped = path.split("/").joinToString("/") { escape(it) }
    return send("GET", "/v0/artifacts/${escape(artifactId)}/versions/$version/files/$escaped", headers = range?.let { mapOf("Range" to it) } ?: emptyMap())
}

/** A daemon-relative image such as a bot's avatar. */
suspend fun DirectAgentClient.image(path: String): ByteArray {
    if (!path.startsWith("/v0/")) throw DirectAgentError.InvalidResponse
    return send("GET", path).body
}

/** Reading a file a range at a time, as happy-ios `DirectAgentClient.read` does. */
object DirectRanges {
    /** One answer holds at most 8 MiB, so a whole file is read in ranges of this size. */
    const val CHUNK = 4 * 1024 * 1024

    /**
     * Reads a file a range at a time through `fetch` (null: the whole file,
     * for one that is empty). `each` takes the pieces in order, with the
     * first answer's head on the first and the file's whole length.
     */
    suspend fun read(fetch: suspend (IntRange?) -> DirectHttpResponse, each: suspend (piece: ByteArray, head: DirectHttpResponse?, total: Int) -> Unit) {
        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total) {
            currentCoroutineContext().ensureActive()
            val response = try {
                fetch(offset..(offset + CHUNK - 1))
            } catch (e: DirectAgentError.Status) {
                if (e.status != 416 || offset != 0) throw e
                val whole = fetch(null)
                return each(whole.body, whole, whole.body.size)
            }
            // A server that ignores ranges answers with the whole file.
            if (response.status == 200 && offset == 0) return each(response.body, response, response.body.size)
            val length = length(response.headers["content-range"])
            if (response.status != 206 || response.body.isEmpty() || length == null) throw DirectAgentError.InvalidResponse
            if (offset == 0) total = length
            each(response.body, if (offset == 0) response else null, total)
            offset += response.body.size
        }
    }

    /**
     * A single `Range` no longer than one answer may hold, as a server may
     * answer: `bytes=0-` asks for the first piece, and a player asks on.
     * Anything else (several ranges, a suffix) is left as it is.
     */
    fun bounded(header: String): String {
        val spec = header.trim()
        if (!spec.startsWith("bytes=") || ',' in spec) return header
        val bounds = spec.substring(6).split("-", limit = 2)
        if (bounds.size != 2) return header
        val start = bounds[0].trim().toIntOrNull() ?: return header
        val last = start + CHUNK - 1
        val end = bounds[1].trim().toIntOrNull()?.let { minOf(it, last) } ?: last
        return "bytes=$start-$end"
    }

    /** The whole length in a `Content-Range` such as `bytes 0-1/28560`. */
    fun length(contentRange: String?): Int? = contentRange?.split("/")?.lastOrNull()?.toIntOrNull()
}
