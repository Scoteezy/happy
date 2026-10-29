package com.slopus.happy.ui.stemcells

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.slopus.happy.direct.*
import com.slopus.happy.harness.StemCellsHarnessTransport
import com.slopus.happy.stemcells.*
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
class StemCellsHoldDragTest {
    @Test fun oneHeldPointerTransfersFromPreviewToCarryThenDropsTheNestedSibling() = gesture(cancel = false)
    @Test fun cancelledHeldPointerRestoresTheSidebarWithoutOpeningOrMovingTheRow() = gesture(cancel = true)
    @Test fun leavingHomeDuringTheCarryReleasesItWithoutDropping() = gesture(cancel = true, leaveWhileCarrying = true)

    private fun gesture(cancel: Boolean, leaveWhileCarrying: Boolean = false) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        Theme.install(context)
        val fixture = StemCellsHarnessTransport(File("src/debug/assets/harness/stem-cells-computer.json").readText(), backgroundScope)
        val engine = DirectSyncEngine(DirectAgentClient(fixture, "fixture"), null, backgroundScope, EmptyCoroutineContext)
        val session = StemCellsSession.session("hold-drag-test", engine)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val screen = StemCellsDirectHomeScreen(context, session, "Fixture")
        try {
            engine.start()
            withTimeout(60_000) { engine.updates.first { it.usesSpaces } }
            val ready = withTimeout(60_000) { session.store.updates.first { it.live && it.document != null } }
            runCurrent()
            activity.get().setContentView(screen.view)
            screen.onAppear()
            runCurrent()
            screen.view.layout(0, 0, 1080, 2400)
            val drawn = StemCellsHomeView.drawn(ready.rows)
            val row = drawn.first { it.nodeId == "agscreens" }
            val drag = StemCellsDrag.holding(row.id, drawn, ready.allRows, ready.document!!)!!
            val source = screen.stemCells.sidebar.view(row.id)!!
            val at = IntArray(2).also(source::getLocationOnScreen)
            val base = IntArray(2).also(screen.view::getLocationOnScreen)
            val x = (at[0] - base[0] + source.width / 2).toFloat()
            val y = (at[1] - base[1] + source.height / 2).toFloat()
            var opened = false
            screen.open = { opened = true }
            fun event(action: Int, y: Float, time: Long) {
                val event = MotionEvent.obtain(1, time, action, x, y, 0)
                screen.view.dispatchTouchEvent(event)
                event.recycle()
            }
            event(MotionEvent.ACTION_DOWN, y, 1)
            assertTrue(source.performLongClick())
            assertNotNull(screen.view.findViewWithTag<View>("stem-hold"))
            val movedY = y + source.height * 1.2f
            event(MotionEvent.ACTION_MOVE, movedY, 700)
            assertNull(screen.view.findViewWithTag<View>("stem-hold"))
            assertNotNull(screen.view.findViewWithTag<View>("stem-reorder"))
            assertEquals(0f, source.alpha)
            if (leaveWhileCarrying) screen.onDisappear()
            else event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, movedY, 800)
            runCurrent()
            session.store.flush()
            val actual = StemCellsHomeView.drawn(session.store.updates.value.rows).map { it.nodeId }
            assertEquals((if (cancel) drawn else drag.order(1)).map { it.nodeId }, actual)
            assertFalse("The original row's click must be cancelled on transfer", opened)
            screen.onDisappear() // immediate teardown also releases the settling overlay
            assertNull(screen.view.findViewWithTag<View>("stem-reorder"))
            assertTrue(screen.stemCells.sidebar.list.isScrollEnabled)
            assertEquals(1f, source.alpha)
        } finally {
            screen.destroy()
            engine.stop()
            StemCellsSession.forget("hold-drag-test")
            activity.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }
}