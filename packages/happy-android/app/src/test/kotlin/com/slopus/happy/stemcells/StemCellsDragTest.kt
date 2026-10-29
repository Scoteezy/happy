package com.slopus.happy.stemcells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Carrying rows through the sidebar, as desktop's reorder does it: peers,
 * sections with their blocks, blocked slots, and where each drop lands
 * (happy-ios `StemCellsDragTests`).
 */
class StemCellsDragTest {
    private val html = """
    <nav>
      <happy-search/>
      <happy-create/>
      <happy-space id="happy"/>
      <happy-space id="a" expanded="true"><happy-space id="a1"/></happy-space>
      <happy-section id="work" label="Work"/>
      <happy-space id="b"/>
      <happy-space id="c"/>
      <happy-separator id="sep"/>
      <happy-space id="d"/>
      <happy-section id="old" label="Old" collapsed="true"/>
      <happy-space id="e"/>
    </nav>
    """.trimIndent()

    private fun rows(document: SpaceSidebarDocument): List<StemCellsRow> {
        fun row(node: SpaceSidebarNode, depth: Int): List<StemCellsRow> {
            val kind = when (node.kind) {
                SpaceSidebarNode.Kind.Search -> StemCellsRow.Kind.Search
                SpaceSidebarNode.Kind.Create -> StemCellsRow.Kind.Create
                SpaceSidebarNode.Kind.Section -> StemCellsRow.Kind.Section
                SpaceSidebarNode.Kind.Separator -> StemCellsRow.Kind.Separator
                SpaceSidebarNode.Kind.Widget -> StemCellsRow.Kind.Widget
                SpaceSidebarNode.Kind.Space -> StemCellsRow.Kind.Space
            }
            val own = StemCellsRow(
                id = node.id, nodeId = node.id, kind = kind, depth = depth, label = node.id,
                collapsed = if (node.kind == SpaceSidebarNode.Kind.Section) node.collapsed else null, isHappy = node.id == "happy",
            )
            return listOf(own) + node.children.flatMap { row(it, depth + 1) }
        }
        return document.items.flatMap { row(it, 0) }
    }

    private fun drawn(all: List<StemCellsRow>) = StemCellsRows.visible(all).filter { !StemCellsDrop.isHidden(it) }

    private fun setUp(id: String): Pair<StemCellsDrag, SpaceSidebarDocument> {
        val document = SpaceSidebar.parse(html)
        val all = rows(document)
        return StemCellsDrag.holding(id, drawn(all), all, document)!! to document
    }

    private fun drop(drag: StemCellsDrag, to: Int, document: SpaceSidebarDocument): List<String> {
        val after = drag.after(to) ?: return document.items.map { it.id }
        return SidebarDocumentEdits.move(document, drag.moving, after.after)!!.items.map { it.id }
    }

    @Test fun aSurfaceRowWearsItsKindsGlyph() {
        assertEquals("tasks", StemCellsRows.surfaceGlyph("todo"))
        assertEquals("doc", StemCellsRows.surfaceGlyph("document"))
        assertEquals("calendar", StemCellsRows.surfaceGlyph("calendar"))
        assertEquals("pulse", StemCellsRows.surfaceGlyph("system_monitor"))
        assertEquals("users", StemCellsRows.surfaceGlyph("chat"))
        assertEquals("doc", StemCellsRows.surfaceGlyph(null))
    }

    @Test fun aRowsPeersAreTheTopLevelRowsWithTheirNestedOnes() {
        val (drag, document) = setUp("c")
        // happy, a (+a1), work, b, c, sep, d, old: the folded section's run is not drawn.
        assertEquals(listOf(1, 2, 1, 1, 1, 1, 1, 1), drag.units.map { it.size })
        assertEquals(4, drag.from)
        assertEquals(listOf("c"), drag.held.map { it.nodeId })
        // To the very top: right after Create, before Happy.
        assertEquals(listOf("happy-search", "happy-create", "c", "happy", "a", "work", "b", "sep", "d", "old", "e"), drop(drag, 0, document))
        // Between a's block and Work: after a, its nested row with it.
        assertEquals(listOf("happy-search", "happy-create", "happy", "a", "c", "work", "b", "sep", "d", "old", "e"), drop(drag, 2, document))
        // Its own slot changes nothing.
        assertNull(drag.after(4))
    }

