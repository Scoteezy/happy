package com.slopus.happy.ui.chat

import android.content.res.Resources
import android.text.TextUtils
import com.slopus.happy.R
import com.slopus.happy.chat.ChatMessage
import com.slopus.happy.chat.ToolState
import com.slopus.happy.chat.tools.*
import com.slopus.happy.diff.DiffStats
import com.slopus.happy.diff.FileChangeKind
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.*
import com.slopus.happy.layout.diff.DiffFileHeaderLayout
import com.slopus.happy.ui.diff.DiffChunkSource
import com.slopus.happy.ui.diff.DiffHeaderPlan
import com.slopus.happy.ui.diff.DiffSectionWords

/** Frozen HappyUI ChatToolRenderer's patch/turn-diff views, shared with full detail. */
internal class ChatPatchRenderer(private val resources: Resources, private val rows: ChatRowRenderer) {
    private val words = DiffSectionWords.of(resources)

    fun source(file: ToolPatchFile): DiffChunkSource? = when (val diff = file.diff) {
        is PatchInput.Patch -> DiffChunkSource(patch = diff.patch, fileName = file.fileName)
        is PatchInput.Pair -> DiffChunkSource(oldText = diff.oldText, newText = diff.newText, fileName = file.fileName)
        null -> null
    }

    fun patch(message: ChatMessage.ToolCallMessage, input: ChatRowInput, flush: Boolean): ChatStackEntry {
        val style = input.style
        val env = style.env
        val error = if (message.tool.state == ToolState.Error) {
            ToolFullPresentation.resultText(message.tool.result)?.let { rows.toolError(it, env) }
        } else null
        val files = patchViewFiles(message.tool.input, style.root)
        if (files == null) return fallback(message.tool.input, env, error)
        val entries = arrayListOf<ChatStackEntry>()
        error?.let(entries::add)
        files.forEachIndexed { index, file ->
            val parts = arrayListOf(ChatStackEntry(header(file.path, file.kind, file.stats, file.movePath,
                ChatRowAction.OpenMessage(message.id, file.file), env)))
            source(file)?.let { source ->
                rows.diffPiece(source, index, message.id, env, style.showLineNumbers, input.diffExpanded[index].orEmpty())
                    ?.let { parts += ChatStackEntry(it) }
            }
            val container = ChatStackEntry(ChatPiece.stack(parts), background = ChatBox(Rect.Zero, Palette.surface))
            entries += rows.fullWidthSection(ChatPiece.stack(listOf(container)), flush, env)
        }
        return ChatStackEntry(ChatPiece.stack(entries))
    }

    fun unified(message: ChatMessage.ToolCallMessage, input: ChatRowInput, flush: Boolean): ChatStackEntry? {
        val diff = ToolUnifiedDiff.of(message.tool.input) ?: return null
        val env = input.style.env
        val parts = arrayListOf<ChatStackEntry>()
        diff.fileName?.takeIf { it.isNotEmpty() }?.let { name ->
            parts += ChatStackEntry(header(name, FileChangeKind.Modified, DiffStats.countPatch(diff.patch), null, null, env))
        }
        rows.diffPiece(DiffChunkSource(patch = diff.patch, fileName = diff.fileName), 0, message.id, env,
            input.style.showLineNumbers, input.diffExpanded[0].orEmpty())?.let { parts += ChatStackEntry(it) }
        return if (parts.isEmpty()) null else rows.fullWidthSection(ChatPiece.stack(parts), flush, env)
    }

    fun fallback(input: kotlinx.serialization.json.JsonElement?, env: ChatTextEnv, error: ChatStackEntry? = null): ChatStackEntry {
        val parts = arrayListOf<ChatStackEntry>()
        error?.let(parts::add)
        patchFallbackText(input)?.takeIf { it.isNotEmpty() }?.let { parts += ChatStackEntry(rows.codeView(it, env)) }
        return rows.section(resources.getString(R.string.tools_names_apply_changes), ChatPiece.stack(parts), env)
    }

