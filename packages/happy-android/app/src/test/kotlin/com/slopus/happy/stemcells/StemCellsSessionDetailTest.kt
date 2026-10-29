package com.slopus.happy.stemcells

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slopus.happy.config.AppVariant
import com.slopus.happy.direct.DirectAgentClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.util.UUID

/** happy-ios `StemCellsSessionDetailTests`. */
class StemCellsSessionDetailTest {
    /** The bootstrap as the daemon sends it: usage by provider then model, its children newest first, its processes. */
    @Test fun theDetailCountsTokensOverEveryModelAndSplitsTheActivity() {
        val json = """
        {"agent":{"id":"a","title":"Plan","status":"idle","createdAt":1000,"subagents":{"total":3,"running":1},"workspaceId":"w"},
         "mode":{"providerId":"claude","modelId":"anthropic/sonnet-5","effort":"medium","permissionMode":"auto"},
         "context":{"approximate":false,"contextTokens":10},
         "usage":{"claude":{"anthropic/sonnet-5":{"input":1200,"output":300,"cacheRead":9000}},"codex":{"openai/gpt":{"input":500,"output":0}}},
         "processes":[{"id":"p1","agentId":"a","command":"pnpm dev","status":"running","startedAt":5},{"id":"p2","agentId":"a","command":"ls","status":"exited","exitCode":0}],
         "subagents":[{"id":"c1","title":"Research","status":"running_tools","totalTokens":42,"archivedAt":null},
                      {"id":"c2","title":null,"status":"idle","totalTokens":null},
                      {"id":"c3","title":"Old","status":"idle","archivedAt":7,"totalTokens":9}],
         "pending":[],"slashCommands":[],"cursor":"x"}
        """
        val detail = StemCellsSessionDetail.of(AgentDetail.from(DirectAgentClient.parse(json.toByteArray())))
        assertEquals("input and output, never the cache", 2000, detail.tokens)
        assertEquals("Plan", detail.title)
        assertFalse(detail.working)
        assertFalse(detail.subtask)
        assertEquals(1000.0, detail.createdAt)
        assertEquals(1, detail.subagentsRunning)
        assertEquals(3, detail.subagentsTotal)
        assertEquals("anthropic/sonnet-5", detail.mode?.modelId)
        assertEquals(listOf("c1"), detail.running.map { it.id })
        assertEquals(listOf("c2", "c3"), detail.completed.map { it.id })
        assertEquals(listOf(false, true), detail.completed.map { it.archived })
        assertEquals("running ones only", listOf("pnpm dev"), detail.terminals.map { it.command })
        assertEquals(5.0, detail.terminals.first().startedAt)
        assertFalse(detail.isQuiet)
    }

    @Test fun noUsageIsNoCountAndNothingAtWorkIsQuiet() {
        val detail = StemCellsSessionDetail.of(AgentDetail(DetailAgent("a")))
        assertNull(detail.tokens)
        assertTrue(detail.isQuiet)
        assertEquals(0, detail.subagentsTotal)
    }

    @Test fun tokensAreThreeFiguresAtMostAndNeverRoundedUp() {
        assertEquals("0", StemCellsDetailFormat.tokens(0))
        assertEquals("980", StemCellsDetailFormat.tokens(980))
        assertEquals("1K", StemCellsDetailFormat.tokens(1_000))
        assertEquals("1.2K", StemCellsDetailFormat.tokens(1_299))
        assertEquals("12K", StemCellsDetailFormat.tokens(12_999))
        assertEquals("120M", StemCellsDetailFormat.tokens(120_996_446))
        assertEquals("1.2B", StemCellsDetailFormat.tokens(1_250_000_000))
    }

    @Test fun ageIsTheLargestWholeUnit() {
        val minute = 60_000.0
        assertEquals(StemCellsDetailFormat.Age.JustNow, StemCellsDetailFormat.age(0.0, 30_000.0))
        assertEquals(StemCellsDetailFormat.Age.Minutes(5), StemCellsDetailFormat.age(0.0, 5 * minute))
        assertEquals(StemCellsDetailFormat.Age.Hours(2), StemCellsDetailFormat.age(0.0, 125 * minute))
        assertEquals(StemCellsDetailFormat.Age.Days(3), StemCellsDetailFormat.age(0.0, 3 * 1440 * minute))
        assertEquals(StemCellsDetailFormat.Age.Months(2), StemCellsDetailFormat.age(0.0, 65 * 1440 * minute))
        assertEquals(StemCellsDetailFormat.Age.Years(2), StemCellsDetailFormat.age(0.0, 800 * 1440 * minute))
    }

    // Family

    private fun space(id: String, depth: Int = 0, collapsed: Boolean? = null) =
        StemCellsRow(id = id, nodeId = id, kind = StemCellsRow.Kind.Space, depth = depth, label = id, collapsed = collapsed, agentId = id, spaceId = id, targetKind = "task")

    private fun session(id: String, depth: Int) = StemCellsRow(id = id, nodeId = id, kind = StemCellsRow.Kind.Session, depth = depth, label = id, agentId = id)

    private fun section(id: String) = StemCellsRow(id = id, nodeId = id, kind = StemCellsRow.Kind.Section, label = id, collapsed = true)

