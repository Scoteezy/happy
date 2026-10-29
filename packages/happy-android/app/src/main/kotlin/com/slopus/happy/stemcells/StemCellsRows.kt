package com.slopus.happy.stemcells

import com.slopus.happy.stemcells.SpaceSidebarNode.Kind as NodeKind

/**
 * What the sidebar's rows are drawn from beside the document: the bots and
 * tasks whose conversations the rows open (`HappyAgentSpacesPlaces`, via
 * happy-ios `StemCellsPlaces`).
 */
data class StemCellsPlaces(
    val bots: List<Bot> = emptyList(),
    val tasks: List<AgentTask> = emptyList(),
    val surfaces: List<Surface> = emptyList(),
) {
    fun bot(id: String): Bot? = bots.firstOrNull { it.id == id }
    fun task(id: String): AgentTask? = tasks.firstOrNull { it.id == id }
    fun surface(id: String): Surface? = surfaces.firstOrNull { it.id == id }

    /** The conversation a space's row stands for. */
    fun conversation(space: Space): Agent? = when (space.target.kind) {
        "task" -> task(space.target.id)?.agent
        "bot" -> bot(space.target.id)?.agent
        "agent" -> space.target.agentId?.let(::agent)
        else -> null
    }

    /** Any conversation in a task's or bot's subtask tree, at any depth. */
    fun agent(id: String): Agent? {
        fun find(agents: List<Agent>): Agent? {
            for (agent in agents) {
                if (agent.id == id) return agent
                find(agent.subtasks.orEmpty())?.let { return it }
            }
            return null
        }
        return find(tasks.mapNotNull { it.agent } + bots.mapNotNull { it.agent })
    }

    fun isHappy(space: Space): Boolean = space.target.kind == "bot" && bot(space.target.id)?.systemKey == HAPPY_BOT_KEY

    companion object {
        /** The system key of Happy's own bot. */
        const val HAPPY_BOT_KEY = "chief_of_staff"
    }
}

/**
 * One drawn row of the Stem Cells sidebar, as `happyAgentSpacesItems` makes
 * it: everything a cell renders, and nothing it does not, so two equal rows
 * draw the same.
 */
data class StemCellsRow(
    /** Unique among the rows: `space:<id>`, `session:<agentId>`, or the node ID. */
    val id: String,
    /** The document node this row stands for. */
    val nodeId: String,
    val kind: Kind,
    val depth: Int = 0,
    val label: String,
    val mark: Mark = Mark.None,
    val status: Status = Status.None,
    val unread: Boolean = false,
    /** Set when something folds under it: whether it is folded now. */
    val collapsed: Boolean? = null,
    /** Happy's own row, which never goes under another. */
    val isHappy: Boolean = false,
    /** Where the row opens: its conversation and the workspace it runs in. */
    val agentId: String? = null,
    val workspaceId: String? = null,
    val spaceId: String? = null,
    /** `task`, `bot`, `surface` or `agent` for a space's row. */
    val targetKind: String? = null,
    /** New work nobody named yet, which "Close new work" may close. */
    val isUntouchedStemCell: Boolean = false,
    /** The task or bot the row stands for, and whether the person may archive it. */
    val targetId: String? = null,
    val canArchive: Boolean = false,
    /**
     * The row's conversation's subtasks as they nest, which "Show subtasks"
     * puts under it; whether some are not drawn, and whether some are.
     */
    val sessions: List<SidebarDocumentEdits.SessionTree> = emptyList(),
    val hasHiddenSessions: Boolean = false,
    val hasShownSessions: Boolean = false,
    /** A Surface row's type: `todo`, `document`, `calendar`, `system_monitor` or `chat`. */
    val surfaceType: String? = null,
) {
    enum class Kind { Search, Create, Section, Separator, Widget, Space, Session }

    /** The mark in a row's leading lane. */
    sealed class Mark {
        /** No mark and no lane: the label starts where the mark would. */
        data object None : Mark()
        /** Happy's own logo. */
        data object Happy : Mark()
        /** The stem cell, its shape seeded by the space ID. */
        data class StemCell(val seed: String) : Mark()
        /** A clay icon from the library, drawn as it stands. */
        data class Clay(val slug: String) : Mark()
        /** A picture read from the daemon, drawn as a rounded square. */
        data class Image(val path: String, val thumbhash: String?) : Mark()
        /** A plain glyph: "search", "compose", "tasks", "agents", "doc", "calendar", "pulse" or "users". */
        data class Glyph(val name: String) : Mark()
    }

    enum class Status { None, Working, Waiting, AwaitingInput }

    val opens: Boolean get() = kind == Kind.Space || kind == Kind.Session || kind == Kind.Search || kind == Kind.Create
}

