package com.slopus.happy.harness

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.slopus.happy.AppGraph
import com.slopus.happy.MainActivity
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.diff.DiffFilesListLayout
import com.slopus.happy.layout.diff.DiffReadingPlace
import com.slopus.happy.model.GitStatusParser
import com.slopus.happy.model.GitStatusCommands
import com.slopus.happy.testing.FakeHappyServer
import com.slopus.happy.ui.chat.*
import com.slopus.happy.ui.details.SessionChangesScreen
import com.slopus.happy.ui.diff.*
import com.slopus.happy.ui.home.HomeScreen
import com.slopus.happy.ui.nav.ScreenStack
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic encrypted replies; the actual MainActivity Changes/chat routes own every interaction. */
class DiffPermissionCapture(private val app: Application, private val graph: AppGraph,
    private val answers: List<JsonObject>) : Application.ActivityLifecycleCallbacks {
    private var job: Job? = null
    private var owner: MainActivity? = null
    private var copied: String? = null
    private val clipboard = app.getSystemService(ClipboardManager::class.java)
    // Read only after this fixture's user copies a synthetic line, never inspect a pre-existing clip.
    private val changed = ClipboardManager.OnPrimaryClipChangedListener {
        clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            ?.takeIf { it.startsWith("val fixtureLine") }?.let { copied = it }
    }

    init {
        check(app.packageName == "com.slopus.happy.dev.shellharness")
        app.registerActivityLifecycleCallbacks(this)
        clipboard.addPrimaryClipChangedListener(changed)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity || owner != null) return
        owner = activity
        job = activity.lifecycleScope.launch {
            val account = withTimeout(30_000) { graph.account.filterNotNull().first() }
            account.syncNow()
            // HTTP sync can finish before the RPC socket. A scripted instant
            // navigation must wait for its synthetic computer to be reachable.
            withTimeout(30_000) { account.connectionStatus.first { it == com.slopus.happy.net.ConnectionStatus.CONNECTED } }
            val stack = withTimeout(30_000) {
                var found: ScreenStack? = null
                while (found == null) {
                    found = descendants(activity.window.decorView).filterIsInstance<ScreenStack>().firstOrNull { it.top is HomeScreen }
                    if (found == null) delay(50)
                }
                found
            }
            stack.push(ChatScreen(activity, graph, SESSION), animated = false)
            stack.push(SessionChangesScreen(activity, graph, SESSION), animated = false)
            val file = File(app.filesDir, "diff-permission-evidence.json")
            while (isActive) {
                val views = stack.top?.view?.let(::descendants)?.toList().orEmpty()
                val list = views.filterIsInstance<DiffFilesListView>().singleOrNull()
                val plans = list?.plans.orEmpty()
                val model = DiffFilesListLayout(plans.firstOrNull()?.width ?: 0.0, null, plans.map { it.layout.height }, Size(0.0, 0.0))
                val place = list?.let { DiffReadingPlace.at(it.readingTop, model, plans.map { p -> p.readingSection }) }
                val row = place?.part as? DiffReadingPlace.Part.Row
                val d = Theme.density
                val report = buildJsonObject {
                    put("realMainActivity", true); put("screen", stack.top?.javaClass?.simpleName)
                    put("fontScale", Theme.textScale.multiplier); put("density", d)
                    put("settled", plans.isNotEmpty() && plans.all { it.env.scale == Theme.textScale })
                    put("listWidth", list?.width ?: -1); put("listHeight", list?.height ?: -1)
                    put("listVisible", list?.visibility == View.VISIBLE)
                    putJsonArray("labels") { views.filterIsInstance<com.slopus.happy.ui.components.Label>().forEach { add(it.text.toString()) } }
                    put("top", list?.readingTop ?: 0.0)
                    put("section", place?.section ?: -1); put("file", row?.file ?: -1)
                    put("row", row?.row ?: -1); put("fraction", row?.fraction ?: 0.0)
                    put("copied", copied)
                    put("answers", JsonArray(answers.toList()))
                    putJsonArray("rows") {
                        plans.forEachIndexed { sectionIndex, plan ->
                            (plan.body as? DiffSectionPlan.Body.Files)?.plans?.forEachIndexed { fileIndex, filePlan ->
                                filePlan.rows.indices.forEach { index -> add(buildJsonObject {
                                    put("section", sectionIndex); put("file", fileIndex); put("row", index)
                                    put("y", model.sections[sectionIndex].minY + plan.layout.files[fileIndex].minY + filePlan.layout.canvas.minY + filePlan.layout.row(index).minY)
                                    put("height", filePlan.layout.lineHeight)
                                }) }
                            }
                        }
                    }
                    putJsonArray("scrolls") {
                        views.filterIsInstance<NestedScrollView>().filter { it.getChildAt(0) is ChatRowView }.forEach { scroll ->
                            val visible = android.graphics.Rect()
                            if (scroll.getGlobalVisibleRect(visible)) add(buildJsonObject {
                                put("height", scroll.height / d); put("content", scroll.getChildAt(0).height / d)
                                put("offset", scroll.scrollY / d); put("x", visible.centerX())
                                put("top", visible.top); put("bottom", visible.bottom)
                            })
                        }
                    }
                    putJsonArray("targets") {
                        fun target(label: String, view: View, x: Double, y: Double) {
                            val at = IntArray(2); view.getLocationOnScreen(at)
                            val visible = android.graphics.Rect()
                            val sx = at[0] + x * d; val sy = at[1] + y * d
                            if (view.getGlobalVisibleRect(visible) && visible.contains(sx.toInt(), sy.toInt())) add(buildJsonObject {
                                put("label", label); put("x", sx); put("y", sy)
                            })
                        }
                        views.filterIsInstance<DiffFileHeaderView>().forEach { target("file-header", it, it.width / d / 2.0, it.height / d / 2.0) }
                        views.filterIsInstance<DiffSectionView>().forEach { section ->
                            val plan = section.plan ?: return@forEach
                            (plan.body as? DiffSectionPlan.Body.Files)?.plans?.forEachIndexed { fileIndex, file ->
                                file.rows.indices.forEach { index ->
                                    val y = plan.layout.frame.minY + plan.layout.files[fileIndex].minY + file.layout.canvas.minY + file.layout.row(index).midY
                                    if (file.selectableText(file.layout.row(index).midY)?.endsWith("hidden horizontal tail") == true)
                                        target("copy-line", section, plan.width * 0.7, y)
                                }
                            }
                        }
                        views.filterIsInstance<ChatRowView>().forEach { view -> view.plan?.hits?.forEach { hit ->
                            val action = hit.action as? ChatRowAction.Permission
                            if (action != null) target("permission-${action.choice}", view, hit.frame.midX, hit.frame.midY)
                        } }
                    }
                }
                withContext(Dispatchers.IO) {
                    val temp = File(file.parentFile, "diff-permission-evidence.tmp")
                    temp.writeText(report.toString()); check(temp.renameTo(file))
                }
                delay(100)
            }
        }
    }

    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        app.unregisterActivityLifecycleCallbacks(this@DiffPermissionCapture)
        clipboard.removePrimaryClipChangedListener(changed)
        job?.cancelAndJoin(); job = null; owner = null
    }
    override fun onActivityDestroyed(activity: Activity) { if (owner === activity) job?.cancel() }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}

    companion object {
        const val SESSION = "fixture-diff-permission"
        fun seed(server: FakeHappyServer): List<JsonObject> {
            val now = System.currentTimeMillis()
            val answers = CopyOnWriteArrayList<JsonObject>()
            server.setSettings(buildJsonObject { put("groupToolCalls", false) }, 1)
            server.addSession(SESSION, now, buildJsonObject {
                put("path", "/fixture/diff"); put("host", "fixture.local"); put("machineId", "fixture-task-relay-machine")
                putJsonObject("summary") { put("text", "Diff and permissions"); put("updatedAt", now) }
            })
            val request = buildJsonObject {
                put("tool", "ExitPlanMode"); put("createdAt", now)
                putJsonObject("arguments") { put("plan", "Synthetic permission list: scroll to Deny.") }
            }
            synchronized(server) {
                val index = server.sessions.indexOfFirst { it.id == SESSION }
                server.sessions[index] = server.sessions[index].copy(agentState = server.seal(SESSION, buildJsonObject {
                    putJsonObject("requests") { put("fixture-permission", request) }
                }), agentStateVersion = 1)
            }
            server.rpc["$SESSION:bash"] = { encrypted ->
                val command = server.openRpc(SESSION, encrypted).jsonObject.getValue("command").jsonPrimitive.content
                val output = when (command) {
                    GitStatusCommands.IS_REPO -> "true\n"
                    GitStatusParser.STATUS_COMMAND, GitStatusCommands.STATUS -> "# branch.head fixture\n1 .M N... 100644 100644 100644 abc def src/reading.kt\n"
                    GitStatusParser.NUMSTAT_COMMAND -> "140\t140\tsrc/reading.kt\n---STAGED---\n"
                    GitStatusCommands.UNSTAGED_NUMSTAT -> "140\t140\tsrc/reading.kt\n"
                    GitStatusCommands.STAGED_NUMSTAT -> ""
                    GitStatusParser.diffCommand("src/reading.kt"), GitStatusParser.diffCommand("src/reading.kt", GitStatusParser.FULL_FILE_CONTEXT) -> {
                        "--- a/src/reading.kt\n+++ b/src/reading.kt\n@@ -1,140 +1,140 @@\n" + (1..140).joinToString("\n") {
                            "-val fixtureLine$it = $it\n+val fixtureLine$it = ${it + 1} // synthetic whole-line copy including the hidden horizontal tail"
                        }
                    }
                    else -> null
                }
                // Unsupported synthetic reads fail without killing the socket's
                // handler (chat's status refresh shares it with Changes).
                server.sealRpc(SESSION, buildJsonObject {
                    put("success", output != null); put("exitCode", if (output == null) 1 else 0)
                    put("stdout", output.orEmpty()); put("stderr", if (output == null) "Unsupported fixture command" else "")
                })
            }
            server.rpc["$SESSION:permission"] = { encrypted ->
                answers += server.openRpc(SESSION, encrypted).jsonObject
                server.sealRpc(SESSION, buildJsonObject { put("success", true) })
            }
            return answers
        }
        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
    }
}