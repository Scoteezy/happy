package com.slopus.happy.stemcells

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * happy-ios `HappyReferencesTests`: reference tags found exactly where the
 * reference `parseHappyReferences` finds them
 * (`scripts/generate-reference-fixtures.ts`), settled and streaming, and how
 * a message reads with them.
 */
class HappyReferencesTest {
    private val cases: List<JsonObject> by lazy {
        Json.parseToJsonElement(requireNotNull(javaClass.classLoader!!.getResource("stemcells/happy-references.json")).readText())
            .jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
    }

    private fun JsonElement.string(key: String) = (jsonObject[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun expected(segments: JsonElement): List<String> = segments.jsonArray.map { segment ->
        if (segment.string("type") != "reference") return@map "T:" + segment.string("text")
        val reference = segment.jsonObject["reference"]!!
        val version = reference.jsonObject["version"]?.jsonPrimitive?.int?.let { " v$it" } ?: ""
        val workspace = reference.string("workspaceId")?.let { " w$it" } ?: ""
        "R:${reference.string("kind")}/${reference.string("id")}$version$workspace ${segment.string("source")}"
    }

    private fun actual(segments: List<HappyReferences.Segment>): List<String> = segments.map {
        when (it) {
            is HappyReferences.Segment.Text -> "T:" + it.text
            is HappyReferences.Segment.Ref ->
                "R:${it.reference.kind}/${it.reference.id}${it.reference.version?.let { v -> " v$v" } ?: ""}${it.reference.workspaceId?.let { w -> " w$w" } ?: ""} ${it.source}"
        }
    }

    @Test
    fun parsesAsTheReference() {
        assertTrue(cases.size > 400)
        val failures = ArrayList<String>()
        for ((index, entry) in cases.withIndex()) {
            val text = entry.string("text")!!
            if (actual(HappyReferences.parse(text)) != expected(entry["settled"]!!)) failures += "case $index: $text"
            if (actual(HappyReferences.parse(text, streaming = true)) != expected(entry["streaming"]!!)) failures += "case $index streaming: $text"
        }
        assertTrue("${failures.size} failures\n" + failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
    private fun s(value: String) = JsonPrimitive(value)

    /** The bots and tasks as the store holds them: a bot whose agent has a subtask with its own workspace, and a task. */
    private val owners: List<JsonElement> = listOf(
        obj(
            "name" to s("Chef"), "workspaceId" to s("wb"),
            "agent" to obj(
                "id" to s("ab"), "workspaceId" to s("wb"), "title" to s("Chief of Staff chat"),
                "subtasks" to JsonArray(listOf(obj(
                    "id" to s("as"), "workspaceId" to s("ws"), "title" to s("Guest list template"),
                    "subtasks" to JsonArray(listOf(obj("id" to s("ag"), "workspaceId" to s("ws"), "title" to s("  ")))),
                ))),
            ),
        ),
        obj("name" to s("Birthday party for Maya"), "workspaceId" to s("wt"), "agent" to obj("id" to s("at"), "subtasks" to JsonArray(emptyList()))),
    )

    private fun markdown(text: String, references: StemCellReferences) =
        HappyReferences.markdown(text, references::view).replace(HappyReferences.linkPrefix, "ref:")

    @Test
    fun anAgentReadsAsItsConversationsName() {
        val references = StemCellReferences.entities(owners)
        assertEquals("[Chef](ref:agent/ab)", markdown("<happy-agent id=\"ab\"/>", references))
        assertEquals("[Birthday party for Maya](ref:agent/at)", markdown("<happy-agent id=\"at\"/>", references))
        assertEquals("Started [Guest list template](ref:agent/as) to draft it.", markdown("Started <happy-agent id=\"as\"/> to draft it.", references))
        assertEquals("[Untitled agent](ref:agent/ag)", markdown("<happy-agent id=\"ag\"/>", references))
        assertEquals("an unavailable agent", markdown("<happy-agent id=\"gone\"/>", references))
        assertEquals(StemCellReferences.Entity("Guest list template", "as"), references.entity(HappyReferences.Reference("agent", "as")))
    }

    @Test
    fun aHiddenSubagentReadsAsItsTitleAndOpensNothing() {
        val references = StemCellReferences.entities(owners).copy(subagents = mapOf("c1" to "Find flights", "c2" to " ", "as" to "Not this"))
        assertEquals("Dispatched to Find flights.", markdown("Dispatched to <happy-agent id=\"c1\"/>.", references))
        assertEquals("Subagent", markdown("<happy-agent id=\"c2\"/>", references))
        assertEquals("[Guest list template](ref:agent/as)", markdown("<happy-agent id=\"as\"/>", references))
        assertNull(references.entity(HappyReferences.Reference("agent", "c1")))
    }

    @Test
    fun aWorkspaceReadsAsWhatItHolds() {
        val references = StemCellReferences.entities(owners)
        assertEquals("[Chef](ref:workspace/wb)", markdown("<happy-workspace id=\"wb\"/>", references))
        assertEquals("[Birthday party for Maya](ref:workspace/wt)", markdown("<happy-workspace id=\"wt\"/>", references))
        assertEquals("[Guest list template](ref:workspace/ws)", markdown("<happy-workspace id=\"ws\"/>", references))
        assertEquals("ab", references.entity(HappyReferences.Reference("workspace", "wb"))?.agentId)
        assertEquals("an unavailable workspace", markdown("<happy-workspace id=\"nope\"/>", references))
    }

    @Test
    fun anArtifactReadsAsItsTitleOnceRead() {
        var references = StemCellReferences()
        val text = "See <happy-artifact id=\"r1\" version=\"2\"/>."
        assertEquals(listOf("r1"), references.unread(text))
        assertEquals("See [artifact](ref:artifact/r1/2).", markdown(text, references))
        references = references.copy(artifacts = mapOf("r1" to StemCellReferences.artifact(obj("title" to s("Q3 revenue report"), "status" to s("active"), "type" to s("markdown"), "latestVersion" to JsonPrimitive(2)))))
        assertEquals(emptyList<String>(), references.unread(text))
        assertEquals("See [Q3 revenue report](ref:artifact/r1/2).", markdown(text, references))
        references = references.copy(artifacts = mapOf("r1" to StemCellReferences.Artifact.Active(" ", "html", 1)))
        assertEquals("See [Untitled artifact](ref:artifact/r1/2).", markdown(text, references))
        references = references.copy(artifacts = mapOf("r1" to StemCellReferences.artifact(obj("title" to s("Old report"), "status" to s("deleted")))))
        assertEquals("See Old report.", markdown(text, references))
        references = references.copy(artifacts = mapOf("r1" to StemCellReferences.Artifact.Missing))
        assertEquals("See an unavailable artifact.", markdown(text, references))
    }

    @Test
    fun projectsServicesAndUnknownKindsAreInert() {
        val references = StemCellReferences.entities(owners)
        assertEquals(
            "an unavailable project an unavailable service an unavailable reference",
            markdown("<happy-project id=\"p\"/> <happy-service id=\"s\" workspace=\"wb\"/> <happy-gizmo id=\"g\"/>", references),
        )
    }

    @Test
    fun namesAreRunningWordsAndOnlyThisPhonesLinksOpen() {
        val references = StemCellReferences(agents = mapOf("a1" to StemCellReferences.Entity("Guest *list* [draft]\nnow", "a1")))
        val text = "Started <happy-agent id=\"a1\"/> to draft the [guest] list, not `<happy-agent id=\"a1\"/>`."
        assertEquals("Started [Guest list (draft) now](ref:agent/a1) to draft the [guest] list, not `<happy-agent id=\"a1\"/>`.", markdown(text, references))
        assertEquals("Started Guest *list* [draft]\nnow to draft the [guest] list, not `<happy-agent id=\"a1\"/>`.", HappyReferences.plain(text, references::view))
        assertEquals(HappyReferences.Reference("agent", "a1"), HappyReferences.reference(HappyReferences.link(HappyReferences.Reference("agent", "a1"))))
        assertEquals(HappyReferences.Reference("artifact", "r", version = 3), HappyReferences.reference(HappyReferences.link(HappyReferences.Reference("artifact", "r", version = 3))))
        assertNull(HappyReferences.reference(HappyReferences.linkPrefix + "artifact/r/0"))
        assertNull("a hand-written link passed for a reference", HappyReferences.reference("happy-reference:agent/a1"))
    }

    @Test
    fun streamingHoldsBackATagStillArriving() {
        assertEquals("Started ", HappyReferences.droppingPartialTag("Started <happy-agent id=\"a"))
        assertEquals("1 < 2", HappyReferences.droppingPartialTag("1 < 2"))
        assertEquals("Done <happy-agent id=\"a\"/>", HappyReferences.droppingPartialTag("Done <happy-agent id=\"a\"/>"))
    }
}
