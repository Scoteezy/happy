package com.slopus.happy.stories

import com.slopus.happy.direct.Wire
import com.slopus.happy.direct.WireException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * The Happy Agent API's Stories and Stickers shapes (happy-agent `API.md`
 * "Stories" and "Stickers"), ported field for field from happy-ios
 * `StoriesWire.swift`, itself from `@slopus/happy-agent-client`'s
 * `protocol/stories.ts` and `protocol/stickers.ts`. Fields the TypeScript
 * marks `Nullable` are always written, as `null` when empty; optional ones
 * are left out. An overlay of a type this app does not know is kept as
 * sent, so an edit sends it back. `StoriesWireTest` pins the round trip
 * against the TypeScript schemas.
 */
object StoryCanvas {
    const val WIDTH = 1080.0
    const val HEIGHT = 1920.0
    const val MAX_FRAMES = 10
    const val MAX_OVERLAYS = 32
    const val MAX_CAPTION = 1_024
    const val IMAGE_DURATION_MS = 5_000
    const val MAX_VIDEO_DURATION_MS = 60_000
    const val LIFETIME_MS: Long = 24L * 60 * 60 * 1000
}

/** A wire enum: its JSON string and the entry it names. */
interface WireName {
    val wire: String
}

internal inline fun <reified E> wireEnum(value: String?): E where E : Enum<E>, E : WireName =
    enumValues<E>().firstOrNull { it.wire == value } ?: throw WireException("unknown ${E::class.simpleName}: $value")

enum class StoryStatus(override val wire: String) : WireName { Draft("draft"), Posted("posted"), Deleted("deleted") }

/** The media a frame shows, by identity. */
sealed class StoryAsset {
    data class ChatMedia(val agentId: String, val toolCallId: String, val index: Int) : StoryAsset()
    data class ArtifactFile(val artifactId: String, val version: Int, val path: String) : StoryAsset()

    fun json(): JsonObject = when (this) {
        is ChatMedia -> Wire.objectOf("type" to JsonPrimitive("chat_media"), "agentId" to JsonPrimitive(agentId), "toolCallId" to JsonPrimitive(toolCallId), "index" to JsonPrimitive(index))
        is ArtifactFile -> Wire.objectOf("type" to JsonPrimitive("artifact_file"), "artifactId" to JsonPrimitive(artifactId), "version" to JsonPrimitive(version), "path" to JsonPrimitive(path))
    }

    companion object {
        fun from(json: JsonElement): StoryAsset {
            val o = Wire.obj(json)
            return when (Wire.string(o, "type")) {
                "chat_media" -> ChatMedia(Wire.string(o, "agentId"), Wire.string(o, "toolCallId"), Wire.int(o, "index"))
                "artifact_file" -> ArtifactFile(Wire.string(o, "artifactId"), Wire.int(o, "version"), Wire.string(o, "path"))
                else -> throw WireException("unknown asset")
            }
        }
    }
}

/**
 * A centre as fractions of the canvas, a size whose meaning depends on what
 * it places, and clockwise rotation in radians.
 */
data class StoryTransform(val x: Double, val y: Double, val scale: Double, val rotation: Double) {
    /** Inside the ranges the API keeps. */
    val clamped: StoryTransform
        get() = StoryTransform(min(1.5, max(-0.5, x)), min(1.5, max(-0.5, y)), min(10.0, max(0.05, scale)), min(PI, max(-PI, rotation)))

    fun json(): JsonObject = Wire.objectOf("x" to Wire.number(x), "y" to Wire.number(y), "scale" to Wire.number(scale), "rotation" to Wire.number(rotation))

    companion object {
        val FITTED = StoryTransform(0.5, 0.5, 1.0, 0.0)

        fun from(json: JsonElement?): StoryTransform {
            val o = Wire.obj(json)
            return StoryTransform(Wire.double(o, "x"), Wire.double(o, "y"), Wire.double(o, "scale"), Wire.double(o, "rotation"))
        }
    }
}

