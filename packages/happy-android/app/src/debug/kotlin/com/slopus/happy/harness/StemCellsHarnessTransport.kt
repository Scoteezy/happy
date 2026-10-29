package com.slopus.happy.harness

import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectHttpResponse
import com.slopus.happy.direct.DirectHttpStream
import com.slopus.happy.direct.DirectTransport
import com.slopus.happy.stemcells.SpaceSidebar
import com.slopus.happy.stemcells.SpaceSidebarDocument
import com.slopus.happy.stemcells.SpaceSidebarError
import com.slopus.happy.stemcells.SpaceSidebarNewNode
import com.slopus.happy.stemcells.SpaceSidebarOperation
import com.slopus.happy.stemcells.SpaceSidebarPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.net.URLDecoder

/**
 * The Stem Cells harnesses' computer: a daemon answering the routes the
 * phone calls, with the shapes happy-agent's API.md gives them, from the
 * shared fixture (`assets/harness/stem-cells-computer.json`, which the iOS
 * reference host serves too). It keeps what the phone changes, as the daemon
 * would: sidebar operations applied by the same applier and announced as
 * `space.sidebar.changed`, new work made and placed under Happy, sends
 * accepted into runs and answered by a scripted reply streamed as
 * `message.delta`, drafts, reads and aborts. Debug builds only; nothing
 * here reaches a real daemon.
 *
 * `scope` runs the scripted replies; `now` is the clock the fixture's times
 * are moved to. `drawMedia` draws a tool call's picture (a colour and a
 * label at a size), as the daemon would serve its file. Routes this computer
 * doesn't know (Stories, Stickers) go to `fallback`.
 */
