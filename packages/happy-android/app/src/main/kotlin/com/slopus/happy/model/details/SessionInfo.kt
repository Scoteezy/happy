package com.slopus.happy.model.details

import com.slopus.happy.data.MachineRecord
import com.slopus.happy.data.SessionRecord
import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.model.formatOSPlatform
import com.slopus.happy.model.GitStatus
import com.slopus.happy.model.isCliOutdated
import com.slopus.happy.model.parseJsonObject
import com.slopus.happy.model.rigIdentity
import com.slopus.happy.sync.ForkSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// app/(app)/session/[id]/info.tsx reduced to what it decides before drawing
// (happy-ios SessionInfoModel.swift, SessionInfoDerivation.swift and the
// rest of SessionDetails.swift). Read from the stored JSON, as iOS reads it,
// so a session whose metadata happy-app's schema would refuse still shows.

private fun JsonElement?.obj(key: String): JsonElement? = (this as? JsonObject)?.get(key)
private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** sessionFork.ts `nonEmpty`: a string with something besides spaces. */
private fun JsonElement?.nonBlank(): String? = string()?.takeIf { it.isNotBlank() }

/** What session info says about a session beyond [SessionDetails]' shared helpers. */
object SessionInfoText {
    /** info.tsx: `${id.substring(0, 8)}...${id.substring(id.length - 8)}`. */
    fun shortId(id: String): String = "${id.take(8)}...${id.takeLast(8)}"

    private fun isWindows(metadata: JsonObject?) = metadata.obj("os").string()?.lowercase() == "win32"

    /** resumeCommand.ts `buildResumeCommand`: on one line. */
    fun resumeCommand(metadata: JsonObject?): String? {
        if (metadata.obj("client").obj("id").string() == "rig" || metadata.obj("capabilities").obj("resume").bool() == false) return null
        val flavor = metadata.obj("flavor").string()
        val thread = SessionDetails.truthyString(metadata.obj("codexThreadId"))
        val claude = SessionDetails.truthyString(metadata.obj("claudeSessionId"))
        val invocation = when {
            (flavor == "codex" || flavor == "openai" || flavor == "gpt") && thread != null -> "happy codex --resume $thread"
            claude != null -> "happy claude --resume $claude"
            else -> return null
        }
        val path = metadata.obj("path").string()?.trim()?.takeIf { it.isNotEmpty() }
        val cd = path?.let {
            if (isWindows(metadata)) "Set-Location -LiteralPath '${it.replace("'", "''")}'" else "cd '${it.replace("'", "'\\''")}'"
        }
        return listOfNotNull(cd, invocation).joinToString(if (isWindows(metadata)) "; " else " && ")
    }

    /** info.tsx `formatSandboxMetadata`. */
    fun sandbox(sandbox: JsonElement?, homeDir: String?): String = when (sandbox) {
        null, JsonNull -> "Disabled"
        is JsonPrimitive -> if (sandbox.isString) sandbox.content else sandbox.booleanOrNull?.toString() ?: SessionDetails.number(sandbox)?.let(SessionDetails::jsNumber) ?: sandbox.content
        // An array is an object to JavaScript, with no fields set.
        is JsonArray -> "Enabled"
        is JsonObject -> if (sandbox["enabled"].bool() == false) "Disabled" else buildList {
            add("Enabled")
            sandbox["sessionIsolation"].string()?.let { add("isolation=$it") }
            sandbox["networkMode"].string()?.let { add("network=$it") }
            sandbox["workspaceRoot"].string()?.let { add("workspace=${SessionDetails.pathRelativeToHome(it, homeDir)}") }
        }.joinToString(" | ")
    }

    /** info.tsx `formatDangerouslySkipPermissionsMetadata`. */
    fun dangerouslySkipPermissions(value: JsonElement?, flavor: String?, permissionMode: String?, sandbox: JsonElement?): String {
        value.bool()?.let { return if (it) "Enabled" else "Disabled" }
        if (permissionMode == "bypassPermissions" || permissionMode == "yolo") return "Enabled"
        if (flavor == "claude" && (sandbox as? JsonObject)?.get("enabled").bool() == true) return "Enabled"
        return "Unknown"
    }

