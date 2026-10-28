package com.slopus.happy.model.details

import com.slopus.happy.sync.ForkOptions
import com.slopus.happy.sync.ForkSource
import com.slopus.happy.sync.RewindPointsResult
import com.slopus.happy.sync.SpawnSessionResult
import kotlin.math.floor

/**
 * components/DuplicateSheet.tsx without the drawing (happy-ios
 * DuplicateSheetModel): the provider's rewind points (Claude's JSONL turns,
 * Codex's thread items), newest first, and the fork that keeps everything up
 * to the chosen one.
 */
object DuplicateSheetModel {
    data class Point(
        /** Claude uuid or Codex item id. */
        val id: String,
        val text: String,
        /** Milliseconds. */
        val timestamp: Double,
    )

    sealed interface Points {
        data class Loaded(val points: List<Point>) : Points
        /** The provider's own words, or the missing-metadata message. */
        data class Failed(val message: String) : Points
    }

    /** The machine calls the sheet makes; tests pass fakes. */
    interface Ops {
        suspend fun claudeRewindPoints(machineId: String, directory: String, claudeSessionId: String): RewindPointsResult
        suspend fun codexRewindPoints(machineId: String, directory: String, codexThreadId: String): RewindPointsResult
        suspend fun forkAndSpawn(source: ForkSource, options: ForkOptions): SpawnSessionResult
    }

    /** happy-app's HappyError: the words to show. */
    class Failure(message: String) : Exception(message)

    /** Lists the session's rewind points over its machine, newest first. */
    suspend fun loadPoints(source: ForkSource?, ops: Ops, missingMetadata: String): Points {
        val result = when (source) {
            null -> return Points.Failed(missingMetadata)
            is ForkSource.Claude -> ops.claudeRewindPoints(source.machineId, source.directory, source.claudeSessionId)
            is ForkSource.Codex -> ops.codexRewindPoints(source.machineId, source.directory, source.codexThreadId)
        }
        return when (result) {
            is RewindPointsResult.Success -> Points.Loaded(result.points.reversed().map { Point(it.id, it.text, it.timestamp) })
            is RewindPointsResult.Error -> Points.Failed(result.errorMessage)
        }
    }

    /** Forks the session and cuts it after [point]; the new session's id. */
    suspend fun duplicate(source: ForkSource?, point: Point, ops: Ops, missingMetadata: String, generic: String): String {
        source ?: throw Failure(missingMetadata)
        val options = when (source) {
            is ForkSource.Claude -> ForkOptions(cutAfterUuid = point.id)
            is ForkSource.Codex -> ForkOptions(cutAfterItemId = point.id)
        }
        return when (val result = ops.forkAndSpawn(source, options)) {
            is SpawnSessionResult.Success -> result.sessionId
            is SpawnSessionResult.Error -> throw Failure(result.errorMessage)
            else -> throw Failure(generic)
        }
    }

    /** A row's text: whitespace collapsed, cut at 140 UTF-16 units with `…`. */
    fun preview(text: String): String {
        val collapsed = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
        return if (collapsed.length <= 140) collapsed else collapsed.substring(0, 140) + "…"
    }

    private val WHITESPACE = Regex("\\s+")

    /** DuplicateSheet.tsx `formatRelativeTime`. */
    sealed interface Age {
        data object JustNow : Age
        data class Minutes(val count: Int) : Age
        data class Hours(val count: Int) : Age
        data class Days(val count: Int) : Age
    }

    fun age(timestamp: Double, now: Double): Age {
        val minutes = floor((now - timestamp) / 60_000).toInt()
        if (minutes < 1) return Age.JustNow
        if (minutes < 60) return Age.Minutes(minutes)
        val hours = minutes / 60
        if (hours < 24) return Age.Hours(hours)
        return Age.Days(hours / 24)
    }
}
