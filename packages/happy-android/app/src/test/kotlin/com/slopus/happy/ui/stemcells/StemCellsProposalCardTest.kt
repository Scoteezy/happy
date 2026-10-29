package com.slopus.happy.ui.stemcells

import android.view.View
import androidx.activity.ComponentActivity
import com.slopus.happy.direct.*
import com.slopus.happy.harness.ProposalsShellFixture
import com.slopus.happy.harness.StemCellsHarnessTransport
import com.slopus.happy.stemcells.StemCellsRow
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.nav.ScreenStack
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StemCellsProposalCardTest {
    private class Fixture(val chat: StemCellChatScreen, val session: StemCellsSession, val requests: List<Pair<String, String>>) {
        val card: StemCellSidebarProposalView get() = chat.view.findViewWithTag("stem-proposal")
        val status: String get() = card.findViewWithTag<Label>("stem-proposal-status").text.toString()
    }

    private suspend fun TestScope.await(message: String, condition: () -> Boolean) {
        repeat(300) {
            runCurrent()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(10) }
        }
        fail(message)
    }

    private suspend fun TestScope.withChat(preview: Boolean = false, check: suspend Fixture.() -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        Theme.install(activity)
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val computer = StemCellsHarnessTransport(
            ProposalsShellFixture.apply(File("src/debug/assets/harness/stem-cells-computer.json").readText()), backgroundScope,
        )
        val transport = object : DirectTransport by computer {
            override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
                requests += method to path
                return computer.request(method, path, credential, body, headers)
            }
        }
        val engine = DirectSyncEngine(DirectAgentClient(transport, "fixture"), null, backgroundScope, EmptyCoroutineContext)
        val session = StemCellsSession.session("proposal-card", engine)
        val row = StemCellsRow("space:spprio", "spprio", StemCellsRow.Kind.Space, label = "Prioritizing", spaceId = "spprio", targetKind = "task", agentId = "agprio")
        val chat = StemCellChatScreen.forRow(activity, session, row, previewing = preview)!!
        val stack = ScreenStack(activity)
        activity.setContentView(stack)
        try {
            engine.start()
            stack.setScreens(listOf(chat))
            val decor = activity.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 1080, 2400)
            stack.layout(0, 0, 1080, 2400)
            await("The real tool presentation must become a live proposal card") {
                chat.view.layout(0, 0, 1080, 2400)
                chat.view.findViewWithTag<StemCellSidebarProposalView>("stem-proposal")?.width?.let { it > 0 } == true
            }
            Fixture(chat, session, requests).check()
        } finally {
            stack.setScreens(emptyList())
            runCurrent()
            engine.stop()
            StemCellsSession.forget("proposal-card")
            controller.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }

    @Test fun theActualCardFoldsLocallyThenAcceptsAndChangesTheRealSidebarStore() = runTest {
        withChat {
            val card = card
            assertTrue(requests.contains("GET" to "/v0/spaces/sidebar/proposals/p1"))
            assertFalse(card.sidebar.list.isScrollEnabled)
            val before = session.snapshot.allRows
            card.findViewWithTag<View>("stem-row:section:Proposed work").performClick()
            await("Local fold must reveal the proposal's task rows") { card.sidebar.rows.any { it.label == "Fix login redirect" } }
            assertSame(card, this.card)
            assertEquals(before, session.snapshot.allRows)
            assertFalse(requests.any { it.first == "POST" && it.second.contains("/sidebar/") })
            val use = card.findViewWithTag<View>("stem-proposal-use")
            assertTrue("Pill hit area includes native's 44-point height", use.height >= (44 * Theme.density).toInt())
            use.performClick()
            await("Accepted card and actual sidebar must both change") {
                status == "You use this sidebar now." && session.snapshot.allRows.any { it.label == "Proposed work" }
            }
            assertEquals(View.GONE, use.visibility)
            assertEquals(1, requests.count { it == "POST" to "/v0/spaces/sidebar/proposals/p1/accept" })
            assertSame(card, this.card)
        }
    }

    @Test fun keepMineDeclinesWithoutChangingTheSidebarAndDimsOnlyThePreviewRows() = runTest {
        withChat {
            val before = session.snapshot.allRows
            card.findViewWithTag<View>("stem-proposal-keep").performClick()
            await("Decline must update the live card") { status == "You kept your own sidebar." }
            assertEquals(before, session.snapshot.allRows)
            assertEquals(0.6f, card.sidebar.alpha, 0.001f)
            assertEquals(1, requests.count { it == "POST" to "/v0/spaces/sidebar/proposals/p1/decline" })
            assertFalse(requests.any { it.second.endsWith("/accept") })
        }
    }

    @Test fun theActualReadOnlyPreviewCannotDecideOrFoldTheProposal() = runTest {
        withChat(preview = true) {
            val before = card.sidebar.rows
            card.findViewWithTag<View>("stem-proposal-use").performClick()
            card.findViewWithTag<View>("stem-proposal-keep").performClick()
            card.findViewWithTag<View>("stem-row:section:Proposed work").performClick()
            runCurrent()
            assertEquals(before, card.sidebar.rows)
            assertTrue(requests.none { it.first == "POST" && it.second.contains("/proposals/") })
            assertEquals("", status)
        }
    }
}