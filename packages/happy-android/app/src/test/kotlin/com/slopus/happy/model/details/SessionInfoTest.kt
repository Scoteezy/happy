package com.slopus.happy.model.details

import com.slopus.happy.data.MachineRecord
import com.slopus.happy.data.SessionRecord
import com.slopus.happy.model.GitStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// happy-ios SecondarySessionDetailsTests.swift: SessionDetailsTests, SessionDetailsQuickActionTests and SessionInfoModelTests.

private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

private fun session(active: Boolean = false, metadata: String? = "{}", agentState: String? = null) = SessionRecord(
    id = "session-0123456789abcdef", seq = 7, createdAt = 1, updatedAt = 1, active = active, activeAt = 1,
    metadataJson = metadata, metadataVersion = 1, agentStateJson = agentState, agentStateVersion = 0,
    wrappedDataKey = "k", projectId = null,
)

private fun machine(active: Boolean = true, metadata: String? = "{}") = MachineRecord(
    id = "m1", seq = 0, createdAt = 1, updatedAt = 1, active = active, activeAt = 5,
    metadataJson = metadata, metadataVersion = 3, daemonStateJson = null, daemonStateVersion = 2, wrappedDataKey = "k",
)

private const val RESUMABLE = """{"resumeSupport":{"rpcAvailable":true}}"""
private const val CLAUDE = """{"machineId":"m1","path":"/w","claudeSessionId":"c1"}"""

class SessionInfoTextTest {
    @Test fun shortId() {
        assertEquals("abcdefgh...ijklmnop", SessionInfoText.shortId("abcdefgh12345678ijklmnop"))
        assertEquals("abc...abc", SessionInfoText.shortId("abc"))
    }

    @Test fun resumeCommand() {
        assertEquals("cd '/w/it'\\''s' && happy claude --resume c1", SessionInfoText.resumeCommand(json("""{"path":"/w/it's","claudeSessionId":"c1"}""")))
        assertEquals(
            "Set-Location -LiteralPath 'C:\\a''b'; happy codex --resume t1",
            SessionInfoText.resumeCommand(json("""{"os":"win32","path":"C:\\a'b","flavor":"codex","codexThreadId":"t1"}""")),
        )
        assertEquals("happy claude --resume c1", SessionInfoText.resumeCommand(json("""{"claudeSessionId":"c1"}""")))
        assertNull(SessionInfoText.resumeCommand(json("""{"client":{"id":"rig"},"claudeSessionId":"c1"}""")))
        assertNull(SessionInfoText.resumeCommand(json("""{"capabilities":{"resume":false},"claudeSessionId":"c1"}""")))
        assertNull(SessionInfoText.resumeCommand(json("""{"flavor":"codex"}""")))
    }

    @Test fun sandbox() {
        assertEquals("Disabled", SessionInfoText.sandbox(null, null))
        assertEquals("Disabled", SessionInfoText.sandbox(kotlinx.serialization.json.JsonNull, null))
        assertEquals("strict", SessionInfoText.sandbox(kotlinx.serialization.json.JsonPrimitive("strict"), null))
        assertEquals("Disabled", SessionInfoText.sandbox(json("""{"enabled":false}"""), null))
        assertEquals(
            "Enabled | isolation=strict | network=none | workspace=~/w",
            SessionInfoText.sandbox(json("""{"enabled":true,"sessionIsolation":"strict","networkMode":"none","workspaceRoot":"/Users/ada/w"}"""), "/Users/ada"),
        )
    }

    @Test fun dangerouslySkipPermissions() {
        val t = kotlinx.serialization.json.JsonPrimitive(false)
        assertEquals("Disabled", SessionInfoText.dangerouslySkipPermissions(t, null, "yolo", null))
        assertEquals("Enabled", SessionInfoText.dangerouslySkipPermissions(null, null, "bypassPermissions", null))
        assertEquals("Enabled", SessionInfoText.dangerouslySkipPermissions(null, "claude", null, json("""{"enabled":true}""")))
        assertEquals("Unknown", SessionInfoText.dangerouslySkipPermissions(null, "codex", null, json("""{"enabled":true}""")))
    }

