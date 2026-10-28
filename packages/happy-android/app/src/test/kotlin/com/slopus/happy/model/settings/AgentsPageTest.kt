package com.slopus.happy.model.settings

import com.slopus.happy.model.HomeModelFixtures.machine
import com.slopus.happy.model.HomeModelFixtures.machineMetadata
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from happy-ios Tests/HappyCoreTests/SettingsPagesTests.swift (AgentsPageTests).
// Android's machine metadata always has a platform, so the computer without
// one on iOS has one here.

/** app/(app)/settings/agents.tsx. */
class AgentsPageTest {
    @Test fun machinesOnlineFirstThenMostRecent() {
        val rows = AgentsPage.machineRows(
            listOf(
                machine("m1", machineMetadata("old") { put("platform", "darwin") }, active = false, activeAt = 5),
                machine("m2", machineMetadata("work") { put("displayName", "Work"); put("platform", "linux") }, active = true, activeAt = 3),
                machine("m3", machineMetadata("mini") { put("platform", "") }, active = false, activeAt = 9),
            ),
            "online",
            "offline",
        )
        assertEquals(listOf("Work", "mini", "old"), rows.map { it.name })
        assertEquals(listOf("linux • online", "offline", "darwin • offline"), rows.map { it.subtitle })
        assertEquals(listOf(true, false, false), rows.map { it.online })
        assertEquals(listOf("m2", "m3", "m1"), rows.map { it.targetMachineId })
    }

    @Test fun aComputerOpensItsReachableDaemon() {
        val machines = listOf(
            machine("r1", machineMetadata("box") { put("machineKind", "rig"); put("siblingMachineId", "h1") }, active = true, activeAt = 4),
            machine("h1", machineMetadata("box") { put("displayName", "Box"); put("happyCliVersion", "1.2.6") }, active = false, activeAt = 2),
        )
        val rows = AgentsPage.machineRows(machines, "online", "offline")
        assertEquals(1, rows.size)
        assertEquals("h1", rows[0].id)
        assertEquals("Box", rows[0].name)
        assertEquals("darwin • online", rows[0].subtitle)
        assertEquals("r1", rows[0].targetMachineId)
        assertEquals("1.2.6", AgentsPage.defaultsCliVersion(machines))
        assertNull(AgentsPage.defaultsCliVersion(emptyList()))
    }

    @Test fun claudeFieldsWithoutOverrides() {
        val fields = AgentsPage.fields(AgentKey.Claude, AgentDefaultOverrides(), "1.3.0")
        assertEquals(listOf(AgentDefaultField.PermissionMode, AgentDefaultField.ModelMode, AgentDefaultField.EffortLevel), fields.map { it.field })
        assertEquals(listOf("Permission", "Model", "Effort"), fields.map { it.title })
        assertEquals(listOf("shield-checkmark-outline", "hardware-chip-outline", "speedometer-outline"), fields.map { it.icon })
        assertEquals(listOf("Default (Auto)", "Default (Opus 5.5)", "Default (Medium)"), fields.map { it.detail })
        assertEquals(listOf("Auto", "Opus 5.5", "Medium"), fields.map { it.codeDefaultName })
        assertEquals(listOf(false, false, false), fields.map { it.hasOverride })
        assertEquals(6, fields[1].options.size)
        assertFalse(fields[1].offersCustomModel)
    }

    @Test fun anOverrideNamesItself() {
        val overrides = AgentDefaultOverrides(mapOf(AgentKey.Claude to AgentDefaultOverride(permissionMode = "plan")))
        val permission = AgentsPage.fields(AgentKey.Claude, overrides, "1.3.0")[0]
        assertEquals("Plan", permission.detail)
        assertTrue(permission.hasOverride)
        assertEquals("plan", permission.overrideValue)
    }

    @Test fun codexCustomModelIsListedAsAnOption() {
        val overrides = AgentDefaultOverrides(mapOf(AgentKey.Codex to AgentDefaultOverride(modelMode = "my-model")))
        val fields = AgentsPage.fields(AgentKey.Codex, overrides, "1.3.0")
        val model = fields[1]
        assertTrue(model.offersCustomModel)
        assertEquals("my-model", model.detail)
        assertEquals(ModeOption("my-model", "my-model", "custom model"), model.options.last())
        assertNull("includeConfiguredModel lists it, so it is an option, not the custom row", model.customModel)
        assertEquals(listOf("low", "medium", "high", "xhigh"), fields[2].options.map { it.key })
    }

    @Test fun agentsWithoutModelsOrEffort() {
        assertEquals(listOf(AgentDefaultField.PermissionMode), AgentsPage.fields(AgentKey.Openclaw, AgentDefaultOverrides(), null).map { it.field })
        assertEquals(listOf(AgentDefaultField.PermissionMode, AgentDefaultField.ModelMode), AgentsPage.fields(AgentKey.Gemini, AgentDefaultOverrides(), null).map { it.field })
        val agy = AgentsPage.fields(AgentKey.Agy, AgentDefaultOverrides(), null)
        assertEquals(listOf(AgentDefaultField.PermissionMode, AgentDefaultField.ModelMode, AgentDefaultField.EffortLevel), agy.map { it.field })
        assertEquals(3, agy[2].options.size)
    }

    @Test fun unknownCliDescribesTheOlderDefaults() {
        val model = AgentsPage.fields(AgentKey.Claude, AgentDefaultOverrides(), null)[1]
        assertEquals("Default (Opus 5)", model.detail)
    }
}