enum class StoryTextFont(override val wire: String) : WireName {
    SanFrancisco("sanFrancisco"), Typewriter("typewriter"), AvenirNext("avenirNext"), CourierNew("courierNew"),
    Noteworthy("noteworthy"), Georgia("georgia"), Papyrus("papyrus"), SnellRoundhand("snellRoundhand"),
}

enum class StoryTextFill(override val wire: String) : WireName { Regular("regular"), Filled("filled"), Semi("semi"), Stroke("stroke"), Blur("blur") }

enum class StoryTextAlign(override val wire: String) : WireName { Left("left"), Center("center"), Right("right") }

enum class StoryLinkStyle(override val wire: String) : WireName { White("white"), Black("black"), Transparent("transparent"), Blur("blur"), Custom("custom") }

data class StoryTextStyle(
    val font: StoryTextFont = StoryTextFont.SanFrancisco,
    val fill: StoryTextFill = StoryTextFill.Regular,
    val color: String = "#FFFFFF",
    val align: StoryTextAlign = StoryTextAlign.Center,
    /** The fraction of the canvas's width the text wraps at before scaling. */
    val width: Double = 0.8,
) {
    fun json(): JsonObject = Wire.objectOf(
        "font" to JsonPrimitive(font.wire), "fill" to JsonPrimitive(fill.wire), "color" to JsonPrimitive(color),
        "align" to JsonPrimitive(align.wire), "width" to Wire.number(width),
    )

    companion object {
        fun from(json: JsonElement?): StoryTextStyle {
            val o = Wire.obj(json)
            return StoryTextStyle(
                wireEnum(Wire.string(o, "font")), wireEnum(Wire.string(o, "fill")), Wire.string(o, "color"),
                wireEnum(Wire.string(o, "align")), Wire.double(o, "width"),
            )
        }
    }
}

/** Drawn live over a frame, the last on top. */
sealed class StoryOverlay {
    data class Text(override val id: String, val text: String, val style: StoryTextStyle, override val transform: StoryTransform) : StoryOverlay()

    /** `label` null shows the URL's host; `color` only with `Custom`. */
    data class Link(override val id: String, val url: String, val label: String?, val style: StoryLinkStyle, val color: String?, override val transform: StoryTransform) : StoryOverlay()
    data class Sticker(override val id: String, val packId: String, val stickerId: String, override val transform: StoryTransform) : StoryOverlay()
    data class Emoji(override val id: String, val emoji: String, override val transform: StoryTransform) : StoryOverlay()
    data class Reaction(override val id: String, val emoji: String, val dark: Boolean?, val flipped: Boolean?, override val transform: StoryTransform) : StoryOverlay()

