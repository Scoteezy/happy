package com.slopus.happy.data

import com.slopus.happy.model.Machine
import com.slopus.happy.model.MachineMetadata
import com.slopus.happy.troubleshoot.TroubleshootSession
import kotlinx.serialization.json.*

/** Only the machine fields used to group computers and choose their log directory. */
data class TroubleshootingMachine(
    val id: String,
    val createdAt: Long,
    val active: Boolean,
    val activeAt: Long,
    val displayName: String?,
    val host: String?,
    val siblingMachineId: String?,
    val rig: Boolean,
    val happyHomeDir: String?,
) {
    /** A view projection, not a decoded wire record; only these fields survive the read. */
    fun machine(): Machine = Machine(
        id = id, seq = 0, createdAt = createdAt, updatedAt = 0, active = active, activeAt = activeAt,
        metadata = MachineMetadata(
            raw = buildJsonObject { siblingMachineId?.let { put("siblingMachineId", it) } },
            host = host.orEmpty(), platform = "", happyCliVersion = "", homeDir = "",
            happyHomeDir = happyHomeDir.orEmpty(), displayName = displayName,
            machineKind = if (rig) "rig" else null,
        ),
        metadataVersion = 0, daemonState = null, daemonStateVersion = 0,
    )
}

/** Native troubleshooting projection: no ciphertexts, daemon state or full session metadata. */
data class TroubleshootingSnapshot(
    val machines: List<TroubleshootingMachine>,
    val recentSession: TroubleshootSession?,
)