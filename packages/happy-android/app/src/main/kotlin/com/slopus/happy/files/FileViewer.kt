package com.slopus.happy.files

import com.slopus.happy.chat.markdown.SyntaxToken
import com.slopus.happy.model.GitStatusParser
import com.slopus.happy.sync.SessionFileCache
import kotlinx.coroutines.CancellationException
import kotlin.math.max

/**
 * app/(app)/session/[id]/file.tsx without the drawing (happy-ios
 * `FileViewer`): which file, how it is read and decided binary, its
 * uncommitted diff, and which view opens.
 */
object FileViewer {
    /** A read file: its text, or binary with no text. */
    data class Content(val content: String, val isBinary: Boolean)

    /** The cache's entry, and a load's result. [diff]: `git diff` for the file, when it has uncommitted changes. */
    data class Snapshot(val content: Content, val diff: String?)

    sealed interface LoadResult {
        data class Loaded(val snapshot: Snapshot) : LoadResult
        /** The file couldn't be read. [diff] arrived before that, if any. */
        data class Failed(val failure: Failure, val diff: String?) : LoadResult
    }

    sealed interface Failure {
        /** readFile said no, with its error when it gave one ("Failed to read file" otherwise). */
        data class Read(val message: String?) : Failure
        /** The call itself threw ("Failed to load file"). */
        data object Load : Failure
    }

    enum class DisplayMode { File, Diff }

    /** The file the screen shows, resolved against the session root. */
    data class Target(
        val filePath: String,
        /** Relative to the root, for `git diff`; null outside it. */
        val gitDiffPath: String?,
        val fileName: String,
    ) {
        companion object {
            operator fun invoke(rawPath: String, sessionPath: String?): Target {
                val resolved = SessionFilePaths.resolve(rawPath, sessionPath)
                val filePath = resolved?.absolutePath ?: rawPath
                val last = filePath.split('/').last()
                return Target(
                    filePath = filePath,
                    gitDiffPath = if (resolved?.withinSessionRoot == true) resolved.relativePath else null,
                    fileName = last.ifEmpty { filePath },
                )
            }
        }
    }

    /** The header's path, with the requested line and column. */
    fun headerPath(filePath: String, line: Int?, column: Int?): String {
        if (line == null || line <= 0) return filePath
        if (column != null && column > 0) return "$filePath:$line:$column"
        return "$filePath:$line"
    }

    /** The file's last extension, lower-cased (`split('.').pop()`). */
    private fun fileExtension(path: String) = path.split('.').last().lowercase()

    /** `getFileLanguage`: the highlighter's language for a path. */
    fun language(path: String): String? = when (fileExtension(path)) {
        "js", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "py" -> "python"
        "html", "htm" -> "html"
        "css" -> "css"
        "json" -> "json"
        "md" -> "markdown"
        "xml" -> "xml"
        "yaml", "yml" -> "yaml"
        "sh", "bash" -> "bash"
        "sql" -> "sql"
        "go" -> "go"
        "rust", "rs" -> "rust"
        "java" -> "java"
        "c" -> "c"
        "cpp", "cc", "cxx" -> "cpp"
        "php" -> "php"
        "rb" -> "ruby"
        "swift" -> "swift"
        "kt" -> "kotlin"
        else -> null
    }

