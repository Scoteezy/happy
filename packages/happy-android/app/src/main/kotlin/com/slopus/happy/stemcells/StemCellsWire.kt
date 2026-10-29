package com.slopus.happy.stemcells

import com.slopus.happy.direct.Wire
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The Happy Agent API shapes Stem Cells reads (happy-agent `API.md`:
 * Spaces, Agents, Messages and runs, Message content, Presentation),
 * ported field for field from happy-ios `StemCellsWire.swift` (`AgentWire`).
 * Only the fields the screens use are read; unknown fields are ignored and
 * unknown kinds read as `Unknown`, as the API asks of clients. A required
 * field that is missing throws, as a Codable decode does.
 */

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun <T> JsonObject.listOrNull(key: String, read: (JsonElement) -> T): List<T>? =
    when (val v = this[key]) { null, JsonNull -> null; else -> Wire.array(v).map(read) }

sealed class SpaceIcon {
    data object StemCell : SpaceIcon()
    data class Library(val slug: String) : SpaceIcon()
    /** A daemon-relative path read with the same bearer token. */
    data class Image(val url: String) : SpaceIcon()
    data object Unknown : SpaceIcon()

    fun json(): JsonObject = when (this) {
        StemCell -> Wire.objectOf("type" to JsonPrimitive("stem_cell"))
        is Library -> Wire.objectOf("type" to JsonPrimitive("library"), "slug" to JsonPrimitive(slug))
        is Image -> Wire.objectOf("type" to JsonPrimitive("image"), "url" to JsonPrimitive(url))
        Unknown -> Wire.objectOf("type" to JsonPrimitive("unknown"))
    }

    companion object {
        fun from(json: JsonElement): SpaceIcon {
            val o = Wire.obj(json)
            return when (Wire.string(o, "type")) {
                "stem_cell" -> StemCell
                "library" -> Library(Wire.string(o, "slug"))
                "image" -> Image(Wire.string(o, "url"))
                else -> Unknown
            }
        }
    }
}

data class SpaceTarget(val kind: String, val id: String, val agentId: String?, val workspaceId: String?) {
    fun json(): JsonObject = Wire.objectOf("kind" to JsonPrimitive(kind), "id" to JsonPrimitive(id), "agentId" to agentId?.let(::JsonPrimitive), "workspaceId" to workspaceId?.let(::JsonPrimitive))

    companion object {
        fun from(json: JsonElement): SpaceTarget {
            val o = Wire.obj(json)
            return SpaceTarget(Wire.string(o, "kind"), Wire.string(o, "id"), Wire.stringOrNull(o, "agentId"), Wire.stringOrNull(o, "workspaceId"))
        }
    }
}

data class Space(
    val id: String,
    val title: String?,
    val icon: SpaceIcon?,
    val target: SpaceTarget,
    val sectionId: String? = null,
    val orderKey: String = "",
    val placement: String = "pending",
    val visibility: String = "shown",
    val working: Boolean = false,
    val version: Int,
    val createdAt: Double,
    val updatedAt: Double,
) {
    fun json(): JsonObject = Wire.objectOf(
        "id" to JsonPrimitive(id), "title" to title?.let(::JsonPrimitive), "icon" to icon?.json(), "target" to target.json(),
        "sectionId" to sectionId?.let(::JsonPrimitive), "orderKey" to JsonPrimitive(orderKey), "placement" to JsonPrimitive(placement),
        "visibility" to JsonPrimitive(visibility), "working" to JsonPrimitive(working), "version" to JsonPrimitive(version),
        "createdAt" to Wire.number(createdAt), "updatedAt" to Wire.number(updatedAt),
    )

    companion object {
        fun from(json: JsonElement): Space {
            val o = Wire.obj(json)
            return Space(
                id = Wire.string(o, "id"), title = Wire.stringOrNull(o, "title"),
                icon = o["icon"]?.takeIf { it != JsonNull }?.let(SpaceIcon::from), target = SpaceTarget.from(o["target"] ?: JsonNull),
                sectionId = Wire.stringOrNull(o, "sectionId"), orderKey = Wire.string(o, "orderKey"), placement = Wire.string(o, "placement"),
                visibility = Wire.string(o, "visibility"), working = Wire.bool(o, "working"), version = Wire.int(o, "version"),
                createdAt = Wire.double(o, "createdAt"), updatedAt = Wire.double(o, "updatedAt"),
            )
        }
    }
}

