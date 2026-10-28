package com.slopus.happy.files

import com.slopus.happy.chat.markdown.SyntaxToken
import com.slopus.happy.chat.markdown.SyntaxTokenType
import com.slopus.happy.chat.markdown.tokenizeCode
import com.slopus.happy.files.FileViewer.Content
import com.slopus.happy.files.FileViewer.DiffLineKind
import com.slopus.happy.files.FileViewer.DisplayMode
import com.slopus.happy.files.FileViewer.Failure
import com.slopus.happy.files.FileViewer.LoadResult
import com.slopus.happy.files.FileViewer.Snapshot
import com.slopus.happy.files.FileViewer.Target
import com.slopus.happy.sync.BashResult
import com.slopus.happy.sync.GitFile
import com.slopus.happy.sync.GitFileContent
import com.slopus.happy.sync.GitState
import com.slopus.happy.sync.ReadFileResult
import com.slopus.happy.sync.SessionFileCache
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

// Ported from happy-ios Tests/HappyCoreTests/FileViewerTests.swift.

/** file.tsx's loading, decoding and view choice. */
class FileViewerTest {
    private val root = "/Users/me/repo"

    @Test fun target() {
        val inside = Target("src/app.ts", root)
        assertEquals("/Users/me/repo/src/app.ts", inside.filePath)
        assertEquals("src/app.ts", inside.gitDiffPath)
        assertEquals("app.ts", inside.fileName)
        assertNull(Target("/etc/hosts", root).gitDiffPath)
        assertEquals("src/app.ts", Target("src/app.ts", null).filePath)
        assertEquals("/", Target("/", null).fileName)
    }

    @Test fun headerPath() {
        assertEquals("/a.ts", FileViewer.headerPath("/a.ts", null, 3))
        assertEquals("/a.ts", FileViewer.headerPath("/a.ts", 0, null))
        assertEquals("/a.ts:12", FileViewer.headerPath("/a.ts", 12, null))
        assertEquals("/a.ts:12:4", FileViewer.headerPath("/a.ts", 12, 4))
        assertEquals("/a.ts:12", FileViewer.headerPath("/a.ts", 12, 0))
    }

    @Test fun languageAndBinaryPaths() {
        assertEquals("typescript", FileViewer.language("a/b.TSX"))
        assertEquals("rust", FileViewer.language("x.rs"))
        assertNull(FileViewer.language("Makefile"))
        assertNull(FileViewer.language("notes.txt"))
        assertTrue(FileViewer.isBinaryPath("logo.PNG"))
        assertTrue(FileViewer.isBinaryPath("icon.svg"))
        assertFalse(FileViewer.isBinaryPath("a.ts"))
        // `split('.').pop()` of a name without a dot is the whole name.
        assertTrue(FileViewer.isBinaryPath("png"))
    }