/** The document as the rows the sidebar draws (happy-ios `StemCellsRows`). */
object StemCellsRows {
    data class Labels(
        val search: String = "Search",
        val create: String = "Create",
        /** A space with no title yet. */
        val untitled: String = "New",
        /** A session the lists know with no title yet. */
        val untitledSession: String = "Untitled",
        /** Prioritizing's own markup, never drawn as HTML. */
        val widget: String = "Prioritizing",
    )

    /** Every row in document order, folded ones included. `agentIcons` are library slugs by agent ID. */
    fun all(document: SpaceSidebarDocument, spaces: Map<String, Space>, places: StemCellsPlaces, agentIcons: Map<String, String>, labels: Labels = Labels()): List<StemCellsRow> {
        val rows = ArrayList<StemCellsRow>()
        val held = HashSet<String>()
        fun hold(node: SpaceSidebarNode) {
            if (node.kind != NodeKind.Space) return
            held += node.id
            node.children.forEach(::hold)
        }
        document.items.forEach(::hold)
        val context = Context(spaces, places, agentIcons, labels, held)
        for (node in document.items) {
            when (node.kind) {
                NodeKind.Search -> rows += StemCellsRow(node.id, node.id, StemCellsRow.Kind.Search, label = labels.search, mark = StemCellsRow.Mark.Glyph("search"))
                NodeKind.Create -> rows += StemCellsRow(node.id, node.id, StemCellsRow.Kind.Create, label = labels.create, mark = StemCellsRow.Mark.Glyph("compose"))
                NodeKind.Section -> rows += StemCellsRow(node.id, node.id, StemCellsRow.Kind.Section, label = node.label, collapsed = node.collapsed)
                NodeKind.Separator -> rows += StemCellsRow(node.id, node.id, StemCellsRow.Kind.Separator, label = "")
                NodeKind.Widget -> rows += StemCellsRow(node.id, node.id, StemCellsRow.Kind.Widget, label = widgetText(node.html) ?: labels.widget)
                NodeKind.Space -> {
                    // A space this client does not know yet draws nothing until it does.
                    val space = spaces[node.id]?.takeIf(::knownTarget) ?: continue
                    rows += spaceRows(node, space, 0, context)
                }
            }
        }
        return rows
    }

    /**
     * The rows a fold leaves showing: a folded section takes every row up
     * to the next section or separator, a folded row its whole tree.
     */
    fun visible(rows: List<StemCellsRow>): List<StemCellsRow> {
        val shown = ArrayList<StemCellsRow>()
        var hiddenBelow: Int? = null
        var runHidden = false
        for (row in rows) {
            if ((row.kind == StemCellsRow.Kind.Section || row.kind == StemCellsRow.Kind.Separator) && row.depth == 0) {
                hiddenBelow = null
                runHidden = row.kind == StemCellsRow.Kind.Section && row.collapsed == true
                shown += row
                continue
            }
            if (runHidden) continue
            val below = hiddenBelow
            if (below != null) {
                if (row.depth >= below) continue
                hiddenBelow = null
            }
            shown += row
            if (row.collapsed == true) hiddenBelow = row.depth + 1
        }
        return shown
    }

    internal fun knownTarget(space: Space): Boolean = space.target.kind in listOf("task", "bot", "surface", "agent")

    private class Context(
        val spaces: Map<String, Space>,
        val places: StemCellsPlaces,
        val agentIcons: Map<String, String>,
        val labels: Labels,
        val held: Set<String>,
    )

