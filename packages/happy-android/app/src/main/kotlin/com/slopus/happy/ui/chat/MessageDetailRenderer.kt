package com.slopus.happy.ui.chat

import android.content.res.Resources
import com.slopus.happy.R
import com.slopus.happy.chat.ChatMessage
import com.slopus.happy.chat.tools.ToolDiffChunk
import com.slopus.happy.chat.tools.ToolFullError
import com.slopus.happy.chat.tools.ToolFullGeneric
import com.slopus.happy.chat.tools.ToolFullPresentation
import com.slopus.happy.chat.tools.ToolFullTerminal
import com.slopus.happy.chat.tools.ToolText
import com.slopus.happy.chat.tools.ToolViewKind
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.boxErrorBackground
import com.slopus.happy.layout.boxErrorBorder
import com.slopus.happy.layout.boxErrorText
import com.slopus.happy.layout.chat.ChatAccessoryRowLayout
import com.slopus.happy.layout.chat.ChatStackLayout
import com.slopus.happy.layout.chat.MessageDetailLayout
import com.slopus.happy.layout.chat.MultiEditHeaderLayout
import com.slopus.happy.layout.chat.TextChunks
import com.slopus.happy.layout.fullViewGreen
import com.slopus.happy.layout.fullViewOrange
import com.slopus.happy.layout.fullViewPurple
import com.slopus.happy.layout.fullViewRed
import com.slopus.happy.layout.multiEditSeparator
import com.slopus.happy.layout.onAccent
import com.slopus.happy.layout.terminalCommand
import com.slopus.happy.layout.terminalEmptyOutput
import com.slopus.happy.layout.terminalError
import com.slopus.happy.layout.terminalStderr
import com.slopus.happy.layout.terminalStdout
import android.text.SpannableStringBuilder
import android.text.Spanned
import com.slopus.happy.diff.DiffSpan
import com.slopus.happy.diff.SpanKind
import com.slopus.happy.diff.syntax.SyntaxService
import com.slopus.happy.ui.diff.DiffChunkPlan
import com.slopus.happy.ui.diff.DiffChunkSource
import com.slopus.happy.ui.diff.DiffStyle

/** Everything a message's screen is drawn from. Equal inputs at the same width give the same plan. */
data class MessageDetailInput(
    val message: ChatMessage,
    /** metadata.path: tool paths under it are shown relative to it. */
    val root: String?,
    /** `settings.showLineNumbersInToolViews`, which WriteView follows. */
    val showLineNumbers: Boolean,
    /** `devModeEnabled || __DEV__`: the raw JSON section. */
    val devMode: Boolean,
    /** The files the reader opened past "Show N more lines", by diff. */
    val diffExpanded: Map<Int, Set<Int>>,
    val maxContentWidth: Double,
    val env: ChatTextEnv,
    val focusFile: String? = null,
)

/**
 * session/[id]/message/[messageId].tsx's content as one plan: a tool's
 * ToolFullView, or a text message's whole text. Long text comes in pieces
 * ([TextChunks]) so the screen draws only what is near the visible part.
 * Ported from happy-ios Chat/MessageDetail/MessageDetailRenderer.swift.
 */
class MessageDetailRenderer(private val resources: Resources, private val rows: ChatRowRenderer) {
    private val patches = ChatPatchRenderer(resources, rows)
    val toolText = object : ToolText {
        override fun string(id: Int, vararg args: Any): String = resources.getString(id, *args)
        override fun plural(id: Int, count: Int, vararg args: Any): String = resources.getQuantityString(id, count, *args)
    }

