package com.slopus.happy.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from happy-ios Tests/HappyCoreTests/ChangesSupportTests.swift (DiffFileItemTests).

class DiffFileItemTest {
    private val patch = "--- a/a.ts\n+++ b/a.ts\n@@ -1 +1 @@\n-old\n+new\n"

    private fun DiffFileItem.built() = body(source?.build() ?: DiffDocument.Empty)

    @Test fun bodyFollowsFileSectionOrder() {
        var item = DiffFileItem("a.ts", FileChangeKind.Modified, 1, 1, source = DiffSource.Patch(patch), error = "boom", message = "msg")
        assertEquals(DiffFileItem.Body.Error("boom"), item.built())
        item = item.copy(error = null)
        assertEquals(DiffFileItem.Body.Message("msg"), item.built())
        item = item.copy(message = null, image = DiffImages(null, "data:image/png;base64,AQ=="))
        assertEquals(DiffFileItem.Body.Image(DiffImages(null, "data:image/png;base64,AQ==")), item.body(DiffDocument.Empty))
        item = item.copy(image = null)
        val files = item.built() as DiffFileItem.Body.Files
        assertEquals(1, files.files.size)
        item = item.copy(source = null)
        assertEquals(DiffFileItem.Body.Loading, item.body(DiffDocument.Empty))
    }

    @Test fun contentsWithoutTextRowsSayNoChanges() {
        val same = DiffFileItem("a.ts", FileChangeKind.Modified, 0, 0, source = DiffSource.Contents("a.ts", "x\n", "x\n"))
        assertEquals(DiffFileItem.Body.NoChanges, same.built())
        val empty = DiffFileItem("a.ts", FileChangeKind.Modified, 0, 0, source = DiffSource.Patch(""))
        assertEquals(DiffFileItem.Body.NoChanges, empty.built())
    }

    @Test fun budgetErrorsAreShown() {
        val old = (0 until 400).joinToString("\n") { "a$it" }
        val new = (0 until 400).joinToString("\n") { "b$it" }
        val item = DiffFileItem("a.ts", FileChangeKind.Modified, 0, 0, source = DiffSource.Contents("a.ts", old, new, diffBudget = DiffBudget(10_000, 10)))
        assertEquals(DiffFileItem.Body.Error("Diff is too large to render on this device."), item.built())
    }

    @Test fun collapseRules() {
        val small = DiffFileItem("a", FileChangeKind.Modified, 1000, 1000)
        val big = DiffFileItem("b", FileChangeKind.Modified, 1500, 501)
        assertFalse(DiffFilesCollapse.isCollapsed(small, null, false))
        assertTrue(DiffFilesCollapse.isCollapsed(small, null, true))
        assertTrue(DiffFilesCollapse.isCollapsed(big, null, false))
        assertFalse(DiffFilesCollapse.isCollapsed(big, false, true))
        assertTrue(DiffFilesCollapse.showsSizeHint(big, true))
        assertFalse(DiffFilesCollapse.showsSizeHint(small, true))
        assertFalse(DiffFilesCollapse.showsSizeHint(big, false))
    }

    @Test fun needsContentOnlyWhenOpenAndBodiless() {
        var item = DiffFileItem("a", FileChangeKind.Modified, 0, 0)
        assertTrue(DiffFilesCollapse.needsContent(item, false))
        assertFalse(DiffFilesCollapse.needsContent(item, true))
        item = item.copy(error = "x")
        assertFalse(DiffFilesCollapse.needsContent(item, false))
        item = item.copy(error = null, message = "m")
        assertFalse(DiffFilesCollapse.needsContent(item, false))
        item = item.copy(message = null, source = DiffSource.Patch(""))
        assertFalse(DiffFilesCollapse.needsContent(item, false))
    }
}
