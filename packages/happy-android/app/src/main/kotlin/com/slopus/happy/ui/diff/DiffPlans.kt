package com.slopus.happy.ui.diff

import android.text.TextPaint
import com.slopus.happy.diff.DiffDocument
import com.slopus.happy.diff.DiffFile
import com.slopus.happy.diff.DiffLayout
import com.slopus.happy.diff.DiffLineType
import com.slopus.happy.diff.DiffRow
import com.slopus.happy.diff.DiffSource
import com.slopus.happy.diff.DiffSpan
import com.slopus.happy.diff.SpanEmphasis
import com.slopus.happy.diff.SpanKind
import com.slopus.happy.diff.syntax.DiffSyntax
import com.slopus.happy.diff.syntax.SyntaxService
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.DiffPalette
import com.slopus.happy.layout.DiffTextStyles
import com.slopus.happy.layout.FontFace
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.diff.DiffChunkLayout
import com.slopus.happy.layout.diff.DiffFileLayout
import com.slopus.happy.layout.diff.DiffMetrics
import com.slopus.happy.ui.text.TextLayouts
import java.util.concurrent.ConcurrentHashMap

// happy-ios HappyUI/Diff (DiffStyle.swift, DiffChunkView.swift and
// DiffFileView.swift's plans): a diff built, coloured and measured off the
// main thread, ready for the views to draw.

/**
 * components/diff/DiffPalette.ts and the rows' type: the theme's diff
 * colours by line type and span kind, and the mono styles at a font size.
 */
object DiffStyle {
    fun rowBackground(type: DiffLineType) = when (type) {
        DiffLineType.Add -> DiffPalette.rowAdded
        DiffLineType.Del -> DiffPalette.rowRemoved
        DiffLineType.Ctx -> DiffPalette.rowContext
    }

    fun gutterBackground(type: DiffLineType) = when (type) {
        DiffLineType.Add -> DiffPalette.gutterAdded
        DiffLineType.Del -> DiffPalette.gutterRemoved
        DiffLineType.Ctx -> DiffPalette.gutterContext
    }

    /** The +/− colour; a context line's (empty) marker takes the number's. */
    fun marker(type: DiffLineType) = when (type) {
        DiffLineType.Add -> DiffPalette.markerAdded
        DiffLineType.Del -> DiffPalette.markerRemoved
        DiffLineType.Ctx -> DiffPalette.lineNumber
    }

    fun syntax(kind: SpanKind) = when (kind) {
        SpanKind.Plain -> DiffPalette.Syntax.plain
        SpanKind.Keyword -> DiffPalette.Syntax.keyword
        SpanKind.Str -> DiffPalette.Syntax.string
        SpanKind.Comment -> DiffPalette.Syntax.comment
        SpanKind.Number -> DiffPalette.Syntax.number
        SpanKind.Function -> DiffPalette.Syntax.function
        SpanKind.Operator -> DiffPalette.Syntax.operator
        SpanKind.Punctuation -> DiffPalette.Syntax.punctuation
        SpanKind.Type -> DiffPalette.Syntax.type
        SpanKind.Variable -> DiffPalette.Syntax.variable
        SpanKind.Tag -> DiffPalette.Syntax.tag
        SpanKind.Attr -> DiffPalette.Syntax.attr
    }

    fun wordBackground(emphasis: SpanEmphasis): ColorToken? = when (emphasis) {
        SpanEmphasis.None -> null
        SpanEmphasis.Inserted -> DiffPalette.wordAdded
        SpanEmphasis.Deleted -> DiffPalette.wordRemoved
    }

    /** A code row: mono at the font size on the row's line (both scaled by the env). */
    fun code(metrics: DiffMetrics) = TextStyle(FontFace.Mono, metrics.fontSize, designedLine(metrics))

    /** Line numbers and hunk rows: a point smaller on the same line. */
    fun small(metrics: DiffMetrics) = TextStyle(FontFace.Mono, metrics.fontSize - 1, designedLine(metrics))

    /** "Binary file not shown" / "No changes" rows: Typography.default(). */
    fun message(metrics: DiffMetrics) = TextStyle(FontFace.Regular, metrics.fontSize, designedLine(metrics))

    /** `Math.round(fontSize * 1.5)`, which the env scales. */
    private fun designedLine(metrics: DiffMetrics) = Math.floor(metrics.fontSize * 1.5 + 0.5)

    private val advances = ConcurrentHashMap<Pair<Double, Float>, Double>()