    fun plan(input: MessageDetailInput, width: Double): ChatRowPlan {
        val env = input.env
        val (content, kind) = when (val message = input.message) {
            is ChatMessage.ToolCallMessage -> {
                val presentation = ToolFullPresentation.of(message.tool, input.root, toolText, message.children)
                tool(message, presentation, input) to if (presentation.wide) MessageDetailLayout.Kind.WideTool else MessageDetailLayout.Kind.Tool
            }
            is ChatMessage.UserText -> longText(message.text, ChatTextStyles.detailMessage, Palette.text, env) to MessageDetailLayout.Kind.Text
            is ChatMessage.AgentText -> longText(message.text, ChatTextStyles.detailMessage, Palette.text, env) to MessageDetailLayout.Kind.Text
            else -> return ChatRowPlan(input.message.id, Size(width, 0.0), emptyList())
        }
        val layout = MessageDetailLayout(width, kind, content.measurable, input.maxContentWidth)
        val (items, hits) = content.draw(layout.content)
        return ChatRowPlan(input.message.id, layout.contentSize, items, hits)
    }

    // ToolFullView

    /**
     * The tool's specialized full view or the generic sections, then (for a
     * specialized view that shows the inputs) how the run ended, then in dev
     * mode the raw JSON.
     */
    private fun tool(call: ChatMessage.ToolCallMessage, presentation: ToolFullPresentation, input: MessageDetailInput): ChatPiece {
        val env = input.env
        val entries = ArrayList<ChatStackEntry>()
        when (val main = presentation.main) {
            is ToolFullPresentation.Main.Terminal -> {
                // BashViewFull: the command and all it printed, then what a shell control typed, 16 apart.
                val parts = arrayListOf(ChatStackEntry(command(main.terminal, env)))
                main.controlInput?.let { parts += rows.section(resources.getString(R.string.tool_view_input), code(it, env), env) }
                entries += ChatStackEntry(ChatPiece.stack(parts, gap = 16.0))
            }
            ToolFullPresentation.Main.Task -> rows.taskView(call, env)?.let { entries += it }
            is ToolFullPresentation.Main.Diff -> diffs(main.kind, main.chunks, main.replaceAll, call.id, input)?.let { entries += it }
            is ToolFullPresentation.Main.Patch -> entries += patches.fullPatch(main.files, call, input)
            is ToolFullPresentation.Main.UnifiedDiff -> patches.fullUnified(main.diff, call.id, input)?.let { entries += it }
            is ToolFullPresentation.Main.Generic -> entries += sections(main.generic, env)
        }
        when (val outcome = presentation.outcome) {
            is ToolFullPresentation.Outcome.Error -> entries += rows.toolError(message(outcome.error), env)
            is ToolFullPresentation.Outcome.Output -> entries += rows.section(resources.getString(R.string.tools_full_view_output), code(outcome.output, env), env)
            null -> Unit
        }
        if (input.devMode) {
            val json = com.slopus.happy.chat.prettyToolJson(ToolFullPresentation.rawJSON(call))
            entries += section(Icon.ionicons("code-slash"), Palette.fullViewOrange, resources.getString(R.string.tools_full_view_raw_json_dev_mode), code(json, env), env)
        }
        return ChatPiece.stack(entries)
    }

    private fun message(error: ToolFullError): String = error.text ?: resources.getString(R.string.tools_full_view_error)