    private fun spaceRows(node: SpaceSidebarNode, space: Space, depth: Int, context: Context): List<StemCellsRow> {
        val places = context.places
        val children = nested(node.children, depth + 1, context)
        val conversation = places.conversation(space)
        val sessions = sessionTree(conversation?.subtasks.orEmpty())
        val hasHidden = hasMissing(sessions, node, context.held)
        var collapsed = if (children.isEmpty()) null else !node.expanded
        // Subtasks not yet in the sidebar: folded, and unfolding brings them.
        if (collapsed == null && hasHidden) collapsed = true
        val row = StemCellsRow(
            id = "space:${space.id}", nodeId = node.id, kind = StemCellsRow.Kind.Space, depth = depth,
            label = space.title ?: context.labels.untitled,
            mark = mark(space, places),
            status = status(conversation, space.working),
            unread = conversation?.unread != null,
            collapsed = collapsed,
            isHappy = places.isHappy(space),
            agentId = space.target.agentId,
            workspaceId = space.target.workspaceId ?: space.target.agentId?.let { places.agent(it)?.workspaceId },
            spaceId = space.id, targetKind = space.target.kind,
            isUntouchedStemCell = space.target.kind == "task" && space.title == null && space.icon == SpaceIcon.StemCell,
            targetId = space.target.id,
            // A bot can always be archived; a task only where the daemon says so.
            canArchive = space.target.kind == "bot" || places.task(space.target.id)?.canArchive == true,
            sessions = sessions,
            hasHiddenSessions = hasHidden,
            hasShownSessions = node.children.any { context.spaces[it.id] == null && places.agent(it.id) != null },
            surfaceType = if (space.target.kind == "surface") places.surface(space.target.id)?.type else null,
        )
        return listOf(row) + children
    }

    fun sessionTree(agents: List<Agent>): List<SidebarDocumentEdits.SessionTree> =
        agents.filter { it.archivedAt == null }.map { SidebarDocumentEdits.SessionTree(it.id, sessionTree(it.subtasks.orEmpty())) }

    /**
     * Whether showing `node`'s sessions would bring any, as `revealSessions`
     * walks them: one the document doesn't hold, or one missing under a
     * session nested here. A session held elsewhere is that place's to show.
     */
    fun hasMissing(trees: List<SidebarDocumentEdits.SessionTree>, under: SpaceSidebarNode, held: Set<String>): Boolean = trees.any { tree ->
        if (tree.id !in held) return@any true
        val child = under.children.firstOrNull { it.id == tree.id } ?: return@any false
        hasMissing(tree.children, child, held)
    }

    private fun nested(nodes: List<SpaceSidebarNode>, depth: Int, context: Context): List<StemCellsRow> {
        val rows = ArrayList<StemCellsRow>()
        val places = context.places
        for (node in nodes) {
            if (node.kind != NodeKind.Space) continue
            val space = context.spaces[node.id]
            if (space != null) {
                if (!knownTarget(space)) continue
                rows += spaceRows(node, space, depth, context)
                continue
            }
            // A session the lists do not know yet draws nothing until they do.
            val agent = places.agent(node.id) ?: continue
            val children = nested(node.children, depth + 1, context)
            // A subtask's own subtasks show and hide under it as a row's do.
            val sessions = sessionTree(agent.subtasks.orEmpty())
            val hasHidden = hasMissing(sessions, node, context.held)
            var collapsed = if (children.isEmpty()) null else !node.expanded
            if (collapsed == null && hasHidden) collapsed = true
            rows += StemCellsRow(
                id = "session:${node.id}", nodeId = node.id, kind = StemCellsRow.Kind.Session, depth = depth,
                label = agent.title ?: context.labels.untitledSession,
                mark = context.agentIcons[node.id]?.let { if (ClayIcons.contains(it)) StemCellsRow.Mark.Clay(it) else StemCellsRow.Mark.None } ?: StemCellsRow.Mark.None,
                status = status(agent, false),
                unread = agent.unread != null,
                collapsed = collapsed,
                agentId = agent.id, workspaceId = agent.workspaceId,
                sessions = sessions,
                hasHiddenSessions = hasHidden,
                hasShownSessions = node.children.any { context.spaces[it.id] == null && places.agent(it.id) != null },
            )
            rows += children
        }
        return rows
    }

