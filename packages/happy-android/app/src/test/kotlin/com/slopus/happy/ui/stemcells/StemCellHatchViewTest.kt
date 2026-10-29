package com.slopus.happy.ui.stemcells

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.slopus.happy.stemcells.StemCellsRow
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
class StemCellHatchViewTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun motion(scale: Float) = ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale", ClassParameter.from(Float::class.javaPrimitiveType, scale))

    private fun visible(body: (FrameLayout) -> Unit) {
        Theme.install(context)
        motion(1f)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val root = FrameLayout(activity.get())
        activity.get().setContentView(root)
        root.layout(0, 0, 1080, 2400)
        activity.windowFocusChanged(true)
        try { body(root) } finally { activity.pause().stop().destroy(); motion(1f) }
    }

    @Test fun normalCompletionDetachAndWindowLossRestoreThePictureExactlyOnce() = visible { root ->
        for (ending in listOf("complete", "detach", "background")) {
            val overlay = StemCellHatchView(context)
            root.addView(overlay)
            overlay.layout(0, 0, 64, 64)
            assertTrue("Visible attached window must allow a hatch", StemCellHatchView.canPlay(overlay))
            val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            var completed = 0
            overlay.play("seed", image) { completed++ }
            assertNotNull(overlay.parent)
            when (ending) {
                "detach" -> root.removeView(overlay)
                "background" -> overlay.onWindowFocusChanged(false)
                else -> shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2700))
            }
            overlay.finish()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, completed)
            assertNull(overlay.parent)
            assertFalse("The shared image cache owns the bitmap", image.isRecycled)
            image.recycle()
        }
    }

    @Test fun reducedMotionAndHiddenOwnersSkipAnimationButStillComplete() = visible { root ->
        for (reduced in listOf(false, true)) {
            val overlay = StemCellHatchView(context)
            root.addView(overlay)
            overlay.layout(0, 0, 64, 64)
            if (reduced) motion(0f) else root.alpha = 0f
            var completed = 0
            val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            overlay.play("seed", image) { completed++ }
            assertEquals(1, completed)
            assertNull(overlay.parent)
            root.alpha = 1f
            motion(1f)
            image.recycle()
        }
    }

    @Test fun realSidebarMarkChangeHatchesOnceAndReplacementRestoresTheMark() = visible { root ->
        val sidebar = StemCellsSidebarView(context)
        root.addView(sidebar)
        val row = StemCellsRow("space:new", "new", StemCellsRow.Kind.Space, label = "New", mark = StemCellsRow.Mark.StemCell("seed"))
        sidebar.apply(listOf(row))
        sidebar.layout(0, 0, 1080, 1800)
        val assigned = row.copy(mark = StemCellsRow.Mark.Clay("wip"))
        sidebar.apply(listOf(assigned))
        val source = sidebar.view(row.id)!!
        val overlay = source.findViewWithTag<StemCellHatchView>("stem-hatch")
        assertNotNull(overlay)
        sidebar.apply(listOf(assigned.copy(label = "Named")))
        assertSame(overlay, source.findViewWithTag<View>("stem-hatch"))
        sidebar.apply(listOf(assigned.copy(mark = StemCellsRow.Mark.Clay("travel"))))
        assertNull(source.findViewWithTag<View>("stem-hatch"))
        val picture = (0 until source.childCount).map(source::getChildAt).filterIsInstance<android.widget.ImageView>().first()
        assertEquals(1f, picture.alpha)
    }

    @Test fun realIntroHatchIsRemovedWithItsOwner() = visible { root ->
        val intro = StemCellIntroView(context)
        root.addView(intro)
        intro.configure(StemCellIntroView.Mark.StemCell("seed"), false)
        intro.place(intro.layout(400.0))
        intro.layout(0, 0, 1080, 500)
        intro.configure(StemCellIntroView.Mark.Clay("wip"), true)
        val overlay = intro.findViewWithTag<StemCellHatchView>("stem-hatch")
        assertNotNull(overlay)
        intro.configure(StemCellIntroView.Mark.Clay("wip"), true)
        assertSame(overlay, intro.findViewWithTag<View>("stem-hatch"))
        root.removeView(intro)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(intro.findViewWithTag<View>("stem-hatch"))
        val picture = (0 until intro.childCount).map(intro::getChildAt).filterIsInstance<android.widget.ImageView>().single()
        assertEquals(1f, picture.alpha)
    }
}