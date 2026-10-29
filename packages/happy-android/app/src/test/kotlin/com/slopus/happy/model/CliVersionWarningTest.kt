package com.slopus.happy.model

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Ported from happy-ios Tests/HappyCoreTests/ChatHeaderModelTests.swift (CliVersionWarningTests).

class CliVersionWarningTest {
    private fun metadata(version: String? = null, machineId: String? = null) =
        SessionMetadata(raw = JsonObject(emptyMap()), path = "", host = "", version = version, machineId = machineId)

    @Test fun anOldCliWarnsUntilItsVersionIsDismissedOnThatMachine() {
        val old = metadata(version = "0.9.4", machineId = "m1")
        val warning = CliVersionWarning.of(old, emptyMap())
        assertEquals("an older dismissal", warning, CliVersionWarning.of(old, mapOf("m1" to "0.9.3")))
        val dismissed = warning?.dismissed(mapOf("m2" to "0.8.0"))
        assertEquals(mapOf("m1" to "0.9.4", "m2" to "0.8.0"), dismissed)
        assertNull(CliVersionWarning.of(old, dismissed.orEmpty()))
        assertNull(CliVersionWarning.of(metadata(version = "0.10.0", machineId = "m1"), emptyMap()))
        assertNull("no version", CliVersionWarning.of(metadata(machineId = "m1"), emptyMap()))
        assertNull("no machine to keep it for", CliVersionWarning.of(metadata(version = "0.9.4"), emptyMap())?.dismissed(emptyMap()))
    }
}
