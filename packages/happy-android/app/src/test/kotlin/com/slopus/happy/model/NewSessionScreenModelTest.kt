package com.slopus.happy.model

import com.slopus.happy.chat.ComposerStatus
import com.slopus.happy.model.HomeModelFixtures.cliAvailability
import com.slopus.happy.model.HomeModelFixtures.machine
import com.slopus.happy.model.HomeModelFixtures.machineMetadata
import com.slopus.happy.model.HomeModelFixtures.metadata
import com.slopus.happy.model.HomeModelFixtures.rigMetadata
import com.slopus.happy.model.HomeModelFixtures.session
import com.slopus.happy.model.settings.AgentDefaultOverrides
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * happy-ios NewSessionStartTests' screen tests: what new/index.tsx shows
 * for a draft. The rows and their words, the pickers' rows, the composer's
 * harness and settings, and when Send can be pressed.
 */
class NewSessionScreenModelTest {
    private fun cli(id: String, name: String = "MacBook", active: Boolean = true, activeAt: Long = 1) = machine(
        id,
        machineMetadata {
            put("displayName", name)
            put("homeDir", "/Users/steve")
            put("happyCliVersion", "9.0.0")
            cliAvailability(claude = true, codex = true)
        },
        active = active,
        activeAt = activeAt,
    )

    private fun rig(id: String, sibling: String) = machine(
        id,
        machineMetadata {
            put("homeDir", "/Users/steve")
            put("machineKind", "rig")
            put("siblingMachineId", sibling)
            putJsonObject("capabilities") { put("newSession", true); put("worktrees", true) }
        },
    )

    private fun screen(
        draft: NewSessionDraft,
        machines: List<Machine>,
        sessions: List<Session> = emptyList(),
        isStarting: Boolean = false,
        now: Long = 1,
    ): NewSessionScreenModel {
        val options = resolveNewSessionOptions(draft, machines, sessions, AgentDefaultOverrides())
        return NewSessionScreenModel.of(options, draft, isStarting, now)
    }

    @Test fun theScreenForAPlainClaudeDraft() {
        val draft = NewSessionDraft(selectedMachineId = "m1", selectedPath = "/Users/steve/happy")
        val machines = listOf(cli("m2", name = "Studio", active = false), cli("m1"))
        val sessions = listOf(
            session("s1", metadata(path = "/Users/steve/happy") { put("machineId", "m1") }),
            session("s2", metadata(path = "/tmp/other") { put("machineId", "m1") }),
        )
        val model = screen(draft, machines, sessions, now = 1 + 3 * 60_000)

        assertEquals("MacBook", model.machineName)
        assertEquals("online first", listOf("m1", "m2"), model.machineItems.map { it.key })
        assertEquals(
            listOf(NewSessionScreenModel.MachineStatus.Online, NewSessionScreenModel.MachineStatus.LastSeen(ComposerStatus.LastSeen.Minutes(3))),
            model.machineItems.map { it.status },
        )
        assertEquals(listOf(false, true), model.machineItems.map { it.isDimmed })
        assertEquals("m1", model.selectedMachineKey)
        assertNull(model.offlineHelp)

        assertEquals(listOf("~/happy", "/tmp/other"), model.pathItems.map { it.label })
        assertEquals("~/happy", model.pathName)
        assertEquals("/Users/steve/happy", model.pathValue)
        assertEquals("/Users/steve/happy", model.matchedPathKey)
        assertNull(model.selectedProjectKey)

        assertTrue(model.canPickWorktree)
        assertFalse(model.picksWorkspaces)
        assertEquals(listOf("new worktree", "no worktree"), model.worktreeFixedItems.map { it.label })
        assertEquals("no worktree", model.worktreeLabel)

        assertEquals(listOf("claude code", "codex"), model.agentItems.map { it.label })
        assertEquals("claude code", model.agentLabel)
        assertEquals("Ask claude code", model.placeholder)
        assertFalse(model.isCodex)
        assertEquals(
            listOf(NewSessionScreenModel.Setting.Kind.Permission, NewSessionScreenModel.Setting.Kind.Model, NewSessionScreenModel.Setting.Kind.Effort),
            model.settings.map { it.kind },
        )
        assertEquals("the ambient mode has no accent", "shield-outline", model.settings[0].icon)

        assertTrue("no prompt needed", model.canSend)
        assertFalse(screen(draft, machines, sessions, isStarting = true).canSend)
    }