data class SidebarProposal(
    val artifactId: String,
    val version: Int,
    val baseVersion: Int,
    val status: String,
    val createdAt: Double,
    val decidedAt: Double?,
    val acceptedVersion: Int?,
) {
    companion object {
        fun from(json: JsonElement): SidebarProposal {
            val o = Wire.obj(json)
            return SidebarProposal(
                Wire.string(o, "artifactId"), Wire.int(o, "version"), Wire.int(o, "baseVersion"), Wire.string(o, "status"),
                Wire.double(o, "createdAt"), Wire.doubleOrNull(o, "decidedAt"), Wire.intOrNull(o, "acceptedVersion"),
            )
        }
    }
}

/** `GET /v0/spaces/sidebar/proposals/:artifactId`: the proposal and its latest document, in the sidebar's grammar. */
data class SidebarProposalResponse(val proposal: SidebarProposal, val html: String) {
    companion object {
        fun from(json: JsonElement): SidebarProposalResponse {
            val o = Wire.obj(json)
            return SidebarProposalResponse(SidebarProposal.from(o["proposal"] ?: JsonNull), Wire.string(o, "html"))
        }
    }
}

data class SidebarResponse(
    val artifactId: String,
    val version: Int,
    val html: String,
    val spaces: List<Space>,
    /** Library icons by agent, keyed by agent ID: each a slug. */
    val agentIcons: Map<String, String>?,
    val seed: Boolean?,
    val proposal: SidebarProposal?,
) {
    companion object {
        fun from(json: JsonElement): SidebarResponse {
            val o = Wire.obj(json)
            return SidebarResponse(
                Wire.string(o, "artifactId"), Wire.int(o, "version"), Wire.string(o, "html"), Wire.list(o, "spaces", Space::from),
                o.obj("agentIcons")?.mapValues { Wire.string(Wire.obj(it.value), "slug") },
                Wire.boolOrNull(o, "seed"), o["proposal"]?.takeIf { it != JsonNull }?.let(SidebarProposal::from),
            )
        }
    }
}

/** happy-ios `AgentWire.Health.Capabilities`, beyond Stories'. */
data class AgentCapabilities(
    val stemCellIdentities: Boolean?,
    val sidebarOperations: Boolean?,
    val sidebarProposals: Boolean?,
    val sidebarAttachments: Boolean?,
) {
    companion object {
        fun from(json: JsonElement?): AgentCapabilities? {
            val o = json as? JsonObject ?: return null
            return AgentCapabilities(
                Wire.boolOrNull(o, "stemCellIdentities"), Wire.boolOrNull(o, "sidebarOperations"),
                Wire.boolOrNull(o, "sidebarProposals"), Wire.boolOrNull(o, "sidebarAttachments"),
            )
        }
    }
}

data class AgentUnread(val reason: String?, val since: Double?) {
    companion object {
        fun from(json: JsonElement): AgentUnread {
            val o = Wire.obj(json)
            return AgentUnread(Wire.stringOrNull(o, "reason"), Wire.doubleOrNull(o, "since"))
        }
    }
}

data class AgentWait(val startedAt: Double, val dueAt: Double) {
    companion object {
        fun from(json: JsonElement): AgentWait {
            val o = Wire.obj(json)
            return AgentWait(Wire.double(o, "startedAt"), Wire.double(o, "dueAt"))
        }
    }
}

/** The automatic recovery inside the current run (protocol 30+). */
data class AgentRetrying(val attempt: Int, val maxAttempts: Int, val kind: String, val vendorName: String) {
    companion object {
        fun from(json: JsonElement): AgentRetrying {
            val o = Wire.obj(json)
            return AgentRetrying(Wire.int(o, "attempt"), Wire.int(o, "maxAttempts"), Wire.string(o, "kind"), Wire.string(o, "vendorName"))
        }
    }
}

