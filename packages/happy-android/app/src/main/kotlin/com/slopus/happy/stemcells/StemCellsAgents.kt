package com.slopus.happy.stemcells

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Every conversation the bots and tasks embed, normalized by ID as the API
 * asks ("Nested `subtasks` are a composed snapshot of independently
 * versioned agents"): each agent kept at its newest version, its direct
 * active subtasks by ID, and the trees composed again on read. A port of
 * happy-ios `StemCellsAgents`.
 */
class StemCellsAgents {
    /** Each agent's own object, without `subtasks`. */
    val records = HashMap<String, JsonObject>()
    /** Each parent's direct active subtasks, in order. */
    val children = HashMap<String, MutableList<String>>()

    private fun JsonElement?.string(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun version(value: JsonElement?): String = value.string("version") ?: ""
    private fun active(value: JsonElement): Boolean = (value as? JsonObject)?.get("archivedAt").let { it == null || it == JsonNull }

    /** Takes a full agent object and every subtask it embeds, never letting an older copy replace a newer one. */
    fun absorb(agent: JsonElement) {
        val whole = agent as? JsonObject ?: return
        val id = whole.string("id") ?: return
        val subtasks = whole["subtasks"] as? JsonArray
        val own = JsonObject(whole - "subtasks")
        if (version(records[id]) <= version(agent)) records[id] = own
        if (subtasks != null) {
            // An archived subtask is no longer under its parent, as `update` has it.
            children[id] = subtasks.filter(::active).mapNotNull { it.string("id") }.toMutableList()
            subtasks.forEach(::absorb)
        }
    }

    /** A new agent; a subtask joins the end of its parent's list. */
    fun created(agent: JsonElement) {
        absorb(agent)
        val id = agent.string("id") ?: return
        val parent = agent.string("parentAgentId") ?: return
        val subtask = ((agent as JsonObject)["subtask"] as? JsonPrimitive)?.booleanOrNull == true
        if (!subtask || !active(agent) || records[parent] == null || children[parent]?.contains(id) == true) return
        children.getOrPut(parent) { ArrayList() } += id
    }

    /**
     * One `agent.updated`. False when the change does not follow the copy
     * held, so the lists must be read again.
     */
    fun update(agentId: String, previousVersion: String?, version: String?, changes: Map<String, JsonElement>): Boolean {
        val held = records[agentId] ?: return true
        val heldVersion = held.string("version")
        if (version != null && heldVersion != null && version <= heldVersion) return true
        if (previousVersion != heldVersion) return false
        val rest = LinkedHashMap(changes)
        (rest.remove("subtasks") as? JsonArray)?.let { subtasks ->
            children[agentId] = subtasks.mapNotNull { it.string("id") }.toMutableList()
            subtasks.forEach(::absorb)
        }
        val next = LinkedHashMap<String, JsonElement>(held)
        next.putAll(rest)
        if (version != null) next["version"] = JsonPrimitive(version)
        records[agentId] = JsonObject(next)
        // An archived subtask leaves its parent's list.
        val archived = rest["archivedAt"]
        val parent = JsonObject(next).string("parentAgentId")
        if (archived != null && archived != JsonNull && parent != null) children[parent]?.removeAll { it == agentId }
        return true
    }

    /** The agent with its active subtasks composed under it. */
    fun tree(id: String, depth: Int = 0): JsonObject? {
        val own = records[id] ?: return null
        if (depth >= 32) return null
        return JsonObject(own + ("subtasks" to JsonArray(children[id].orEmpty().mapNotNull { tree(it, depth + 1) })))
    }
}
