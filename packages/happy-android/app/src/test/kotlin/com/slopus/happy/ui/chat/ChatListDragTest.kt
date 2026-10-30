package com.slopus.happy.ui.chat

import android.app.Activity
import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.ui.components.TextField
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.setFrame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ChatListDragTest {
    @Test fun actualDragDismissesEditingWithoutChangingDraftButProgrammaticScrollDoesNot() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        Theme.install(activity, Configuration(activity.resources.configuration).apply { fontScale = 1f; densityDpi = 160 })
        try {
            val root = FrameLayout(activity).apply { isFocusable = true; isFocusableInTouchMode = true }
            val list = ChatListView(activity)
            val editor = TextField(activity, ChatTextStyles.headerTitle, multiline = true)
            root.addView(list); root.addView(editor)
            activity.setContentView(root)
            var drags = 0
            list.onDragBegan = { drags++; editor.dismissKeyboard() }
            list.submit(ChatRowPlan("footer", Size(393, 0), emptyList()),
                (1..20).map { ChatRowPlan("row-$it", Size(393, 100), emptyList()) })
            repeat(3) {
                shadowOf(Looper.getMainLooper()).idle()
                list.setFrame(Rect(0, 0, 393, 400))
                editor.setFrame(Rect(0, 400, 393, 80))
            }
            editor.setText("Keep this draft")
            editor.setSelection(5)
            editor.requestFocus()
            assertTrue(editor.hasFocus())
            list.scrollBy(0, -100)
            list.scrollToNewest(animated = false)
            assertEquals(0, drags)
            assertTrue(editor.hasFocus())
            val now = SystemClock.uptimeMillis()
            for (index in 0..10) {
                val action = when (index) { 0 -> MotionEvent.ACTION_DOWN; 10 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                MotionEvent.obtain(now, now + index * 50L, action, 150f, 100f + index * 20, 0).also {
                    list.dispatchTouchEvent(it); it.recycle()
                }
            }
            assertEquals(1, drags)
            assertFalse(editor.hasFocus())
            assertEquals("Keep this draft", editor.string)
            assertEquals(5, editor.selectionStart)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun phoneTitleClipsAtLargeTextWhileTabletTitleKeepsNativeOverflow() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        Theme.install(activity, Configuration(activity.resources.configuration).apply { fontScale = 3f; densityDpi = 160 })
        try {
            val header = ChatHeaderView(activity)
            activity.setContentView(header)
            header.update("Large synthetic title", "workspace", null, null)
            header.setFrame(Rect(0, 0, 393, 52))
            assertTrue(header.titleControl.clipToOutline)
            assertTrue(header.titleControl.contentDescription.contains("Large synthetic title"))
            header.tablet = true
            header.setFrame(Rect(0, 0, 800, 56))
            assertFalse(header.plainControl.clipToOutline)
        } finally { controller.pause().stop().destroy() }
    }
}