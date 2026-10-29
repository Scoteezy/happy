package com.slopus.happy.ui.stemcells

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.test.core.app.ApplicationProvider
import com.slopus.happy.config.ThemePreference
import com.slopus.happy.direct.*
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.EmptyCoroutineContext

/** The real Settings view, with session requests deliberately completed out of order. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StemCellsSettingsBindingTest {
    @Test fun theInitiallyMissingSessionBindsWithoutAProfileSwitch() = checkBinding { host, screen ->
        assertFalse(hidden(screen))
        host.deliver("a", makeSession("a", hidden = true))
        runCurrent()
        assertTrue(hidden(screen))
    }

    @Test fun aLateSessionCannotReplaceTheQuicklySelectedProfile() = checkBinding { host, screen ->
        carousel(screen.view).onSelect!!(1)
        runCurrent()
        host.deliver("b", makeSession("b", hidden = false))
        runCurrent()
        host.deliver("a", makeSession("a", hidden = true))
        runCurrent()
        assertTrue(screen.probe.contains("settings=b"))
        assertFalse(hidden(screen))
        carousel(screen.view).onSelect!!(0)
        runCurrent()
        assertTrue(hidden(screen))
    }

    @Test fun removingTheShownComputerWhileItsSessionIsPendingBindsTheRemainingOne() = checkBinding { host, screen ->
        host.profiles = host.profiles.filter { it.id != "a" }.map { it.copy(isActive = true) }
        host.changed()
        host.deliver("a", makeSession("a", hidden = true))
        runCurrent()
        assertTrue(screen.probe.contains("settings=b"))
        assertFalse(hidden(screen))
        host.deliver("b", makeSession("b", hidden = true))
        runCurrent()
        assertTrue(hidden(screen))
        host.profiles = emptyList()
        host.changed()
        runCurrent()
        place(screen.view)
        assertNull(screen.view.findViewWithTag<View>("stem-settings-toggle:hide-projects"))
    }

    private fun checkBinding(block: suspend TestScope.(PendingHost, StemCellsSettingsScreen) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        Theme.install(context)
        val host = PendingHost(backgroundScope)
        val screen = StemCellsSettingsScreen(context, host, "a")
        try {
            screen.view
            runCurrent()
            block(host, screen)
        } finally {
            screen.onDestroy()
            screen.scope.cancel()
            listOf("a", "b").forEach { StemCellsSession.forget(it) }
            Dispatchers.resetMain()
        }
    }

    private suspend fun TestScope.makeSession(id: String, hidden: Boolean): StemCellsSession {
        val transport = object : DirectTransport {
            override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>) = DirectHttpResponse(503)
        }
        val engine = DirectSyncEngine(DirectAgentClient(transport, "fixture"), null, backgroundScope, EmptyCoroutineContext)
        engine.setProjectsHidden(hidden)
        return StemCellsSession.session(id, engine)
    }

    private fun hidden(screen: StemCellsSettingsScreen): Boolean {
        place(screen.view)
        return screen.view.findViewWithTag<CompoundButton>("stem-settings-toggle:hide-projects").isChecked
    }

    private fun place(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1080, 2400)
    }

    private fun carousel(view: View): StemCellsProfileCarouselView = descendants(view).filterIsInstance<StemCellsProfileCarouselView>().first()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private class PendingHost(scope: CoroutineScope) : StemCellsSettingsHost {
        override var profiles = listOf("a", "b").map { StemCellsSettingsHost.Profile(it, it, "Connecting", true, it == "a") }
        var changed: () -> Unit = {}
        private val sessions = HashMap<String, StemCellsSession>()
        private val pending = listOf("a", "b").associateWith { CompletableDeferred<StemCellsSession>() }
        init {
            for ((id, response) in pending) scope.launch {
                sessions[id] = response.await()
                changed()
            }
        }
        fun deliver(id: String, session: StemCellsSession) { pending.getValue(id).complete(session) }
        override fun watch(changed: () -> Unit): () -> Unit { this.changed = changed; return { this.changed = {} } }
        override fun session(id: String) = sessions[id]
        override suspend fun switchTo(id: String) { profiles = profiles.map { it.copy(isActive = it.id == id) }; changed() }
        override fun connections(id: String) = StemCellsSettingsHost.Connections()
        override var directConnection = true
        override var theme = ThemePreference.Adaptive
        override val version = "fixture"
        override suspend fun rename(id: String, name: String): Unit = error("unexpected rename")
        override suspend fun remove(id: String): Unit = error("unexpected remove")
        override fun addComputer(from: Screen): Unit = error("unexpected navigation")
        override fun addHappyAccount(from: Screen): Unit = error("unexpected navigation")
        override fun open(page: StemCellsSettingsHost.Page, id: String, from: Screen): Unit = error("unexpected navigation")
        override fun connect(connection: StemCellsSettingsHost.Connection, connected: Boolean, id: String, from: Screen): Unit = error("unexpected connection")
        override fun scan(from: Screen): Unit = error("unexpected scan")
        override fun enterLink(from: Screen): Unit = error("unexpected pairing")
        override fun toggleDeveloperMode(): Boolean = error("unexpected preference")
    }
}