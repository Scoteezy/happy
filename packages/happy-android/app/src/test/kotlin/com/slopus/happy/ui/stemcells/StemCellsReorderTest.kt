package com.slopus.happy.ui.stemcells

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.slopus.happy.layout.Rect
import com.slopus.happy.stemcells.*
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.dp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class StemCellsReorderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val rows = listOf(
        StemCellsRow("work", "work", StemCellsRow.Kind.Section, label = "Work"),
        StemCellsRow("a", "a", StemCellsRow.Kind.Space, label = "A"),
        StemCellsRow("b", "b", StemCellsRow.Kind.Space, label = "B"),
        StemCellsRow("next", "next", StemCellsRow.Kind.Section, label = "Next"),
        StemCellsRow("c", "c", StemCellsRow.Kind.Space, label = "C"),
    )
    private val document = SpaceSidebar.parse("<nav><happy-search/><happy-create/><happy-section id=\"work\" label=\"Work\"/><happy-space id=\"a\"/><happy-space id=\"b\"/><happy-section id=\"next\" label=\"Next\"/><happy-space id=\"c\"/></nav>")
    private fun sidebar() = StemCellsSidebarView(context).also {
        Theme.install(context)
        it.apply(rows)
        it.layout(0, 0, 1080, 1800)
    }
    private fun drag(id: String) = StemCellsDrag.holding(id, rows, rows, document)!!
    private fun order(sidebar: StemCellsSidebarView) = rows.sortedBy { sidebar.view(it.id)!!.frames!!.box.minY }.map { it.id }

    @Test fun sectionLiftsItsWholeBlockAndCancellationRestoresIt() {
        val sidebar = sidebar()
        val drag = drag("work")
        val picture = sidebar.prepareReorder(drag)!!
        assertFalse(sidebar.list.isScrollEnabled)
        sidebar.list.layoutSubviews(400.0, 700.0)
        for (id in listOf("work", "a", "b")) assertEquals(0f, sidebar.view(id)!!.alpha)
        assertEquals(2, picture.heights.size)
        sidebar.arrangeReorder(drag, 1)
        assertEquals(listOf("next", "c", "work", "a", "b"), order(sidebar))
        sidebar.endReorder(dropped = false)
        sidebar.revealReorder()
        assertEquals(rows.map { it.id }, order(sidebar))
        assertTrue(sidebar.list.isScrollEnabled)
        for (row in rows) assertEquals(1f, sidebar.view(row.id)!!.alpha)
        picture.image.recycle()
    }

    @Test fun structuralChangesInvalidateACarryButStatusUpdatesDoNot() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val sidebar = sidebar()
        activity.get().setContentView(sidebar)
        val picture = sidebar.prepareReorder(drag("a"))!!
        var cancelled = 0
        sidebar.onReorderInvalidated = { cancelled++; sidebar.endReorder(false); sidebar.revealReorder() }
        sidebar.apply(rows.map { it.copy(status = StemCellsRow.Status.Working) })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, cancelled)
        sidebar.apply(rows.filter { it.id != "a" })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, cancelled)
        assertTrue(sidebar.list.isScrollEnabled)
        picture.image.recycle()
        activity.pause().stop().destroy()
    }

    @Test fun touchDropAndAccessibilityDropUseTheSameSlotAndEndExactlyOnce() {
        for (accessible in listOf(false, true)) {
            val sidebar = sidebar()
            val drag = drag("a")
            val picture = sidebar.prepareReorder(drag)!!
            val overlay = StemCellsReorderOverlay(context, sidebar, drag, picture, picture.frame, "A")
            overlay.layout(0, 0, 1080, 1800)
            val drops = mutableListOf<Int>()
            var ends = 0
            overlay.onDrop = { drops += it }
            overlay.onEnd = { ends++ }
            if (accessible) {
                val card = overlay.findViewWithTag<View>("stem-lifted")
                assertTrue(card.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                card.performClick()
            } else {
                fun event(action: Int, y: Double) {
                    val event = MotionEvent.obtain(0, 10, action, dp(100.0), dp(y), 0)
                    overlay.onTouchEvent(event)
                    event.recycle()
                }
                event(MotionEvent.ACTION_DOWN, 100.0)
                event(MotionEvent.ACTION_MOVE, 100.0 + picture.heights[2])
                event(MotionEvent.ACTION_UP, 100.0 + picture.heights[2])
            }
            overlay.cancel(animated = false) // teardown during settling must not send another edit/end
            assertEquals(listOf(2), drops)
            assertEquals(1, ends)
            assertTrue(picture.image.isRecycled)
            assertTrue(sidebar.list.isScrollEnabled)
            assertEquals(1f, sidebar.view("a")!!.alpha)
        }
    }

    @Test fun tapAndBackCancelWithoutSendingADrop() {
        for (tap in listOf(false, true)) {
            val sidebar = sidebar()
            val drag = drag("a")
            val picture = sidebar.prepareReorder(drag)!!
            val overlay = StemCellsReorderOverlay(context, sidebar, drag, picture, Rect(0.0, 0.0, 300.0, 34.0), "A")
            overlay.layout(0, 0, 1080, 1800)
            overlay.onDrop = { fail("Cancellation sent a drop") }
            var ends = 0
            overlay.onEnd = { ends++ }
            if (tap) {
                val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 100f, 100f, 0)
                overlay.onTouchEvent(event)
                event.recycle()
            }
            overlay.cancel(animated = false)
            assertEquals(1, ends)
            assertEquals(rows.map { it.id }, order(sidebar))
        }
    }
}