package com.slopus.happy.stories

import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectEvent
import com.slopus.happy.direct.DirectHttpResponse
import com.slopus.happy.direct.DirectOfflineFile
import com.slopus.happy.direct.DirectTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

/** A daemon with Stories, answered from memory (happy-ios `StoriesTransport`). */
private class StoriesTestTransport : DirectTransport {
    var capabilities = """{"stories":true,"stickers":true,"sidebarOperations":true}"""
    var setting: String? = "true"
    var feed: String = ""
    val stories = HashMap<String, String>()
    val requests = mutableListOf<Pair<String, String>>()
    var reactionDelays = mutableListOf<Long>()

    private val settingPath = "/v0/user-settings/" + DirectAgentClient.escape(STORIES_SETTING_KEY)

    override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse {
        requests += method to path
        fun ok(text: String) = DirectHttpResponse(200, body = text.toByteArray())
        val route = path.substringBefore("?")
        when {
            method == "GET" && route == "/v0/health" -> return ok("""{"ready":true,"capabilities":$capabilities}""")
            method == "GET" && route == settingPath -> {
                val setting = setting ?: return DirectHttpResponse(404, body = """{"error":"none","code":"not_found"}""".toByteArray())
                return ok("""{"setting":{"key":"$STORIES_SETTING_KEY","value":$setting}}""")
            }
            method == "PUT" && route == settingPath -> {
                val value = Json.parseToJsonElement(body!!.decodeToString()).jsonObject["value"]
                setting = if (value == JsonPrimitive(true)) "true" else "false"
                return ok("{}")
            }
            method == "GET" && route == "/v0/stories/feed" -> {
                if (setting != "true") return DirectHttpResponse(404)
                return ok(feed)
            }
            method == "GET" && route == "/v0/stickers/recents" -> return ok("""{"recents":[{"packId":"p","stickerId":"s"}],"cursor":"c"}""")
        }
        val parts = route.split("/")
        if (parts.size == 5 && parts[4] == "reaction" && reactionDelays.isNotEmpty()) {
            val wait = reactionDelays.removeAt(0)
            val emoji = (Json.parseToJsonElement(body!!.decodeToString()).jsonObject["emoji"] as? JsonPrimitive)?.content
            delay(wait)
            val reactions = emoji?.let { """[{"emoji":"$it","count":1}]""" } ?: "[]"
            return ok("""{"story":${StoriesSamples.story(parts[3], seen = true, myReaction = emoji, reactions = reactions)}}""")
        }
        if (parts.size >= 4 && parts[2] == "stories") {
            stories[parts[3]]?.let { story ->
                if (parts.size == 5 && parts[4] in setOf("reaction", "seen", "post")) return ok("""{"story":$story}""")
                if (parts.size == 4) return ok("""{"story":$story}""")
            }
        }
        return DirectHttpResponse(404, body = """{"error":"not found","code":"not_found"}""".toByteArray())
    }

    fun paths(): List<String> = requests.map { "${it.first} ${it.second}" }
}

/** Stories JSON in the daemon's shape (happy-ios `StoriesSamples`). */
internal object StoriesSamples {
    fun story(
        id: String,
        author: String? = "u9",
        status: String = "posted",
        seen: Boolean = false,
        postedAt: Long = 1_000,
        expiresAt: Long? = null,
        myReaction: String? = null,
        reactions: String = "[]",
        version: String = "v1",
    ): String {
        val posted = if (status == "draft") "null" else "$postedAt"
        val expires = if (status == "draft") "null" else "${expiresAt ?: (postedAt + StoryCanvas.LIFETIME_MS)}"
        return """{"id":"$id","status":"$status","authorUserId":${author?.let { "\"$it\"" } ?: "null"},"createdBy":{"kind":"user","userId":null},"source":null,"caption":null,""" +
            """"frames":[{"id":"f-$id","kind":"image","width":100,"height":200,"thumbhash":null,"durationMs":null,"asset":{"type":"chat_media","agentId":"a","toolCallId":"t","index":0},""" +
            """"available":true,"placement":{"x":0.5,"y":0.5,"scale":1,"rotation":0},"background":["#000000","#000000"],"overlays":[]}],""" +
            """"audience":"team","artifactVersion":1,"createdAt":1,"updatedAt":1,"postedAt":$posted,"expiresAt":$expires,"seen":$seen,"viewCount":null,""" +
            """"myReaction":${myReaction?.let { "\"$it\"" } ?: "null"},"reactions":$reactions,"version":"$version"}"""
    }

    fun author(id: String?, name: String, isSelf: Boolean, storyIds: List<String>, unseen: Int, drafts: Int? = null): String {
        val ids = storyIds.joinToString(",") { "\"$it\"" }
        return """{"userId":${id?.let { "\"$it\"" } ?: "null"},"name":"$name","photo":null,"thumbhash":null,"isSelf":$isSelf,"storyIds":[$ids],"unseenCount":$unseen,"draftCount":${drafts?.toString() ?: "null"},"latestPostedAt":null}"""
    }

