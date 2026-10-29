package com.slopus.happy.stories

import com.slopus.happy.direct.ClientId
import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectAgentClient.Companion.escape
import com.slopus.happy.direct.DirectAgentError
import com.slopus.happy.direct.DirectHttpResponse
import com.slopus.happy.direct.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The Stories and Stickers routes (happy-agent `API.md` "Stories",
 * "Stickers"), over the same paired device's tunnel as Stem Cells
 * (happy-ios `StoriesClient.swift`). Each write carries a fresh
 * `mutationId`, so the store knows its own echo.
 */

/** The user setting that turns Stories on; only the JSON `true` does. */
const val STORIES_SETTING_KEY = "happy-desktop.experiments.stories"

private fun storyOf(json: JsonElement): Story = Story.from(Wire.obj(json)["story"] ?: JsonNull)

private fun mutation(): Pair<String, JsonElement> = "mutationId" to JsonPrimitive(ClientId.make())

private fun DirectHttpResponse.story(): Story = try {
    storyOf(DirectAgentClient.parse(body))
} catch (_: Exception) {
    throw DirectAgentError.InvalidResponse
}

// Reading

suspend fun DirectAgentClient.storyFeed(): StoryFeed = decode("GET", "/v0/stories/feed", read = StoryFeed::from)

suspend fun DirectAgentClient.story(storyId: String): Story = decode("GET", "/v0/stories/${escape(storyId)}", read = ::storyOf)

/** The person's own stories, newest first. */
suspend fun DirectAgentClient.ownStories(
    status: List<StoryStatus> = listOf(StoryStatus.Draft, StoryStatus.Posted),
    cursor: String? = null,
    limit: Int = 50,
): StoriesPage {
    var query = "status=${status.joinToString(",") { it.wire }}&limit=$limit"
    if (cursor != null) query += "&cursor=${escape(cursor)}"
    return decode("GET", "/v0/stories?$query", read = StoriesPage::from)
}

/** What one conversation offers to put in a story. */
suspend fun DirectAgentClient.storyMedia(agentId: String, cursor: String? = null, limit: Int = 30): StoryMediaPage {
    var query = "agentId=${escape(agentId)}&limit=$limit"
    if (cursor != null) query += "&cursor=${escape(cursor)}"
    return decode("GET", "/v0/stories/media?$query", read = StoryMediaPage::from)
}

/**
 * Where a conversation's media item is read from, by its asset: the tool
 * call's media or the artifact version's file.
 */
fun storyAssetPath(asset: StoryAsset): String = when (asset) {
    is StoryAsset.ChatMedia -> "/v0/agents/${escape(asset.agentId)}/tool-calls/${escape(asset.toolCallId)}/media/${asset.index}"
    is StoryAsset.ArtifactFile ->
        "/v0/artifacts/${escape(asset.artifactId)}/versions/${asset.version}/files/" + asset.path.split("/").filter { it.isNotEmpty() }.joinToString("/") { escape(it) }
}

suspend fun DirectAgentClient.storyViewers(storyId: String, cursor: String? = null): StoryViewersPage {
    var path = "/v0/stories/${escape(storyId)}/viewers?limit=50"
    if (cursor != null) path += "&cursor=${escape(cursor)}"
    return decode("GET", path, read = StoryViewersPage::from)
}

private fun rangeHeader(range: IntRange?): Map<String, String> = range?.let { mapOf("Range" to "bytes=${it.first}-${it.last}") } ?: emptyMap()

/** A frame's bytes, whole or one range of them. */
suspend fun DirectAgentClient.storyFrame(storyId: String, frameId: String, range: IntRange? = null): DirectHttpResponse =
    send("GET", "/v0/stories/${escape(storyId)}/frames/${escape(frameId)}", headers = rangeHeader(range))

/** A conversation's media item's bytes, whole or one range of them. */
suspend fun DirectAgentClient.asset(asset: StoryAsset, range: IntRange? = null): DirectHttpResponse =
    send("GET", storyAssetPath(asset), headers = rangeHeader(range))

// Writing

/** Raw bytes for a later story; its ID is used once. */
suspend fun DirectAgentClient.upload(data: ByteArray): String {
    val response = send("POST", "/v0/artifact-uploads", raw = data)
    return try {
        Wire.string(Wire.obj(Wire.obj(DirectAgentClient.parse(response.body))["upload"]), "id")
    } catch (_: Exception) {
        throw DirectAgentError.InvalidResponse
    }
}

