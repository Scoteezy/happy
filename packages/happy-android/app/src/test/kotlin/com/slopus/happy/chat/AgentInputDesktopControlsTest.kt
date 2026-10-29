package com.slopus.happy.chat

import com.slopus.happy.data.SessionRecord
import com.slopus.happy.model.settings.ModeOption
import com.slopus.happy.sync.ComposerMirror
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from happy-ios Tests/HappyCoreTests/AgentInputDesktopControlsTests.swift.

/** AgentInput.tsx's `desktopActionControls` send button. */
class AgentInputDesktopSendTest {
    private fun send(fill: AgentInputDesktopSend.Fill, glyph: AgentInputDesktopSend.Glyph, enabled: Boolean) =
        AgentInputDesktopSend(fill, glyph, enabled)

    @Test fun testTextFillsTheButtonAndAnyContentEnablesIt() {
        assertEquals(
            send(AgentInputDesktopSend.Fill.Active, AgentInputDesktopSend.Glyph.Arrow, true),
            AgentInputDesktopSend(hasText = true, hasComposerContent = true, isSendBlocked = false),
        )
        assertEquals(
            send(AgentInputDesktopSend.Fill.Inactive, AgentInputDesktopSend.Glyph.Arrow, false),
            AgentInputDesktopSend(hasText = false, hasComposerContent = false, isSendBlocked = false),
        )
        // Pictures alone send, on the unfilled button.
        assertEquals(
            send(AgentInputDesktopSend.Fill.Inactive, AgentInputDesktopSend.Glyph.Arrow, true),
            AgentInputDesktopSend(hasText = false, hasComposerContent = true, isSendBlocked = false),
        )
    }

    @Test fun testABlockedSendIsLockedAndStillPressableWithContent() {
        // Pressing shakes the card (handleBlockedSendAttempt).
        assertEquals(
            send(AgentInputDesktopSend.Fill.Locked, AgentInputDesktopSend.Glyph.Lock, true),
            AgentInputDesktopSend(hasText = true, hasComposerContent = true, isSendBlocked = true),
        )
        assertEquals(
            send(AgentInputDesktopSend.Fill.Locked, AgentInputDesktopSend.Glyph.Lock, false),
            AgentInputDesktopSend(hasText = false, hasComposerContent = false, isSendBlocked = true),
        )
    }

    @Test fun testSendingSpinsOnTheFilledButton() {
        assertEquals(
            send(AgentInputDesktopSend.Fill.Active, AgentInputDesktopSend.Glyph.Spinner, false),
            AgentInputDesktopSend(hasText = false, hasComposerContent = false, isSendBlocked = false, isSending = true),
        )
        assertEquals(
            AgentInputDesktopSend.Glyph.Spinner,
            AgentInputDesktopSend(hasText = true, hasComposerContent = true, isSendBlocked = true, isSending = true).glyph,
        )
        assertFalse(AgentInputDesktopSend(hasText = true, hasComposerContent = true, isSendBlocked = false, isSendDisabled = true).isEnabled)
    }
}

/** The gear's settings menu: `permissionSettingsGroups` then `modelSettingsGroups`. */
class ComposerSettingsMenuTest {
    private val titles = ComposerChipSet.Titles(
        permission = "PERMISSION MODE", codexPermission = "CODEX PERMISSION MODE",
        geminiPermission = "GEMINI PERMISSION MODE", model = "MODEL", effort = "EFFORT",
    )
    private val translate: (Int) -> String = { "tr:$it" }

    private fun session(metadata: String) =
        ChatSessionInfo.from(SessionRecord("s", 0, 1, 1, true, 5, metadata, 1, null, 1, "", null))

    @Test fun testAClaudeSessionsGearHoldsEveryChoiceUnderItsHeading() {
        val session = session("""{ "path": "/repo", "host": "h", "flavor": "claude", "version": "1.2.6" }""")
        val modes = ComposerModeResolver.resolve(session.metadata, ComposerMirror(), null, translate)
        val chips = ComposerChipSet.of(modes, session, titles)
        assertTrue(chips.showsSettings)
        val kinds = ComposerChipSet.SettingsSection.Kind.entries
        assertEquals(kinds.toList(), chips.settings.map { it.kind })
        assertEquals(listOf("PERMISSION MODE", chips.model.sections!!.first().title, "EFFORT"), chips.settings.map { it.title })
        assertEquals(listOf("shield", "cube", "bolt"), chips.settings.map { it.systemImage })
        assertEquals(listOf(chips.permission?.selectedKey, chips.model.selectedKey, chips.effort?.selectedKey), chips.settings.map { it.selectedKey })
        // The chips' own rows.
        assertEquals(chips.permission?.sections?.first()?.options, chips.settings[0].options)
        assertEquals(chips.model.sections?.first()?.options, chips.settings[1].options)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), chips.settings[2].options.map { it.key })
    }

    @Test fun testACodexSessionsPermissionIsHeadedForCodex() {
        val session = session("""{ "path": "/repo", "host": "h", "flavor": "codex" }""")
        val modes = ComposerModeResolver.resolve(session.metadata, ComposerMirror(), null, translate)
        assertEquals("CODEX PERMISSION MODE", ComposerChipSet.of(modes, session, titles).settings.first().title)
    }

    @Test fun testTheGearShowsWhereverThePermissionCanBePicked() {
        // Nothing listed at all: the gear is there, its menu empty.
        val chips = ComposerChipSet.of(ComposerModes.None, session("""{ "path": "/repo", "host": "h", "flavor": "claude" }"""), titles)
        assertTrue(chips.showsSettings)
        assertEquals(emptyList<ComposerChipSet.SettingsSection>(), chips.settings)

        // A Happy Agent that locks the permission has no gear, even with models to pick (happy-app reads only a full capabilities object).
        val locked = session(
            """{ "path": "/repo", "host": "h", "flavor": "codex", "rigMetadataVersion": 1, "client": { "id": "rig", "name": "Rig", "version": "0.0.30" }, "capabilities": { "abort": true, "attachments": { "enabled": false, "maxBytes": 0, "mediaTypes": [] }, "files": { "browse": false, "read": false, "search": false, "write": false }, "modelSelection": true, "reasoningSelection": true, "permissionModeSelection": false, "resume": false, "rpcMethods": [], "shell": false, "steering": false } }""",
        )
        val models = ComposerModes(
            availableModes = listOf(ModeOption("auto", "Auto")),
            availableModels = listOf(ModeOption("codex:sol", "Sol")),
        )
        val lockedChips = ComposerChipSet.of(models, locked, titles)
        assertFalse(lockedChips.showsSettings)
        assertEquals("the menu, were it shown", listOf(ComposerChipSet.SettingsSection.Kind.Model), lockedChips.settings.map { it.kind })

        assertFalse("no session yet", ComposerChipSet.of(models, null, titles).showsSettings)
    }
}
