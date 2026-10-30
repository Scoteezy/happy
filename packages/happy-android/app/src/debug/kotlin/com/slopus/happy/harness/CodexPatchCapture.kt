package com.slopus.happy.harness

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.slopus.happy.AppGraph
import com.slopus.happy.MainActivity
import com.slopus.happy.chat.ChatNormalizer
import com.slopus.happy.testing.FakeHappyServer
import com.slopus.happy.ui.chat.*
import com.slopus.happy.ui.home.HomeScreen
import com.slopus.happy.ui.nav.ScreenStack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.File

/** Actual chat/detail routes; only the isolated APK can seed or observe these synthetic cases. */
class CodexPatchCapture(private val app: Application, private val graph: AppGraph, private val scenario: String) : Application.ActivityLifecycleCallbacks {
    private var job: Job? = null
    private var owner: MainActivity? = null

    init {
        check(app.packageName == "com.slopus.happy.dev.shellharness")
        require(scenario in cases)
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity || owner != null) return
        owner = activity
        job = activity.lifecycleScope.launch {
            val account = withTimeout(30_000) { graph.account.filterNotNull().first() }
            account.syncNow()
            val stack = withTimeout(30_000) {
                var found: ScreenStack? = null
                while (found == null) {
                    found = descendants(activity.window.decorView).filterIsInstance<ScreenStack>().firstOrNull { it.top is HomeScreen }
                    if (found == null) delay(50)
                }
                found
            }
            stack.push(ChatScreen(activity, graph, SESSION), animated = false)
            val file = File(app.filesDir, "codex-patch-evidence.json")
            while (isActive) {
                val top = stack.top
                val root = top?.view
                val views = root?.let(::descendants)?.toList().orEmpty()
                val plans = views.filterIsInstance<ChatRowView>().mapNotNull { it.plan }
                val report = buildJsonObject {
                    put("realMainActivity", true); put("scenario", scenario)
                    put("screen", top?.javaClass?.simpleName ?: "none")
                    putJsonArray("headers") { plans.flatMap { it.items }.filterIsInstance<ChatDrawItem.DiffHeader>().forEach { add(it.path) } }
                    putJsonArray("diffs") { plans.flatMap { it.items }.filterIsInstance<ChatDrawItem.Diff>().forEach { diff -> add(buildJsonObject {
                        put("chunk", diff.chunk); put("height", diff.frame.height); put("radius", diff.radius)
                        put("bottomCornersOnly", diff.bottomCornersOnly)
                        put("hidden", diff.plan.files.sumOf { it.hiddenCount })
                        put("rows", diff.plan.files.sumOf { it.rows.size })
                    }) } }
                    putJsonArray("targets") {
                        views.filterIsInstance<ChatRowView>().forEach { row ->
                            row.plan?.hits?.forEach { hit ->
                                val label = when (val action = hit.action) {
                                    is ChatRowAction.ToggleTool -> "toggle-tool"
                                    is ChatRowAction.OpenMessage -> action.file ?: "open-message"
                                    else -> null
                                }
                                val at = IntArray(2); row.getLocationOnScreen(at)
                                val d = row.resources.displayMetrics.density
                                val x = at[0] + hit.frame.midX * d; val y = at[1] + hit.frame.midY * d
                                val visible = android.graphics.Rect()
                                if (label != null && row.getGlobalVisibleRect(visible) && visible.contains(x.toInt(), y.toInt())) add(buildJsonObject {
                                    put("label", label); put("x", x); put("y", y)
                                })
                            }
                        }
                        views.filter { it.isClickable && !it.contentDescription.isNullOrBlank() }.forEach { view ->
                            val visible = android.graphics.Rect()
                            if (view.getGlobalVisibleRect(visible)) add(buildJsonObject {
                                put("label", view.contentDescription.toString()); put("x", visible.centerX()); put("y", visible.centerY())
                            })
                        }
                    }
                    putJsonArray("text") { plans.flatMap { it.items }.filterIsInstance<ChatDrawItem.Text>().forEach { add(it.layout.text.toString()) } }
                }
                withContext(Dispatchers.IO) {
                    val temporary = File(file.parentFile, "codex-patch-evidence.tmp")
                    temporary.writeText(report.toString()); check(temporary.renameTo(file))
                }
                delay(100)
            }
        }
    }

    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        app.unregisterActivityLifecycleCallbacks(this@CodexPatchCapture)
        job?.cancelAndJoin(); job = null; owner = null
    }
    override fun onActivityDestroyed(activity: Activity) { if (owner === activity) job?.cancel() }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}

    companion object {
        const val SESSION = "fixture-codex-patch"
        val cases = setOf("files", "unified", "fallback", "headerless")

        fun input(scenario: String): JsonObject {
            require(scenario in cases)
            val patch = "--- a/src/value.kt\n+++ b/src/value.kt\n@@ -1 +1 @@\n-val value = 1\n+val value = 2"
            return when (scenario) {
                "unified" -> buildJsonObject { put("unified_diff", patch) }
                "headerless" -> buildJsonObject { put("unified_diff", "@@ -1 +1 @@\n-before\n+after") }
                "fallback" -> buildJsonObject { put("input", "Synthetic malformed patch: no envelope") }
                else -> buildJsonObject {
                    putJsonObject("changes") {
                        putJsonObject("/fixture/patch/a-added.kt") { put("type", "add"); put("content", "val added = true") }
                        putJsonObject("/fixture/patch/b-long.kt") {
                            put("type", "update")
                            putJsonObject("modify") {
                                put("old_content", (1..120).joinToString("\n") { "val value$it = $it" })
                                put("new_content", (1..120).joinToString("\n") { "val value$it = ${if (it % 3 == 0) it + 1 else it}" })
                            }
                        }
                        putJsonObject("/fixture/patch/c-before.kt") { putJsonObject("kind") { put("type", "update"); put("move_path", "/fixture/patch/c-after.kt") } }
                        putJsonObject("/fixture/patch/d-deleted.kt") { put("type", "delete"); put("content", "val obsolete = true") }
                    }
                }
            }
        }

        fun messages(scenario: String): List<JsonObject> {
            val name = if (scenario in setOf("unified", "headerless")) "CodexDiff" else "CodexPatch"
            fun envelope(index: Int, event: JsonObject) = buildJsonObject {
                put("role", "session")
                putJsonObject("content") { put("type", "session"); putJsonObject("data") {
                    put("id", "patch-envelope-$index"); put("time", index); put("role", "agent"); put("turn", "patch-turn"); put("ev", event)
                } }
            }
            val records = listOf(envelope(1, buildJsonObject {
                put("t", "tool-call-start"); put("call", "patch-call"); put("name", name)
                put("title", "Synthetic $scenario"); put("description", "Frozen native patch comparison"); put("args", input(scenario))
            }), envelope(2, buildJsonObject {
                put("t", "tool-call-end"); put("call", "patch-call")
                put("result", if (scenario == "fallback") "Synthetic patch refused" else "Synthetic patch completed")
                put("isError", scenario == "fallback")
            }))
            records.forEachIndexed { index, raw -> check(ChatNormalizer.normalize("patch-$index", null, index.toLong(), raw) != null) }
            return records
        }

        fun seed(server: FakeHappyServer, scenario: String) {
            val now = System.currentTimeMillis()
            server.setSettings(buildJsonObject { put("groupToolCalls", false) }, 1)
            server.addSession(SESSION, now, buildJsonObject {
                put("path", "/fixture/patch"); put("host", "fixture.local"); put("machineId", "fixture-machine"); put("flavor", "codex")
                putJsonObject("summary") { put("text", "Codex $scenario"); put("updatedAt", now) }
            })
            messages(scenario).forEachIndexed { index, raw -> server.addMessage(SESSION, index + 1L, raw, now + index) }
        }

        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
    }
}