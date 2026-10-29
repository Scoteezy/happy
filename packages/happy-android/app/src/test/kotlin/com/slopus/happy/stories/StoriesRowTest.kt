package com.slopus.happy.stories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** happy-ios `StoriesRowTests`. */
class StoriesRowTest {
    private fun author(id: String?, name: String, isSelf: Boolean = false, stories: List<String> = emptyList(), unseen: Int = 0, latest: Long? = null) =
        StoryFeedAuthor(id, name, null, null, isSelf, stories, unseen, if (isSelf) 0 else null, latest)

    private fun story(id: String, posted: Long, seen: Boolean = false, expires: Long = posted + 10) = Story(
        id = id, status = StoryStatus.Posted, authorUserId = "u", createdBy = StoryCreator.User("u"), caption = null, frames = emptyList(),
        artifactVersion = 1, createdAt = posted, updatedAt = posted, postedAt = posted, expiresAt = expires, seen = seen,
        viewCount = null, myReaction = null, reactions = emptyList(), version = "v",
    )

    /**
     * Nothing to start a story from: no circle before the feed, and none
     * for the person until they have a story.
     */
    @Test
    fun noMyStoryWithoutAStory() {
        assertEquals(emptyList<StoriesRowItem>(), StoriesRow.items(null, "My Story"))
        val feed = StoryFeed(listOf(author("u1", "Kirill Dubovitskiy", isSelf = true), author("u2", "Ada Lovelace", stories = listOf("b"), unseen = 1)), emptyMap(), "c")
        val items = StoriesRow.items(feed, "My Story")
        assertEquals(listOf("Ada"), items.map { it.title })
        assertEquals(0, StoriesRow.collapseStart(items))
        assertTrue(StoriesRow.hasUnseen(items))
    }

    /**
     * The daemon's order, never sorted again; My Story first when it has a
     * story, never unseen, and left out of what closes into the title.
     */
    @Test
    fun theFeedsOrderIsKept() {
        val feed = StoryFeed(listOf(
            author("u1", "Kirill Dubovitskiy", isSelf = true, stories = listOf("a"), unseen = 3),
            author("u3", "Zed Last", stories = listOf("c"), unseen = 0),
            author("u2", "  Ada  Lovelace ", stories = listOf("b", "b2"), unseen = 1),
            author("u4", "Empty"),
        ), emptyMap(), "c")
        val items = StoriesRow.items(feed, "My Story")
        assertEquals(listOf("My Story", "Zed", "Ada"), items.map { it.title })
        assertEquals(listOf("self", "u3", "u2"), items.map { it.id })
        assertEquals(1, items[0].segments)
        assertEquals(0, items[0].unseen)
        assertTrue(items[1].allSeen)
        assertFalse(items[2].allSeen)
        assertEquals("AL", items[2].initials)
        assertEquals(1, StoriesRow.collapseStart(items))
        assertTrue(StoriesRow.hasUnseen(items))
    }

    /**
     * Open at launch with something to look at, until put away; after
     * that only a story posted since brings it back.
     */
    @Test
    fun opensAtLaunchUntilPutAway() {
        val stories = mapOf("a" to story("a", 100), "b" to story("b", 200), "m" to story("m", 150))
        val unseen = StoryFeed(listOf(author("u1", "Me", isSelf = true), author("u2", "Ada", stories = listOf("a", "b"), unseen = 1)), stories, "c")
        val seen = StoryFeed(listOf(author("u1", "Me", isSelf = true), author("u2", "Ada", stories = listOf("a", "b"), unseen = 0)), stories, "c")
        val mine = StoryFeed(listOf(author("u1", "Me", isSelf = true, stories = listOf("m"))), stories, "c")
        assertTrue(StoriesRow.opensAtLaunch(unseen, putAwayThrough = null))
        assertFalse("everything seen opened it", StoriesRow.opensAtLaunch(seen, putAwayThrough = null))
        assertTrue("the person's own story didn't", StoriesRow.opensAtLaunch(mine, putAwayThrough = null))
        assertFalse(StoriesRow.opensAtLaunch(null, putAwayThrough = null))

        assertEquals(200, StoriesRow.newest(unseen))
        assertEquals(150, StoriesRow.newest(mine))
        assertEquals(0, StoriesRow.newest(null))
        assertFalse("put away, it came back with nothing new", StoriesRow.opensAtLaunch(unseen, putAwayThrough = 200))
        assertTrue(StoriesRow.opensAtLaunch(unseen, putAwayThrough = 199))
        assertFalse(StoriesRow.opensAtLaunch(mine, putAwayThrough = 150))
        assertTrue(StoriesRow.opensAtLaunch(mine, putAwayThrough = 149))
        // Something newer, but seen: still put away.
        assertFalse(StoriesRow.opensAtLaunch(seen, putAwayThrough = 0))

        // The feed's own date counts when its stories aren't carried.
        val bare = StoryFeed(listOf(author("u2", "Ada", stories = listOf("x"), unseen = 1, latest = 300)), emptyMap(), "c")
        assertEquals(300, StoriesRow.newest(bare))
        assertTrue(StoriesRow.opensAtLaunch(bare, putAwayThrough = 299))

        // Stories known besides the feed's count too.
        val elsewhere = StoryFeed(listOf(author("u2", "Ada", stories = listOf("a", "b"), unseen = 1)), emptyMap(), "c")
        assertEquals(200, StoriesRow.newest(elsewhere, stories))
        assertTrue(StoriesRow.opensAtLaunch(elsewhere, stories, putAwayThrough = 199))
        assertFalse(StoriesRow.opensAtLaunch(elsewhere, stories, putAwayThrough = 200))

        // A deleted story's date, left on its author, counts for nothing.
        val emptied = author("u1", "Me", isSelf = true, latest = 500)
        val stale = author("u2", "Ada", stories = listOf("a"), unseen = 1, latest = 500)
        assertEquals(100, StoriesRow.newest(StoryFeed(listOf(emptied, stale), stories, "c")))
    }

    @Test
    fun initials() {
        assertEquals("KD", StoriesRow.initials("Kirill Dubovitskiy"))
        assertEquals("S", StoriesRow.initials("steve"))
        assertEquals("MS", StoriesRow.initials("Mary Ann Smith"))
        assertEquals("", StoriesRow.initials(""))
    }

    @Test
    fun theViewerOpensOnTheFirstUnseen() {
        val stories = mapOf("a" to story("a", 1, seen = true), "b" to story("b", 1), "c" to story("c", 1))
        assertEquals(1, StoriesRow.start(author("u", "U", stories = listOf("a", "b", "c")), stories))
        assertEquals(0, StoriesRow.start(author("u", "U", stories = listOf("a")), stories))
    }
}