    private val binaryExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "bmp", "svg", "ico",
        "mp4", "avi", "mov", "wmv", "flv", "webm",
        "mp3", "wav", "flac", "aac", "ogg",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "zip", "tar", "gz", "rar", "7z",
        "exe", "dmg", "deb", "rpm",
        "woff", "woff2", "ttf", "otf",
        "db", "sqlite", "sqlite3",
    )

    /** `isBinaryFile`: files never read, by extension. */
    fun isBinaryPath(path: String) = fileExtension(path) in binaryExtensions

    /**
     * readFile's base64 as the screen shows it: binary when it doesn't
     * decode, holds a NUL byte, or more than a tenth of it is control
     * characters other than tab, newline and return.
     */
    fun decode(base64: String): Content {
        val bytes = JsBase64.decode(base64) ?: return Content("", true)
        val text = JsBase64.utf8(bytes)
        val nonPrintable = text.count { it.code < 32 && it.code != 9 && it.code != 10 && it.code != 13 }
        // 0 / 0 is NaN in JavaScript, and NaN > 0.1 is false.
        val isBinary = bytes.any { it.toInt() == 0 } || (text.isNotEmpty() && nonPrintable.toDouble() / text.length > 0.1)
        return Content(if (isBinary) "" else text, isBinary)
    }

    /**
     * `git diff --no-ext-diff -- "path"`, for changes not yet staged. The
     * path is quoted for the shell, where file.tsx wraps it in bare quotes.
     */
    fun gitDiffCommand(relativePath: String) = "git diff --no-ext-diff -- ${GitStatusParser.quoteShellPath(relativePath)}"

    /**
     * file.tsx's `loadFile`: binary extensions are never read; otherwise
     * the diff (within the root) and then the file itself.
     */
    suspend fun load(target: Target, sessionPath: String?, ops: SessionFileOps, sessionId: String): LoadResult {
        if (isBinaryPath(target.filePath)) return LoadResult.Loaded(Snapshot(Content("", true), null))
        var diff: String? = null
        val gitPath = target.gitDiffPath
        if (sessionPath != null && gitPath != null && gitPath != ".") {
            val response = try {
                ops.bash(sessionId, gitDiffCommand(gitPath), sessionPath, 5000)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (response != null && response.success && response.stdout.trim().isNotEmpty()) diff = response.stdout
        }
        return try {
            val response = ops.readFile(sessionId, target.filePath)
            val content = response.content
            // An empty file reads as "" and fails here too, as in file.tsx.
            if (!response.success || content.isNullOrEmpty()) {
                LoadResult.Failed(Failure.Read(response.error?.takeIf { it.isNotEmpty() }), diff)
            } else {
                LoadResult.Loaded(Snapshot(decode(content), diff))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LoadResult.Failed(Failure.Load, diff)
        }
    }

    /**
     * [load], keeping a fresh read in the account's [cache] (file.tsx's
     * `applyFileCache`); a failed one leaves the kept copy as it was.
     */
    suspend fun load(target: Target, sessionPath: String?, ops: SessionFileOps, sessionId: String, cache: SessionFileCache): LoadResult {
        val result = load(target, sessionPath, ops, sessionId)
        if (result is LoadResult.Loaded) cache.set(sessionId, target.filePath, result.snapshot)
        return result
    }

    /**
     * The view the screen opens on: the file when a line was asked for,
     * else the diff when there is one, else the file once it has loaded.
     */
    fun displayMode(requestedLine: Int?, hasDiff: Boolean, hasContent: Boolean, current: DisplayMode): DisplayMode = when {
        requestedLine != null && requestedLine > 0 -> DisplayMode.File
        hasDiff -> DisplayMode.Diff
        hasContent -> DisplayMode.File
        else -> current
    }

    /** What the scrolling content holds, in file.tsx's order. */
    enum class Body {
        /** DiffDisplay. */
        Diff,
        /** SimpleSyntaxHighlighter over the file. */
        Code,
        /** "File is empty". */
        FileEmpty,
        /** "No changes to display". */
        NoChanges,
        /** Nothing: the diff was asked for and there is none, but the file has text. */
        None;

        companion object {
            fun of(mode: DisplayMode, diff: String?, content: Content?): Body {
                val hasDiff = !diff.isNullOrEmpty()
                val hasText = !content?.content.isNullOrEmpty()
                return when {
                    mode == DisplayMode.Diff && hasDiff -> Diff
                    mode == DisplayMode.File && hasText -> Code
                    mode == DisplayMode.File && content != null -> FileEmpty
                    !hasDiff && !hasText -> NoChanges
                    else -> None
                }
            }
        }
    }

    /** Where a requested line scrolls to: two lines above it, at 20 a line. */
    fun scrollOffset(line: Int): Double = max(0.0, (line - 1) * 20.0 - 40)

    /** How DiffDisplay draws a line of `git diff` output. */
    enum class DiffLineKind {
        Added, Removed, Hunk, FileHeader, Context;

        /** Added and removed lines carry a 3pt bar on the left. */
        val hasBar: Boolean get() = this == Added || this == Removed
        /** Hunk and file headers are semibold. */
        val isBold: Boolean get() = this == Hunk || this == FileHeader

        companion object {
            fun of(line: String): DiffLineKind = when {
                line.startsWith("+") && !line.startsWith("+++") -> Added
                line.startsWith("-") && !line.startsWith("---") -> Removed
                line.startsWith("@@") -> Hunk
                line.startsWith("+++") || line.startsWith("---") -> FileHeader
                else -> Context
            }
        }
    }

    data class DiffLine(val text: String, val kind: DiffLineKind)

    /**
     * DiffDisplay's rows: `split('\n')`, an empty line drawn as a space.
     * A CRLF line's carriage return is left off the text it draws.
     */
    fun diffLines(diff: String): List<DiffLine> = diff.split('\n').map { line ->
        val shown = line.removeSuffix("\r")
        DiffLine(shown.ifEmpty { " " }, DiffLineKind.of(line))
    }

    /**
     * SimpleSyntaxHighlighter's tokens for the whole file (or the file as
     * one plain token), split into its lines (`split('\n')`) so each can be
     * measured and drawn on its own. The one Text block wraps each line in
     * turn, so this draws the same. A CRLF line's carriage return, which
     * ends the paragraph with the newline there, is left off.
     */
    fun codeLines(tokens: List<SyntaxToken>): List<List<SyntaxToken>> {
        val lines = mutableListOf<MutableList<SyntaxToken>>(mutableListOf())
        for (token in tokens) {
            for ((index, part) in token.text.split('\n').withIndex()) {
                if (index > 0) lines.add(mutableListOf())
                if (part.isNotEmpty()) lines.last().add(token.copy(text = part))
            }
        }
        for (line in lines) {
            val last = line.lastOrNull() ?: continue
            if (!last.text.endsWith('\r')) continue
            val text = last.text.dropLast(1)
            if (text.isEmpty()) line.removeAt(line.lastIndex) else line[line.lastIndex] = last.copy(text = text)
        }
        return lines
    }
}

/** The browser's `atob` and `TextDecoder`, as file.tsx reads a file. */
object JsBase64 {
    /**
     * `atob` (forgiving-base64 decode): ASCII whitespace is ignored, one or
     * two trailing `=` are dropped when the length is a multiple of four,
     * and anything else outside the alphabet, or a length of 4n + 1,
     * fails. Leftover bits are discarded.
     */
    fun decode(string: String): ByteArray? {
        val chars = StringBuilder(string.length)
        for (c in string) if (c != '\t' && c != '\n' && c != '\u000C' && c != '\r' && c != ' ') chars.append(c)
        if (chars.length % 4 == 0) repeat(2) { if (chars.endsWith('=')) chars.setLength(chars.length - 1) }
        if (chars.length % 4 == 1) return null
        val output = java.io.ByteArrayOutputStream(chars.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in chars) {
            val value = sextet(c) ?: return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.write((buffer shr bits) and 0xFF)
            }
        }
        return output.toByteArray()
    }

    private fun sextet(c: Char): Int? = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> null
    }

    /** `new TextDecoder().decode(bytes)`: UTF-8, malformed sequences replaced, a leading byte order mark dropped. */
    fun utf8(bytes: ByteArray): String {
        val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        return if (bom) String(bytes, 3, bytes.size - 3, Charsets.UTF_8) else String(bytes, Charsets.UTF_8)
    }
}
