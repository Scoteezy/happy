package com.slopus.happy.model.details

import com.slopus.happy.data.MachineRecord
import com.slopus.happy.data.SessionRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

// happy-ios SecondarySessionDetailsTests.swift: SessionDetails and MachineDetailsModelTests.

private fun session(id: String, updatedAt: Long, metadata: String?) = SessionRecord(
    id = id, seq = 7, createdAt = 1, updatedAt = updatedAt, active = false, activeAt = 1,
    metadataJson = metadata, metadataVersion = 1, agentStateJson = null, agentStateVersion = 0,
    wrappedDataKey = "k", projectId = null,
)

private fun machine(id: String = "m1", activeAt: Long = 5, metadata: String? = "{}", daemonState: String? = null) = MachineRecord(
    id = id, seq = 0, createdAt = 1, updatedAt = 1, active = true, activeAt = activeAt,
    metadataJson = metadata, metadataVersion = 3, daemonStateJson = daemonState, daemonStateVersion = 2, wrappedDataKey = "k",
)

class SessionDetailsTest {
    @Test fun pathRelativeToHome() {
        assertEquals("~/code", SessionDetails.pathRelativeToHome("/Users/ada/code", "/Users/ada"))
        assertEquals("~/code", SessionDetails.pathRelativeToHome("/Users/ada/code", "/Users/ada/"))
        assertEquals("~", SessionDetails.pathRelativeToHome("/Users/ada", "/Users/ada"))
        assertEquals("~/m/x", SessionDetails.pathRelativeToHome("/Users/adam/x", "/Users/ada"))
        assertEquals("/srv/x", SessionDetails.pathRelativeToHome("/srv/x", "/Users/ada"))
        assertEquals("/srv/x", SessionDetails.pathRelativeToHome("/srv/x", null))
    }

    @Test fun localeDateTime() {
        val text = SessionDetails.localeDateTime(0, Locale.US, ZoneId.of("UTC"))
        assertTrue(text, text.contains("1/1/1970"))
        assertTrue(text, text.contains("12:00:00"))
    }

    @Test fun jsNumber() {
        assertEquals("8080", SessionDetails.jsNumber(8080.0))
        assertEquals("1.5", SessionDetails.jsNumber(1.5))
    }
}

class MachineDetailsModelTest {
    @Test fun namesAndDaemon() {
        val model = MachineDetailsModel(
            machine(
                metadata = """{"displayName":"","host":"mac.local","homeDir":"/Users/ada"}""",
                daemonState = """{"pid":0,"httpPort":8080,"startedWithCliVersion":"1.2.3"}""",
            ),
            emptyList(),
        )
        assertEquals("mac.local", model.name)
        assertEquals("", model.displayName)
        assertNull("a zero pid is falsy and hidden", model.daemon?.pid)
        assertEquals("8080", model.daemon?.httpPort)
        assertEquals("1.2.3", model.daemon?.cliVersion)
        assertEquals("/Users/ada", model.homeDirectory)
        assertEquals(5L, model.lastSeen)
        assertNull(model.cliAvailability)

        val bare = MachineDetailsModel(machine("m9", activeAt = 0, metadata = null), emptyList())
        assertEquals("unknown machine", bare.name)
        assertEquals("m9", bare.host)
        assertNull(bare.lastSeen)
        assertNull(bare.daemon)
    }

    @Test fun cliAvailability() {
        val model = MachineDetailsModel(
            machine(metadata = """{"cliAvailability":{"claude":true,"codex":false,"gemini":false,"openclaw":true,"detectedAt":9}}"""),
            emptyList(),
        )
        assertEquals(MachineDetailsModel.CliAvailability(claude = true, codex = false, gemini = false, openclaw = true, rig = null, detectedAt = 9), model.cliAvailability)
    }

    @Test fun recentSessionsNewestUpdateFirstWithoutSideChats() {
        val sessions = (0 until 7).map { session("s$it", it.toLong(), """{"machineId":"m1","path":"/w/$it"}""") } + listOf(
            session("side", 100, """{"machineId":"m1","isSideChat":true}"""),
            session("other", 100, """{"machineId":"m2"}"""),
            session("bare", 50, null),
        )
        val model = MachineDetailsModel(machine(), sessions)
        assertEquals(listOf("s6", "s5", "s4", "s3", "s2"), model.recentSessions.map { it.id })
        assertEquals("/w/6", model.recentSessions.first().subtitle)
    }

    @Test fun renamedMetadataKeepsTheRest() {
        val metadata = JsonObject(mapOf("host" to JsonPrimitive("mac"), "displayName" to JsonPrimitive("Old")))
        assertEquals(JsonObject(mapOf("host" to JsonPrimitive("mac"), "displayName" to JsonPrimitive("New"))), MachineDetailsModel.renamedMetadata(metadata, "  New  "))
        assertEquals(JsonObject(mapOf("host" to JsonPrimitive("mac"))), MachineDetailsModel.renamedMetadata(metadata, " "))
    }
}
