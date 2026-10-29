package com.slopus.happy.ui.stemcells

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.slopus.happy.direct.*
import com.slopus.happy.harness.StemCellsHarnessTransport
import com.slopus.happy.harness.StoriesHarnessTransport
import com.slopus.happy.harness.StoriesShellFixture
import com.slopus.happy.stemcells.StemCellsRow
import com.slopus.happy.ui.chat.ChatDrawItem
import com.slopus.happy.ui.chat.ChatRowPlan
import com.slopus.happy.ui.chat.ChatRowView
import com.slopus.happy.ui.nav.ScreenStack
import com.slopus.happy.ui.stories.*
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StemCellsInlineStoryTest {
    private class Fixture(
        val chat: StemCellChatScreen,
        val session: StemCellsSession,
        val stack: ScreenStack,
        val activity: ComponentActivity,
        val requests: List<Pair<String, String>>,
        val destroyActivity: () -> Unit,
    ) {
        val card: StoryInlineView get() = chat.view.findViewWithTag("story-inline:d1")
        val underlay: View get() = (activity.window.decorView as ViewGroup).getChildAt(0)
    }

    private suspend fun TestScope.await(message: String, condition: () -> Boolean) {
        repeat(300) {
            runCurrent()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(10) }
        }
        fail(message)
    }

    private suspend fun TestScope.withChat(check: suspend Fixture.() -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        Theme.install(activity)
        var destroyed = false
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val computer = StemCellsHarnessTransport(
            StoriesShellFixture.apply(File("src/debug/assets/harness/stem-cells-computer.json").readText()),
            backgroundScope, fallback = StoriesHarnessTransport(System.currentTimeMillis()),
        )
        val transport = object : DirectTransport by computer {
            override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
                requests += method to path
                return computer.request(method, path, credential, body, headers)
            }
        }
        val engine = DirectSyncEngine(DirectAgentClient(transport, "fixture"), null, backgroundScope, EmptyCoroutineContext)
        val session = StemCellsSession.session("inline-story", engine)
        val row = StemCellsRow("space:spprio", "spprio", StemCellsRow.Kind.Space, label = "Prioritizing", spaceId = "spprio", targetKind = "task", agentId = "agprio")
        val chat = StemCellChatScreen.forRow(activity, session, row)!!
        val stack = ScreenStack(activity)
        activity.setContentView(stack)
        (activity.window.decorView as ViewGroup).getChildAt(0).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        try {
            engine.start()
            stack.setScreens(listOf(chat))
            val decor = activity.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 1080, 2400)
            stack.layout(0, 0, 1080, 2400)
            chat.view.layout(0, 0, 1080, 2400)
            session.stories.store.load()
            await("The real conversation must render the tool's live draft card") {
                chat.view.layout(0, 0, 1080, 2400)
                chat.view.findViewWithTag<StoryInlineView>("story-inline:d1")?.state?.startsWith("draft#") == true
            }
            Fixture(chat, session, stack, activity, requests) {
                controller.pause().stop().destroy()
                destroyed = true
            }.check()
        } finally {
            stack.setScreens(emptyList())
            runCurrent()
            engine.stop()
            StemCellsSession.forget("inline-story")
            if (!destroyed) controller.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }

    @Test fun actualConversationPostsTheDraftAndOpensItsViewerThenCleansUpOnRemoval() = runTest {
        withChat {
            assertTrue(card.width > 0 && card.height > 0)
            assertTrue("The card reads this computer's Stories; its draft may already be in the feed",
                requests.any { it.first == "GET" && it.second.startsWith("/v0/stories") })
            val canvas = card.getChildAt(0)
            card.findViewWithTag<View>("story-inline-post").performClick()
            await("Posting must update the same live card") { card.state.startsWith("posted#") }
            assertEquals(1, requests.count { it == "POST" to "/v0/stories/d1/post" })
            canvas.performClick()
            val viewer = StoriesViewerPresenter.presented(activity.window.decorView)
            assertNotNull(viewer)
            assertSame(canvas, viewer!!.origin)
            assertEquals("More", viewer.findViewWithTag<View>("story-more").contentDescription)
            assertEquals("TalkBack must not reach the chat under a full-screen viewer", View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                underlay.importantForAccessibility)
            stack.setScreens(emptyList())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull("No old-account viewer can remain or finish opening", StoriesViewerPresenter.presented(activity.window.decorView))
            assertNull(viewer.parent)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, underlay.importantForAccessibility)
        }
    }

    @Test fun editorUsesTheConversationDraftAndCannotOutliveItsOwningScreen() = runTest {
        withChat {
            card.findViewWithTag<View>("story-inline-edit").performClick()
            val editor = StoriesEditorPresenter.presented(activity.window.decorView)
            assertNotNull(editor)
            assertEquals("d1", editor!!.draft.original?.id)
            assertEquals("TalkBack must not reach the chat under a full-screen editor", View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                underlay.importantForAccessibility)
            stack.setScreens(emptyList())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(StoriesEditorPresenter.presented(activity.window.decorView))
            assertNull(editor.parent)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, underlay.importantForAccessibility)
            assertFalse(requests.any { it.first == "POST" && it.second.startsWith("/v0/stories/") })
        }
    }

    @Test fun activityDestructionRemovesAnEditorStillOpening() = runTest {
        withChat {
            card.findViewWithTag<View>("story-inline-edit").performClick()
            val editor = StoriesEditorPresenter.presented(activity.window.decorView)!!
            destroyActivity()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(StoriesEditorPresenter.presented(activity.window.decorView))
            assertNull(editor.parent)
        }
    }

    @Test fun replanningKeepsTheSameLiveCardAndRemovingItReleasesTheBinding() = runTest {
        withChat {
            val card = card
            val row = card.parent as ChatRowView
            val plan = row.plan!!
            val story = plan.items.filterIsInstance<ChatDrawItem.Story>().single()
            row.bind(ChatRowPlan(plan.id, plan.size, listOf(ChatDrawItem.Story(story.id, story.frame))))
            assertSame(card, row.findViewWithTag("story-inline:d1"))
            row.bind(ChatRowPlan(plan.id, plan.size, emptyList()))
            assertNull(card.parent)
            assertNull(row.findViewWithTag<View>("story-inline:d1"))
        }
    }

    @Test fun existingCardFollowsStoriesDisabledRestoredAndDeletedStates() = runTest {
        withChat {
            val original = card
            session.stories.store.setEnabled(false)
            await("Disabling Stories must remove the draft's actions") { card.state == "gone" }
            assertEquals(View.GONE, card.findViewWithTag<View>("story-inline-post").visibility)
            session.stories.store.setEnabled(true)
            await("Re-enabling Stories must restore the card without reopening chat") { card.state.startsWith("draft#") }
            session.stories.store.delete("d1")
            await("Deleting the story must update its existing conversation card") { card.state == "deleted" }
            assertSame(original, card)
            assertEquals(View.GONE, card.findViewWithTag<View>("story-inline-edit").visibility)
        }
    }
}