    /** info.tsx's AI Provider row: Happy Agent's provider, else the flavor. */
    fun aiProvider(metadata: JsonObject?, typed: SessionMetadata?): String {
        typed.rigIdentity()?.let { return it.providerName }
        return when (val flavor = SessionDetails.truthyString(metadata.obj("flavor")) ?: "claude") {
            "claude" -> "Claude"
            "gpt", "openai" -> "Codex"
            "gemini" -> "Gemini"
            "openclaw" -> "OpenClaw"
            else -> flavor
        }
    }

    /** The Activity group's counters (`metadata.activity`), hard-coded English in info.tsx; null hides a row. */
    data class Activity(val subagents: String?, val workflows: String?, val processes: String?, val tasks: String?)

    fun activity(metadata: JsonObject?): Activity {
        val activity = metadata.obj("activity")
        fun count(group: String, key: String) = SessionDetails.number(activity.obj(group).obj(key))?.toLong() ?: 0
        val subagentsRunning = count("subagents", "running")
        val subagentsQueued = count("subagents", "queued")
        val workflows = count("workflows", "running")
        val processes = count("processes", "running")
        val tasksPending = count("tasks", "pending")
        val tasksInProgress = count("tasks", "inProgress")
        return Activity(
            subagents = if (subagentsRunning + subagentsQueued > 0) "$subagentsRunning running · $subagentsQueued queued" else null,
            workflows = if (workflows > 0) "$workflows running" else null,
            processes = if (processes > 0) "$processes running" else null,
            tasks = if (tasksPending + tasksInProgress > 0) "$tasksInProgress in progress · $tasksPending pending" else null,
        )
    }

    /** Why Resume can or can't run (useSessionQuickActions.ts `getResumeAvailability`). */
    enum class Resume {
        /** The row is not shown at all. */
        Hidden, MissingMachine, MissingBackendId, SameMachineOnly, MachineOffline, Available;

        val canResume get() = this == Available
        val showsRow get() = this != Hidden
    }

    fun resumeAvailability(session: SessionRecord, metadata: JsonObject?, machine: MachineRecord?): Resume {
        if (metadata.obj("client").obj("id").string() == "rig" || metadata.obj("capabilities").obj("resume").bool() == false) return Resume.Hidden
        if (session.active) return Resume.Hidden
        if (SessionDetails.truthyString(metadata.obj("machineId")) == null) return Resume.MissingMachine
        if (SessionDetails.truthyString(metadata.obj("claudeSessionId")) == null && SessionDetails.truthyString(metadata.obj("codexThreadId")) == null) {
            return Resume.MissingBackendId
        }
        machine ?: return Resume.SameMachineOnly
        if (!machine.active) return Resume.MachineOffline
        // Older daemons don't publish resumeSupport and can't run the RPC.
        if (parseJsonObject(machine.metadataJson).obj("resumeSupport").obj("rpcAvailable").bool() != true) return Resume.Hidden
        return Resume.Available
    }

    /** sessionFork.ts `getSessionForkSource`. */
    fun forkSource(sessionId: String, metadata: JsonObject?): ForkSource? {
        val machineId = metadata.obj("machineId").nonBlank() ?: return null
        val directory = metadata.obj("path").nonBlank() ?: return null
        if (metadata.obj("flavor").string() == "codex") {
            val thread = metadata.obj("codexThreadId").nonBlank() ?: return null
            return ForkSource.Codex(sessionId, machineId, directory, thread)
        }
        val claude = metadata.obj("claudeSessionId").nonBlank() ?: return null
        return ForkSource.Claude(sessionId, machineId, directory, claude)
    }

    /** rigGitLineChanges.ts `compactCount`: 999, 1.2k, 12k. */
    fun compactCount(value: Long): String {
        val count = maxOf(0, value)
        if (count < 1_000) return count.toString()
        if (count < 5_000) return SessionDetails.jsNumber(Math.round(count / 100.0) / 10.0) + "k"
        return "${Math.round(count / 1_000.0)}k"
    }

    /** `JSON.stringify(value, null, 2)`, keys sorted as iOS prints them. */
    fun prettyJson(value: JsonElement): String = StringBuilder().also { write(value, 0, it) }.toString()