data class AgentSubagents(val total: Int?, val running: Int?) {
    companion object {
        fun from(json: JsonElement): AgentSubagents {
            val o = Wire.obj(json)
            return AgentSubagents(Wire.intOrNull(o, "total"), Wire.intOrNull(o, "running"))
        }
    }
}

data class Agent(
    val id: String,
    val workspaceId: String,
    val parentAgentId: String? = null,
    val title: String? = null,
    val status: String = "idle",
    val wait: AgentWait? = null,
    val subtasks: List<Agent>? = null,
    val pendingQuestionId: String? = null,
    val unread: AgentUnread? = null,
    val canSendMessages: Boolean? = null,
    val archivedAt: Double? = null,
    val version: String? = null,
    val retrying: AgentRetrying? = null,
    val subagents: AgentSubagents? = null,
) {
    val isWorking: Boolean get() = status != "idle"

    companion object {
        fun from(json: JsonElement): Agent {
            val o = Wire.obj(json)
            return Agent(
                id = Wire.string(o, "id"), workspaceId = Wire.string(o, "workspaceId"), parentAgentId = Wire.stringOrNull(o, "parentAgentId"),
                title = Wire.stringOrNull(o, "title"), status = Wire.string(o, "status"),
                wait = o["wait"]?.takeIf { it != JsonNull }?.let(AgentWait::from), subtasks = o.listOrNull("subtasks", ::from),
                pendingQuestionId = Wire.stringOrNull(o, "pendingQuestionId"), unread = o["unread"]?.takeIf { it != JsonNull }?.let(AgentUnread::from),
                canSendMessages = Wire.boolOrNull(o, "canSendMessages"), archivedAt = Wire.doubleOrNull(o, "archivedAt"),
                version = Wire.stringOrNull(o, "version"), retrying = o["retrying"]?.takeIf { it != JsonNull }?.let(AgentRetrying::from),
                subagents = o["subagents"]?.takeIf { it != JsonNull }?.let(AgentSubagents::from),
            )
        }
    }
}

data class Bot(
    val id: String,
    val name: String,
    val systemKey: String?,
    val status: String?,
    val thumbhash: String?,
    val version: String?,
    val agent: Agent?,
    /** Whether it has an `avatar`, with or without a thumbhash. */
    val hasAvatar: Boolean = thumbhash != null,
) {
    companion object {
        fun from(json: JsonElement): Bot {
            val o = Wire.obj(json)
            return Bot(
                Wire.string(o, "id"), Wire.string(o, "name"), Wire.stringOrNull(o, "systemKey"), Wire.stringOrNull(o, "status"),
                o.obj("avatar")?.let { Wire.stringOrNull(it, "thumbhash") }, Wire.stringOrNull(o, "version"),
                o["agent"]?.takeIf { it != JsonNull }?.let(Agent::from), o.obj("avatar") != null,
            )
        }
    }
}

data class AgentTask(val id: String, val name: String?, val canArchive: Boolean?, val version: String?, val agent: Agent?) {
    companion object {
        fun from(json: JsonElement): AgentTask {
            val o = Wire.obj(json)
            return AgentTask(
                Wire.string(o, "id"), Wire.stringOrNull(o, "name"), Wire.boolOrNull(o, "canArchive"), Wire.stringOrNull(o, "version"),
                o["agent"]?.takeIf { it != JsonNull }?.let(Agent::from),
            )
        }
    }
}

/** A Surface: a todo list, document, calendar, system monitor or chat. */
data class Surface(val id: String, val type: String, val name: String, val version: String? = null) {
    companion object {
        fun from(json: JsonElement): Surface {
            val o = Wire.obj(json)
            return Surface(Wire.string(o, "id"), Wire.string(o, "type"), Wire.string(o, "name"), Wire.stringOrNull(o, "version"))
        }
    }
}

data class Todo(val id: String, val text: String, /** `active`, `completed` or `deleted`. */ val status: String) {
    companion object {
        fun from(json: JsonElement): Todo {
            val o = Wire.obj(json)
            return Todo(Wire.string(o, "id"), Wire.string(o, "text"), Wire.string(o, "status"))
        }
    }
}