    @Test fun aNestedRowComesAlong() {
        val (drag, document) = setUp("a")
        assertEquals(listOf("a", "a1"), drag.held.map { it.nodeId })
        assertEquals(listOf("happy-search", "happy-create", "happy", "work", "b", "c", "a", "sep", "d", "old", "e"), drop(drag, 4, document))
    }

    @Test fun aSectionCarriesItsBlockAmongBlocks() {
        val (drag, document) = setUp("work")
        // [happy, a, a1], [work, b, c], [sep], [d], [old].
        assertEquals(listOf(3, 3, 1, 1, 1), drag.units.map { it.size })
        assertEquals(listOf("work", "b", "c"), drag.held.map { it.nodeId })
        assertEquals(listOf("happy-search", "happy-create", "work", "b", "c", "happy", "a", "sep", "d", "old", "e"), drop(drag, 0, document))
        assertEquals(listOf("happy-search", "happy-create", "happy", "a", "sep", "d", "work", "b", "c", "old", "e"), drop(drag, 3, document))
        assertEquals(listOf("happy", "a", "a1", "sep", "d", "old", "work", "b", "c"), drag.order(4).map { it.nodeId })
    }

    @Test fun theSeparatorMoves() {
        val (drag, document) = setUp("sep")
        assertEquals(listOf("happy-search", "happy-create", "happy", "a", "sep", "work", "b", "c", "d", "old", "e"), drop(drag, 2, document))
    }

    @Test fun aSlotThatWouldHideARowIsPassedOver() {
        val (drag, _) = setUp("sep")
        // Only a landing that hides a shown row is blocked.
        assertFalse(true in drag.blocked)
        val (row, _) = setUp("d")
        // d set down after the folded section would vanish into it.
        assertEquals(List(8) { it == 7 }, row.blocked)
        // The hand over that slot stops short of it.
        assertEquals(6, row.target(1000.0, List(8) { 34.0 }))
    }

    @Test fun theTargetIsTheSlotWhoseNeighboursTheHandPassedByHalf() {
        val (drag, _) = setUp("c")
        val heights = listOf(34.0, 68.0, 30.0, 34.0, 34.0, 19.0, 34.0, 30.0)
        assertEquals(4, drag.target(0.0, heights))
        assertEquals(4, drag.target(9.0, heights))
        assertEquals(5, drag.target(10.0, heights))
        assertEquals(4, drag.target(-17.0, heights))
        assertEquals(3, drag.target(-18.0, heights))
        assertEquals(0, drag.target(-1000.0, heights))
    }

    @Test fun aFoldedSectionIsNotSetDownAboveRowsItWouldFold() {
        val (drag, document) = setUp("old")
        // Above Happy or d it would fold them away; before Work or the separator its run is empty.
        assertEquals(listOf(true, false, false, true, false), drag.blocked)
        assertEquals(1, drag.target(-1000.0, listOf(68.0, 102.0, 19.0, 34.0, 30.0)))
        assertEquals(listOf("happy-search", "happy-create", "happy", "a", "old", "e", "work", "b", "c", "sep", "d"), drop(drag, 1, document))
    }

    @Test fun aNestedRowMovesAmongItsFamily() {
        val html = """
        <nav>
          <happy-search/>
          <happy-create/>
          <happy-space id="happy"/>
          <happy-space id="t" expanded="true">
            <happy-space id="s1"/>
            <happy-space id="s2" expanded="true"><happy-space id="s2a"/></happy-space>
            <happy-space id="s3"/>
          </happy-space>
          <happy-space id="u"/>
        </nav>
        """.trimIndent()
        val document = SpaceSidebar.parse(html)
        val all = rows(document)
        val drawn = drawn(all)
        val drag = StemCellsDrag.holding("s1", drawn, all, document)!!
        assertEquals("t", drag.parent)
        // s1, [s2, s2a], s3: its siblings with what is under them.
        assertEquals(listOf(listOf("s1"), listOf("s2", "s2a"), listOf("s3")), drag.units.map { unit -> unit.map { drawn[it].nodeId } })
        assertEquals(listOf(false, false, false), drag.blocked)
        // The rows around the family stay; only the family reorders.
        assertEquals(listOf("happy", "t", "s2", "s2a", "s3", "s1", "u"), drag.order(2).map { it.nodeId })
        val last = drag.after(2)!!
        assertEquals("s3", last.after)
        val moved = SidebarDocumentEdits.move(document, "s1", last.after)!!
        assertEquals(listOf("s2", "s3", "s1"), moved.items[3].children.map { it.id })
        // First under the parent, carrying what is nested under it.
        val middle = StemCellsDrag.holding("s2", drawn, all, document)!!
        assertEquals(listOf("s2", "s2a"), middle.held.map { it.nodeId })
        val first = middle.after(0)
        assertNotNull(first)
        assertNull(first!!.after)
        val top = SidebarDocumentEdits.move(document, "s2", first.after)!!
        assertEquals(listOf("s2", "s1", "s3"), top.items[3].children.map { it.id })
        // The daemon gets one move inside the family.
        assertEquals(1, SpaceSidebar.diff(document, top).size)
        // An only child has nowhere to go.
        assertNull(StemCellsDrag.holding("s2a", drawn, all, document))
    }