    /** A type this app does not know, kept as sent and never drawn. */
    data class Unknown(val raw: JsonObject) : StoryOverlay() {
        override val id: String? get() = (raw["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        override val transform: StoryTransform? get() = null
    }

    /** Its ID; null for a type this app does not know. */
    abstract val id: String?
    abstract val transform: StoryTransform?

    /** The same overlay moved to `t`; one of an unknown type stays as it is. */
    fun withTransform(t: StoryTransform): StoryOverlay = when (this) {
        is Text -> copy(transform = t)
        is Link -> copy(transform = t)
        is Sticker -> copy(transform = t)
        is Emoji -> copy(transform = t)
        is Reaction -> copy(transform = t)
        is Unknown -> this
    }

    fun json(): JsonObject = when (this) {
        is Text -> Wire.objectOf("id" to JsonPrimitive(id), "type" to JsonPrimitive("text"), "text" to JsonPrimitive(text), "style" to style.json(), "transform" to transform.json())
        is Link -> Wire.objectOf(
            "id" to JsonPrimitive(id), "type" to JsonPrimitive("link"), "url" to JsonPrimitive(url), "label" to Wire.string(label),
            "style" to JsonPrimitive(style.wire), "color" to color?.let(::JsonPrimitive), "transform" to transform.json(),
        )
        is Sticker -> Wire.objectOf("id" to JsonPrimitive(id), "type" to JsonPrimitive("sticker"), "packId" to JsonPrimitive(packId), "stickerId" to JsonPrimitive(stickerId), "transform" to transform.json())
        is Emoji -> Wire.objectOf("id" to JsonPrimitive(id), "type" to JsonPrimitive("emoji"), "emoji" to JsonPrimitive(emoji), "transform" to transform.json())
        is Reaction -> Wire.objectOf(
            "id" to JsonPrimitive(id), "type" to JsonPrimitive("reaction"), "emoji" to JsonPrimitive(emoji),
            "dark" to dark?.let(::JsonPrimitive), "flipped" to flipped?.let(::JsonPrimitive), "transform" to transform.json(),
        )
        is Unknown -> raw
    }

    companion object {
        fun from(json: JsonElement): StoryOverlay {
            val o = Wire.obj(json)
            return when (Wire.string(o, "type")) {
                "text" -> Text(Wire.string(o, "id"), Wire.string(o, "text"), StoryTextStyle.from(o["style"]), StoryTransform.from(o["transform"]))
                "link" -> Link(Wire.string(o, "id"), Wire.string(o, "url"), Wire.stringOrNull(o, "label"), wireEnum(Wire.string(o, "style")), Wire.stringOrNull(o, "color"), StoryTransform.from(o["transform"]))
                "sticker" -> Sticker(Wire.string(o, "id"), Wire.string(o, "packId"), Wire.string(o, "stickerId"), StoryTransform.from(o["transform"]))
                "emoji" -> Emoji(Wire.string(o, "id"), Wire.string(o, "emoji"), StoryTransform.from(o["transform"]))
                "reaction" -> Reaction(Wire.string(o, "id"), Wire.string(o, "emoji"), Wire.boolOrNull(o, "dark"), Wire.boolOrNull(o, "flipped"), StoryTransform.from(o["transform"]))
                else -> Unknown(o)
            }
        }
    }
}

data class StoryFrame(
    val id: String,
    val kind: Kind,
    /** Pixels after EXIF orientation; null for a video not yet measured. */
    val width: Int?,
    val height: Int?,
    /** Pixels per point; absent means 1. */
    val scale: Double? = null,
    val thumbhash: String?,
    val durationMs: Int?,
    val asset: StoryAsset,
    val available: Boolean,
    val placement: StoryTransform = StoryTransform.FITTED,
    /** Two `#RRGGBB` colours, top to bottom. */
    val background: List<String> = listOf("#000000", "#000000"),
    val overlays: List<StoryOverlay> = emptyList(),
) {
    enum class Kind(override val wire: String) : WireName { Image("image"), Video("video") }

    /**
     * How long the viewer shows it: five seconds for an image, a video's
     * length up to a minute (five seconds while that is unknown).
     */
    val showMs: Int
        get() {
            val duration = durationMs
            if (kind != Kind.Video || duration == null || duration <= 0) return StoryCanvas.IMAGE_DURATION_MS
            return min(duration, StoryCanvas.MAX_VIDEO_DURATION_MS)
        }

    fun json(): JsonObject = Wire.objectOf(
        "id" to JsonPrimitive(id), "kind" to JsonPrimitive(kind.wire), "width" to Wire.number(width), "height" to Wire.number(height),
        "scale" to scale?.let(Wire::number), "thumbhash" to Wire.string(thumbhash), "durationMs" to Wire.number(durationMs),
        "asset" to asset.json(), "available" to JsonPrimitive(available), "placement" to placement.json(),
        "background" to Wire.strings(background), "overlays" to JsonArray(overlays.map { it.json() }),
    )

    companion object {
        fun from(json: JsonElement): StoryFrame {
            val o = Wire.obj(json)
            return StoryFrame(
                id = Wire.string(o, "id"), kind = wireEnum(Wire.string(o, "kind")),
                width = Wire.intOrNull(o, "width"), height = Wire.intOrNull(o, "height"), scale = Wire.doubleOrNull(o, "scale"),
                thumbhash = Wire.stringOrNull(o, "thumbhash"), durationMs = Wire.intOrNull(o, "durationMs"),
                asset = StoryAsset.from(o["asset"] ?: JsonNull), available = Wire.bool(o, "available"),
                placement = StoryTransform.from(o["placement"]), background = Wire.strings(o, "background"),
                overlays = Wire.list(o, "overlays", StoryOverlay::from),
            )
        }
    }
}

sealed class StoryCreator {
    data class User(val userId: String?) : StoryCreator()
    data class Agent(val agentId: String) : StoryCreator()