/** A draft by hand; `id` is the retry key. */
suspend fun DirectAgentClient.createStory(id: String, caption: String?, frames: List<StoryFrameInput>): Story {
    val body = linkedMapOf<String, JsonElement>("id" to JsonPrimitive(id), "frames" to JsonArray(frames.map { it.json() }), mutation())
    if (!caption.isNullOrEmpty()) body["caption"] = JsonPrimitive(caption)
    return decode("POST", "/v0/stories", JsonObject(body), ::storyOf)
}

/** A caption edit: `Keep` leaves it, `Set` writes it (an empty one clears it). */
sealed class CaptionEdit {
    data object Keep : CaptionEdit()
    data class Set(val caption: String?) : CaptionEdit()
}

/** An edit of the version `version`; `frames` replaces the whole list. */
suspend fun DirectAgentClient.updateStory(storyId: String, version: String, caption: CaptionEdit, frames: List<StoryFrameInput>?): Story {
    val body = linkedMapOf(mutation())
    if (caption is CaptionEdit.Set) body["caption"] = caption.caption?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull
    if (frames != null) body["frames"] = JsonArray(frames.map { it.json() })
    return send("PATCH", "/v0/stories/${escape(storyId)}", JsonObject(body), headers = mapOf("If-Match" to version)).story()
}

/** Posts the version the person was looking at. */
suspend fun DirectAgentClient.postStory(storyId: String, artifactVersion: Int): Story =
    decode("POST", "/v0/stories/${escape(storyId)}/post", JsonObject(mapOf("artifactVersion" to JsonPrimitive(artifactVersion), mutation())), ::storyOf)

suspend fun DirectAgentClient.deleteStory(storyId: String, version: String): Story =
    send("POST", "/v0/stories/${escape(storyId)}/delete", JsonObject(mapOf(mutation())), headers = mapOf("If-Match" to version)).story()

suspend fun DirectAgentClient.markStorySeen(storyId: String): Story =
    decode("POST", "/v0/stories/${escape(storyId)}/seen", JsonObject(emptyMap()), ::storyOf)

/** Sets the person's one reaction, or removes it with null. */
suspend fun DirectAgentClient.reactToStory(storyId: String, emoji: String?): Story {
    val path = "/v0/stories/${escape(storyId)}/reaction"
    if (emoji != null) return decode("PUT", path, JsonObject(mapOf("emoji" to JsonPrimitive(emoji), mutation())), ::storyOf)
    return decode("DELETE", path, JsonObject(mapOf(mutation())), ::storyOf)
}

// Stickers

suspend fun DirectAgentClient.stickerPacks(): List<StickerPack> =
    decode("GET", "/v0/stickers/packs") { Wire.list(Wire.obj(it), "packs", StickerPack::from) }

suspend fun DirectAgentClient.stickerPack(packId: String): StickerPackContents =
    decode("GET", "/v0/stickers/packs/${escape(packId)}", read = StickerPackContents::from)

/** A sticker's file; a `tgs` one as its Lottie JSON with `lottie`. */
suspend fun DirectAgentClient.stickerFile(ref: StickerRef, lottie: Boolean = false): ByteArray =
    send("GET", "/v0/stickers/packs/${escape(ref.packId)}/stickers/${escape(ref.stickerId)}/file" + if (lottie) "?as=lottie" else "").body

suspend fun DirectAgentClient.searchStickers(query: String? = null, emoji: String? = null, kind: StickerPackKind = StickerPackKind.Stickers): List<Sticker> {
    val parts = mutableListOf("kind=${kind.wire}")
    if (query != null) parts += "q=${escape(query)}"
    if (emoji != null) parts += "emoji=${escape(emoji)}"
    return decode("GET", "/v0/stickers/search?" + parts.joinToString("&")) { Wire.list(Wire.obj(it), "stickers", Sticker::from) }
}

suspend fun DirectAgentClient.stickerRecents(): StickerRecents = decode("GET", "/v0/stickers/recents", read = StickerRecents::from)

suspend fun DirectAgentClient.useSticker(ref: StickerRef): StickerRecents = decode(
    "POST", "/v0/stickers/recents",
    JsonObject(mapOf("packId" to JsonPrimitive(ref.packId), "stickerId" to JsonPrimitive(ref.stickerId), mutation())),
    StickerRecents::from,
)

suspend fun DirectAgentClient.clearStickerRecents(): StickerRecents =
    decode("DELETE", "/v0/stickers/recents", JsonObject(mapOf(mutation())), StickerRecents::from)

/** A refusal's details, as the daemon writes them: `{ error, code, story? }`. */
data class StoryRefusal(val status: Int, val code: String?, val message: String?) {
    /** A stale version: the latest must be shown before trying again. */
    val isConflict: Boolean get() = status == 409

    companion object {
        fun of(error: Throwable): StoryRefusal? = (error as? DirectAgentError.Status)?.let { StoryRefusal(it.status, it.code, it.error) }
    }
}