data class CreateStemCellResponse(val space: Space, val agent: Agent) {
    companion object {
        fun from(json: JsonElement): CreateStemCellResponse {
            val o = Wire.obj(json)
            return CreateStemCellResponse(Space.from(o["space"] ?: JsonNull), Agent.from(o["agent"] ?: JsonNull))
        }
    }
}

data class AgentMode(val providerId: String, val modelId: String, val effort: String, val serviceTier: String?, val permissionMode: String) {
    // `serviceTier` is required on the wire, `null` included.
    fun json(): JsonObject = JsonObject(mapOf(
        "providerId" to JsonPrimitive(providerId), "modelId" to JsonPrimitive(modelId), "effort" to JsonPrimitive(effort),
        "serviceTier" to Wire.string(serviceTier), "permissionMode" to JsonPrimitive(permissionMode),
    ))

    companion object {
        fun from(json: JsonElement): AgentMode {
            val o = Wire.obj(json)
            return AgentMode(Wire.string(o, "providerId"), Wire.string(o, "modelId"), Wire.string(o, "effort"), Wire.stringOrNull(o, "serviceTier"), Wire.string(o, "permissionMode"))
        }
    }
}

// Messages

data class MediaItem(
    val kind: String,
    val name: String,
    val mimeType: String,
    val size: Double?,
    val width: Double?,
    val height: Double?,
    val thumbhash: String?,
    val scale: Double?,
    val source: Source?,
) {
    data class Source(val path: String, val absolutePath: String)

    fun json(): JsonObject = Wire.objectOf(
        "kind" to JsonPrimitive(kind), "name" to JsonPrimitive(name), "mimeType" to JsonPrimitive(mimeType),
        "size" to size?.let(Wire::number), "width" to width?.let(Wire::number), "height" to height?.let(Wire::number),
        "thumbhash" to thumbhash?.let(::JsonPrimitive), "scale" to scale?.let(Wire::number),
        "source" to source?.let { Wire.objectOf("path" to JsonPrimitive(it.path), "absolutePath" to JsonPrimitive(it.absolutePath)) },
    )

    companion object {
        fun from(json: JsonElement): MediaItem {
            val o = Wire.obj(json)
            return MediaItem(
                Wire.string(o, "kind"), Wire.string(o, "name"), Wire.string(o, "mimeType"), Wire.doubleOrNull(o, "size"),
                Wire.doubleOrNull(o, "width"), Wire.doubleOrNull(o, "height"), Wire.stringOrNull(o, "thumbhash"), Wire.doubleOrNull(o, "scale"),
                o.obj("source")?.let { Source(Wire.string(it, "path"), Wire.string(it, "absolutePath")) },
            )
        }
    }
}

data class Presentation(
    val type: String,
    val items: List<MediaItem>? = null,
    /** `columns` or a `tree`, kept as sent; `MediaLayout` reads it. */
    val layout: JsonElement? = null,
    val artifactId: String? = null,
    val artifactType: String? = null,
    val title: String? = null,
    /** An artifact presentation's version, and which it shows: `latest` follows the artifact; anything else is that version. */
    val version: Int? = null,
    val shows: String? = null,
) {
    fun json(): JsonObject = Wire.objectOf(
        "type" to JsonPrimitive(type), "items" to items?.let { kotlinx.serialization.json.JsonArray(it.map(MediaItem::json)) },
        "layout" to layout?.takeIf { it != JsonNull }, "artifactId" to artifactId?.let(::JsonPrimitive),
        "artifactType" to artifactType?.let(::JsonPrimitive), "title" to title?.let(::JsonPrimitive),
        "version" to version?.let(::JsonPrimitive), "shows" to shows?.let(::JsonPrimitive),
    )

    companion object {
        fun from(json: JsonElement): Presentation {
            val o = Wire.obj(json)
            return Presentation(
                Wire.string(o, "type"), o.listOrNull("items", MediaItem::from), o["layout"]?.takeIf { it != JsonNull },
                Wire.stringOrNull(o, "artifactId"), Wire.stringOrNull(o, "artifactType"), Wire.stringOrNull(o, "title"),
                Wire.intOrNull(o, "version"), Wire.stringOrNull(o, "shows"),
            )
        }
    }
}