    fun json(): JsonObject = when (this) {
        is User -> Wire.objectOf("kind" to JsonPrimitive("user"), "userId" to Wire.string(userId))
        is Agent -> Wire.objectOf("kind" to JsonPrimitive("agent"), "agentId" to JsonPrimitive(agentId))
    }

    companion object {
        fun from(json: JsonElement?): StoryCreator {
            val o = Wire.obj(json)
            return when (Wire.string(o, "kind")) {
                "agent" -> Agent(Wire.string(o, "agentId"))
                else -> User(Wire.stringOrNull(o, "userId"))
            }
        }
    }
}

data class StoryReactionCount(val emoji: String, val count: Int) {
    fun json(): JsonObject = Wire.objectOf("emoji" to JsonPrimitive(emoji), "count" to JsonPrimitive(count))

    companion object {
        fun from(json: JsonElement): StoryReactionCount {
            val o = Wire.obj(json)
            return StoryReactionCount(Wire.string(o, "emoji"), Wire.int(o, "count"))
        }
    }
}

data class StorySource(val agentId: String, val toolCallId: String) {
    fun json(): JsonObject = Wire.objectOf("agentId" to JsonPrimitive(agentId), "toolCallId" to JsonPrimitive(toolCallId))

    companion object {
        fun from(json: JsonElement): StorySource {
            val o = Wire.obj(json)
            return StorySource(Wire.string(o, "agentId"), Wire.string(o, "toolCallId"))
        }
    }
}

/** The story object, from the caller's view. */
data class Story(
    val id: String,
    val status: StoryStatus,
    val authorUserId: String?,
    val createdBy: StoryCreator,
    val source: StorySource? = null,
    val caption: String?,
    val frames: List<StoryFrame>,
    val audience: String = "self",
    val artifactVersion: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val postedAt: Long?,
    val expiresAt: Long?,
    val seen: Boolean,
    val viewCount: Int?,
    val myReaction: String?,
    val reactions: List<StoryReactionCount>,
    val version: String,
) {
    /** Whether the person may react: a posted story of someone else's. */
    fun reactable(isOwn: Boolean): Boolean = status == StoryStatus.Posted && !isOwn

    fun json(): JsonObject = Wire.objectOf(
        "id" to JsonPrimitive(id), "status" to JsonPrimitive(status.wire), "authorUserId" to Wire.string(authorUserId),
        "createdBy" to createdBy.json(), "source" to (source?.json() ?: JsonNull), "caption" to Wire.string(caption),
        "frames" to JsonArray(frames.map { it.json() }), "audience" to JsonPrimitive(audience),
        "artifactVersion" to JsonPrimitive(artifactVersion), "createdAt" to JsonPrimitive(createdAt), "updatedAt" to JsonPrimitive(updatedAt),
        "postedAt" to Wire.number(postedAt), "expiresAt" to Wire.number(expiresAt), "seen" to JsonPrimitive(seen),
        "viewCount" to Wire.number(viewCount), "myReaction" to Wire.string(myReaction),
        "reactions" to JsonArray(reactions.map { it.json() }), "version" to JsonPrimitive(version),
    )

    companion object {
        fun from(json: JsonElement): Story {
            val o = Wire.obj(json)
            return Story(
                id = Wire.string(o, "id"), status = wireEnum(Wire.string(o, "status")), authorUserId = Wire.stringOrNull(o, "authorUserId"),
                createdBy = StoryCreator.from(o["createdBy"]), source = o["source"]?.takeIf { it != JsonNull }?.let(StorySource::from),
                caption = Wire.stringOrNull(o, "caption"), frames = Wire.list(o, "frames", StoryFrame::from), audience = Wire.string(o, "audience"),
                artifactVersion = Wire.int(o, "artifactVersion"), createdAt = Wire.long(o, "createdAt"), updatedAt = Wire.long(o, "updatedAt"),
                postedAt = Wire.longOrNull(o, "postedAt"), expiresAt = Wire.longOrNull(o, "expiresAt"), seen = Wire.bool(o, "seen"),
                viewCount = Wire.intOrNull(o, "viewCount"), myReaction = Wire.stringOrNull(o, "myReaction"),
                reactions = Wire.list(o, "reactions", StoryReactionCount::from), version = Wire.string(o, "version"),
            )
        }
    }
}

/** One circle of the stories row. */
data class StoryFeedAuthor(
    val userId: String?,
    val name: String,
    val photo: String?,
    val thumbhash: String?,
    val isSelf: Boolean,
    /** Posted, unexpired stories, oldest first. */
    val storyIds: List<String>,
    val unseenCount: Int,
    val draftCount: Int?,
    val latestPostedAt: Long?,
) {
    /** Its circle's key in the row: the user, or the one person. */
    val key: String get() = if (isSelf) "self" else (userId ?: "self")

    fun json(): JsonObject = Wire.objectOf(
        "userId" to Wire.string(userId), "name" to JsonPrimitive(name), "photo" to Wire.string(photo), "thumbhash" to Wire.string(thumbhash),
        "isSelf" to JsonPrimitive(isSelf), "storyIds" to Wire.strings(storyIds), "unseenCount" to JsonPrimitive(unseenCount),
        "draftCount" to Wire.number(draftCount), "latestPostedAt" to Wire.number(latestPostedAt),
    )

    companion object {
        fun from(json: JsonElement): StoryFeedAuthor {
            val o = Wire.obj(json)
            return StoryFeedAuthor(
                Wire.stringOrNull(o, "userId"), Wire.string(o, "name"), Wire.stringOrNull(o, "photo"), Wire.stringOrNull(o, "thumbhash"),
                Wire.bool(o, "isSelf"), Wire.strings(o, "storyIds"), Wire.int(o, "unseenCount"), Wire.intOrNull(o, "draftCount"),
                Wire.longOrNull(o, "latestPostedAt"),
            )
        }
    }
}

/** `GET /v0/stories/feed`: the row in the order it is shown. */
data class StoryFeed(val authors: List<StoryFeedAuthor>, val stories: Map<String, Story>, val cursor: String) {
    fun json(): JsonObject = Wire.objectOf(
        "authors" to JsonArray(authors.map { it.json() }),
        "stories" to JsonObject(stories.mapValues { it.value.json() }),
        "cursor" to JsonPrimitive(cursor),
    )

