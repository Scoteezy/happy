package com.slopus.happy.chat.tools

import com.slopus.happy.diff.DiffStats
import com.slopus.happy.diff.FileChangeKind
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** HappyCore ChatDisplayTests: view files, raw fallback, and turn-diff file names. */
class ToolPatchFilesTest {
    @Test fun normalizesSortsAndMaterializesThePatchFiles() {
        val files = patchViewFiles(Json.parseToJsonElement("""{"changes":{
            "/repo/src/a.ts":{"kind":{"type":"update"},"unified_diff":"@@ -1 +1 @@\n-x\n+y"},
            "/repo/b.ts":{"kind":{"type":"add"},"diff":"hello"},
            "/repo/c.ts":{"kind":{"type":"update","move_path":"/repo/d.ts"},"modify":{"old_content":"","new_content":""}},
            "/elsewhere/e.ts":{"type":"delete","unified_diff":"@@ -1 +0,0 @@\n-gone"}
        }}"""), "/repo")!!
        assertEquals(listOf("/elsewhere/e.ts", "b.ts", "c.ts", "src/a.ts"), files.map { it.path })
        assertEquals(listOf(FileChangeKind.Deleted, FileChangeKind.Added, FileChangeKind.Renamed, FileChangeKind.Modified), files.map { it.kind })
        assertEquals(PatchInput.Patch("--- a//elsewhere/e.ts\n+++ /dev/null\n@@ -1 +0,0 @@\n-gone"), files[0].diff)
        assertEquals(PatchInput.Pair("", "hello"), files[1].diff)
        assertNull(files[2].diff)
        assertEquals("d.ts", files[2].movePath)
        assertEquals(PatchInput.Patch("--- a//repo/src/a.ts\n+++ b//repo/src/a.ts\n@@ -1 +1 @@\n-x\n+y"), files[3].diff)
        assertEquals("a.ts", files[3].fileName)
        assertEquals("/repo/src/a.ts", files[3].file)
        assertEquals(listOf(DiffStats.Counts(0, 1), DiffStats.Counts(1, 0), DiffStats.Counts(0, 0), DiffStats.Counts(1, 1)), files.map { it.stats })
    }

    @Test fun listAndEnvelopeKeepTheirOrderAndFileIdentity() {
        val list = Json.parseToJsonElement("""{"changes":[{"path":"z","type":"add","content":"z"},{"path":"a","type":"add","content":"a"}]}""")
        assertEquals(listOf("z", "a"), patchViewFiles(list, null)!!.map { it.file })
        val envelope = Json.parseToJsonElement("""{"workdir":"/repo","input":"*** Begin Patch\n*** Update File: src/a\n*** Move to: src/b\n-old\n+new\n*** End Patch"}""")
        val file = patchViewFiles(envelope, "/repo")!!.single()
        assertEquals("/repo/src/a", file.file)
        assertEquals("src/b", file.movePath)
        assertEquals(FileChangeKind.Renamed, file.kind)
    }

    @Test fun malformedPatchUsesTheOriginalRawText() {
        val input = Json.parseToJsonElement("""{"patch":"not an envelope"}""")
        assertNull(patchViewFiles(input, null))
        assertEquals("not an envelope", patchFallbackText(input))
        assertEquals("raw", patchFallbackText(Json.parseToJsonElement("""{"input":"raw"}""")))
        assertEquals("{\n  \"changes\": {}\n}", patchFallbackText(Json.parseToJsonElement("""{"changes":{}}""")))
    }

    @Test fun primaryListWinsOverSecondaryMapAndSkipsEmptyPaths() {
        val input = Json.parseToJsonElement("""{"changes":[{"path":"z","type":"add","content":"z"},{"path":"","type":"add","content":"skip"},{"path":"a","type":"add","content":"a"}],"fileChanges":{"other":{"type":"add","content":"other"}}}""")
        assertEquals(listOf("z", "a"), patchViewFiles(input, null)!!.map { it.file })
    }

    @Test fun turnDiffUsesExplicitPathThenItsHeaderAndRejectsEmptyInput() {
        fun diff(input: String) = ToolUnifiedDiff.of(Json.parseToJsonElement(input))
        assertEquals("x.swift", diff("""{"unified_diff":"--- a/x.swift\n+++ b/x.swift\n@@ -1 +1 @@\n-a\n+b"}""")?.fileName)
        assertEquals("/w/y.swift", diff("""{"unified_diff":"+++ b/x.swift","path":"/w/y.swift"}""")?.fileName)
        assertEquals("z.swift", diff("""{"unified_diff":"@@ -1 +1 @@\n-a\n+b","filePath":"z.swift"}""")?.fileName)
        assertNull(diff("""{"unified_diff":"@@ -1 +1 @@\n-a\n+b"}""")?.fileName)
        assertNull(diff("""{"unified_diff":""}"""))
        assertNull(diff("{}"))
    }
}