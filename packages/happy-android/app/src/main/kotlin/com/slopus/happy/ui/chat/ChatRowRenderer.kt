package com.slopus.happy.ui.chat

import android.content.res.Resources
import android.text.Layout
import com.slopus.happy.R
import com.slopus.happy.chat.AgentEvent
import com.slopus.happy.chat.ChatMessage
import com.slopus.happy.chat.ChatRow
import com.slopus.happy.chat.PermissionStatus
import com.slopus.happy.chat.ToolState
import com.slopus.happy.chat.UserDisplay
import com.slopus.happy.chat.markdown.MarkdownBlock
import com.slopus.happy.chat.markdown.SyntaxTokenType
import com.slopus.happy.chat.markdown.tokenizeCode
import com.slopus.happy.chat.obj
import com.slopus.happy.chat.str
import com.slopus.happy.chat.tools.ToolIcon
import com.slopus.happy.chat.tools.ToolStatusIcon
import com.slopus.happy.chat.tools.ToolText
import com.slopus.happy.chat.tools.answeredChoice
import com.slopus.happy.chat.tools.parseToolUseError
import com.slopus.happy.chat.tools.permissionOptionState
import com.slopus.happy.chat.tools.permissionOptions
import com.slopus.happy.chat.tools.presentTool
import com.slopus.happy.chat.tools.resultText
import com.slopus.happy.chat.tools.PermissionChoice
import com.slopus.happy.chat.tools.ToolDiffInputs
import com.slopus.happy.chat.tools.ToolPresentation
import com.slopus.happy.chat.tools.ToolViewKind
import com.slopus.happy.ui.diff.DiffChunkPlan
import com.slopus.happy.ui.diff.DiffChunkSource
import com.slopus.happy.ui.diff.DiffWords
import com.slopus.happy.chat.tools.PermissionOptionState
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.BubblePalette
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.FontFace
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.agentEventText
import com.slopus.happy.layout.boxErrorBackground
import com.slopus.happy.layout.boxErrorBorder
import com.slopus.happy.layout.boxErrorText
import com.slopus.happy.layout.boxWarningText
import com.slopus.happy.layout.chat.AgentTextLayout
import com.slopus.happy.layout.chat.ChatAccessoryRowLayout
import com.slopus.happy.layout.chat.ChatEventLayout
import com.slopus.happy.layout.chat.ChatFooterLayout
import com.slopus.happy.layout.chat.CompactToolRowLayout
import com.slopus.happy.layout.chat.MarkdownLayout
import com.slopus.happy.layout.chat.PermissionFooterLayout
import com.slopus.happy.layout.chat.ToolCardLayout
import com.slopus.happy.layout.chat.UserMessageLayout
import com.slopus.happy.layout.syntaxBracket1
import com.slopus.happy.layout.syntaxBracket2
import com.slopus.happy.layout.syntaxBracket3
import com.slopus.happy.layout.syntaxBracket4
import com.slopus.happy.layout.syntaxBracket5
import com.slopus.happy.layout.syntaxComment
import com.slopus.happy.layout.syntaxDefault
import com.slopus.happy.layout.syntaxFunction
import com.slopus.happy.layout.syntaxKeyword
import com.slopus.happy.layout.syntaxNumber
import com.slopus.happy.layout.syntaxString
import com.slopus.happy.layout.terminalCommand
import com.slopus.happy.layout.terminalError
import com.slopus.happy.layout.terminalPanel
import com.slopus.happy.layout.terminalPromptGreen
import com.slopus.happy.layout.userMessageText
import com.slopus.happy.layout.warning
import android.text.SpannableStringBuilder
import android.text.Spanned
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.text.DateFormat
import java.util.Date

/** The settings and session facts that change how every row looks. */
data class ChatRowStyle(
    val bubble: BubblePalette = BubblePalette.Gray,
    val compactTools: Boolean = false,
    val maxContentWidth: Double = Double.MAX_VALUE,
    /** metadata.path: tool paths under it are shown relative to it. */
    val root: String? = null,
    val flavor: String? = null,
    /** `settings.showLineNumbersInToolViews`. */
    val showLineNumbers: Boolean = false,
    val env: ChatTextEnv,
)

/** Everything one row is drawn from. Equal inputs at the same width give the same plan. */
data class ChatRowInput(
    val row: ChatRow,
    /** For a tool, whether the reader opened its body. */
    val expanded: Boolean = false,
    /** For an agent's message: copied a moment ago, so its button shows a tick. */
    val copied: Boolean = false,
    /** For a tool waiting on a permission: the button whose answer is being sent. */
    val permissionBusy: PermissionChoice? = null,
    /** For a tool's diffs: the files the reader opened in full, by diff. */
    val diffExpanded: Map<Int, Set<Int>> = emptyMap(),
    val style: ChatRowStyle,
)

/**
 * Builds a row's plan from its input (happy-ios `ChatRowRenderer`): measures
 * the text, places it with the pure layouts in :layout, and lists what to
 * draw. Runs off the main thread.
 */
class ChatRowRenderer(private val resources: Resources) {
    private val toolText = object : ToolText {
        override fun string(id: Int, vararg args: Any): String = resources.getString(id, *args)
        override fun plural(id: Int, count: Int, vararg args: Any): String = resources.getQuantityString(id, count, *args)
    }

    fun plan(input: ChatRowInput, width: Double): ChatRowPlan = when (val row = input.row) {
        is ChatRow.User -> userText(row, width, input.style)
        is ChatRow.AgentText -> agentText(row, input.copied, width, input.style)
        is ChatRow.Tool -> toolCall(row.message, input.expanded, input.permissionBusy, input.diffExpanded, width, input.style)
        is ChatRow.Event -> agentEvent(row.message, width, input.style)
    }

    /** ChatFooter, just past the newest message: padding only, or the "terminal has control" box. */
    fun footer(width: Double): ChatRowPlan = ChatRowPlan(FOOTER_ID, ChatFooterLayout(width, null).size, emptyList())

    // User messages