    companion object {
        fun from(json: JsonElement): StoryFeed {
            val o = Wire.obj(json)
            return StoryFeed(
                Wire.list(o, "authors", StoryFeedAuthor::from),
                Wire.obj(o["stories"]).mapValues { Story.from(it.value) },
                Wire.string(o, "cursor"),
            )
        }
    }
}

data class StoriesPage(val stories: List<Story>, val nextCursor: String?, val cursor: String) {
    fun json(): JsonObject = Wire.objectOf("stories" to JsonArray(stories.map { it.json() }), "nextCursor" to Wire.string(nextCursor), "cursor" to JsonPrimitive(cursor))

    companion object {
        fun from(json: JsonElement): StoriesPage {
            val o = Wire.obj(json)
            return StoriesPage(Wire.list(o, "stories", Story::from), Wire.stringOrNull(o, "nextCursor"), Wire.string(o, "cursor"))
        }
    }
}

/** One image or video a conversation presented. */
data class StoryMediaItem(
    val asset: StoryAsset,
    val kind: StoryFrame.Kind,
    val name: String,
    val width: Int?,
    val height: Int?,
    val scale: Double?,
    val thumbhash: String?,
    val createdAt: Long,
) {
    fun json(): JsonObject = Wire.objectOf(
        "asset" to asset.json(), "kind" to JsonPrimitive(kind.wire), "name" to JsonPrimitive(name),
        "width" to Wire.number(width), "height" to Wire.number(height), "scale" to scale?.let(Wire::number),
        "thumbhash" to Wire.string(thumbhash), "createdAt" to JsonPrimitive(createdAt),
    )

    companion object {
        fun from(json: JsonElement): StoryMediaItem {
            val o = Wire.obj(json)
            return StoryMediaItem(
                StoryAsset.from(o["asset"] ?: JsonNull), wireEnum(Wire.string(o, "kind")), Wire.string(o, "name"),
                Wire.intOrNull(o, "width"), Wire.intOrNull(o, "height"), Wire.doubleOrNull(o, "scale"),
                Wire.stringOrNull(o, "thumbhash"), Wire.long(o, "createdAt"),
            )
        }
    }
}

data class StoryMediaPage(val items: List<StoryMediaItem>, val nextCursor: String?) {
    fun json(): JsonObject = Wire.objectOf("items" to JsonArray(items.map { it.json() }), "nextCursor" to Wire.string(nextCursor))

