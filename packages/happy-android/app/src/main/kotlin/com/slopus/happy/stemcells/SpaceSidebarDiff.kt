package com.slopus.happy.stemcells

import com.slopus.happy.stemcells.SpaceSidebar.format
import com.slopus.happy.stemcells.SpaceSidebar.locate
import com.slopus.happy.stemcells.SpaceSidebar.node
import com.slopus.happy.stemcells.SpaceSidebar.runLength
import com.slopus.happy.stemcells.SpaceSidebarNode.Kind

/** No operations turn the current document into the proposed one. */
class SpaceSidebarDiffFailure : Exception("The sidebar edit has no operations.")

/**
 * A port of happy-ios `SpaceSidebarDiff.swift` (`diffSpaceSidebar.ts`): the
 * operations that turn one document into another by node ID. A client edits
 * the tree, then sends the difference, so its batch goes through the
 * daemon's rebase and authorship rules exactly as desktop's does.
 */
internal object SpaceSidebarDiff {
    /**
     * The operations that turn `current` into `proposed`. As few items as
     * possible move, a section whose block travelled whole moves as one
     * block, and only what changed is set. Applying the result to `current`
     * gives `proposed` exactly.
     */
    fun diff(current: SpaceSidebarDocument, proposed: SpaceSidebarDocument, widgets: Boolean): List<SpaceSidebarOperation> {
        val keepWidgets = !widgets
        if (keepWidgets && proposed.items.any { it.kind == Kind.Widget }) throw SpaceSidebarError("This write cannot contain widgets.")
        val wanted = format(proposed, widgets = !keepWidgets)
        for (blocks in listOf(true, false)) {
            val operations = try {
                plan(current, proposed, keepWidgets, blocks)
            } catch (error: SpaceSidebarError) {
                if (blocks) continue
                throw error
            }
            val document = SpaceSidebar.apply(current, operations).document
            if (format(document, widgets = !keepWidgets) == wanted) return operations
        }
        throw SpaceSidebarDiffFailure()
    }

    private class Step(val node: SpaceSidebarNode, val to: SpaceSidebarPosition)

    private fun plan(current: SpaceSidebarDocument, proposed: SpaceSidebarDocument, keepWidgets: Boolean, blocks: Boolean): List<SpaceSidebarOperation> {
        var working = current
        val operations = ArrayList<SpaceSidebarOperation>()
        fun emit(operation: SpaceSidebarOperation) {
            val (next, done) = SpaceSidebarOperations.applyOperation(working, operation, null, operations.size)
            working = next
            if (done != null) operations += operation
        }
        fun visible(items: List<SpaceSidebarNode>) = if (keepWidgets) items.filter { it.kind != Kind.Widget } else items
        fun place(node: SpaceSidebarNode, to: SpaceSidebarPosition) {
            emit(if (locate(working, node.id) != null) SpaceSidebarOperation.Move(node.id, to) else SpaceSidebarOperation.Insert(newNode(node), to))
        }

        val target = visible(proposed.items)
        if (blocks) {
            for (step in topLevelUnits(visible(current.items), target)) place(step.node, step.to)
        } else {
            // Item by item: each item lands right after the one before it.
            for ((index, node) in target.withIndex()) {
                val now = visible(working.items)
                if (index in now.indices && now[index].id == node.id) continue
                place(node, if (index == 0) SpaceSidebarPosition.Parent(null) else SpaceSidebarPosition.After(target[index - 1].id))
            }
        }

        // Each row's and session's nested sessions, top down.
        fun nest(parent: SpaceSidebarNode) {
            val holder = node(working, locate(working, parent.id)!!.path)
            val wanted = parent.children.map { it.id }
            val present = holder.children.map { it.id }.filter { it in wanted }
            val still = stationary(present, wanted.filter { it in present })
            for ((index, child) in parent.children.withIndex()) {
                if (child.id in still) continue
                place(child, if (index == 0) SpaceSidebarPosition.Parent(parent.id) else SpaceSidebarPosition.After(parent.children[index - 1].id))
            }
            for (child in parent.children) nest(child)
        }
        for (item in target) if (item.kind == Kind.Space) nest(item)

        // Switches and text.
        fun settle(wanted: SpaceSidebarNode) {
            val now = node(working, locate(working, wanted.id)!!.path)
            changedFields(now, wanted)?.let { emit(SpaceSidebarOperation.Set(it)) }
            if (wanted.kind == Kind.Space) for (child in wanted.children) settle(child)
        }
        for (item in target) settle(item)
        if (working.foldRibbon != proposed.foldRibbon) {
            emit(SpaceSidebarOperation.Set(SpaceSidebarSet("nav", foldRibbon = proposed.foldRibbon?.let(SpaceSidebarRibbonPatch::Value) ?: SpaceSidebarRibbonPatch.Clear)))
        }

        // Whatever the proposal left out, outermost first.
        val kept = HashSet<String>()
        fun collect(node: SpaceSidebarNode) {
            kept += node.id
            if (node.kind == Kind.Space) node.children.forEach(::collect)
        }
        proposed.items.forEach(::collect)
        val leaving = ArrayList<String>()
        fun sweep(node: SpaceSidebarNode) {
            if (keepWidgets && node.kind == Kind.Widget) return
            if (node.id !in kept) leaving += node.id else if (node.kind == Kind.Space) node.children.forEach(::sweep)
        }
        working.items.forEach(::sweep)
        for (id in leaving) emit(SpaceSidebarOperation.Remove(id))
        return operations
    }

