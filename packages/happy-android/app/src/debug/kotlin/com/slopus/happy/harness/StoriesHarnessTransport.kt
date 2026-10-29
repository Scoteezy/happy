package com.slopus.happy.harness

import com.slopus.happy.direct.DirectHttpResponse
import com.slopus.happy.direct.DirectTransport
import com.slopus.happy.stories.STORIES_SETTING_KEY
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URLDecoder

/**
 * The harness's computer (happy-ios `StoriesHarnessTransport`): Stories on,
 * three authors, reactions and seen marks kept as the daemon keeps them,
 * answering every route happy-agent's API.md gives Stories and Stickers
 * with the shapes it documents. Debug builds only; nothing here reaches a
 * real daemon.
 */
class StoriesHarnessTransport(private val epoch: Long, newer: Boolean = false, private val slowFrames: Boolean = false) : DirectTransport {
    private val lock = Mutex()
    private val stories = LinkedHashMap<String, MutableMap<String, JsonElement>>()
    private var frames: Map<String, ByteArray> = emptyMap()
    /** The media type of each frame sent by hand; the drawn ones are PNGs. */
    private val frameTypes = HashMap<String, String>()
    private val order = linkedMapOf("self" to mutableListOf("m1"), "u2" to mutableListOf("s1", "s2"), "u3" to mutableListOf("a1"))
    private val uploads = HashMap<String, ByteArray>()
    private val recents = ArrayList<JsonObject>()
    private var stickerFiles: Map<String, ByteArray> = emptyMap()
    private var revision = 0

    /** One sticker of a harness pack: its emoji, colour and the shape drawn for it. */
    data class Sticker(val id: String, val emoji: String, val color: Int, val shape: String)
    data class Pack(val id: String, val title: String, val kind: String, val stickers: List<Sticker>)

    companion object {
        /**
         * Two tiny packs drawn here: shapes for the Stickers tab, and glyphs
         * of kind emoji; and one animated, served as its Lottie.
         */
        val packs = listOf(
            Pack("shapes", "Shapes", "stickers", listOf(
                Sticker("circle", "🔵", 0xFF0088FF.toInt(), "circle"), Sticker("square", "🟥", 0xFFFF383C.toInt(), "square"),
                Sticker("star", "⭐️", 0xFFFFCC00.toInt(), "star"), Sticker("heart", "❤️", 0xFFFF2D55.toInt(), "heart"),
                Sticker("drop", "💧", 0xFF00C3D0.toInt(), "circle"), Sticker("leaf", "🍀", 0xFF34C759.toInt(), "square"),
            )),
            Pack("glyphs", "Glyphs", "emoji", listOf(Sticker("plus", "➕", 0xFFFF8D28.toInt(), "square"), Sticker("ring", "⭕️", 0xFFCB30E0.toInt(), "circle"))),
            Pack("motion", "Motion", "stickers", listOf(Sticker("spin", "🌀", 0xFFFF2D55.toInt(), "lottie"))),
        )

        private fun ok(json: JsonElement, status: Int = 200) =
            DirectHttpResponse(status, mapOf("content-type" to "application/json"), json.toString().toByteArray())

        private fun status(code: Int) = DirectHttpResponse(code)

        private val MEDIA_TYPES = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
            "mp4" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm",
        )

        /** A file as the daemon serves media: whole, or one byte range of it (`206`), `416` outside it. */
        private fun served(data: ByteArray, type: String, headers: Map<String, String>): DirectHttpResponse {
            val base = mapOf("content-type" to type, "accept-ranges" to "bytes")
            val asked = headers.entries.firstOrNull { it.key.equals("range", ignoreCase = true) }?.value
            val spec = asked?.removePrefix("bytes=")?.takeIf { asked.startsWith("bytes=") && !it.contains(',') }
                ?: return DirectHttpResponse(200, base, data)
            val size = data.size
            val (first, last) = when {
                spec.startsWith("-") -> (size - (spec.drop(1).toIntOrNull() ?: 0)).coerceAtLeast(0) to size - 1
                else -> (spec.substringBefore('-').toIntOrNull() ?: 0) to (spec.substringAfter('-').toIntOrNull()?.coerceAtMost(size - 1) ?: (size - 1))
            }
            if (first >= size || first > last) return DirectHttpResponse(416, base + ("content-range" to "bytes */$size"))
            return DirectHttpResponse(206, base + ("content-range" to "bytes $first-$last/$size"), data.copyOfRange(first, last + 1))
        }

