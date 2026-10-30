package com.slopus.happy.harness

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.slopus.happy.AppGraph
import com.slopus.happy.MainActivity
import com.slopus.happy.chat.ChatFeed
import com.slopus.happy.chat.NoLivePresence
import com.slopus.happy.chat.StoreChatSessionSource
import com.slopus.happy.chat.SyncChatMessagesSource
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.chat.MessageDetailReadingPlace
import com.slopus.happy.testing.FakeHappyServer
import com.slopus.happy.ui.chat.ChatDrawItem
import com.slopus.happy.ui.chat.MessageDetailScreen
import com.slopus.happy.ui.chat.MessageDetailScrollView
import com.slopus.happy.ui.home.HomeScreen
import com.slopus.happy.ui.nav.ScreenStack
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.abs

/** Debug-only real-MainActivity entry and read-only geometry observer for system font-size replay. */
class MessageReadingCapture(private val app: Application, private val graph: AppGraph) : Application.ActivityLifecycleCallbacks {
    private var job: Job? = null
    private var owner: MainActivity? = null

    init {
        check(app.packageName == "com.slopus.happy.dev.shellharness")
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity || owner != null) return
        owner = activity
        job = activity.lifecycleScope.launch {
            val account = withTimeout(30_000) { graph.account.filterNotNull().first() }
            account.syncNow()
            val messageId = withTimeout(30_000) {
                ChatFeed(SyncChatMessagesSource(account.messages, account.sender), StoreChatSessionSource(graph.store), NoLivePresence)
                    .state(SESSION).first { it.snapshot.loaded && it.snapshot.rows.isNotEmpty() }.snapshot.rows.single().message.id
            }
            val stack = withTimeout(30_000) {
                var found: ScreenStack? = null
                while (found == null) {
                    found = descendants(activity.window.decorView).filterIsInstance<ScreenStack>()
                        .firstOrNull { it.top is HomeScreen }
                    if (found == null) delay(50)
                }
                found
            }
            val detail = MessageDetailScreen(activity, graph, SESSION, messageId)
            stack.push(detail, animated = false)
            val scroll = descendants(detail.view).filterIsInstance<MessageDetailScrollView>().single()
            scroll.contentDescription = "Reading position fixture"
            val file = File(app.filesDir, "message-reading-evidence.json")
            while (isActive) {
                scroll.canvas.plan?.let { plan ->
                    val texts = plan.items.filterIsInstance<ChatDrawItem.Text>()
                    val frames = texts.map { it.frame }
                    val top = scroll.scrollY / Theme.density.toDouble()
                    val place = MessageDetailReadingPlace.at(top, frames)
                    val expectedTextSize = ChatTextStyles.detailMessage.scaled(Theme.textScale).size * Theme.density
                    val report = buildJsonObject {
                        put("realMainActivity", true)
                        put("fontScale", Theme.textScale.multiplier)
                        put("density", Theme.density)
                        put("top", top)
                        put("viewport", scroll.height / Theme.density.toDouble())
                        put("contentHeight", plan.size.height)
                        put("settled", texts.isNotEmpty() && abs(texts.first().layout.paint.textSize - expectedTextSize) < 0.1 &&
                            abs(scroll.canvas.height - plan.size.height * Theme.density) <= 1)
                        put("piece", place?.piece ?: -1)
                        put("fraction", place?.fraction ?: 0.0)
                        put("above", place?.above ?: 0.0)
                        putJsonArray("frames") { frames.forEach { frame -> add(buildJsonObject {
                            put("y", frame.minY); put("height", frame.height)
                        }) } }
                    }
                    withContext(Dispatchers.IO) {
                        val temporary = File(file.parentFile, "message-reading-evidence.tmp")
                        temporary.writeText(report.toString())
                        check(temporary.renameTo(file))
                    }
                }
                delay(100)
            }
        }
    }

    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        app.unregisterActivityLifecycleCallbacks(this@MessageReadingCapture)
        job?.cancelAndJoin()
        job = null
        owner = null
    }

    override fun onActivityDestroyed(activity: Activity) { if (owner === activity) job?.cancel() }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}

    companion object {
        const val SESSION = "fixture-reading-position"
        fun seed(server: FakeHappyServer) {
            val now = System.currentTimeMillis()
            server.addSession(SESSION, now, buildJsonObject {
                put("path", "/fixture/reading"); put("host", "fixture.local"); put("machineId", "fixture-machine")
                putJsonObject("summary") { put("text", "Reading position"); put("updatedAt", now) }
            })
            val text = (1..140).joinToString("\n") { "Reading line $it — a synthetic message with enough words to wrap as the text size changes." }
            server.addMessage(SESSION, 1, buildJsonObject {
                put("role", "user")
                putJsonObject("content") { put("type", "text"); put("text", text) }
            }, now)
        }

        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
    }
}