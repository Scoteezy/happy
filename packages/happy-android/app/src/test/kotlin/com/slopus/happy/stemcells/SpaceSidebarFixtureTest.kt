package com.slopus.happy.stemcells

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * happy-ios `SpaceSidebarFixtureTests`: the sidebar grammar, applier, differ
 * and desktop's edits against vectors the TypeScript reference produced
 * (`scripts/generate-sidebar-fixtures.ts`). Every case must match byte for
 * byte: the formatted document, the applied operations, the error message
 * and its offset.
 */
class SpaceSidebarFixtureTest {
    private val vectors: JsonObject by lazy {
        Json.parseToJsonElement(requireNotNull(javaClass.classLoader!!.getResource("stemcells/space-sidebar-vectors.json")).readText()).jsonObject
    }

    private fun cases(key: String) = vectors[key]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Collects every mismatch, so one run shows them all. */
    private class Failures {
        val list = ArrayList<String>()
        fun check(expected: Any?, actual: Any?, what: String) { if (expected != actual) list += "$what\n  expected: $expected\n  actual:   $actual" }
        fun fail(what: String) { list += what }
        fun done() = assertTrue("${list.size} failures\n" + list.take(10).joinToString("\n"), list.isEmpty())
    }

    @Test
    fun parseAndFormat() {
        val failures = Failures()
        for ((index, entry) in cases("parse").withIndex()) {
            val html = entry.string("html")!!
            try {
                val document = SpaceSidebar.parse(html)
                val formatted = entry.string("formatted")
                if (formatted == null) { failures.fail("parse $index should fail with ${entry["error"]}"); continue }
                failures.check(formatted, SpaceSidebar.format(document), "parse $index")
                failures.check(entry.string("withoutWidgets"), SpaceSidebar.format(document, widgets = false), "parse $index without widgets")
                failures.check(document, SpaceSidebar.parse(formatted), "parse $index round trip")
            } catch (error: SpaceSidebarError) {
                val expected = entry["error"]?.jsonObject
                if (expected == null) { failures.fail("parse $index failed: ${error.message}"); continue }
                failures.check(expected.string("message"), error.message, "parse $index")
                failures.check(expected["offset"]!!.jsonPrimitive.int, error.offset, "parse $index offset")
            }
        }
        failures.done()
    }

    @Test
    fun apply() {
        val failures = Failures()
        for ((index, entry) in cases("apply").withIndex()) {
            val document = SpaceSidebar.parse(entry.string("html")!!)
            val base = entry.string("base")?.let(SpaceSidebar::parse)
            val operations = entry["operations"]!!.jsonArray.map { SpaceSidebarOperation.from(it)!! }
            try {
                val result = SpaceSidebar.apply(document, operations, base)
                val expected = entry.string("result")
                if (expected == null) { failures.fail("apply $index should fail with ${entry["error"]}"); continue }
                failures.check(expected, SpaceSidebar.format(result.document), "apply $index")
                failures.check(entry["applied"]!!.jsonArray, JsonArray(result.operations.map { it.json() }), "apply $index operations")
            } catch (error: SpaceSidebarError) {
                val expected = entry["error"]?.jsonObject
                if (expected == null) { failures.fail("apply $index failed: ${error.message}"); continue }
                failures.check(expected.string("message"), error.message, "apply $index")
                failures.check(expected["operationIndex"]?.jsonPrimitive?.int, error.operationIndex, "apply $index index")
            }
        }
        failures.done()
    }

    @Test
    fun diff() {
        val failures = Failures()
        for ((index, entry) in cases("diff").withIndex()) {
            val current = SpaceSidebar.parse(entry.string("current")!!)
            val proposed = SpaceSidebar.parse(entry.string("proposed")!!)
            val operations = SpaceSidebar.diff(current, proposed, widgets = entry["widgets"]!!.jsonPrimitive.booleanOrNull!!)
            failures.check(entry["operations"]!!.jsonArray, JsonArray(operations.map { it.json() }), "diff $index")
        }
        failures.done()
    }

    @Test
    fun desktopEdits() {
        val failures = Failures()
        fun tree(value: JsonElement): SidebarDocumentEdits.SessionTree =
            SidebarDocumentEdits.SessionTree(value.jsonObject.string("id")!!, value.jsonObject["children"]!!.jsonArray.map(::tree))
        for ((index, entry) in cases("edits").withIndex()) {
            val document = SpaceSidebar.parse(entry.string("html")!!)
            val id = entry.string("id")
            val after = entry.string("after")
            val kind = entry.string("kind")!!
            val edited = when (kind) {
                "move" -> SidebarDocumentEdits.move(document, id!!, after)
                "fold" -> SidebarDocumentEdits.fold(document, id!!)
                "place" -> SidebarDocumentEdits.place(document, id!!, entry.string("parent"), after)
                "insert" -> SidebarDocumentEdits.insert(document, entry.string("spaceId")!!, entry.string("sectionId"), entry.string("happySpaceId"))
                else -> SidebarDocumentEdits.revealSessions(document, id!!, entry["sessions"]!!.jsonArray.map(::tree))
            }
            failures.check(entry.string("result"), edited?.let { SpaceSidebar.format(it) }, "edit $index $kind")
        }
        failures.done()
    }

    @Test
    fun desktopHidesSessions() {
        val failures = Failures()
        for ((index, entry) in cases("hides").withIndex()) {
            val document = SpaceSidebar.parse(entry.string("html")!!)
            val attached = entry["attached"]!!.jsonArray.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
            val edited = SidebarDocumentEdits.hideSessions(document, entry.string("id")!!) { it in attached }
            failures.check(entry.string("result"), edited?.let { SpaceSidebar.format(it) }, "hide $index")
        }
        failures.done()
    }

    @Test
    fun vectorsAreAllThere() {
        assertEquals(listOf(116, 700, 300, 400, 120), listOf("parse", "apply", "diff", "edits", "hides").map { cases(it).size })
    }
}
