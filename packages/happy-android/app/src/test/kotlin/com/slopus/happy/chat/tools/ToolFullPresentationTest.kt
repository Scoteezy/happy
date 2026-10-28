package com.slopus.happy.chat.tools

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.chat.ChatMessage
import com.slopus.happy.chat.PermissionStatus
import com.slopus.happy.chat.ToolCall
import com.slopus.happy.chat.ToolPermission
import com.slopus.happy.chat.ToolState
import com.slopus.happy.chat.obj
import com.slopus.happy.chat.arr
import com.slopus.happy.text.plural
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Ported from happy-ios Tests/HappyCoreTests/ChatToolPresentationTests.swift `ToolFullPresentationTests`. */
@RunWith(AndroidJUnit4::class)
class ToolFullPresentationTest {
    private val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val text = object : ToolText {
        override fun string(id: Int, vararg args: Any) = resources.getString(id, *args)
        override fun plural(id: Int, count: Int, vararg args: Any) = resources.plural(id, count, *args)
    }

    private fun tool(
        name: String,
        input: String = "{}",
        state: ToolState = ToolState.Completed,
        result: String? = null,
        description: String? = null,
        title: String? = null,
    ) = ToolCall(
        name = name, title = title, state = state, input = Json.parseToJsonElement(input), createdAt = 0, description = description,
        result = result?.let(Json::parseToJsonElement),
    )

    private fun full(tool: ToolCall) = ToolFullPresentation.of(tool, "/repo", text)

    private fun terminal(tool: ToolCall) = (full(tool).main as ToolFullPresentation.Main.Terminal).terminal

    @Test fun theTitleIsTheKnownToolsUnlessTheProviderNamedTheCall() {
        assertEquals("src/a.swift", full(tool("Read", """{"file_path":"/repo/src/a.swift"}""")).title)
        assertEquals("Read the config", full(tool("Read", """{"file_path":"/repo/src/a.swift"}""", title = "Read the config")).title)
        assertEquals("a terminal tool is titled by what it ran", "ls -la", full(tool("Bash", """{"command":"ls -la"}""", description = "List")).title)
    }

    @Test fun aTerminalToolShowsItsWholeRun() {
        val done = full(tool("Bash", """{"command":"swift test"}""", result = "\"Executed 3 tests\\n\""))
        assertEquals(
            ToolFullPresentation.Main.Terminal(ToolFullTerminal("swift test", true, "Executed 3 tests\n", null, null, false), null),
            done.main,
        )
        assertNull("the terminal shows the outcome itself", done.outcome)

        val structured = terminal(tool("CodexBash", """{"command":["bash","-lc","make"]}""", result = """{"output":"built","stderr":"warning: x"}"""))
        assertEquals("built", structured.stdout)
        assertEquals("warning: x", structured.stderr)

        val failure = terminal(tool("Bash", """{"command":"false"}""", ToolState.Error, """{"error":"exit 1"}"""))
        assertEquals(ToolFullError("exit 1"), failure.error)
        assertNull(failure.stdout)
        assertEquals(ToolFullError(null), terminal(tool("Bash", """{"command":"false"}""", ToolState.Error)).error)

        assertFalse("no empty-output line while it runs", terminal(tool("Bash", """{"command":"sleep 5"}""", ToolState.Running)).showsEmptyOutput)
        assertTrue(terminal(tool("Bash", """{"command":"true"}""")).showsEmptyOutput)
        val blank = terminal(tool("Bash", """{"command":"echo"}""", result = "\"  \\n\""))
        assertNull("whitespace isn't drawn", blank.visibleStdout)
        assertFalse("but it isn't nothing either", blank.showsEmptyOutput)
    }

    @Test fun aShellControlShowsItsLabelAndWhatItTyped() {
        val typed = full(tool("write_stdin", """{"session_id":7,"chars":"y\n"}""", result = "\"ok\""))
        assertEquals(
            ToolFullPresentation.Main.Terminal(ToolFullTerminal("Sending input to shell (7)", false, "ok", null, null, false), "y\n"),
            typed.main,
        )
    }

    @Test fun theFallbackSections() {
        val generic = full(tool("deploy_preview", """{"env":"staging"}""", description = "Deploy", result = """{"url":"https://x"}"""))
        val sections = (generic.main as ToolFullPresentation.Main.Generic).generic
        assertEquals("Deploy", sections.description)
        assertEquals("{\n  \"env\": \"staging\"\n}", sections.input)
        assertEquals("{\n  \"url\": \"https://x\"\n}", sections.output)
        assertNull(sections.error)
        assertFalse(sections.completedWithoutOutput)

        val quiet = (full(tool("deploy_preview")).main as ToolFullPresentation.Main.Generic).generic
        assertNull("no description and no title", quiet.description)
        assertEquals("an empty object is still shown", "{}", quiet.input)
        assertTrue(quiet.completedWithoutOutput)

        val failed = (full(tool("deploy_preview", state = ToolState.Error, result = "\"denied\"")).main as ToolFullPresentation.Main.Generic).generic
        assertEquals("denied", failed.error)
        assertNull(failed.output)
        assertFalse(failed.completedWithoutOutput)

        val named = (full(tool("deploy_preview", title = "Deploy preview")).main as ToolFullPresentation.Main.Generic).generic
        assertEquals("the display title stands in for a missing description", "Deploy preview", named.description)
    }

