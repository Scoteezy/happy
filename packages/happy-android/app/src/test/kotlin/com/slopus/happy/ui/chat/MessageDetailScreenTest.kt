package com.slopus.happy.ui.chat

import android.content.Context
import android.content.res.Configuration
import android.database.sqlite.SQLiteOpenHelper
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.AppGraph
import com.slopus.happy.auth.AuthState
import com.slopus.happy.chat.ChatFeed
import com.slopus.happy.chat.NoLivePresence
import com.slopus.happy.chat.StoreChatMessagesSource
import com.slopus.happy.chat.StoreChatSessionSource
import com.slopus.happy.data.MessageRecord
import com.slopus.happy.data.SessionRecord
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.chat.MessageDetailReadingPlace
import com.slopus.happy.sync.eventually
import com.slopus.happy.testing.SoftwareKeys
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class MessageDetailScreenTest {
    /** Signed-out preview seams use the actual store/feed/renderer, without any network or credentials. */
    private class Fixture(private val toolArgs: String? = null, private val focusFile: String? = null) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph: AppGraph
        var beforePlan: (MessageDetailInput) -> Unit = {}
        var afterPlan: (MessageDetailInput) -> Unit = {}
        lateinit var screen: MessageDetailScreen
            private set
        lateinit var scroll: MessageDetailScrollView
            private set

        init {
            theme(1f)
            graph = AppGraph(context, "reading-position-fixture", "http://127.0.0.1:1", SoftwareKeys(), databaseName = null)
            try {
                eventually { graph.storeAccount.value == AuthState.SignedOut }
                graph.store.upsertSessions(listOf(SessionRecord("reading-session", 1, 1, 1, false, 1,
                    """{"path":"/synthetic/reading","host":"fixture"}""", 1, null, 0, "", null)))
                val text = (1..140).joinToString("\n") { "Reading line $it — a synthetic message with enough words to wrap as the text size changes." }
                val raw = if (toolArgs == null) {
                    """{"role":"user","content":{"type":"text","text":${JsonPrimitive(text)}}}"""
                } else {
                    """{"role":"agent","content":{"type":"session","data":{"id":"patch-envelope","time":1,"role":"agent","turn":"patch-turn","ev":{"t":"tool-call-start","call":"patch-call","name":"CodexPatch","title":"Update files","description":"Synthetic patch","args":$toolArgs}}}}"""
                }
                check(com.slopus.happy.chat.ChatNormalizer.normalize("reading-message", null, 1,
                    kotlinx.serialization.json.Json.parseToJsonElement(raw)) != null) { "Invalid fixture wire message" }
                graph.store.applyNewestPage("reading-session", listOf(MessageRecord("reading-session", 1, "reading-message", null,
                    raw, 1, 1)), false)
                // Routes use the reducer's stable UI id, not the raw server record id.
                val messageId = runBlocking {
                    ChatFeed(StoreChatMessagesSource(graph.store), StoreChatSessionSource(graph.store), NoLivePresence)
                        .state("reading-session").first { it.snapshot.loaded }.snapshot.rows.single().message.id
                }
                val renderer = MessageDetailRenderer(context.resources, ChatRowRenderer(context.resources))
                screen = MessageDetailScreen(context, graph, "reading-session", messageId, focusFile = focusFile) { input, width ->
                    beforePlan(input)
                    renderer.plan(input, width).also { afterPlan(input) }
                }
                screen.view.setFrame(Rect(0, 0, 320, 600))
                scroll = descendants(screen.view).filterIsInstance<MessageDetailScrollView>().single()
                await { scroll.canvas.plan != null && scroll.visibility == View.VISIBLE }
                layout()
                if (toolArgs == null) assertTrue(frames().size >= 3)
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }

        fun theme(scale: Float) = Theme.install(context, Configuration(context.resources.configuration).apply {
            fontScale = scale
            densityDpi = 160
        })

        fun layout() = screen.view.setFrame(Rect(0, 0, 320, 600))
        fun changeSize(scale: Float) {
            theme(scale)
            Theme.applyTree(screen.view, relayout = true)
            layout()
        }
        fun frames() = scroll.canvas.plan!!.items.filterIsInstance<ChatDrawItem.Text>().map { it.frame }
        fun await(predicate: () -> Boolean) = eventually(message = "actual message screen") {
            shadowOf(Looper.getMainLooper()).idle()
            predicate()
        }

        fun close() {
            if (::screen.isInitialized) screen.destroy()
            runBlocking {
                for (name in listOf("scope", "directScope")) {
                    val scope = AppGraph::class.java.getDeclaredField(name).apply { isAccessible = true }.get(graph) as CoroutineScope
                    scope.coroutineContext[Job]?.cancelAndJoin()
                }
            }
            val databases = AppGraph::class.java.getDeclaredField("databases").apply { isAccessible = true }.get(graph) as Map<*, *>
            databases.values.forEach { (it as SQLiteOpenHelper).close() }
        }

        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
    }

    @Test fun actualScreenKeepsTheLatestReadingPlaceWhenAsyncMeasurementFinishes() {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val oldPlan = fixture.scroll.canvas.plan
            val frames = fixture.frames()
            val firstTop = (frames[1].minY + frames[1].height * 0.25).roundToInt()
            val laterTop = (frames[1].minY + frames[1].height * 0.75).roundToInt()
            fixture.scroll.scrollTo(0, firstTop)
            fixture.beforePlan = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            fixture.changeSize(1.6f)
            fixture.await { entered.count == 0L }
            fixture.scroll.scrollTo(0, laterTop)
            val place = MessageDetailReadingPlace.at(laterTop.toDouble(), frames)!!
            release.countDown()
            fixture.await { fixture.scroll.canvas.plan !== oldPlan }
            assertEquals(place.top(fixture.frames())!!.roundToInt(), fixture.scroll.scrollY)
            assertTrue(fixture.scroll.canvas.height > oldPlan!!.size.height)
            fixture.layout()
            assertEquals(place.top(fixture.frames())!!.roundToInt(), fixture.scroll.scrollY)
        } finally {
            release.countDown()
            fixture.close()
        }
    }

    @Test fun actualStoreFeedScreenPreservesFocusedFileIndexWhenExpanding() {
        // A continuous add block may extend 200 rows beyond the 80-row cap.
        val lines = (1..350).joinToString("\n") { "val line$it = $it" }
        val input = """{"changes":{"a.kt":{"type":"add","content":"first"},"b.kt":{"type":"add","content":${JsonPrimitive(lines)}}}}"""
        val fixture = Fixture(input, "b.kt")
        try {
            val plan = fixture.scroll.canvas.plan!!
            assertEquals(listOf("b.kt"), plan.items.filterIsInstance<ChatDrawItem.DiffHeader>().map { it.path })
            val diff = plan.items.filterIsInstance<ChatDrawItem.Diff>().single()
            assertEquals(1, diff.chunk)
            assertTrue(diff.plan.files.single().hiddenCount > 0)
            fixture.scroll.canvas.onAction!!.invoke(ChatRowAction.DiffShowMore(plan.id, diff.chunk, 0), diff.frame, false)
            fixture.await { fixture.scroll.canvas.plan !== plan }
            val expanded = fixture.scroll.canvas.plan!!.items.filterIsInstance<ChatDrawItem.Diff>().single()
            assertEquals(1, expanded.chunk)
            assertEquals(0, expanded.plan.files.single().hiddenCount)
            assertTrue(expanded.frame.height > diff.frame.height)
        } finally {
            fixture.close()
        }
    }

    @Test fun aCompletedPlanWithOldTextEnvironmentIsRejectedBeforeTheNextLayout() {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = AtomicBoolean()
        try {
            val original = fixture.scroll.canvas.plan
            fixture.beforePlan = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            fixture.afterPlan = { returned.set(true) }
            fixture.changeSize(1.5f)
            fixture.await { entered.count == 0L }
            // The new theme is visible before its next layout starts another measurement.
            fixture.theme(2f)
            release.countDown()
            fixture.await { returned.get() }
            fixture.await { fixture.screen.scope.coroutineContext[Job]!!.children.count() == 1 }
            assertSame(original, fixture.scroll.canvas.plan)
            fixture.beforePlan = {}
            fixture.changeSize(2f)
            fixture.await { fixture.scroll.canvas.plan !== original }
        } finally {
            release.countDown()
            fixture.close()
        }
    }

    @Test fun destroyingTheScreenRejectsAnAlreadyRunningMeasurement() {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = AtomicBoolean()
        try {
            val original = fixture.scroll.canvas.plan
            fixture.beforePlan = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            fixture.afterPlan = { returned.set(true) }
            fixture.changeSize(1.5f)
            fixture.await { entered.count == 0L }
            fixture.screen.destroy()
            assertFalse(fixture.screen.scope.isActive)
            release.countDown()
            fixture.await { returned.get() }
            fixture.await { fixture.screen.scope.coroutineContext[Job]!!.isCompleted }
            assertSame(original, fixture.scroll.canvas.plan)
        } finally {
            release.countDown()
            fixture.close()
        }
    }
}