    private fun write(value: JsonElement, indent: Int, out: StringBuilder) {
        val pad = " ".repeat(indent + 2)
        val close = " ".repeat(indent)
        when (value) {
            JsonNull -> out.append("null")
            is JsonPrimitive -> when {
                value.isString -> out.append(jsonString(value.content))
                value.booleanOrNull != null -> out.append(value.booleanOrNull.toString())
                else -> out.append(SessionDetails.number(value)?.takeIf { it.isFinite() }?.let(SessionDetails::jsNumber) ?: "null")
            }
            is JsonArray -> {
                if (value.isEmpty()) { out.append("[]"); return }
                out.append("[\n")
                value.forEachIndexed { i, item ->
                    out.append(pad)
                    write(item, indent + 2, out)
                    out.append(if (i < value.size - 1) ",\n" else "\n")
                }
                out.append(close).append("]")
            }
            is JsonObject -> {
                if (value.isEmpty()) { out.append("{}"); return }
                out.append("{\n")
                val keys = value.keys.sorted()
                keys.forEachIndexed { i, key ->
                    out.append(pad).append(jsonString(key)).append(": ")
                    write(value.getValue(key), indent + 2, out)
                    out.append(if (i < keys.size - 1) ",\n" else "\n")
                }
                out.append(close).append("}")
            }
        }
    }

    /** A JSON string literal escaped as JSON.stringify escapes it. */
    fun jsonString(text: String): String {
        val out = StringBuilder("\"")
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c.code < 0x20) out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }

    /**
     * copySessionMetadataToClipboard.ts: the metadata as pretty JSON. happy-app
     * appends its in-memory client log; this app keeps none, so the "& Client
     * Logs" copy is the metadata under its heading, as happy-app writes it when
     * the log is empty.
     */
    fun metadataClipboard(metadata: JsonElement, withLogs: Boolean): String {
        val json = prettyJson(metadata)
        return if (withLogs) "=== Session Metadata ===\n$json" else json
    }
}

/** info.tsx's line counts, drawn with a leading ≈ when approximate. */
data class LineChanges(val insertions: Long, val deletions: Long, val approximate: Boolean)

/** sessionGitPresentation.ts, without the subtitle info doesn't use. */
data class GitPresentation(val changedFileCount: Long?, val changes: LineChanges?) {
    /** The Changes row draws its own right element when either is known. */
    val hasAccessory get() = changedFileCount != null || changes != null

    companion object {
        /** Happy Agent's published comparison wins over the working-tree status. */
        fun of(metadata: JsonObject?, status: GitStatus?): GitPresentation {
            val git = metadata.obj("git")
            if (metadata.obj("client").obj("id").string() == "rig" && git != null && git != JsonNull) {
                val insertions = SessionDetails.number(git.obj("insertions"))?.toLong() ?: 0
                val deletions = SessionDetails.number(git.obj("deletions"))?.toLong() ?: 0
                return GitPresentation(
                    SessionDetails.number(git.obj("changedFiles"))?.toLong(),
                    if (insertions == 0L && deletions == 0L) null else LineChanges(insertions, deletions, git.obj("countsExact").bool() != true),
                )
            }
            status ?: return GitPresentation(null, null)
            val insertions = status.linesAdded
            val deletions = status.linesRemoved
            return GitPresentation(
                status.changedFileCount?.toLong(),
                if (insertions > 0 || deletions > 0) LineChanges(insertions, deletions, false) else null,
            )
        }
    }
}

