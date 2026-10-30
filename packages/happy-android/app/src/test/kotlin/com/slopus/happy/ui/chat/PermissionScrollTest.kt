package com.slopus.happy.ui.chat

import android.app.Activity
import android.content.res.Configuration
import android.os.SystemClock
import android.os.Looper
import android.view.MotionEvent
import androidx.core.widget.NestedScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.chat.*
import com.slopus.happy.chat.tools.PermissionChoice
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.setFrame
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class PermissionScrollTest {
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private lateinit var activity: Activity
    private lateinit var renderer: ChatRowRenderer

    @Before fun setUp() {
        activity = controller.setup().get()
        Theme.install(activity, Configuration(activity.resources.configuration).apply { fontScale = 1f; densityDpi = 160 })
        renderer = ChatRowRenderer(activity.resources)
    }

    @After fun tearDown() { controller.pause().stop().destroy() }

    private fun plan(cap: Double = 80.0, tablet: Boolean = false, busy: PermissionChoice? = null,
        status: PermissionStatus = PermissionStatus.Pending, id: String = "permission-tool"): ChatRowPlan {
        val call = ChatMessage.ToolCallMessage(id, 0, ToolCall(name = "ExitPlanMode", createdAt = 0,
            state = ToolState.Running, input = Json.parseToJsonElement("""{"plan":"A synthetic plan"}"""),
            permission = ToolPermission("request-1", status)))
        return renderer.plan(ChatRowInput(ChatRow.Tool(call), permissionBusy = busy,
            style = ChatRowStyle(env = TextLayouts.Env(Theme.density, Theme.textScale), tablet = tablet,
                permissionListMaxHeight = cap)), if (tablet) 800.0 else 393.0)
    }

    private fun scroller(plan: ChatRowPlan) = plan.items.filterIsInstance<ChatDrawItem.Scroller>().single { it.vertical }

    @Test fun overflowMovesAllButtonsAndActionsIntoTheCappedList() {
        val plan = plan()
        val scroll = scroller(plan)
        assertEquals(80.0, scroll.frame.height, 0.0)
        assertTrue(scroll.plan.size.height > scroll.frame.height)
        assertEquals(4, scroll.plan.hits.filter { it.action is ChatRowAction.Permission }.size)
        assertTrue(plan.hits.none { it.action is ChatRowAction.Permission })
        assertEquals(0.0, scroll.plan.hits.first().frame.minY, 0.0)
        assertEquals(scroll.plan.size.height, scroll.plan.hits.last().frame.maxY, 0.0)
    }

    @Test fun shortListsStayInlineAndTabletButtonsKeepTheirTrailingAlignment() {
        val short = plan(cap = 260.0)
        assertTrue(short.items.filterIsInstance<ChatDrawItem.Scroller>().none { it.vertical })
        assertEquals(4, short.hits.filter { it.action is ChatRowAction.Permission }.size)
        val tablet = scroller(plan(tablet = true))
        for (hit in tablet.plan.hits) assertEquals(tablet.plan.size.width, hit.frame.maxX, 1e-9)
    }

    @Test fun busyAndAnsweredListsKeepTheirPresentationButHaveNoPermissionHits() {
        val busy = scroller(plan(busy = PermissionChoice.AllowEverything))
        assertTrue(busy.plan.hits.isEmpty())
        assertEquals(1, busy.plan.items.filterIsInstance<ChatDrawItem.Pulse>().size)
        val answered = scroller(plan(status = PermissionStatus.Approved))
        assertTrue(answered.plan.hits.isEmpty())
        assertTrue(answered.plan.items.filterIsInstance<ChatDrawItem.Text>().any { it.alpha < 1 })
    }

    @Test fun actualNestedScrollDispatchesTheLastAnswerAndKeepsOffsetOnlyForItsOwner() {
        val row = ChatRowView(activity)
        activity.setContentView(row)
        var answer: ChatRowAction.Permission? = null
        var deliveredFrame: Rect? = null
        row.onAction = { action, frame, _ -> answer = action as? ChatRowAction.Permission; deliveredFrame = frame }
        val plan = plan()
        row.bind(plan)
        row.setFrame(Rect(0.0, 0.0, plan.size.width, plan.size.height))
        val scroll = (0 until row.childCount).map(row::getChildAt).filterIsInstance<NestedScrollView>().single()
        scroll.scrollTo(0, 10_000)
        assertTrue(scroll.scrollY > 0)
        val item = scroller(plan)
        val last = item.plan.hits.last()
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(now, now + 20, action, last.frame.midX.toFloat(), last.frame.midY.toFloat() - scroll.scrollY, 0)
                .also { scroll.dispatchTouchEvent(it); it.recycle() }
        }
        assertEquals(PermissionChoice.Deny, answer?.choice)
        assertEquals("request-1", answer?.answer?.requestId)
        assertEquals(last.frame.offsetBy(item.frame.minX, item.frame.minY - scroll.scrollY), deliveredFrame)
        val offset = scroll.scrollY
        val busy = plan(busy = PermissionChoice.Deny)
        row.bind(busy)
        row.setFrame(Rect(0.0, 0.0, busy.size.width, busy.size.height))
        assertTrue((0 until row.childCount).map(row::getChildAt).any { it === scroll })
        assertEquals(offset, scroll.scrollY)
        assertTrue((scroll.getChildAt(0) as ChatRowView).plan!!.hits.isEmpty())
        val other = plan(id = "another-tool")
        row.bind(other)
        row.setFrame(Rect(0.0, 0.0, other.size.width, other.size.height))
        val reset = (0 until row.childCount).map(row::getChildAt).filterIsInstance<NestedScrollView>().single()
        assertNotSame(scroll, reset)
        assertEquals(0, reset.scrollY)
    }

    @Test fun dragInsidePermissionListIsNotStolenByTheOuterChatRecycler() {
        val list = ChatListView(activity)
        activity.setContentView(list)
        var answered = false
        list.onAction = { action, _, _ -> if (action is ChatRowAction.Permission) answered = true }
        list.submit(ChatRowPlan("footer", Size(393, 0), emptyList()), listOf(plan()))
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            list.setFrame(Rect(0, 0, 393, 400))
        }
        val row = (0 until list.childCount).map(list::getChildAt).filterIsInstance<ChatRowView>()
            .single { it.plan?.id == "permission-tool" }
        val scroll = (0 until row.childCount).map(row::getChildAt).filterIsInstance<NestedScrollView>().single()
        val bounds = android.graphics.Rect(0, 0, scroll.width, scroll.height)
        list.offsetDescendantRectToMyCoords(scroll, bounds)
        val now = SystemClock.uptimeMillis()
        for (index in 0..12) {
            val action = when (index) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val y = bounds.bottom - 4 - (bounds.height() - 8) * index / 12f
            MotionEvent.obtain(now, now + index * 50L, action, bounds.exactCenterX(), y, 0).also {
                list.dispatchTouchEvent(it); it.recycle()
            }
        }
        assertTrue("Inner permission list must receive the drag", scroll.scrollY > 0)
        assertFalse("A drag must not answer a permission", answered)
    }
}