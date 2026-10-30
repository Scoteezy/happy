package com.slopus.happy.chat.tools

import com.slopus.happy.chat.AgentEvent
import com.slopus.happy.chat.ChatMessage
import com.slopus.happy.chat.ToolCall
import com.slopus.happy.chat.ToolState
import com.slopus.happy.chat.arr
import com.slopus.happy.chat.obj
import com.slopus.happy.chat.prettyToolJson
import com.slopus.happy.chat.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// A tool's own screen (session/[id]/message/[messageId].tsx with
// ToolFullView.tsx and ToolHeader.tsx), decided: its title, then the whole
// tool, output included. The chat collapses tools; this screen never does.
// Ported from happy-ios HappyCore/ChatDisplay/ToolFullPresentation.swift.

private fun truthy(value: JsonElement?): Boolean = when (value) {
    null, JsonNull -> false
    is JsonPrimitive -> value.content.isNotEmpty() && value.content != "false" && value.content != "0"
    else -> true
}

/** A failure's words, or tools.fullView.error ("Error") when it said nothing ([text] null). */
data class ToolFullError(val text: String?) {
    companion object {
        fun of(text: String?) = ToolFullError(text?.ifEmpty { null })
    }
}

/** CommandView on the tool's screen: what it ran and everything it said. */
data class ToolFullTerminal(
    /** The command, or the activity label when there is none. */
    val command: String,
    /** "$ " before a command; nothing before a label. */
    val prompt: Boolean,
    /** Shown only when there is more than whitespace. */
    val stdout: String?,
    val stderr: String?,
    val error: ToolFullError?,
    /** No "[Command completed with no output]" while it is running. */
    val hideEmptyOutput: Boolean,
) {
    /** The output lines CommandView draws (`stdout && stdout.trim()`). */
    val visibleStdout: String? get() = stdout?.takeIf { it.isNotBlank() }
    val visibleStderr: String? get() = stderr?.takeIf { it.isNotBlank() }

    /** "[Command completed with no output]": nothing at all came back, not even whitespace. */
    val showsEmptyOutput: Boolean get() = stdout.isNullOrEmpty() && stderr.isNullOrEmpty() && error == null && !hideEmptyOutput

    companion object {
        /**
         * toolResult.ts `getTerminalToolResult`: Happy's result is text, but
         * CLI providers may still send stdout and stderr in an object.
         */
        fun of(tool: ToolCall, command: String, prompt: Boolean): ToolFullTerminal {
            val result = tool.result
            val record = result.obj
            val structured = record != null && listOf("stdout", "stderr", "output").any { record[it].str != null }
            val stdout: String?
            val stderr: String?
            if (structured) {
                stdout = record!!["stdout"].str ?: record["output"].str
                stderr = record["stderr"].str
            } else {
                stdout = if (tool.state == ToolState.Completed) ToolFullPresentation.resultText(result) else null
                stderr = null
            }
            val error = if (tool.state == ToolState.Error) {
                val source = record?.get("error")?.takeIf { it != JsonNull } ?: result
                ToolFullError.of(ToolFullPresentation.resultText(source))
            } else {
                null
            }
            return ToolFullTerminal(command, prompt, stdout, stderr, error, hideEmptyOutput = tool.state == ToolState.Running)
        }
    }
}

/** ToolFullView's fallback sections, each null when it isn't shown. */
data class ToolFullGeneric(
    /** `tool.description || getToolDisplayTitle(tool)`, shown when the call has a description or a title. */
    val description: String?,
    /** The input as JSON. */
    val input: String?,
    val output: String?,
    val error: String?,
    /** "Tool completed successfully" / "No output was produced". */
    val completedWithoutOutput: Boolean,
) {
    companion object {
        fun of(tool: ToolCall, text: ToolText): ToolFullGeneric {
            val describedBy = listOfNotNull(tool.description, tool.title).firstOrNull { it.isNotEmpty() }
            val hasResult = truthy(tool.result)
            return ToolFullGeneric(
                description = if (describedBy == null) null else tool.description?.ifEmpty { null } ?: toolDisplayTitle(tool, text),
                input = tool.input?.takeIf { it != JsonNull }?.let(::prettyToolJson),
                output = if (tool.state == ToolState.Completed && hasResult) ToolFullPresentation.resultText(tool.result) else null,
                error = if (tool.state == ToolState.Error && hasResult) ToolFullPresentation.resultText(tool.result) else null,
                completedWithoutOutput = tool.state == ToolState.Completed && !hasResult,
            )
        }
    }
}

