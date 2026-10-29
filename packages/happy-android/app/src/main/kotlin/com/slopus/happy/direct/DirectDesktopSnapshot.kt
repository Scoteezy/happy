package com.slopus.happy.direct

import com.slopus.happy.stemcells.Agent
import com.slopus.happy.stemcells.StemCellsRow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `GET /v0/bootstrap/desktop`, shared with desktop (happy-ios
 * `DirectDesktopSnapshot`): the fields the phone draws. Unknown ones stay on
 * the wire and in the kept checkpoint.
 */
data class DirectDesktopSnapshot(
    val projects: List<Project>,
    val workspaces: List<Workspace>,
    val bots: List<Owner>?,
    val tasks: List<Owner>?,
    val taskMemberships: List<String>?,
    val surfaces: List<Surface>?,
    val profileUserId: String?,
    val userSettings: List<DirectUserSetting>?,
    val cursor: String,
) {
    data class Project(val id: String, val name: String, val status: String, val orderKey: String, val agents: List<Agent>)
    data class Workspace(val id: String, val projectId: String?, val parentId: String?, val name: String, val kind: String, val status: String, val orderKey: String, val agents: List<Agent>)
    data class Owner(val id: String, val name: String?, val status: String?, val systemKey: String?, val agent: Agent?, val avatarThumbhash: String?, val hasAvatar: Boolean, val canArchive: Boolean?)
    data class Surface(val id: String, val name: String, val type: String, val members: List<Member>, val chatAgentId: String?) {
        data class Member(val userId: String?, val orderKey: String)
    }

    companion object {
        fun from(json: JsonElement): DirectDesktopSnapshot {
            val o = Wire.obj(json)
            fun agents(value: JsonObject) = Wire.array(value["agents"]).map(Agent::from)
            fun owner(value: JsonElement): Owner {
                val e = Wire.obj(value)
                val avatar = e["avatar"]?.takeIf { it != JsonNull } as? JsonObject
                return Owner(
                    Wire.string(e, "id"), Wire.stringOrNull(e, "name"), Wire.stringOrNull(e, "status"), Wire.stringOrNull(e, "systemKey"),
                    e["agent"]?.takeIf { it != JsonNull }?.let(Agent::from), avatar?.let { Wire.stringOrNull(it, "thumbhash") }, avatar != null,
                    Wire.boolOrNull(e, "canArchive"),
                )
            }
            return DirectDesktopSnapshot(
                projects = Wire.array(o["projects"]).map { val p = Wire.obj(it); Project(Wire.string(p, "id"), Wire.string(p, "name"), Wire.string(p, "status"), Wire.string(p, "orderKey"), agents(p)) },
                workspaces = Wire.array(o["workspaces"]).map {
                    val w = Wire.obj(it)
                    Workspace(Wire.string(w, "id"), Wire.stringOrNull(w, "projectId"), Wire.stringOrNull(w, "parentId"), Wire.string(w, "name"), Wire.string(w, "kind"), Wire.string(w, "status"), Wire.string(w, "orderKey"), agents(w))
                },
                bots = o["bots"]?.takeIf { it != JsonNull }?.let { list -> Wire.array(list).map(::owner) },
                tasks = o["tasks"]?.takeIf { it != JsonNull }?.let { list -> Wire.array(list).map(::owner) },
                taskMemberships = o["taskMemberships"]?.takeIf { it != JsonNull }?.let { list -> Wire.array(list).map { Wire.string(Wire.obj(it), "taskId") } },
                surfaces = o["surfaces"]?.takeIf { it != JsonNull }?.let { list ->
                    Wire.array(list).map {
                        val s = Wire.obj(it)
                        Surface(
                            Wire.string(s, "id"), Wire.string(s, "name"), Wire.string(s, "type"),
                            Wire.array(s["members"]).map { m -> val e = Wire.obj(m); Surface.Member(Wire.stringOrNull(e, "userId"), Wire.string(e, "orderKey")) },
                            Wire.stringOrNull(s, "chatAgentId"),
                        )
                    }
                },
                profileUserId = (o["profile"] as? JsonObject)?.let { Wire.stringOrNull(it, "userId") },
                userSettings = o["userSettings"]?.takeIf { it != JsonNull }?.let { list -> Wire.array(list).mapNotNull(DirectUserSetting::from) },
                cursor = Wire.string(o, "cursor"),
            )
        }
    }
}

/** happy-ios `DirectSettings`. */
object DirectSettings {
    const val PROJECTS_VISIBILITY = "happy-desktop.projectsVisibility"
    const val STEM_CELLS = "happy-desktop.experiments.stemCells"

    fun projectsHidden(values: Map<String, JsonElement>): Boolean =
        (values[PROJECTS_VISIBILITY] as? JsonObject)?.get("projectsHidden") == JsonPrimitive(true)
}

/**
 * Desktop's `happyAgentSidebarTree` and `happyAgentSections`, projected once
 * for home and search (happy-ios `DirectSidebarRows`). A subtask-owned
 * worktree is never listed twice.
 */
object DirectSidebarRows {
    class Labels(val artifacts: String = "Artifacts", val bots: String = "Bots", val tasks: String = "Tasks", val projects: String = "Projects", val untitled: String = "Untitled")