    @Test fun whatCantBeCarried() {
        val document = SpaceSidebar.parse(html)
        val all = rows(document)
        val drawn = drawn(all)
        assertNull(StemCellsDrag.holding("happy", drawn, all, document))
        // a1 is a's only nested row: it has no sibling to pass.
        assertNull(StemCellsDrag.holding("a1", drawn, all, document))
        assertNull(StemCellsDrag.holding("happy-search", drawn, all, document))
    }

    /** A nested row folds as a top-level one does: one `set` of its own `expanded`. */
    @Test fun aNestedRowFoldsByItself() {
        val document = SpaceSidebar.parse("""<nav><happy-search/><happy-create/><happy-space id="t" expanded="true"><happy-space id="s1"><happy-space id="g1"/></happy-space><happy-space id="s2"/></happy-space></nav>""")
        val opened = SidebarDocumentEdits.fold(document, "s1")!!
        assertEquals(listOf(SpaceSidebarOperation.Set(SpaceSidebarSet(id = "s1", expanded = true))), SpaceSidebar.diff(document, opened))
        val closed = SidebarDocumentEdits.fold(opened, "s1")!!
        assertEquals(listOf(SpaceSidebarOperation.Set(SpaceSidebarSet(id = "s1", expanded = false))), SpaceSidebar.diff(opened, closed))
    }
}

class StemCellsWarmthTest {
    private fun row(agent: String, kind: StemCellsRow.Kind = StemCellsRow.Kind.Space, status: StemCellsRow.Status = StemCellsRow.Status.None, unread: Boolean = false, target: String? = "task") =
        StemCellsRow(id = "space:$agent", nodeId = agent, kind = kind, label = agent, status = status, unread = unread, agentId = agent, spaceId = agent, targetKind = target)

    /** Every row that opens a conversation, once, in the sidebar's order: not a surface, a section, or the same agent twice. */
    @Test fun theCandidatesAreTheSidebarsConversationsOnce() {
        val rows = listOf(
            row("a"), row("doc", target = "surface"), row("b", kind = StemCellsRow.Kind.Session, status = StemCellsRow.Status.Working),
            StemCellsRow(id = "section:s", nodeId = "s", kind = StemCellsRow.Kind.Section, label = "S"), row("a"),
        )
        assertEquals(listOf(StemCellsWarmth.Candidate("a", false, false), StemCellsWarmth.Candidate("b", true, false)), StemCellsWarmth.candidates(rows))
    }

    @Test fun theRankingIsRunningThenWhatShowsThenNewsThenTheLastLeft() {
        val candidates = listOf(
            StemCellsWarmth.Candidate("top", false, false),
            StemCellsWarmth.Candidate("seen", false, false),
            StemCellsWarmth.Candidate("left-long-ago", false, false),
            StemCellsWarmth.Candidate("news", false, true),
            StemCellsWarmth.Candidate("left", false, false),
            StemCellsWarmth.Candidate("busy", true, false),
            StemCellsWarmth.Candidate("below", false, false),
            StemCellsWarmth.Candidate("seen-news", false, true),
        )
        val ranked = StemCellsWarmth.ranked(candidates, listOf("left", "left-long-ago"), setOf("top", "seen", "seen-news"), 12)
        assertEquals(listOf("busy", "top", "seen", "seen-news", "news", "left", "left-long-ago", "below"), ranked)
        assertEquals(listOf("busy", "news", "seen-news"), StemCellsWarmth.ranked(candidates, emptyList(), emptySet(), 3))
        assertEquals(emptyList<String>(), StemCellsWarmth.ranked(candidates, emptyList(), emptySet(), 0))
    }