    @Test fun anOfflineDaemonStopsSendAndExplains() {
        val model = screen(NewSessionDraft(selectedMachineId = "m1"), listOf(cli("m1", active = false)))
        assertEquals(NewSessionScreenModel.OfflineHelp.HappyCli, model.offlineHelp)
        assertFalse(model.canSend)
        assertEquals("~", model.pathName)
        assertFalse("a computer must be chosen", screen(NewSessionDraft(), listOf(cli("m1"))).canSend)
    }

    @Test fun codexWordsItsOwnPlaceholder() {
        val model = screen(NewSessionDraft(selectedMachineId = "m1", agentType = AgentType.Codex, pickedAgentType = AgentType.Codex), listOf(cli("m1")))
        assertEquals("codex", model.agentLabel)
        assertEquals("Ask Codex", model.placeholder)
        assertTrue(model.isCodex)
    }

    @Test fun aHappyAgentProjectOffersItsWorkspaces() {
        val sessions = listOf(
            session("s1", rigMetadata(path = "/Users/steve/happy/.dev/worktree/fix", machineId = "rig1", projectId = "p1", projectName = "happy", workspaceId = "w1", workspaceName = "fix")),
            session("s2", rigMetadata(path = "/Users/steve/happy", machineId = "rig1", projectId = "p1", projectName = "happy")),
        )
        val model = screen(
            NewSessionDraft(selectedMachineId = "m1", selectedPath = "/Users/steve/happy", agentType = AgentType.Rig, pickedAgentType = AgentType.Rig),
            listOf(cli("m1"), rig("rig1", sibling = "m1")),
            sessions,
        )
        assertEquals(listOf(NewSessionScreenModel.Item("/Users/steve/happy", "happy", "~/happy")), model.pathItems)
        assertEquals("/Users/steve/happy", model.matchedPathKey)
        assertTrue(model.picksWorkspaces)
        assertEquals(listOf("Create New", "Main"), model.worktreeFixedItems.map { it.label })
        assertEquals(listOf("fix"), model.worktreeItems.map { it.label })
        assertEquals("Main", model.worktreeLabel)
        assertEquals(listOf("claude", "codex", "rig"), model.agentItems.map { it.key })
        assertEquals("happy", model.agentLabel)
        assertEquals("Ask happy", model.placeholder)
        assertTrue(model.canSend)
    }

    @Test fun aCatalogProjectWithNoChatsLeftKeepsItsRow() {
        val model = screen(
            NewSessionDraft(selectedMachineId = "m1", selectedProjectId = "p9", agentType = AgentType.Rig, pickedAgentType = AgentType.Rig),
            listOf(cli("m1"), rig("rig1", sibling = "m1")),
        )
        assertEquals(listOf(NewSessionScreenModel.Item("project:p9", "Project", projectId = "p9")), model.pathItems)
        assertEquals("project:p9", model.selectedProjectKey)
        assertEquals("Project", model.selectedProjectName)
        assertEquals("Project", model.pathName)
        assertEquals("a project has no folder to type", "", model.fieldValue(model.pathItems[0]))
        assertTrue(model.worktreeItems.isEmpty())
    }

    @Test fun pathsAndIcons() {
        assertEquals("/Users/steve/happy", NewSessionScreenModel.normalizedPath(" ~/happy/ ", "/Users/steve"))
        assertEquals("/", NewSessionScreenModel.normalizedPath("/", null))
        assertEquals("C:\\", NewSessionScreenModel.normalizedPath("C:\\", null))
        assertNull(NewSessionScreenModel.normalizedPath("  ", null))
        assertEquals("pause", NewSessionScreenModel.permissionIcon("plan"))
        assertEquals("play-forward", NewSessionScreenModel.permissionIcon("yolo"))
        assertNull(NewSessionScreenModel.permissionIcon("default"))
    }
}
