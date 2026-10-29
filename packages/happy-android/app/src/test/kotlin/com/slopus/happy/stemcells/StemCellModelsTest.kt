package com.slopus.happy.stemcells

import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectOfflineFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The model pickers' catalog, menus and usage, as desktop's
 * happyAgentModelCatalogProject, happyAgentMenusDerive and
 * happyAgentComposerModelControlProps derive them (happy-ios `StemCellModelsTests`).
 */
class StemCellModelsTest {
    /** Providers written out of alphabetical order, as a configuration may. */
    private val config = """
    {"config": {
        "defaults": {"providerId": "claude", "modelId": "anthropic/sonnet-5", "effort": "low", "permissionMode": "auto"},
        "models": {
            "openai/sol": {"name": "GPT Sol", "efforts": ["low", "medium", "high", "xhigh"], "defaultEffort": "medium", "serviceTiers": ["priority"]},
            "anthropic/opus-5": {"name": "Opus 5 1M", "efforts": ["off", "low", "medium", "high", "max"], "defaultEffort": "medium", "serviceTiers": []},
            "anthropic/sonnet-5": {"name": "Sonnet 5", "efforts": ["off", "low", "medium", "high"], "defaultEffort": "high", "serviceTiers": []},
            "xai/grok": {"name": "Grok", "efforts": ["medium"], "defaultEffort": "medium", "serviceTiers": []}
        },
        "providers": {
            "codex": {"type": "codex", "enabled": true, "models": [{"id": "openai/sol", "enabled": true}]},
            "claude": {"name": "Current CLI", "type": "claude", "enabled": true, "hidden": false,
                "models": [{"id": "anthropic/opus-5", "enabled": true}, {"id": "anthropic/sonnet-5", "enabled": true}]},
            "claude-work": {"name": "Work", "type": "claude", "enabled": true,
                "models": [{"id": "anthropic/opus-5", "enabled": true, "name": "Opus 5 (Work)"}, {"id": "anthropic/sonnet-5", "enabled": false}]},
            "claude-old": {"name": "Old", "type": "claude", "enabled": true, "hidden": true, "models": [{"id": "anthropic/sonnet-5", "enabled": true}]},
            "grok": {"type": "grok", "enabled": false, "models": [{"id": "xai/grok", "enabled": true}]}
        }
    }}
    """

    private val catalog get() = StemCellModelCatalog.from(DirectAgentClient.parse(config.toByteArray()))!!

    private fun mode(provider: String, model: String, effort: String = "medium") = AgentMode(provider, model, effort, null, "auto")

    @Test fun theCatalogKeepsTheDaemonsOrder() {
        val catalog = catalog
        assertEquals(listOf("codex", "claude", "claude-work", "claude-old", "grok"), catalog.providers.map { it.id })
        assertEquals(listOf("openai/sol", "anthropic/opus-5", "anthropic/sonnet-5", "xai/grok"), catalog.models.map { it.id })
        // Only the models an account switched on; a reference may rename one.
        assertEquals(listOf("Opus 5 (Work)"), catalog.provider("claude-work")?.models?.map { it.name })
        assertTrue(catalog.provider("grok")!!.isDisabled)
    }

    @Test fun theDefaultIsTheDeclaredModelAtItsOwnEffort() {
        // Not the configuration's "low": the model's own default, as desktop picks it.
        assertEquals(mode("claude", "anthropic/sonnet-5", "high"), catalog.defaultMode())
        assertEquals(mode("codex", "openai/sol", "medium"), catalog.copy(defaultModelId = "anthropic/gone").defaultMode())
        // A declared account that is switched off is passed over too.
        assertEquals(mode("codex", "openai/sol", "medium"), catalog.copy(defaultProviderId = "grok", defaultModelId = "xai/grok").defaultMode())
    }

    @Test fun pickingAModelTakesItsDefaultEffortAndDropsASpeedItLacks() {
        val catalog = catalog
        val current = mode("codex", "openai/sol", "xhigh").copy(serviceTier = "priority")
        assertEquals(mode("claude", "anthropic/opus-5", "medium"), catalog.select("claude", "anthropic/opus-5", from = current))
        // An effort the model has is kept; one it lacks falls to its default.
        assertEquals("max", catalog.select("claude", "anthropic/opus-5", "max", current).effort)
        assertEquals("high", catalog.select("claude", "anthropic/sonnet-5", "max", current).effort)
        assertEquals("priority", catalog.select("codex", "openai/sol", from = current).serviceTier)
        // No account named: the first that offers it.
        assertEquals("claude", catalog.select(null, "anthropic/sonnet-5", from = current).providerId)
    }