    fun header(path: String, kind: FileChangeKind, stats: DiffStats.Counts, movePath: String?,
        opens: ChatRowAction?, env: ChatTextEnv, topRadius: Double = 0.0): ChatPiece {
        val m = DiffFileHeaderLayout.Metrics
        val chevronSize = 14.0
        val reserve = if (opens == null) 0.0 else m.gap + chevronSize
        val move = movePath?.let { MeasuredText(ChatStrings.string(it, Palette.textSecondary),
            ChatTextStyles.patchMovePath, env, maxLines = 1, ellipsize = TextUtils.TruncateAt.START) }
        fun plan(width: Double) = DiffHeaderPlan(path, kind, stats.additions, stats.deletions, false,
            maxOf(0.0, width - reserve), env, words, toggles = false)
        fun moveHeight(width: Double) = move?.let {
            it.size(maxOf(0.0, width - 2 * m.paddingHorizontal)).height + m.paddingVertical
        } ?: 0.0
        return ChatPiece(Measurable { width -> Size(width, plan(width).layout.size.height + moveHeight(width)) }) { frame ->
            val header = plan(frame.width)
            val headerFrame = Rect(frame.minX, frame.minY, header.layout.size.width, header.layout.size.height)
            val box = Rect(frame.minX, frame.minY, frame.width, headerFrame.height + moveHeight(frame.width))
            val items = arrayListOf<ChatDrawItem>(
                ChatDrawItem.Box(ChatBox(box, DiffPalette.hunkBackground, radius = topRadius, topCornersOnly = true)),
                ChatDrawItem.DiffHeader(header, path, headerFrame, opens),
            )
            if (opens != null) items += ChatDrawItem.Glyph(Icon.octicons("chevron-right"), chevronSize,
                Rect(frame.maxX - m.paddingHorizontal - chevronSize, headerFrame.midY - chevronSize / 2, chevronSize, chevronSize), Palette.textSecondary)
            if (move != null) {
                val moveFrame = Rect(frame.minX + m.paddingHorizontal, headerFrame.maxY,
                    maxOf(0.0, frame.width - 2 * m.paddingHorizontal), box.height - headerFrame.height - m.paddingVertical)
                items += ChatDrawItem.Text(move.layout(moveFrame), moveFrame)
            }
            items to (opens?.let { listOf(ChatHit(box, it)) } ?: emptyList())
        }
    }

    /** Full-view cards retain the original file index when only one file is focused. */
    fun fullPatch(files: List<ToolPatchFile>?, call: ChatMessage.ToolCallMessage, input: MessageDetailInput): ChatStackEntry {
        if (files == null) return fallback(call.tool.input, input.env)
        val focused = files.indices.filter { files[it].file == input.focusFile }
        val shown = focused.ifEmpty { files.indices.toList() }
        val cards = shown.map { index ->
            val file = files[index]
            ChatStackEntry(card(file, source(file), index, call.id, input) ?: ChatPiece.stack(emptyList()))
        }
        return fullCardEntry(ChatPiece.stack(cards, gap = 16.0))
    }

    fun fullUnified(diff: ToolUnifiedDiff?, toolId: String, input: MessageDetailInput): ChatStackEntry? {
        diff ?: return null
        val header = diff.fileName?.takeIf { it.isNotEmpty() }?.let { name ->
            ToolPatchFile(name, name, null, FileChangeKind.Modified, PatchInput.Patch(diff.patch))
        }
        return card(header, DiffChunkSource(patch = diff.patch, fileName = diff.fileName), 0, toolId, input)?.let(::fullCardEntry)
    }

    private fun fullCardEntry(piece: ChatPiece) = ChatStackEntry(piece, marginBottom = 28.0, padding = LayoutInsets(0.0, 12.0, 0.0, 12.0))

    private fun card(file: ToolPatchFile?, source: DiffChunkSource?, index: Int, toolId: String, input: MessageDetailInput): ChatPiece? {
        val parts = arrayListOf<ChatStackEntry>()
        file?.let { parts += ChatStackEntry(header(it.path, it.kind, it.stats, it.movePath, null, input.env, topRadius = 7.0)) }
        source?.let {
            rows.diffPiece(it, index, toolId, input.env, input.showLineNumbers, input.diffExpanded[index].orEmpty(),
                radius = 7.0, bottomCornersOnly = file != null)?.let { piece -> parts += ChatStackEntry(piece) }
        }
        if (parts.isEmpty()) return null
        return ChatPiece.stack(listOf(ChatStackEntry(ChatPiece.stack(parts), padding = LayoutInsets(1.0, 1.0, 1.0, 1.0),
            background = ChatBox(Rect.Zero, Palette.surface, Palette.divider, 1.0, 8.0))))
    }
}