package com.slopus.happy.stemcells

import com.slopus.happy.direct.Wire
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * A conversation's detail, ported from happy-ios `StemCellsSessionDetail.swift`.
 */

private fun JsonObject.optObject(key: String): JsonObject? = this[key]?.takeIf { it != JsonNull }?.let(Wire::obj)

data class DetailAgent(
    val id: String,
    val title: String? = null,
    val status: String = "idle",
    val parentAgentId: String? = null,
    val subtask: Boolean? = null,
    val createdAt: Double? = null,
    val archivedAt: Double? = null,
    /** Its own lifetime input and output tokens; only children carry it. */
    val totalTokens: Int? = null,
    val subagents: AgentSubagents? = null,
) {
    companion object {
        fun from(json: JsonElement): DetailAgent {
            val o = Wire.obj(json)
            return DetailAgent(
                Wire.string(o, "id"), Wire.stringOrNull(o, "title"), Wire.string(o, "status"), Wire.stringOrNull(o, "parentAgentId"),
                Wire.boolOrNull(o, "subtask"), Wire.doubleOrNull(o, "createdAt"), Wire.doubleOrNull(o, "archivedAt"),
                Wire.intOrNull(o, "totalTokens"), o.optObject("subagents")?.let(AgentSubagents::from),
            )
        }
    }
}

/** A background process an agent started. */
data class AgentProcess(val id: String, val command: String, val status: String, val startedAt: Double? = null, val endedAt: Double? = null) {
    companion object {
        fun from(json: JsonElement): AgentProcess {
            val o = Wire.obj(json)
            return AgentProcess(Wire.string(o, "id"), Wire.string(o, "command"), Wire.string(o, "status"), Wire.doubleOrNull(o, "startedAt"), Wire.doubleOrNull(o, "endedAt"))
        }
    }
}

/** One model's lifetime inference totals. */
data class TokenUsage(val input: Double? = null, val output: Double? = null) {
    companion object {
        fun from(json: JsonElement): TokenUsage {
            val o = Wire.obj(json)
            return TokenUsage(Wire.doubleOrNull(o, "input"), Wire.doubleOrNull(o, "output"))
        }
    }
}

/**
 * `GET /v0/agents/:agentId/bootstrap`, for the detail: the agent, its mode,
 * its subtree's usage by provider then model, and its activity.
 */
data class AgentDetail(
    val agent: DetailAgent,
    val mode: AgentMode? = null,
    val usage: Map<String, Map<String, TokenUsage>>? = null,
    val processes: List<AgentProcess>? = null,
    val subagents: List<DetailAgent>? = null,
) {
    companion object {
        fun from(json: JsonElement): AgentDetail {
            val o = Wire.obj(json)
            return AgentDetail(
                DetailAgent.from(o["agent"] ?: JsonNull),
                o.optObject("mode")?.let(AgentMode::from),
                o.optObject("usage")?.mapValues { (_, models) -> Wire.obj(models).mapValues { TokenUsage.from(it.value) } },
                o["processes"]?.takeIf { it != JsonNull }?.let { Wire.array(it).map(AgentProcess::from) },
                o["subagents"]?.takeIf { it != JsonNull }?.let { Wire.array(it).map(DetailAgent::from) },
            )
        }
    }
}

/**
 * What a conversation's detail shows: its numbers, and desktop's Activity
 * (`HappyAgentActivityPanel`): the children at work and the terminals
 * running, then the children that have settled.
 */
data class StemCellsSessionDetail(
    /** What the daemon calls it, and whether it is a subtask at work: for one the sidebar doesn't hold. */
    val title: String?,
    val subtask: Boolean,
    val working: Boolean,
    val createdAt: Double?,
    /** Input and output tokens over every model the subtree used, as desktop counts a child's; null before any was recorded. */
    val tokens: Int?,
    val subagentsRunning: Int,
    val subagentsTotal: Int,
    val mode: AgentMode?,
    val running: List<Child>,
    val terminals: List<Terminal>,
    val completed: List<Child>,
) {
    data class Child(
        val id: String,
        val title: String?,
        /** The daemon's status: `idle`, or what it is at. */
        val status: String,
        val archived: Boolean,
        val tokens: Int?,
    ) {
        val running: Boolean get() = status != "idle" && !archived
    }

    data class Terminal(val id: String, val command: String, val startedAt: Double?)

    val isQuiet: Boolean get() = running.isEmpty() && terminals.isEmpty() && completed.isEmpty()

    companion object {
        fun of(detail: AgentDetail): StemCellsSessionDetail {
            val models = detail.usage.orEmpty().values.flatMap { it.values }
            val children = detail.subagents.orEmpty().map { Child(it.id, it.title, it.status, it.archivedAt != null, it.totalTokens) }
            return StemCellsSessionDetail(
                title = detail.agent.title,
                subtask = detail.agent.subtask == true,
                working = detail.agent.status != "idle",
                createdAt = detail.agent.createdAt,
                tokens = if (models.isEmpty()) null else models.sumOf { (it.input ?: 0.0) + (it.output ?: 0.0) }.toInt(),
                subagentsRunning = detail.agent.subagents?.running ?: 0,
                subagentsTotal = detail.agent.subagents?.total ?: detail.subagents?.size ?: 0,
                mode = detail.mode,
                running = children.filter { it.running },
                terminals = detail.processes.orEmpty().filter { it.status == "running" }.map { Terminal(it.id, it.command, it.startedAt) },
                completed = children.filter { !it.running },
            )
        }
    }
}