    /**
     * DiffMetrics.tsx's MonoProbe: the mono font's real advance at the size
     * drawn, measured once per drawn size over 50 "M"s and kept.
     */
    fun metrics(env: TextLayouts.Env, fontSize: Double = DiffMetrics.DEFAULT_FONT_SIZE): DiffMetrics {
        val base = DiffMetrics(fontSize, env.scale, env.density.toDouble())
        val drawn = base.drawnFontSize
        val advance = advances.getOrPut(drawn to env.density) {
            val paint = TextLayouts.paint(TextStyle(FontFace.Mono, fontSize), env, 0)
            val width = paint.measureText("M".repeat(50)) / 50.0 / env.density
            // A broken measurement (font not loaded) keeps the 0.6 em estimate.
            if (DiffMetrics.acceptsAdvance(width, drawn)) width else -1.0
        }
        return if (advance > 0) DiffMetrics(fontSize, env.scale, env.density.toDouble(), advance) else base
    }
}

/** The words a diff shows, read where the plan is made. */
interface DiffWords {
    fun showMoreLines(count: Int): String
    fun unchangedLines(count: Int): String
    val binaryFile: String
    val noChanges: String
}

/** components/diff/DiffChunk.tsx's input: a patch, or the text before and after (a patch wins). */
data class DiffChunkSource(
    val patch: String? = null,
    val oldText: String? = null,
    val newText: String? = null,
    /** Names the language; "file.txt" when null. */
    val fileName: String? = null,
    val contextLines: Int = 3,
) {
    val source: DiffSource?
        get() {
            if (!patch.isNullOrEmpty()) return DiffSource.Patch(patch)
            if (oldText == null && newText == null) return null
            return DiffSource.Contents(fileName ?: "file.txt", oldText ?: "", newText ?: "", contextLines)
        }

    /** The diff, built once per source (DiffDocumentCache). */
    fun document(): DiffDocument {
        val source = source ?: return DiffDocument.Empty
        return documents.getOrPut(this) { source.build() }
    }

    private companion object {
        val documents: MutableMap<DiffChunkSource, DiffDocument> = java.util.Collections.synchronizedMap(
            object : LinkedHashMap<DiffChunkSource, DiffDocument>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DiffChunkSource, DiffDocument>?) = size > 64
            },
        )
    }
}

/** DiffFileView.tsx's props, less the callbacks. */
data class DiffViewOptions(
    val fontSize: Double = DiffMetrics.DEFAULT_FONT_SIZE,
    val showLineNumbers: Boolean = true,
    /** Draw the `@@` separators between hunks. */
    val showHunkHeaders: Boolean = true,
    /** Rows drawn before "Show N more lines". */
    val collapseAfter: Int = 400,
    val paddingVertical: Double = 0.0,
    /**
     * Whether "N unchanged" separators can be tapped: only owners that can
     * fetch a wider diff (`onExpandContext`) say yes.
     */
    val canExpandContext: Boolean = false,
    /** Long press offers the platform Copy menu for a code line, off in chat tool diffs. */
    val selectable: Boolean = false,
)

/** One run of a code line: where it sits in the row (px from the text's start) and how it is coloured. */
class DiffRun(val start: Int, val end: Int, val x: Float, val width: Float, val color: ColorToken, val background: ColorToken?)

/** What one row draws, worked out off the main thread. */
sealed interface DiffRowDraw {
    class Line(
        val type: DiffLineType,
        val text: String,
        val runs: List<DiffRun>,
        val number: String?,
        val marker: String,
    ) : DiffRowDraw

    /** [expandable]: an "N unchanged" separator a tap widens. */
    class Hunk(val head: String, val section: String, val expandable: Boolean = false) : DiffRowDraw

    class Message(val text: String) : DiffRowDraw
}

/**
 * One file's diff, measured and ready to draw: the rows on show after the
 * collapse, syntax-coloured, every frame. Immutable.
 */
