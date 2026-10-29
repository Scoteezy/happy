package com.slopus.happy.stemcells

import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectOfflineFile
import com.slopus.happy.direct.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.Executors

/**
 * What one paired computer last showed, kept on this phone so the app opens
 * on it with the computer asleep or out of reach: the sidebar as last read
 * (its document, spaces, bots, tasks, Surfaces and Prioritizing), the
 * computer's model configuration for the pickers, and the dock's pick.
 * happy-ios `StemCellsOfflineSnapshot`, in `snapshot.json` of the
 * computer's [DirectOfflineFile] folder.
 */
data class StemCellsOfflineSnapshot(
    val format: Int = FORMAT,
    val sidebar: Sidebar? = null,
    /** `GET /v0/config` as the computer sent it: its order is the pickers'. */
    val config: String? = null,
    /** What new work goes with, picked on the dock. */
    val dockMode: AgentMode? = null,
) {
    data class Sidebar(
        val artifactId: String,
        val version: Int,
        val html: String,
        val spaces: List<Space>,
        /** Library slugs by agent ID. */
        val agentIcons: Map<String, String>,
        val bots: List<JsonElement>,
        val tasks: List<JsonElement>,
        /** Absent in a copy written before they were kept. */
        val surfaces: List<Surface>?,
        val capabilities: AgentCapabilities?,
        val prioritizing: Boolean?,
    ) {
        fun json(): JsonObject = Wire.objectOf(
            "artifactId" to JsonPrimitive(artifactId), "version" to JsonPrimitive(version), "html" to JsonPrimitive(html),
            "spaces" to JsonArray(spaces.map { it.json() }),
            "agentIcons" to JsonObject(agentIcons.mapValues { JsonObject(mapOf("slug" to JsonPrimitive(it.value))) }),
            "bots" to JsonArray(bots), "tasks" to JsonArray(tasks),
            "surfaces" to surfaces?.let { list -> JsonArray(list.map { it.json() }) },
            "capabilities" to capabilities?.json(), "prioritizing" to prioritizing?.let(::JsonPrimitive),
        )

        companion object {
            fun from(json: JsonElement): Sidebar {
                val o = Wire.obj(json)
                return Sidebar(
                    Wire.string(o, "artifactId"), Wire.int(o, "version"), Wire.string(o, "html"), Wire.list(o, "spaces", Space::from),
                    Wire.obj(o["agentIcons"]).mapValues { Wire.string(Wire.obj(it.value), "slug") },
                    Wire.array(o["bots"]), Wire.array(o["tasks"]),
                    o["surfaces"]?.takeIf { it != JsonNull }?.let { Wire.array(it).map(Surface::from) },
                    AgentCapabilities.from(o["capabilities"]), Wire.boolOrNull(o, "prioritizing"),
                )
            }
        }
    }

    fun json(): JsonObject = Wire.objectOf(
        "format" to JsonPrimitive(format), "sidebar" to sidebar?.json(), "config" to config?.let(::JsonPrimitive), "dockMode" to dockMode?.json(),
    )

    companion object {
        /** Bumped when an older copy can no longer be read as this one. */
        const val FORMAT = 1

        fun from(json: JsonElement): StemCellsOfflineSnapshot {
            val o = Wire.obj(json)
            return StemCellsOfflineSnapshot(
                Wire.int(o, "format"), o["sidebar"]?.takeIf { it != JsonNull }?.let(Sidebar::from), Wire.stringOrNull(o, "config"),
                o["dockMode"]?.takeIf { it != JsonNull }?.let(AgentMode::from),
            )
        }
    }
}

/**
 * The one writer of a computer's `snapshot.json`: every change is merged
 * into what was last written and saved off the main thread, in order.
 */
class StemCellsOfflineFile(val file: DirectOfflineFile) {
    private var contents: StemCellsOfflineSnapshot? = null
    private val lock = Any()

    /** What was kept, read once; null when nothing was or it can't be read. */
    fun read(): StemCellsOfflineSnapshot? = synchronized(lock) {
        contents?.let { return it }
        val data = runCatching { file.snapshotFile.takeIf { it.isFile }?.readBytes() }.getOrNull() ?: return null
        val kept = runCatching { StemCellsOfflineSnapshot.from(DirectAgentClient.parse(data)) }.getOrNull()?.takeIf { it.format == StemCellsOfflineSnapshot.FORMAT }
        contents = kept
        kept
    }

    /** Changes what is kept and writes it, after any write before it. */
    fun update(change: (StemCellsOfflineSnapshot) -> StemCellsOfflineSnapshot) {
        val next = synchronized(lock) {
            val next = change(read() ?: StemCellsOfflineSnapshot())
            if (next == contents) return
            contents = next
            next
        }
        writer.execute { file.writeExtra(file.snapshotFile.name, next.json().toString().toByteArray()) }
    }

    /** Waits for the writes already asked for. */
    fun flush() {
        writer.submit {}.get()
    }

    private companion object {
        val writer = Executors.newSingleThreadExecutor { Thread(it, "stem-cells-offline").apply { isDaemon = true } }
    }
}