/** Everything info.tsx decides before drawing (happy-ios `SessionInfoModel`). */
data class SessionInfoModel(
    val sessionId: String,
    /** sessionUtils.ts `getSessionName`; null reads "New chat". */
    val title: String?,
    val isConnected: Boolean,
    val isBot: Boolean,
    val isTask: Boolean,
    /** A task whose Happy Agent answers `leaveTask` (happy-ios SessionState.canLeaveTask). */
    val canLeaveTask: Boolean,
    /** A task the paired person may archive; every other session may be. */
    val canArchive: Boolean,
    // Quick Actions
    val git: GitPresentation,
    val machineId: String?,
    val resume: SessionInfoText.Resume,
    val canFork: Boolean,
    val forkSource: ForkSource?,
    val parentSessionId: String?,
    /** The CLI predates `MINIMUM_CLI_VERSION`. */
    val cliOutdated: Boolean,
    // Session details
    val sessionIdShort: String,
    val claudeSessionId: String?,
    val codexThreadId: String?,
    /** Shown only while disconnected. */
    val resumeCommand: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val seq: Long,
    /** The Metadata group; null without metadata. */
    val metadata: Metadata?,
    /** The Agent State group; null without agent state and for Happy Agent. */
    val agentState: AgentState?,
    // Activity
    val thinking: Boolean,
    val thinkingAt: Long,
    val activity: SessionInfoText.Activity,
) {
    /** A bot or a task: archived, never deleted. */
    val keepsConversation get() = isBot || isTask

    data class Metadata(
        val host: String?,
        val path: String,
        val cliVersion: String?,
        val operatingSystem: String?,
        /** Happy Agent's client and version. */
        val client: String?,
        val aiProvider: String,
        val model: String?,
        /** Not shown for Happy Agent. */
        val sandbox: String?,
        val dangerouslySkipPermissions: String?,
        val processId: String?,
        val happyHome: String?,
    )

    data class AgentState(val controlledByUser: Boolean, val pendingRequests: Int)

    companion object {
        /**
         * [permissionMode] is the session's local pick (the composer's), which
         * "Dangerously Skip Permissions" reads; [continuationExperiments] is
         * the account's `expResumeSession`.
         */
        operator fun invoke(
            session: SessionRecord,
            machine: MachineRecord?,
            continuationExperiments: Boolean,
            gitStatus: GitStatus?,
            permissionMode: String?,
            thinking: Boolean = false,
            thinkingAt: Long = 0,
        ): SessionInfoModel {
            val metadata = parseJsonObject(session.metadataJson)
            val typed = metadata?.let(SessionMetadata::parse)
            fun string(key: String) = metadata.obj(key).string()
            val isRig = metadata.obj("client").obj("id").string() == "rig"
            val isBot = metadata.obj("bot") is JsonObject
            val isTask = !isBot && metadata.obj("task") is JsonObject
            val connected = session.active
            val version = SessionDetails.truthyString(metadata.obj("version"))
            val forkSource = SessionInfoText.forkSource(session.id, metadata)
            val details = metadata?.let { m ->
                val homeDir = string("homeDir")
                val identity = typed.rigIdentity()
                Metadata(
                    host = string("host"),
                    path = SessionDetails.pathRelativeToHome(string("path") ?: "", homeDir),
                    cliVersion = version,
                    operatingSystem = SessionDetails.truthyString(m["os"])?.let(::formatOSPlatform),
                    client = if (isRig) {
                        (m.obj("client").obj("name").string() ?: "Rig") + (SessionDetails.truthyString(m.obj("client").obj("version"))?.let { " $it" } ?: "")
                    } else null,
                    aiProvider = SessionInfoText.aiProvider(m, typed),
                    model = identity?.modelName,
                    sandbox = if (isRig) null else SessionInfoText.sandbox(m["sandbox"], homeDir),
                    dangerouslySkipPermissions = if (isRig) null else SessionInfoText.dangerouslySkipPermissions(
                        m["dangerouslySkipPermissions"], string("flavor"), permissionMode, m["sandbox"],
                    ),
                    processId = SessionDetails.number(m["hostPid"])?.takeIf { it != 0.0 }?.let(SessionDetails::jsNumber),
                    happyHome = SessionDetails.truthyString(m["happyHomeDir"])?.let { SessionDetails.pathRelativeToHome(it, homeDir) },
                )
            }
            val state = parseJsonObject(session.agentStateJson)
            return SessionInfoModel(
                sessionId = session.id,
                title = SessionDetails.title(metadata),
                isConnected = connected,
                isBot = isBot,
                isTask = isTask,
                canLeaveTask = isTask && (metadata.obj("capabilities").obj("rpcMethods") as? JsonArray).orEmpty().any { it.string() == "leaveTask" },
                canArchive = !isTask || metadata.obj("task").obj("canArchive").bool() == true,
                git = GitPresentation.of(metadata, gitStatus),
                machineId = SessionDetails.truthyString(metadata.obj("machineId")),
                resume = SessionInfoText.resumeAvailability(session, metadata, machine),
                canFork = continuationExperiments && !isRig && forkSource != null && machine?.active == true,
                forkSource = forkSource,
                parentSessionId = SessionDetails.truthyString(metadata.obj("parentSessionId")),
                cliOutdated = version?.let { isCliOutdated(it) } ?: false,
                sessionIdShort = SessionInfoText.shortId(session.id),
                claudeSessionId = SessionDetails.truthyString(metadata.obj("claudeSessionId")),
                codexThreadId = SessionDetails.truthyString(metadata.obj("codexThreadId")),
                resumeCommand = if (connected) null else SessionInfoText.resumeCommand(metadata),
                createdAt = session.createdAt,
                updatedAt = session.updatedAt,
                seq = session.seq,
                metadata = details,
                agentState = if (state != null && !isRig) AgentState(
                    controlledByUser = SessionDetails.truthy(state["controlledByUser"]),
                    pendingRequests = (state["requests"] as? JsonObject)?.size ?: 0,
                ) else null,
                thinking = thinking,
                thinkingAt = thinkingAt,
                activity = SessionInfoText.activity(metadata),
            )
        }
    }
}

