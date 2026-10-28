package com.slopus.happy.model.details

import com.slopus.happy.data.MachineRecord
import com.slopus.happy.data.SessionRecord
import com.slopus.happy.model.parseJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/** happy-ios SessionDetails: what the session info and machine screens share. */
object SessionDetails {
    /** sessionUtils.ts `formatPathRelativeToHome`. */
    fun pathRelativeToHome(path: String, homeDir: String?): String {
        if (homeDir.isNullOrEmpty()) return path
        val home = homeDir.removeSuffix("/")
        if (!path.startsWith(home)) return path
        val relative = path.substring(home.length)
        if (relative.startsWith("/")) return "~$relative"
        if (relative.isEmpty()) return "~"
        return "~/$relative"
    }

    /** A string JavaScript treats as truthy: present and not empty. */
    fun truthyString(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    /** JavaScript's truthiness of a JSON value. */
    fun truthy(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonObject, is JsonArray -> true
        is JsonPrimitive -> when {
            value.isString -> value.content.isNotEmpty()
            value.booleanOrNull != null -> value.booleanOrNull == true
            else -> value.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: false
        }
    }

    fun number(value: JsonElement?): Double? = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    /** A number the way JavaScript's String(n) writes it: no trailing `.0`. */
    fun jsNumber(value: Double): String {
        if (!value.isFinite()) return if (value.isNaN()) "NaN" else if (value > 0) "Infinity" else "-Infinity"
        if (value == Math.rint(value) && Math.abs(value) < 1e21) return value.toLong().toString()
        return value.toString()
    }

    /** `new Date(ms).toLocaleString()`: "10/13/2026, 3:04:05 PM" in en-US. */
    fun localeDateTime(milliseconds: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
        val date = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
            .replace(Regex("(?<!y)yy(?!y)"), "y")
        val time = DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.MEDIUM, IsoChronology.INSTANCE, locale)
        return DateTimeFormatter.ofPattern("$date, $time", locale).withZone(zone).format(Instant.ofEpochMilli(milliseconds))
    }

    /** sessionUtils.ts `getSessionName`; null reads "New chat". */
    fun title(metadata: JsonObject?): String? {
        fun obj(key: String) = metadata?.get(key) as? JsonObject
        val bot = obj("bot")
        (bot?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
        if (bot == null && obj("task") != null) {
            (obj("task")?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
        }
        return (obj("summary")?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** app/(app)/machine/[id].tsx's rows (happy-ios `MachineDetailsModel`). */
data class MachineDetailsModel(
    val machineId: String,
    /** `displayName || host || 'unknown machine'`. */
    val name: String,
    val online: Boolean,
    /** The rename prompt's default and placeholder. */
    val displayName: String,
    val hostPlaceholder: String?,
    /** Present when the daemon has reported its state. */
    val daemon: Daemon?,
    val daemonStateVersion: Long,
    val cliAvailability: CliAvailability?,
    /** The five most recently updated sessions on this machine. */
    val recentSessions: List<RecentSession>,
    val host: String,
    val username: String?,
    val homeDirectory: String?,
    val platform: String?,
    val architecture: String?,
    /** null reads "Never". */
    val lastSeen: Long?,
    val metadataVersion: Long,
) {
    data class Daemon(val pid: String?, val httpPort: String?, val startedAt: Long?, val cliVersion: String?)

    data class CliAvailability(
        val claude: Boolean,
        val codex: Boolean,
        val gemini: Boolean,
        val openclaw: Boolean,
        /** Only daemons that know Happy Agent report it. */
        val rig: Boolean?,
        val detectedAt: Long,
    )

    /** [title] null reads "New chat"; [subtitle] null reads "unknown". */
    data class RecentSession(val id: String, val title: String?, val subtitle: String?)

    companion object {
        const val RECENT_SESSION_LIMIT = 5

        operator fun invoke(machine: MachineRecord, sessions: List<SessionRecord>): MachineDetailsModel {
            val metadata = parseJsonObject(machine.metadataJson)
            fun string(key: String) = SessionDetails.truthyString(metadata?.get(key))
            val state = parseJsonObject(machine.daemonStateJson)
            val daemon = state?.let {
                fun positive(key: String) = SessionDetails.number(it[key])?.takeIf { v -> v != 0.0 && !v.isNaN() }
                Daemon(
                    pid = positive("pid")?.let(SessionDetails::jsNumber),
                    httpPort = positive("httpPort")?.let(SessionDetails::jsNumber),
                    startedAt = positive("startTime")?.toLong(),
                    cliVersion = SessionDetails.truthyString(it["startedWithCliVersion"]),
                )
            }
            val cli = (metadata?.get("cliAvailability") as? JsonObject)?.let { cli ->
                CliAvailability(
                    claude = SessionDetails.truthy(cli["claude"]),
                    codex = SessionDetails.truthy(cli["codex"]),
                    gemini = SessionDetails.truthy(cli["gemini"]),
                    openclaw = SessionDetails.truthy(cli["openclaw"]),
                    rig = if (cli.containsKey("rig")) SessionDetails.truthy(cli["rig"]) else null,
                    detectedAt = SessionDetails.number(cli["detectedAt"])?.toLong() ?: 0,
                )
            }
            // storage.ts sessionsData: every session but side chats, newest update first (a stable sort).
            val recent = sessions.asSequence()
                .map { it to parseJsonObject(it.metadataJson) }
                .filter { (_, m) ->
                    (m?.get("machineId") as? JsonPrimitive)?.content == machine.id &&
                        (m?.get("isSideChat") as? JsonPrimitive)?.booleanOrNull != true
                }
                .sortedByDescending { (s, _) -> s.updatedAt }
                .take(RECENT_SESSION_LIMIT)
                .map { (s, m) ->
                    RecentSession(
                        id = s.id,
                        title = SessionDetails.title(m),
                        subtitle = m?.let {
                            SessionDetails.pathRelativeToHome(
                                (it["path"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "",
                                (it["homeDir"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content,
                            )
                        },
                    )
                }
                .toList()
            return MachineDetailsModel(
                machineId = machine.id,
                name = string("displayName") ?: string("host") ?: "unknown machine",
                online = machine.active,
                displayName = string("displayName") ?: "",
                hostPlaceholder = string("host"),
                daemon = daemon,
                daemonStateVersion = machine.daemonStateVersion,
                cliAvailability = cli,
                recentSessions = recent,
                host = string("host") ?: machine.id,
                username = string("username"),
                homeDirectory = string("homeDir"),
                platform = string("platform"),
                architecture = string("arch"),
                lastSeen = machine.activeAt.takeIf { it != 0L },
                metadataVersion = machine.metadataVersion,
            )
        }

        /** The metadata a rename writes: everything as it was, with the new display name, or without one when left empty. */
        fun renamedMetadata(metadata: JsonObject?, displayName: String): JsonObject {
            val fields = (metadata ?: JsonObject(emptyMap())).toMutableMap()
            val trimmed = displayName.trim()
            if (trimmed.isEmpty()) fields.remove("displayName") else fields["displayName"] = JsonPrimitive(trimmed)
            return JsonObject(fields)
        }
    }
}
