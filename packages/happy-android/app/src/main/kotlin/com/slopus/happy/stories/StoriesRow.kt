package com.slopus.happy.stories

/** One circle of the stories row, as it is drawn (happy-ios `StoriesRowItem`). */
data class StoriesRowItem(
    val id: String,
    /** "My Story", or the author's first name. */
    val title: String,
    val isSelf: Boolean,
    /** A daemon-relative path, read with the same bearer token. */
    val photo: String?,
    val thumbhash: String?,
    /** Drawn without a photo. */
    val initials: String,
    /** One ring segment per story, the unseen ones last and coloured. */
    val segments: Int,
    val unseen: Int,
) {
    /** Everything in its circle is seen: a grey ring, the name dimmed. */
    val allSeen: Boolean get() = segments > 0 && unseen == 0
}

/**
 * The row from the feed, in the daemon's order (happy-ios `StoriesRow`):
 * the person's own stories first when they have any, then everyone with a
 * story. Nothing here makes a story; that happens in a conversation.
 */
object StoriesRow {
    fun items(feed: StoryFeed?, myStory: String): List<StoriesRowItem> {
        val authors = feed?.authors ?: emptyList()
        val items = mutableListOf<StoriesRowItem>()
        authors.firstOrNull { it.isSelf }?.takeIf { it.storyIds.isNotEmpty() }?.let { items += item(it, myStory) }
        val keys = mutableSetOf("self")
        for (author in authors) {
            if (!author.isSelf && author.storyIds.isNotEmpty() && keys.add(author.key)) items += item(author, myStory)
        }
        return items
    }

    internal fun item(author: StoryFeedAuthor, myStory: String) = StoriesRowItem(
        id = author.key, title = if (author.isSelf) myStory else firstName(author.name), isSelf = author.isSelf,
        photo = author.photo, thumbhash = author.thumbhash, initials = initials(author.name),
        segments = author.storyIds.size, unseen = if (author.isSelf) 0 else minOf(author.unseenCount, author.storyIds.size),
    )

    /**
     * The first circle that closes into the title: the person's own is
     * left out, as it never has anything new.
     */
    fun collapseStart(items: List<StoriesRowItem>): Int = if (items.firstOrNull()?.isSelf == true) 1 else 0

    /** Something in the row is still to be seen. */
    fun hasUnseen(items: List<StoriesRowItem>): Boolean = items.any { it.unseen > 0 }

    /**
     * When the newest story in the row was posted, as the daemon dates it:
     * what putting the row away remembers. `stories` are those known
     * besides the feed's own.
     */
    fun newest(feed: StoryFeed?, stories: Map<String, Story> = emptyMap()): Long =
        (feed?.authors ?: emptyList()).mapNotNull { latest(it, feed, stories) }.maxOrNull() ?: 0

    /**
     * The row starts open as home first shows it when it has something to
     * look at (someone else's unseen story, or the person's own) and was
     * never put away, or something of that was posted since it last was.
     */
    fun opensAtLaunch(feed: StoryFeed?, stories: Map<String, Story> = emptyMap(), putAwayThrough: Long?): Boolean {
        val showing = (feed?.authors ?: emptyList()).filter { it.storyIds.isNotEmpty() && (it.isSelf || it.unseenCount > 0) }
        if (putAwayThrough == null) return showing.isNotEmpty()
        return showing.any { (latest(it, feed, stories) ?: 0) > putAwayThrough }
    }

    /**
     * An author's newest posted story: the newest of the stories known,
     * else the feed's own date (which a deletion leaves stale until the
     * feed is read again).
     */
    private fun latest(author: StoryFeedAuthor, feed: StoryFeed?, stories: Map<String, Story>): Long? {
        if (author.storyIds.isEmpty()) return null
        val posted = author.storyIds.mapNotNull { (feed?.stories?.get(it) ?: stories[it])?.postedAt }
        return posted.maxOrNull() ?: author.latestPostedAt
    }

    fun firstName(name: String): String {
        val trimmed = name.trim()
        return trimmed.split(Regex("\\s+")).firstOrNull { it.isNotEmpty() } ?: trimmed
    }

    /** Up to two letters: the first of the first and last words. */
    fun initials(name: String): String {
        val words = name.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val letters = listOfNotNull(words.firstOrNull(), if (words.size > 1) words.last() else null).map { it.codePoints().findFirst().asInt }
        return letters.joinToString("") { String(Character.toChars(it)) }.uppercase()
    }

    /**
     * Where the viewer opens on an author: their first unseen story, or
     * their first when all are seen.
     */
    fun start(author: StoryFeedAuthor, stories: Map<String, Story>): Int =
        author.storyIds.indexOfFirst { stories[it]?.seen == false }.takeIf { it >= 0 } ?: 0
}