    private fun userText(row: ChatRow.User, width: Double, style: ChatRowStyle): ChatRowPlan {
        val message = row.message
        val palette = style.bubble
        val env = style.env
        val author = message.author?.takeIf { it.owner != true }
        val authorText = author?.let { MeasuredText(ChatStrings.string(it.name, Palette.textSecondary), ChatTextStyles.userAuthor, env, maxLines = 1) }

        var markdown: MarkdownPiece? = null
        var chipText: MeasuredText? = null
        var sentText: MeasuredText? = null
        val copyText: String
        val content: UserMessageLayout.Content = when (val display = row.display) {
            is UserDisplay.Goal -> {
                val piece = MarkdownPiece(display.blocks, Palette.userMessageText, env, selectable = false)
                sentText = MeasuredText(ChatStrings.string(resources.getString(R.string.message_sent_as_goal), Palette.textSecondary), ChatTextStyles.goalSent, env)
                markdown = piece
                copyText = display.goal
                UserMessageLayout.Content.Goal(piece.blocks, sentText.measurable)
            }
            is UserDisplay.Command -> {
                chipText = MeasuredText(ChatStrings.string("/${display.commandName}", Palette.userMessageText), ChatTextStyles.commandChip, env, maxLines = 1)
                val piece = display.args?.let { MarkdownPiece(display.argBlocks, Palette.userMessageText, env, selectable = false) }
                markdown = piece
                copyText = display.args?.let { "/${display.commandName} $it" } ?: "/${display.commandName}"
                UserMessageLayout.Content.Command(chipText.measurable, piece?.blocks)
            }
            is UserDisplay.Text -> {
                val piece = MarkdownPiece(display.blocks, Palette.userMessageText, env, selectable = false)
                markdown = piece
                copyText = display.text
                UserMessageLayout.Content.Text(piece.blocks)
            }
        }

        val layout = UserMessageLayout(UserMessageLayout.Input(width, style.maxContentWidth, authorText?.measurable, content))
        val items = ArrayList<ChatDrawItem>()
        val hits = ArrayList<ChatHit>()
        if (layout.author != null && authorText != null) items += ChatDrawItem.Text(authorText.layout(layout.author!!), layout.author!!)
        layout.bubble?.let { items += ChatDrawItem.Box(ChatBox(it, palette.background, palette.border, 1.0, UserMessageLayout.Metrics.bubbleRadius)) }
        val copyTarget = listOfNotNull(layout.bubble, layout.chip, layout.goalIcon, layout.goalLabel).reduceOrNull { a, b -> union(a, b) }
        if (copyTarget != null) hits += ChatHit(copyTarget, ChatRowAction.CopyMenu(copyText), hold = true)
        val placed = layout.markdown
        if (placed != null && markdown != null) {
            val (drawn, drawnHits) = markdown.draw(placed, layout.markdownOrigin.x, layout.markdownOrigin.y)
            items += drawn
            hits.addAll(0, drawnHits)
        }
        val chip = layout.chip
        val chipLabel = layout.chipLabel
        if (chip != null && chipLabel != null && chipText != null) {
            items += ChatDrawItem.Box(ChatBox(chip, palette.background, palette.border, UserMessageLayout.Metrics.chipBorder, UserMessageLayout.Metrics.chipRadius))
            items += ChatDrawItem.Text(chipText.layout(chipLabel), chipLabel)
        }
        val goalIcon = layout.goalIcon
        val goalLabel = layout.goalLabel
        if (goalIcon != null && goalLabel != null && sentText != null) {
            items += ChatDrawItem.Glyph(Icon.ionicons("locate-outline"), UserMessageLayout.Metrics.goalIconSize, goalIcon, Palette.textSecondary)
            items += ChatDrawItem.Text(sentText.layout(goalLabel), goalLabel)
        }

        // The status line is always there at one line's height, so a row
        // never changes height when a message settles.
        val failure = message.sendError
        val status = when {
            failure != null -> resources.getString(R.string.message_send_failed, failure)
            message.pending && message.meta?.queuedWhileBusy == true -> resources.getString(R.string.message_sends_after_this_turn)
            message.pending -> resources.getString(R.string.message_sending)
            else -> ""
        }
        if (status.isNotEmpty()) {
            val color = if (failure != null) Palette.statusError else Palette.textSecondary
            val line = MeasuredText(
                ChatStrings.string(status, color), ChatTextStyles.pendingStatus, style.env, maxLines = 1,
                alignment = if (layout.alignsLeft) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE,
            )
            items += ChatDrawItem.Text(line.layout(layout.status), layout.status)
            row.failedLocalId?.let { hits.add(0, ChatHit(layout.status, ChatRowAction.Retry(it))) }
        }
        return ChatRowPlan(
            message.id, layout.size, items, hits,
            alpha = if (message.pending && failure == null) UserMessageLayout.Metrics.pendingOpacity else 1.0,
            accessibilityLabel = listOfNotNull(author?.name, copyText, status.ifEmpty { null }).joinToString(", "),
        )
    }

    // Agent rows

    private fun agentText(row: ChatRow.AgentText, copied: Boolean, width: Double, style: ChatRowStyle): ChatRowPlan {
        val message = row.message
        val markdown = MarkdownPiece(row.blocks, Palette.text, style.env)
        val layout = AgentTextLayout(width, style.maxContentWidth, markdown.blocks)
        val (drawn, drawnHits) = markdown.draw(layout.markdown, layout.markdownOrigin.x, layout.markdownOrigin.y)
        val slop = AgentTextLayout.Metrics.copyHitSlop
        val button = layout.copyButton
        val copyTarget = Rect(button.minX - slop.left, button.minY - slop.top, button.width + slop.horizontal, button.height + slop.vertical)
        val icon = ChatDrawItem.Glyph(Icon.ionicons(if (copied) "checkmark" else "copy-outline"), AgentTextLayout.Metrics.copyIconSize, layout.copyIcon, Palette.text)
        return ChatRowPlan(
            message.id, layout.size, drawn + icon,
            listOf(ChatHit(copyTarget, ChatRowAction.CopyMessage(message.id, message.text))) + drawnHits,
            accessibilityLabel = message.text,
        )
    }