    @Test fun theLimitsAreDesktops() {
        assertEquals(12, StemCellsWarmth.Limits.PRELOADED)
        assertEquals(6, StemCellsWarmth.Limits.PREFETCHED)
        assertEquals(16, StemCellsWarmth.Limits.RELEASED)
        assertEquals(2, StemCellsWarmth.laidOut(1_500_000_000))
        assertEquals(3, StemCellsWarmth.laidOut(3_000_000_000))
        assertEquals(12, StemCellsWarmth.laidOut(6_000_000_000))
        assertEquals(12, StemCellsWarmth.laidOut(16_000_000_000))
    }

    @Test fun retentionKeepsTheOpenTheWantedTheLastLeftAndTheLastReachedFor() {
        val kept = listOf(
            StemCellsWarmth.Kept("open", held = true),
            StemCellsWarmth.Kept("wanted"),
            StemCellsWarmth.Kept("dropped"),
            StemCellsWarmth.Kept("left-1", left = 1),
            StemCellsWarmth.Kept("left-2", left = 2),
            StemCellsWarmth.Kept("left-3", left = 3),
            StemCellsWarmth.Kept("reached-1", intent = 1),
            StemCellsWarmth.Kept("reached-2", intent = 2),
            // Reached for, then opened and left: counted as left.
            StemCellsWarmth.Kept("reached-left", left = 0, intent = 9),
        )
        assertEquals(setOf("open", "wanted", "left-3", "left-2", "reached-2"), StemCellsWarmth.retained(kept, listOf("wanted", "not-kept"), released = 2, prefetched = 1))
        assertEquals(kept.size - 2, StemCellsWarmth.retained(kept, emptyList()).size)
    }

    @Test fun thePlannedAreTheReachedForTheJustLeftTheRankedThenTheLeft() {
        val kept = listOf(
            StemCellsWarmth.Kept("open", held = true, intent = 5),
            StemCellsWarmth.Kept("a"), StemCellsWarmth.Kept("b"),
            StemCellsWarmth.Kept("left-before", left = 1),
            StemCellsWarmth.Kept("left", left = 2),
            StemCellsWarmth.Kept("reached", intent = 3),
        )
        assertEquals(listOf("reached", "left", "b", "a", "left-before"), StemCellsWarmth.planned(kept, listOf("open", "b", "a", "missing"), 8))
        assertEquals(listOf("reached", "left"), StemCellsWarmth.planned(kept, listOf("b", "a"), 2))
    }
}

class StemCellsSharedTest {
    private fun item(name: String, kind: String = "image") =
        MediaItem(kind, name, if (kind == "image") "image/png" else "application/pdf", 10.0, 10.0, 10.0, null, null, null)

    private fun media(call: String, items: List<MediaItem>) = Block.ToolCall(call, "present_files", "completed", Presentation("media", items))

    private fun base64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    @Test fun linksAreFoundInTheirOrder() {
        val links = StemCellsShared.links("See [the docs](https://www.example.com/docs) and https://github.com/slopus/happy. Also (https://a.io/x), then [https://b.dev](https://b.dev).")
        assertEquals(listOf("https://www.example.com/docs", "https://github.com/slopus/happy", "https://a.io/x", "https://b.dev"), links.map { it.url })
        assertEquals(listOf("the docs", null, null, null), links.map { it.title })
        assertEquals(listOf("example.com", "github.com", "a.io", "b.dev"), links.map { it.host })
        assertEquals(emptyList<StemCellsShared.Link>(), StemCellsShared.links("no links, not even ftp://x.y or http://"))
        // A Markdown image is a picture, not a link; a URL keeps its own parentheses.
        val wiki = StemCellsShared.links("![chart](https://img.dev/c.png) see https://en.wikipedia.org/wiki/Swift_(programming_language), [it](https://en.wikipedia.org/wiki/A_(b)).")
        assertEquals(listOf("https://en.wikipedia.org/wiki/Swift_(programming_language)", "https://en.wikipedia.org/wiki/A_(b)"), wiki.map { it.url })
    }

