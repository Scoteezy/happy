package com.slopus.happy.ui.chat

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.setFrame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDetailScrollViewTest {
    private lateinit var context: Context
    private lateinit var scroll: MessageDetailScrollView
    private val original = listOf(Rect(16, 16, 288, 100), Rect(16, 132, 288, 200), Rect(16, 348, 288, 400))
    private val larger = listOf(Rect(16, 16, 288, 200), Rect(16, 232, 288, 400), Rect(16, 648, 288, 800))

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        environment(1f)
        scroll = MessageDetailScrollView(context)
        scroll.show(plan(original, 800), environment(1f))
        layout()
    }

    private fun environment(scale: Float, density: Int = 160): TextLayouts.Env {
        Theme.install(context, Configuration(context.resources.configuration).apply {
            fontScale = scale
            densityDpi = density
        })
        return TextLayouts.Env(Theme.density, Theme.textScale)
    }

    private fun plan(frames: List<Rect>, height: Int, width: Int = 320, nested: Boolean = false): ChatRowPlan {
        val text = TextLayouts.build("Reading piece", ChatTextStyles.detailMessage, 288.0,
            TextLayouts.Env(Theme.density, Theme.textScale), Theme.color(Palette.text))
        val items = frames.map { ChatDrawItem.Text(text, it) }.toMutableList<ChatDrawItem>()
        if (nested) items.add(0, ChatDrawItem.Scroller(
            ChatRowPlan("nested", Size(600, 40), listOf(ChatDrawItem.Text(text, Rect(0, 0, 600, 40)))),
            Rect(0, 0, 320, 40), 0.0))
        return ChatRowPlan("message", Size(width, height), items)
    }

    private fun layout(width: Int = 320) = scroll.setFrame(Rect(0, 0, width, 200))

    @Test fun preservesTheFractionInsideTheSamePieceAfterTextSizeChanges() {
        scroll.scrollTo(0, 232)
        scroll.show(plan(larger, 1500), environment(2f))
        assertEquals(432, scroll.scrollY)
        layout()
        assertEquals(432, scroll.scrollY)
        assertEquals(1500, scroll.canvas.height)
    }

    @Test fun preservesTheGapBeforeTheNextPieceWithoutScalingIt() {
        scroll.scrollTo(0, 124)
        scroll.show(plan(larger, 1500), environment(2f))
        assertEquals(224, scroll.scrollY)
    }

    @Test fun startStaysAtStartAndMissingTextPiecesKeepOnlyThePlatformClamp() {
        scroll.show(plan(larger, 1500), environment(2f))
        assertEquals(0, scroll.scrollY)
        scroll.scrollTo(0, 800)
        scroll.show(plan(emptyList(), 300), environment(1f))
        assertEquals(100, scroll.scrollY)
    }

    @Test fun restorationClampsToNewBottomIncludingSafePadding() {
        scroll.setPadding(0, 0, 0, 20)
        layout()
        scroll.scrollTo(0, 600)
        scroll.show(plan(listOf(Rect(16, 16, 288, 30), Rect(16, 62, 288, 40), Rect(16, 118, 288, 80)), 230), environment(0.5f))
        assertEquals(50, scroll.scrollY)
        layout()
        assertEquals(50, scroll.scrollY)
    }

    @Test fun pastLastTextKeepsItsOffsetThenClampsInsteadOfInventingAnAnchor() {
        scroll.show(plan(original, 1000), environment(1f))
        scroll.scrollTo(0, 800)
        scroll.show(plan(listOf(Rect(16, 16, 288, 100)), 600), environment(2f))
        assertEquals(400, scroll.scrollY)
    }

    @Test fun widthOnlyChangesDoNotRestoreATextAnchor() {
        scroll.scrollTo(0, 232)
        scroll.show(plan(larger, 1500, width = 240), environment(1f))
        layout(240)
        assertEquals(232, scroll.scrollY)
    }

    @Test fun capturesWhereTheReaderIsWhenThePreparedPlanIsApplied() {
        val prepared = plan(larger, 1500)
        scroll.scrollTo(0, 232)
        // The reader moves while asynchronous preparation is finishing.
        scroll.scrollTo(0, 300)
        scroll.show(prepared, environment(2f))
        assertEquals(568, scroll.scrollY)
    }

    @Test fun successivePlansBeforeParentLayoutEachUseTheirInstalledExtent() {
        scroll.scrollTo(0, 232)
        scroll.show(plan(larger, 1500), environment(2f))
        val largest = listOf(Rect(16, 16, 288, 300), Rect(16, 332, 288, 600), Rect(16, 948, 288, 1200))
        scroll.show(plan(largest, 2200), environment(3f))
        assertEquals(632, scroll.scrollY)
        layout()
        assertEquals(632, scroll.scrollY)
    }

    @Test fun noDeferredRestorationOverwritesAScrollAfterApplyingThePlan() {
        scroll.scrollTo(0, 232)
        scroll.show(plan(larger, 1500), environment(2f))
        scroll.scrollTo(0, 500)
        layout()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(500, scroll.scrollY)
    }

    @Test fun convertsOldInstalledPixelsToDpAndNewAnchorBackToNewPixels() {
        scroll.scrollTo(0, 232)
        val env = environment(2f, 320)
        scroll.show(plan(larger, 1500), env)
        assertEquals(864, scroll.scrollY)
        layout()
        assertEquals(864, scroll.scrollY)
    }

    @Test fun densityRelayoutBeforeNewTextArrivesUsesTheActualDisplayedFrames() {
        scroll.scrollTo(0, 232)
        val env = environment(2f, 320)
        layout()
        // The existing frames now draw at 2px/dp: top=116dp, 16dp before piece 1.
        assertEquals(232, scroll.scrollY)
        scroll.show(plan(larger, 1500), env)
        assertEquals(432, scroll.scrollY)
    }

    @Test fun onlyTopLevelTextPiecesParticipateAsInTheNativeCanvas() {
        scroll.show(plan(original, 800, nested = true), environment(1f))
        layout()
        scroll.scrollTo(0, 232)
        scroll.show(plan(larger, 1500, nested = true), environment(2f))
        assertEquals(432, scroll.scrollY)
    }
}