    fun make(desktop: DirectDesktopSnapshot, settings: Map<String, JsonElement>, collapsed: Set<String> = emptySet(), labels: Labels = Labels()): List<StemCellsRow> {
        val rows = ArrayList<StemCellsRow>()
        val descendants = HashSet<String>()
        val tasksById = LinkedHashMap<String, DirectDesktopSnapshot.Owner>()
        for (task in desktop.tasks.orEmpty()) tasksById.putIfAbsent(task.id, task)
        val tasks = desktop.taskMemberships.orEmpty().mapNotNull { tasksById[it] }.filter { it.status != "archived" }
        val bots = desktop.bots.orEmpty().filter { it.status != "archived" }
        fun collect(agent: Agent) {
            for (child in agent.subtasks.orEmpty()) {
                descendants += child.workspaceId
                collect(child)
            }
        }
        for (owner in bots + tasks) owner.agent?.let(::collect)
        fun heading(id: String, title: String) {
            rows += StemCellsRow(id = id, nodeId = id, kind = StemCellsRow.Kind.Section, label = title, collapsed = id in collapsed)
        }
        fun folder(id: String, title: String, depth: Int, kind: String) {
            rows += StemCellsRow(id = id, nodeId = id, kind = StemCellsRow.Kind.Space, depth = depth, label = title, mark = StemCellsRow.Mark.Glyph("folder"), collapsed = id in collapsed, targetKind = kind)
        }
        fun status(agent: Agent): StemCellsRow.Status = when {
            agent.pendingQuestionId != null -> StemCellsRow.Status.AwaitingInput
            agent.wait != null -> StemCellsRow.Status.Waiting
            agent.isWorking -> StemCellsRow.Status.Working
            else -> StemCellsRow.Status.None
        }
        fun agentRow(agent: Agent, depth: Int, label: String? = null, owner: DirectDesktopSnapshot.Owner? = null, kind: String? = null, muted: Boolean = false) {
            if (agent.archivedAt != null) return
            val id = owner?.let { "${kind ?: "agent"}:${it.id}" } ?: "session:${agent.id}"
            val children = agent.subtasks.orEmpty().filter { it.archivedAt == null }
            val happy = owner?.systemKey == "chief_of_staff"
            var mark: StemCellsRow.Mark = if (happy) StemCellsRow.Mark.Happy else StemCellsRow.Mark.Glyph(if (kind == "bot") "agents" else "tasks")
            if (kind == "bot" && owner != null && owner.hasAvatar) mark = StemCellsRow.Mark.Image("/v0/bots/${DirectAgentClient.escape(owner.id)}/avatar", owner.avatarThumbhash)
            rows += StemCellsRow(
                id = id, nodeId = id, kind = if (owner == null) StemCellsRow.Kind.Session else StemCellsRow.Kind.Space, depth = depth,
                label = label ?: agent.title ?: labels.untitled, mark = mark,
                status = if (muted) StemCellsRow.Status.None else status(agent), unread = !muted && agent.unread != null,
                collapsed = if (children.isEmpty()) null else id in collapsed, isHappy = happy,
                agentId = agent.id, workspaceId = agent.workspaceId, targetKind = kind, targetId = owner?.id, canArchive = owner?.canArchive ?: false,
            )
            for (child in children) agentRow(child, depth + 1, muted = muted)
        }
        rows += StemCellsRow(id = "direct:artifacts", nodeId = "direct:artifacts", kind = StemCellsRow.Kind.Space, label = labels.artifacts, mark = StemCellsRow.Mark.Glyph("doc"), targetKind = "artifacts")
        val joined = desktop.surfaces.orEmpty().mapNotNull { surface ->
            val member = surface.members.firstOrNull { it.userId == desktop.profileUserId } ?: return@mapNotNull null
            surface to member.orderKey
        }.sortedWith(compareBy({ it.second }, { it.first.id }))
        for ((surface, _) in joined) {
            rows += StemCellsRow(
                id = "surface:${surface.id}", nodeId = "surface:${surface.id}", kind = StemCellsRow.Kind.Space, label = surface.name,
                mark = StemCellsRow.Mark.Glyph(if (surface.type == "calendar") "calendar" else "doc"), agentId = surface.chatAgentId,
                targetKind = "surface", targetId = surface.id, surfaceType = surface.type,
            )
        }
        heading("direct:bots", labels.bots)
        for (bot in bots) {
            val agent = bot.agent ?: continue
            val attention = settings["happy-desktop.bot.${bot.id}"] as? JsonObject
            if (attention?.get("hidden") == JsonPrimitive(true)) continue
            agentRow(agent, 0, bot.name, bot, "bot", muted = attention?.get("muted") == JsonPrimitive(true))
        }
        if (desktop.tasks != null) {
            heading("direct:tasks", labels.tasks)
            for (task in tasks) {
                val agent = task.agent ?: continue
                agentRow(agent, 0, task.name, task, "task")
            }
        }
        if (!DirectSettings.projectsHidden(settings)) {
            heading("direct:projects", labels.projects)
            val projects = desktop.projects.filter { it.status != "archived" }.sortedWith(compareBy({ it.orderKey }, { it.id }))
            for (project in projects) {
                folder("project:${project.id}", project.name, 0, "project")
                for (agent in project.agents) if (agent.parentAgentId == null) agentRow(agent, 1)
                val workspaces = desktop.workspaces.filter {
                    it.projectId == project.id && it.id != project.id && it.status != "archived" && it.status != "archiving" && it.id !in descendants
                }.sortedWith(compareBy({ it.orderKey }, { it.id }))
                for (workspace in workspaces) {
                    folder("workspace:${workspace.id}", workspace.name, 1, "workspace")
                    for (agent in workspace.agents) if (agent.parentAgentId == null) agentRow(agent, 2)
                }
            }
        }
        return rows
    }
}
