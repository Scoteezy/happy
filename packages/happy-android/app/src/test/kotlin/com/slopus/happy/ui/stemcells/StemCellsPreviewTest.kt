package com.slopus.happy.ui.stemcells

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.slopus.happy.direct.*
import com.slopus.happy.harness.StemCellsHarnessTransport
import com.slopus.happy.stemcells.StemCellDraft
import com.slopus.happy.stemcells.StemCellsRow
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StemCellsPreviewTest {
    @Test fun previewReadsActualHistoryWithoutMarkingReadOrFlushingTheUnsyncedDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        Theme.install(context)
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val fixture = StemCellsHarnessTransport(File("src/debug/assets/harness/stem-cells-computer.json").readText(), backgroundScope)
        val transport = object : DirectTransport by fixture {
            override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
                requests += method to path
                return fixture.request(method, path, credential, body, headers)
            }
        }
        val engine = DirectSyncEngine(DirectAgentClient(transport, "fixture"), null, backgroundScope, EmptyCoroutineContext)
        val session = StemCellsSession.session("preview-test", engine)
        val draft = StemCellDraft("Do not send or overwrite this draft", 9_999_999_999_999.0, false)
        session.drafts.keep("aglogin", draft)
        val row = StemCellsRow("space:splogin", "splogin", StemCellsRow.Kind.Space, label = "Fix login redirect", spaceId = "splogin", targetKind = "task", agentId = "aglogin", unread = true)
        val screen = StemCellChatScreen.forRow(context, session, row, previewing = true)!!
        try {
            screen.view.layout(0, 0, 1000, 1000)
            // Even a host invoking normal appearance must leave a preview read-only.
            screen.onAppear()
            var attempts = 0
            while (screen.probe.contains("msgs=0") && attempts++ < 300) {
                runCurrent()
                screen.view.layout(0, 0, 1000, 1000)
                withContext(Dispatchers.Default) { delay(10) }
            }
            assertFalse("The actual conversation history must have rendered", screen.probe.contains("msgs=0"))
            assertEquals(View.GONE, screen.view.findViewWithTag<View>("stem-input").visibility)
            screen.onDisappear()
            runCurrent()
            assertEquals(draft, session.drafts.draft("aglogin"))
            assertTrue("Preview requests: $requests", requests.all { it.first == "GET" })
            assertNull(session.selectedRowId)
        } finally {
            screen.destroy()
            runCurrent()
            StemCellsSession.forget("preview-test")
            Dispatchers.resetMain()
        }
    }
}