    @Test fun aPageSharesItsMediaAndLinksNewestFirst() {
        val png = base64(byteArrayOf(1, 2, 3))
        val messages = listOf(
            AgentMessage("m1", "user", 1.0, listOf(Block.Text("look https://one.dev"), Block.Image("image/png", png), Block.Text("again https://one.dev"))),
            AgentMessage("m2", "agent", 2.0, listOf(
                Block.Reasoning("https://thinking.dev"),
                media("call", listOf(item("a.png"), item("report.pdf", kind = "file"))),
                Block.Text("Done: [report](https://two.dev/r)"),
            )),
            AgentMessage("m3", "system", 3.0, listOf(Block.Text("https://system.dev"))),
        )
        val shared = StemCellsShared.of(messages)
        assertEquals(listOf("tool/call/0", "tool/call/1", "sent/m1#0"), shared.media.map { it.key })
        assertEquals(listOf(true, false, true), shared.media.map { it.isPicture })
        assertEquals(listOf("https://two.dev/r", "https://one.dev"), shared.links.map { it.url })
    }

    @Test fun theTranscriptSharesTheSame() {
        val one = StemCellMedia("c", 0, item("a.png"))
        val items = listOf(
            StemCellChatItem("1", StemCellChatItem.Role.User, listOf(StemCellChatItem.Piece.Text("https://first.dev"), StemCellChatItem.Piece.Picture("1:1", "image/png", byteArrayOf(1))), StemCellChatItem.Delivery.Sent),
            StemCellChatItem("2", StemCellChatItem.Role.Collaborator("x", null), listOf(StemCellChatItem.Piece.Text("https://theirs.dev")), StemCellChatItem.Delivery.Sent),
            StemCellChatItem("3", StemCellChatItem.Role.Agent, listOf(StemCellChatItem.Piece.Gallery(listOf(one), 0), StemCellChatItem.Piece.Text("https://last.dev")), StemCellChatItem.Delivery.Sent),
        )
        val shared = StemCellsShared.of(items)
        assertEquals(listOf("tool/c/0", "sent/1#0"), shared.media.map { it.key })
        assertEquals(listOf("https://last.dev", "https://first.dev"), shared.links.map { it.url })
    }

    @Test fun aSentPictureIsTheSameBeforeAndAfterItsEcho() {
        val bytes = byteArrayOf(7)
        val sending = StemCellsShared.of(listOf(StemCellChatItem("s", StemCellChatItem.Role.User, listOf(StemCellChatItem.Piece.Picture("s:1", "image/png", bytes)), StemCellChatItem.Delivery.Sending)))
        val echoed = StemCellsShared.of(listOf(AgentMessage("s", "user", 1.0, listOf(Block.Image("image/png", base64(bytes))))))
        assertEquals(listOf("sent/s#0"), sending.merged(echoed).media.map { it.key })
    }

    @Test fun theReaderReadsWhatChanged() {
        val reader = StemCellsShared.Reader()
        val items = mutableListOf(
            StemCellChatItem("1", StemCellChatItem.Role.User, listOf(StemCellChatItem.Piece.Text("https://one.dev")), StemCellChatItem.Delivery.Sent),
            StemCellChatItem("2", StemCellChatItem.Role.Agent, listOf(StemCellChatItem.Piece.Text("streaming https://two")), StemCellChatItem.Delivery.Sent),
        )
        assertEquals(listOf("https://two", "https://one.dev"), reader.read(items).links.map { it.url })
        items[1] = items[1].copy(pieces = listOf(StemCellChatItem.Piece.Text("streaming https://two.dev/more")))
        assertEquals(listOf("https://two.dev/more", "https://one.dev"), reader.read(items).links.map { it.url })
        assertEquals(StemCellsShared.of(items), reader.read(items))
    }

    @Test fun olderPagesAddWhatIsNew() {
        val newer = StemCellsShared(listOf(StemCellsShared.Media.Tool(StemCellMedia("c", 0, item("a.png")))), listOf(StemCellsShared.Link("https://a.dev", host = "a.dev")))
        val older = StemCellsShared(
            listOf(StemCellsShared.Media.Tool(StemCellMedia("c", 0, item("a.png"))), StemCellsShared.Media.Tool(StemCellMedia("d", 0, item("b.png")))),
            listOf(StemCellsShared.Link("https://a.dev", host = "a.dev"), StemCellsShared.Link("https://b.dev", host = "b.dev")),
        )
        val merged = newer.merged(older)
        assertEquals(listOf("tool/c/0", "tool/d/0"), merged.media.map { it.key })
        assertEquals(listOf("https://a.dev", "https://b.dev"), merged.links.map { it.url })
    }
}
