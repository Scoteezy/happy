package com.slopus.happy.changelog

import com.slopus.happy.changelog.ChangelogMarkdown.Block
import com.slopus.happy.changelog.ChangelogMarkdown.Span
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// Ported from happy-ios Tests/HappyCoreTests/SettingsLogicTests.swift (ChangelogTests).

class ChangelogTest {
    @Test fun decodesHappyAppsFile() {
        val json = """{"entries":[{"title":"T","summary":"","markdown":"- a","titleImage":"x.webp"}],"latestTitle":"T"}"""
        val changelog = Changelog.parse(json)
        assertEquals(listOf(ChangelogEntry("T", "", "- a", "x.webp")), changelog.entries)
        assertEquals("T", changelog.latestTitle)
    }

    @Test fun blocksAndSpans() {
        val blocks = ChangelogMarkdown.parse("- **Bots** — make one.\n- Credits: [@f-liva](https://github.com/f-liva).\n\nA paragraph\ncontinues.")
        assertEquals(
            listOf(
                Block.Bullet(listOf(Span.Text("Bots", bold = true), Span.Text(" — make one.", bold = false))),
                Block.Bullet(listOf(Span.Text("Credits: ", bold = false), Span.Link("@f-liva", "https://github.com/f-liva"), Span.Text(".", bold = false))),
                Block.Paragraph(listOf(Span.Text("A paragraph continues.", bold = false))),
            ),
            blocks,
        )
    }

    @Test fun unclosedMarkersStayText() {
        assertEquals(listOf(Span.Text("2 ** 3 [x", bold = false)), ChangelogMarkdown.spans("2 ** 3 [x"))
    }

    /** res/raw/changelog.json is a copy of happy-app's; copy it over when this fails. */
    @Test fun theBundledCopyMatchesHappyApps() {
        val bundled = File("src/main/res/raw/changelog.json")
        val source = File("../../happy-app/sources/changelog/changelog.json")
        if (!source.exists()) return
        assertEquals(source.readText(), bundled.readText())
    }
}
