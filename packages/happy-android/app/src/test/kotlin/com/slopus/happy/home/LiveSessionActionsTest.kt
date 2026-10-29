package com.slopus.happy.home

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.model.HomeModelFixtures
import com.slopus.happy.sync.AccountHarness
import com.slopus.happy.sync.SessionOps
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LiveSessionActionsTest {
    @Test fun switchingAccountDuringKillCannotSendFallbackToEitherServer() = runBlocking {
        val first = AccountHarness()
        val second = AccountHarness()
        try {
            first.server.addSession("same", updatedAt = 100)
            second.server.addSession("same", updatedAt = 100)
            first.startAndSync()
            second.startAndSync()
            val current = AtomicReference<SessionOps?>(first.account.sessionOps)
            first.server.rpc["same:killSession"] = {
                current.set(second.account.sessionOps)
                first.server.sealRpc("same", buildJsonObject { put("success", false) })
            }
            val archiver = SessionArchiver(LiveSessionActions { current.get() })
            assertTrue(archiver.archive(HomeModelFixtures.session("same")) is ArchiveResult.Failed)
            assertTrue(archiver.archiving.value.isEmpty())
            assertTrue(first.server.requestsTo("/v1/sessions/same/archive").isEmpty())
            assertTrue(second.server.requestsTo("/v1/sessions/same/archive").isEmpty())
            assertTrue(second.server.socketEvents.none { it.first == "rpc-call" })
        } finally {
            first.close()
            second.close()
        }
    }

    @Test fun anOperationBoundWhileSignedOutCannotUseALaterAccount() = runBlocking {
        val harness = AccountHarness()
        try {
            harness.server.addSession("same", updatedAt = 100)
            harness.startAndSync()
            var current: SessionOps? = null
            val operation = LiveSessionActions { current }.forOperation()
            current = harness.account.sessionOps
            assertFalse(operation.archiveOnServer("same").success)
            assertTrue(harness.server.requestsTo("/v1/sessions/same/archive").isEmpty())
        } finally {
            harness.close()
        }
    }
}