package com.slopus.happy.stemcells

/**
 * A top-level row carried through the sidebar, as desktop's `Sidebar.tsx`
 * reorders: the rows it can be set down among (its peers), the slots a
 * drop may not take, the slot the hand is over, and where the document
 * puts it there. happy-ios `StemCellsDrag`.
 *
 * A peer is a top-level row with the rows nested under it. A section is
 * carried with its block, the rows after it up to the next section or
 * separator, and its peers are the other blocks. Rows are the drawn ones:
 * Search and Create are never peers and never move, and a drop at the very
 * top lands right after them.
 *
 * A nested row (a subtask, or work set under a row) is carried among its
 * family alone: the rows nested under the same parent, each with its own.
 */
class StemCellsDrag private constructor(
    /** The node carried. */
    val moving: String,
    /** Each peer's rows, as indices into the drawn rows, top to bottom. */
    val units: List<List<Int>>,
    /** The carried peer. */
    val from: Int,
    /** Slots a drop would hide a row in (desktop's `dragSlotsBlocked`). */
    val blocked: List<Boolean>,
    /** A nested row's parent: it moves only among its family, the rows nested under the same row. Null at the top level. */
    val parent: String?,
    private val drawn: List<StemCellsRow>,
    private val all: List<StemCellsRow>,
    /** The drawn rows before and after the family, which stay where they are. */
    private val before: List<Int>,
    private val afterFamily: List<Int>,
) {
    /**
     * Desktop's `dragTargetIndex`, then `dragSlotOpen`: the slot whose
     * neighbours the hand has passed by half their height, or the nearest
     * open one short of it. `heights` are each peer's, gaps inside included.
     */
    fun target(deltaY: Double, heights: List<Double>): Int {
        var index = from
        var travel = deltaY
        while (travel > 0 && index < heights.size - 1) {
            val next = heights[index + 1]
            if (travel <= next / 2) break
            travel -= next
            index += 1
        }
        while (travel < 0 && index > 0) {
            val previous = heights[index - 1]
            if (-travel <= previous / 2) break
            travel += previous
            index -= 1
        }
        val step = if (index > from) -1 else 1
        while (index != from && blocked[index]) index += step
        return index
    }

    /** The drawn rows in the order a drop in slot `to` leaves them. */
    fun order(to: Int): List<StemCellsRow> {
        val peers = units.toMutableList()
        val held = peers.removeAt(from)
        peers.add(to, held)
        return (before + peers.flatten() + afterFamily).map { drawn[it] }
    }

    /** The rows carried. */
    val held: List<StemCellsRow> get() = units[from].map { drawn[it] }

    /** The node it is set down after in slot `to` (`Anchor(null)` for first); null where nothing changes. */
    fun after(to: Int): StemCellsDrop.Anchor? {
        if (to == from) return null
        // In a family: after a sibling, or first under the parent.
        if (parent != null) {
            val remaining = units.toMutableList().apply { removeAt(from) }
            return StemCellsDrop.Anchor(if (to > 0) drawn[remaining[to - 1][0]].nodeId else null)
        }
        return anchor(to, units, from, drawn, all, moving)
    }

    override fun equals(other: Any?) = other is StemCellsDrag && other.moving == moving && other.units == units && other.from == from &&
        other.blocked == blocked && other.parent == parent && other.drawn == drawn && other.all == all

    override fun hashCode() = moving.hashCode() * 31 + units.hashCode()

    companion object {
        /**
         * Null when `rowId` can't be carried: a hidden row, Happy's, a lone
         * nested row, or one inside a section's block it can't leave alone.
         */
        fun holding(rowId: String, drawn: List<StemCellsRow>, all: List<StemCellsRow>, document: SpaceSidebarDocument): StemCellsDrag? {
            val index = drawn.indexOfFirst { it.id == rowId }
            if (index < 0) return null
            val row = drawn[index]
            if (row.depth > 0) {
                val (parent, units) = family(drawn, index) ?: return null
                return StemCellsDrag(
                    moving = row.nodeId, units = units, from = units.indexOfFirst { it.first() == index },
                    // Among its own siblings nothing folds or unfolds.
                    blocked = List(units.size) { false }, parent = drawn[parent].nodeId, drawn = drawn, all = all,
                    before = (0..parent).toList(), afterFamily = ((units.last().last() + 1) until drawn.size).toList(),
                )
            }
            if (!StemCellsDrop.canMove(row)) return null
            val units = if (row.kind == StemCellsRow.Kind.Section) sectionBlocks(drawn) else blocks(drawn)
            val from = units.indexOfFirst { it.first() == index }
            if (from < 0) return null
            val blocked = MutableList(units.size) { false }
            // A folded section too: set down above rows it would fold them away.
            val shown = shownTop(document)
            for (to in units.indices) {
                if (to == from) continue
                val after = anchor(to, units, from, drawn, all, row.nodeId) ?: continue
                val moved = SidebarDocumentEdits.move(document, row.nodeId, after.after) ?: continue
                blocked[to] = !shownTop(moved).containsAll(shown)
            }
            return StemCellsDrag(row.nodeId, units, from, blocked, null, drawn, all, emptyList(), emptyList())
        }

        /**
         * A nested row's family: its parent's index, and each sibling with
         * what is nested under it. Null for a row with no sibling to pass, or
         * one that can't move (a widget, a hidden row).
         */
        internal fun family(rows: List<StemCellsRow>, index: Int): Pair<Int, List<List<Int>>>? {
            val depth = rows[index].depth
            if (rows[index].kind != StemCellsRow.Kind.Space && rows[index].kind != StemCellsRow.Kind.Session) return null
            var parent = index - 1
            while (parent >= 0 && rows[parent].depth >= depth) parent -= 1
            if (parent < 0 || rows[parent].depth != depth - 1) return null
            val units = ArrayList<MutableList<Int>>()
            var next = parent + 1
            while (next < rows.size && rows[next].depth >= depth) {
                if (rows[next].depth == depth) units += mutableListOf(next) else units.last() += next
                next += 1
            }
            return if (units.size > 1) parent to units else null
        }

        /** Top-level rows with what is nested under them. */
        internal fun blocks(rows: List<StemCellsRow>): List<List<Int>> {
            val units = ArrayList<MutableList<Int>>()
            rows.forEachIndexed { index, row ->
                if (row.depth == 0 || units.isEmpty()) units += mutableListOf(index) else units.last() += index
            }
            return units
        }

        /** The rows before the first section, then each section with its run; a separator stands alone. */
        internal fun sectionBlocks(rows: List<StemCellsRow>): List<List<Int>> {
            val units = ArrayList<MutableList<Int>>()
            rows.forEachIndexed { index, row ->
                val breaks = row.depth == 0 && (row.kind == StemCellsRow.Kind.Section || row.kind == StemCellsRow.Kind.Separator)
                val afterSeparator = units.lastOrNull()?.let { rows[it[0]].kind == StemCellsRow.Kind.Separator } ?: false
                if (breaks || units.isEmpty() || afterSeparator) units += mutableListOf(index) else units.last() += index
            }
            return units
        }

        /** The top-level nodes a fold leaves showing. */
        internal fun shownTop(document: SpaceSidebarDocument): Set<String> {
            val shown = HashSet<String>()
            var hidden = false
            for (node in document.items) {
                if (node.kind == SpaceSidebarNode.Kind.Section || node.kind == SpaceSidebarNode.Kind.Separator) {
                    hidden = node.kind == SpaceSidebarNode.Kind.Section && node.collapsed
                    shown += node.id
                } else if (!hidden) {
                    shown += node.id
                }
            }
            return shown
        }

        private fun anchor(to: Int, units: List<List<Int>>, from: Int, drawn: List<StemCellsRow>, all: List<StemCellsRow>, moving: String): StemCellsDrop.Anchor? {
            val remaining = units.toMutableList().apply { removeAt(from) }
            if (to > 0) return StemCellsDrop.Anchor(drawn[remaining[to - 1][0]].nodeId)
            val first = remaining.firstOrNull() ?: return null
            // Above every drawn row: after whatever hidden node stands before it.
            return StemCellsDrop.after(moving, drawn[first[0]].nodeId, all)
        }
    }
}