/** info.tsx's groups. */
enum class SessionInfoSection { QuickActions, CliWarning, Details, Metadata, AgentState, Activity, Raw }

/** One block of the "Raw JSON (Dev Mode)" group, already printed. */
data class SessionInfoRawBlock(val kind: Kind, val code: String) {
    enum class Kind { AgentState, Metadata, Session }

    companion object {
        /** The agent state and metadata when there are any, then the session. */
        fun blocks(record: SessionRecord, thinking: Boolean, thinkingAt: Long): List<SessionInfoRawBlock> = buildList {
            val state = parseJsonObject(record.agentStateJson)
            val metadata = parseJsonObject(record.metadataJson)
            state?.let { add(SessionInfoRawBlock(Kind.AgentState, SessionInfoText.prettyJson(it))) }
            metadata?.let { add(SessionInfoRawBlock(Kind.Metadata, SessionInfoText.prettyJson(it))) }
            add(SessionInfoRawBlock(Kind.Session, SessionInfoText.prettyJson(buildJsonObject {
                put("id", record.id)
                put("seq", record.seq)
                put("createdAt", record.createdAt)
                put("updatedAt", record.updatedAt)
                put("active", record.active)
                put("activeAt", record.activeAt)
                put("metadata", metadata ?: JsonNull)
                put("metadataVersion", record.metadataVersion)
                put("agentState", state ?: JsonNull)
                put("agentStateVersion", record.agentStateVersion)
                put("thinking", thinking)
                put("thinkingAt", thinkingAt)
            })))
        }
    }
}

/** info.tsx reduced to what it draws (happy-ios `SessionInfoFrame`). */
data class SessionInfoFrame(
    val content: Content,
    val forking: Boolean = false,
    val copiedResumeCommand: Boolean = false,
    /** The raw JSON blocks, in order; empty without the group. */
    val raw: List<SessionInfoRawBlock> = emptyList(),
) {
    sealed interface Content {
        /** Sessions were never fetched and this one isn't stored. */
        data object Loading : Content
        data object Deleted : Content
        data class Shown(val model: SessionInfoModel) : Content
    }

    val model: SessionInfoModel? get() = (content as? Content.Shown)?.model

    /** The groups shown, top to bottom. */
    val sections: List<SessionInfoSection>
        get() {
            val model = model ?: return emptyList()
            return buildList {
                add(SessionInfoSection.QuickActions)
                if (model.cliOutdated) add(SessionInfoSection.CliWarning)
                add(SessionInfoSection.Details)
                if (model.metadata != null) add(SessionInfoSection.Metadata)
                if (model.agentState != null) add(SessionInfoSection.AgentState)
                add(SessionInfoSection.Activity)
                if (raw.isNotEmpty()) add(SessionInfoSection.Raw)
            }
        }

    /**
     * What each group shows, so a group is built again only when its own
     * content changed (SessionInfoDerivation.changes): a new message changes
     * Details' last updated and sequence, a heartbeat changes nothing.
     */
    fun sectionContent(section: SessionInfoSection): Any? {
        val m = model ?: return null
        return when (section) {
            SessionInfoSection.QuickActions -> listOf(m.git, m.canLeaveTask, m.machineId, m.resume, m.canFork, m.parentSessionId, m.keepsConversation, m.canArchive, m.isTask, forking)
            SessionInfoSection.CliWarning -> Unit
            SessionInfoSection.Details -> listOf(m.sessionId, m.claudeSessionId, m.codexThreadId, m.resumeCommand, copiedResumeCommand, m.isConnected, m.createdAt, m.updatedAt, m.seq)
            SessionInfoSection.Metadata -> listOf(m.metadata, m.cliOutdated)
            SessionInfoSection.AgentState -> m.agentState
            SessionInfoSection.Activity -> listOf(m.thinking, m.thinkingAt, m.activity)
            SessionInfoSection.Raw -> raw
        }
    }
}
