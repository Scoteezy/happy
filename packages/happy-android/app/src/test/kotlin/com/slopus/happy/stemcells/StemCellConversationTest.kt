package com.slopus.happy.stemcells

import com.slopus.happy.direct.DirectAgentClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `message.delta` applied exactly as happy-agent's API asks (happy-ios `StemCellConversationTests`). */
class StemCellConversationTest {
    private fun message(text: String) = AgentMessage("m", "agent", 0.0, listOf(Block.Reasoning("…"), Block.Text(text)))

    private fun text(message: AgentMessage?): String? = (message?.content?.get(1) as? Block.Text)?.text

    @Test fun anAppendAtTheEndGrowsTheBlock() {
        assertEquals("Hello", text(StemCellConversation.grow(message("Hel"), 1, 3, "lo")))
    }

    @Test fun aReplayChangesNothing() {
        assertEquals("Hello", text(StemCellConversation.grow(message("Hello"), 1, 3, "lo")))
    }

    @Test fun aPartlyAppliedAppendAddsOnlyWhatIsMissing() {
        assertEquals("Hello!", text(StemCellConversation.grow(message("Hell"), 1, 3, "lo!")))
    }

    @Test fun offsetsCountUTF16() {
        // "👋" is two UTF-16 units.
        assertEquals("👋 hi", text(StemCellConversation.grow(message("👋"), 1, 2, " hi")))
    }

    @Test fun aGapOrAConflictAsksForHistory() {
        assertNull(StemCellConversation.grow(message("He"), 1, 3, "lo"))
        assertNull(StemCellConversation.grow(message("Help"), 1, 3, "lo"))
        assertNull(StemCellConversation.grow(message("Hel"), 2, 0, "x"))
    }

    @Test fun anotherAgentsMessageIsACollaboratorsButNeverItsOwnOrItsParents() {
        val json = """{"id":"m","role":"agent","createdAt":1,"content":[{"type":"text","text":"Collaborator c1 finished working."}],"metadata":{"senderAgentId":"c1","senderAgentExplanation":"Finished: final answer"}}"""
        val message = AgentMessage.from(DirectAgentClient.parse(json.toByteArray()))
        assertEquals(MessageMetadata("c1", "Finished: final answer"), message.metadata)
        val sender = StemCellConversation.collaborator(message, "me", "boss")
        assertEquals("c1", sender?.agentId)
        assertEquals("Finished: final answer", sender?.explanation)
        // Driving itself, or the agent that manages it: dialogue, not a row.
        assertNull(StemCellConversation.collaborator(message, "c1", null))
        assertNull(StemCellConversation.collaborator(message, "me", "c1"))
        // Typed by the person: no sender at all.
        assertNull(StemCellConversation.collaborator(message("hi"), "me", null))
    }

    @Test fun theDaemonsAddressingLineIsTrimmed() {
        assertEquals("Done.", StemCellConversation.inboundText("Message from agent c1:\n\nDone.", "c1"))
        assertEquals("Message from agent c2: Done.", StemCellConversation.inboundText("Message from agent c2: Done.", "c1"))
    }

    @Test fun aMessageWithoutAPicturesBytesStillDecodes() {
        val json = """{"id":"m","role":"user","createdAt":1,"content":[{"type":"text","text":"hi"},{"type":"image","mimeType":"image/png"}],"metadata":{}}"""
        val decoded = AgentMessage.from(DirectAgentClient.parse(json.toByteArray()))
        assertEquals(listOf(Block.Text("hi"), Block.Unknown("image")), decoded.content)
    }
}

/** Soft breaks as remark reads them on desktop (happy-ios `StemCellMarkdownTests`). */
class StemCellMarkdownTest {
    private fun join(text: String) = StemCellMarkdown.joiningSoftBreaks(text)

    @Test fun aSoftBreakIsASpace() {
        assertEquals("Spiky-dreamed, it envies thorns, but syrup drips.", join("Spiky-dreamed,\nit envies thorns,\nbut syrup drips."))
    }

    @Test fun aBlankLineStartsAParagraph() {
        assertEquals("One two\n\nThree", join("One\ntwo\n\nThree"))
    }

    @Test fun blocksKeepTheirLines() {
        val text = "Intro\n- a\n- b\n1. c\n# Head\nAfter\n> quote\n| a | b |\n| - | - |\n---"
        assertEquals(text, join(text))
    }

    @Test fun codeIsLeftAlone() {
        assertEquals("Run\n```sh\nline one\nline two\n```\nthen this", join("Run\n```sh\nline one\nline two\n```\nthen\nthis"))
    }

    @Test fun aHardBreakKeepsItsLine() {
        assertEquals("End  \nNext\\\nLast", join("End  \nNext\\\nLast"))
    }

    @Test fun aListItemRunsOnLazily() {
        assertEquals("- first more\n- second", join("- first\n  more\n- second"))
    }
}

