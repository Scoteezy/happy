package com.slopus.happy.ui.diff

import android.app.Activity
import android.content.res.Configuration
import android.database.sqlite.SQLiteOpenHelper
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.widget.NestedScrollView
import com.slopus.happy.AppGraph
import com.slopus.happy.harness.DiffPermissionCapture
import com.slopus.happy.layout.Rect
import com.slopus.happy.model.GitStatusCommands
import com.slopus.happy.sync.eventually
import com.slopus.happy.testing.FakeHappyServer
import com.slopus.happy.testing.SoftwareKeys
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.chat.ChatScreen
import com.slopus.happy.ui.chat.ChatRowView
import com.slopus.happy.ui.chat.ChatRowAction
import com.slopus.happy.ui.details.SessionChangesScreen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class SessionChangesScreenTest {
    @Test fun actualChangesRouteLoadsTheEncryptedFixtureAndExpandsItsFile() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        fun theme(scale: Float) = Theme.install(activity, Configuration(activity.resources.configuration).apply {
            fontScale = scale; densityDpi = 160
        })
        theme(1f)
        val server = FakeHappyServer().start()
        DiffPermissionCapture.seed(server)
        val graph = AppGraph(activity, "diff-permission-test", server.url, SoftwareKeys(), databaseName = null)
        var screen: SessionChangesScreen? = null
        var chat: ChatScreen? = null
        try {
            runBlocking {
                graph.auth.restoreStored()
                graph.auth.signIn(server.token, server.secret)
                graph.setForeground(true)
                val account = withTimeout(10_000) { graph.account.filterNotNull().first() }
                account.syncNow()
                withTimeout(10_000) { account.connectionStatus.first { it == com.slopus.happy.net.ConnectionStatus.CONNECTED } }
                for (command in listOf(GitStatusCommands.IS_REPO, GitStatusCommands.STATUS,
                    GitStatusCommands.UNSTAGED_NUMSTAT, GitStatusCommands.STAGED_NUMSTAT)) {
                    assertTrue(account.sessionOps.bash(DiffPermissionCapture.SESSION, command).success)
                }
                assertFalse(account.sessionOps.bash(DiffPermissionCapture.SESSION, "unsupported synthetic read").success)
            }
            screen = SessionChangesScreen(activity, graph, DiffPermissionCapture.SESSION)
            activity.setContentView(screen.view)
            val list = descendants(screen.view).filterIsInstance<DiffFilesListView>().single()
            eventually(message = "actual Changes plans") {
                shadowOf(Looper.getMainLooper()).idle()
                screen.view.setFrame(Rect(0, 0, 393, 800))
                list.plans.isNotEmpty()
            }
            assertEquals("src/reading.kt", list.plans.single().id)
            descendants(screen.view).filterIsInstance<DiffFileHeaderView>().single().performClick()
            eventually(message = "expanded Changes body") {
                shadowOf(Looper.getMainLooper()).idle()
                screen.view.setFrame(Rect(0, 0, 393, 800))
                list.plans.single().body is DiffSectionPlan.Body.Files
            }
            assertEquals(281, (list.plans.single().body as DiffSectionPlan.Body.Files).plans.single().rows.size)
            chat = ChatScreen(activity, graph, DiffPermissionCapture.SESSION)
            activity.setContentView(chat.view)
            fun awaitChat(scrolling: Boolean) = eventually(message = "chat permissions scrolling=$scrolling") {
                shadowOf(Looper.getMainLooper()).idle()
                chat.view.setFrame(Rect(0, 0, 393, 800))
                val views = descendants(chat.view).toList()
                views.filterIsInstance<ChatRowView>().sumOf { row -> row.plan?.hits?.count { it.action is ChatRowAction.Permission } ?: 0 } == 4 &&
                    views.filterIsInstance<NestedScrollView>().any { it.getChildAt(0) is ChatRowView } == scrolling
            }
            awaitChat(false)
            theme(3f)
            Theme.applyTree(chat.view, relayout = true)
            awaitChat(true)
            val scroll = descendants(chat.view).filterIsInstance<NestedScrollView>().single { it.getChildAt(0) is ChatRowView }
            assertEquals(com.slopus.happy.layout.chat.PermissionFooterLayout.maxListHeight(chat.view.rootView.height.toDouble()).toInt(), scroll.height)
            assertTrue(scroll.getChildAt(0).height > scroll.height)
            theme(1f)
            Theme.applyTree(chat.view, relayout = true)
            awaitChat(false)
        } catch (failure: Throwable) {
            val views = screen?.view?.let(::descendants)?.toList().orEmpty()
            throw AssertionError("labels=${views.filterIsInstance<Label>().map { it.text }} list=${views.filterIsInstance<DiffFilesListView>().map { "${it.width}x${it.height}, visible=${it.visibility}, plans=${it.plans.size}" }} acks=${server.rpcAckSent} failures=${server.socketFailures}", failure)
        } finally {
            screen?.destroy()
            chat?.destroy()
            runBlocking {
                graph.logout().join()
                for (name in listOf("scope", "directScope")) {
                    val scope = AppGraph::class.java.getDeclaredField(name).apply { isAccessible = true }.get(graph) as CoroutineScope
                    scope.coroutineContext[Job]?.cancelAndJoin()
                }
            }
            val databases = AppGraph::class.java.getDeclaredField("databases").apply { isAccessible = true }.get(graph) as Map<*, *>
            databases.values.forEach { (it as SQLiteOpenHelper).close() }
            server.close()
            controller.pause().stop().destroy()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}