    companion object {
        fun from(json: JsonElement): StoryMediaPage {
            val o = Wire.obj(json)
            return StoryMediaPage(Wire.list(o, "items", StoryMediaItem::from), Wire.stringOrNull(o, "nextCursor"))
        }
    }
}

/** One person who saw the author's story. */
data class StoryViewer(
    val userId: String,
    val name: String,
    val photo: String?,
    val thumbhash: String?,
    val reaction: String?,
    val reactedAt: Long?,
) {
    fun json(): JsonObject = Wire.objectOf(
        "userId" to JsonPrimitive(userId), "name" to JsonPrimitive(name), "photo" to Wire.string(photo),
        "thumbhash" to Wire.string(thumbhash), "reaction" to Wire.string(reaction), "reactedAt" to Wire.number(reactedAt),
    )

    companion object {
        fun from(json: JsonElement): StoryViewer {
            val o = Wire.obj(json)
            return StoryViewer(
                Wire.string(o, "userId"), Wire.string(o, "name"), Wire.stringOrNull(o, "photo"), Wire.stringOrNull(o, "thumbhash"),
                Wire.stringOrNull(o, "reaction"), Wire.longOrNull(o, "reactedAt"),
            )
        }
    }
}

data class StoryViewersPage(val viewers: List<StoryViewer>, val nextCursor: String?) {
    fun json(): JsonObject = Wire.objectOf("viewers" to JsonArray(viewers.map { it.json() }), "nextCursor" to Wire.string(nextCursor))

    companion object {
        fun from(json: JsonElement): StoryViewersPage {
            val o = Wire.obj(json)
            return StoryViewersPage(Wire.list(o, "viewers", StoryViewer::from), Wire.stringOrNull(o, "nextCursor"))
        }
    }
}

/**
 * A frame in a write: new media from an upload or an asset, or on `PATCH`
 * an existing frame's `id` alone, keeping its media.
 */
data class StoryFrameInput(
    val id: String?,
    val media: Media,
    val placement: StoryTransform? = null,
    val background: List<String>? = null,
    val overlays: List<StoryOverlay>? = null,
) {
    sealed class Media {
        data class Upload(val uploadId: String, val name: String) : Media()
        data class Asset(val asset: StoryAsset) : Media()
        data object Keep : Media()
    }

    fun json(): JsonObject = Wire.objectOf(
        "id" to id?.let(::JsonPrimitive),
        "upload" to (media as? Media.Upload)?.let { Wire.objectOf("uploadId" to JsonPrimitive(it.uploadId), "name" to JsonPrimitive(it.name)) },
        "asset" to (media as? Media.Asset)?.asset?.json(),
        "placement" to placement?.json(),
        "background" to background?.let(Wire::strings),
        "overlays" to overlays?.let { list -> JsonArray(list.map { it.json() }) },
    )

    companion object {
        /** An existing frame as an edit sends it back: its media kept, the rest as it now is. */
        fun keeping(frame: StoryFrame) = StoryFrameInput(frame.id, Media.Keep, frame.placement, frame.background, frame.overlays)
    }
}

// Stickers

enum class StickerPackKind(override val wire: String) : WireName { Stickers("stickers"), Emoji("emoji") }

enum class StickerFormat(override val wire: String) : WireName { Webp("webp"), Tgs("tgs"), Webm("webm") }

/** The picker's grid: at least `minColumns`, and as many `cellSize` cells as fit. */
object StickerGrid {
    fun columns(kind: StickerPackKind, width: Double, spacing: Double): Int {
        val (minColumns, cell) = if (kind == StickerPackKind.Stickers) 5 to 70.0 else 8 to 40.0
        return max(minColumns, floor((width + spacing) / (cell + spacing)).toInt())
    }
}

data class StickerPack(val id: String, val title: String, val kind: StickerPackKind, val thumbnail: String, val stickerCount: Int, val version: String) {
    fun json(): JsonObject = Wire.objectOf(
        "id" to JsonPrimitive(id), "title" to JsonPrimitive(title), "kind" to JsonPrimitive(kind.wire),
        "thumbnail" to JsonPrimitive(thumbnail), "stickerCount" to JsonPrimitive(stickerCount), "version" to JsonPrimitive(version),
    )

