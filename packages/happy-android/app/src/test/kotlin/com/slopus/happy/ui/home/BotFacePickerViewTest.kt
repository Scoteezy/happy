package com.slopus.happy.ui.home

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.layout.Rect
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.setFrame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class BotFacePickerViewTest {
    @Test fun fourAccessibleChoicesKeepFramesWhenSelectionChangesAndBlockWhileStarting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Theme.install(context)
        val picker = BotFacePickerView(context)
        val seeds = listOf("a", "b", "c", "d")
        picker.configure(seeds, 0)
        picker.setFrame(Rect(0, 0, 333, 110))
        fun named(label: String): View = (0 until picker.childCount).map(picker::getChildAt).single { it.contentDescription == label }
        val faces = (1..4).map { named("Face $it") }
        assertEquals(listOf(true, false, false, false), faces.map { it.isSelected })
        val frames = faces.map { listOf(it.left, it.top, it.width, it.height) }
        var picked = -1
        var rolls = 0
        var refused = 0
        picker.onPick = { picked = it; picker.configure(seeds, it) }
        picker.onRoll = { rolls++ }
        picker.onRefuse = { refused++ }
        faces[2].performClick()
        assertEquals(2, picked)
        assertEquals(listOf(false, false, true, false), faces.map { it.isSelected })
        assertEquals(frames, faces.map { listOf(it.left, it.top, it.width, it.height) })
        named("Roll four new faces").performClick()
        assertEquals(1, rolls)
        assertEquals("android.widget.Button", named("Roll four new faces").createAccessibilityNodeInfo().className)
        picker.refusing = true
        faces[0].performClick()
        named("Roll four new faces").performClick()
        named(BotFacePickerView.ARTIST).performClick()
        assertEquals(3, refused)
        assertEquals(2, picked)
        assertEquals(1, rolls)
    }

    @Test fun artistCreditOpensTheBundledArtistsPage() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        Theme.install(context)
        val picker = BotFacePickerView(context)
        (0 until picker.childCount).map(picker::getChildAt).single { it.contentDescription == BotFacePickerView.ARTIST }.performClick()
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
        assertEquals(BotFacePickerView.ARTIST_URL, intent.dataString)
    }
}