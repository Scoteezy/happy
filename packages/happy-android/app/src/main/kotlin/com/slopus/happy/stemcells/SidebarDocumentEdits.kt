package com.slopus.happy.stemcells

import com.slopus.happy.stemcells.SpaceSidebarNode.Kind

/**
 * The person's edits of the document, as desktop makes them
 * (`happy-desktop-state/src/spaces/spacesSidebarDocument.ts`), ported from
 * happy-ios `SidebarDocumentEdits.swift`. Each returns the edited document,
 * or null when the edit names nothing or changes nothing. The store sends
 * the difference as operations.
 *
 * Node IDs are unique across the document, so a node is named by its ID;
 * Search and Create by `happy-search` and `happy-create`.
 */
object SidebarDocumentEdits {
    /** Where a folded section's run stops: at the next section or separator. */
    private fun breaksRun(node: SpaceSidebarNode) = node.kind == Kind.Section || node.kind == Kind.Separator

    /** Where the rows after `index` stop belonging to it. */
    internal fun blockEnd(nodes: List<SpaceSidebarNode>, index: Int): Int {
        var end = index + 1
        while (end < nodes.size && !breaksRun(nodes[end])) end++
        return end
    }

    /** The node itself, with the run it folds away when it is a folded section. */
    internal fun runEnd(nodes: List<SpaceSidebarNode>, index: Int): Int =
        if (index in nodes.indices && nodes[index].kind == Kind.Section && nodes[index].collapsed) blockEnd(nodes, index) else index + 1

    /**
     * Moves one node among its own siblings, after `afterId` or first. A
     * section is a block: its heading and every row after it up to the next
     * section or separator move as one, and it lands after the whole block
     * `afterId` belongs to. Any other node landing after a folded section
     * lands after its run, so a move never hides or reveals rows.
     */
    fun move(document: SpaceSidebarDocument, id: String, afterId: String?): SpaceSidebarDocument? {
        if (id == afterId) return null
        val location = SpaceSidebar.locate(document, id) ?: return null
        val nodes = SpaceSidebar.siblings(document, location.parentPath)
        val from = location.index
        val section = nodes[from].kind == Kind.Section
        val end = if (section) blockEnd(nodes, from) else runEnd(nodes, from)
        val moved = nodes.subList(from, end)
        val rest = nodes.subList(0, from) + nodes.subList(end, nodes.size)
        var at = 0
        if (afterId != null) {
            val after = rest.indexOfFirst { it.id == afterId }
            if (after < 0) return null
            // Search and Create carry no run: what lands after one lands right there.
            val hidden = rest[after].kind == Kind.Search || rest[after].kind == Kind.Create
            at = if (hidden) after + 1 else if (section && rest[after].kind != Kind.Separator) blockEnd(rest, after) else runEnd(rest, after)
        }
        val result = rest.subList(0, at) + moved + rest.subList(at, rest.size)
        if (result.map { it.id } == nodes.map { it.id }) return null
        return SpaceSidebar.withSiblings(document, location.parentPath) { it.clear(); it.addAll(result) }
    }

    /** Folds or unfolds a section's run, or a row's or session's nested rows. */
    fun fold(document: SpaceSidebarDocument, id: String): SpaceSidebarDocument? {
        val location = SpaceSidebar.locate(document, id) ?: return null
        return SpaceSidebar.withNode(document, location.path) { node ->
            when (node.kind) {
                Kind.Section -> node.copy(collapsed = !node.collapsed)
                Kind.Space -> node.copy(expanded = !node.expanded)
                else -> node
            }
        }
    }

    /**
     * Deletes a section's heading: its rows stay where they are and join
     * the block above (the daemon's `remove` of a section).
     */
    fun removeSection(document: SpaceSidebarDocument, id: String): SpaceSidebarDocument? {
        val index = document.items.indexOfFirst { it.id == id && it.kind == Kind.Section }
        if (index < 0) return null
        return document.copy(items = document.items.toMutableList().apply { removeAt(index) })
    }

    /** Renames a section's heading. */
    fun relabel(document: SpaceSidebarDocument, id: String, label: String): SpaceSidebarDocument? {
        val index = document.items.indexOfFirst { it.id == id && it.kind == Kind.Section }
        if (index < 0) return null
        return document.copy(items = document.items.toMutableList().apply { this[index] = this[index].copy(label = label) })
    }

