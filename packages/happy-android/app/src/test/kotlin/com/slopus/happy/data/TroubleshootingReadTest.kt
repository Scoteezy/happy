package com.slopus.happy.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.model.collectMachineChoices
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TroubleshootingReadTest {
    private lateinit var database: HappyDatabase
    private lateinit var store: HappyStore

    @Before fun open() {
        database = HappyDatabase(ApplicationProvider.getApplicationContext(), null)
        store = HappyStore(database)
    }
    @After fun close() = database.close()

    private fun machine(id: String, created: Long = 1, metadata: JsonObject = buildJsonObject { put("host", id) }) =
        MachineRecord(id, 0, created, 1, false, 100, metadata.toString(), 1, null, 0, "")

    private fun session(id: String, updated: Long, machine: String = "happy", extra: JsonObjectBuilder.() -> Unit = {}) =
        SessionRecord(id, 0, 1, updated, false, updated, buildJsonObject {
            put("machineId", machine); put("path", "/work/$id"); extra()
        }.toString(), 1, null, 0, "", null)

    private fun both(): TroubleshootingSnapshot {
        val narrow = readTroubleshootingSnapshot(database.writableDatabase, narrow = true)
        val compatible = readTroubleshootingSnapshot(database.writableDatabase, narrow = false)
        assertEquals(narrow, compatible)
        assertEquals(narrow, store.troubleshootingSnapshot())
        return narrow
    }

    @Test fun groupingRecentKnownProjectAndSideChatExclusionMatchBothSqlitePaths() {
        store.replaceMachines(listOf(
            machine("happy", 100, buildJsonObject { put("displayName", "Laptop"); put("happyHomeDir", "/logs") }),
            machine("rig", 200, buildJsonObject { put("host", "host"); put("siblingMachineId", "happy"); putJsonObject("client") { put("id", "rig") } }),
            machine("other", 300),
        ))
        store.upsertSessions(listOf(
            session("side", 10000, "other") { put("isSideChat", true) },
            session("unknown", 9000, "missing"), session("expected", 500, "rig"), session("older", 100, "other"),
        ))
        val first = both()
        assertEquals("/work/expected", first.recentSession!!.path)
        val choices = collectMachineChoices(first.machines.map { it.machine() })
        assertEquals(listOf("other", "happy"), choices.map { it.id })
        assertEquals("Laptop", choices.last().name)
        assertEquals(setOf("happy", "rig"), choices.last().machineIds.toSet())
        store.replaceMachines(listOf(machine("other", 300)))
        assertEquals("/work/older", both().recentSession!!.path)
    }

    @Test fun timestampActivityMeaningfulAndIdTiesHaveNativeOrdering() {
        store.upsertMachines(listOf(machine("happy")))
        store.upsertSessions(listOf(
            session("offline", 500) { put("lastMeaningfulMessageAt", 99999) },
            session("numeric-string", 500) { put("lastMeaningfulMessageAt", "999999") }.copy(active = true, lastMessageSentAt = 300),
            session("meaningful-b", 500) { put("lastMeaningfulMessageAt", 400) }.copy(active = true),
            session("meaningful-a", 500) { put("lastMeaningfulMessageAt", 400) }.copy(active = true),
            session("older", 499) { put("lastMeaningfulMessageAt", 9999999) }.copy(active = true),
        ))
        store.markMessageSent("numeric-string", 300)
        assertEquals("/work/meaningful-a", both().recentSession!!.path)
        store.markMessageSent("numeric-string", 401)
        assertEquals("/work/numeric-string", both().recentSession!!.path)
    }

    @Test fun onlyStrictBooleansAndStringMachineIdsAffectSelection() {
        store.upsertMachines(listOf(machine("happy", metadata = buildJsonObject { put("rigOnly", "true") })))
        store.upsertSessions(listOf(session("string-side", 100) { put("isSideChat", "true") },
            session("number-machine", 200) { put("machineId", 42) }))
        val result = both()
        assertFalse(result.machines.single().rig)
        assertEquals("/work/string-side", result.recentSession!!.path)
    }

    @Test fun thousandRowsProduceOnlyTheNewestProjectionWithoutStoreMutation() {
        store.upsertMachines(listOf(machine("happy", metadata = buildJsonObject {
            put("host", "host"); put("unused", "x".repeat(2000)); putJsonArray("models") { repeat(100) { add("unused") } }
        })))
        store.upsertSessions((0..999).map { n -> session("s$n", n.toLong()) { put("unused", "x".repeat(2000)) } })
        val versions = store.versions.value
        val changes = store.changes.value
        val result = both()
        assertEquals("/work/s999", result.recentSession!!.path)
        assertFalse(result.toString().contains("unused"))
        assertFalse(result.toString().contains("xxx"))
        assertEquals(versions, store.versions.value)
        assertEquals(changes, store.changes.value)
    }

    @Test fun emptyAndNullMetadataAreSafeButMalformedDocumentsFailTheRead() {
        assertNull(both().recentSession)
        store.upsertMachines(listOf(machine("happy").copy(metadataJson = null)))
        assertEquals("Unknown machine", collectMachineChoices(both().machines.map { it.machine() }).single().name)
        store.upsertSessions(listOf(session("bad", 100).copy(metadataJson = "{broken")))
        for (narrow in listOf(true, false)) {
            assertThrows(Exception::class.java) { readTroubleshootingSnapshot(database.writableDatabase, narrow) }
        }
    }
}