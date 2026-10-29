package com.slopus.happy.stemcells

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * happy-ios `StemCellMediaTreeTests` and `StemCellMediaTests`' splitting:
 * layout blocks split exactly as `splitMediaLayoutBlocks` splits them, layout
 * HTML parsed exactly as `parseMediaLayoutHtml` parses it, a presentation's
 * tree as desktop takes it, and the boxes the phone lays out.
 */
class StemCellMediaTreeTest {
    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(requireNotNull(javaClass.classLoader!!.getResource("stemcells/$name")).readText()).jsonObject

    private fun JsonElement.string(key: String) = (jsonObject[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun json(nodes: List<StemCellMediaTree.Node>): JsonArray = JsonArray(nodes.map { node ->
        fun style(style: Map<String, String>) = JsonObject(style.mapValues { JsonPrimitive(it.value) })
        when (node) {
            is StemCellMediaTree.Node.Element -> JsonObject(buildMap {
                put("type", JsonPrimitive("element")); put("tag", JsonPrimitive(node.tag)); put("children", json(node.children))
                if (node.style.isNotEmpty()) put("style", style(node.style))
            })
            is StemCellMediaTree.Node.Item -> JsonObject(buildMap {
                put("type", JsonPrimitive("item")); put("index", JsonPrimitive(node.index))
                node.alt?.let { put("alt", JsonPrimitive(it)) }
                if (node.style.isNotEmpty()) put("style", style(node.style))
            })
            is StemCellMediaTree.Node.Text -> JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(node.text)))
            StemCellMediaTree.Node.LineBreak -> JsonObject(mapOf("type" to JsonPrimitive("break")))
        }
    })

    @Test
    fun splitsAsTheReference() {
        val failures = ArrayList<String>()
        for ((index, entry) in fixture("media-layout-blocks.json")["cases"]!!.jsonArray.withIndex()) {
            val text = entry.string("text")!!
            val expected = entry.jsonObject["segments"]!!.jsonArray.map { segment ->
                if (segment.string("type") == "markdown") "M:" + segment.string("text")
                else "L${if (segment.jsonObject["complete"]!!.jsonPrimitive.boolean) "" else "?"}:" + segment.string("html")
            }
            val actual = StemCellMediaBlocks.split(text).map {
                when (it) {
                    is StemCellMediaBlocks.Segment.Markdown -> "M:" + it.text
                    is StemCellMediaBlocks.Segment.Layout -> "L${if (it.complete) "" else "?"}:" + it.raw
                }
            }
            if (actual != expected) failures += "case $index: $text\n got $actual\nwant $expected"
        }
        assertTrue("${failures.size} failures\n" + failures.take(5).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun parsesAsTheReference() {
        val vectors = fixture("media-layout-html.json")
        val sources = vectors["sources"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }
        val failures = ArrayList<String>()
        for ((index, entry) in vectors["cases"]!!.jsonArray.withIndex()) {
            val html = entry.string("html")!!
            val parsed = StemCellMediaTree.parse(html) { sources[it] }
            val placed = entry.jsonObject["placed"]!!.jsonArray.map { it.jsonPrimitive.int }
            val root = json(parsed.tree.root)
            val same = root == entry.jsonObject["root"] && parsed.placed == placed && parsed.truncated == entry.jsonObject["truncated"]!!.jsonPrimitive.boolean
            if (!same) failures += "case $index: ${html.take(200)}\n got $root\nwant ${entry.jsonObject["root"]}\n placed ${parsed.placed} want $placed, truncated ${parsed.truncated}"
        }
        assertTrue("${failures.size} failures\n" + failures.take(5).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun stylesNormalizeAsTheReference() {
        assertEquals(
            mapOf("display" to "grid", "gridTemplateColumns" to "repeat(auto-fill, minmax(200px, 1fr))", "gap" to "8px 4px"),
            StemCellMediaTree.parseStyle("Display: GRID; grid-template-columns: repeat( auto-fill,minmax(200px , 1fr) ); gap:8px  4px"),
        )
        assertEquals(mapOf("aspectRatio" to "16 / 9"), StemCellMediaTree.parseStyle("aspect-ratio: 16/9; color: red; width: calc(100% - 1px)"))
        assertEquals(emptyMap<String, String>(), StemCellMediaTree.parseStyle("width: 1px; font-family: 'x'"))
    }

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))

    @Test
    fun aPresentationsTreePlacesEveryItemOnce() {
        val layout = obj(
            "type" to JsonPrimitive("tree"),
            "root" to JsonArray(listOf(
                obj(
                    "type" to JsonPrimitive("element"), "tag" to JsonPrimitive("blink"),
                    "style" to obj("display" to JsonPrimitive("grid"), "color" to JsonPrimitive("red")),
                    "children" to JsonArray(listOf(
                        obj("type" to JsonPrimitive("item"), "index" to JsonPrimitive(1), "alt" to JsonPrimitive("B")),
                        obj("type" to JsonPrimitive("item"), "index" to JsonPrimitive(1)),
                        obj("type" to JsonPrimitive("item"), "index" to JsonPrimitive(7)),
                        obj("type" to JsonPrimitive("text"), "text" to JsonPrimitive("caption")),
                    )),
                ),
                obj("type" to JsonPrimitive("break")),
                obj("type" to JsonPrimitive("mystery")),
            )),
        )
        assertEquals(
            listOf(
                StemCellMediaTree.Node.Element("div", mapOf("display" to "grid"), listOf(StemCellMediaTree.Node.Item(1, "B", emptyMap()), StemCellMediaTree.Node.Text("caption"))),
                StemCellMediaTree.Node.LineBreak,
                StemCellMediaTree.Node.Item(0, null, emptyMap()),
                StemCellMediaTree.Node.Item(2, null, emptyMap()),
            ),
            StemCellMediaTree.decode(layout, 3)?.root,
        )
        assertNull(StemCellMediaTree.decode(obj("type" to JsonPrimitive("columns"), "columns" to JsonPrimitive(2)), 2))
    }

    @Test
    fun blocksGatherInlineTextIntoParagraphs() {
        val parsed = StemCellMediaTree.parse("""<figure style="display: grid; text-align: center"><img src="a"><figcaption>Before <strong>and</strong><br>after</figcaption></figure><span style="tone: muted">tail</span>""") { if (it == "a") 0 else null }
        assertEquals(
            listOf(
                StemCellMediaTree.Block.Container(mapOf("display" to "grid", "textAlign" to "center"), listOf(
                    StemCellMediaTree.Block.Item(0, null, emptyMap()),
                    StemCellMediaTree.Block.Container(emptyMap(), listOf(
                        StemCellMediaTree.Block.Paragraph("center", listOf(
                            StemCellMediaTree.Run("Before "),
                            StemCellMediaTree.Run("and", bold = true),
                            StemCellMediaTree.Run("\n"),
                            StemCellMediaTree.Run("after"),
                        )),
                    )),
                )),
                StemCellMediaTree.Block.Paragraph(null, listOf(StemCellMediaTree.Run("tail", tone = "muted"))),
            ),
            parsed.tree.blocks,
        )
    }
}
