package com.slopus.happy.ui.stemcells

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import com.slopus.happy.direct.*
import com.slopus.happy.harness.StemCellsHarnessTransport
import com.slopus.happy.stemcells.StemCellCreation
import com.slopus.happy.ui.composer.AgentInputCardView
import com.slopus.happy.ui.nav.ScreenStack
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StemCellsNewWorkTransitionTest {
    @Test fun realChatKeepsEmptyComposerOverDockAndFadesTypedContentOnPop() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val context = activity.get()
        Theme.install(context)
        val fixture = StemCellsHarnessTransport(File("src/debug/assets/harness/stem-cells-computer.json").readText(), backgroundScope)
        val engine = DirectSyncEngine(DirectAgentClient(fixture, "fixture"), null, backgroundScope, EmptyCoroutineContext)
        val session = StemCellsSession.session("new-work-transition", engine)
        val home = StemCellsDirectHomeScreen(context, session, "Fixture")
        val creation = StemCellCreation("pending-space", "pending-agent", "pending-workspace", CompletableDeferred())
        val chat = StemCellChatScreen.forNewWork(context, session, creation)
        val stack = ScreenStack(context)
        context.setContentView(stack)
        try {
            stack.setScreens(listOf(home, chat))
            stack.layout(0, 0, 1080, 2400)
            chat.view.layout(0, 0, 1080, 2400)
            val card = chat.view.findViewWithTag<AgentInputCardView>("stem-input")
            val push = chat.navigationTransition(home, pushing = true)!!
            push.begin(false)
            push.apply(.4f)
            assertEquals(0f, home.stemCells.dock.alpha)
            assertEquals(1f, card.alpha)
            assertEquals(.4f, chat.view.findViewWithTag<View>("stem-transcript").alpha)
            assertEquals(102, Color.alpha((chat.view.background as ColorDrawable).color))
            push.finish(false)
            assertEquals(1f, home.stemCells.dock.alpha)
            assertEquals(255, Color.alpha((chat.view.background as ColorDrawable).color))

            card.setText("Keep this draft", animatesHeight = false)
            card.focus()
            chat.keyboard = 300.0
            assertTrue(card.isTextFocused)
            val pop = chat.navigationTransition(home, pushing = false)!!
            pop.begin(true)
            assertFalse(card.isTextFocused)
            pop.apply(.4f)
            assertEquals(.4f, card.alpha)
            assertEquals(.6f, home.stemCells.dock.alpha, .001f)
            pop.finish(true)
            assertTrue(card.isTextFocused)
            assertEquals("Keep this draft", card.text)
            assertEquals(1f, card.alpha)
            assertEquals(1f, home.stemCells.dock.alpha)
        } finally {
            stack.setScreens(emptyList())
            runCurrent()
            engine.stop()
            StemCellsSession.forget("new-work-transition")
            activity.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }
}