    @Test fun aiProvider() {
        assertEquals("Claude", SessionInfoText.aiProvider(json("{}"), null))
        assertEquals("Codex", SessionInfoText.aiProvider(json("""{"flavor":"openai"}"""), null))
        assertEquals("Gemini", SessionInfoText.aiProvider(json("""{"flavor":"gemini"}"""), null))
        assertEquals("agy", SessionInfoText.aiProvider(json("""{"flavor":"agy"}"""), null))
    }

    @Test fun activity() {
        val activity = SessionInfoText.activity(json("""{"activity":{"subagents":{"running":1,"queued":2},"workflows":{"running":0},"processes":{"running":3},"tasks":{"pending":4,"inProgress":5}}}"""))
        assertEquals("1 running · 2 queued", activity.subagents)
        assertNull(activity.workflows)
        assertEquals("3 running", activity.processes)
        assertEquals("5 in progress · 4 pending", activity.tasks)
    }

    @Test fun compactCount() {
        assertEquals("999", SessionInfoText.compactCount(999))
        assertEquals("1k", SessionInfoText.compactCount(1_000))
        assertEquals("1.3k", SessionInfoText.compactCount(1_250))
        assertEquals("4.9k", SessionInfoText.compactCount(4_949))
        assertEquals("13k", SessionInfoText.compactCount(12_500))
        assertEquals("0", SessionInfoText.compactCount(-3))
    }

    @Test fun prettyJsonMatchesStringify() {
        val value = json("""{"b":[1,true],"a":"x\n\"y\"","e":{}}""")
        assertEquals("{\n  \"a\": \"x\\n\\\"y\\\"\",\n  \"b\": [\n    1,\n    true\n  ],\n  \"e\": {}\n}", SessionInfoText.prettyJson(value))
    }

    @Test fun metadataClipboard() {
        assertEquals("=== Session Metadata ===\n{\n  \"a\": 1\n}", SessionInfoText.metadataClipboard(json("""{"a":1}"""), true))
    }

    @Test fun resumeAvailability() {
        fun r(s: SessionRecord, m: MachineRecord?) = SessionInfoText.resumeAvailability(s, com.slopus.happy.model.parseJsonObject(s.metadataJson), m)
        assertEquals("connected", SessionInfoText.Resume.Hidden, r(session(active = true, metadata = CLAUDE), machine(metadata = RESUMABLE)))
        assertEquals(SessionInfoText.Resume.Hidden, r(session(metadata = """{"client":{"id":"rig"}}"""), null))
        assertEquals(SessionInfoText.Resume.MissingMachine, r(session(metadata = "{}"), null))
        assertEquals(SessionInfoText.Resume.MissingBackendId, r(session(metadata = """{"machineId":"m1"}"""), null))
        assertEquals(SessionInfoText.Resume.SameMachineOnly, r(session(metadata = CLAUDE), null))
        assertEquals(SessionInfoText.Resume.MachineOffline, r(session(metadata = CLAUDE), machine(active = false, metadata = RESUMABLE)))
        assertEquals("an older daemon", SessionInfoText.Resume.Hidden, r(session(metadata = CLAUDE), machine()))
        assertEquals(SessionInfoText.Resume.Available, r(session(metadata = CLAUDE), machine(metadata = RESUMABLE)))
    }

    @Test fun gitPresentation() {
        val rig = GitPresentation.of(json("""{"client":{"id":"rig"},"git":{"changedFiles":3,"insertions":10,"deletions":0,"countsExact":false}}"""), null)
        assertEquals(3L, rig.changedFileCount)
        assertEquals(LineChanges(10, 0, approximate = true), rig.changes)
        assertFalse(GitPresentation.of(json("{}"), null).hasAccessory)
    }
}

