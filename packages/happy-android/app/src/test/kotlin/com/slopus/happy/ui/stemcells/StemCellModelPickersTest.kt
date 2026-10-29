package com.slopus.happy.ui.stemcells

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.stemcells.AgentMode
import com.slopus.happy.stemcells.StemCellAccountUsage
import com.slopus.happy.stemcells.StemCellModelCatalog
import com.slopus.happy.stemcells.StemCellModelMenus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The model control's lists as desktop's ComposerModelControl draws them,
 * and what each pick in them makes the mode (happy-ios `StemCellModelPickersTests`).
 */
@RunWith(AndroidJUnit4::class)
class StemCellModelPickersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val config = """
    {"config": {
        "defaults": {"providerId": "claude", "modelId": "anthropic/sonnet-5", "effort": "low", "permissionMode": "auto"},
        "models": {
            "openai/sol": {"name": "GPT Sol", "efforts": ["low", "medium", "high"], "defaultEffort": "medium", "serviceTiers": []},
            "anthropic/opus-5": {"name": "Opus 5 1M", "efforts": ["low", "medium", "high", "max"], "defaultEffort": "medium", "serviceTiers": []},
            "anthropic/sonnet-5": {"name": "Sonnet 5", "efforts": ["low", "medium", "high"], "defaultEffort": "high", "serviceTiers": []}
        },
        "providers": {
            "codex": {"type": "codex", "enabled": true, "models": [{"id": "openai/sol", "enabled": true}]},
            "claude": {"name": "Current CLI", "type": "claude", "enabled": true,
                "models": [{"id": "anthropic/opus-5", "enabled": true}, {"id": "anthropic/sonnet-5", "enabled": true}]},
            "claude-work": {"name": "Work", "type": "claude", "enabled": true, "models": [{"id": "anthropic/opus-5", "enabled": true}]}
        }
    }}
    """

    private val catalog = StemCellModelCatalog.from(DirectAgentClient.parse(config.toByteArray()))!!
    private val mode = AgentMode("claude", "anthropic/sonnet-5", "high", null, "auto")
    private val menus = StemCellModelMenus(catalog, mode)

    private fun options(lines: List<StemCellPickerContent.Line>) = lines.filterIsInstance<StemCellPickerContent.Line.Option>()

    /** Each service under its heading, which opens its accounts; a hairline between; the selected model checked and opened on; the benchmarks under the list. */
    @Test fun theModelListIsEachServiceUnderItsHeading() {
        val content = StemCellModelPickers.modelContent(context, menus, emptyMap())
        val headings = content.lines.filterIsInstance<StemCellPickerContent.Line.Heading>()
        assertEquals(listOf("Codex", "Claude"), headings.map { it.service })
        assertEquals(listOf("codex", "Current CLI"), headings.map { it.account })
        assertEquals("a heading opens its service's accounts", listOf("codex", "claude"), headings.map { it.opens })
        assertEquals(1, content.lines.count { it == StemCellPickerContent.Line.Separator })
        assertEquals(listOf("GPT Sol", "Opus 5 1M", "Sonnet 5"), options(content.lines).map { it.label })
        assertEquals(listOf("Sonnet 5"), options(content.lines).filter { it.checked }.map { it.label })
        assertEquals(content.lines.indexOfLast { it is StemCellPickerContent.Line.Option && it.label == "Sonnet 5" }, content.selected)
        assertEquals(listOf(StemCellPickerContent.Line.Separator, StemCellPickerContent.Line.Link("benchmarks", "Latest benchmarks")), content.footer)
        assertEquals(listOf("codex", "claude", "claude-work"), StemCellModelPickers.listedAccounts(menus))
    }

    /** The effort chip's own list: the model's efforts, the current one checked. */
    @Test fun theEffortListIsTheModelsOwn() {
        val content = StemCellModelPickers.effortContent(context, menus)
        assertEquals(listOf("Low", "Medium", "High"), options(content.lines).map { it.label })
        assertEquals(listOf("High"), options(content.lines).filter { it.checked }.map { it.label })
        assertEquals(2, content.selected)
    }

    /** A service's accounts, the one it lists checked, with what is left of its week; that account's windows under them. */
    @Test fun aServicesAccountsWithTheirUsage() {
        val now = 1_800_000_000_000L
        val usage = StemCellAccountUsage.project(
            DirectAgentClient.parse(
                """{"providers": [{"providerId": "claude-work", "refreshedAt": ${now - 120_000},
                "usage": {"windows": {"weekly": {"usedPercent": 37}}}}]}""".toByteArray(),
            ),
        )
        val claude = menus.services.first { it.id == "claude" }
        val content = StemCellModelPickers.accountsContent(context, claude, menus, usage, now)
        assertEquals(listOf("Current CLI", "Work"), options(content.lines).map { it.label })
        assertEquals(listOf("Current CLI"), options(content.lines).filter { it.checked }.map { it.label })
        assertEquals("63%", (content.lines[1] as StemCellPickerContent.Line.Option).gauge?.text)
        assertEquals(StemCellPickerContent.Line.Separator, content.lines[2])
        // The checked account has no reading: its usage is unknown.
        assertEquals(StemCellPickerContent.Line.Windows(listOf(StemCellPickerContent.Window("Usage unknown"))), content.lines[3])
        assertEquals(listOf("Week", "Updated 2 min ago"), StemCellModelPickers.details(context, usage["claude-work"], now).map { it.label })
    }

    @Test fun eachPickIsTheModeItNames() {
        val models = options(StemCellModelPickers.modelContent(context, menus, emptyMap()).lines)
        val opus = models.first { it.label == "Opus 5 1M" }
        val picked = StemCellModelPickers.pick(opus.key, menus, catalog)
        assertEquals("anthropic/opus-5", picked?.modelId)
        assertEquals("a new model starts on its own default effort", "medium", picked?.effort)
        val claude = menus.services.first { it.id == "claude" }
        val work = options(StemCellModelPickers.accountsContent(context, claude, menus, emptyMap()).lines).first { it.label == "Work" }
        assertEquals("claude-work", StemCellModelPickers.pick(work.key, menus, catalog)?.providerId)
        val low = options(StemCellModelPickers.effortContent(context, menus).lines)[0]
        assertEquals("low", StemCellModelPickers.pick(low.key, menus, catalog)?.effort)
        assertEquals("read_only", StemCellModelPickers.pick("permission\tread_only", menus, catalog)?.permissionMode)
        for (key in listOf("benchmarks", "model\tnot-a-pair", "permission\tanything", "nonsense")) {
            assertNull(key, StemCellModelPickers.pick(key, menus, catalog))
        }
    }

    @Test fun theNoticeComesBeforeAModelTheListsDontOffer() {
        val hidden = AgentMode("gone", "anthropic/sonnet-5", "high", null, "auto")
        val lines = StemCellModelPickers.modelContent(context, StemCellModelMenus(catalog, hidden), emptyMap()).lines
        assertTrue("the notice isn't first", lines[0] is StemCellPickerContent.Line.Notice)
        assertEquals(StemCellPickerContent.Line.Separator, lines[1])
        val heading = lines[2] as StemCellPickerContent.Line.Heading
        val option = lines[3] as StemCellPickerContent.Line.Option
        assertNull(heading.opens)
        assertEquals("Sonnet 5", option.label)
        assertTrue(option.checked && option.disabled)
    }

    @Test fun theChipsNameTheModelAndItsEffort() {
        assertEquals(StemCellModelPickers.Chips("Claude · Current CLI", "Sonnet 5", "High"), StemCellModelPickers.chips(context, menus))
        assertEquals("Models not configured", StemCellModelPickers.chips(context, null).model)
    }
}
