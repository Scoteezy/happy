package com.slopus.happy.ui.stemcells

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import com.slopus.happy.direct.*
import com.slopus.happy.harness.StemCellsHarnessTransport
import com.slopus.happy.harness.StoriesHarnessTransport
import com.slopus.happy.stories.STORIES_SETTING_KEY
import com.slopus.happy.ui.nav.ScreenStack
import com.slopus.happy.ui.stories.StoriesViewerPresenter
import com.slopus.happy.ui.stories.StoriesSession
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StemCellsStoriesHomeTest {
    private suspend fun TestScope.withHome(check: suspend (StemCellsDirectHomeScreen, ScreenStack, ComponentActivity, () -> Unit) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        var destroyed = false
        Theme.install(activity)
        val root = Json.parseToJsonElement(File("src/debug/assets/harness/stem-cells-computer.json").readText()).jsonObject
        val settings = root.getValue("settings").jsonObject + (STORIES_SETTING_KEY to JsonPrimitive(true))
        val fixture = StemCellsHarnessTransport(JsonObject(root + ("settings" to JsonObject(settings))).toString(), backgroundScope,
            fallback = StoriesHarnessTransport(System.currentTimeMillis()))
        val engine = DirectSyncEngine(DirectAgentClient(fixture, "fixture"), null, backgroundScope, EmptyCoroutineContext)
        val session = StemCellsSession.session("stories-home", engine)
        val home = StemCellsDirectHomeScreen(activity, session, "Fixture")
        val stack = ScreenStack(activity)
        activity.setContentView(stack)
        try {
            engine.start()
            stack.setScreens(listOf(home))
            val decor = activity.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 1080, 2400)
            stack.layout(0, 0, 1080, 2400)
            home.view.layout(0, 0, 1080, 2400)
            session.stories.store.load()
            repeat(100) {
                runCurrent()
                if (home.stemCells.stories.isShown) return@repeat
                withContext(Dispatchers.Default) { delay(10) }
            }
            assertTrue("The real Direct home must show its computer's Stories", home.stemCells.stories.isShown)
            check(home, stack, activity) { controller.pause().stop().destroy(); destroyed = true }
        } finally {
            stack.setScreens(emptyList())
            runCurrent()
            engine.stop()
            StemCellsSession.forget("stories-home")
            if (!destroyed) controller.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }

    @Test fun realHomeInstallsRowAndKeepsHeldScrollPositionWhenClosingIt() = runTest {
        withHome { home, _, _, _ ->
            val stories = home.stemCells.stories
            val list = home.stemCells.sidebar.list
            assertSame(stories.view, home.header.accessory)
            assertNotNull(stories.view.photos)
            assertEquals(3, stories.view.items.size)
            assertTrue("Android accessibility clips descendants to their parent's actual bounds",
                home.header.height >= stories.view.pager.bottom)
            assertTrue(stories.unlocked)
            assertEquals(96.0, stories.inset, .001)
            val closedTop = list.insetTop - stories.inset
            stories.carrying = true
            list.setContentOffset(-closedTop)
            assertTrue("Reorder edge-scrolling must not close Stories", stories.unlocked)
            list.setContentOffset(-list.insetTop)
            stories.carrying = false
            list.listener!!.willBeginDragging()
            list.setContentOffset(-closedTop)
            assertFalse(stories.unlocked)
            // The room is not changed underneath a held finger.
            assertEquals(96.0, stories.inset, .001)
            val offset = list.contentOffset
            val target = doubleArrayOf(offset)
            list.listener!!.willEndDragging(0.0, target)
            assertEquals(0.0, stories.inset, .001)
            assertEquals(closedTop, list.insetTop, .001)
            assertEquals(offset, list.contentOffset, .001)
            list.listener!!.didEndDragging(false)
            stories.view.onExpand!!.invoke()
            assertTrue(stories.unlocked)
            assertEquals(96.0, stories.inset, .001)
            assertEquals(-list.insetTop, list.contentOffset, .001)
        }
    }

    @Test fun putAwayIsPerComputerAndUnsupportedHomeHasNoStoriesInset() = runTest {
        withHome { home, _, _, _ ->
            val stories = home.stemCells.stories
            val list = home.stemCells.sidebar.list
            list.listener!!.willBeginDragging()
            list.setContentOffset(-(list.insetTop - stories.inset))
            list.listener!!.willEndDragging(0.0, doubleArrayOf(list.contentOffset))
            list.listener!!.didEndDragging(false)
            stories.attach(home.session.stories)
            runCurrent()
            assertFalse("The same computer remembers that its stories were put away", stories.unlocked)
            val other = StoriesSession("stories-home-other", home.session.client, null, null) { kotlinx.coroutines.flow.emptyFlow() }
            try {
                stories.attach(other)
                assertTrue("Account changes clear old author IDs synchronously", stories.view.items.isEmpty())
                other.store.load()
                runCurrent()
                assertTrue("The first computer's preference must not close another's row", stories.unlocked)
                stories.isSuppressed = true
                assertFalse(stories.isShown)
                assertEquals(0.0, stories.inset, .001)
                assertEquals(View.GONE, stories.view.visibility)
            } finally {
                stories.attach(null)
                other.close()
            }
        }
    }

    @Test fun accountDetachRemovesAnOpeningViewerAndClearsEveryOldCircle() = runTest {
        withHome { home, _, activity, _ ->
            val stories = home.stemCells.stories
            stories.view.onOpen!!.invoke(stories.view.items.first())
            var viewer = StoriesViewerPresenter.presented(activity.window.decorView)
            var tries = 0
            while (viewer == null && tries++ < 100) {
                runCurrent()
                withContext(Dispatchers.Default) { delay(10) }
                viewer = StoriesViewerPresenter.presented(activity.window.decorView)
            }
            assertNotNull("A row opens the real viewer, including before its animation completes", viewer)
            stories.attach(null)
            assertNull(StoriesViewerPresenter.presented(activity.window.decorView))
            assertNull(viewer!!.parent)
            assertTrue(stories.view.items.isEmpty())
            assertNull(stories.view.circle("self"))
            assertEquals(View.GONE, stories.view.visibility)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull("An old opening callback must not reattach the viewer", viewer.parent)
            assertNull(home.header.titleX)
        }
    }

    @Test fun destroyingTheActivityClosesItsStillOpeningViewer() = runTest {
        withHome { home, stack, activity, destroy ->
            val feed = home.session.stories.snapshot.feed!!
            val viewer = StoriesViewerPresenter.present(home.session.stories, feed, 0, host = home.view, origin = null)!!
            // This presentation is deliberately still opening when the window is destroyed.
            destroy()
            assertNull(viewer.parent)
            assertNull(StoriesViewerPresenter.presented(activity.window.decorView))
            stack.setScreens(emptyList())
        }
    }

}