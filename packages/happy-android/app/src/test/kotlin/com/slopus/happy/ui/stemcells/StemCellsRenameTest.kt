package com.slopus.happy.ui.stemcells

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slopus.happy.stemcells.StemCellsRow
import com.slopus.happy.ui.components.TextField
import com.slopus.happy.ui.theme.Theme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StemCellsRenameTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val row = StemCellsRow("task", "node", StemCellsRow.Kind.Space, label = "Original")
    private fun sidebar() = StemCellsSidebarView(context).also { Theme.install(context); it.apply(listOf(row)); it.layout(0, 0, 1080, 2400) }

    @Test fun returnCommitsTrimmedTextOnceAndKeepsItVisibleAcrossOldSnapshots() {
        val sidebar = sidebar()
        val saved = ArrayList<String>()
        sidebar.beginRename(row.id, row.label) { saved += it }
        val source = sidebar.view(row.id)!!
        val field = source.findViewWithTag<TextField>("stem-rename")
        assertEquals(0, field.selectionStart)
        assertEquals(row.label.length, field.selectionEnd)
        field.setText("  Renamed  ")
        field.onReturn!!()
        sidebar.finishRename()
        assertEquals(listOf("Renamed"), saved)
        assertEquals("Renamed", source.contentDescription)
        sidebar.apply(listOf(row.copy(status = StemCellsRow.Status.Working)))
        assertEquals("Renamed", source.contentDescription)
        assertFalse(sidebar.isEditing)
    }

    @Test fun blankUnchangedCancelledAndRemovedRowsDoNotRename() {
        val sidebar = sidebar()
        val saved = ArrayList<String>()
        for (value in listOf(" ", row.label)) {
            sidebar.beginRename(row.id, row.label) { saved += it }
            sidebar.view(row.id)!!.findViewWithTag<TextField>("stem-rename").setText(value)
            sidebar.finishRename()
        }
        sidebar.beginRename(row.id, row.label) { saved += it }
        sidebar.view(row.id)!!.findViewWithTag<TextField>("stem-rename").setText("Cancelled")
        sidebar.finishRename(keep = false)
        sidebar.beginRename(row.id, row.label) { saved += it }
        sidebar.view(row.id)!!.findViewWithTag<TextField>("stem-rename").setText("Removed")
        sidebar.apply(emptyList())
        assertTrue(saved.isEmpty())
        assertFalse(sidebar.isEditing)
    }

    @Test fun theEditedRowStaysAboveTheKeyboardAsTheBottomInsetChanges() {
        val sidebar = sidebar()
        val rows = (0..25).map { row.copy(id = "row-$it", nodeId = "node-$it") }
        sidebar.apply(rows)
        sidebar.layout(0, 0, 1080, 1800)
        val last = rows.last()
        sidebar.beginRename(last.id, last.label) {}
        sidebar.list.setInsets(100.0, 350.0)
        sidebar.revealRename()
        val bottom = sidebar.view(last.id)!!.frames!!.box.maxY
        val viewportBottom = sidebar.list.contentOffset + com.slopus.happy.ui.view.px(sidebar.list.height) - sidebar.list.insetBottom
        assertTrue(bottom + 24 <= viewportBottom)
        sidebar.finishRename(keep = false)
    }
}