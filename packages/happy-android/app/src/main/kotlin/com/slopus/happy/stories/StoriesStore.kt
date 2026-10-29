package com.slopus.happy.stories

import com.slopus.happy.direct.DirectAgentClient
import com.slopus.happy.direct.DirectAgentError
import com.slopus.happy.direct.DirectEvent
import com.slopus.happy.direct.DirectOfflineFile
import com.slopus.happy.direct.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the Stories screens draw from, for one paired computer. */
data class StoriesSnapshot(
    val availability: Availability = Availability.Unknown,
    /** The row, as last read and changed by events since. */
    val feed: StoryFeed? = null,
    /** Every story known: the feed's, and those read for a conversation. */
    val stories: Map<String, Story> = emptyMap(),
    /** Stories read and not found: deleted for everyone but their author. */
    val gone: Set<String> = emptySet(),
    val recents: List<StickerRef> = emptyList(),
    val live: Boolean = false,
) {
    enum class Availability(override val wire: String) : WireName {
        /** Not read yet, and nothing kept. */
        Unknown("unknown"),

        /** The computer serves no Stories. */
        Unsupported("unsupported"),

        /** Served, and the person's setting is not `true`. */
        Off("off"),
        On("on"),
    }

    val isOn: Boolean get() = availability == Availability.On
}

/**
 * One computer's Stories (happy-ios `StoriesStore`): the feed, every story
 * the screens show, the person's recent stickers, and the setting that
 * turns it all on. Events arrive off the Stem Cells store's one stream;
 * reads and writes run here, on `scope`'s one thread, and every change is
 * one snapshot.
 *
 * The row's order is the daemon's. While the viewer is open (`hold`), a
 * fresh read of the feed waits, so circles don't move under the person;
 * what they see marks rings at once.
 *
 * `scope` must run one coroutine at a time (a single-threaded or
 * `limitedParallelism(1)` dispatcher): it plays the Swift actor.
 */