    private class Block(val id: String, val node: SpaceSidebarNode, val last: String)

    /** The top-level moves and inserts, block by block (`topLevelUnits`). */
    private fun topLevelUnits(current: List<SpaceSidebarNode>, proposed: List<SpaceSidebarNode>): List<Step> {
        fun runOf(items: List<SpaceSidebarNode>, index: Int): List<String> =
            items.subList(index + 1, index + runLength(items, index)).map { it.id }
        val currentRuns = HashMap<String, List<String>>()
        for ((index, item) in current.withIndex()) if (item.kind == Kind.Section) currentRuns[item.id] = runOf(current, index)
        val whole = HashSet<String>()
        for ((index, item) in proposed.withIndex()) {
            val before = currentRuns[item.id] ?: continue
            val after = runOf(proposed, index)
            if (before.withIndex().all { (offset, id) -> offset in after.indices && after[offset] == id }) whole += item.id
        }
        fun units(items: List<SpaceSidebarNode>): List<Block> {
            val result = ArrayList<Block>()
            var index = 0
            while (index < items.size) {
                val item = items[index]
                val run = if (item.id in whole) currentRuns.getValue(item.id) else emptyList()
                result += Block(item.id, item, run.lastOrNull() ?: item.id)
                index += run.size + 1
            }
            return result
        }
        val before = units(current)
        val after = units(proposed)
        val known = before.mapTo(HashSet()) { it.id }
        val afterIds = after.mapTo(HashSet()) { it.id }
        val still = stationary(before.map { it.id }.filter { it in afterIds }, after.map { it.id }.filter { it in known })
        for (unit in after) {
            if (unit.node.kind == Kind.Section && unit.id in known && unit.id !in still && unit.id !in whole) {
                throw SpaceSidebarError("Section ${unit.id} moved without its block.")
            }
        }
        val steps = ArrayList<Step>()
        var previous: String? = null
        for (unit in after) {
            if (unit.id !in still) steps += Step(unit.node, previous?.let(SpaceSidebarPosition::After) ?: SpaceSidebarPosition.Parent(null))
            previous = unit.last
        }
        return steps
    }

    /**
     * The longest subsequence of `before` that `after` keeps in order,
     * with the reference implementation's tie-breaking.
     */
    fun stationary(before: List<String>, after: List<String>): Set<String> {
        val positions = HashMap<String, Int>()
        for ((index, id) in before.withIndex()) positions[id] = index
        val lengths = IntArray(after.size) { 1 }
        val previous = IntArray(after.size) { -1 }
        var best = -1
        for (index in after.indices) {
            for (earlier in 0 until index) {
                if (positions.getValue(after[earlier]) < positions.getValue(after[index]) && lengths[earlier] + 1 > lengths[index]) {
                    lengths[index] = lengths[earlier] + 1
                    previous[index] = earlier
                }
            }
            if (best == -1 || lengths[index] > lengths[best]) best = index
        }
        val kept = HashSet<String>()
        var index = best
        while (index != -1) {
            kept += after[index]
            index = previous[index]
        }
        return kept
    }

    private fun newNode(node: SpaceSidebarNode): SpaceSidebarNewNode = when (node.kind) {
        Kind.Space -> SpaceSidebarNewNode(Kind.Space, node.id, expanded = if (node.expanded) true else null, foldRibbon = node.foldRibbon)
        Kind.Section -> SpaceSidebarNewNode(Kind.Section, node.id, label = node.label, collapsed = if (node.collapsed) true else null)
        Kind.Separator -> SpaceSidebarNewNode(Kind.Separator, node.id)
        Kind.Widget -> SpaceSidebarNewNode(Kind.Widget, node.id, html = node.html)
        Kind.Search, Kind.Create -> throw SpaceSidebarError("The sidebar needs exactly one ${node.id}.")
    }

    private fun changedFields(now: SpaceSidebarNode, wanted: SpaceSidebarNode): SpaceSidebarSet? {
        var fields = SpaceSidebarSet(wanted.id)
        if (now.kind == Kind.Section && wanted.kind == Kind.Section) {
            if (now.label != wanted.label) fields = fields.copy(label = wanted.label)
            if (now.collapsed != wanted.collapsed) fields = fields.copy(collapsed = wanted.collapsed)
        } else if (now.kind == Kind.Space && wanted.kind == Kind.Space) {
            if (now.expanded != wanted.expanded) fields = fields.copy(expanded = wanted.expanded)
            if (now.foldRibbon != wanted.foldRibbon) fields = fields.copy(foldRibbon = wanted.foldRibbon?.let(SpaceSidebarRibbonPatch::Value) ?: SpaceSidebarRibbonPatch.Clear)
        } else if (now.kind == Kind.Widget && wanted.kind == Kind.Widget && now.html != wanted.html) {
            fields = fields.copy(html = wanted.html)
        }
        return if (fields.isEmpty) null else fields
    }
}