    @Test fun atob() {
        val hi = "hi".toByteArray()
        assertArrayEquals(hi, JsBase64.decode("aGk="))
        assertArrayEquals(hi, JsBase64.decode("aGk"))
        assertArrayEquals(hi, JsBase64.decode(" aG\nk= "))
        assertArrayEquals("leftover bits are dropped", hi, JsBase64.decode("aGl="))
        assertNull(JsBase64.decode("aGk=="))
        assertNull(JsBase64.decode("aG="))
        assertNull(JsBase64.decode("a"))
        assertNull(JsBase64.decode("a-b_"))
        assertArrayEquals(ByteArray(0), JsBase64.decode(""))
        assertEquals("A\uFFFD", JsBase64.utf8(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x41, 0xFF.toByte())))
    }

    @Test fun decodeDecidesBinary() {
        fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
        assertEquals(Content("let x = 1\n", false), FileViewer.decode(b64("let x = 1\n".toByteArray())))
        assertEquals(Content("", true), FileViewer.decode(b64(byteArrayOf(0x41, 0, 0x42))))
        // 2 of 11 units are control characters: over a tenth.
        assertTrue(FileViewer.decode(b64("abcdefghi".toByteArray() + byteArrayOf(1, 2))).isBinary)
        // 1 of 11: not.
        assertFalse(FileViewer.decode(b64("abcdefghij".toByteArray() + byteArrayOf(1))).isBinary)
        assertFalse(FileViewer.decode(b64("\t\r\n".toByteArray())).isBinary)
        assertTrue(FileViewer.decode("%%%").isBinary)
    }

    @Test fun displayMode() {
        assertEquals(DisplayMode.File, FileViewer.displayMode(5, hasDiff = true, hasContent = true, current = DisplayMode.Diff))
        assertEquals(DisplayMode.Diff, FileViewer.displayMode(0, hasDiff = true, hasContent = true, current = DisplayMode.File))
        assertEquals(DisplayMode.File, FileViewer.displayMode(null, hasDiff = false, hasContent = true, current = DisplayMode.Diff))
        assertEquals(DisplayMode.Diff, FileViewer.displayMode(null, hasDiff = false, hasContent = false, current = DisplayMode.Diff))
        assertEquals(0.0, FileViewer.scrollOffset(1), 0.0)
        assertEquals(140.0, FileViewer.scrollOffset(10), 0.0)
    }

    @Test fun body() {
        val text = Content("x", false)
        val empty = Content("", false)
        assertEquals(FileViewer.Body.Diff, FileViewer.Body.of(DisplayMode.Diff, "+x", text))
        assertEquals(FileViewer.Body.Code, FileViewer.Body.of(DisplayMode.File, "+x", text))
        assertEquals(FileViewer.Body.FileEmpty, FileViewer.Body.of(DisplayMode.File, "+x", empty))
        assertEquals(FileViewer.Body.NoChanges, FileViewer.Body.of(DisplayMode.File, null, null))
        assertEquals(FileViewer.Body.NoChanges, FileViewer.Body.of(DisplayMode.Diff, null, empty))
        assertEquals(FileViewer.Body.None, FileViewer.Body.of(DisplayMode.Diff, null, text))
        assertEquals(FileViewer.Body.None, FileViewer.Body.of(DisplayMode.File, "+x", null))
    }

    @Test fun diffLines() {
        val lines = FileViewer.diffLines("diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n ctx\n")
        assertEquals(
            listOf(DiffLineKind.Context, DiffLineKind.FileHeader, DiffLineKind.FileHeader, DiffLineKind.Hunk, DiffLineKind.Removed, DiffLineKind.Added, DiffLineKind.Context, DiffLineKind.Context),
            lines.map { it.kind },
        )
        assertEquals(" ", lines.last().text)
        assertTrue(DiffLineKind.Added.hasBar)
        assertFalse(DiffLineKind.Hunk.hasBar)
        assertTrue(DiffLineKind.FileHeader.isBold)
        val crlf = FileViewer.diffLines("+a\r\n-\r\n")
        assertEquals(listOf("+a", "-", " "), crlf.map { it.text })
        assertEquals(listOf(DiffLineKind.Added, DiffLineKind.Removed, DiffLineKind.Context), crlf.map { it.kind })
    }

    @Test fun codeLines() {
        // Without a language the file is one plain token, split by line.
        fun codeLines(content: String, language: String?) = FileViewer.codeLines(tokenizeCode(content, language))
        val plain = SyntaxTokenType.Default
        assertEquals(listOf(listOf(SyntaxToken("a", plain)), emptyList(), listOf(SyntaxToken("b", plain)), emptyList()), codeLines("a\n\nb\n", null))
        val lines = codeLines("const x = f(1)\r\n// done", "typescript")
        assertEquals(2, lines.size)
        assertEquals("const x = f(1)", lines[0].joinToString("") { it.text })
        assertEquals(SyntaxToken("const", SyntaxTokenType.Keyword), lines[0].first())
        assertEquals(SyntaxToken("(", SyntaxTokenType.Bracket, 1), lines[0].first { it.type == SyntaxTokenType.Bracket })
        assertEquals(listOf(SyntaxToken("// done", SyntaxTokenType.Comment)), lines[1])
        // A line that is only a carriage return draws empty.
        assertEquals(listOf(listOf("a"), emptyList(), listOf("b")), codeLines("a\r\n\r\nb", null).map { line -> line.map { it.text } })
    }

    private class Ops(val diff: String, val success: Boolean = true, val read: ReadFileResult?) : SessionFileOps {
        val calls = mutableListOf<String>()
        override suspend fun bash(sessionId: String, command: String, cwd: String?, timeoutMs: Long): BashResult {
            calls += "bash $command cwd=${cwd ?: "-"} t=$timeoutMs"
            return BashResult(success, diff, "", 0)
        }
        override suspend fun readFile(sessionId: String, path: String): ReadFileResult {
            calls += "read $path"
            return read ?: throw IllegalStateException("no answer")
        }
        override suspend fun gitState(sessionId: String): GitState = throw UnsupportedOperationException()
        override suspend fun readGitFile(sessionId: String, base: String, file: GitFile): GitFileContent = throw UnsupportedOperationException()
    }

    @Test fun loadReadsDiffThenFile() = runTest {
        val content = ReadFileResult(true, Base64.getEncoder().encodeToString("x\n".toByteArray()))
        val ops = Ops("+x\n", read = content)
        val result = FileViewer.load(Target("src/a b.ts", root), root, ops, "s")
        assertEquals(LoadResult.Loaded(Snapshot(Content("x\n", false), "+x\n")), result)
        assertEquals(listOf("bash git diff --no-ext-diff -- \"src/a b.ts\" cwd=$root t=5000", "read $root/src/a b.ts"), ops.calls)
    }

    @Test fun loadSkipsBlankDiffsAndPathsOutsideTheRoot() = runTest {
        val content = ReadFileResult(true, "eA==")
        val loaded = LoadResult.Loaded(Snapshot(Content("x", false), null))
        assertEquals(loaded, FileViewer.load(Target("a.ts", root), root, Ops(" \n", read = content), "s"))
        val outside = Ops("+x", read = content)
        assertEquals(loaded, FileViewer.load(Target("/etc/hosts", root), root, outside, "s"))
        assertTrue(outside.calls.none { it.startsWith("bash") })
        assertEquals(loaded, FileViewer.load(Target("a.ts", root), root, Ops("+x", success = false, read = content), "s"))
    }

    @Test fun loadFailuresAndBinaryPaths() = runTest {
        val target = Target("a.ts", root)
        assertEquals(LoadResult.Failed(Failure.Read("denied"), "+x"), FileViewer.load(target, root, Ops("+x", read = ReadFileResult(false, null, "denied")), "s"))
        assertEquals(LoadResult.Failed(Failure.Read(null), null), FileViewer.load(target, root, Ops("", read = ReadFileResult(true, "")), "s"))
        assertEquals(LoadResult.Failed(Failure.Load, null), FileViewer.load(target, root, Ops("", read = null), "s"))
        val binary = Ops("+x", read = null)
        assertEquals(LoadResult.Loaded(Snapshot(Content("", true), null)), FileViewer.load(Target("logo.png", root), root, binary, "s"))
        assertTrue("binary extensions are never read", binary.calls.isEmpty())
    }

    @Test fun cache() = runTest {
        val cache = SessionFileCache()
        assertNull(cache.file("s", "$root/a.ts"))
        FileViewer.load(Target("a.ts", root), root, Ops("", read = ReadFileResult(true, "eA==")), "s", cache)
        assertEquals(Snapshot(Content("x", false), null), FileViewer.snapshot(cache.file("s", "$root/a.ts")!!))
        assertNull(cache.file("t", "$root/a.ts"))
        // A failed read keeps the copy.
        FileViewer.load(Target("a.ts", root), root, Ops("", read = ReadFileResult(false, null, "gone")), "s", cache)
        assertEquals("x", cache.file("s", "$root/a.ts")!!.content)
        cache.setRoot("s", SessionFileCache.Root(root))
        assertEquals(SessionFileCache.Root(root), cache.root("s"))
        cache.forgetSession("s")
        assertNull(cache.root("s"))
    }
}