data class ToolFullPresentation(
    /** ToolHeader: the navigation title. */
    val title: String,
    val main: Main,
    val outcome: Outcome?,
    /** An edit tool's diffs run edge to edge on a phone instead of in a readable column. */
    val wide: Boolean,
) {
    /** What fills the screen. */
    sealed interface Main {
        /** BashViewFull: CommandView with the output, then a shell control's typed input. */
        data class Terminal(val terminal: ToolFullTerminal, val controlInput: String?) : Main
        /** TaskView: the sub-agent's last tool calls. */
        data object Task : Main
        /** EditViewFull (line numbers always on), WriteView (the setting's), MultiEditViewFull ("Edit N of M"). */
        data class Diff(val kind: ToolViewKind, val chunks: List<ToolDiffChunk>, val replaceAll: List<Boolean>) : Main
        data class Patch(val files: List<ToolPatchFile>?) : Main
        data class UnifiedDiff(val diff: ToolUnifiedDiff?) : Main
        /** ToolFullView's fallback: description, input, output, error. */
        data class Generic(val generic: ToolFullGeneric) : Main
    }

    /** How a specialized view that shows the tool's inputs ends: the error, or the output. */
    sealed interface Outcome {
        data class Error(val error: ToolFullError) : Outcome
        data class Output(val output: String) : Outcome
    }

    companion object {
        /** toolFullViewRegistry's terminal entries; anything else in the terminal category joins them. */
        private val TERMINAL_NAMES = setOf("Bash", "CodexBash")
        private val TASK_NAMES = setOf("Task", "Agent")
        private val DIFF_NAMES = mapOf(
            "Edit" to ToolViewKind.Edit, "search_replace" to ToolViewKind.Edit, "Write" to ToolViewKind.Write,
            "write" to ToolViewKind.Write, "MultiEdit" to ToolViewKind.MultiEdit,
        )

        fun of(tool: ToolCall, root: String?, text: ToolText, children: List<ChatMessage> = emptyList()): ToolFullPresentation {
            val title = title(tool, root, text, children)
            val name = tool.name
            val wide = toolSummaryCategory(name) == ToolCategory.Edit
            if (isTerminalToolName(name) || name in TERMINAL_NAMES) {
                val command = terminalToolCommand(name, tool.input)
                val terminal = ToolFullTerminal.of(tool, command ?: toolActivityLabel(tool, text), prompt = command != null)
                val control = shellControlChars(name, tool.input)
                return ToolFullPresentation(title, Main.Terminal(terminal, control), null, wide)
            }
            val main = when {
                name in TASK_NAMES -> Main.Task
                name in setOf("CodexPatch", "GeminiPatch", "apply_patch") -> Main.Patch(patchViewFiles(tool.input, root))
                name in setOf("CodexDiff", "GeminiDiff") -> Main.UnifiedDiff(ToolUnifiedDiff.of(tool.input))
                DIFF_NAMES[name] != null -> {
                    val kind = DIFF_NAMES.getValue(name)
                    val replaceAll = if (kind == ToolViewKind.MultiEdit) {
                        tool.input.obj?.get("edits").arr.orEmpty().map { (it.obj?.get("replace_all") as? JsonPrimitive)?.booleanOrNull == true }
                    } else {
                        emptyList()
                    }
                    Main.Diff(kind, ToolDiffInputs.chunks(kind, tool.input), replaceAll)
                }
                else -> return ToolFullPresentation(title, Main.Generic(ToolFullGeneric.of(tool, text)), null, wide)
            }
            // Specialized views show the inputs, not how the run ended.
            val outcome = when {
                tool.state == ToolState.Error -> Outcome.Error(ToolFullError.of(resultText(tool.result)))
                tool.state == ToolState.Completed && tool.result != null && tool.result != JsonNull -> Outcome.Output(resultText(tool.result) ?: "")
                else -> null
            }
            return ToolFullPresentation(title, main, outcome, wide)
        }

        /** ToolHeader: the known tool's title unless the provider named the call; for a terminal tool, what it ran. */
        fun title(tool: ToolCall, root: String?, text: ToolText, children: List<ChatMessage> = emptyList()): String {
            if (isTerminalToolName(tool.name)) return toolActivityLabel(tool, text)
            if (tool.title.isNullOrBlank()) {
                KnownTools[tool.name]?.title?.let { return it(ToolContext(tool, text, root, children)) }
            }
            return toolDisplayTitle(tool, text)
        }

        /** toolResult.ts `toolResultText`. */
        fun resultText(value: JsonElement?): String? {
            if (value == null || value == JsonNull) return null
            return value.str ?: prettyToolJson(value)
        }

        /**
         * ToolFullView's dev-mode "Raw JSON": the call as happy-app's
         * `ToolCall` and its sub-agent's messages as `Message`s. Absent
         * optionals are left out and nullable ones are null, as
         * `JSON.stringify` writes them.
         */
        fun rawJSON(message: ChatMessage.ToolCallMessage): JsonElement {
            val tool = toolJSON(message.tool).toMutableMap()
            tool.remove("callId")
            tool["messages"] = JsonArray(message.children.map(::messageJSON))
            return JsonObject(tool)
        }

        private fun toolJSON(tool: ToolCall): JsonObject {
            val o = linkedMapOf<String, JsonElement>(
                "name" to JsonPrimitive(tool.name),
                "state" to JsonPrimitive(tool.state.name.lowercase()),
                "description" to (tool.description?.let(::JsonPrimitive) ?: JsonNull),
                "input" to (tool.input ?: JsonNull),
                "createdAt" to JsonPrimitive(tool.createdAt),
                "startedAt" to (tool.startedAt?.let(::JsonPrimitive) ?: JsonNull),
                "completedAt" to (tool.completedAt?.let(::JsonPrimitive) ?: JsonNull),
            )
            tool.callId?.let { o["callId"] = JsonPrimitive(it) }
            tool.title?.let { o["title"] = JsonPrimitive(it) }
            tool.result?.let { o["result"] = it }
            tool.permission?.let { p ->
                val record = linkedMapOf<String, JsonElement>("id" to JsonPrimitive(p.id), "status" to JsonPrimitive(p.status.wire))
                p.reason?.let { record["reason"] = JsonPrimitive(it) }
                p.mode?.let { record["mode"] = JsonPrimitive(it) }
                p.allowedTools?.let { record["allowedTools"] = JsonArray(it.map(::JsonPrimitive)) }
                p.decision?.let { record["decision"] = JsonPrimitive(it.wire) }
                p.date?.let { record["date"] = JsonPrimitive(it) }
                o["permission"] = JsonObject(record)
            }
            return JsonObject(o)
        }

        private fun messageJSON(message: ChatMessage): JsonElement {
            val o = linkedMapOf<String, JsonElement>("id" to JsonPrimitive(message.id), "createdAt" to JsonPrimitive(message.createdAt))
            when (message) {
                is ChatMessage.UserText -> {
                    o["kind"] = JsonPrimitive("user-text")
                    o["localId"] = message.localId?.let(::JsonPrimitive) ?: JsonNull
                    o["text"] = JsonPrimitive(message.text)
                    message.displayText?.let { o["displayText"] = JsonPrimitive(it) }
                }
                is ChatMessage.AgentText -> {
                    o["kind"] = JsonPrimitive("agent-text")
                    o["localId"] = JsonNull
                    o["text"] = JsonPrimitive(message.text)
                    if (message.isThinking) o["isThinking"] = JsonPrimitive(true)
                    message.turn?.let { o["turn"] = JsonPrimitive(it) }
                }
                is ChatMessage.ToolCallMessage -> {
                    o["kind"] = JsonPrimitive("tool-call")
                    o["localId"] = JsonNull
                    o["tool"] = toolJSON(message.tool)
                    o["children"] = JsonArray(message.children.map(::messageJSON))
                    message.turn?.let { o["turn"] = JsonPrimitive(it) }
                }
                is ChatMessage.Event -> {
                    o["kind"] = JsonPrimitive("agent-event")
                    o["event"] = eventJSON(message.event)
                    message.turn?.let { o["turn"] = JsonPrimitive(it) }
                }
                is ChatMessage.Unsupported -> o["kind"] = JsonPrimitive(message.kind)
                else -> Unit
            }
            return JsonObject(o)
        }

        private fun eventJSON(event: AgentEvent): JsonElement = JsonObject(
            when (event) {
                is AgentEvent.Switch -> mapOf("type" to JsonPrimitive("switch"), "mode" to JsonPrimitive(event.mode))
                is AgentEvent.Message -> mapOf("type" to JsonPrimitive("message"), "message" to JsonPrimitive(event.message))
                is AgentEvent.LimitReached -> mapOf("type" to JsonPrimitive("limit-reached"), "endsAt" to JsonPrimitive(event.endsAt))
                AgentEvent.Ready -> mapOf("type" to JsonPrimitive("ready"))
                is AgentEvent.UserMessageAccepted -> mapOf("type" to JsonPrimitive("user-message-accepted"), "ref" to JsonPrimitive(event.ref))
                is AgentEvent.UserMessageRejected -> mapOf(
                    "type" to JsonPrimitive("user-message-rejected"), "ref" to JsonPrimitive(event.ref), "reason" to JsonPrimitive(event.reason),
                )
                else -> mapOf("type" to JsonPrimitive("unknown"))
            },
        )

        /** A shell control's typed characters (`getShellControl(...).chars`). */
        private fun shellControlChars(name: String, input: JsonElement?): String? {
            if (shellControl(name, input) == null) return null
            val args = input.obj
            return (if (name == "write_stdin") args?.get("chars") else args?.get("input")).str?.ifEmpty { null }
        }
    }
}