class SessionInfoModelTest {
    @Test fun metadataRows() {
        val model = SessionInfoModel(
            session(metadata = """{"host":"mac","path":"/Users/ada/w","homeDir":"/Users/ada","version":"0.9.0","os":"darwin","hostPid":4242,"happyHomeDir":"/Users/ada/.happy"}"""),
            null, continuationExperiments = true, gitStatus = null, permissionMode = null,
        )
        val metadata = model.metadata!!
        assertTrue(model.cliOutdated)
        assertEquals("~/w", metadata.path)
        assertEquals("0.9.0", metadata.cliVersion)
        assertEquals("macOS", metadata.operatingSystem)
        assertEquals("4242", metadata.processId)
        assertEquals("~/.happy", metadata.happyHome)
        assertEquals("Disabled", metadata.sandbox)
        assertEquals("Unknown", metadata.dangerouslySkipPermissions)
        assertNull(metadata.client)
        assertNull(model.agentState)
    }

    @Test fun happyAgentHidesSandboxAndAgentState() {
        val model = SessionInfoModel(
            session(metadata = """{"client":{"id":"rig","version":"3"},"path":"/w"}""", agentState = """{"controlledByUser":true}"""),
            null, continuationExperiments = true, gitStatus = null, permissionMode = null,
        )
        assertNull(model.metadata?.sandbox)
        assertNull(model.metadata?.dangerouslySkipPermissions)
        assertEquals("Rig 3", model.metadata?.client)
        assertNull(model.agentState)
    }

    @Test fun agentStateAndConnection() {
        val model = SessionInfoModel(
            session(active = true, metadata = """{"claudeSessionId":"c1"}""", agentState = """{"controlledByUser":false,"requests":{"r1":{},"r2":{}}}"""),
            null, continuationExperiments = true, gitStatus = null, permissionMode = "bypassPermissions",
        )
        assertTrue(model.isConnected)
        assertNull("only offered while disconnected", model.resumeCommand)
        assertEquals(SessionInfoModel.AgentState(controlledByUser = false, pendingRequests = 2), model.agentState)
        assertEquals("Enabled", model.metadata?.dangerouslySkipPermissions)
    }

    @Test fun noMetadataNoGroup() {
        val model = SessionInfoModel(session(metadata = null), null, continuationExperiments = true, gitStatus = null, permissionMode = null)
        assertNull(model.metadata)
        assertNull(model.machineId)
        assertFalse(model.cliOutdated)
        assertEquals(SessionInfoText.Resume.MissingMachine, model.resume)
    }

    @Test fun canForkNeedsTheExperimentAndAnOnlineMachine() {
        val s = session(metadata = CLAUDE)
        assertTrue(SessionInfoModel(s, machine(), continuationExperiments = true, gitStatus = null, permissionMode = null).canFork)
        assertFalse(SessionInfoModel(s, machine(), continuationExperiments = false, gitStatus = null, permissionMode = null).canFork)
        assertFalse(SessionInfoModel(s, machine(active = false), continuationExperiments = true, gitStatus = null, permissionMode = null).canFork)
    }

    @Test fun sectionsFollowTheModel() {
        val frame = SessionInfoFrame(SessionInfoFrame.Content.Shown(
            SessionInfoModel(session(metadata = """{"version":"0.9.0"}"""), null, continuationExperiments = true, gitStatus = null, permissionMode = null),
        ))
        assertEquals(
            listOf(SessionInfoSection.QuickActions, SessionInfoSection.CliWarning, SessionInfoSection.Details, SessionInfoSection.Metadata, SessionInfoSection.Activity),
            frame.sections,
        )
        assertEquals(emptyList<SessionInfoSection>(), SessionInfoFrame(SessionInfoFrame.Content.Deleted).sections)
        // An unused status keeps the git presentation empty.
        assertNull(GitPresentation.of(json("{}"), GitStatus(null, false, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0)).changes)
    }
}