    /**
     * CommandView on the tool's screen: the command, then everything it
     * printed, in pieces when it is long. The command and its output copy on
     * a long press and are coloured (`syntaxHighlighting`), the command as
     * bash and the output as a shell session, all tokenized by the deadline
     * or left plain; the error is neither.
     */
    private fun command(terminal: ToolFullTerminal, env: ChatTextEnv): ChatPiece {
        val deadline = DiffChunkPlan.syntaxDeadline()
        val syntax = SyntaxService.Shared
        val commandSpans = if (terminal.prompt) syntax.codeSpans(terminal.command, "bash", deadline) else null
        val commandText = commandSpans?.let { terminalString(it, Palette.terminalCommand) } ?: ChatStrings.string(terminal.command, Palette.terminalCommand)
        val line = rows.commandLine(commandText, terminal.prompt, env)
        val entries = arrayListOf(ChatStackEntry(selectable(line, terminal.command)))
        fun output(text: String, style: TextStyle, color: ColorToken, terminalText: Boolean) {
            val spans = if (terminalText) syntax.codeSpans(text, OUTPUT_LANGUAGE, deadline) else null
            val piece = longText(text, style, color, env, spans)
            entries += ChatStackEntry(if (terminalText) selectable(piece, text) else piece, marginTop = 8.0)
        }
        terminal.visibleStdout?.let { output(it, ChatTextStyles.terminal, Palette.terminalStdout, true) }
        terminal.visibleStderr?.let { output(it, ChatTextStyles.terminal, Palette.terminalStderr, true) }
        terminal.error?.let { output(message(it), ChatTextStyles.terminal, Palette.terminalError, false) }
        if (terminal.showsEmptyOutput) {
            output(resources.getString(R.string.chat_command_no_output), ChatTextStyles.terminalEmptyOutput, Palette.terminalEmptyOutput, false)
        }
        return rows.terminalPanel(entries)
    }