    fun feed(authors: List<String>, stories: Map<String, String>): String {
        val map = stories.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
        return """{"authors":[${authors.joinToString(",")}],"stories":{$map},"cursor":"c1"}"""
    }
}

/** A computer out of reach. */
private class FailingTransport : DirectTransport {
    override suspend fun request(method: String, path: String, credential: String, body: ByteArray?, headers: Map<String, String>): DirectHttpResponse =
        throw IOException("unavailable")
}

/** happy-ios `StoriesStoreTests`, on the test scheduler's clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class StoriesStoreTest {
    private var events = Channel<DirectEvent>(Channel.UNLIMITED)
    private var firstReadDelayMs = 0L

    private fun TestScope.make(transport: DirectTransport, offline: DirectOfflineFile? = null, now: Long = 2_000, scope: CoroutineScope = backgroundScope): StoriesStore {
        events = Channel(Channel.UNLIMITED)
        val channel = events
        return StoriesStore(DirectAgentClient(transport, "t"), { channel.receiveAsFlow() }, offline, scope, { now }, firstReadDelayMs)
    }

    private suspend fun StoriesStore.next(condition: (StoriesSnapshot) -> Boolean): StoriesSnapshot =
        withTimeout(3_000) { updates.first(condition) }

    private fun event(type: String, payload: String) {
        events.trySend(DirectEvent.Event(Json.parseToJsonElement("""{"type":"$type","payload":$payload}""") as JsonObject))
    }

    private fun twoAuthors(): String = StoriesSamples.feed(
        listOf(
            StoriesSamples.author("u1", "Kirill Dubovitskiy", isSelf = true, storyIds = emptyList(), unseen = 0, drafts = 0),
            StoriesSamples.author("u9", "Steve Korshakov", isSelf = false, storyIds = listOf("s1", "s2"), unseen = 2),
        ),
        mapOf("s1" to StoriesSamples.story("s1", postedAt = 1_000), "s2" to StoriesSamples.story("s2", postedAt = 1_500)),
    )

    private fun transport(feed: String = twoAuthors()) = StoriesTestTransport().also { it.feed = feed }

    @Test
    fun aComputerWithoutStoriesShowsNothing() = runTest {
        val transport = transport().apply { capabilities = """{"sidebarOperations":true}""" }
        val store = make(transport)
        store.start()
        val snapshot = store.next { it.availability != StoriesSnapshot.Availability.Unknown }
        assertEquals(StoriesSnapshot.Availability.Unsupported, snapshot.availability)
        assertNull(snapshot.feed)
        assertFalse(transport.paths().any { "/v0/stories" in it })
    }

    @Test
    fun offUntilTheSettingIsTrue() = runTest {
        val transport = transport().apply { setting = null }
        val store = make(transport)
        store.start()
        val off = store.next { it.availability != StoriesSnapshot.Availability.Unknown }
        assertEquals(StoriesSnapshot.Availability.Off, off.availability)
        store.setEnabled(true)
        val on = store.next { it.feed != null }
        assertEquals(StoriesSnapshot.Availability.On, on.availability)
        assertEquals(2, on.feed?.authors?.size)
        assertTrue(transport.paths().contains("PUT /v0/user-settings/${DirectAgentClient.escape(STORIES_SETTING_KEY)}"))
    }

    @Test
    fun theRowIsTheFeedsOrder() = runTest {
        val store = make(transport())
        store.start()
        val snapshot = store.next { it.feed != null }
        val items = StoriesRow.items(snapshot.feed, "My Story")
        assertEquals(listOf("Steve"), items.map { it.title })
        assertEquals(2, items[0].segments)
        assertEquals(2, items[0].unseen)
        assertEquals(listOf(StickerRef("p", "s")), snapshot.recents)
    }

    /** Seeing a story marks it and every earlier one of its author at once. */
    @Test
    fun seenTurnsTheRingAtOnce() = runTest {
        val transport = transport().apply { stories["s2"] = StoriesSamples.story("s2", seen = true, postedAt = 1_500) }
        val store = make(transport)
        store.start()
        store.next { it.feed != null }
        store.seen("s2")
        val seen = store.next { it.feed?.authors?.get(1)?.unseenCount == 0 }
        assertEquals(true, seen.stories["s1"]?.seen)
        assertEquals(true, seen.stories["s2"]?.seen)
        assertTrue(StoriesRow.items(seen.feed, "My Story")[0].allSeen)
    }

    @Test
    fun anotherDevicesSeenEventMarksTheRing() = runTest {
        val store = make(transport())
        store.start()
        store.next { it.feed != null }
        event("story.seen", """{"authorUserId":"u9","storyId":"s1"}""")
        val snapshot = store.next { it.feed?.authors?.get(1)?.unseenCount == 1 }
        assertEquals(true, snapshot.stories["s1"]?.seen)
        assertEquals(false, snapshot.stories["s2"]?.seen)
    }

    @Test
    fun deletingAnAuthorsLastStoryRemovesTheCircle() = runTest {
        val store = make(transport())
        store.start()
        store.next { it.feed != null }
        event("story.deleted", """{"storyId":"s1","version":"v2"}""")
        val one = store.next { it.feed?.authors?.lastOrNull()?.storyIds == listOf("s2") }
        assertEquals(1, one.feed?.authors?.last()?.unseenCount)
        event("story.deleted", """{"storyId":"s2","version":"v2"}""")
        val none = store.next { it.feed?.authors?.size == 1 }
        assertEquals(true, none.feed?.authors?.first()?.isSelf)
    }

    @Test
    fun aPostedStoryJoinsItsAuthorsCircle() = runTest {
        val store = make(transport())
        store.start()
        store.next { it.feed != null }
        event("story.posted", """{"story":${StoriesSamples.story("s3", postedAt = 1_800)}}""")
        val snapshot = store.next { it.feed?.authors?.get(1)?.storyIds?.size == 3 }
        assertEquals(listOf("s1", "s2", "s3"), snapshot.feed?.authors?.get(1)?.storyIds)
        assertEquals(3, snapshot.feed?.authors?.get(1)?.unseenCount)
    }

    @Test
    fun myDraftsAreCounted() = runTest {
        val transport = transport()
        val store = make(transport)
        store.start()
        store.next { it.feed != null }
        // The feed counts drafts; a new one reads it again.
        transport.feed = twoAuthors().replace(""""draftCount":0""", """"draftCount":1""")
        event("story.created", """{"story":${StoriesSamples.story("d1", author = "u1", status = "draft")}}""")
        val one = store.next { it.feed?.authors?.get(0)?.draftCount == 1 }
        assertEquals(StoryStatus.Draft, one.stories["d1"]?.status)
        transport.feed = twoAuthors()
        event("story.deleted", """{"storyId":"d1","version":"v2"}""")
        val none = store.next { it.feed?.authors?.get(0)?.draftCount == 0 }
        assertEquals(StoryStatus.Deleted, none.stories["d1"]?.status)
    }

    @Test
    fun aReactionShowsAtOnceAndOthersCountsArrive() = runTest {
        val transport = transport().apply {
            stories["s1"] = StoriesSamples.story("s1", seen = true, myReaction = "❤️", reactions = """[{"emoji":"❤️","count":1}]""", version = "v2")
        }
        val store = make(transport)
        store.start()
        store.next { it.feed != null }
        store.react("s1", "❤️")
        val mine = store.next { it.stories["s1"]?.myReaction == "❤️" }
        assertEquals(listOf(StoryReactionCount("❤️", 1)), mine.stories["s1"]?.reactions)
        delay(100)
        event("story.reacted", """{"storyId":"s1","reactions":[{"emoji":"🔥","count":2},{"emoji":"❤️","count":1}]}""")
        val others = store.next { it.stories["s1"]?.reactions?.size == 2 }
        assertEquals("another person's reaction leaves mine", "❤️", others.stories["s1"]?.myReaction)
    }

    /** Quick taps: only the last answer shows, whatever order they come in. */
    @Test
    fun theLastReactionWins() = runTest {
        val transport = transport().apply { reactionDelays = mutableListOf(400, 50) }
        val store = make(transport)
        store.start()
        store.next { it.feed != null }
        store.react("s1", "❤️")
        store.react("s1", "🔥")
        delay(700)
        assertEquals(2, transport.paths().count { it.endsWith("/reaction") })
        val snapshot = store.current()
        assertEquals("🔥", snapshot.stories["s1"]?.myReaction)
        assertEquals(listOf(StoryReactionCount("🔥", 1)), snapshot.stories["s1"]?.reactions)
    }

    /** Seeing doesn't change a version, so a read from before it can't unsee. */
    @Test
    fun aSeenStoryStaysSeen() = runTest {
        val store = make(transport())
        store.start()
        store.next { it.feed != null }
        store.seen("s2")
        store.next { it.feed?.authors?.get(1)?.unseenCount == 0 }
        events.trySend(DirectEvent.Resync)
        delay(300)
        val snapshot = store.current()
        assertEquals(true, snapshot.stories["s1"]?.seen)
        assertEquals(0, snapshot.feed?.authors?.get(1)?.unseenCount)
    }

    /** A read that began before a deletion doesn't bring the story back. */
    @Test
    fun aDeletedStoryStaysDeleted() = runTest {
        val store = make(transport())
        store.start()
        store.next { it.feed != null }
        event("story.deleted", """{"storyId":"s1","version":"v2"}""")
        store.next { it.feed?.authors?.lastOrNull()?.storyIds == listOf("s2") }
        events.trySend(DirectEvent.Resync)
        delay(300)
        val snapshot = store.current()
        assertEquals(StoryStatus.Deleted, snapshot.stories["s1"]?.status)
        assertEquals(listOf("s2"), snapshot.feed?.authors?.last()?.storyIds)
    }

    /** A stream that has just connected says `resync`: one read, not two. */
    @Test
    fun connectingReadsOnce() = runTest {
        firstReadDelayMs = 1_000
        val transport = transport()
        val store = make(transport)
        store.start()
        events.trySend(DirectEvent.Resync)
        store.next { it.feed != null }
        delay(1_200)
        assertEquals(1, transport.paths().count { it == "GET /v0/stories/feed" })
    }

    /** Off at once, with nothing of the row left to draw or keep. */
    @Test
    fun turningOffForgetsTheRow() = runTest {
        val store = make(transport())
        store.start()
        store.next { it.feed != null }
        store.setEnabled(false)
        val off = store.next { it.availability == StoriesSnapshot.Availability.Off }
        assertNull(off.feed)
        assertTrue(off.stories.isEmpty())
        assertTrue(off.recents.isEmpty())
    }

    @Test
    fun reactionCountsMoveOnePerson() {
        val counts = listOf(StoryReactionCount("🔥", 2), StoryReactionCount("❤️", 2))
        assertEquals(listOf(StoryReactionCount("❤️", 3), StoryReactionCount("🔥", 2)), StoriesStore.counts(counts, null, "❤️"))
        assertEquals(listOf(StoryReactionCount("❤️", 2), StoryReactionCount("🔥", 1)), StoriesStore.counts(counts, "🔥", null))
        assertEquals(listOf(StoryReactionCount("😂", 1)), StoriesStore.counts(listOf(StoryReactionCount("🔥", 1)), "🔥", "😂"))
        assertEquals(counts, StoriesStore.counts(counts, "🔥", "🔥"))
    }

    @Test
    fun expiredStoriesLeaveTheRow() = runTest {
        val feed = StoriesSamples.feed(
            listOf(
                StoriesSamples.author("u1", "Me", isSelf = true, storyIds = emptyList(), unseen = 0, drafts = 0),
                StoriesSamples.author("u9", "Old", isSelf = false, storyIds = listOf("s1"), unseen = 1),
                StoriesSamples.author("u8", "New", isSelf = false, storyIds = listOf("s2"), unseen = 1),
            ),
            mapOf("s1" to StoriesSamples.story("s1", postedAt = 0, expiresAt = 1_999), "s2" to StoriesSamples.story("s2", author = "u8", postedAt = 1_500)),
        )
        val store = make(transport(feed), now = 2_000)
        store.start()
        val snapshot = store.next { it.feed != null }
        assertEquals(listOf("Me", "New"), snapshot.feed?.authors?.map { it.name })
    }

    /** What the row last showed is drawn before the computer answers. */
    @Test
    fun theRowOpensOnWhatWasKept() = runTest {
        val directory = Files.createTempDirectory("stories").toFile()
        try {
            val first = make(transport(), offline = DirectOfflineFile(directory))
            first.start()
            first.next { it.feed != null }
            first.stop()
            delay(200)

            val offline = DirectOfflineFile(directory)
            assertNotNull(offline.readExtra(StoriesStore.OFFLINE_NAME))
            val second = StoriesStore(DirectAgentClient(FailingTransport(), "t"), { emptyFlow() }, offline, backgroundScope, { 2_000 })
            second.start()
            val kept = second.next { it.feed != null }
            assertEquals(StoriesSnapshot.Availability.On, kept.availability)
            assertEquals(listOf("Steve"), StoriesRow.items(kept.feed, "My Story").map { it.title })
            assertEquals(2, kept.stories.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** The viewer holds the row's order: a read after a post waits until it closes. */
    @Test
    fun theRowHoldsStillWhileTheViewerIsOpen() = runTest {
        val transport = transport()
        val store = make(transport)
        store.start()
        store.next { it.feed != null }
        store.hold(true)
        delay(1)
        val before = transport.paths().count { it == "GET /v0/stories/feed" }
        event("story.posted", """{"story":${StoriesSamples.story("s3", postedAt = 1_800)}}""")
        delay(700)
        assertEquals(before, transport.paths().count { it == "GET /v0/stories/feed" })
        store.hold(false)
        delay(700)
        assertEquals(before + 1, transport.paths().count { it == "GET /v0/stories/feed" })
    }
}