    private fun agentEvent(message: ChatMessage.Event, width: Double, style: ChatRowStyle): ChatRowPlan {
        val label = when (val event = message.event) {
            is AgentEvent.Switch -> resources.getString(R.string.message_switched_to_mode, event.mode)
            is AgentEvent.Message -> event.message
            is AgentEvent.LimitReached ->
                resources.getString(R.string.message_usage_limit_until, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.endsAt * 1000)))
            else -> resources.getString(R.string.message_unknown_event)
        }
        val text = MeasuredText(ChatStrings.string(label, Palette.agentEventText), ChatTextStyles.agentEvent, style.env, alignment = Layout.Alignment.ALIGN_CENTER)
        val layout = ChatEventLayout(width, style.maxContentWidth, text.measurable)
        return ChatRowPlan(message.id, layout.size, listOf(ChatDrawItem.Text(text.layout(layout.label), layout.label)), accessibilityLabel = label)
    }

    // Tool rows (ToolView.tsx)

    private fun toolCall(
        message: ChatMessage.ToolCallMessage, expanded: Boolean, busy: PermissionChoice?, diffExpanded: Map<Int, Set<Int>>, width: Double, style: ChatRowStyle,
    ): ChatRowPlan {
        val tool = message.tool
        val env = style.env
        val presentation = presentTool(message, toolText, style.compactTools, style.flavor, style.root)
            ?: return ChatRowPlan(message.id, Size(width, 0.0), emptyList())
        val form = when {
            presentation.compact -> Form.Compact
            tool.name == "file" -> Form.Inline
            else -> Form.Card
        }
        val awaitingDecision = tool.permission?.status == PermissionStatus.Pending
        val full = fullBody(message, presentation, style, diffExpanded, flush = form != Form.Compact)
        val body = when {
            form == Form.Inline || expanded -> full
            awaitingDecision -> full.filter { it.inputOnly }
            else -> emptyList()
        }.map { it.entry }.takeIf { it.isNotEmpty() }?.let { ChatPiece.stack(it) }
        val expandable = !awaitingDecision && full.isNotEmpty()
        val tap: ChatRowAction = if (expandable) ChatRowAction.ToggleTool(message.id) else ChatRowAction.OpenMessage(message.id)
        val footer = if (tool.permission != null && tool.name != "AskUserQuestion") permissionFooter(message, busy, style) else null
        val glyphSize = ToolCardLayout.Metrics.statusIconSize
        val glyph = presentation.statusIcon.takeIf { it != ToolStatusIcon.None }?.let { Size(glyphSize, glyphSize) }
        val elapsed = if (tool.state == ToolState.Running) elapsedSize(env) else null

        if (form == Form.Compact) {
            val label = MeasuredText(ChatStrings.string(presentation.activityLabel, Palette.textSecondary), ChatTextStyles.compactActivity, env, maxLines = 1)
            val layout = CompactToolRowLayout(
                width, style.maxContentWidth, label.measurable, elapsed?.let { Measurable.fixed(it) }, glyph, body?.measurable, footer?.measurable,
            )
            val items = arrayListOf(headerIcon(presentation.icon, presentation.iconSecondary, layout.icon), ChatDrawItem.Text(label.layout(layout.label), layout.label))
            layout.elapsed?.let { items += ChatDrawItem.Elapsed(tool.createdAt, it, env) }
            layout.statusIcon?.let { items += statusGlyph(presentation.statusIcon, it) }
            val hits = ArrayList<ChatHit>()
            val box = layout.bodyBox
            val bodyFrame = layout.body
            if (body != null && box != null && bodyFrame != null) {
                items += ChatDrawItem.Box(ChatBox(box, Palette.surfaceHigh, radius = CompactToolRowLayout.Metrics.bodyRadius))
                val (drawn, drawnHits) = body.draw(bodyFrame)
                items += drawn
                hits += drawnHits
            }
            if (footer != null) layout.footer?.let { frame ->
                val (drawn, drawnHits) = footer.draw(frame)
                items += drawn
                hits += drawnHits
            }
            hits += ChatHit(layout.header, tap)
            return ChatRowPlan(message.id, layout.size, items, hits, accessibilityLabel = presentation.activityLabel)
        }

        var header: ToolCardLayout.Header? = null
        var title: MeasuredText? = null
        var subtitle: MeasuredText? = null
        if (form == Form.Card) {
            val string = SpannableStringBuilder(ChatStrings.string(presentation.title, Palette.text))
            presentation.status?.let { status ->
                val start = string.length
                string.append(" $status")
                val size = ChatTextStyles.toolStatus.scaled(env.scale).size * env.density
                string.setSpan(FaceSpan(ChatTextStyles.toolStatus.face, size.toFloat()), start, string.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                string.setSpan(TokenColorSpan(Palette.text, 0.3f), start, string.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            title = MeasuredText(string, ChatTextStyles.toolName, env, maxLines = 1)
            subtitle = presentation.description?.let { MeasuredText(ChatStrings.string(it, Palette.textSecondary), ChatTextStyles.toolDescription, env, maxLines = 1) }
            header = ToolCardLayout.Header(title.measurable, subtitle?.measurable, elapsed?.let { Measurable.fixed(it) }, glyph)
        }
        val layout = ToolCardLayout(ToolCardLayout.Input(width, style.maxContentWidth, header, body?.measurable, footer?.measurable, form == Form.Inline))
        val card = layout.card
        fun place(frame: Rect) = frame.offsetBy(card.minX, card.minY)
        val items = ArrayList<ChatDrawItem>()
        var hits = ArrayList<ChatHit>()
        if (form == Form.Card) items += ChatDrawItem.Box(ChatBox(card, Palette.surfaceHigh, radius = ToolCardLayout.Metrics.radius))
        layout.header?.let { headerFrame ->
            // The card clips its header's top corners, and its bottom ones when nothing follows.
            val alone = headerFrame.height >= card.height
            items += ChatDrawItem.Box(ChatBox(place(headerFrame), Palette.surfaceHighest, radius = ToolCardLayout.Metrics.radius, topCornersOnly = !alone))
            hits += ChatHit(place(headerFrame), tap)
        }
        layout.icon?.let { items += headerIcon(presentation.icon, presentation.iconSecondary, place(it)) }
        if (title != null) layout.title?.let { items += ChatDrawItem.Text(title.layout(it), place(it)) }
        if (subtitle != null) layout.description?.let { items += ChatDrawItem.Text(subtitle.layout(it), place(it)) }
        layout.elapsed?.let { items += ChatDrawItem.Elapsed(tool.createdAt, place(it), env) }
        layout.statusIcon?.let { items += statusGlyph(presentation.statusIcon, place(it)) }
        if (body != null) layout.body?.let { frame ->
            val (drawn, drawnHits) = body.draw(place(frame))
            items += drawn
            hits = ArrayList(drawnHits + hits + if (form == Form.Inline) emptyList() else listOf(ChatHit(place(frame), ChatRowAction.OpenMessage(message.id))))
        }
        if (footer != null) layout.footer?.let { frame ->
            val (drawn, drawnHits) = footer.draw(place(frame))
            items += drawn
            hits = ArrayList(drawnHits + hits)
        }
        return ChatRowPlan(message.id, layout.size, items, hits, accessibilityLabel = listOfNotNull(presentation.title, presentation.description).joinToString(", "))
    }

    private enum class Form { Compact, Card, Inline }

    /** One part of a tool's body; [inputOnly] parts show while a decision holds the card open. */
    private class BodyPart(val entry: ChatStackEntry, val inputOnly: Boolean)

    /**
     * What the tool's body shows once opened: its own view where there is
     * one (a command), an error, or its raw input and output.
     */
    private fun fullBody(
        message: ChatMessage.ToolCallMessage, presentation: ToolPresentation, style: ChatRowStyle, diffExpanded: Map<Int, Set<Int>>, flush: Boolean,
    ): List<BodyPart> {
        val tool = message.tool
        val env = style.env
        val hideDefaultError = presentation.hideDefaultError
        val isToolUseError = presentation.isToolUseError
        val stopped = tool.permission?.status == PermissionStatus.Denied || tool.permission?.status == PermissionStatus.Canceled
        val errorText = if (tool.state == ToolState.Error && truthy(tool.result) && !stopped) resultText(tool.result) ?: pretty(tool.result) else null
        val command = if (tool.name == "Bash" || tool.name == "CodexBash") tool.input.obj?.get("command").let { it.str ?: (it as? JsonArray)?.joinToString(" ") { e -> e.str ?: "" } } else null
        if (command != null) {
            return listOf(BodyPart(section(null, commandView(command, errorText, env), env), inputOnly = true))
        }
        val view = presentation.view
        if (view in DIFF_VIEWS) {
            // The view, then its error when it failed.
            val error = if (hideDefaultError) null else errorText
            val diff = diffView(view!!, message, style, diffExpanded, flush)
            return listOfNotNull(diff?.let { BodyPart(it, inputOnly = true) }, error?.let { BodyPart(toolError(it, env), inputOnly = false) })
        }
        if (errorText != null && !isToolUseError && !hideDefaultError) return listOf(BodyPart(toolError(errorText, env), inputOnly = false))
        val parts = ArrayList<BodyPart>()
        if (truthy(tool.input)) parts += BodyPart(section(resources.getString(R.string.tool_view_input), codeView(pretty(tool.input), env), env), inputOnly = true)
        if (tool.state == ToolState.Completed && truthy(tool.result)) {
            val output = tool.result.str ?: pretty(tool.result)
            parts += BodyPart(section(resources.getString(R.string.tool_view_output), codeView(output, env), env), inputOnly = false)
        }
        return parts
    }

    private fun truthy(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonPrimitive -> value.content.isNotEmpty() && value.content != "false" && value.content != "0"
        is JsonObject -> true
        is JsonArray -> true
    }

    private fun pretty(value: JsonElement?): String = when (value) {
        null -> ""
        is JsonPrimitive -> value.content
        else -> PRETTY.encodeToString(JsonElement.serializer(), value)
    }

    private fun elapsedSize(env: ChatTextEnv): Size =
        MeasuredText(ChatStrings.string("000.0s", Palette.textSecondary), ChatTextStyles.toolElapsed, env, maxLines = 1).size(1000.0)

    private fun headerIcon(icon: ToolIcon, secondary: Boolean, frame: Rect): ChatDrawItem {
        val glyph = when (icon) {
            ToolIcon.Task -> Icon.octicons("rocket")
            ToolIcon.Terminal -> Icon.octicons("terminal")
            ToolIcon.Search -> Icon.octicons("search")
            ToolIcon.Read -> Icon.octicons("eye")
            ToolIcon.Edit -> Icon.octicons("file-diff")
            ToolIcon.Web -> Icon.ionicons("globe-outline")
            ToolIcon.Exit -> Icon.ionicons("exit-outline")
            ToolIcon.Todo -> Icon.ionicons("bulb-outline")
            ToolIcon.Reasoning -> Icon.octicons("light-bulb")
            ToolIcon.Question -> Icon.ionicons("help-circle-outline")
            ToolIcon.Mcp -> Icon.ionicons("extension-puzzle-outline")
            ToolIcon.Construct -> Icon.ionicons("construct-outline")
        }
        return ChatDrawItem.Glyph(glyph, 18.0, frame, if (secondary) Palette.textSecondary else Palette.text)
    }

    private fun statusGlyph(glyph: ToolStatusIcon, frame: Rect): ChatDrawItem = when (glyph) {
        ToolStatusIcon.Running -> ChatDrawItem.Spinner(frame, Palette.text)
        ToolStatusIcon.Removed -> ChatDrawItem.Glyph(Icon.ionicons("remove-circle-outline"), 20.0, frame, Palette.textSecondary)
        else -> ChatDrawItem.Glyph(Icon.ionicons("alert-circle-outline"), 20.0, frame, Palette.warning)
    }

    // Diff views

    private val diffWords = object : DiffWords {
        override fun showMoreLines(count: Int) = resources.getQuantityString(R.plurals.diff_show_more_lines, count, count)
        override fun unchangedLines(count: Int) = resources.getString(R.string.diff_unchanged_lines, count)
        override val binaryFile: String get() = resources.getString(R.string.diff_binary_file)
        override val noChanges: String get() = resources.getString(R.string.diff_no_changes)
    }

    /**
     * EditView, WriteView, MultiEditView and GeminiEditView: DiffChunk under a
     * full-width ToolSectionView (flush under the card's header, to its
     * edges), MultiEdit's diffs 8 apart. Null when no diff draws anything.
     */
    private fun diffView(kind: ToolViewKind, message: ChatMessage.ToolCallMessage, style: ChatRowStyle, expanded: Map<Int, Set<Int>>, flush: Boolean): ChatStackEntry? {
        val pieces = ToolDiffInputs.chunks(kind, message.tool.input).mapIndexedNotNull { index, chunk ->
            diffPiece(DiffChunkSource(oldText = chunk.oldText, newText = chunk.newText, fileName = chunk.fileName), index, message.id, style, expanded[index].orEmpty())
        }
        if (pieces.isEmpty()) return null
        return fullWidthSection(ChatPiece.stack(pieces.map { ChatStackEntry(it) }, gap = 8.0), flush, style.env)
    }

    /** ToolSectionView fullWidth: flush under the card's header and out to its edges. In a compact row's box, a plain section. */
    private fun fullWidthSection(piece: ChatPiece, flush: Boolean, env: ChatTextEnv): ChatStackEntry {
        if (!flush) return section(null, piece, env)
        val m = ToolCardLayout.Metrics
        return ChatStackEntry(piece, marginTop = -m.contentPaddingTop, marginBottom = 12.0, padding = LayoutInsets(0.0, -m.contentPaddingH, 0.0, -m.contentPaddingH))
    }

    /** One DiffChunk: diff [index] of tool [toolId], planned once per width it is measured at; null when it draws nothing. */
    private fun diffPiece(source: DiffChunkSource, index: Int, toolId: String, style: ChatRowStyle, expanded: Set<Int>): ChatPiece? {
        if (source.document().files.isEmpty()) return null
        val plans = ArrayList<Pair<Double, DiffChunkPlan>>(2)
        fun plan(width: Double): DiffChunkPlan = plans.firstOrNull { it.first == width }?.second
            ?: DiffChunkPlan(source, width, style.env, diffWords, style.showLineNumbers, expanded).also { plans += width to it }
        return ChatPiece(Measurable { plan(it).size }) { frame ->
            listOf(ChatDrawItem.Diff(plan(frame.width), frame, toolId, index)) to emptyList()
        }
    }

    /** ToolSectionView: an optional uppercase title, then the content, 12 below. */
    private fun section(title: String?, content: ChatPiece, env: ChatTextEnv): ChatStackEntry {
        val entries = ArrayList<ChatStackEntry>()
        if (title != null) {
            val label = MeasuredText(ChatStrings.string(title.uppercase(), Palette.textSecondary), ChatTextStyles.toolSectionTitle.copy(uppercase = false), env)
            entries += ChatStackEntry(ChatPiece.text(label), marginBottom = 6.0)
        }
        entries += ChatStackEntry(content)
        return ChatStackEntry(ChatPiece.stack(entries), marginBottom = 12.0)
    }

    /** CodeView: mono 12/18 on surfaceHigh, wrapped. */
    private fun codeView(code: String, env: ChatTextEnv): ChatPiece {
        val lines = MeasuredText(ChatStrings.string(code, Palette.text), ChatTextStyles.codeView, env)
        return ChatPiece.stack(
            listOf(ChatStackEntry(ChatPiece.text(lines), padding = LayoutInsets(12, 12, 12, 12), background = ChatBox(Rect.Zero, Palette.surfaceHigh, radius = 6.0))),
        )
    }

    /** CommandView: a dark terminal panel, "$ " then the command, then the error in red. */
    private fun commandView(command: String, error: String?, env: ChatTextEnv): ChatPiece {
        val text = MeasuredText(ChatStrings.string(command, Palette.terminalCommand), ChatTextStyles.terminalCommand, env)
        val prompt = MeasuredText(ChatStrings.string("$ ", Palette.terminalPromptGreen), ChatTextStyles.terminalPrompt, env, maxLines = 1)
        val line = ChatPiece(Measurable { width -> ChatAccessoryRowLayout(width, prompt.size(width), text.measurable, gap = 0.0, alignment = ChatAccessoryRowLayout.Alignment.Top).size }) { frame ->
            val layout = ChatAccessoryRowLayout(frame.width, prompt.size(frame.width), text.measurable, gap = 0.0, alignment = ChatAccessoryRowLayout.Alignment.Top)
            val promptFrame = layout.leading!!.offsetBy(frame.minX, frame.minY)
            val textFrame = layout.text.offsetBy(frame.minX, frame.minY)
            listOf(ChatDrawItem.Text(prompt.layout(Rect(0.0, 0.0, frame.width, promptFrame.height)), promptFrame), ChatDrawItem.Text(text.layout(textFrame), textFrame)) to emptyList()
        }
        val entries = arrayListOf(ChatStackEntry(line))
        if (error != null) {
            val errorText = MeasuredText(ChatStrings.string(error, Palette.terminalError), ChatTextStyles.terminal, env)
            entries += ChatStackEntry(ChatPiece.text(errorText), marginTop = 8.0)
        }
        return ChatPiece.stack(
            listOf(ChatStackEntry(ChatPiece.stack(entries), padding = LayoutInsets(16, 16, 16, 16), background = ChatBox(Rect.Zero, Palette.terminalPanel, radius = 8.0))),
        )
    }

    /** ToolError: the message in a red box, at most 115 tall; a refused call shows only its reason, after a warning sign. */
    private fun toolError(message: String, env: ChatTextEnv): ChatStackEntry {
        val reason = parseToolUseError(message)
        val shown = reason?.ifEmpty { null } ?: message
        val text = MeasuredText(ChatStrings.string(shown, Palette.boxErrorText), ChatTextStyles.toolError, env)
        val glyph = if (reason == null) null else Size(16, 16)
        val row = ChatPiece(Measurable { ChatAccessoryRowLayout(it, glyph, text.measurable, alignment = ChatAccessoryRowLayout.Alignment.Top).size }) { frame ->
            val layout = ChatAccessoryRowLayout(frame.width, glyph, text.measurable, alignment = ChatAccessoryRowLayout.Alignment.Top)
            val items = ArrayList<ChatDrawItem>()
            layout.leading?.let { items += ChatDrawItem.Glyph(Icon.ionicons("warning"), 16.0, it.offsetBy(frame.minX, frame.minY), Palette.boxWarningText) }
            val textFrame = layout.text.offsetBy(frame.minX, frame.minY)
            items += ChatDrawItem.Text(text.layout(textFrame), textFrame.copy(height = minOf(textFrame.height, frame.maxY - textFrame.minY)))
            items to emptyList()
        }
        return ChatStackEntry(
            row, marginBottom = 12.0, padding = LayoutInsets(12, 12, 12, 12), maxHeight = 115.0,
            background = ChatBox(Rect.Zero, Palette.boxErrorBackground, Palette.boxErrorBorder, 1.0, 6.0),
        )
    }

    /**
     * PermissionFooter: a column of outlined buttons, the answered one shaded,
     * the others faded. While an answer is on its way its button pulses and
     * none takes a tap.
     */
    private fun permissionFooter(message: ChatMessage.ToolCallMessage, busy: PermissionChoice?, style: ChatRowStyle): ChatPiece? {
        val tool = message.tool
        val permission = tool.permission ?: return null
        val options = permissionOptions(permission, tool.name, tool.input, style.flavor)
        val answered = answeredChoice(permission, tool.name, tool.input, style.flavor)
        val pending = permission.status == PermissionStatus.Pending
        val states = options.map { permissionOptionState(it, permission, answered) }
        val labels = options.mapIndexed { index, option ->
            val selected = states[index] == PermissionOptionState.Selected
            val textStyle = if (pending || selected) ChatTextStyles.permissionButton else ChatTextStyles.permissionButtonSettled
            MeasuredText(ChatStrings.string(resources.getString(option.label), Palette.text), textStyle, style.env, maxLines = option.maxLines, alignment = Layout.Alignment.ALIGN_CENTER)
        }
        val measurables = labels.map { it.measurable }
        return ChatPiece(Measurable { PermissionFooterLayout(it, measurables).size }) { frame ->
            val m = PermissionFooterLayout.Metrics
            val layout = PermissionFooterLayout(frame.width, measurables)
            val items = ArrayList<ChatDrawItem>()
            val hits = ArrayList<ChatHit>()
            options.forEachIndexed { index, option ->
                val box = layout.buttons[index].offsetBy(frame.minX, frame.minY)
                val labelFrame = layout.labels[index].offsetBy(frame.minX, frame.minY)
                val alpha = if (states[index] == PermissionOptionState.Inactive) m.inactiveOpacity else 1.0
                val selected = states[index] == PermissionOptionState.Selected
                items += ChatDrawItem.Box(ChatBox(box, if (selected) Palette.surfaceHighest else Palette.surface, Palette.divider, m.buttonBorder, m.buttonRadius, alpha = alpha))
                items += ChatDrawItem.Text(labels[index].layout(Rect(0.0, 0.0, labelFrame.width, labelFrame.height)), labelFrame, alpha)
                if (pending && busy == option.choice) {
                    items += ChatDrawItem.Pulse(ChatBox(box.insetBy(-1.0, -1.0), null, Palette.text, 2.0, m.buttonRadius + 1))
                }
                if (pending && busy == null) hits += ChatHit(box, ChatRowAction.Permission(message.id, option.choice, option.answer, option.mode))
            }
            items to hits
        }
    }

    companion object {
        const val FOOTER_ID = "chat-footer"
        private val DIFF_VIEWS = setOf(ToolViewKind.Edit, ToolViewKind.Write, ToolViewKind.MultiEdit, ToolViewKind.GeminiEdit)
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

        fun union(a: Rect, b: Rect): Rect {
            val minX = minOf(a.minX, b.minX)
            val minY = minOf(a.minY, b.minY)
            return Rect(minX, minY, maxOf(a.maxX, b.maxX) - minX, maxOf(a.maxY, b.maxY) - minY)
        }
    }
}

/**
 * A markdown text measured block by block (happy-ios `MarkdownPiece`): the
 * blocks for a pure layout, and once that layout has placed them, what to
 * draw where.
 */
class MarkdownPiece(
    parsed: List<MarkdownBlock>,
    color: ColorToken,
    env: ChatTextEnv,
    selectable: Boolean = true,
) {
    val blocks: List<MarkdownLayout.Block>
    private val drawers: List<(MarkdownLayout.Placed, Double, Double) -> Pair<List<ChatDrawItem>, List<ChatHit>>>

    init {
        val blocks = ArrayList<MarkdownLayout.Block>()
        val drawers = ArrayList<(MarkdownLayout.Placed, Double, Double) -> Pair<List<ChatDrawItem>, List<ChatHit>>>()
        val body = ChatTextStyles.markdownText

        fun hold(frame: Rect, paragraph: String): List<ChatHit> =
            if (selectable) listOf(ChatHit(frame, ChatRowAction.CopyMenu(paragraph), hold = true)) else emptyList()

        fun text(measured: MeasuredText): (MarkdownLayout.Placed, Double, Double) -> Pair<List<ChatDrawItem>, List<ChatHit>> = { placed, x, y ->
            val frame = placed.frame.offsetBy(x, y)
            listOf<ChatDrawItem>(ChatDrawItem.Text(measured.layout(placed.frame), frame)) to (linkHits(measured, frame) + hold(frame, measured.string))
        }

        fun list(rows: List<Triple<MeasuredText, MeasuredText, Int>>) {
            blocks += MarkdownLayout.Block.List(rows.map { MarkdownLayout.ListItem(it.third, it.first.measurable, it.second.measurable) })
            drawers += { placed, x, y ->
                val items = ArrayList<ChatDrawItem>()
                val hits = ArrayList<ChatHit>()
                (placed as? MarkdownLayout.Placed.ListBlock)?.items?.forEachIndexed { index, item ->
                    if (index < rows.size) {
                        val textFrame = item.text.offsetBy(x, y)
                        items += ChatDrawItem.Text(rows[index].first.layout(item.marker), item.marker.offsetBy(x, y))
                        items += ChatDrawItem.Text(rows[index].second.layout(item.text), textFrame)
                        hits += linkHits(rows[index].second, textFrame) + hold(textFrame, rows[index].second.string)
                    }
                }
                items to hits
            }
        }

        for (block in parsed) {
            when (block) {
                is MarkdownBlock.Text -> {
                    val measured = MeasuredText(ChatStrings.spans(block.content, body, color, env), body, env)
                    blocks += MarkdownLayout.Block.Text(measured.measurable)
                    drawers += text(measured)
                }
                is MarkdownBlock.Header -> {
                    val style = ChatTextStyles.markdownHeader(block.level)
                    val measured = MeasuredText(ChatStrings.spans(block.content, style, color, env), style, env)
                    blocks += MarkdownLayout.Block.Header(block.level, measured.measurable)
                    drawers += text(measured)
                }
                MarkdownBlock.HorizontalRule -> {
                    blocks += MarkdownLayout.Block.Rule
                    drawers += { placed, x, y -> listOf<ChatDrawItem>(ChatDrawItem.Box(ChatBox(placed.frame.offsetBy(x, y), Palette.divider))) to emptyList() }
                }
                is MarkdownBlock.BulletList -> list(block.items.map { item ->
                    Triple(
                        MeasuredText(ChatStrings.string(BULLETS[minOf(item.depth, BULLETS.size - 1)], color), body, env),
                        MeasuredText(ChatStrings.spans(item.spans, body, color, env), body, env),
                        item.depth,
                    )
                })
                is MarkdownBlock.NumberedList -> list(block.items.map { item ->
                    Triple(
                        MeasuredText(ChatStrings.string("${item.number}.", color), body, env),
                        MeasuredText(ChatStrings.spans(item.spans, body, color, env), body, env),
                        item.depth,
                    )
                })
                is MarkdownBlock.CodeBlock -> code(block.language, block.content, env, blocks, drawers, ::hold)
                is MarkdownBlock.Mermaid -> {
                    // A diagram needs a web view; until the diagram stage, its source as code.
                    code("mermaid", block.content, env, blocks, drawers, ::hold)
                }
                is MarkdownBlock.Options -> {
                    val labels = block.items.map { MeasuredText(ChatStrings.string(it, color), ChatTextStyles.option, env) }
                    blocks += MarkdownLayout.Block.Options(labels.map { it.measurable })
                    drawers += { placed, x, y ->
                        val items = ArrayList<ChatDrawItem>()
                        val hits = ArrayList<ChatHit>()
                        (placed as? MarkdownLayout.Placed.Options)?.let { options ->
                            options.buttons.forEachIndexed { index, button ->
                                if (index < labels.size) {
                                    val frame = button.offsetBy(x, y)
                                    items += ChatDrawItem.Box(ChatBox(frame, Palette.surfaceHighest, radius = MarkdownLayout.Metrics.optionRadius))
                                    items += ChatDrawItem.Text(labels[index].layout(options.labels[index]), options.labels[index].offsetBy(x, y))
                                    hits += ChatHit(frame, ChatRowAction.Option(block.items[index]))
                                }
                            }
                        }
                        items to hits
                    }
                }
                is MarkdownBlock.Table -> {
                    val all = listOf(block.headers) + block.rows
                    val measured = all.mapIndexed { rowIndex, row ->
                        row.map { cell ->
                            val style = if (rowIndex == 0) ChatTextStyles.tableHeader else ChatTextStyles.tableCell
                            MeasuredText(ChatStrings.spans(cell, style, color, env), style, env)
                        }
                    }
                    val lengths = all.map { row -> row.map { cell -> cell.sumOf { it.text.length } } }
                    blocks += MarkdownLayout.Block.TableBlock(MarkdownLayout.Table(MarkdownLayout.columnWidths(lengths), measured.map { row -> row.map { it.measurable } }))
                    drawers += { placed, x, y ->
                        val table = (placed as MarkdownLayout.Placed.TableBlock).table
                        val frame = table.frame.offsetBy(x, y)
                        val grid = ArrayList<ChatDrawItem>()
                        table.rows.firstOrNull()?.let { grid += ChatDrawItem.Box(ChatBox(it, Palette.surfaceHigh)) }
                        for (separator in table.separators) grid += ChatDrawItem.Box(ChatBox(separator, Palette.divider))
                        val hits = ArrayList<ChatHit>()
                        table.cells.forEachIndexed { rowIndex, cells ->
                            cells.forEachIndexed { column, cell ->
                                if (rowIndex < measured.size && column < measured[rowIndex].size) {
                                    grid += ChatDrawItem.Text(measured[rowIndex][column].layout(cell), cell)
                                    hits += hold(cell, measured[rowIndex][column].string)
                                }
                            }
                        }
                        val content = ChatRowPlan("table", table.contentSize, grid, hits)
                        listOf(
                            ChatDrawItem.Box(ChatBox(frame, border = Palette.divider, borderWidth = MarkdownLayout.Metrics.tableBorder, radius = MarkdownLayout.Metrics.tableRadius)),
                            ChatDrawItem.Scroller(content, table.scroll.offsetBy(frame.minX, frame.minY), MarkdownLayout.Metrics.tableRadius - MarkdownLayout.Metrics.tableBorder),
                        ) to emptyList()
                    }
                }
                is MarkdownBlock.Image -> {
                    val caption = block.alt.ifEmpty { null }?.let { MeasuredText(ChatStrings.string(it, Palette.textSecondary), ChatTextStyles.imageCaption, env) }
                    blocks += MarkdownLayout.Block.Image(caption?.measurable)
                    drawers += { placed, x, y ->
                        val image = placed as MarkdownLayout.Placed.Image
                        val shifted = image.frame.offsetBy(x, y)
                        val items = arrayListOf<ChatDrawItem>(
                            ChatDrawItem.Box(ChatBox(image.picture.offsetBy(shifted.minX, shifted.minY), Palette.surfaceHighest, radius = MarkdownLayout.Metrics.imageRadius)),
                        )
                        val captionFrame = image.caption
                        if (caption != null && captionFrame != null) {
                            items += ChatDrawItem.Text(caption.layout(captionFrame), captionFrame.offsetBy(shifted.minX, shifted.minY))
                        }
                        items to emptyList()
                    }
                }
            }
        }
        this.blocks = blocks
        this.drawers = drawers
    }

    fun draw(layout: MarkdownLayout, x: Double, y: Double): Pair<List<ChatDrawItem>, List<ChatHit>> {
        val items = ArrayList<ChatDrawItem>()
        val hits = ArrayList<ChatHit>()
        layout.blocks.zip(drawers).forEach { (placed, drawer) ->
            val (i, h) = drawer(placed, x, y)
            items += i
            hits += h
        }
        return items to hits
    }

    companion object {
        val BULLETS = listOf("•", "◦", "▪")

        /** Each link run's line boxes, tappable. */
        fun linkHits(text: MeasuredText, frame: Rect): List<ChatHit> {
            val spanned = text.text as? Spanned ?: return emptyList()
            val links = spanned.getSpans(0, spanned.length, ChatLinkSpan::class.java)
            if (links.isEmpty()) return emptyList()
            val layout = text.layout(frame)
            val d = text.env.density.toDouble()
            return links.flatMap { link ->
                val start = spanned.getSpanStart(link)
                val end = spanned.getSpanEnd(link)
                val first = layout.getLineForOffset(start)
                val last = layout.getLineForOffset(end)
                (first..last).map { line ->
                    val left = if (line == first) layout.getPrimaryHorizontal(start) else layout.getLineLeft(line)
                    val right = if (line == last) layout.getPrimaryHorizontal(end) else layout.getLineRight(line)
                    Rect(frame.minX + left / d, frame.minY + layout.getLineTop(line) / d, (right - left) / d, (layout.getLineBottom(line) - layout.getLineTop(line)) / d)
                }.map { ChatHit(it, ChatRowAction.OpenLink(link.url)) }
            }
        }

        /** A code block, unwrapped like happy-app's: the language on top, the code scrolling sideways under it. */
        private fun code(
            language: String?, content: String, env: ChatTextEnv,
            blocks: MutableList<MarkdownLayout.Block>,
            drawers: MutableList<(MarkdownLayout.Placed, Double, Double) -> Pair<List<ChatDrawItem>, List<ChatHit>>>,
            hold: (Rect, String) -> List<ChatHit>,
        ) {
            val label = language?.let { MeasuredText(ChatStrings.string(it, Palette.textSecondary), ChatTextStyles.codeLanguage, env, maxLines = 1) }
            val code = MeasuredText(ChatSyntax.string(content, language, env), ChatTextStyles.codeBlock, env)
            val (codeLayout, codeSize) = code.unwrapped()
            blocks += MarkdownLayout.Block.Code(label?.measurable, codeSize)
            drawers += { placed, x, y ->
                val placedCode = (placed as MarkdownLayout.Placed.Code).code
                val frame = placedCode.frame.offsetBy(x, y)
                val items = arrayListOf<ChatDrawItem>(ChatDrawItem.Box(ChatBox(frame, Palette.surfaceHighest, radius = MarkdownLayout.Metrics.codeRadius)))
                val languageFrame = placedCode.language
                if (label != null && languageFrame != null) items += ChatDrawItem.Text(label.layout(languageFrame), languageFrame.offsetBy(frame.minX, frame.minY))
                val strip = ChatRowPlan("code", placedCode.contentSize, listOf(ChatDrawItem.Text(codeLayout, placedCode.text)))
                items += ChatDrawItem.Scroller(strip, placedCode.scroll.offsetBy(frame.minX, frame.minY))
                items to hold(frame, content)
            }
        }
    }
}

/** SimpleSyntaxHighlighter's colours over the shared tokenizer (happy-ios `ChatSyntax`, `FileSyntax`). */
object ChatSyntax {
    fun string(code: String, language: String?, env: ChatTextEnv): CharSequence {
        val builder = SpannableStringBuilder()
        val tokens = if (language.isNullOrEmpty()) emptyList() else runCatching { tokenizeCode(code, language) }.getOrDefault(emptyList())
        if (tokens.isEmpty()) {
            builder.append(code)
            builder.setSpan(TokenColorSpan(Palette.syntaxDefault), 0, builder.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            return builder
        }
        val boldSize = ChatTextStyles.codeBlock.scaled(env.scale).size * env.density
        for (token in tokens) {
            val start = builder.length
            builder.append(token.text)
            if (builder.length == start) continue
            builder.setSpan(TokenColorSpan(color(token.type, token.nestLevel)), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (token.type.emphasized) builder.setSpan(FaceSpan(FontFace.MonoSemiBold, boldSize.toFloat()), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return builder
    }

    fun color(type: SyntaxTokenType, nestLevel: Int): ColorToken = when (type) {
        SyntaxTokenType.Keyword, SyntaxTokenType.ControlFlow, SyntaxTokenType.Type, SyntaxTokenType.Modifier, SyntaxTokenType.Assignment,
        SyntaxTokenType.Comparison, SyntaxTokenType.Logical, SyntaxTokenType.Decorator, SyntaxTokenType.Import -> Palette.syntaxKeyword
        SyntaxTokenType.String, SyntaxTokenType.Regex -> Palette.syntaxString
        SyntaxTokenType.Number, SyntaxTokenType.Boolean -> Palette.syntaxNumber
        SyntaxTokenType.Function, SyntaxTokenType.Method -> Palette.syntaxFunction
        SyntaxTokenType.Comment, SyntaxTokenType.Docstring -> Palette.syntaxComment
        SyntaxTokenType.Bracket -> when (maxOf(1, nestLevel) % 5) {
            2 -> Palette.syntaxBracket2
            3 -> Palette.syntaxBracket3
            4 -> Palette.syntaxBracket4
            0 -> Palette.syntaxBracket5
            else -> Palette.syntaxBracket1
        }
        else -> Palette.syntaxDefault
    }
}