class StemCellsHarnessTransport(
    fixture: String,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val drawMedia: (color: Int, label: String, width: Int, height: Int) -> ByteArray = { _, _, _, _ -> ByteArray(0) },
    private val fallback: DirectTransport? = null,
    /** How long each request takes, as over a link. */
    private val latencyMs: Long = 0,
) : DirectTransport {
    private val lock = Mutex()
    private val root: JsonObject
    private val health: JsonObject
    private val settings = LinkedHashMap<String, JsonElement>()

    // The sidebar.
    private val artifactId: String
    private var version: Int
    private var document: SpaceSidebarDocument
    private val agentIcons: JsonObject
    private val spaces = LinkedHashMap<String, JsonObject>()
    private val mutations = HashMap<String, JsonObject>()

    // Bots, tasks and their agents, kept flat and composed into trees as read.
    private val bots = ArrayList<JsonObject>()
    private val tasks = ArrayList<JsonObject>()
    private val agents = LinkedHashMap<String, JsonObject>()
    private val agentVersions = HashMap<String, Int>()

    private val surfaces: JsonArray
    private val todos = HashMap<String, MutableList<JsonObject>>()
    private val documents: JsonObject
    private val artifacts: JsonObject

    private class Run(val id: String, var status: String, var reason: String?, val startedAt: Long, var endedAt: Long?, val messages: MutableList<JsonObject>)
    private class Conversation(
        val runs: MutableList<Run>,
        val pending: MutableList<JsonObject>,
        var mode: JsonElement,
        var draft: JsonObject,
        val processes: JsonArray,
    )
    private val conversations = HashMap<String, Conversation>()
    private val replies = HashMap<String, Job>()
    private val toolItems = HashMap<String, List<JsonObject>>()
    private val media: JsonObject
    private val reply: JsonObject

    // The journal.
    private val journal = ArrayList<JsonObject>()
    private var cursor = 0
    private val streams = ArrayList<Channel<ByteArray>>()
    private var ids = 0

    init {
        val raw = DirectAgentClient.parse(fixture.toByteArray()) as JsonObject
        val offset = now() - ((raw["now"] as JsonPrimitive).content.toLong())
        root = shift(raw, offset) as JsonObject
        health = root.obj("health")
        root.obj("settings").forEach { (key, value) -> settings[key] = value }
        val sidebar = root.obj("sidebar")
        artifactId = sidebar.str("artifactId")!!
        version = sidebar.num("version")!!.toInt()
        document = SpaceSidebar.parse(sidebar.str("html")!!)
        agentIcons = sidebar.obj("agentIcons")
        for (space in root.array("spaces")) spaces[(space as JsonObject).str("id")!!] = space
        for (bot in root.array("bots")) bots += flatten(bot as JsonObject)
        for (task in root.array("tasks")) tasks += flatten(task as JsonObject)
        surfaces = root.array("surfaces")
        root.obj("todos").forEach { (id, list) -> todos[id] = (list as JsonArray).map { it as JsonObject }.toMutableList() }
        documents = root.obj("documents")
        artifacts = root.obj("artifacts")
        media = root.obj("media")
        reply = root.obj("reply")
        root.obj("agents").forEach { (id, value) ->
            val o = value as JsonObject
            val runs = o.array("runs").map { element ->
                val run = element as JsonObject
                val messages = run.array("messages").map { (it as JsonObject) + ("runId" to JsonPrimitive(run.str("id"))) }.map(::JsonObject)
                for (message in messages) for (block in message.array("content")) {
                    val call = block as JsonObject
                    if (call.str("type") == "tool_call") call.obj("presentation").array("items").takeIf { it.isNotEmpty() }?.let { items ->
                        toolItems[call.str("id")!!] = items.map { it as JsonObject }
                    }
                }
                Run(run.str("id")!!, run.str("status")!!, run.str("reason"), run.num("startedAt")!!.toLong(), run.num("endedAt")?.toLong(), messages.toMutableList())
            }
            conversations[id] = Conversation(runs.toMutableList(), ArrayList(), o["mode"] ?: JsonNull, o.obj("draft"), o.array("processes"))
        }
    }

    // Transport

    override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
        if (latencyMs > 0) delay(latencyMs)
        val route = path.substringBefore('?')
        if (fallback != null && (route.startsWith("/v0/stories") || route.startsWith("/v0/stickers"))) {
            return fallback.request(method, path, credential, body, headers)
        }
        val query = path.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val input = body?.takeIf { it.isNotEmpty() }?.let { runCatching { DirectAgentClient.parse(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        return lock.withLock {
            try {
                answer(method, route.split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, "UTF-8") }, query, input, headers)
            } catch (e: SpaceSidebarError) {
                error(400, "invalid_request", e.message ?: "")
            }
        }
    }

    override suspend fun stream(path: String, credential: String, headers: Map<String, String>): DirectHttpStream {
        if (latencyMs > 0) delay(latencyMs)
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        val after = headers.entries.firstOrNull { it.key.equals("Last-Event-ID", ignoreCase = true) }?.value
        lock.withLock {
            val index = after?.let { last -> journal.indexOfFirst { it.str("cursor") == last } } ?: -1
            val resumed = index >= 0
            val hello = obj(
                "cursor" to current(), "gap" to (after != null && !resumed), "resumed" to resumed, "connectedAt" to now(),
                "daemonId" to "dharness", "daemonStartedAt" to now(), "draining" to false,
            )
            channel.trySend("event: hello\ndata: $hello\n\n".toByteArray())
            if (resumed) for (event in journal.drop(index + 1)) channel.trySend(frame(event))
            streams += channel
        }
        val body = channelFlow {
            // The daemon's heartbeat, so a quiet stream is not taken for a lost one.
            val heartbeat = launch {
                while (true) {
                    delay(15_000)
                    channel.trySend(":\n\n".toByteArray())
                }
            }
            try {
                for (bytes in channel) send(bytes)
            } finally {
                heartbeat.cancel()
                lock.withLock { streams -= channel }
            }
        }
        return DirectHttpStream(200, mapOf("content-type" to "text/event-stream"), body)
    }

    // Routes

    private fun answer(method: String, parts: List<String>, query: Map<String, String>, input: JsonObject, headers: Map<String, String>): DirectHttpResponse {
        val route = "/" + parts.joinToString("/")
        when {
            method == "GET" && route == "/v0/health" -> return ok(health)
            parts.size == 3 && parts[1] == "user-settings" -> {
                val key = parts[2]
                if (method == "PUT") settings[key] = input["value"] ?: JsonNull
                val setting = obj("key" to key, "value" to (settings[key] ?: JsonNull), "updatedAt" to settings[key]?.let { now() }, "version" to settings[key]?.let { "v-$key" })
                if (method == "PUT") emit("user_setting.updated", obj("setting" to setting))
                return ok(obj("setting" to setting))
            }
            method == "GET" && route == "/v0/config" -> return ok(root.obj("config"))
            method == "GET" && route == "/v0/usage" -> return ok(root.obj("usage"))
            method == "POST" && route == "/v0/usage/refresh" -> return ok(root.obj("usage"))
            method == "GET" && route == "/v0/bots" -> return ok(obj("bots" to JsonArray(bots.map(::composedOwner))))
            method == "GET" && route == "/v0/tasks" -> return ok(obj("tasks" to JsonArray(tasks.filter { it["archivedAt"] is JsonNull || it["archivedAt"] == null }.map(::composedOwner)), "memberships" to JsonArray(emptyList())))
            method == "GET" && route == "/v0/surfaces" -> return ok(obj("surfaces" to surfaces))
            method == "GET" && route == "/v0/artifacts" -> return ok(obj("artifacts" to JsonArray(artifacts.values.map { (it as JsonObject).obj("artifact") }), "nextPageCursor" to null))
        }
        return when (parts.getOrNull(1)) {
            "spaces" -> spacesRoute(method, parts, query, input)
            "agents" -> agentsRoute(method, parts, query, input, headers)
            "tasks", "bots" -> ownersRoute(method, parts, input)
            "surfaces" -> surfacesRoute(method, parts, query, input)
            "artifacts" -> artifactsRoute(parts, headers)
            else -> error(404, "not_found", "No such route.")
        }
    }

    private fun spacesRoute(method: String, parts: List<String>, query: Map<String, String>, input: JsonObject): DirectHttpResponse {
        val route = "/" + parts.joinToString("/")
        when {
            method == "GET" && route == "/v0/spaces/sidebar" -> {
                val widgets = query["widgets"] == "true"
                return ok(obj(
                    "artifactId" to artifactId, "version" to version, "html" to SpaceSidebar.format(document, widgets),
                    "spaces" to JsonArray(spaces.values.toList()), "agentIcons" to agentIcons,
                ))
            }
            method == "POST" && route == "/v0/spaces/sidebar/operations" -> {
                val mutationId = input.str("mutationId") ?: return error(400, "invalid_request", "mutationId is required.")
                mutations[mutationId]?.let { return ok(it) }
                val operations = input.array("operations").map { SpaceSidebarOperation.from(it) ?: return error(400, "invalid_request", "An operation is not valid.") }
                val applied = apply(operations, "person", mutationId)
                val answer = obj("artifactId" to artifactId, "version" to version, "html" to SpaceSidebar.format(document), "operations" to JsonArray(applied.map { it.json() }))
                mutations[mutationId] = answer
                return ok(answer)
            }
            method == "POST" && route == "/v0/spaces/stem-cells" -> return createStemCell(input)
            method == "POST" && (route == "/v0/spaces/attention" || route == "/v0/spaces/tidy") -> return ok(obj())
            method == "PATCH" && parts.size == 3 -> {
                val spaceId = parts[2]
                val space = spaces[spaceId] ?: return error(404, "not_found", "No such space.")
                if (input["removed"] == JsonPrimitive(true)) {
                    val agentId = space.obj("target").str("agentId")
                    if (agentId != null && conversations[agentId]?.runs?.any { it.messages.isNotEmpty() } == true) {
                        return error(409, "conflict", "This work has a message; archive it instead.")
                    }
                    removeSpace(spaceId, input.str("mutationId"))
                    return DirectHttpResponse(204)
                }
                return ok(obj("space" to space))
            }
        }
        return error(404, "not_found", "No such route.")
    }

    private fun agentsRoute(method: String, parts: List<String>, query: Map<String, String>, input: JsonObject, headers: Map<String, String>): DirectHttpResponse {
        val agentId = parts.getOrNull(2) ?: return error(404, "not_found", "No such agent.")
        val agent = agents[agentId] ?: return error(404, "not_found", "No such agent.")
        val conversation = conversations.getOrPut(agentId) { Conversation(ArrayList(), ArrayList(), JsonNull, obj("value" to null, "updatedAt" to null), JsonArray(emptyList())) }
        val action = parts.getOrNull(3)
        when {
            method == "GET" && action == null -> return ok(obj("agent" to compose(agentId), "profiles" to JsonArray(emptyList()), "slashCommands" to JsonArray(emptyList())))
            method == "GET" && action == "bootstrap" -> {
                val children = agents.values.filter { it.str("parentAgentId") == agentId }.sortedByDescending { it.num("createdAt") ?: 0.0 }
                return ok(obj(
                    "agent" to compose(agentId), "draft" to conversation.draft, "mode" to conversation.mode,
                    "context" to null, "usage" to obj(), "pending" to JsonArray(conversation.pending),
                    "processes" to conversation.processes, "subagents" to JsonArray(children.map { compose(it.str("id")!!) }),
                    "profiles" to JsonArray(emptyList()), "slashCommands" to JsonArray(emptyList()), "cursor" to current(),
                ))
            }
            method == "GET" && action == "messages" -> return ok(page(conversation, query["before"], query["limit"]?.toIntOrNull() ?: 50))
            method == "GET" && action == "mode" -> return ok(obj("mode" to conversation.mode))
            method == "GET" && action == "draft" -> return ok(obj("draft" to conversation.draft))
            method == "PUT" && action == "draft" -> {
                val stamp = input.num("updatedAt")
                val held = conversation.draft.num("updatedAt")
                if (stamp == null || held == null || stamp >= held) {
                    conversation.draft = obj("value" to (input["draft"] ?: JsonNull), "updatedAt" to (stamp ?: now().toDouble()))
                    emit("agent.draft.updated", obj("agentId" to agentId, "draft" to conversation.draft))
                }
                return ok(obj("draft" to conversation.draft))
            }
            method == "POST" && action == "send" -> return send(agentId, conversation, input)
            method == "POST" && action == "read" -> {
                if (agent["unread"] != null && agent["unread"] !is JsonNull) updateAgent(agentId, obj("unread" to null))
                return ok(obj())
            }
            method == "POST" && action == "abort" -> {
                abort(agentId, conversation)
                return ok(obj())
            }
            method == "POST" && action == "archive" -> {
                updateAgent(agentId, obj("archivedAt" to now()))
                spaces.values.firstOrNull { it.obj("target").str("agentId") == agentId }?.let { removeSpace(it.str("id")!!, input.str("mutationId")) }
                if (document.contains(agentId)) apply(listOf(SpaceSidebarOperation.Remove(agentId)), "daemon", null)
                return ok(obj("agent" to compose(agentId)))
            }
            method == "GET" && action == "tool-calls" && parts.getOrNull(5) == "media" -> {
                val toolCallId = parts[4]
                val index = parts.getOrNull(6)?.toIntOrNull() ?: return error(404, "not_found", "No such media.")
                val item = toolItems[toolCallId]?.getOrNull(index) ?: return error(404, "not_found", "No such media.")
                val look = media.obj("$toolCallId/$index")
                val color = look.str("color")?.removePrefix("#")?.toLong(16)?.let { (0xFF000000 or it).toInt() } ?: 0xFF8E8E93.toInt()
                // Drawn small, the shape of the file it stands for.
                val width = ((item.num("width") ?: 1080.0) / 4).toInt()
                val height = ((item.num("height") ?: 1080.0) / 4).toInt()
                return served(drawMedia(color, look.str("label") ?: item.str("name") ?: "", width, height), item.str("mimeType") ?: "image/png", headers)
            }
        }
        return error(404, "not_found", "No such route.")
    }

    private fun ownersRoute(method: String, parts: List<String>, input: JsonObject): DirectHttpResponse {
        val isTask = parts[1] == "tasks"
        val list = if (isTask) tasks else bots
        val key = if (isTask) "task" else "bot"
        val id = parts.getOrNull(2) ?: return error(404, "not_found", "No such $key.")
        val index = list.indexOfFirst { it.str("id") == id }
        if (index < 0) return error(404, "not_found", "No such $key.")
        val owner = list[index]
        val agentId = owner.obj("agent").str("id")
        when {
            method == "GET" && parts.size == 3 -> return ok(obj(key to composedOwner(owner)))
            method == "PATCH" && parts.size == 3 -> {
                val name = input.str("name") ?: return error(400, "invalid_request", "name is required.")
                list[index] = JsonObject(owner + ("name" to JsonPrimitive(name)) + ("version" to JsonPrimitive("v-$id-${++ids}")))
                emit("$key.updated", obj("${key}Id" to id, "previousVersion" to owner.str("version"), "version" to list[index].str("version"), "changes" to obj("name" to name, "updatedAt" to now())))
                agentId?.let { updateAgent(it, obj("title" to name)) }
                retitle(agentId, name)
                return ok(obj(key to composedOwner(list[index])))
            }
            method == "POST" && (parts.getOrNull(3) == "archive" || parts.getOrNull(3) == "leave") -> {
                list[index] = JsonObject(owner + ("archivedAt" to JsonPrimitive(now())))
                spaces.values.firstOrNull { it.obj("target").str("id") == id }?.let { removeSpace(it.str("id")!!, input.str("mutationId")) }
                return ok(obj(key to composedOwner(list[index])))
            }
        }
        return error(404, "not_found", "No such route.")
    }

    private fun surfacesRoute(method: String, parts: List<String>, query: Map<String, String>, input: JsonObject): DirectHttpResponse {
        val id = parts.getOrNull(3) ?: return error(404, "not_found", "No such surface.")
        when (parts.getOrNull(2)) {
            "todo" -> {
                val list = todos.getOrPut(id) { ArrayList() }
                if (method == "POST") {
                    val todo = obj("id" to (input.str("id") ?: "td${++ids}"), "text" to (input.str("text") ?: ""), "status" to "active")
                    if (list.none { it.str("id") == todo.str("id") }) {
                        list.add(0, todo)
                        emit("todo.created", obj("surfaceId" to id, "todo" to (todo + ("surfaceId" to JsonPrimitive(id))).let(::JsonObject)))
                    }
                    return ok(obj("todo" to todo))
                }
                val scope = query["scope"] ?: "active"
                return ok(obj("todos" to JsonArray(list.filter { it.str("status") == scope })))
            }
            "document" -> return ok(obj("document" to obj("text" to (documents.str(id) ?: ""))))
        }
        return error(404, "not_found", "No such route.")
    }

    private fun artifactsRoute(parts: List<String>, headers: Map<String, String>): DirectHttpResponse {
        val entry = artifacts[parts.getOrNull(2)] as? JsonObject ?: return error(404, "not_found", "No such artifact.")
        val artifact = entry.obj("artifact")
        if (parts.size == 3) return ok(obj("artifact" to artifact))
        if (parts.getOrNull(3) != "versions") return error(404, "not_found", "No such route.")
        val number = parts.getOrNull(4)?.let { if (it == "latest") artifact.num("latestVersion")?.toInt() else it.toIntOrNull() } ?: return error(404, "not_found", "No such version.")
        val files = entry.obj("files")
        if (parts.getOrNull(5) == "files") {
            val path = parts.drop(6).joinToString("/")
            val text = files.str(path) ?: return error(404, "not_found", "No such file.")
            return served(text.toByteArray(), if (path.endsWith(".md")) "text/markdown" else "text/plain", headers)
        }
        val listed = files.map { (path, text) -> obj("path" to path, "mimeType" to if (path.endsWith(".md")) "text/markdown" else "text/plain", "size" to (text as JsonPrimitive).content.toByteArray().size) }
        return ok(obj("version" to obj(
            "artifactId" to artifact.str("id"), "number" to number, "title" to artifact.str("title"),
            "entry" to artifact.obj("entry"), "files" to JsonArray(listed), "live" to null,
        )))
    }

    // The sidebar

    /** Applies operations as one batch and announces it. */
    private fun apply(operations: List<SpaceSidebarOperation>, author: String, mutationId: String?): List<SpaceSidebarOperation> {
        val application = SpaceSidebar.apply(document, operations)
        if (application.operations.isEmpty()) return emptyList()
        val previous = version
        document = application.document
        version += 1
        emit("space.sidebar.changed", obj(
            "artifactId" to artifactId, "previousVersion" to previous, "version" to version, "author" to author,
            "operations" to JsonArray(application.operations.map { it.json() }), "mutationId" to mutationId,
        ))
        return application.operations
    }

    private fun removeSpace(spaceId: String, mutationId: String?) {
        spaces.remove(spaceId) ?: return
        emit("space.removed", obj("spaceId" to spaceId, "mutationId" to mutationId))
        if (document.contains(spaceId)) apply(listOf(SpaceSidebarOperation.Remove(spaceId)), "daemon", null)
    }

    private fun SpaceSidebarDocument.contains(id: String): Boolean {
        fun search(nodes: List<com.slopus.happy.stemcells.SpaceSidebarNode>): Boolean = nodes.any { it.id == id || search(it.children) }
        return search(items)
    }

    /** New work, as `POST /v0/spaces/stem-cells` makes it: a task, its agent and a row right below Happy. */
    private fun createStemCell(input: JsonObject): DirectHttpResponse {
        val spaceId = input.str("id") ?: "sp${++ids}"
        spaces[spaceId]?.let { existing -> return ok(obj("space" to existing, "agent" to compose(existing.obj("target").str("agentId")!!))) }
        val taskId = input.str("taskId") ?: "tk${++ids}"
        val agentId = input.str("agentId") ?: "ag${++ids}"
        val workspaceId = input.str("workspaceId") ?: "ws${++ids}"
        val sectionId = input.str("sectionId")
        val time = now()
        val agent = obj(
            "id" to agentId, "workspaceId" to workspaceId, "parentAgentId" to null, "subtask" to false, "userVisible" to true,
            "managedByAnotherAgent" to false, "canSendMessages" to true, "title" to null, "titleStatus" to "pending", "status" to "idle",
            "wait" to null, "subagents" to obj("total" to 0, "running" to 0), "processes" to obj("running" to 0), "pendingQuestionId" to null,
            "unread" to null, "orderKey" to "0", "version" to "v-$agentId-1", "createdAt" to time, "updatedAt" to time, "archivedAt" to null,
        )
        agents[agentId] = agent
        agentVersions[agentId] = 1
        val task = obj(
            "id" to taskId, "name" to "New Task", "folderName" to "new_task_$taskId", "ownerUserId" to null, "creatorAgentId" to null,
            "workspaceId" to workspaceId, "compute" to obj("type" to "host", "path" to "/Users/you/Happy/Tasks/new_task_$taskId"),
            "status" to "active", "canArchive" to true, "version" to "v-$taskId-1", "createdAt" to time, "updatedAt" to time, "archivedAt" to null,
            "agent" to obj("id" to agentId),
        )
        tasks += task
        val space = obj(
            "id" to spaceId, "title" to null, "icon" to obj("type" to "stem_cell"),
            "target" to obj("kind" to "task", "id" to taskId, "agentId" to agentId, "workspaceId" to workspaceId),
            "sectionId" to null, "orderKey" to "0", "placement" to if (sectionId == null) "pending" else "settled", "visibility" to "shown",
            "working" to false, "version" to 1, "createdAt" to time, "updatedAt" to time,
        )
        spaces[spaceId] = space
        conversations[agentId] = Conversation(ArrayList(), ArrayList(), JsonNull, obj("value" to null, "updatedAt" to null), JsonArray(emptyList()))
        val mutationId = input.str("mutationId")
        emit("task.created", obj("task" to composedOwner(task), "mutationId" to mutationId))
        emit("agent.created", obj("agent" to compose(agentId), "mutationId" to mutationId))
        emit("space.created", obj("space" to space, "mutationId" to mutationId))
        val happy = spaces.values.firstOrNull { it.obj("target").str("id") == bots.firstOrNull { b -> b.str("systemKey") == "chief_of_staff" }?.str("id") }?.str("id")
        val to = sectionId?.let { SpaceSidebarPosition.After(it) } ?: happy?.let { SpaceSidebarPosition.After(it) } ?: SpaceSidebarPosition.Parent(null)
        apply(listOf(SpaceSidebarOperation.Insert(SpaceSidebarNewNode.space(spaceId), to)), "daemon", mutationId)
        return ok(obj("space" to space, "agent" to compose(agentId)), 201)
    }

    // Conversations

    private fun page(conversation: Conversation, before: String?, limit: Int): JsonObject {
        val end = before?.let { id -> conversation.runs.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: conversation.runs.size
        var start = end
        var count = 0
        while (start > 0 && count < limit) {
            start -= 1
            count += conversation.runs[start].messages.size
        }
        val runs = conversation.runs.subList(start, end).map(::runJson)
        return obj("runs" to JsonArray(runs), "hasMore" to (start > 0), "cursor" to current())
    }

    private fun runJson(run: Run, messages: Boolean = true): JsonObject {
        val meta = obj("id" to run.id, "status" to run.status, "reason" to run.reason, "startedAt" to run.startedAt, "endedAt" to run.endedAt, "usage" to obj(), "costUsd" to null)
        return if (messages) JsonObject(meta + ("messages" to JsonArray(run.messages))) else meta
    }

    private fun send(agentId: String, conversation: Conversation, input: JsonObject): DirectHttpResponse {
        val id = input.str("id") ?: "ms${++ids}"
        (conversation.pending + conversation.runs.flatMap { it.messages }).firstOrNull { it.str("id") == id }?.let { return ok(obj("message" to it, "cursor" to current()), 202) }
        val content = ArrayList<JsonElement>()
        input.str("text")?.takeIf { it.isNotEmpty() }?.let { content += obj("type" to "text", "text" to it) }
        for (block in input.array("content")) if ((block as JsonObject).str("type") == "image") content += block
        val mode = input["mode"] ?: JsonNull
        val message = obj(
            "id" to id, "role" to "user", "status" to "pending", "delivery" to (input.str("delivery") ?: "queue"), "createdAt" to now(),
            "content" to JsonArray(content), "metadata" to obj(), "clientMetadata" to (input["clientMetadata"] ?: obj()), "mode" to mode, "runId" to null,
        )
        conversation.pending += message
        if (mode !is JsonNull) conversation.mode = mode
        emit("message.created", obj("agentId" to agentId, "runId" to null, "message" to message))
        val running = conversation.runs.lastOrNull()?.takeIf { it.status == "running" }
        when {
            running == null -> start(agentId, conversation, null)
            // A run this computer isn't scripting takes the steering at once.
            replies[agentId] == null -> start(agentId, conversation, running)
            // Mid-reply: taken at the reply's end.
        }
        return ok(obj("message" to message, "cursor" to current()), 202)
    }

    /** Accepts what is pending into a new run, after `finishing` when steering ends one, and answers it. */
    private fun start(agentId: String, conversation: Conversation, finishing: Run?) {
        val accepted = conversation.pending.toList()
        conversation.pending.clear()
        val runId = "rn${++ids}"
        val run = Run(runId, "running", null, now(), null, accepted.map { JsonObject(it + ("status" to JsonPrimitive("accepted")) + ("runId" to JsonPrimitive(runId))) }.toMutableList())
        conversation.runs += run
        val acceptedIds = JsonArray(accepted.map { JsonPrimitive(it.str("id")) })
        if (finishing != null) {
            finishing.status = "aborted"; finishing.reason = "steering"; finishing.endedAt = now()
            emit("run.boundary", obj("agentId" to agentId, "finishedRun" to runJson(finishing, false), "startedRun" to runJson(run, false), "acceptedMessageIds" to acceptedIds))
        } else {
            emit("run.started", obj("agentId" to agentId, "run" to runJson(run, false), "acceptedMessageIds" to acceptedIds))
            updateAgent(agentId, obj("status" to "thinking"))
            working(agentId, true)
        }
        replies[agentId] = scope.launch { answer(agentId, conversation, run) }
    }

    private suspend fun answer(agentId: String, conversation: Conversation, run: Run) {
        delay(reply.num("thinkingMs")?.toLong() ?: 900)
        val text = reply.str("text") ?: ""
        val chunk = reply.num("chunk")?.toInt() ?: 6
        val interval = reply.num("intervalMs")?.toLong() ?: 60
        val messageId = lock.withLock { "ms${++ids}" }
        fun message(body: String, status: String) = obj(
            "id" to messageId, "role" to "agent", "status" to status, "createdAt" to run.startedAt + 1, "runId" to run.id,
            "content" to JsonArray(listOf(obj("type" to "text", "text" to body))), "metadata" to obj("providerId" to "claude"),
        )
        lock.withLock {
            run.messages += message("", "streaming")
            emit("message.created", obj("agentId" to agentId, "runId" to run.id, "message" to run.messages.last()))
            updateAgent(agentId, obj("status" to "working"))
        }
        var offset = 0
        while (offset < text.length) {
            delay(interval)
            val piece = text.substring(offset, minOf(text.length, offset + chunk))
            lock.withLock {
                emit("message.delta", obj("agentId" to agentId, "runId" to run.id, "messageId" to messageId, "blockIndex" to 0, "offset" to offset, "append" to piece))
                offset += piece.length
                run.messages[run.messages.size - 1] = message(text.substring(0, offset), "streaming")
            }
        }
        lock.withLock {
            run.messages[run.messages.size - 1] = message(text, "completed")
            emit("message.updated", obj("agentId" to agentId, "runId" to run.id, "message" to run.messages.last()))
            replies.remove(agentId)
            // Steering sent meanwhile starts the next run at this one's end.
            if (conversation.pending.isNotEmpty()) {
                start(agentId, conversation, run)
                return
            }
            finish(agentId, run, "completed", "completed")
            name(agentId, conversation)
        }
    }

    private fun finish(agentId: String, run: Run, status: String, reason: String) {
        run.status = status; run.reason = reason; run.endedAt = now()
        emit("run.finished", obj("agentId" to agentId, "run" to runJson(run, false)))
        updateAgent(agentId, obj("status" to "idle", "unread" to obj("reason" to "turn_finished", "since" to now())))
        working(agentId, false)
    }

    private fun abort(agentId: String, conversation: Conversation) {
        replies.remove(agentId)?.cancel()
        val run = conversation.runs.lastOrNull()?.takeIf { it.status == "running" } ?: return
        run.messages.lastOrNull()?.takeIf { it.str("status") == "streaming" }?.let { last ->
            run.messages[run.messages.size - 1] = JsonObject(last + ("status" to JsonPrimitive("completed")))
            emit("message.updated", obj("agentId" to agentId, "runId" to run.id, "message" to run.messages.last()))
        }
        finish(agentId, run, "aborted", "abort")
    }

    /** New work names itself after its first answer, as the daemon's titling does. */
    private fun name(agentId: String, conversation: Conversation) {
        if (agents[agentId]?.str("title") != null) return
        val first = conversation.runs.firstNotNullOfOrNull { run -> run.messages.firstOrNull { it.str("role") == "user" } } ?: return
        val words = first.array("content").firstNotNullOfOrNull { (it as JsonObject).str("text") }?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.take(4) ?: return
        val title = words.joinToString(" ").trimEnd('.', ',', '!', '?').replaceFirstChar { it.uppercase() }
        updateAgent(agentId, obj("title" to title, "titleStatus" to "ready"))
        val index = tasks.indexOfFirst { it.obj("agent").str("id") == agentId }
        if (index >= 0) {
            val task = tasks[index]
            tasks[index] = JsonObject(task + ("name" to JsonPrimitive(title)) + ("version" to JsonPrimitive("v-${task.str("id")}-${++ids}")))
            emit("task.updated", obj("taskId" to task.str("id"), "previousVersion" to task.str("version"), "version" to tasks[index].str("version"), "changes" to obj("name" to title, "updatedAt" to now())))
        }
        retitle(agentId, title, obj("type" to "library", "slug" to "wip"))
    }

    /** A row's title (and icon) follow its work's, published whole with a greater version. */
    private fun retitle(agentId: String?, title: String, icon: JsonObject? = null) {
        val space = spaces.values.firstOrNull { it.obj("target").str("agentId") == agentId } ?: return
        var next = space + ("title" to JsonPrimitive(title)) + ("version" to JsonPrimitive((space.num("version") ?: 0.0).toInt() + 1)) + ("updatedAt" to JsonPrimitive(now()))
        if (icon != null) next = next + ("icon" to icon) + ("placement" to JsonPrimitive("settled"))
        spaces[space.str("id")!!] = JsonObject(next)
        emit("space.updated", obj("space" to spaces[space.str("id")!!]))
    }

    private fun working(agentId: String, working: Boolean) {
        val space = spaces.values.firstOrNull { it.obj("target").str("agentId") == agentId } ?: return
        val next = JsonObject(space + ("working" to JsonPrimitive(working)) + ("version" to JsonPrimitive((space.num("version") ?: 0.0).toInt() + 1)))
        spaces[space.str("id")!!] = next
        emit("space.updated", obj("space" to next))
    }

    // Agents

    /** Keeps a bot's or task's agent tree flat, by ID; the owner keeps only its agent's ID. */
    private fun flatten(owner: JsonObject): JsonObject {
        fun keep(agent: JsonObject) {
            val id = agent.str("id")!!
            agents[id] = JsonObject(agent - "subtasks")
            agentVersions[id] = 1
            for (child in agent.array("subtasks")) keep(child as JsonObject)
        }
        val agent = owner.obj("agent")
        keep(agent)
        return JsonObject(owner + ("agent" to obj("id" to agent.str("id"))))
    }

    private fun composedOwner(owner: JsonObject): JsonObject = JsonObject(owner + ("agent" to compose(owner.obj("agent").str("id")!!)))

    /** An agent with its active subtasks under it, in sibling order. */
    private fun compose(id: String): JsonObject {
        val agent = agents[id] ?: return obj("id" to id)
        val children = agents.values
            .filter { it.str("parentAgentId") == id && it["subtask"] == JsonPrimitive(true) && (it["archivedAt"] == null || it["archivedAt"] is JsonNull) }
            .sortedBy { it.str("subtaskOrderKey") ?: "~" }
        return JsonObject(agent + ("subtasks" to JsonArray(children.map { compose(it.str("id")!!) })))
    }

    private fun updateAgent(agentId: String, changes: JsonObject) {
        val agent = agents[agentId] ?: return
        val previous = agent.str("version")
        val next = (agentVersions[agentId] ?: 1) + 1
        agentVersions[agentId] = next
        val version = "v-$agentId-$next"
        val stamped = JsonObject(changes + ("updatedAt" to JsonPrimitive(now())))
        agents[agentId] = JsonObject(agent + stamped + ("version" to JsonPrimitive(version)))
        emit("agent.updated", obj("agentId" to agentId, "previousVersion" to previous, "version" to version, "changes" to stamped))
    }

    // The journal

    private fun current(): String = "c%08d".format(cursor)

    private fun emit(type: String, payload: JsonObject) {
        cursor += 1
        val envelope = obj("cursor" to current(), "type" to type, "occurredAt" to now(), "payload" to payload)
        journal += envelope
        if (journal.size > 2000) journal.removeAt(0)
        val bytes = frame(envelope)
        for (stream in streams) stream.trySend(bytes)
    }

    private fun frame(envelope: JsonObject) = "id: ${envelope.str("cursor")}\nevent: ${envelope.str("type")}\ndata: $envelope\n\n".toByteArray()

    /** The journal so far, for a harness's probe. */
    suspend fun events(): List<JsonObject> = lock.withLock { journal.toList() }

    /** The sidebar document as the computer holds it, for a harness's probe. */
    suspend fun sidebarHtml(): String = lock.withLock { SpaceSidebar.format(document) }

    companion object {
        private fun ok(json: JsonElement, status: Int = 200) =
            DirectHttpResponse(status, mapOf("content-type" to "application/json"), json.toString().toByteArray())

        private fun error(status: Int, code: String, message: String) =
            DirectHttpResponse(status, mapOf("content-type" to "application/json"), obj("error" to message, "code" to code).toString().toByteArray())

        /** A file whole, or one byte range of it (`206`). */
        private fun served(data: ByteArray, type: String, headers: Map<String, String>): DirectHttpResponse {
            val base = mapOf("content-type" to type, "accept-ranges" to "bytes")
            val asked = headers.entries.firstOrNull { it.key.equals("range", ignoreCase = true) }?.value
            val spec = asked?.removePrefix("bytes=")?.takeIf { asked.startsWith("bytes=") && !it.contains(',') } ?: return DirectHttpResponse(200, base, data)
            val size = data.size
            val first = spec.substringBefore('-').toIntOrNull() ?: 0
            val last = spec.substringAfter('-').toIntOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            if (first >= size || first > last) return DirectHttpResponse(416, base + ("content-range" to "bytes */$size"))
            return DirectHttpResponse(206, base + ("content-range" to "bytes $first-$last/$size"), data.copyOfRange(first, last + 1))
        }

        internal fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (key, value) -> key to json(value) })

        private fun json(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
            is List<*> -> JsonArray(value.map(::json))
            else -> JsonPrimitive(value.toString())
        }

        /** Every time in the fixture moved by `offset`: numbers under keys ending in `At`, and `since`. */
        private fun shift(value: JsonElement, offset: Long): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { (key, child) ->
                val number = (child as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
                if (number != null && (key.endsWith("At") || key == "since")) JsonPrimitive(number + offset) else shift(child, offset)
            })
            is JsonArray -> JsonArray(value.map { shift(it, offset) })
            else -> value
        }

        private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
        private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    }
}
