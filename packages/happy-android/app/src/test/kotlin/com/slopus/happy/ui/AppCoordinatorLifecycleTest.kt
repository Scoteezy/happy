package com.slopus.happy.ui

import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import com.slopus.happy.AppGraph
import com.slopus.happy.testing.SoftwareKeys
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.isActive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppCoordinatorLifecycleTest {
    private class TrackedScreen(context: Context) : Screen(context) {
        var destroys = 0
        var disappears = 0
        override fun createView() = View(context)
        override fun onDestroy() { destroys++ }
        override fun onDisappear() { disappears++ }
    }

    @Test fun activityDestructionDestroysEveryRetainedScreenAndItsScope() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        Theme.install(activity)
        val graph = AppGraph(activity, "coordinator-lifecycle-fixture", "http://127.0.0.1:1", SoftwareKeys(), databaseName = null)
        val coordinator = AppCoordinator(activity, graph)
        activity.setContentView(coordinator.root)
        val home = TrackedScreen(activity)
        val detail = TrackedScreen(activity)
        val stack = coordinator.root.stack
        stack.setScreens(listOf(home, detail))
        try {
            controller.pause().stop().destroy()
            assertEquals("A destroyed activity cannot retain screen watchers or presentations", 0, stack.size)
            assertEquals(1, home.destroys)
            assertEquals(1, detail.destroys)
            assertEquals(1, detail.disappears)
            assertFalse(home.scope.isActive)
            assertFalse(detail.scope.isActive)
            assertNull(home.stack)
            assertNull(detail.stack)
        } finally {
            // Also release the deliberately leaked pre-fix screens when the regression fails.
            stack.setScreens(emptyList())
        }
    }
}