    /** A nested conversation: its parent row, its siblings a level in, and its own children a level further; nothing of the next tree. */
    @Test fun aNestedConversationShowsItsParentItsSiblingsAndItsChildren() {
        val rows = listOf(
            space("bot", collapsed = false), session("a", 1), session("me", 1),
            session("kid", 2), session("grandkid", 3), session("b", 1),
            space("next"), session("other", 1),
        )
        val family = StemCellsFamily.of("me", rows)
        assertEquals(listOf("bot", "a", "me", "kid", "b"), family.map { it.id })
        assertEquals(listOf(0, 1, 1, 2, 1), family.map { it.depth })
        assertEquals("nothing folds here", listOf<Boolean?>(null, null, null, null, null), family.map { it.collapsed })
    }

    /** At the top level, filed under a section or not: itself over its children, no section and no siblings. */
    @Test fun aTopLevelConversationShowsOnlyItsChildren() {
        val rows = listOf(space("loose"), section("Work"), space("x"), space("me"), session("kid", 1), session("grandkid", 2), space("y"), section("Home"), space("z"))
        val family = StemCellsFamily.of("me", rows)
        assertEquals(listOf("me", "kid"), family.map { it.id })
        assertEquals(listOf(0, 1), family.map { it.depth })
        assertEquals("alone, with no children", listOf("loose"), StemCellsFamily.of("loose", rows).map { it.id })
    }

    /** A surface's row opens no conversation: it isn't family. */
    @Test fun surfacesAreNotFamily() {
        val doc = StemCellsRow(id = "doc", nodeId = "doc", kind = StemCellsRow.Kind.Session, depth = 1, label = "doc", spaceId = "doc", targetKind = "surface")
        assertEquals(listOf("bot", "a", "me"), StemCellsFamily.of("me", listOf(space("bot"), session("a", 1), doc, session("me", 1))).map { it.id })
    }

    /** Nested among many siblings: the two either side, five in all even at an end. */
    @Test fun manySiblingsAreTheNearestFive() {
        val rows = listOf(space("bot")) + (0 until 12).map { session("s$it", 1) }
        assertEquals(listOf("bot", "s4", "s5", "s6", "s7", "s8"), StemCellsFamily.of("s6", rows).map { it.id })
        assertEquals(listOf("bot", "s0", "s1", "s2", "s3", "s4"), StemCellsFamily.of("s0", rows).map { it.id })
        assertEquals(listOf("bot", "s7", "s8", "s9", "s10", "s11"), StemCellsFamily.of("s11", rows).map { it.id })
        assertEquals(emptyList<StemCellsRow>(), StemCellsFamily.of("missing", rows))
    }

    @Test fun startedReadsAsNearAsAPersonSaysIt() {
        val utc = ZoneId.of("UTC")
        val now = 1_793_000_000_000L // Mon 2026-10-26 07:33 UTC
        fun at(hoursAgo: Double) = (now - hoursAgo * 3_600_000).toLong()
        fun started(hoursAgo: Double) = StemCellsDetailFormat.started(at(hoursAgo).toDouble(), now, utc)
        assertEquals(StemCellsDetailFormat.Started.Today(at(2.0)), started(2.0))
        assertEquals(StemCellsDetailFormat.Started.Yesterday(at(20.0)), started(20.0))
        assertEquals(StemCellsDetailFormat.Started.Weekday(at(24.0 * 4)), started(24.0 * 4))
        assertEquals(StemCellsDetailFormat.Started.ThisYear(at(24.0 * 40)), started(24.0 * 40))
        assertEquals(StemCellsDetailFormat.Started.Date(at(24.0 * 400)), started(24.0 * 400))
    }
}

/** Stem Cells is a preview feature: no production build can show it (happy-ios `StemCellsPreferenceTests`). */
@RunWith(RobolectricTestRunner::class)
class StemCellsPreferenceTest {
    private fun storage() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("stem-cells-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    @Test fun productionNeverShowsStemCells() {
        val storage = storage()
        // Even a stored choice from a preview install on the same phone.
        storage.edit().putString(StemCellsPreference.COMPUTER_KEY, "direct-1").putBoolean(StemCellsPreference.DIRECT_KEY, true).commit()
        val production = StemCellsPreference(AppVariant.Production, storage)
        assertFalse(production.isAvailable)
        assertNull(production.computerId)
        assertFalse(production.directConnection)
        // Production keeps its computer page.
        assertTrue(production.showsComputers)
    }

    @Test fun previewAndDevelopmentOfferIt() {
        for (variant in listOf(AppVariant.Preview, AppVariant.Development)) {
            val preference = StemCellsPreference(variant, storage())
            assertTrue(preference.isAvailable)
            preference.computerId = "direct-2"
            assertEquals("direct-2", preference.computerId)
        }
    }

    /** Direct connection is an experiment, off until turned on. */
    @Test fun directConnectionIsOffUntilTurnedOn() {
        for (variant in listOf(AppVariant.Preview, AppVariant.Development)) {
            val preference = StemCellsPreference(variant, storage())
            assertFalse(preference.directConnection)
            assertFalse(preference.showsComputers)
            preference.directConnection = true
            assertTrue(preference.directConnection)
            assertTrue(preference.showsComputers)
        }
    }
}
