package com.slopus.happy.stemcells

/** The current iOS/desktop sidebar's row-specific actions, never offered for Happy. */
object StemCellsRowActions {
    enum class Action { RenameSection, RenameTask, RenameBot, Reorder, DeleteSection, CloseNewWork, LeaveTask, ArchiveTask, ArchiveBot, ArchiveSubtask }

    fun groups(row: StemCellsRow, canReorder: Boolean = false): List<List<Action>> {
        if (row.isHappy || row.kind !in listOf(StemCellsRow.Kind.Space, StemCellsRow.Kind.Session, StemCellsRow.Kind.Section)) return emptyList()
        val first = buildList {
            when {
                row.kind == StemCellsRow.Kind.Section -> add(Action.RenameSection)
                row.kind == StemCellsRow.Kind.Space && row.spaceId != null && row.targetKind == "task" -> add(Action.RenameTask)
                row.kind == StemCellsRow.Kind.Space && row.spaceId != null && row.targetKind == "bot" -> add(Action.RenameBot)
            }
            if (canReorder) add(Action.Reorder)
        }
        val last = when {
            row.kind == StemCellsRow.Kind.Section -> listOf(Action.DeleteSection)
            row.kind == StemCellsRow.Kind.Session && row.agentId != null -> listOf(Action.ArchiveSubtask)
            row.kind == StemCellsRow.Kind.Space && row.isUntouchedStemCell && row.spaceId != null -> listOf(Action.CloseNewWork)
            row.kind == StemCellsRow.Kind.Space && row.targetId != null && row.targetKind == "task" -> listOfNotNull(Action.LeaveTask, Action.ArchiveTask.takeIf { row.canArchive })
            row.kind == StemCellsRow.Kind.Space && row.targetId != null && row.targetKind == "bot" -> listOf(Action.ArchiveBot)
            else -> emptyList()
        }
        return listOf(first, last).filter { it.isNotEmpty() }
    }
}