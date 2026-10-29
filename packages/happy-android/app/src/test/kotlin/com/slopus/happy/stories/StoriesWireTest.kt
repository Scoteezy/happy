package com.slopus.happy.stories

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI

/**
 * happy-ios `StoriesWireTests`: the models against samples checked by
 * @slopus/happy-agent-client's own TypeBox schemas
 * (`scripts/generate-stories-fixtures.ts`): each decodes and encodes back
 * to the same JSON, and the client's constants and grid agree.
 */
class StoriesWireTest {
    private val fixture: JsonObject by lazy {
        Json.parseToJsonElement(requireNotNull(javaClass.classLoader!!.getResource("stories/stories-wire.json")).readText()).jsonObject
    }
    private val samples get() = fixture["samples"]!!.jsonObject

    /** JSON equality with numbers compared by value, as Swift's `JSONValue` holds them. */
    private fun same(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { same(a[it]!!, b[it]!!) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is JsonNull || b is JsonNull -> a == b
        a is JsonPrimitive && b is JsonPrimitive ->
            if (a.isString || b.isString) a == b
            else if (a.doubleOrNull != null && b.doubleOrNull != null) a.doubleOrNull == b.doubleOrNull
            else a.content == b.content
        else -> false
    }

    private fun roundTrip(name: String, decode: (JsonElement) -> Any, encode: (Any) -> JsonElement) {
        val list = samples[name]!!.jsonArray
        assertFalse(list.isEmpty())
        for (sample in list) {
            val encoded = encode(decode(sample))
            assertTrue("$name: $encoded\n!=\n$sample", same(encoded, sample))
        }
    }

    @Test
    fun everySampleRoundTrips() {
        roundTrip("story", Story::from) { (it as Story).json() }
        roundTrip("feed", StoryFeed::from) { (it as StoryFeed).json() }
        roundTrip("page", StoriesPage::from) { (it as StoriesPage).json() }
        roundTrip("media", StoryMediaPage::from) { (it as StoryMediaPage).json() }
        roundTrip("viewers", StoryViewersPage::from) { (it as StoryViewersPage).json() }
        roundTrip("pack", StickerPackContents::from) { (it as StickerPackContents).json() }
        roundTrip("recents", StickerRecents::from) { (it as StickerRecents).json() }
    }

    @Test
    fun overlaysDecodeToTheirKinds() {
        val story = Story.from(samples["story"]!!.jsonArray.first())
        val kinds = story.frames[0].overlays.map {
            when (it) {
                is StoryOverlay.Text -> "text"
                is StoryOverlay.Link -> "link"
                is StoryOverlay.Sticker -> "sticker"
                is StoryOverlay.Emoji -> "emoji"
                is StoryOverlay.Reaction -> "reaction"
                is StoryOverlay.Unknown -> "unknown"
            }
        }
        assertEquals(listOf("text") + List(8) { "text" } + listOf("link", "link", "sticker", "emoji", "reaction", "reaction"), kinds)
        val last = story.frames[0].overlays.last() as? StoryOverlay.Reaction ?: return fail()
        assertEquals("🔥", last.emoji); assertEquals(true, last.dark); assertEquals(true, last.flipped)
    }

    /** A newer daemon's overlay is kept as sent, never drawn, and sent back. */
    @Test
    fun anUnknownOverlayIsKept() {
        val raw = """{"id":"o9","type":"poll","question":"Ship it?","transform":{"x":0.5,"y":0.5,"scale":1,"rotation":0}}"""
        val overlay = StoryOverlay.from(Json.parseToJsonElement(raw))
        if (overlay !is StoryOverlay.Unknown) return fail("decoded as a known kind")
        assertEquals("o9", overlay.id)
        assertNull(overlay.transform)
        assertEquals(Json.parseToJsonElement(raw), overlay.json())
    }

    /** Frame inputs encode exactly as the TypeScript `storyFrameInputSchema` accepts them. */
    @Test
    fun frameInputsEncodeAsTheClientDoes() {
        val expected = samples["frameInputs"]!!.jsonArray
        val text = StoryOverlay.from(expected[2].jsonObject["overlays"]!!.jsonArray.first())
        val inputs = listOf(
            StoryFrameInput("f1", StoryFrameInput.Media.Upload("u8p2l4o6a1d3e5f7g9h0j2k4", "IMG_0042.jpg"), overlays = emptyList()),
            StoryFrameInput(null, StoryFrameInput.Media.Asset(StoryAsset.ChatMedia("a", "t", 1)), overlays = emptyList()),
            StoryFrameInput("f2", StoryFrameInput.Media.Keep, StoryTransform(0.5, 0.5, 1.2, 0.0), listOf("#000000", "#FFFFFF"), listOf(text)),
        )
        for ((input, json) in inputs.zip(expected)) assertTrue("${input.json()} != $json", same(input.json(), json))
    }

    @Test
    fun constantsMatchTheClient() {
        val constants = fixture["constants"]!!.jsonObject
        fun number(e: JsonElement?) = e!!.jsonPrimitive.doubleOrNull
        val canvas = constants["canvas"]!!.jsonObject
        assertEquals(StoryCanvas.WIDTH, number(canvas["width"]))
        assertEquals(StoryCanvas.HEIGHT, number(canvas["height"]))
        assertEquals(StoryCanvas.MAX_FRAMES.toDouble(), number(constants["maxFrames"]))
        assertEquals(StoryCanvas.MAX_OVERLAYS.toDouble(), number(constants["maxOverlays"]))
        assertEquals(StoryCanvas.MAX_CAPTION.toDouble(), number(constants["maxCaption"]))
        assertEquals(StoryCanvas.LIFETIME_MS.toDouble(), number(constants["lifetimeMs"]))
        assertEquals(StoryCanvas.IMAGE_DURATION_MS.toDouble(), number(constants["imageDurationMs"]))
        assertEquals(StoryCanvas.MAX_VIDEO_DURATION_MS.toDouble(), number(constants["maxVideoDurationMs"]))
        assertEquals(STORIES_SETTING_KEY, constants["settingKey"]!!.jsonPrimitive.content)
        assertEquals(constants["fonts"]!!.jsonArray.map { it.jsonPrimitive.content }, StoryTextFont.entries.map { it.wire })
        assertEquals(constants["fills"]!!.jsonArray.map { it.jsonPrimitive.content }, StoryTextFill.entries.map { it.wire })
        assertEquals(constants["linkStyles"]!!.jsonArray.map { it.jsonPrimitive.content }, StoryLinkStyle.entries.map { it.wire })
        val grid = constants["grid"]!!.jsonArray
        assertTrue(grid.size > 20)
        for (cell in grid.map { it.jsonObject }) {
            val kind = wireEnum<StickerPackKind>(cell["kind"]!!.jsonPrimitive.content)
            val width = number(cell["width"])!!
            val spacing = number(cell["spacing"])!!
            assertEquals("$kind $width $spacing", number(cell["columns"]), StickerGrid.columns(kind, width, spacing).toDouble())
        }
    }

    @Test
    fun aFrameShowsForItsDuration() {
        val asset = StoryAsset.ChatMedia("a", "t", 0)
        assertEquals(5000, StoryFrame("f", StoryFrame.Kind.Image, 1, 1, thumbhash = null, durationMs = 9000, asset = asset, available = true).showMs)
        assertEquals(9000, StoryFrame("f", StoryFrame.Kind.Video, null, null, thumbhash = null, durationMs = 9000, asset = asset, available = true).showMs)
        assertEquals(60000, StoryFrame("f", StoryFrame.Kind.Video, null, null, thumbhash = null, durationMs = 90000, asset = asset, available = true).showMs)
        assertEquals(5000, StoryFrame("f", StoryFrame.Kind.Video, null, null, thumbhash = null, durationMs = null, asset = asset, available = true).showMs)
    }

    @Test
    fun transformsClampToTheKeptRanges() {
        assertEquals(StoryTransform(-0.5, 1.5, 0.05, PI), StoryTransform(-3.0, 9.0, 0.0, 7.0).clamped)
    }
}