    /**
     * `happyAgentSpaceMark`: Happy's logo; the stem cell while the work takes
     * shape; the chosen clay icon or picture; a face; then the stem cell for
     * unnamed work or the plain glyph of what it is.
     */
    fun mark(space: Space, places: StemCellsPlaces): StemCellsRow.Mark {
        if (places.isHappy(space)) return StemCellsRow.Mark.Happy
        when (val icon = space.icon) {
            SpaceIcon.StemCell -> return StemCellsRow.Mark.StemCell(space.id)
            is SpaceIcon.Library -> if (ClayIcons.contains(icon.slug)) return StemCellsRow.Mark.Clay(icon.slug)
            is SpaceIcon.Image -> {
                val thumbhash = if (space.target.kind == "bot") places.bot(space.target.id)?.thumbhash else null
                return StemCellsRow.Mark.Image(icon.url, thumbhash)
            }
            else -> {}
        }
        if (space.target.kind == "bot") {
            val bot = places.bot(space.target.id)
            if (bot != null && bot.hasAvatar) return StemCellsRow.Mark.Image("/v0/bots/${bot.id}/avatar", bot.thumbhash)
        }
        if (space.title == null) return StemCellsRow.Mark.StemCell(space.id)
        return StemCellsRow.Mark.Glyph(
            when (space.target.kind) {
                "surface" -> surfaceGlyph(places.surface(space.target.id)?.type)
                "task" -> "tasks"
                else -> "agents"
            },
        )
    }

    /** The glyph desktop's Create menu gives each kind of Surface. */
    fun surfaceGlyph(type: String?): String = when (type) {
        "todo" -> "tasks"
        "calendar" -> "calendar"
        "system_monitor" -> "pulse"
        "chat" -> "users"
        else -> "doc"
    }

    fun status(agent: Agent?, working: Boolean): StemCellsRow.Status = when {
        agent?.pendingQuestionId != null -> StemCellsRow.Status.AwaitingInput
        agent?.wait != null -> StemCellsRow.Status.Waiting
        working || agent?.isWorking == true -> StemCellsRow.Status.Working
        else -> StemCellsRow.Status.None
    }

    /** A widget's words without its markup; null when it has none. */
    fun widgetText(html: String): String? {
        val text = StringBuilder()
        var inTag = false
        for (character in html) {
            when {
                character == '<' -> { inTag = true; text.append(' ') }
                character == '>' -> inTag = false
                !inTag -> text.append(character)
            }
        }
        val words = SpaceSidebar.decodeEntities(text.toString()).split(Regex("[$UNICODE_SPACE]+")).filter { it.isNotEmpty() }.joinToString(" ")
        return words.ifEmpty { null }
    }
}

/**
 * Where a row dragged on a phone lands. A phone doesn't draw desktop's
 * Search and Create rows, but they stay in the document: they never move,
 * and every drop is anchored on the rows the person can see, so desktop's
 * order doesn't shift around them.
 */
object StemCellsDrop {
    /** Kept in the document for desktop; never drawn on a phone. */
    fun isHidden(row: StemCellsRow): Boolean = row.kind == StemCellsRow.Kind.Search || row.kind == StemCellsRow.Kind.Create

    /**
     * A row the person can pick up: a space, a section, a separator or a
     * widget, never Happy's own row, a session or a hidden one.
     */
    fun canMove(row: StemCellsRow): Boolean = !isHidden(row) && !row.isHappy &&
        row.kind in listOf(StemCellsRow.Kind.Space, StemCellsRow.Kind.Section, StemCellsRow.Kind.Separator, StemCellsRow.Kind.Widget)

    /** Where a drop lands: after `id`, or first when `id` is null. */
    data class Anchor(val after: String?)

    /**
     * The top-level node `moving` is set down after when it is dropped just
     * above the drawn top-level row `before` (null: below the last). A drop at
     * the very top lands right after whatever hidden node sits before the
     * first drawn row (Create), never before it. Null when the row can't move
     * there, or `before` is hidden. `rows` are all of them, hidden ones
     * included, so the anchor is the document's real neighbour.
     */
    fun after(moving: String, droppedBefore: String?, rows: List<StemCellsRow>): Anchor? {
        val row = rows.firstOrNull { it.nodeId == moving } ?: return null
        if (!canMove(row) || row.depth != 0) return null
        val top = rows.filter { it.depth == 0 && it.nodeId != moving }
        if (droppedBefore == null) return Anchor(top.lastOrNull()?.nodeId)
        val index = top.indexOfFirst { it.nodeId == droppedBefore }
        if (index < 0 || isHidden(top[index])) return null
        return Anchor(if (index > 0) top[index - 1].nodeId else null)
    }
}
