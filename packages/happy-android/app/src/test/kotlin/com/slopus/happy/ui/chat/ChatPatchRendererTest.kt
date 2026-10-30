package com.slopus.happy.ui.chat

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.chat.ChatMessage
import com.slopus.happy.chat.ChatRow
import com.slopus.happy.chat.ToolCall
import com.slopus.happy.chat.ToolState
import com.slopus.happy.layout.Rect
import com.slopus.happy.ui.diff.DiffChunkView
import com.slopus.happy.ui.diff.DiffFileHeaderView
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.setFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatPatchRendererTest {
    private lateinit var context: Context
    private lateinit var rows: ChatRowRenderer
    private lateinit var detail: MessageDetailRenderer
    private val env get() = TextLayouts.Env(Theme.density, Theme.textScale)
    private val patch = """{"changes":{"/repo/b.ts":{"kind":{"type":"add"},"diff":"new b"},"/repo/a.ts":{"kind":{"type":"update","move_path":"/repo/moved.ts"},"modify":{"old_content":"old a","new_content":"new a"}}}}"""

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Theme.install(context, Configuration(context.resources.configuration).apply { fontScale = 1f; densityDpi = 160 })
        rows = ChatRowRenderer(context.resources)
        detail = MessageDetailRenderer(context.resources, rows)
    }

    private fun message(name: String = "CodexPatch", input: String = patch, failed: Boolean = false) = ChatMessage.ToolCallMessage(
        id = "m1", createdAt = 0, tool = ToolCall(name = name, createdAt = 0,
            state = if (failed) ToolState.Error else ToolState.Completed, input = Json.parseToJsonElement(input),
            result = if (failed) JsonPrimitive("Patch refused") else null))

    private fun row(message: ChatMessage.ToolCallMessage = message(), expanded: Map<Int, Set<Int>> = emptyMap()) = rows.plan(
        ChatRowInput(ChatRow.Tool(message), expanded = true, diffExpanded = expanded, style = ChatRowStyle(root = "/repo", env = env)), 393.0)

    private fun full(message: ChatMessage.ToolCallMessage = message(), focus: String? = null, expanded: Map<Int, Set<Int>> = emptyMap()) = detail.plan(
        MessageDetailInput(message, "/repo", false, false, expanded, Double.MAX_VALUE, env, focus), 393.0)

    @Test fun actualChatRendererDrawsSortedHeadersDiffsAndFocusedRoutes() {
        val plan = row()
        assertEquals(listOf("a.ts", "b.ts"), plan.items.filterIsInstance<ChatDrawItem.DiffHeader>().map { it.path })
        assertEquals(listOf(0, 1), plan.items.filterIsInstance<ChatDrawItem.Diff>().map { it.chunk })
        val actions = plan.hits.map { it.action }.filterIsInstance<ChatRowAction.OpenMessage>().filter { it.file != null }
        assertEquals(listOf("/repo/a.ts", "/repo/b.ts"), actions.map { it.file })
        assertTrue(plan.items.filterIsInstance<ChatDrawItem.Text>().any { it.layout.text.toString() == "moved.ts" })
        assertTrue(plan.items.filterIsInstance<ChatDrawItem.Diff>().all { it.radius == 0.0 })
    }

    @Test fun captureCasesUseCurrentWireRecordsAndActualReduction() {
        for (scenario in com.slopus.happy.harness.CodexPatchCapture.cases) {
            val normalized = com.slopus.happy.harness.CodexPatchCapture.messages(scenario).mapIndexed { index, raw ->
                com.slopus.happy.chat.ChatNormalizer.normalize("record-$index", null, index.toLong(), raw)!!
            }
            val reduced = com.slopus.happy.chat.ReducerState().reduce(normalized).messages.single() as ChatMessage.ToolCallMessage
            assertEquals(if (scenario == "fallback") ToolState.Error else ToolState.Completed, reduced.tool.state)
            val plan = row(reduced)
            if (scenario == "files") {
                assertEquals(4, plan.items.filterIsInstance<ChatDrawItem.DiffHeader>().size)
                assertTrue(plan.items.filterIsInstance<ChatDrawItem.Diff>().any { it.plan.files.any { file -> file.hiddenCount > 0 } })
            } else if (scenario != "fallback") {
                assertEquals(1, plan.items.filterIsInstance<ChatDrawItem.Diff>().size)
            }
        }
    }

    @Test fun aFocusedDetailRetainsTheOriginalDiffIndexAndMissingFocusShowsAll() {
        val focused = full(focus = "/repo/b.ts")
        assertEquals(listOf("b.ts"), focused.items.filterIsInstance<ChatDrawItem.DiffHeader>().map { it.path })
        assertEquals(listOf(1), focused.items.filterIsInstance<ChatDrawItem.Diff>().map { it.chunk })
        assertEquals(2, full(focus = "/gone").items.filterIsInstance<ChatDrawItem.DiffHeader>().size)
    }

    @Test fun nativeCardClipsBelowHeaderAndHeaderlessDiffClipsEveryCorner() {
        val patch = """{"unified_diff":"--- a/a.swift\n+++ b/a.swift\n@@ -1 +1 @@\n-let a = 1\n+let a = 2"}"""
        val clipped = full(message("CodexDiff", patch)).items.filterIsInstance<ChatDrawItem.Diff>().single()
        assertEquals(7.0, clipped.radius, 0.0)
        assertTrue(clipped.bottomCornersOnly)
        val headerless = full(message("CodexDiff", """{"unified_diff":"@@ -1 +1 @@\n-a\n+b"}"""))
        assertTrue(headerless.items.filterIsInstance<ChatDrawItem.DiffHeader>().isEmpty())
        val noHeaderClip = headerless.items.filterIsInstance<ChatDrawItem.Diff>().single()
        assertFalse(noHeaderClip.bottomCornersOnly)
        assertEquals(7.0, noHeaderClip.radius, 0.0)
    }

    @Test fun fallbackAndFailedPatchUseRawTextAndOneError() {
        val message = message("apply_patch", """{"input":"invalid patch text"}""", failed = true)
        for (plan in listOf(row(message), full(message))) {
            val text = plan.items.filterIsInstance<ChatDrawItem.Text>().map { it.layout.text.toString() }
            assertTrue(text.contains("invalid patch text"))
            assertEquals(1, text.count { it == "Patch refused" })
            assertTrue(plan.items.filterIsInstance<ChatDrawItem.Diff>().isEmpty())
        }
    }

    @Test fun realHeaderWidgetExposesTheFileActionAndRebindClearsClipping() {
        val plan = row()
        val view = ChatRowView(context)
        var action: ChatRowAction? = null
        view.onAction = { value, _, _ -> action = value }
        view.bind(plan)
        view.setFrame(Rect(0.0, 0.0, plan.size.width, plan.size.height))
        val header = (0 until view.childCount).map(view::getChildAt).filterIsInstance<DiffFileHeaderView>().first()
        assertTrue(header.isFocusable)
        assertEquals("a.ts", header.contentDescription)
        header.performClick()
        assertEquals(ChatRowAction.OpenMessage("m1", "/repo/a.ts"), action)
        view.bind(full(focus = "/repo/a.ts"))
        assertTrue((0 until view.childCount).map(view::getChildAt).filterIsInstance<DiffChunkView>().all { it.clipRadius == 7.0 })
        view.bind(plan)
        assertTrue((0 until view.childCount).map(view::getChildAt).filterIsInstance<DiffChunkView>().all { it.clipRadius == 0.0 })
    }
}