sealed class Block {
    data class Text(val text: String) : Block()
    data class Reasoning(val text: String) : Block()
    data class Image(val mimeType: String, val data: String) : Block()
    /** A tool call, kept only for what it showed the person. */
    data class ToolCall(val id: String, val name: String, val status: String, val presentation: Presentation?) : Block()
    data class Unknown(val type: String) : Block()

    fun json(): JsonObject = when (this) {
        is Text -> Wire.objectOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))
        is Reasoning -> Wire.objectOf("type" to JsonPrimitive("reasoning"), "text" to JsonPrimitive(text))
        is Image -> Wire.objectOf("type" to JsonPrimitive("image"), "mimeType" to JsonPrimitive(mimeType), "data" to JsonPrimitive(data))
        is ToolCall -> Wire.objectOf(
            "type" to JsonPrimitive("tool_call"), "id" to JsonPrimitive(id), "name" to JsonPrimitive(name),
            "status" to JsonPrimitive(status), "presentation" to presentation?.json(),
        )
        is Unknown -> Wire.objectOf("type" to JsonPrimitive(type))
    }

    companion object {
        fun from(json: JsonElement): Block {
            val o = Wire.obj(json)
            return when (val type = Wire.string(o, "type")) {
                "text" -> Text(Wire.string(o, "text"))
                "reasoning" -> Reasoning(Wire.stringOrNull(o, "text") ?: "")
                // History may leave a large picture's bytes out; it is then not shown.
                "image" -> {
                    val mimeType = runCatching { Wire.string(o, "mimeType") }.getOrNull()
                    val data = runCatching { Wire.string(o, "data") }.getOrNull()
                    if (mimeType != null && data != null) Image(mimeType, data) else Unknown(type)
                }
                "tool_call" -> ToolCall(
                    Wire.string(o, "id"), Wire.stringOrNull(o, "name") ?: "", Wire.stringOrNull(o, "status") ?: "running",
                    runCatching { o["presentation"]?.takeIf { it != JsonNull }?.let(Presentation::from) }.getOrNull(),
                )
                else -> Unknown(type)
            }
        }
    }
}

/**
 * A message's public provenance (`metadata`): the agent that sent a
 * message the daemon wrote on its behalf, and that agent's one line on
 * what it is.
 */
data class MessageMetadata(val senderAgentId: String? = null, val senderAgentExplanation: String? = null) {
    fun json(): JsonObject = Wire.objectOf("senderAgentId" to senderAgentId?.let(::JsonPrimitive), "senderAgentExplanation" to senderAgentExplanation?.let(::JsonPrimitive))

    companion object {
        fun from(json: JsonElement): MessageMetadata {
            val o = Wire.obj(json)
            return MessageMetadata(Wire.stringOrNull(o, "senderAgentId"), Wire.stringOrNull(o, "senderAgentExplanation"))
        }
    }
}

data class AgentMessage(
    val id: String,
    val role: String,
    val createdAt: Double,
    val content: List<Block>,
    val status: String? = null,
    val runId: String? = null,
    val metadata: MessageMetadata? = null,
) {
    fun json(): JsonObject = Wire.objectOf(
        "id" to JsonPrimitive(id), "role" to JsonPrimitive(role), "createdAt" to Wire.number(createdAt),
        "content" to kotlinx.serialization.json.JsonArray(content.map(Block::json)), "status" to status?.let(::JsonPrimitive),
        "runId" to runId?.let(::JsonPrimitive), "metadata" to metadata?.json(),
    )

    companion object {
        fun from(json: JsonElement): AgentMessage {
            val o = Wire.obj(json)
            return AgentMessage(
                Wire.string(o, "id"), Wire.string(o, "role"), Wire.double(o, "createdAt"), Wire.list(o, "content", Block::from),
                Wire.stringOrNull(o, "status"), Wire.stringOrNull(o, "runId"), o["metadata"]?.takeIf { it != JsonNull }?.let(MessageMetadata::from),
            )
        }
    }
}

