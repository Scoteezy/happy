package com.slopus.happy.ui.diff

import android.app.Activity
import android.content.res.Configuration
import android.os.Looper
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.diff.*
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.diff.DiffFilesListLayout
import com.slopus.happy.layout.diff.DiffReadingPlace
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DiffFilesListViewTest {
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private lateinit var activity: Activity
    private lateinit var list: DiffFilesListView
    private var gate: CompletableDeferred<Unit>? = null
    private var heldScale: Float? = null
    private var width = 360
    private val item = DiffFileItem("changes.kt", FileChangeKind.Added, 200, 0,
        DiffSource.Contents(path = "changes.kt", oldText = "", newText = (1..200).joinToString("\n") { "val line$it = $it" }))

    @Before fun setUp() {
        activity = controller.setup().get()
        environment(1f)
        val words = DiffSectionWords.of(activity)
        list = DiffFilesListView(activity, scope) { inputs, notices, empty, width, env ->
            if (env.scale.multiplier.toFloat() == heldScale) gate?.await()
            inputs.map { DiffSectionPlan(it, width, env, words, { source -> source.build() }, null) } to
                DiffListChrome(notices, empty, width, env)
        }
        activity.setContentView(list, android.view.ViewGroup.LayoutParams(360, 300))
        list.update(listOf(item), defaultCollapsed = false)
        drain()
    }

    @After fun tearDown() {
        scope.cancel()
        controller.pause().stop().destroy()
    }

    private fun environment(scale: Float) {
        Theme.install(activity, Configuration(activity.resources.configuration).apply { fontScale = scale; densityDpi = 160 })
    }

    private fun drain() {
        repeat(3) {
            list.setFrame(Rect(0, 0, width, 300))
            scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun font(scale: Float) {
        environment(scale)
        Theme.applyTree(list, relayout = true)
        drain()
    }

    private fun model(plans: List<DiffSectionPlan>) = DiffFilesListLayout(width.toDouble(), null, plans.map { it.layout.height }, Size(0, 0))
    private fun place(top: Double = list.readingTop, plans: List<DiffSectionPlan> = list.plans) =
        DiffReadingPlace.at(top, model(plans), plans.map { it.readingSection })!!

    private fun scroll(amount: Int) {
        (list.getChildAt(0) as RecyclerView).scrollBy(0, amount)
        drain()
    }

    @Test fun sameBoundsFontChangeRemeasuresAndPreservesTheActualCodeRow() {
        scroll(363)
        val old = list.plans.single()
        val anchor = place()
        font(1.35f)
        assertNotSame(old, list.plans.single())
        assertEquals(1.35f.toDouble(), list.plans.single().env.scale.multiplier, 0.0)
        assertEquals(anchor.y(model(list.plans), list.plans.map { it.readingSection })!!, list.readingTop, 0.5)
        assertTrue((list.plans.single().body as DiffSectionPlan.Body.Files).plans.single().options.selectable)
    }

    @Test fun repeatedSizesDoNotAccumulateRoundingDriftAndWidthOnlyKeepsOffset() {
        scroll(363)
        val start = list.readingTop
        val anchor = place()
        for (scale in listOf(1.35f, 0.823f, 2.143f, 1f)) {
            font(scale)
            assertEquals(anchor.y(model(list.plans), list.plans.map { it.readingSection })!!, list.readingTop, 0.5)
        }
        assertEquals(start, list.readingTop, 0.0)
        width = 340
        drain()
        assertEquals(start, list.readingTop, 0.0)
    }

    @Test fun scrollingDuringMeasurementUsesTheLatestPosition() {
        scroll(363)
        val oldPlans = list.plans
        heldScale = 1.7f
        gate = CompletableDeferred()
        font(1.7f)
        assertSame(oldPlans, list.plans)
        scroll(121)
        val anchor = place(plans = oldPlans)
        gate!!.complete(Unit)
        drain()
        assertEquals(anchor.y(model(list.plans), list.plans.map { it.readingSection })!!, list.readingTop, 0.5)
    }

    @Test fun resetAndNewerEnvironmentRejectInFlightPlans() {
        heldScale = 1.7f
        gate = CompletableDeferred()
        font(1.7f)
        list.reset()
        gate!!.complete(Unit)
        drain()
        assertTrue(list.plans.isEmpty())
        list.update(listOf(item), defaultCollapsed = false)
        font(1.2f)
        assertEquals(1.2f.toDouble(), list.plans.single().env.scale.multiplier, 0.0)
    }

    @Test fun aDifferentFileDoesNotReuseTheOldLineFraction() {
        scroll(363)
        val top = list.readingTop
        environment(1.5f)
        list.update(listOf(item.copy(path = "different.kt")), defaultCollapsed = false)
        drain()
        assertEquals(top, list.readingTop, 0.0)
        assertEquals("different.kt", list.plans.single().id)
    }
}