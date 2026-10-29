package com.slopus.happy.home

import com.slopus.happy.model.HomeModelFixtures
import com.slopus.happy.model.Machine
import com.slopus.happy.model.Session
import com.slopus.happy.model.forkSource
import com.slopus.happy.sync.ForkSource
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** happy-ios `SessionQuickActionsTests`: the "Session" alert a long press on a home row opens. */
class SessionQuickActionsTest {
    private fun session(
        active: Boolean = false,
        rig: Boolean = false,
        flavor: String? = null,
        machine: String? = "m1",
        path: String = "/src/app",
        claude: String? = "c-1",
        codex: String? = null,
    ): Session = HomeModelFixtures.session(
        "s1",
        active = active,
        metadata = HomeModelFixtures.metadata(path = path) {
            machine?.let { put("machineId", it) }
            claude?.let { put("claudeSessionId", it) }
            codex?.let { put("codexThreadId", it) }
            flavor?.let { put("flavor", it) }
            if (rig) putJsonObject("client") {
                put("id", "rig")
                put("name", "Rig")
                put("version", "test")
            }
        },
    )

    private fun machine(active: Boolean = true, resume: Boolean = true): Machine = HomeModelFixtures.machine(
        "m1",
        active = active,
        metadata = HomeModelFixtures.machineMetadata { putJsonObject("resumeSupport") { put("rpcAvailable", resume) } },
    )

    private fun items(session: Session, machine: Machine?, experiments: Boolean = true, devMode: Boolean = false) =
        sessionActionIds(session, machine, experiments, devMode)

    @Test
    fun anOfflineSessionOnAnOnlineMachineOffersEverything() {
        assertEquals(
            listOf(SessionActionId.Details, SessionActionId.Resume, SessionActionId.Fork, SessionActionId.Duplicate, SessionActionId.Archive),
            items(session(), machine()),
        )
        assertTrue(resumeAvailability(session(), machine()).canResume)
    }

    @Test
    fun devModeAddsTheMetadataCopies() {
        assertEquals(
            listOf(
                SessionActionId.Details, SessionActionId.Fork, SessionActionId.Duplicate,
                SessionActionId.CopyMetadata, SessionActionId.CopyMetadataAndLogs, SessionActionId.Archive,
            ),
            items(session(active = true), machine(), devMode = true),
        )
    }

    @Test
    fun aConnectedSessionHasNothingToResume() {
        assertFalse(resumeAvailability(session(active = true), machine()).canShowResume)
    }

    @Test
    fun resumeExplainsWhyItCannotRun() {
        fun block(session: Session, machine: Machine?) = resumeAvailability(session, machine).block
        assertEquals(ResumeBlock.MissingMachine, block(session(machine = null), null))
        assertEquals(ResumeBlock.MissingBackendId, block(session(claude = null), machine()))
        assertEquals(ResumeBlock.SameMachineOnly, block(session(), null))
        assertEquals(ResumeBlock.MachineOffline, block(session(), machine(active = false)))
        assertFalse("older daemons without the RPC hide the item", resumeAvailability(session(), machine(resume = false)).canShowResume)
        assertFalse(resumeAvailability(session(rig = true), machine()).canShowResume)
    }

    @Test
    fun forkNeedsTheSettingTheSourceAndAnOnlineMachine() {
        assertFalse(canForkSession(session(), machine(), continuationExperiments = false))
        assertFalse(canForkSession(session(), machine(active = false), continuationExperiments = true))
        assertFalse(canForkSession(session(claude = null), machine(), continuationExperiments = true))
    }

    @Test
    fun forkSourceFollowsTheFlavor() {
        assertEquals(ForkSource.Codex("s1", "m1", "/src/app", "t-1"), session(flavor = "codex", claude = null, codex = "t-1").forkSource())
        assertEquals(ForkSource.Claude("s1", "m1", "/src/app", "c-1"), session().forkSource())
        assertNull(session(flavor = "codex").forkSource())
        assertNull(session(path = "  ").forkSource())
    }
}