    /**
     * SyntaxText's runs on CommandView's panel, which stays dark: tokens in
     * the dark theme's diff colours whatever the appearance, plain runs in
     * the text's own colour.
     */
    private fun terminalString(spans: List<DiffSpan>, color: ColorToken): CharSequence {
        val builder = SpannableStringBuilder()
        for (span in spans) {
            val start = builder.length
            builder.append(span.text)
            val token = if (span.kind == SpanKind.Plain) color else DiffStyle.syntax(span.kind).let { ColorToken(it.dark, it.dark) }
            builder.setSpan(TokenColorSpan(token), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return builder
    }

    /** React Native's `selectable` Text: a long press anywhere on it copies all of [text]. */
    private fun selectable(piece: ChatPiece, text: String): ChatPiece = ChatPiece(piece.measurable) { frame ->
        val (items, hits) = piece.draw(frame)
        items to hits + ChatHit(frame, ChatRowAction.CopyMenu(text), hold = true)
    }

    /**
     * EditViewFull and MultiEditViewFull draw their diffs with line numbers
     * and leave 28 below; WriteView follows the setting and sits in
     * ToolSectionView's full-width section, pulled 8 up, 12 below.
     */
    private fun diffs(kind: ToolViewKind, chunks: List<ToolDiffChunk>, replaceAll: List<Boolean>, toolId: String, input: MessageDetailInput): ChatStackEntry? {
        fun diff(index: Int, showLineNumbers: Boolean): ChatPiece? {
            val chunk = chunks[index]
            return rows.diffPiece(
                DiffChunkSource(oldText = chunk.oldText, newText = chunk.newText, fileName = chunk.fileName),
                index, toolId, input.env, showLineNumbers, input.diffExpanded[index].orEmpty(),
            )
        }
        return when (kind) {
            ToolViewKind.MultiEdit -> {
                val entries = ArrayList<ChatStackEntry>()
                for (index in chunks.indices) {
                    entries += ChatStackEntry(multiEditHeader(index, chunks.size, replaceAll.getOrElse(index) { false }, input.env), marginBottom = 8.0)
                    diff(index, showLineNumbers = true)?.let { entries += ChatStackEntry(it) }
                    if (index < chunks.size - 1) {
                        val rule = ChatPiece(Measurable { Size(it, 1.0) }) { frame -> listOf<ChatDrawItem>(ChatDrawItem.Box(ChatBox(frame, Palette.multiEditSeparator))) to emptyList() }
                        entries += ChatStackEntry(rule, marginTop = 16.0, marginBottom = 16.0)
                    }
                }
                if (entries.isEmpty()) null else ChatStackEntry(ChatPiece.stack(entries), marginBottom = 28.0)
            }
            // ToolSectionView's fullWidth also pulls 12 out at each side,
            // which on a phone (no padding here) would cut the gutter off.
            ToolViewKind.Write -> if (chunks.isEmpty()) null else diff(0, input.showLineNumbers)?.let { ChatStackEntry(it, marginTop = -8.0, marginBottom = 12.0) }
            else -> if (chunks.isEmpty()) null else diff(0, showLineNumbers = true)?.let { ChatStackEntry(it, marginBottom = 28.0) }
        }
    }

    /** "Edit N of M", with the "Replace All" badge beside it. */
    private fun multiEditHeader(index: Int, total: Int, replaceAll: Boolean, env: ChatTextEnv): ChatPiece {
        val number = MeasuredText(
            ChatStrings.string(resources.getString(R.string.tools_multi_edit_edit_number, index + 1, total), Palette.fullViewPurple),
            ChatTextStyles.multiEditNumber, env, maxLines = 1,
        )
        val badge = if (replaceAll) {
            MeasuredText(ChatStrings.string(resources.getString(R.string.tools_multi_edit_replace_all), Palette.onAccent), ChatTextStyles.multiEditBadge, env, maxLines = 1)
        } else {
            null
        }
        return ChatPiece(Measurable { MultiEditHeaderLayout(it, number.measurable, badge?.measurable).size }) { frame ->
            val layout = MultiEditHeaderLayout(frame.width, number.measurable, badge?.measurable)
            fun place(rect: Rect) = rect.offsetBy(frame.minX, frame.minY)
            val items = arrayListOf<ChatDrawItem>(ChatDrawItem.Text(number.layout(layout.number), place(layout.number)))
            val pill = layout.badge
            val text = layout.badgeText
            if (badge != null && pill != null && text != null) {
                items += ChatDrawItem.Box(ChatBox(place(pill), Palette.fullViewPurple, radius = MultiEditHeaderLayout.Metrics.badgeRadius))
                items += ChatDrawItem.Text(badge.layout(text), place(text))
            }
            items to emptyList()
        }
    }

    /** ToolFullView's fallback: what the call was for, its input, and its output or error, or a tick when it finished without saying anything. */
    private fun sections(generic: ToolFullGeneric, env: ChatTextEnv): List<ChatStackEntry> {
        val entries = ArrayList<ChatStackEntry>()
        generic.description?.let {
            val piece = longText(it, ChatTextStyles.fullDescription, Palette.textSecondary, env)
            entries += section(Icon.ionicons("information-circle"), Palette.fullViewPurple, resources.getString(R.string.tools_full_view_description), piece, env)
        }
        generic.input?.let {
            entries += section(Icon.ionicons("log-in"), Palette.fullViewPurple, resources.getString(R.string.tools_full_view_input_params), code(it, env), env)
        }
        generic.output?.let {
            entries += section(Icon.ionicons("log-out"), Palette.fullViewGreen, resources.getString(R.string.tools_full_view_output), code(it, env), env)
        }
        generic.error?.let {
            val box = ChatStackEntry(
                longText(it, ChatTextStyles.fullError, Palette.boxErrorText, env), padding = LayoutInsets(16, 16, 16, 16),
                background = ChatBox(Rect.Zero, Palette.boxErrorBackground, Palette.boxErrorBorder, 1.0, 8.0),
            )
            entries += section(Icon.ionicons("close-circle"), Palette.fullViewRed, resources.getString(R.string.tools_full_view_error), ChatPiece.stack(listOf(box)), env)
        }
        if (generic.completedWithoutOutput) {
            val icon = ChatPiece(Measurable.fixed(Size(48, 48))) { frame ->
                listOf<ChatDrawItem>(ChatDrawItem.Glyph(Icon.ionicons("checkmark-circle-outline"), 48.0, frame, Palette.fullViewGreen)) to emptyList()
            }
            val center = android.text.Layout.Alignment.ALIGN_CENTER
            val title = MeasuredText(ChatStrings.string(resources.getString(R.string.tools_full_view_completed), Palette.text), ChatTextStyles.fullEmptyTitle, env, alignment = center)
            val subtitle = MeasuredText(ChatStrings.string(resources.getString(R.string.tools_full_view_no_output), Palette.textSecondary), ChatTextStyles.fullEmptySubtitle, env, alignment = center)
            val column = ChatPiece.stack(
                listOf(
                    ChatStackEntry(icon, placement = ChatStackLayout.Placement.Center),
                    ChatStackEntry(ChatPiece.text(title), placement = ChatStackLayout.Placement.Center),
                    ChatStackEntry(ChatPiece.text(subtitle), placement = ChatStackLayout.Placement.Center),
                ),
                gap = 12.0,
            )
            entries += ChatStackEntry(column, marginBottom = 28.0, padding = LayoutInsets(48.0, 0.0, 48.0, 0.0))
        }
        return entries
    }

    /** One of ToolFullView's sections: a 20 glyph and its title 8 apart, 12 above the content; 28 below the section. */
    private fun section(icon: Icon, tint: ColorToken, title: String, content: ChatPiece, env: ChatTextEnv): ChatStackEntry {
        val text = MeasuredText(ChatStrings.string(title, Palette.text), ChatTextStyles.fullSectionTitle, env)
        val glyph = Size(20, 20)
        val header = ChatPiece(Measurable { ChatAccessoryRowLayout(it, glyph, text.measurable).size }) { frame ->
            val layout = ChatAccessoryRowLayout(frame.width, glyph, text.measurable)
            val textFrame = layout.text.offsetBy(frame.minX, frame.minY)
            listOf(
                ChatDrawItem.Glyph(icon, 20.0, layout.leading!!.offsetBy(frame.minX, frame.minY), tint),
                ChatDrawItem.Text(text.layout(textFrame), textFrame),
            ) to emptyList()
        }
        return ChatStackEntry(ChatPiece.stack(listOf(ChatStackEntry(header, marginBottom = 12.0), ChatStackEntry(content))), marginBottom = 28.0)
    }

    /** CodeView: mono 12/18 on surfaceHigh, in pieces when long. */
    private fun code(code: String, env: ChatTextEnv): ChatPiece = ChatPiece.stack(
        listOf(
            ChatStackEntry(
                longText(code, ChatTextStyles.codeView, Palette.text, env), padding = LayoutInsets(12, 12, 12, 12),
                background = ChatBox(Rect.Zero, Palette.surfaceHigh, radius = 6.0),
            ),
        ),
    )

    /**
     * Text that can run to thousands of lines, as pieces stacked with no gap;
     * short text is one piece. With [syntax], the text is drawn in those
     * spans' colours, each piece cut from the whole.
     */
    private fun longText(text: String, style: TextStyle, color: ColorToken, env: ChatTextEnv, syntax: List<DiffSpan>? = null): ChatPiece {
        val whole = syntax?.let { terminalString(it, color) }?.takeIf { it.length == text.length }
        val chunks = TextChunks.split(text)
        if (chunks.size <= 1) return ChatPiece.text(MeasuredText(whole ?: ChatStrings.string(text, color), style, env))
        if (whole == null) {
            return ChatPiece.stack(
                chunks.mapIndexed { index, chunk ->
                    val drawn = if (index == chunks.size - 1) chunk else TextChunks.drawn(chunk)
                    ChatStackEntry(ChatPiece.text(MeasuredText(ChatStrings.string(drawn, color), style, env)))
                },
            )
        }
        return ChatPiece.stack(
            TextChunks.cuts(chunks).map { cut ->
                val piece = SpannableStringBuilder(whole.subSequence(cut.location, cut.location + cut.length))
                if (cut.space) piece.append(ChatStrings.string(" ", color))
                ChatStackEntry(ChatPiece.text(MeasuredText(piece, style, env)))
            },
        )
    }

    companion object {
        /** TerminalSyntax.outputLanguage. */
        private const val OUTPUT_LANGUAGE = "shell-session"
    }
}
