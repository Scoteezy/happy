package com.slopus.happy.model.details

import com.slopus.happy.sync.ForkOptions
import com.slopus.happy.sync.ForkSource
import com.slopus.happy.sync.RewindPoint
import com.slopus.happy.sync.RewindPointsResult
import com.slopus.happy.sync.SpawnSessionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

// happy-ios SecondarySessionDetailsTests.swift: DuplicateSheetModelTests and the duplicate sheet's load and fork.

class DuplicateSheetModelTest {
    private class FakeOps(
        val points: RewindPointsResult = RewindPointsResult.Success(emptyList()),
        val spawn: SpawnSessionResult = SpawnSessionResult.Success("new"),
    ) : DuplicateSheetModel.Ops {
        val calls = mutableListOf<String>()
        var options: ForkOptions? = null

        override suspend fun claudeRewindPoints(machineId: String, directory: String, claudeSessionId: String): RewindPointsResult {
            calls += "claude $machineId $directory $claudeSessionId"
            return points
        }

        override suspend fun codexRewindPoints(machineId: String, directory: String, codexThreadId: String): RewindPointsResult {
            calls += "codex $machineId $directory $codexThreadId"
            return points
        }

        override suspend fun forkAndSpawn(source: ForkSource, options: ForkOptions): SpawnSessionResult {
            this.options = options
            return spawn
        }
    }

    private val claude = ForkSource.Claude("s1", "m1", "/repo", "c1")
    private val codex = ForkSource.Codex("s1", "m1", "/repo", "t1")

    @Test fun previewCollapsesWhitespace() {
        assertEquals("fix the bug", DuplicateSheetModel.preview("  fix\n\n the   bug \t"))
        assertEquals("", DuplicateSheetModel.preview(" \n "))
    }

    @Test fun previewCutsAt140() {
        val exact = "a".repeat(140)
        assertEquals(exact, DuplicateSheetModel.preview(exact))
        assertEquals("$exact…", DuplicateSheetModel.preview(exact + "b"))
        // Counted after collapsing, in UTF-16 units as `String.length` is.
        assertEquals("a ".repeat(70) + "…", DuplicateSheetModel.preview("a ".repeat(80)))
        assertEquals("é".repeat(140) + "…", DuplicateSheetModel.preview("é".repeat(141)))
    }

    @Test fun age() {
        val now = 10_000_000_000.0
        val minute = 60_000.0
        assertEquals(DuplicateSheetModel.Age.JustNow, DuplicateSheetModel.age(now - minute + 1, now))
        assertEquals(DuplicateSheetModel.Age.JustNow, DuplicateSheetModel.age(now + minute, now))
        assertEquals(DuplicateSheetModel.Age.Minutes(1), DuplicateSheetModel.age(now - minute, now))
        assertEquals(DuplicateSheetModel.Age.Minutes(59), DuplicateSheetModel.age(now - 59 * minute - 59_999, now))
        assertEquals(DuplicateSheetModel.Age.Hours(1), DuplicateSheetModel.age(now - 60 * minute, now))
        assertEquals(DuplicateSheetModel.Age.Hours(23), DuplicateSheetModel.age(now - (24 * 60 - 1) * minute, now))
        assertEquals(DuplicateSheetModel.Age.Days(1), DuplicateSheetModel.age(now - 24 * 60 * minute, now))
        assertEquals(DuplicateSheetModel.Age.Days(2), DuplicateSheetModel.age(now - 49 * 60 * minute, now))
    }

    @Test fun withoutASourceItSaysMetadataIsMissing() = runBlocking {
        val ops = FakeOps()
        assertEquals(DuplicateSheetModel.Points.Failed("missing"), DuplicateSheetModel.loadPoints(null, ops, "missing"))
        val error = assertThrows(DuplicateSheetModel.Failure::class.java) {
            runBlocking { DuplicateSheetModel.duplicate(null, DuplicateSheetModel.Point("p", "", 0.0), ops, "missing", "generic") }
        }
        assertEquals("missing", error.message)
        assertEquals(emptyList<String>(), ops.calls)
    }

    @Test fun pointsAreNewestFirstFromTheProvider() = runBlocking {
        val ops = FakeOps(RewindPointsResult.Success(listOf(RewindPoint("a", "first", 1.0), RewindPoint("b", "second", 2.0))))
        val loaded = DuplicateSheetModel.loadPoints(claude, ops, "missing")
        assertEquals(DuplicateSheetModel.Points.Loaded(listOf(DuplicateSheetModel.Point("b", "second", 2.0), DuplicateSheetModel.Point("a", "first", 1.0))), loaded)
        DuplicateSheetModel.loadPoints(codex, ops, "missing")
        assertEquals(listOf("claude m1 /repo c1", "codex m1 /repo t1"), ops.calls)
        // The provider's own words when it can't list them.
        assertEquals(DuplicateSheetModel.Points.Failed("no transcript"), DuplicateSheetModel.loadPoints(claude, FakeOps(RewindPointsResult.Error("no transcript")), "missing"))
    }

    @Test fun duplicateCutsAfterThePoint() = runBlocking {
        val point = DuplicateSheetModel.Point("p1", "", 0.0)
        val ops = FakeOps()
        assertEquals("new", DuplicateSheetModel.duplicate(claude, point, ops, "missing", "generic"))
        assertEquals(ForkOptions(cutAfterUuid = "p1"), ops.options)
        DuplicateSheetModel.duplicate(codex, point, ops, "missing", "generic")
        assertEquals(ForkOptions(cutAfterItemId = "p1"), ops.options)
        val failed = assertThrows(DuplicateSheetModel.Failure::class.java) {
            runBlocking { DuplicateSheetModel.duplicate(claude, point, FakeOps(spawn = SpawnSessionResult.Error("boom")), "missing", "generic") }
        }
        assertEquals("boom", failed.message)
        val pending = assertThrows(DuplicateSheetModel.Failure::class.java) {
            runBlocking { DuplicateSheetModel.duplicate(claude, point, FakeOps(spawn = SpawnSessionResult.Pending("r1", 1000)), "missing", "generic") }
        }
        assertEquals("generic", pending.message)
    }
}