/** The working line says what desktop's says, in desktop's order (happy-ios `StemCellActivityTests`). */
class StemCellActivityTest {
    private fun project(
        working: Boolean = true, status: String = "working", wait: AgentWait? = null, retrying: AgentRetrying? = null,
        question: Boolean = false, subagents: Int = 0, produced: Boolean = true,
    ) = StemCellActivity.project(working, status, wait, retrying, question, subagents, produced, 1000.0)?.phase

    @Test fun theAgentsStatusNamesThePhase() {
        assertEquals(StemCellActivity.Phase.Thinking, project(status = "thinking"))
        assertEquals(StemCellActivity.Phase.PreparingTools, project(status = "generating_tools"))
        assertEquals(StemCellActivity.Phase.RunningTools, project(status = "running_tools"))
        assertEquals(StemCellActivity.Phase.Working, project(status = "working"))
        assertNull(project(working = false, status = "idle"))
    }

    @Test fun untilTheTurnShowsAnythingItIsWaitingForTheModel() {
        assertEquals(StemCellActivity.Phase.WaitingForModel, project(status = "idle", produced = false))
        assertEquals(StemCellActivity.Phase.WaitingForModel, project(status = "thinking", produced = false))
        // Tools mean the model has answered.
        assertEquals(StemCellActivity.Phase.RunningTools, project(status = "running_tools", produced = false))
        assertEquals(StemCellActivity.Phase.Thinking, project(status = "thinking", produced = true))
    }

    @Test fun aQuestionThenAWaitThenARetryOutrankTheStatus() {
        val wait = AgentWait(0.0, 60_000.0)
        val retry = AgentRetrying(2, 10, "rate_limited", "Claude")
        assertEquals(StemCellActivity.Phase.AwaitingAnswer, project(wait = wait, retrying = retry, question = true))
        assertEquals(StemCellActivity.Phase.Waiting(wait), project(wait = wait, retrying = retry))
        assertEquals(StemCellActivity.Phase.Retrying(retry), project(status = "thinking", retrying = retry, produced = false))
        // A question is shown after its run ends too, without a clock.
        val asked = StemCellActivity.project(false, "idle", null, null, true, 0, false, 5.0)
        assertEquals(StemCellActivity(StemCellActivity.Phase.AwaitingAnswer, null), asked)
    }

    @Test fun aFinishedTurnWithSubagentsStillRunningIsDelegating() {
        assertEquals(StemCellActivity.Phase.Delegating, project(working = false, status = "idle", subagents = 2))
        assertEquals(StemCellActivity.Phase.Working, project(working = true, status = "working", subagents = 2))
    }
}

/** What the phone keeps of a layout block in an agent's text (happy-ios `StemCellMediaTests`). */
class StemCellMediaPiecesTest {
    private fun image(path: String, index: Int = 0) = StemCellMedia(
        "t1", index, MediaItem("image", path, "image/png", 10.0, 100.0, 50.0, null, null, MediaItem.Source(path, "/w/$path")),
    )

    private fun text(text: String) = StemCellChatItem.Piece.Text(text)

    @Test fun aBlockShowsWhatItNamesOrStaysMarkdown() {
        val shown = mapOf("a.png" to image("a.png"), "/w/b.png" to image("b.png", index = 1))
        val source = "Look:\n\n<div><img src=\"a.png\"><img src=\"/w/b.png\"><img src=\"c.png\"></div>\n\nNice."
        val pieces = StemCellConversation.pieces(source, shown, streaming = false)
        assertEquals(3, pieces.size)
        assertEquals(text("Look:"), pieces[0])
        val tree = pieces[1] as StemCellChatItem.Piece.Tree
        assertEquals(listOf("a.png", "b.png"), tree.media.map { it.item.name })
        assertEquals(
            listOf(
                StemCellMediaTree.Node.Element(
                    "div", emptyMap(),
                    listOf(StemCellMediaTree.Node.Item(0, null, emptyMap()), StemCellMediaTree.Node.Item(1, null, emptyMap())),
                ),
            ),
            tree.tree.root,
        )
        // A block that lost content to a limit is the Markdown it is.
        val long = "<div><img src=\"a.png\">" + "<span>x</span>".repeat(600) + "</div>"
        assertEquals(listOf(text(long)), StemCellConversation.pieces(long, shown, streaming = false))
        assertEquals(text("Nice."), pieces[2])
        // Naming nothing shown: the block is the Markdown it is.
        assertEquals(listOf(text("<div><img src=\"x.png\"></div>")), StemCellConversation.pieces("<div><img src=\"x.png\"></div>", shown, streaming = false))
        // Still arriving: held back, never drawn as HTML.
        assertEquals(listOf(text("Here:")), StemCellConversation.pieces("Here:\n<div><img src=\"a.png\">", shown, streaming = true))
        // Final but never closed: Markdown.
        assertEquals(listOf(text("<div><img src=\"a.png\">")), StemCellConversation.pieces("<div><img src=\"a.png\">", shown, streaming = false))
    }