    @Test fun servicesGroupAccountsAndListTheSelectedOnesModels() {
        val menus = StemCellModelMenus(catalog, mode("claude-work", "anthropic/opus-5"))
        assertEquals(listOf("Codex", "Claude", "Grok"), menus.services.map { it.label })
        val claude = menus.services[1]
        // The hidden account is not offered.
        assertEquals(listOf("Current CLI", "Work"), claude.accounts.map { it.label })
        assertEquals("claude-work", claude.account)
        assertEquals(listOf("Opus 5 (Work)"), claude.shown?.models?.map { it.label })
        // A service without the selection lists its first account; a switched-off one greys its models.
        assertEquals("codex", menus.services[0].account)
        assertEquals(listOf(true), menus.services[2].shown?.models?.map { it.disabled })
        assertNull(menus.notice)
        assertEquals("Opus 5 (Work)", menus.current?.label)
        assertEquals("medium", menus.effort)
        // Another account keeps the model when it has it, else its first.
        assertEquals("anthropic/opus-5", menus.model(claude.accounts[0])?.id)
        assertEquals("openai/sol", menus.model(menus.services[0].accounts[0])?.id)
    }

    @Test fun aSelectionOutsideTheListsSaysWhy() {
        val hidden = StemCellModelMenus(catalog, mode("claude-old", "anthropic/sonnet-5", "low"))
        assertEquals(StemCellModelMenus.Notice.Hidden, hidden.notice)
        assertEquals("Sonnet 5", hidden.current?.label)
        assertEquals("low", hidden.effort)
        assertEquals(StemCellModelMenus.Notice.Off("grok"), StemCellModelMenus(catalog, mode("grok", "xai/grok")).notice)
        val gone = StemCellModelMenus(catalog, mode("bedrock", "anthropic/opus-5", "ultra"))
        assertEquals(StemCellModelMenus.Notice.Removed("bedrock"), gone.notice)
        assertEquals(true, gone.current?.disabled)
        // An effort the model doesn't have shows its default.
        assertEquals("medium", gone.effort)
        // An account that stopped listing the model still names it, greyed.
        val dropped = StemCellModelMenus(catalog, mode("claude-work", "anthropic/sonnet-5"))
        assertEquals("Sonnet 5", dropped.current?.label)
        assertEquals(true, dropped.current?.disabled)
        assertNull(dropped.notice)
    }

    @Test fun names() {
        assertEquals("Open Router Pro", StemCellModelMenus.serviceName("open_router-pro"))
        assertEquals("Extra High", StemCellModelMenus.effortName("xhigh"))
        assertEquals("Maximum", StemCellModelMenus.effortName("max"))
    }

    @Test fun usageProjectsWeekSessionAndLimit() {
        val response = DirectAgentClient.parse(
            """
            {"providers": [
                {"providerId": "claude", "usage": {"planName": "Max", "exhausted": false, "capturedAt": 1000,
                    "windows": {"fiveHour": {"usedPercent": 32, "resetsAt": 5000}, "weekly": {"usedPercent": 79.6, "resetsAt": 9000, "capturedAt": 900}, "monthly": null}},
                 "refreshedAt": 1000, "refreshError": null, "refreshing": false},
                {"providerId": "codex", "usage": null, "error": "The provider did not report account usage.", "refreshing": false},
                {"providerId": "bedrock", "usage": null, "error": null, "refreshing": false},
                {"providerId": "grok", "usage": {"exhausted": true, "windows": {"fiveHour": {"usedPercent": 100, "resetsAt": 7000}, "weekly": {"usedPercent": 60, "resetsAt": 8000}}},
                 "refreshing": true}
            ]}
            """.toByteArray(),
        )
        val usage = StemCellAccountUsage.project(response)
        assertEquals(setOf("claude", "codex", "grok"), usage.keys)
        val claude = usage["claude"]!!
        assertEquals(20, claude.week?.left)
        assertEquals(68, claude.session?.left)
        assertEquals(StemCellAccountUsage.Window.Kind.Week, claude.shown?.kind)
        assertEquals(1000.0, claude.updatedAt)
        assertFalse(claude.limited)
        assertEquals("The provider did not report account usage.", usage["codex"]?.refreshError)
        assertNull(usage["codex"]?.shown)
        val grok = usage["grok"]!!
        assertTrue(grok.limited)
        assertEquals(true, grok.session?.blocking)
        assertEquals(false, grok.week?.blocking)
        assertEquals(7000.0, grok.limitResets)
        assertTrue(grok.refreshing)
        assertEquals(StemCellAccountUsage.Tone.Low, StemCellAccountUsage.tone(20, false))
        assertEquals(StemCellAccountUsage.Tone.Critical, StemCellAccountUsage.tone(9, false))
        assertEquals(StemCellAccountUsage.Tone.Critical, StemCellAccountUsage.tone(80, true))
        assertEquals(StemCellAccountUsage.Tone.Ample, StemCellAccountUsage.tone(80, false))
    }
}