    companion object {
        fun from(json: JsonElement): StickerPack {
            val o = Wire.obj(json)
            return StickerPack(Wire.string(o, "id"), Wire.string(o, "title"), wireEnum(Wire.string(o, "kind")), Wire.string(o, "thumbnail"), Wire.int(o, "stickerCount"), Wire.string(o, "version"))
        }
    }
}

data class Sticker(
    val packId: String,
    val id: String,
    val emoji: List<String>,
    val format: StickerFormat,
    val width: Int,
    val height: Int,
    val size: Int,
    val sha256: String,
) {
    val ref: StickerRef get() = StickerRef(packId, id)

    fun json(): JsonObject = Wire.objectOf(
        "packId" to JsonPrimitive(packId), "id" to JsonPrimitive(id), "emoji" to Wire.strings(emoji), "format" to JsonPrimitive(format.wire),
        "width" to JsonPrimitive(width), "height" to JsonPrimitive(height), "size" to JsonPrimitive(size), "sha256" to JsonPrimitive(sha256),
    )

    companion object {
        fun from(json: JsonElement): Sticker {
            val o = Wire.obj(json)
            return Sticker(
                Wire.string(o, "packId"), Wire.string(o, "id"), Wire.strings(o, "emoji"), wireEnum(Wire.string(o, "format")),
                Wire.int(o, "width"), Wire.int(o, "height"), Wire.int(o, "size"), Wire.string(o, "sha256"),
            )
        }
    }
}

data class StickerRef(val packId: String, val stickerId: String) {
    fun json(): JsonObject = Wire.objectOf("packId" to JsonPrimitive(packId), "stickerId" to JsonPrimitive(stickerId))

    companion object {
        fun from(json: JsonElement): StickerRef {
            val o = Wire.obj(json)
            return StickerRef(Wire.string(o, "packId"), Wire.string(o, "stickerId"))
        }
    }
}

data class StickerPackContents(val pack: StickerPack, val stickers: List<Sticker>) {
    fun json(): JsonObject = Wire.objectOf("pack" to pack.json(), "stickers" to JsonArray(stickers.map { it.json() }))

    companion object {
        fun from(json: JsonElement): StickerPackContents {
            val o = Wire.obj(json)
            return StickerPackContents(StickerPack.from(o["pack"] ?: JsonNull), Wire.list(o, "stickers", Sticker::from))
        }
    }
}

data class StickerRecents(val recents: List<StickerRef>, val cursor: String?) {
    fun json(): JsonObject = Wire.objectOf("recents" to JsonArray(recents.map { it.json() }), "cursor" to cursor?.let(::JsonPrimitive))

    companion object {
        fun from(json: JsonElement): StickerRecents {
            val o = Wire.obj(json)
            return StickerRecents(Wire.list(o, "recents", StickerRef::from), Wire.stringOrNull(o, "cursor"))
        }
    }
}
