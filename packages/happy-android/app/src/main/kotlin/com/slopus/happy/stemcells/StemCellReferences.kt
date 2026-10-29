package com.slopus.happy.stemcells

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * What each Happy reference in a message reads as now, and what it opens:
 * happy-desktop's `messageReferencesCreate` (`messageReferences.ts`), via
 * happy-ios `StemCellReferences`, over the bots and tasks this phone holds.
 * An agent reads as its conversation's name (a bot's or task's own as the
 * bot's or task's), a workspace as the bot, task or subtask it holds, an
 * artifact as its current title. What the lists don't hold (archived, a
 * project, a service, a kind this build doesn't know) is inert words that
 * say what it was.
 */
data class StemCellReferences(
    val agents: Map<String, Entity> = emptyMap(),
    val workspaces: Map<String, Entity> = emptyMap(),
    val artifacts: Map<String, Artifact> = emptyMap(),
    /**
     * The conversation's own subagents, by ID, as titled ("" untitled): a
     * hidden one reads as its title, and opens nothing.
     */
    val subagents: Map<String, String> = emptyMap(),
) {
    /** Something a reference can open: a conversation. */
    data class Entity(val label: String, val agentId: String)

    /** An artifact's title as read: absent until read. */
    sealed class Artifact {
        data class Active(val title: String, val type: String, val latestVersion: Int) : Artifact()
        data class Deleted(val title: String) : Artifact()
        data object Missing : Artifact()
    }

    sealed class View {
        abstract val label: String

        /** Opens: a name to tap. `reading` while an artifact's title is on its way. */
        data class Link(override val label: String, val reading: Boolean = false) : View()

        /** Opens nothing: plain words. */
        data class Text(override val label: String) : View()
    }

    /** The artifacts `text` names whose titles haven't been read. */
    fun unread(text: String): List<String> {
        if ("<happy-artifact" !in text) return emptyList()
        val ids = ArrayList<String>()
        for (segment in HappyReferences.parse(text)) {
            val reference = (segment as? HappyReferences.Segment.Ref)?.reference ?: continue
            if (reference.kind == "artifact" && reference.id !in artifacts && reference.id !in ids) ids += reference.id
        }
        return ids
    }

    /** What `reference` reads as now. */
    fun view(reference: HappyReferences.Reference): View {
        val inert = View.Text(HappyReferences.placeholder(reference))
        return when (reference.kind) {
            "artifact" -> when (val artifact = artifacts[reference.id]) {
                null -> View.Link("artifact", reading = true)
                Artifact.Missing -> inert
                // A deleted artifact serves nothing, so it is named and not offered.
                is Artifact.Deleted -> artifact.title.trim().let { if (it.isEmpty()) inert else View.Text(it) }
                is Artifact.Active -> View.Link(artifact.title.trim().ifEmpty { "Untitled artifact" })
            }
            "agent", "workspace" -> {
                val entity = (if (reference.kind == "agent") agents else workspaces)[reference.id]
                if (entity == null) {
                    // desktop's `messageReferencesCreate`: a subagent is named, as "Subagent" untitled.
                    val title = if (reference.kind == "agent") subagents[reference.id] else null
                    title ?: return inert
                    View.Text(title.trim().ifEmpty { "Subagent" })
                } else {
                    View.Link(entity.label.trim().ifEmpty { "Untitled ${reference.kind}" })
                }
            }
            // Projects aren't on this phone; services open nowhere yet.
            else -> inert
        }
    }

    /** The conversation a reference opens, for an agent or a workspace. */
    fun entity(reference: HappyReferences.Reference): Entity? = when (reference.kind) {
        "agent" -> agents[reference.id]
        "workspace" -> workspaces[reference.id]
        else -> null
    }

    companion object {
        private fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)
        private val JsonElement?.string: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        /**
         * `messageReferenceEntities`, from the bots and tasks with their
         * conversations composed (each `agent` with its active `subtasks`).
         */
        fun entities(owners: List<JsonElement>): StemCellReferences {
            val agents = LinkedHashMap<String, Entity>()
            val workspaces = LinkedHashMap<String, Entity>()
            val subtasks = ArrayList<JsonElement>()
            fun collect(agent: JsonElement) {
                for (subtask in agent.field("subtasks") as? JsonArray ?: return) {
                    subtasks += subtask
                    collect(subtask)
                }
            }
            for (owner in owners) {
                val agent = owner.field("agent") ?: continue
                val agentId = agent.field("id").string ?: continue
                val name = owner.field("name").string ?: ""
                agents[agentId] = Entity(name, agentId)
                (owner.field("workspaceId").string ?: agent.field("workspaceId").string)?.let { workspaces[it] = Entity(name, agentId) }
                collect(agent)
            }
            for (subtask in subtasks) {
                val agentId = subtask.field("id").string ?: continue
                val title = subtask.field("title").string ?: ""
                agents[agentId] = Entity(title, agentId)
                subtask.field("workspaceId").string?.let { if (it !in workspaces) workspaces[it] = Entity(title, agentId) }
            }
            return StemCellReferences(agents, workspaces)
        }

        /** An artifact object (`GET /v0/artifacts/:id`) as a reference reads it. */
        fun artifact(json: JsonElement): Artifact {
            val title = json.field("title").string ?: ""
            if (json.field("status").string == "deleted") return Artifact.Deleted(title)
            val latest = (json.field("latestVersion") as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: 1.0
            return Artifact.Active(title, json.field("type").string ?: "", latest.toInt())
        }
    }
}