    @Test fun columnsFollowThePresentation() {
        fun layout(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else if (v is String) JsonPrimitive(v) else JsonArray(emptyList()) })
        assertEquals(0, StemCellConversation.columns(null, 1))
        assertEquals(0, StemCellConversation.columns(JsonNull, 3))
        assertEquals(1, StemCellConversation.columns(layout("type" to "columns", "columns" to 1), 3))
        assertEquals(2, StemCellConversation.columns(layout("type" to "columns", "columns" to 2), 1))
        assertEquals(0, StemCellConversation.columns(layout("type" to "tree", "root" to emptyList<Any>()), 4))
    }

    @Test fun aThumbhashCarriesItsShape() {
        // No hash, or one too short to have a header, has no shape.
        assertNull(StemCellMedia.hashedRatio(null))
        assertNull(StemCellMedia.hashedRatio("AAA"))
        assertTrue((StemCellMedia.hashedRatio("1QcSHQRnh493V4dIh4eXh1h4kJUI") ?: 0.0) > 0)
    }
}

class StemCellsAgentsTest {
    /** A list read again after a subtask was archived leaves it out from under its parent, as the live update did. */
    @Test fun anArchivedSubtaskIsNotUnderItsParent() {
        val agents = StemCellsAgents()
        val json = """{"id":"t","version":"1","subtasks":[{"id":"a","version":"1","archivedAt":null},{"id":"b","version":"2","archivedAt":1700000000000}]}"""
        agents.absorb(DirectAgentClient.parse(json.toByteArray()))
        assertEquals(listOf("a"), agents.children["t"])
    }
}

/** A phone doesn't draw Search and Create; drops are anchored on what it draws and the two never move (happy-ios `StemCellsDropTests`). */
class StemCellsDropTest {
    private val html = """
    <nav>
      <happy-search/>
      <happy-create/>
      <happy-space id="happy"/>
      <happy-space id="a"/>
      <happy-section id="travel" label="Travel"/>
      <happy-space id="b"/>
      <happy-space id="c"/>
    </nav>
    """.trimIndent()

    private fun rows(document: SpaceSidebarDocument) = document.items.map { node ->
        val kind = when (node.kind) {
            SpaceSidebarNode.Kind.Search -> StemCellsRow.Kind.Search
            SpaceSidebarNode.Kind.Create -> StemCellsRow.Kind.Create
            SpaceSidebarNode.Kind.Section -> StemCellsRow.Kind.Section
            SpaceSidebarNode.Kind.Separator -> StemCellsRow.Kind.Separator
            SpaceSidebarNode.Kind.Widget -> StemCellsRow.Kind.Widget
            SpaceSidebarNode.Kind.Space -> StemCellsRow.Kind.Space
        }
        StemCellsRow(id = node.id, nodeId = node.id, kind = kind, label = node.id, isHappy = node.id == "happy")
    }

    private fun order(document: SpaceSidebarDocument) = document.items.map { it.id }

    private fun drop(moving: String, before: String?, document: SpaceSidebarDocument): SpaceSidebarDocument? {
        val anchor = StemCellsDrop.after(moving, before, rows(document)) ?: return null
        return SidebarDocumentEdits.move(document, moving, anchor.after)
    }

    @Test fun aDropAtTheTopLandsAfterTheHiddenRows() {
        val document = SpaceSidebar.parse(html)
        assertEquals(listOf("happy-search", "happy-create", "happy", "a", "travel", "b", "c"), order(document))
        // Above the first drawn row (Happy): after Create, never before Search or Create.
        assertEquals(StemCellsDrop.Anchor("happy-create"), StemCellsDrop.after("c", "happy", rows(document)))
        val top = drop("c", "happy", document)!!
        assertEquals(listOf("happy-search", "happy-create", "c", "happy", "a", "travel", "b"), order(top))
    }

    @Test fun dropsBetweenAndAtTheEnd() {
        val document = SpaceSidebar.parse(html)
        assertEquals(listOf("happy-search", "happy-create", "happy", "c", "a", "travel", "b"), order(drop("c", "a", document)!!))
        assertEquals(listOf("happy-search", "happy-create", "happy", "travel", "b", "c", "a"), order(drop("a", null, document)!!))
    }

    @Test fun theHiddenRowsAndHappyNeverMove() {
        val rows = rows(SpaceSidebar.parse(html))
        assertNull(StemCellsDrop.after("happy-search", "c", rows))
        assertNull(StemCellsDrop.after("happy-create", null, rows))
        assertNull(StemCellsDrop.after("happy", null, rows))
        assertNull(StemCellsDrop.after("a", "nowhere", rows))
        // Nothing is set down against a row the phone doesn't draw.
        assertNull(StemCellsDrop.after("c", "happy-create", rows))
        assertEquals(listOf("happy-search", "happy-create"), rows.filter(StemCellsDrop::isHidden).map { it.nodeId })
    }
}
