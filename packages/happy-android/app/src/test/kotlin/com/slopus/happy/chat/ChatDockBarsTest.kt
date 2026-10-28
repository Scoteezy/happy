package com.slopus.happy.chat

import com.slopus.happy.model.AgentGoalAction
import com.slopus.happy.model.HomeModelFixtures
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ported from happy-ios ChatDockBarsTests: the inactive hint's action and the goal bar's dispatch. */
class ChatDockBarsTest {
    private val f = HomeModelFixtures

    private fun claude(path: String = "/repo") = f.metadata(path) {
        put("machineId", "m1")
        put("claudeSessionId", "c-1")
    }

    private fun resumable() = f.machine("m1", f.machineMetadata { putJsonObject("resumeSupport") { put("rpcAvailable", true) } })

    @Test
    fun onlineSessionShowsNoHint() {
        assertNull(InactiveSessionHint.of(f.session("s", claude(), active = true), online = true, machine = resumable()))
    }

    @Test
    fun rigSessionShowsNoHint() {
        assertNull(InactiveSessionHint.of(f.session("s", f.rigMetadata(), active = false), online = false, machine = resumable()))
    }

    @Test
    fun resumableSessionOffersResume() {
        val hint = InactiveSessionHint.of(f.session("s", claude(), active = false), online = false, machine = resumable())
        assertEquals(InactiveSessionHint.Action.Resume, hint?.action)
        assertNull(hint?.copyText)
    }

    @Test
    fun offlineMachineFallsBackToTheCommands() {
        val hint = InactiveSessionHint.of(f.session("s", claude(), active = false), online = false, machine = f.machine("m1", active = false))
        assertEquals(InactiveSessionHint.Action.Command(listOf("cd '/repo'", "happy claude --resume c-1")), hint?.action)
        assertEquals("cd '/repo'\nhappy claude --resume c-1", hint?.copyText)
    }

    @Test
    fun noAgentIdLeavesTheNoticeAlone() {
        val hint = InactiveSessionHint.of(f.session("s", f.metadata { put("machineId", "m1") }, active = false), online = false, machine = null)
        assertEquals(InactiveSessionHint.Action.NoticeOnly, hint?.action)
    }

    @Test
    fun goalDispatch() {
        assertNull(goalDispatch(AgentGoalAction.Stop, "Ship it", null))
        assertEquals(AgentGoalAction.Clear to null, goalDispatch(AgentGoalAction.Clear, "Ship it", null))
        assertNull(goalDispatch(AgentGoalAction.Edit, "Ship it", null))
        assertNull(goalDispatch(AgentGoalAction.Edit, "Ship it", "  Ship it "))
        assertNull(goalDispatch(AgentGoalAction.Edit, "Ship it", "   "))
        assertEquals(AgentGoalAction.Edit to "Ship v2", goalDispatch(AgentGoalAction.Edit, "Ship it", " Ship v2 "))
    }
}