data class AgentRun(
    val id: String,
    val status: String,
    /** Unix milliseconds; the turn's clock counts from here. */
    val startedAt: Double?,
    val messages: List<AgentMessage>?,
) {
    companion object {
        fun from(json: JsonElement): AgentRun {
            val o = Wire.obj(json)
            return AgentRun(Wire.string(o, "id"), Wire.string(o, "status"), Wire.doubleOrNull(o, "startedAt"), o.listOrNull("messages", AgentMessage::from))
        }
    }
}

data class MessagesResponse(val runs: List<AgentRun>, val hasMore: Boolean, val cursor: String?) {
    companion object {
        fun from(json: JsonElement): MessagesResponse {
            val o = Wire.obj(json)
            return MessagesResponse(Wire.list(o, "runs", AgentRun::from), Wire.bool(o, "hasMore"), Wire.stringOrNull(o, "cursor"))
        }
    }
}

/** One version of an artifact (`GET /v0/artifacts/:id/versions/:number`). */
data class ArtifactVersion(val artifactId: String, val number: Int, val title: String, val entry: File?, val files: List<File>, val liveUrl: String?) {
    data class File(val path: String, val mimeType: String, val size: Double) {
        companion object {
            fun from(json: JsonElement): File {
                val o = Wire.obj(json)
                return File(Wire.string(o, "path"), Wire.string(o, "mimeType"), Wire.double(o, "size"))
            }
        }
    }

    companion object {
        fun from(json: JsonElement): ArtifactVersion {
            val o = Wire.obj(json)
            return ArtifactVersion(
                Wire.string(o, "artifactId"), Wire.int(o, "number"), Wire.string(o, "title"),
                o["entry"]?.takeIf { it != JsonNull }?.let(File::from), Wire.list(o, "files", File::from),
                o.obj("live")?.let { Wire.string(it, "url") },
            )
        }
    }
}

/**
 * The composer's draft on the computer (`GET/PUT /v0/agents/:id/draft`):
 * the text with the mode it goes with, and when it was last touched, kept
 * even when cleared so an older device can't bring it back. On a team
 * server it is the signed-in person's own.
 */
data class AgentDraft(val value: DraftValue?, val updatedAt: Double?) {
    fun json(): JsonObject = Wire.objectOf("value" to (value?.json() ?: JsonNull), "updatedAt" to Wire.numberOrNull(updatedAt))

    companion object {
        fun from(json: JsonElement): AgentDraft {
            val o = Wire.obj(json)
            return AgentDraft(o["value"]?.takeIf { it != JsonNull }?.let(DraftValue::from), Wire.doubleOrNull(o, "updatedAt"))
        }
    }
}

data class DraftValue(val text: String, val mode: AgentMode) {
    fun json(): JsonObject = JsonObject(mapOf("text" to JsonPrimitive(text)) + mode.json())

    companion object {
        fun from(json: JsonElement): DraftValue = DraftValue(Wire.string(Wire.obj(json), "text"), AgentMode.from(json))
    }
}

data class Bootstrap(
    val agent: Agent,
    val mode: AgentMode?,
    /** This person's draft; absent from an older computer. */
    val draft: AgentDraft?,
    val pending: List<AgentMessage>?,
    val cursor: String?,
    /** Every child the agent spawned, hidden collaborators included. */
    val subagents: List<Agent>?,
) {
    companion object {
        fun from(json: JsonElement): Bootstrap {
            val o = Wire.obj(json)
            return Bootstrap(
                Agent.from(o["agent"] ?: JsonNull), o["mode"]?.takeIf { it != JsonNull }?.let(AgentMode::from),
                o["draft"]?.takeIf { it != JsonNull }?.let(AgentDraft::from), o.listOrNull("pending", AgentMessage::from),
                Wire.stringOrNull(o, "cursor"), o.listOrNull("subagents", Agent::from),
            )
        }
    }
}