/** Numbers as the detail's tiles say them, words left to the screen. */
object StemCellsDetailFormat {
    /** 980, 1.2K, 12K, 121M, 1.2B: tenths only under ten, never rounded up. */
    fun tokens(count: Int): String {
        val value = maxOf(0, count).toLong()
        for ((unit, size) in listOf("B" to 1_000_000_000L, "M" to 1_000_000L, "K" to 1_000L)) {
            if (value < size) continue
            if (value >= 10 * size) return "${value / size}$unit"
            val tenths = value * 10 / size
            return (if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}") + unit
        }
        return "$value"
    }

    sealed class Age {
        data object JustNow : Age()
        data class Minutes(val count: Int) : Age()
        data class Hours(val count: Int) : Age()
        data class Days(val count: Int) : Age()
        data class Months(val count: Int) : Age()
        data class Years(val count: Int) : Age()
    }

    /** How long ago `since` was, in the largest whole unit; both in Unix milliseconds. */
    fun age(since: Double, now: Double): Age {
        val seconds = maxOf(0.0, (now - since) / 1000)
        val minutes = (seconds / 60).toInt()
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> Age.JustNow
            hours < 1 -> Age.Minutes(minutes)
            days < 1 -> Age.Hours(hours)
            days < 30 -> Age.Days(days)
            days < 365 -> Age.Months(days / 30)
            else -> Age.Years(days / 365)
        }
    }

    /**
     * When something started, as near as a person says it: today or
     * yesterday at a time, a weekday this week, a date this year, or a date
     * with its year. Each holds the moment in Unix milliseconds.
     */
    sealed class Started {
        abstract val at: Long
        data class Today(override val at: Long) : Started()
        data class Yesterday(override val at: Long) : Started()
        data class Weekday(override val at: Long) : Started()
        data class ThisYear(override val at: Long) : Started()
        data class Date(override val at: Long) : Started()
    }

    /** `since` and `now` in Unix milliseconds. */
    fun started(since: Double, now: Long, zone: ZoneId = ZoneId.systemDefault()): Started {
        val at = since.toLong()
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (day == today) return Started.Today(at)
        if (day == today.minusDays(1)) return Started.Yesterday(at)
        val days = ChronoUnit.DAYS.between(day, today)
        if (days in 1..6) return Started.Weekday(at)
        if (day.year == today.year) return Started.ThisYear(at)
        return Started.Date(at)
    }
}

/**
 * Where a conversation sits in the sidebar's tree, as its detail draws it:
 * nested, its parent, itself among its nearest siblings, and its own
 * children under it, nested again from the parent so the tree reads on its
 * own; at the top level, itself over its children alone.
 */
object StemCellsFamily {
    fun of(id: String, rows: List<StemCellsRow>, around: Int = 2, childLimit: Int = 8): List<StemCellsRow> {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return emptyList()
        val depth = rows[index].depth
        val topLevel = depth == 0
        fun opens(row: StemCellsRow) = (row.kind == StemCellsRow.Kind.Space || row.kind == StemCellsRow.Kind.Session) && row.targetKind != "surface" && row.agentId != null
        fun boundary(row: StemCellsRow) = (row.kind == StemCellsRow.Kind.Section || row.kind == StemCellsRow.Kind.Separator) && row.depth == 0
        // The parent, and where its children start.
        var parent: Int? = null
        var start = 0
        var j = index - 1
        while (j >= 0) {
            val row = rows[j]
            if (depth > 0 && row.depth == depth - 1 && opens(row)) { parent = j; start = j + 1; break }
            if (depth == 0 && boundary(row)) {
                if (row.kind == StemCellsRow.Kind.Section) parent = j
                start = j + 1
                break
            }
            j--
        }
        var end = rows.size
        for (k in index + 1 until rows.size) {
            if (rows[k].depth < depth || (depth == 0 && boundary(rows[k]))) { end = k; break }
        }
        val siblings = (start until end).filter { rows[it].depth == depth && opens(rows[it]) }
        val at = siblings.indexOf(index).coerceAtLeast(0)
        val width = 2 * around + 1
        val lower = maxOf(0, minOf(at - around, siblings.size - width))
        val shown = if (topLevel) listOf(index) else siblings.subList(lower, minOf(siblings.size, lower + width))
        val children = ArrayList<Int>()
        for (k in index + 1 until rows.size) {
            if (rows[k].depth <= depth || boundary(rows[k])) break
            if (rows[k].depth == depth + 1 && opens(rows[k])) children += k
        }
        // Under a row, everything moves in a level; under a section, siblings stay at its level.
        val nested = !topLevel && (parent?.let { rows[it].kind != StemCellsRow.Kind.Section } ?: false)
        val family = ArrayList<StemCellsRow>()
        if (parent != null && !topLevel) {
            val row = rows[parent]
            family += row.copy(depth = 0, collapsed = if (row.kind == StemCellsRow.Kind.Section) false else null, hasHiddenSessions = false)
        }
        for (sibling in shown) {
            family += rows[sibling].copy(depth = if (nested) 1 else 0, collapsed = null, hasHiddenSessions = false)
            if (sibling != index) continue
            for (child in children.take(maxOf(0, childLimit))) {
                family += rows[child].copy(depth = if (nested) 2 else 1, collapsed = null, hasHiddenSessions = false)
            }
        }
        return family
    }
}
