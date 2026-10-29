package com.slopus.happy.chat

import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.model.details.DuplicateSheetModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.serialization.json.JsonObject

/** EmptyMessages.tsx's details: the machine's glyph and name, the folder from home, the age. */
class ChatEmptyStateTest {
    @Test fun theGlyphFollowsTheOs() {
        assertEquals("laptop-outline", ChatEmptyState.osIcon("darwin"))
        assertEquals("laptop-outline", ChatEmptyState.osIcon("MacOS"))
        assertEquals("desktop-outline", ChatEmptyState.osIcon("win32"))
        assertEquals("terminal-outline", ChatEmptyState.osIcon("linux"))
        assertEquals("hardware-chip-outline", ChatEmptyState.osIcon("freebsd"))
        assertEquals("hardware-chip-outline", ChatEmptyState.osIcon(null))
        assertEquals("hardware-chip-outline", ChatEmptyState.osIcon(""))
    }

    @Test fun theFolderIsShownFromHomeAndBlanksAreLeftOut() {
        val metadata = SessionMetadata(raw = JsonObject(emptyMap()), path = "/Users/ana/code/happy", host = "ana-mac", os = "darwin", homeDir = "/Users/ana")
        val state = ChatEmptyState.of(metadata, createdAt = 0, now = 5 * 60_000)
        assertEquals("ana-mac", state.host)
        assertEquals("~/code/happy", state.path)
        assertEquals(DuplicateSheetModel.Age.Minutes(5), state.created)

        val blank = ChatEmptyState.of(SessionMetadata(raw = JsonObject(emptyMap()), path = "", host = ""), createdAt = 0, now = 0)
        assertNull(blank.host)
        assertNull(blank.path)
        assertEquals(DuplicateSheetModel.Age.JustNow, blank.created)
    }
}