class StoriesStore(
    private val client: DirectAgentClient,
    private val events: suspend () -> Flow<DirectEvent>,
    private val offline: DirectOfflineFile?,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val firstReadDelayMs: Long = 1_000,
) {
    private val confined = scope.coroutineContext.minusKey(Job)
    private val updatesFlow = MutableStateFlow(StoriesSnapshot())

    /** Every change, the latest kept. */
    val updates: StateFlow<StoriesSnapshot> = updatesFlow.asStateFlow()

    private var snapshot = StoriesSnapshot()
    private var following: Job? = null
    private var loading: Job? = null

    /** Asked to read again while a read was under way. */
    private var loadAgain = false

    /** The first read, waiting a moment for the stream's first `resync`. */
    private var firstLoad: Job? = null
    private var heldEvents: MutableList<JsonObject>? = null
    private var holds = 0
    private var reloadWanted = false
    private var reloading: Job? = null
    private var expiry: Job? = null
    private val reading = HashMap<String, Job>()
    private var settingWrite: Job? = null
    private var settingWrites = 0

    /** Each story's latest reaction write; older answers are dropped. */
    private val reactionWrites = HashMap<String, Int>()
    private val reactedMeanwhile = HashSet<String>()

    /** Whether `stories.json` exists for this computer. */
    private var keptOnDisk = false
    private var keeping: Job? = null
    private var stopped = false

    // Life

    fun start() {
        scope.launch {
            if (following != null || stopped) return@launch
            restoreOffline()
            following = scope.launch {
                val stream = events()
                loadFirst()
                stream.collect { event ->
                    when (event) {
                        DirectEvent.Resync -> resynced()
                        is DirectEvent.Event -> receive(event.envelope)
                    }
                }
            }
        }
    }

    fun stop() {
        scope.launch {
            stopped = true
            following?.cancel(); following = null
            firstLoad?.cancel(); firstLoad = null
            loading?.cancel(); loading = null
            reloading?.cancel(); reloading = null
            expiry?.cancel(); expiry = null
            keeping?.cancel(); keeping = null
            settingWrite?.cancel(); settingWrite = null
            for (task in reading.values) task.cancel()
            reading.clear()
        }
    }

    /**
     * A stream that has just connected says `resync` first; the first read
     * waits a moment for it so it doesn't read twice.
     */
    private fun loadFirst() {
        firstLoad = scope.launch {
            delay(firstReadDelayMs)
            firstLoad = null
            startLoad()
        }
    }

    private fun resynced() {
        firstLoad?.cancel(); firstLoad = null
        startLoad()
    }

    /**
     * Everything read whole. Events that arrive meanwhile apply after it;
     * asked again meanwhile, it reads again before they do.
     */
    suspend fun load() {
        val job = withContext(confined) {
            startLoad()
            loading
        }
        job?.join()
    }

    private fun startLoad() {
        if (loading != null) {
            loadAgain = true
            return
        }
        if (heldEvents == null) heldEvents = mutableListOf()
        loading = scope.launch { readUntilSettled() }
    }

    private suspend fun readUntilSettled() {
        do {
            loadAgain = false
            read()
        } while (loadAgain && scope.isActive)
        loading = null
        val pending = heldEvents ?: emptyList()
        heldEvents = null
        for (event in pending) receive(event)
        publish()
        saveOffline()
    }

    private suspend fun read() {
        // Out of reach, what was kept stays on screen.
        val health = attempt { client.health() } ?: return
        if (health.stories != true) return turnedOff(StoriesSnapshot.Availability.Unsupported)
        val setting = try {
            client.userSetting(STORIES_SETTING_KEY)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        if (setting != JsonPrimitive(true)) return turnedOff(StoriesSnapshot.Availability.Off)
        try {
            val (feedAnswer, recentsAnswer) = coroutineScope {
                val feed = async { client.storyFeed() }
                val recents = async { runCatching { client.stickerRecents() } }
                feed.await() to recents.await()
            }
            snapshot = snapshot.copy(availability = StoriesSnapshot.Availability.On)
            if (holds > 0 && snapshot.feed != null) {
                // The viewer is open: its stories change, the row waits.
                for ((id, story) in feedAnswer.stories) adopt(story, id)
                reloadWanted = true
            } else {
                adoptFeed(feedAnswer)
            }
            recentsAnswer.getOrNull()?.let { snapshot = snapshot.copy(recents = it.recents) }
            snapshot = snapshot.copy(live = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DirectAgentError.Status) {
            if (e.status == 404) turnedOff(StoriesSnapshot.Availability.Off)
        } catch (_: Exception) {
        }
    }

    private fun turnedOff(availability: StoriesSnapshot.Availability) {
        snapshot = snapshot.copy(availability = availability, feed = null, stories = emptyMap(), gone = emptySet(), recents = emptyList())
        reloading?.cancel(); reloading = null
        expiry?.cancel(); expiry = null
    }

    /**
     * A read of the feed, after events that may have reordered it. It
     * waits while the viewer is open.
     */
    private fun reloadFeedSoon() {
        if (holds != 0) {
            reloadWanted = true
            return
        }
        reloading?.cancel()
        reloading = scope.launch {
            delay(400)
            val feed = attempt { client.storyFeed() } ?: return@launch
            adoptReloaded(feed)
        }
    }

    private fun adoptReloaded(feed: StoryFeed) {
        if (holds != 0) {
            reloadWanted = true
            return
        }
        adoptFeed(feed)
        publish()
        saveOffline()
    }

    private fun adoptFeed(feed: StoryFeed) {
        for ((id, story) in feed.stories) adopt(story, id)
        val authors = feed.authors.map { author ->
            // A read from before a deletion still lists it.
            val ids = author.storyIds.filter { snapshot.stories[it]?.status != StoryStatus.Deleted && it !in snapshot.gone }
            author.copy(storyIds = ids).let { it.copy(unseenCount = unseen(it)) }
        }.filter { it.isSelf || it.storyIds.isNotEmpty() }
        snapshot = snapshot.copy(feed = feed.copy(authors = authors, stories = emptyMap()))
    }

    /**
     * Keeps the newer copy of a story (versions are UUIDv7s, so they sort
     * by time). A deleted story stays deleted, and one seen here stays
     * seen: seeing doesn't change a version, so an answer read before the
     * person saw it looks as new. A reaction being written stays as shown.
     */
    private fun adopt(incoming: Story, id: String = incoming.id) {
        if (id in snapshot.gone) return
        var story = incoming
        snapshot.stories[id]?.let { held ->
            if (held.version > story.version || held.status == StoryStatus.Deleted) return
            if (held.seen && story.status == StoryStatus.Posted) story = story.copy(seen = true)
            if (reactionWrites[id] != null) story = story.copy(myReaction = held.myReaction, reactions = held.reactions)
        }
        snapshot = snapshot.copy(stories = snapshot.stories + (id to story))
    }

    // Holding still

    /** The viewer opens (`true`) or closes: the row keeps its order meanwhile. */
    fun hold(on: Boolean) {
        scope.launch {
            holds = maxOf(0, holds + if (on) 1 else -1)
            if (holds == 0 && reloadWanted) {
                reloadWanted = false
                reloadFeedSoon()
            }
        }
    }

    // Events

    private fun receive(event: JsonObject) {
        heldEvents?.let {
            it.add(event)
            return
        }
        val type = (event["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        val payload = event["payload"] as? JsonObject ?: JsonObject(emptyMap())
        when (type) {
            "user_settings.updated" -> {
                val setting = payload["setting"] as? JsonObject ?: return
                if ((setting["key"] as? JsonPrimitive)?.content != STORIES_SETTING_KEY) return
                val on = setting["value"] == JsonPrimitive(true)
                if (on == snapshot.isOn) return
                scope.launch { load() }
                return
            }
            "story.created", "story.posted", "story.updated" -> {
                val story = payload["story"]?.let { decode { Story.from(it) } } ?: return
                adopt(story)
                // The feed counts drafts it doesn't list, so only it can say
                // how many there are now.
                if (type == "story.created" && story.status == StoryStatus.Draft) reloadFeedSoon()
                if (type == "story.posted") {
                    place(story)
                    reloadFeedSoon()
                }
            }
            "story.deleted" -> {
                val id = stringOf(payload["storyId"]) ?: return
                removed(id, stringOf(payload["version"]))
                reloadFeedSoon()
            }
            "story.seen" -> {
                val id = stringOf(payload["storyId"]) ?: return
                if (!markSeen(id)) reloadFeedSoon()
            }
            "story.reacted" -> {
                val id = stringOf(payload["storyId"]) ?: return
                // Mid-write, the counts may be from before it: read once it's done.
                if (reactionWrites[id] != null) {
                    reactedMeanwhile.add(id)
                    return
                }
                var story = snapshot.stories[id] ?: return
                val reactions = payload["reactions"]?.let { r -> decode { Wire.array(r).map(StoryReactionCount::from) } } ?: return
                story = story.copy(reactions = reactions)
                if ("myReaction" in payload) story = story.copy(myReaction = stringOf(payload["myReaction"]))
                snapshot = snapshot.copy(stories = snapshot.stories + (id to story))
            }
            "sticker_recents.updated" -> {
                val recents = payload["recents"]?.let { r -> decode { Wire.array(r).map(StickerRef::from) } } ?: return
                snapshot = snapshot.copy(recents = recents)
            }
            else -> return
        }
        publish()
        keepSoon()
    }

    /**
     * A posted story goes last in its author's circle, which exists once
     * the feed is read again if it doesn't yet.
     */
    private fun place(story: Story) {
        val feed = snapshot.feed ?: return
        val index = feed.authors.indexOfFirst { author ->
            if (author.isSelf) story.authorUserId == author.userId || story.authorUserId == null else author.userId == story.authorUserId
        }
        if (index < 0 || story.id in feed.authors[index].storyIds) return
        val author = feed.authors[index]
        val placed = author.copy(
            storyIds = author.storyIds + story.id,
            unseenCount = author.unseenCount + if (!story.seen) 1 else 0,
            latestPostedAt = story.postedAt ?: author.latestPostedAt,
        )
        snapshot = snapshot.copy(feed = feed.copy(authors = feed.authors.toMutableList().also { it[index] = placed }))
    }

    private fun removed(id: String, version: String?) {
        val held = snapshot.stories[id]
        if (held != null) {
            val newer = if (version != null && version > held.version) version else held.version
            snapshot = snapshot.copy(stories = snapshot.stories + (id to held.copy(status = StoryStatus.Deleted, version = newer)))
        } else {
            snapshot = snapshot.copy(gone = snapshot.gone + id)
        }
        val feed = snapshot.feed ?: return
        val authors = feed.authors.map { author ->
            if (id !in author.storyIds) author else author.copy(storyIds = author.storyIds - id).let { it.copy(unseenCount = unseen(it)) }
        }.filter { it.isSelf || it.storyIds.isNotEmpty() }
        snapshot = snapshot.copy(feed = feed.copy(authors = authors))
    }

    /**
     * Marks `storyId` and every earlier story of its author seen. False
     * when the story isn't known here.
     */
    private fun markSeen(storyId: String): Boolean {
        val story = snapshot.stories[storyId] ?: return false
        val mark = story.postedAt
        if (story.status != StoryStatus.Posted || mark == null) return false
        val stories = snapshot.stories.mapValues { (_, other) ->
            if (other.authorUserId == story.authorUserId && other.status == StoryStatus.Posted && !other.seen && (other.postedAt ?: Long.MAX_VALUE) <= mark) other.copy(seen = true) else other
        }
        snapshot = snapshot.copy(stories = stories)
        snapshot.feed?.let { feed -> snapshot = snapshot.copy(feed = feed.copy(authors = feed.authors.map { it.copy(unseenCount = unseen(it)) })) }
        return true
    }

    private fun unseen(author: StoryFeedAuthor): Int =
        if (author.isSelf) 0 else author.storyIds.count { snapshot.stories[it]?.seen == false }

    // Publishing

    private fun publish() {
        dropExpired()
        updatesFlow.value = snapshot
    }

    /**
     * Stories past `expiresAt` leave the row, without an event; the next
     * one to expire wakes this again.
     */
    private fun dropExpired() {
        // While the viewer is open, circles leave once it closes.
        if (holds != 0) return
        val feed = snapshot.feed ?: return
        val now = now()
        var next: Long? = null
        var changed = false
        val authors = feed.authors.map { author ->
            val kept = author.storyIds.filter { id ->
                val expires = snapshot.stories[id]?.expiresAt ?: return@filter true
                if (expires <= now) return@filter false
                next = minOf(next ?: expires, expires)
                true
            }
            if (kept != author.storyIds) {
                changed = true
                author.copy(storyIds = kept).let { it.copy(unseenCount = unseen(it)) }
            } else {
                author
            }
        }
        if (changed) snapshot = snapshot.copy(feed = feed.copy(authors = authors.filter { it.isSelf || it.storyIds.isNotEmpty() }))
        expiry?.cancel()
        next?.let { at ->
            expiry = scope.launch {
                delay(maxOf(0, at - now) + 50)
                publish()
            }
        }
    }

    // Offline

    private fun restoreOffline() {
        if (snapshot.availability != StoriesSnapshot.Availability.Unknown) return
        val data = offline?.readExtra(OFFLINE_NAME) ?: return
        val kept = runCatching {
            val o = Wire.obj(DirectAgentClient.parse(data))
            if (Wire.int(o, "format") != KEPT_FORMAT) return
            Triple(
                wireEnum<StoriesSnapshot.Availability>(Wire.string(o, "availability")),
                o["feed"]?.takeIf { it != JsonNull }?.let(StoryFeed::from),
                Wire.list(o, "recents", StickerRef::from),
            )
        }.getOrNull() ?: return
        keptOnDisk = true
        snapshot = snapshot.copy(availability = kept.first, recents = kept.third)
        kept.second?.let(::adoptFeed)
        publish()
    }

    private fun keepSoon() {
        keeping?.cancel()
        keeping = scope.launch {
            delay(2_000)
            keeping = null
            saveOffline()
        }
    }

    /** Saves what is waiting to be kept, before the app is suspended. */
    fun flush() {
        scope.launch {
            if (keeping == null) return@launch
            keeping?.cancel(); keeping = null
            saveOffline()
        }
    }

    /**
     * Kept once Stories have been on here: off and never on, nothing is
     * written; turned off, what was kept becomes just "off".
     */
    private fun saveOffline() {
        val offline = offline ?: return
        if (snapshot.availability != StoriesSnapshot.Availability.On && !keptOnDisk) return
        keptOnDisk = true
        val feed = snapshot.feed?.let { shown ->
            val ids = shown.authors.flatMap { it.storyIds }.toSet()
            shown.copy(stories = snapshot.stories.filterKeys { it in ids })
        }
        val kept = Wire.objectOf(
            "format" to JsonPrimitive(KEPT_FORMAT),
            "availability" to JsonPrimitive(snapshot.availability.wire),
            "feed" to (feed?.json() ?: JsonNull),
            "recents" to JsonArray(snapshot.recents.map { it.json() }),
        )
        offline.writeExtra(OFFLINE_NAME, kept.toString().toByteArray())
    }

    // Acts

    /**
     * Turns Stories on or off for the person; the screens follow at once,
     * and a refusal puts back what the daemon has.
     */
    fun setEnabled(on: Boolean) {
        scope.launch {
            if (snapshot.availability != StoriesSnapshot.Availability.On && snapshot.availability != StoriesSnapshot.Availability.Off) return@launch
            if (on) snapshot = snapshot.copy(availability = StoriesSnapshot.Availability.On) else turnedOff(StoriesSnapshot.Availability.Off)
            publish()
            saveOffline()
            settingWrites += 1
            val write = settingWrites
            val previous = settingWrite
            settingWrite = scope.launch {
                previous?.join()
                attempt { client.setUserSetting(STORIES_SETTING_KEY, JsonPrimitive(on)) }
                // Only the last of quick toggles reads back what the daemon has.
                if (write != settingWrites) return@launch
                load()
            }
        }
    }

    /**
     * Reads a story the screens need and the store doesn't hold (a
     * conversation's draft), once at a time.
     */
    fun want(storyId: String) {
        scope.launch {
            if (snapshot.stories[storyId] != null || storyId in snapshot.gone || reading[storyId] != null) return@launch
            reading[storyId] = scope.launch {
                val story = try {
                    client.story(storyId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DirectAgentError.Status) {
                    if (e.status == 404) {
                        notFound(storyId)
                        return@launch
                    }
                    null
                } catch (_: Exception) {
                    null
                }
                read(storyId, story)
            }
        }
    }

    /** Reads it again: the author's view count moves without an event. */
    fun refresh(storyId: String) {
        scope.launch {
            try {
                read(storyId, client.story(storyId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: DirectAgentError.Status) {
                if (e.status == 404) gone(storyId)
            } catch (_: Exception) {
            }
        }
    }

    /** Deleted, or no longer the person's to see. */
    private fun gone(storyId: String) {
        removed(storyId, null)
        snapshot = snapshot.copy(gone = snapshot.gone + storyId)
        publish()
    }

    private fun read(storyId: String, story: Story?) {
        reading.remove(storyId)
        if (story == null) return
        adopt(story)
        publish()
    }

    private fun notFound(storyId: String) {
        reading.remove(storyId)
        snapshot = snapshot.copy(gone = snapshot.gone + storyId)
        publish()
    }

    /** The viewer showed `storyId`: its ring turns at once. */
    fun seen(storyId: String) {
        scope.launch {
            val story = snapshot.stories[storyId] ?: return@launch
            if (story.status != StoryStatus.Posted || story.seen) return@launch
            markSeen(storyId)
            publish()
            keepSoon()
            scope.launch { attempt { client.markStorySeen(storyId) }?.let(::adoptAndPublish) }
        }
    }

    /** Sets or, with null, removes the person's reaction; shown at once. */
    fun react(storyId: String, emoji: String?) {
        scope.launch {
            val held = snapshot.stories[storyId] ?: return@launch
            if (held.status != StoryStatus.Posted) return@launch
            val before = held.myReaction to held.reactions
            val story = held.copy(reactions = counts(held.reactions, held.myReaction, emoji), myReaction = emoji)
            snapshot = snapshot.copy(stories = snapshot.stories + (storyId to story))
            markSeen(storyId)
            publish()
            // Only the last write's answer is shown; a refusal of it puts back
            // what was there before.
            val write = (reactionWrites[storyId] ?: 0) + 1
            reactionWrites[storyId] = write
            scope.launch {
                try {
                    val answer = client.reactToStory(storyId, emoji)
                    if (reactionWrites[storyId] != write) return@launch
                    reactionWrites.remove(storyId)
                    adoptAndPublish(answer)
                    if (reactedMeanwhile.remove(storyId)) refresh(storyId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (reactionWrites[storyId] != write) return@launch
                    reactionWrites.remove(storyId)
                    reactedMeanwhile.remove(storyId)
                    snapshot.stories[storyId]?.let {
                        snapshot = snapshot.copy(stories = snapshot.stories + (storyId to it.copy(myReaction = before.first, reactions = before.second)))
                    }
                    publish()
                }
            }
        }
    }

    private fun adoptAndPublish(story: Story) {
        adopt(story)
        publish()
    }

    /**
     * Posts the version shown. Throws the refusal; a stale version brings
     * the latest in its place.
     */
    suspend fun post(storyId: String, artifactVersion: Int): Story = withContext(confined) {
        try {
            val story = client.postStory(storyId, artifactVersion)
            adopt(story)
            place(story)
            publish()
            reloadFeedSoon()
            story
        } catch (e: Exception) {
            if (e !is CancellationException && StoryRefusal.of(e)?.isConflict == true) refresh(storyId)
            throw e
        }
    }

    suspend fun delete(storyId: String) = withContext(confined) {
        val story = snapshot.stories[storyId] ?: return@withContext
        val deleted = client.deleteStory(storyId, story.version)
        removed(storyId, deleted.version)
        publish()
        reloadFeedSoon()
    }

    /** A new draft by hand. */
    suspend fun create(id: String, caption: String?, frames: List<StoryFrameInput>): Story = withContext(confined) {
        val story = client.createStory(id, caption, frames)
        adopt(story)
        publish()
        reloadFeedSoon()
        story
    }

    /**
     * An edit of `version` (the editor's), or of the version held; `caption`
     * `Keep` leaves it as it is.
     */
    suspend fun update(storyId: String, version: String? = null, caption: CaptionEdit, frames: List<StoryFrameInput>?): Story = withContext(confined) {
        val base = version ?: snapshot.stories[storyId]?.version ?: throw DirectAgentError.InvalidResponse
        try {
            val story = client.updateStory(storyId, base, caption, frames)
            adopt(story)
            publish()
            story
        } catch (e: Exception) {
            if (e !is CancellationException && StoryRefusal.of(e)?.isConflict == true) refresh(storyId)
            throw e
        }
    }

    fun useSticker(ref: StickerRef) {
        scope.launch {
            snapshot = snapshot.copy(recents = (listOf(ref) + snapshot.recents.filter { it != ref }).take(20))
            publish()
            scope.launch { attempt { client.useSticker(ref) }?.let { adoptRecents(it.recents) } }
        }
    }

    fun clearRecents() {
        scope.launch {
            snapshot = snapshot.copy(recents = emptyList())
            publish()
            scope.launch { attempt { client.clearStickerRecents() } }
        }
    }

    private fun adoptRecents(recents: List<StickerRef>) {
        snapshot = snapshot.copy(recents = recents)
        publish()
    }

    /** The person's drafts, newest first. */
    suspend fun drafts(): List<Story> = withContext(confined) {
        val page = client.ownStories(listOf(StoryStatus.Draft))
        for (story in page.stories) adopt(story)
        publish()
        page.stories
    }

    /** The snapshot as it is now, read on the store's thread. */
    suspend fun current(): StoriesSnapshot = withContext(confined) { snapshot }

    companion object {
        const val OFFLINE_NAME = "stories.json"
        private const val KEPT_FORMAT = 1
        val EVENT_PREFIXES = listOf("story.", "sticker_recents.", "user_settings.")

        /**
         * The counts after one person's reaction moves from `old` to `new`,
         * most first, then in their earlier order.
         */
        fun counts(counts: List<StoryReactionCount>, from: String?, to: String?): List<StoryReactionCount> {
            if (from == to) return counts
            val next = counts.toMutableList()
            if (from != null) {
                val index = next.indexOfFirst { it.emoji == from }
                if (index >= 0) {
                    next[index] = next[index].copy(count = next[index].count - 1)
                    if (next[index].count <= 0) next.removeAt(index)
                }
            }
            if (to != null) {
                val index = next.indexOfFirst { it.emoji == to }
                if (index >= 0) next[index] = next[index].copy(count = next[index].count + 1) else next.add(StoryReactionCount(to, 1))
            }
            return next.withIndex().sortedWith(compareByDescending<IndexedValue<StoryReactionCount>> { it.value.count }.thenBy { it.index }).map { it.value }
        }

        private inline fun <T> decode(read: () -> T): T? = try {
            read()
        } catch (_: Exception) {
            null
        }

        private fun stringOf(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** The answer, or null for any failure but cancellation (Swift's `try?`). */
        private suspend inline fun <T> attempt(block: () -> T): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