        private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (key, value) -> key to json(value) })

        private fun json(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is List<*> -> JsonArray(value.map { json(it) })
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k as String to json(v) })
            else -> error("unsupported $value")
        }
    }

    fun drawFrames(frames: Map<String, ByteArray>) { this.frames = frames }
    fun drawStickers(files: Map<String, ByteArray>) { stickerFiles = files }

    private fun stickerObject(pack: Pack, sticker: Sticker): JsonObject {
        val size = stickerFiles["${pack.id}/${sticker.id}"]?.size ?: 1
        val animated = sticker.shape == "lottie"
        val digest = "0".repeat(56) + String.format("%08x", ("${pack.id}/${sticker.id}".hashCode() and 0x7FFFFFFF) % 0xFFFFFFF)
        return obj(
            "packId" to pack.id, "id" to sticker.id, "emoji" to listOf(sticker.emoji), "format" to if (animated) "tgs" else "webp",
            "width" to if (animated) 512 else 256, "height" to if (animated) 512 else 256, "size" to size, "sha256" to digest,
        )
    }

    private fun packObject(pack: Pack) = obj(
        "id" to pack.id, "title" to pack.title, "kind" to pack.kind, "thumbnail" to pack.stickers[0].id,
        "stickerCount" to pack.stickers.size, "version" to "1",
    )

    private fun body(bytes: ByteArray?): JsonObject =
        bytes?.let { runCatching { Json.parseToJsonElement(String(it)).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())

    private fun t(x: Double, y: Double, scale: Double = 1.0, rotation: Double = 0.0) = obj("x" to x, "y" to y, "scale" to scale, "rotation" to rotation)

    private fun frame(id: String, width: Int, height: Int, background: List<String>, overlays: List<JsonObject>) = obj(
        "id" to id, "kind" to "image", "width" to width, "height" to height, "thumbhash" to null, "durationMs" to null,
        "asset" to obj("type" to "chat_media", "agentId" to "a1", "toolCallId" to "t1", "index" to 0), "available" to true,
        "placement" to t(0.5, 0.5), "background" to background, "overlays" to overlays,
    )

    private fun text(id: String, string: String, fill: String, color: String, font: String = "sanFrancisco", transform: JsonObject) = obj(
        "id" to id, "type" to "text", "text" to string,
        "style" to obj("font" to font, "fill" to fill, "color" to color, "align" to "center", "width" to 0.8), "transform" to transform,
    )

    private fun story(id: String, author: String?, minutesAgo: Long, frames: List<JsonObject>, caption: String?, reactions: List<JsonObject> = emptyList(), viewCount: Int? = null): MutableMap<String, JsonElement> {
        val posted = epoch - minutesAgo * 60_000
        return obj(
            "id" to id, "status" to "posted", "authorUserId" to author, "createdBy" to obj("kind" to "user", "userId" to author),
            "source" to if (author == null) obj("agentId" to "a1", "toolCallId" to "t1") else null, "caption" to caption, "frames" to frames,
            "audience" to "team", "artifactVersion" to 1, "createdAt" to posted, "updatedAt" to posted, "postedAt" to posted,
            "expiresAt" to posted + 86_400_000, "seen" to (author == null), "viewCount" to viewCount, "myReaction" to null,
            "reactions" to reactions, "version" to "0190-$id",
        ).toMutableMap()
    }

    init {
        stories["m1"] = story("m1", null, 50, listOf(
            frame("f-m1a", 720, 1280, listOf("#202040", "#104050"), listOf(
                text("o1", "Shipped the viewer", "filled", "#FFFFFF", transform = t(0.5, 0.2, 1.2)),
                obj("id" to "o2", "type" to "link", "url" to "https://happy.engineering", "label" to null, "style" to "white", "transform" to t(0.5, 0.82)),
            )),
            frame("f-m1b", 1280, 720, listOf("#F08030", "#F03080"), listOf(
                obj("id" to "o3", "type" to "emoji", "emoji" to "🚀", "transform" to t(0.25, 0.25, 0.25, -0.3)),
            )),
        ), "Two frames of mine", viewCount = 6)
        stories["s1"] = story("s1", "u2", 120, listOf(
            frame("f-s1", 720, 1280, listOf("#108040", "#1040A0"), listOf(
                text("o4", "React to this", "regular", "#FFFFFF", "typewriter", t(0.5, 0.25, 1.3)),
                obj("id" to "o5", "type" to "reaction", "emoji" to "🔥", "transform" to t(0.7, 0.6, 0.28)),
            )),
        ), null, reactions = listOf(obj("emoji" to "🔥", "count" to 3)))
        stories["s2"] = story("s2", "u2", 90, listOf(
            frame("f-s2", 720, 1280, listOf("#602080", "#A02020"), listOf(
                text("o6", "Second from Steve", "semi", "#000000", "georgia", t(0.5, 0.3, 1.1, 0.1)),
            )),
        ), "Late night at the studio, finally got the last of the boulder problems set for the weekend comp. Six new routes on the overhang, two slabs that will humble everyone, and a dyno I am weirdly proud of. Huge thanks to Maya for holding the ladder for three hours straight and to whoever left the pizza in the break room. Doors open at nine, bring chalk, bring friends, and please stretch first this time.")
        stories["a1"] = story("a1", "u3", 30, listOf(
            frame("f-a1", 720, 1280, listOf("#C0A010", "#604020"), listOf(
                text("o7", "Ana's story", "stroke", "#2040FF", "noteworthy", t(0.5, 0.3, 1.4)),
            )),
        ), null)
        if (newer) {
            stories["a2"] = story("a2", "u3", -1, listOf(
                frame("f-a1", 720, 1280, listOf("#C0A010", "#604020"), listOf(
                    text("o9", "Ana's newer one", "stroke", "#2040FF", "noteworthy", t(0.5, 0.3, 1.4)),
                )),
            ), null)
            order.getValue("u3") += "a2"
        }
        // A draft of the person's, for the story card.
        val draft = story("d1", null, 5, listOf(
            frame("f-d1", 720, 1280, listOf("#203040", "#405060"), listOf(
                text("o8", "A draft", "filled", "#FFFFFF", transform = t(0.5, 0.25, 1.2)),
            )),
        ), "Drafted in a conversation")
        for (key in listOf("postedAt", "expiresAt", "viewCount")) draft[key] = JsonNull
        draft["status"] = JsonPrimitive("draft")
        draft["seen"] = JsonPrimitive(false)
        stories["d1"] = draft
    }

    private fun String.field(story: Map<String, JsonElement>) = (story[this] as? JsonPrimitive)

    /** Another of Ana's stories, posted now and unseen: the `story.posted` event that brings it. */
    suspend fun postNew(): JsonObject? = lock.withLock {
        val base = stories["a1"] ?: return@withLock null
        val now = System.currentTimeMillis()
        revision += 1
        val id = "new-$revision"
        val story = base.toMutableMap()
        story["id"] = JsonPrimitive(id)
        for (key in listOf("postedAt", "createdAt", "updatedAt")) story[key] = JsonPrimitive(now)
        story["expiresAt"] = JsonPrimitive(now + 86_400_000)
        story["seen"] = JsonPrimitive(false)
        story["version"] = JsonPrimitive("v$revision")
        stories[id] = story
        order.getValue("u3") += id
        obj("type" to "story.posted", "payload" to obj("story" to JsonObject(story)))
    }

    private fun author(key: String): JsonObject {
        val names = mapOf("self" to "Kirill Dubovitskiy", "u2" to "Steve Korshakov", "u3" to "Ana Lima")
        val ids = order[key] ?: emptyList()
        val unseen = if (key == "self") 0 else ids.count { stories[it]?.let { s -> "seen".field(s)?.booleanOrNull } == false }
        val latest = ids.mapNotNull { stories[it]?.let { s -> "postedAt".field(s)?.longOrNull } }.maxOrNull()
        return obj(
            "userId" to if (key == "self") null else key, "name" to (names[key] ?: key), "photo" to null, "thumbhash" to null,
            "isSelf" to (key == "self"), "storyIds" to ids, "unseenCount" to unseen, "draftCount" to if (key == "self") 0 else null,
            "latestPostedAt" to latest,
        )
    }

    /** A frame as the daemon keeps one sent by hand: its upload becomes a file of the story's artifact. */
    private fun made(input: JsonObject, storyId: String, kept: JsonObject?): JsonObject? {
        val id = (input["id"] as? JsonPrimitive)?.content ?: "f$revision"
        val frame = (kept ?: obj(
            "id" to id, "kind" to "image", "width" to null, "height" to null, "thumbhash" to null, "durationMs" to null, "available" to true,
            "placement" to t(0.5, 0.5), "background" to listOf("#203040", "#405060"), "overlays" to emptyList<Any>(),
        )).toMutableMap()
        val upload = input["upload"] as? JsonObject
        val uploadId = (upload?.get("uploadId") as? JsonPrimitive)?.content
        val data = uploadId?.let { uploads.remove(it) }
        when {
            data != null -> {
                frames = frames + (id to data)
                // As the daemon keeps an upload: its kind from its media type, under media/<frameId> with its extension.
                val name = (upload["name"] as? JsonPrimitive)?.content ?: "$id.png"
                val ext = name.substringAfterLast('.', "png").lowercase()
                val type = MEDIA_TYPES[ext] ?: "application/octet-stream"
                frameTypes[id] = type
                if (type.startsWith("video/")) {
                    // The daemon doesn't measure videos yet: the client takes their size and length from the player.
                    frame["kind"] = JsonPrimitive("video")
                    frame["width"] = JsonNull
                    frame["height"] = JsonNull
                    frame["durationMs"] = JsonNull
                    frame["background"] = JsonArray(listOf(JsonPrimitive("#000000"), JsonPrimitive("#000000")))
                } else {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                    frame["kind"] = JsonPrimitive("image")
                    frame["width"] = if (bounds.outWidth > 0) JsonPrimitive(bounds.outWidth) else JsonNull
                    frame["height"] = if (bounds.outHeight > 0) JsonPrimitive(bounds.outHeight) else JsonNull
                }
                frame["asset"] = obj("type" to "artifact_file", "artifactId" to storyId, "version" to revision, "path" to "media/$id.$ext")
            }
            input["asset"] != null -> frame["asset"] = input.getValue("asset")
            kept == null -> return null
        }
        for (key in listOf("placement", "background", "overlays")) input[key]?.let { frame[key] = it }
        return JsonObject(frame)
    }

    override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
        delay(60)
        val route = path.substringBefore('?')
        // Pictures take a while, as over a slow link.
        if (route.contains("/frames/") && slowFrames) delay(1500)
        return lock.withLock { answer(method, path, route, body, headers) }
    }

    private fun answer(method: String, path: String, route: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
        val parts = route.split('/').filter { it.isNotEmpty() }.let { listOf("") + it }
        when {
            method == "GET" && route == "/v0/health" -> return ok(obj("ready" to true, "capabilities" to obj("stories" to true, "stickers" to true)))
            method == "GET" && route.startsWith("/v0/user-settings/") -> return ok(obj("setting" to obj("key" to STORIES_SETTING_KEY, "value" to true)))
            method == "GET" && route == "/v0/stories/feed" -> {
                val keys = listOf("self", "u2", "u3")
                return ok(obj("authors" to keys.map(::author), "stories" to stories.mapValues { JsonObject(it.value) }, "cursor" to "c1"))
            }
            method == "GET" && route == "/v0/stickers/recents" -> return ok(obj("recents" to recents, "cursor" to "c1"))
            method == "POST" && route == "/v0/stickers/recents" -> {
                val b = body(body)
                val ref = obj("packId" to ((b["packId"] as? JsonPrimitive)?.content ?: ""), "stickerId" to ((b["stickerId"] as? JsonPrimitive)?.content ?: ""))
                recents.removeAll { it == ref }
                recents.add(0, ref)
                return ok(obj("recents" to recents, "cursor" to "c1"))
            }
            method == "DELETE" && route == "/v0/stickers/recents" -> {
                recents.clear()
                return ok(obj("recents" to recents, "cursor" to "c1"))
            }
            method == "GET" && route == "/v0/stickers/packs" -> return ok(obj("packs" to packs.map(::packObject)))
            method == "GET" && route == "/v0/stickers/search" -> {
                val query = path.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
                    it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                }
                val emoji = query["emoji"]
                val q = query["q"]?.lowercase()
                val kind = query["kind"] ?: "stickers"
                val found = packs.filter { it.kind == kind }.flatMap { pack ->
                    pack.stickers.filter { sticker ->
                        when {
                            emoji != null -> sticker.emoji == emoji
                            q != null -> pack.title.lowercase().startsWith(q)
                            else -> false
                        }
                    }.map { stickerObject(pack, it) }
                }
                return ok(obj("stickers" to found))
            }
            method == "POST" && route == "/v0/artifact-uploads" -> {
                revision += 1
                val id = "u$revision"
                uploads[id] = body ?: ByteArray(0)
                return ok(obj("upload" to obj("id" to id, "size" to (body?.size ?: 0), "sha256" to "", "createdAt" to 0, "expiresAt" to 0)), status = 201)
            }
            method == "POST" && route == "/v0/stories" -> {
                val b = body(body)
                val id = (b["id"] as? JsonPrimitive)?.content ?: "made$revision"
                stories[id]?.let { return ok(obj("story" to JsonObject(it)), status = 201) }
                revision += 1
                val made = (b["frames"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { made(it.jsonObject, id, null) }
                val now = System.currentTimeMillis()
                val story = obj(
                    "id" to id, "status" to "draft", "authorUserId" to null, "createdBy" to obj("kind" to "user", "userId" to null), "source" to null,
                    "caption" to (b["caption"] ?: JsonNull), "frames" to made, "audience" to "self", "artifactVersion" to 1, "createdAt" to now,
                    "updatedAt" to now, "postedAt" to null, "expiresAt" to null, "seen" to false, "viewCount" to null, "myReaction" to null,
                    "reactions" to emptyList<Any>(), "version" to "v$revision",
                )
                stories[id] = story.toMutableMap()
                return ok(obj("story" to story), status = 201)
            }
            method == "GET" && route == "/v0/stories" -> {
                val drafts = stories.values.filter { "status".field(it)?.content == "draft" }.map { JsonObject(it) }
                return ok(obj("stories" to drafts, "nextCursor" to null, "cursor" to "c1"))
            }
        }
        if (parts.size >= 4 && parts[1] == "v0" && parts[2] == "stickers" && parts[3] == "packs" && parts.size >= 5) {
            val pack = packs.firstOrNull { it.id == parts[4] } ?: return status(404)
            if (parts.size == 5) return ok(obj("pack" to packObject(pack), "stickers" to pack.stickers.map { stickerObject(pack, it) }))
            if (parts.size == 8) {
                val sticker = pack.stickers.firstOrNull { it.id == parts[6] } ?: return status(404)
                val data = stickerFiles["${pack.id}/${sticker.id}"] ?: return status(404)
                // A tgs is only ever served here as its Lottie, which the app must ask for.
                if (sticker.shape == "lottie") {
                    if (!path.contains("as=lottie")) return status(406)
                    return DirectHttpResponse(200, mapOf("content-type" to "application/json"), data)
                }
                return DirectHttpResponse(200, mapOf("content-type" to "image/webp"), data)
            }
            return status(404)
        }
        if (parts.size < 4 || parts[1] != "v0" || parts[2] != "stories") return status(404)
        val id = parts[3]
        val story = stories[id] ?: return status(404)
        val sub = parts.getOrNull(4) ?: ""
        when {
            method == "GET" && sub == "" -> return ok(obj("story" to JsonObject(story)))
            method == "GET" && sub == "frames" -> {
                val frameId = parts.getOrNull(5) ?: return status(404)
                val data = frames[frameId] ?: return status(404)
                return served(data, frameTypes[frameId] ?: "image/png", headers)
            }
            method == "GET" && sub == "viewers" -> {
                // As the API orders them: reactors by latest reaction, then the rest by name.
                val now = System.currentTimeMillis()
                fun viewer(id: String, name: String, reaction: String? = null, minutesAgo: Long = 0) = obj(
                    "userId" to id, "name" to name, "photo" to null, "thumbhash" to null,
                    "reaction" to reaction, "reactedAt" to if (reaction == null) null else now - minutesAgo * 60_000,
                )
                return ok(obj("viewers" to listOf(
                    viewer("u2", "Steve Korshakov", "❤️", 2), viewer("u4", "Maya Okafor", "🔥", 14), viewer("u5", "Leo Brandt", "👍", 1_700),
                    viewer("u3", "Ana Lima"), viewer("u6", "Jonas Weber"), viewer("u7", "Priya Raman"),
                ), "nextCursor" to null))
            }
            method == "POST" && sub == "seen" -> {
                // Seen through this one: every earlier story of the author too.
                val author = "authorUserId".field(story)?.takeIf { it.isString }?.content
                val mark = "postedAt".field(story)?.longOrNull ?: 0
                for ((_, value) in stories) {
                    val other = "authorUserId".field(value)?.takeIf { it.isString }?.content
                    if (other == author && ("postedAt".field(value)?.longOrNull ?: 0) <= mark) value["seen"] = JsonPrimitive(true)
                }
                return ok(obj("story" to JsonObject(stories[id] ?: story)))
            }
            (method == "PUT" || method == "DELETE") && sub == "reaction" -> {
                val emoji = if (method == "PUT") (body(body)["emoji"] as? JsonPrimitive)?.content else null
                val old = "myReaction".field(story)?.takeIf { it.isString }?.content
                val counts = ((story["reactions"] as? JsonArray) ?: JsonArray(emptyList())).map {
                    val o = it.jsonObject
                    ((o["emoji"] as JsonPrimitive).content) to ((o["count"] as JsonPrimitive).intOrNull ?: 0)
                }.toMutableList()
                if (old != null) counts.indexOfFirst { it.first == old }.takeIf { it >= 0 }?.let { counts[it] = counts[it].first to counts[it].second - 1 }
                if (emoji != null) {
                    val index = counts.indexOfFirst { it.first == emoji }
                    if (index >= 0) counts[index] = emoji to counts[index].second + 1 else counts += emoji to 1
                }
                story["reactions"] = json(counts.filter { it.second > 0 }.sortedByDescending { it.second }.map { obj("emoji" to it.first, "count" to it.second) })
                story["myReaction"] = json(emoji)
                story["seen"] = JsonPrimitive(true)
                return ok(obj("story" to JsonObject(story)))
            }
            method == "POST" && sub == "delete" -> {
                story["status"] = JsonPrimitive("deleted")
                stories.remove(id)
                return ok(obj("story" to JsonObject(story)))
            }
            method == "PATCH" && sub == "" -> {
                if (headers["If-Match"] != "version".field(story)?.content) return status(409)
                val b = body(body)
                revision += 1
                b["caption"]?.let { story["caption"] = it }
                (b["frames"] as? JsonArray)?.let { inputs ->
                    val held = ((story["frames"] as? JsonArray) ?: JsonArray(emptyList())).associateBy { (it.jsonObject["id"] as JsonPrimitive).content }
                    story["frames"] = JsonArray(inputs.mapNotNull { input ->
                        val o = input.jsonObject
                        made(o, id, (o["id"] as? JsonPrimitive)?.content?.let { held[it]?.jsonObject })
                    })
                }
                story["artifactVersion"] = JsonPrimitive(("artifactVersion".field(story)?.intOrNull ?: 1) + 1)
                story["version"] = JsonPrimitive("v$revision")
                return ok(obj("story" to JsonObject(story)))
            }
            method == "POST" && sub == "post" -> {
                val wanted = (body(body)["artifactVersion"] as? JsonPrimitive)?.intOrNull
                if ("status".field(story)?.content != "draft" || wanted != "artifactVersion".field(story)?.intOrNull) return status(409)
                val now = System.currentTimeMillis()
                revision += 1
                story["status"] = JsonPrimitive("posted")
                story["postedAt"] = JsonPrimitive(now)
                story["expiresAt"] = JsonPrimitive(now + 86_400_000)
                story["seen"] = JsonPrimitive(true)
                story["viewCount"] = JsonPrimitive(0)
                story["version"] = JsonPrimitive("v$revision")
                order.getValue("self") += id
                return ok(obj("story" to JsonObject(story)))
            }
        }
        return status(404)
    }
}