class DiffFilePlan(
    val file: DiffFile,
    width: Double,
    val env: TextLayouts.Env,
    val words: DiffWords,
    val options: DiffViewOptions = DiffViewOptions(),
    val expanded: Boolean = false,
    syntaxDeadlineNanos: Long? = DiffChunkPlan.syntaxDeadline(),
) {
    val metrics: DiffMetrics = DiffStyle.metrics(env, options.fontSize)
    val hiddenCount: Int
    val rows: List<DiffRowDraw>
    val layout: DiffFileLayout
    val showMoreText: String

    init {
        val selection = DiffLayout.selectVisibleRows(file.rows, options.showHunkHeaders, expanded, options.collapseAfter)
        hiddenCount = selection.hiddenCount
        val shown = syntaxDeadlineNanos?.let { SyntaxService.Shared.colour(file, selection.rows, it) } ?: selection.rows
        val paint = TextLayouts.paint(DiffStyle.code(metrics), env, 0)
        rows = shown.map { row -> draw(row, paint) }
        showMoreText = words.showMoreLines(hiddenCount)
        layout = DiffFileLayout(
            DiffFileLayout.Input(
                width = width,
                metrics = metrics,
                rowCount = rows.size,
                hiddenCount = hiddenCount,
                maxColumns = file.maxColumns,
                maxLineNo = file.maxLineNo,
                showLineNumbers = options.showLineNumbers,
                paddingVertical = options.paddingVertical,
                showMoreTextHeight = TextLayouts.lineHeight(DiffTextStyles.showMore, env),
            ),
        )
    }

    val height: Double get() = layout.height

    /** Native DiffRow.selectableText: whole displayed line, not its gutter or visible slice. */
    fun selectableText(y: Double): String? {
        if (!options.selectable) return null
        val index = layout.rowIndex(y - layout.canvas.minY) ?: return null
        return (rows[index] as? DiffRowDraw.Line)?.text?.ifEmpty { " " }
    }

    private fun draw(row: DiffRow, paint: TextPaint): DiffRowDraw = when (row) {
        is DiffRow.Line -> {
            val spans = row.spans.ifEmpty { listOf(DiffSpan(" ", SpanKind.Plain, SpanEmphasis.None)) }
            val text = StringBuilder()
            val runs = ArrayList<DiffRun>(spans.size)
            var x = 0f
            for (span in spans) {
                val start = text.length
                text.append(span.text)
                val width = paint.measureText(span.text)
                runs += DiffRun(start, text.length, x, width, DiffStyle.syntax(span.kind), DiffStyle.wordBackground(span.emphasis))
                x += width
            }
            val number = if (options.showLineNumbers) (if (row.type == DiffLineType.Del) row.oldNo else row.newNo)?.toString() else null
            val marker = when (row.type) {
                DiffLineType.Add -> "+"
                DiffLineType.Del -> "−"
                DiffLineType.Ctx -> ""
            }
            DiffRowDraw.Line(row.type, text.toString(), runs, number, marker)
        }
        is DiffRow.Hunk -> {
            val mark = if (options.canExpandContext) "⤢" else "⋯"
            DiffRowDraw.Hunk(row.hidden?.let { "$mark ${words.unchangedLines(it)}" } ?: row.text, row.section, options.canExpandContext && row.hidden != null)
        }
        is DiffRow.Message -> DiffRowDraw.Message(if (row.code == DiffRow.Message.Code.Binary) words.binaryFile else words.noChanges)
    }
}

/**
 * The chat-sized diff (DiffChunk.tsx), built and measured: each file's plan,
 * stacked. DiffChunk's defaults: line numbers on, 80 rows before "show
 * more", hunk separators only when a file has more than one hunk.
 */
class DiffChunkPlan(
    val source: DiffChunkSource,
    width: Double,
    env: TextLayouts.Env,
    words: DiffWords,
    showLineNumbers: Boolean = true,
    /** Files the reader opened with "show more", by index. */
    val expanded: Set<Int> = emptySet(),
    syntaxDeadlineNanos: Long? = syntaxDeadline(),
) {
    val files: List<DiffFilePlan> = source.document().files.mapIndexed { index, file ->
        val hunks = file.rows.count { it is DiffRow.Hunk }
        val options = DiffViewOptions(showLineNumbers = showLineNumbers, showHunkHeaders = hunks > 1, collapseAfter = DiffChunkLayout.COLLAPSE_AFTER)
        DiffFilePlan(file, width, env, words, options, index in expanded, syntaxDeadlineNanos)
    }
    val layout = DiffChunkLayout(files.map { it.layout.input })

    /** Nothing to draw (DiffChunk renders null). */
    val isEmpty: Boolean get() = files.isEmpty()

    val size: Size get() = layout.size

    companion object {
        /** When a plan made now stops starting colour work: the first paint's second (`SYNTAX_REVEAL_MS`). */
        fun syntaxDeadline(): Long = System.nanoTime() + DiffSyntax.REVEAL_MS * 1_000_000
    }
}

/** The surface a diff sits on. */
internal val DiffSurface get() = Palette.surface
