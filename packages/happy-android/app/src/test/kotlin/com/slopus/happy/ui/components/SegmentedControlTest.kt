package com.slopus.happy.ui.components

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.slopus.happy.ui.theme.Theme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SegmentedControlTest {
    @Test fun changingCapabilitiesPreservesSelectedTitleAndDropsRemovedSelection() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val context = activity.get()
            Theme.install(context)
            val control = SegmentedControl(context, listOf("Session", "Task"))
            var choices = 0
            control.onChange = { choices++ }
            control.selectedIndex = 1
            control.setItems(listOf("Session", "Bot", "Task"))
            assertEquals(2, control.selectedIndex)
            assertEquals(3, control.childCount)
            control.setItems(listOf("Session", "Bot"))
            assertEquals(0, control.selectedIndex)
            assertEquals(2, control.childCount)
            assertEquals(0, choices)
            val node = AccessibilityNodeInfo.obtain()
            control.onInitializeAccessibilityNodeInfo(node)
            assertEquals("Session", node.contentDescription)
            control.layout(0, 0, 300, 48)
            control.setItems(listOf("Session", "Bot", "Task"))
            control.layout(0, 0, 300, 48)
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 250f, 24f, 0)
            control.onTouchEvent(event)
            event.recycle()
            assertEquals(2, control.selectedIndex)
            assertEquals(1, choices)
        } finally { activity.pause().stop().destroy() }
    }
}