    /**
     * Sets a row or attachment down under another parent: under `parentId`,
     * after its nested node `afterId` or first, or for null at the top
     * level, after the item `afterId` or first. Among its own siblings it is
     * an ordinary move.
     */
    fun place(document: SpaceSidebarDocument, id: String, parentId: String?, afterId: String?): SpaceSidebarDocument? {
        val from = SpaceSidebar.locate(document, id) ?: return null
        val fromParent = if (from.parentId == "nav") null else from.parentId
        if (fromParent == parentId) return move(document, id, afterId)
        val node = SpaceSidebar.node(document, from.path)
        if (node.kind != Kind.Space) return null
        if (parentId != null && SpaceSidebar.contains(node, parentId)) return null
        val lifted = SpaceSidebar.withSiblings(document, from.parentPath) { it.removeAt(from.index) }
        if (parentId == null) {
            var at = 0
            if (afterId != null) {
                val after = lifted.items.indexOfFirst { it.id == afterId }
                if (after < 0) return null
                at = runEnd(lifted.items, after)
            }
            return lifted.copy(items = lifted.items.toMutableList().apply { add(at, node) })
        }
        val host = SpaceSidebar.locate(lifted, parentId) ?: return null
        val holder = SpaceSidebar.node(lifted, host.path)
        if (holder.kind != Kind.Space) return null
        var at = 0
        if (afterId != null) {
            val after = holder.children.indexOfFirst { it.id == afterId }
            if (after < 0) return null
            at = after + 1
        }
        return SpaceSidebar.withNode(lifted, host.path) { parent ->
            parent.copy(expanded = true, children = parent.children.toMutableList().apply { add(at, node) })
        }
    }

    /**
     * Names new work before the document read catches up, where the daemon
     * puts it: right after the named section or separator; otherwise right
     * after Happy's own top-level row; otherwise — Happy absent or folded
     * away under a section — right after Create, and first when there is no
     * Create.
     */
    fun insert(document: SpaceSidebarDocument, spaceId: String, sectionId: String?, happySpaceId: String?): SpaceSidebarDocument? {
        if (document.items.any { it.kind == Kind.Space && it.id == spaceId }) return null
        fun after(): Int {
            if (sectionId != null) return document.items.indexOfFirst { (it.kind == Kind.Section || it.kind == Kind.Separator) && it.id == sectionId }
            var folded = false
            for ((index, item) in document.items.withIndex()) {
                when {
                    item.kind == Kind.Section -> folded = item.collapsed
                    item.kind == Kind.Separator -> folded = false
                    item.kind == Kind.Space && item.id == happySpaceId && !folded -> return index
                }
            }
            return document.items.indexOfFirst { it.kind == Kind.Create }
        }
        return document.copy(items = document.items.toMutableList().apply { add(after() + 1, SpaceSidebarNode.space(spaceId)) })
    }

    /**
     * Shows every session of the row or session `id`: each one the document
     * does not hold yet joins its parent's nested nodes, after the ones
     * already there, and every node a session joined under opens.
     */
    fun revealSessions(document: SpaceSidebarDocument, id: String, sessions: List<SessionTree>): SpaceSidebarDocument? {
        val location = SpaceSidebar.locate(document, id) ?: return null
        if (SpaceSidebar.node(document, location.path).kind != Kind.Space) return null
        val held = HashSet<String>()
        fun hold(node: SpaceSidebarNode) {
            if (node.kind != Kind.Space) return
            held += node.id
            node.children.forEach(::hold)
        }
        document.items.forEach(::hold)
        fun reveal(node: SpaceSidebarNode, wanted: List<SessionTree>): SpaceSidebarNode? {
            var grew = false
            val children = node.children.map { child ->
                val tree = wanted.firstOrNull { it.id == child.id }
                val next = tree?.let { reveal(child, it.children) }
                if (next == null) child else { grew = true; next }
            }.toMutableList()
            var added = false
            // Filtered before any is held, as desktop's `filter` then `map` does.
            val missing = wanted.filter { it.id !in held }
            for (session in missing) {
                held += session.id
                val fresh = SpaceSidebarNode.space(session.id)
                children += reveal(fresh, session.children) ?: fresh
                added = true
            }
            if (!grew && !added) return null
            return node.copy(expanded = true, children = children)
        }
        val revealed = reveal(SpaceSidebar.node(document, location.path), sessions) ?: return null
        return SpaceSidebar.withNode(document, location.path) { revealed }
    }

    /**
     * `sidebarDocumentSessionsHide`: takes the sessions back out from under
     * a row or session; a space nested under one of them (`attached`) takes
     * its place. Null when everything under it is a space.
     */
    fun hideSessions(document: SpaceSidebarDocument, id: String, attached: (String) -> Boolean): SpaceSidebarDocument? {
        val location = SpaceSidebar.locate(document, id) ?: return null
        val node = SpaceSidebar.node(document, location.path)
        if (node.kind != Kind.Space || node.children.all { attached(it.id) }) return null
        fun kept(nodes: List<SpaceSidebarNode>): List<SpaceSidebarNode> = nodes.flatMap { if (attached(it.id)) listOf(it) else kept(it.children) }
        return SpaceSidebar.withNode(document, location.path) { it.copy(children = kept(node.children)) }
    }

    /** A conversation's sessions as they nest, by agent ID. */
    data class SessionTree(val id: String, val children: List<SessionTree> = emptyList())
}