    @Test fun editsShowTheirDiffsThenHowTheyWent() {
        val edit = full(tool("Edit", """{"file_path":"/repo/a.swift","old_string":"a","new_string":"b"}""", result = "\"Updated.\""))
        assertEquals(ToolFullPresentation.Main.Diff(ToolViewKind.Edit, listOf(ToolDiffChunk("a", "b", "/repo/a.swift")), emptyList()), edit.main)
        assertEquals(ToolFullPresentation.Outcome.Output("Updated."), edit.outcome)
        assertTrue(edit.wide)

        val multi = full(
            tool(
                "MultiEdit",
                """{"file_path":"/repo/a.swift","edits":[{"old_string":"a","new_string":"b"},{"old_string":"c","new_string":"d","replace_all":true}]}""",
                ToolState.Error, "\"String not found\"",
            ),
        )
        val diff = multi.main as ToolFullPresentation.Main.Diff
        assertEquals(ToolViewKind.MultiEdit, diff.kind)
        assertEquals(2, diff.chunks.size)
        assertEquals(listOf(false, true), diff.replaceAll)
        assertEquals(ToolFullPresentation.Outcome.Error(ToolFullError("String not found")), multi.outcome)

        assertEquals(
            ToolFullPresentation.Main.Diff(ToolViewKind.Write, listOf(ToolDiffChunk("", "x", "/repo/n.md")), emptyList()),
            full(tool("write", """{"file_path":"/repo/n.md","content":"x"}""")).main,
        )
        assertFalse(full(tool("Task")).wide)
    }

    @Test fun aTaskEndsWithHowItWent() {
        assertEquals(ToolFullPresentation.Outcome.Output("Found it"), full(tool("Task", """{"description":"Explore"}""", result = "\"Found it\"")).outcome)
        assertEquals("an empty result is still an output", ToolFullPresentation.Outcome.Output(""), full(tool("Task", """{"description":"Explore"}""", result = "\"\"")).outcome)
        assertEquals(ToolFullPresentation.Outcome.Error(ToolFullError(null)), full(tool("Agent", state = ToolState.Error)).outcome)
        assertNull(full(tool("Task", state = ToolState.Running)).outcome)
        assertEquals(ToolFullPresentation.Main.Task, full(tool("Task")).main)
    }

    @Test fun devModeShowsTheCallAndItsSubAgentAsJSON() {
        val call = tool("Task", """{"description":"Explore"}""", result = "\"Found it\"").copy(
            callId = "toolu_1", permission = ToolPermission("p1", PermissionStatus.Approved, mode = "default"),
        )
        val child = ToolCall(callId = "toolu_2", name = "Read", state = ToolState.Running, input = Json.parseToJsonElement("""{"file_path":"/repo/a"}"""), createdAt = 5)
        val message = ChatMessage.ToolCallMessage(
            "m1", 1, call,
            listOf(ChatMessage.AgentText("c1", 2, "Looking"), ChatMessage.ToolCallMessage("c2", 3, child)),
        )
        val json = ToolFullPresentation.rawJSON(message).obj!!
        assertEquals(JsonPrimitive("Task"), json["name"])
        assertEquals(JsonPrimitive("completed"), json["state"])
        assertEquals(JsonPrimitive("Found it"), json["result"])
        assertEquals("nullable fields are written as null", JsonNull, json["startedAt"])
        assertNull("absent optionals are left out", json["title"])
        assertNull("the screen lists the call's fields by name, without its id", json["callId"])
        assertEquals(Json.parseToJsonElement("""{"id":"p1","status":"approved","mode":"default"}"""), json["permission"])
        val messages = json["messages"].arr!!
        assertEquals(2, messages.size)
        assertEquals(Json.parseToJsonElement("""{"id":"c1","createdAt":2,"kind":"agent-text","localId":null,"text":"Looking"}"""), messages.first())
        assertEquals("a child's tool is the whole ToolCall", JsonPrimitive("toolu_2"), messages.last().obj!!["tool"].obj!!["callId"])
        assertEquals(JsonArray(emptyList()), messages.last().obj!!["children"])
    }
}
