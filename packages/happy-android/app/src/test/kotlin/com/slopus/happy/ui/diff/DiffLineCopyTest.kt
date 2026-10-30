package com.slopus.happy.ui.diff

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.diff.DiffSource
import com.slopus.happy.layout.Rect
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.setFrame
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPopupMenu
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class DiffLineCopyTest {
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private lateinit var activity: Activity
    private lateinit var view: DiffFileView
    private val env get() = TextLayouts.Env(Theme.density, Theme.textScale)
    private val words get() = DiffSectionWords.of(activity)
    private val clipboard get() = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Before fun setUp() {
        activity = controller.setup().get()
        Theme.install(activity, Configuration(activity.resources.configuration).apply { fontScale = 1f; densityDpi = 160 })
        view = DiffFileView(activity)
        activity.setContentView(view)
        clipboard.setPrimaryClip(ClipData.newPlainText(null, "unchanged"))
    }

    @After fun tearDown() { controller.pause().stop().destroy() }

    private fun plan(text: String, selectable: Boolean = true) = DiffFilePlan(
        DiffSource.Contents("file.kt", "", text).build().files.single(), 360.0, env, words,
        DiffViewOptions(showHunkHeaders = false, selectable = selectable), syntaxDeadlineNanos = null)

    private fun show(plan: DiffFilePlan) {
        view.apply(plan)
        view.setFrame(Rect(0.0, 0.0, 360.0, plan.height))
    }

    private fun hold(code: View, y: Float) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 40f, y, 0).also { code.dispatchTouchEvent(it); it.recycle() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 40))
        MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 40f, y, 0).also { code.dispatchTouchEvent(it); it.recycle() }
    }

    @Test fun actualLongPressMenuCopiesTheWholeLineAfterHorizontalScroll() {
        val text = "val longLine = " + "x".repeat(1000)
        val plan = plan(text)
        show(plan)
        val scroll = view.getChildAt(0) as HorizontalScrollView
        scroll.scrollTo(400, 0)
        hold(scroll.getChildAt(0), (plan.layout.lineHeight / 2).toFloat())
        val menu = ShadowPopupMenu.getLatestPopupMenu()
        assertNotNull(menu)
        assertEquals("Copy", menu.menu.getItem(0).title.toString())
        assertTrue(menu.menu.performIdentifierAction(menu.menu.getItem(0).itemId, 0))
        assertEquals(text, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun applyingAnotherPlanInvalidatesAnOpenCopyAction() {
        show(plan("old line"))
        hold((view.getChildAt(0) as HorizontalScrollView).getChildAt(0), 9f)
        val menu = ShadowPopupMenu.getLatestPopupMenu()!!
        show(plan("new line"))
        assertFalse(menu.menu.performIdentifierAction(menu.menu.getItem(0).itemId, 0))
        assertEquals("unchanged", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun nonSelectableToolsAndHunkRowsHaveNoCopyText() {
        assertNull(plan("line", selectable = false).selectableText(9.0))
        val patch = "--- a/file.kt\n+++ b/file.kt\n@@ -1 +1 @@\n-before\n+after"
        val file = DiffFilePlan(DiffSource.Patch(patch).build().files.single(), 360.0, env, words,
            DiffViewOptions(selectable = true), syntaxDeadlineNanos = null)
        assertNull(file.selectableText(file.layout.lineHeight / 2))
        assertEquals("before", file.selectableText(file.layout.lineHeight * 1.5))
        assertNull(file.selectableText(-1.0))
        assertNull(file.selectableText(file.height + 1))
        assertEquals(" ", plan("\n").selectableText(9.0))
    }

    @Test fun themeRefreshOfActualChatDiffDoesNotReenterItself() {
        val chunk = DiffChunkView(activity)
        chunk.apply(DiffChunkPlan(DiffChunkSource(oldText = "a", newText = "b", fileName = "a.kt"), 360.0, env, words))
        activity.setContentView(chunk)
        Theme.applyTree(chunk, relayout = true)
        chunk.setFrame(Rect(0.0, 0.0, 360.0, chunk.plan!!.size.height))
        assertEquals(1, chunk.childCount)
    }
}