package com.slopus.happy.ui.components

import android.app.Activity
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.slopus.happy.icons.Icon
import com.slopus.happy.ui.theme.Theme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class SwipeActionsTest {
    private class Fixture(compact: Boolean = false) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val context = activity.get()
        val root = FrameLayout(context)
        val list = RecyclerView(context)
        var available = true
        var clicks = 0
        var actions = 0
        val swipe: SwipeActions
        val row: View get() = list.getChildAt(0)

        init {
            Theme.install(context)
            context.setContentView(root)
            root.addView(list, FrameLayout.LayoutParams(-1, -1))
            list.layoutManager = LinearLayoutManager(context)
            list.itemAnimator = null
            swipe = SwipeActions(list) {
                if (available) SwipeActions.Action("Leave task", Icon.ionicons("exit-outline"), compact = compact) { actions++ } else null
            }
            list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 1
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val view = View(context).apply {
                        layoutParams = RecyclerView.LayoutParams(-1, ((if (compact) 48 else 100) * Theme.density).toInt())
                        setOnClickListener { clicks++ }
                    }
                    return object : RecyclerView.ViewHolder(view) {}
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = swipe.rowBound(holder)
            }
            val width = (400 * Theme.density).toInt()
            val height = (800 * Theme.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            idle()
            assertEquals(1, list.childCount)
        }

        fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))

        fun gesture(from: Float, to: Float, y: Float = row.height / 2f, move: Boolean = true) {
            val start = SystemClock.uptimeMillis()
            fun event(action: Int, x: Float, elapsed: Long) {
                val event = MotionEvent.obtain(start, start + elapsed, action, x, y, 0)
                list.dispatchTouchEvent(event)
                event.recycle()
            }
            event(MotionEvent.ACTION_DOWN, from, 0)
            if (move) event(MotionEvent.ACTION_MOVE, to, 100)
            event(MotionEvent.ACTION_UP, to, 200)
            idle()
        }

        fun open() = gesture(list.width * 0.85f, list.width * 0.25f)
        fun close() { activity.pause().stop().destroy() }
    }

    private fun withList(compact: Boolean = false, work: (Fixture) -> Unit) {
        val fixture = Fixture(compact)
        try { work(fixture) } finally { fixture.close() }
    }

    @Test fun unsupportedHorizontalDragsDoNotBecomeClicksButTapsStillWork() = withList { f ->
        f.available = false
        f.gesture(f.list.width * 0.85f, f.list.width * 0.25f)
        f.gesture(f.list.width * 0.25f, f.list.width * 0.85f)
        assertEquals(0, f.clicks)
        assertEquals(0, f.actions)
        assertEquals(0f, f.row.translationX, 0.01f)
        f.gesture(f.list.width / 2f, f.list.width / 2f, move = false)
        assertEquals(1, f.clicks)
    }

    @Test fun rightDragOnASupportedRowDoesNotOpenOrClick() = withList { f ->
        f.gesture(f.list.width * 0.25f, f.list.width * 0.85f)
        assertEquals(0, f.clicks)
        assertEquals(0, f.actions)
        assertEquals(0f, f.row.translationX, 0.01f)
    }

    @Test fun leftDragOnlyRevealsAndCapsuleTapInvokesTheAction() = withList { f ->
        f.open()
        assertEquals(0, f.clicks)
        assertEquals(0, f.actions)
        assertEquals(-117 * Theme.density, f.row.translationX, 0.1f)
        val x = f.list.width - 58.5f * Theme.density
        f.gesture(x, x, y = f.row.height / 2f - 9.5f * Theme.density, move = false)
        assertEquals(1, f.actions)
        assertEquals(0f, f.row.translationX, 0.1f)
    }

    @Test fun rebindAndDetachClearTheOldSwipe() = withList { f ->
        f.open()
        f.available = false
        f.list.adapter!!.notifyItemChanged(0)
        f.idle()
        assertEquals(0f, f.row.translationX, 0.1f)
        f.available = true
        f.open()
        val row = f.row
        f.root.removeView(f.list)
        assertEquals(0f, row.translationX, 0.1f)
        assertEquals(0, f.actions)
    }

    @Test fun compactPillFitsTheShortRowAndItsWholeColumnIsTappable() = withList(compact = true) { f ->
        f.open()
        val holder = f.list.getChildViewHolder(f.row)
        val rect = ReflectionHelpers.callInstanceMethod<RectF>(f.swipe, "capsuleRect", ClassParameter.from(RecyclerView.ViewHolder::class.java, holder))
        assertTrue(rect.top >= f.row.top)
        assertTrue(rect.bottom <= f.row.bottom)
        assertEquals(40 * Theme.density, rect.height(), 0.1f)
        // Below the painted pill, but within the accessible 48dp row-height hit target.
        f.gesture(rect.centerX(), rect.centerX(), y = f.row.bottom - 1f, move = false)
        assertEquals(1, f.actions)
        assertEquals(0, f.clicks)
    }
}