/** What a computer last showed is kept, merged, read back, and forgotten (happy-ios `StemCellsOfflineTests`). */
class StemCellsOfflineTest {
    private val directory: File = File(Files.createTempDirectory("stem-offline").toFile(), "computer")

    @After fun tearDown() {
        directory.parentFile.deleteRecursively()
    }

    @Test fun changesMergeAndAFreshReaderSeesThem() {
        val file = StemCellsOfflineFile(DirectOfflineFile(directory))
        val mode = AgentMode("claude", "anthropic/sonnet-5", "low", null, "auto")
        file.update { it.copy(config = """{"config":{}}""") }
        file.update { it.copy(dockMode = mode) }
        file.update {
            it.copy(
                sidebar = StemCellsOfflineSnapshot.Sidebar(
                    artifactId = "a", version = 3, html = "<nav/>", spaces = emptyList(), agentIcons = emptyMap(), bots = emptyList(), tasks = emptyList(),
                    surfaces = listOf(Surface("s", "todo", "Groceries")), capabilities = null, prioritizing = true,
                ),
            )
        }
        file.flush()
        val kept = StemCellsOfflineFile(DirectOfflineFile(directory)).read()
        assertEquals("""{"config":{}}""", kept?.config)
        assertEquals(mode, kept?.dockMode)
        assertEquals(3, kept?.sidebar?.version)
        assertEquals("Groceries", kept?.sidebar?.surfaces?.firstOrNull()?.name)
        assertEquals(true, kept?.sidebar?.prioritizing)
    }

    @Test fun aCopyOfAnotherFormatIsNotRead() {
        directory.mkdirs()
        val offline = DirectOfflineFile(directory)
        offline.snapshotFile.writeText("""{"format":0}""")
        assertNull(StemCellsOfflineFile(offline).read())
    }

    @Test fun picturesAreKeptByPathAndHash() {
        val file = DirectOfflineFile(directory)
        file.keepPicture(byteArrayOf(1, 2, 3), "/v0/bots/b1/avatar", "KZcG/N+=")
        assertArrayEquals(byteArrayOf(1, 2, 3), file.picture("/v0/bots/b1/avatar", "KZcG/N+="))
        // A new picture has a new hash: the old bytes are not it, and
        // keeping the new one drops the old.
        assertNull(file.picture("/v0/bots/b1/avatar", "other"))
        file.keepPicture(byteArrayOf(4), "/v0/bots/b1/avatar", "other")
        assertNull(file.picture("/v0/bots/b1/avatar", "KZcG/N+="))
        assertArrayEquals(byteArrayOf(4), file.picture("/v0/bots/b1/avatar", "other"))
        val folder = file.pictureFile("/v0/bots/b1/avatar", "other").parentFile
        assertEquals(1, folder.list()?.size)
        assertEquals(32, file.pictureFile("/v0/b", "x/").name.length)
    }

    @Test fun aRemovedFolderStaysGoneWhateverWasOnItsWay() {
        val file = StemCellsOfflineFile(DirectOfflineFile(directory))
        file.update { it.copy(config = "a") }
        file.flush()
        assertTrue(directory.exists())
        DirectOfflineFile.remove(directory)
        assertFalse(directory.exists())
        // Late writes from the file that was open on it.
        file.update { it.copy(config = "b") }
        file.flush()
        file.file.keepPicture(byteArrayOf(1), "/p", null)
        assertFalse(directory.exists())
        // A computer paired again opens the folder afresh.
        val again = StemCellsOfflineFile(DirectOfflineFile(directory))
        again.update { it.copy(config = "c") }
        again.flush()
        assertEquals("c", StemCellsOfflineFile(DirectOfflineFile(directory)).read()?.config)
    }
}
