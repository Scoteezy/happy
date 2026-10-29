package com.slopus.happy.stemcells

import com.slopus.happy.stemcells.StemCellsRowActions.Action.*
import org.junit.Assert.*
import org.junit.Test

class StemCellsRowActionsTest {
    private val task = StemCellsRow("space:s", "s", StemCellsRow.Kind.Space, label = "Task", spaceId = "s", targetId = "t", targetKind = "task")
    @Test fun happyAndNonActionableRowsHaveNoMenu() {
        assertTrue(StemCellsRowActions.groups(task.copy(isHappy = true)).isEmpty())
        assertTrue(StemCellsRowActions.groups(task.copy(kind = StemCellsRow.Kind.Widget)).isEmpty())
        assertTrue(StemCellsRowActions.groups(task.copy(targetKind = "surface")).isEmpty())
    }
    @Test fun archiveRequiresPermissionAndNewWorkClosesInsteadOfLeaving() {
        assertEquals(listOf(listOf(RenameTask), listOf(LeaveTask)), StemCellsRowActions.groups(task))
        assertEquals(listOf(listOf(RenameTask), listOf(LeaveTask, ArchiveTask)), StemCellsRowActions.groups(task.copy(canArchive = true)))
        assertEquals(listOf(listOf(RenameTask), listOf(CloseNewWork)), StemCellsRowActions.groups(task.copy(isUntouchedStemCell = true, canArchive = true)))
    }
    @Test fun sectionsBotsAndSubtasksOfferTheirOwnActions() {
        assertEquals(listOf(listOf(RenameSection), listOf(DeleteSection)), StemCellsRowActions.groups(task.copy(kind = StemCellsRow.Kind.Section)))
        assertEquals(listOf(listOf(RenameBot), listOf(ArchiveBot)), StemCellsRowActions.groups(task.copy(targetKind = "bot")))
        assertEquals(listOf(listOf(ArchiveSubtask)), StemCellsRowActions.groups(task.copy(kind = StemCellsRow.Kind.Session, agentId = "agent")))
    }
    @Test fun reorderIsOfferedOnlyWhenTheHostHasALegalDrag() {
        assertEquals(listOf(RenameTask, Reorder), StemCellsRowActions.groups(task, canReorder = true).first())
        assertTrue(StemCellsRowActions.groups(task.copy(kind = StemCellsRow.Kind.Separator), canReorder = true).isEmpty())
        assertTrue(StemCellsRowActions.groups(task.copy(isHappy = true), canReorder = true).isEmpty())
    }
}