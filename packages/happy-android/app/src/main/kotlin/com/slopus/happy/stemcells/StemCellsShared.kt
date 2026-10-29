package com.slopus.happy.stemcells

import com.slopus.happy.direct.DirectAgentClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import java.util.Base64

/**
 * Unicode's White_Space, for inside a regex's brackets: Swift's `\s` and
 * ICU's on Android. The JVM's `\s` is ASCII without `(?U)`, which Android's
 * ICU refuses, so the characters are listed for both.
 */
internal const val UNICODE_SPACE = "\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"

/**
 * What a conversation shared, as Telegram's shared media: the pictures,
 * recordings and files it showed, and the links written in it, newest
 * first. Derived from the transcript itself, nothing kept beside it.
 * happy-ios `StemCellsShared`.
 */
data class StemCellsShared(val media: List<Media> = emptyList(), val links: List<Link> = emptyList()) {
    sealed class Media {
        /**
         * A picture sent with a message, from the bytes sent: `id` is its
         * message's and which of its pictures it is, the same whether the
         * phone still sends it or history has it.
         */
        class Picture(val id: String, val mimeType: String, val data: ByteArray) : Media() {
            override fun equals(other: Any?) = other is Picture && other.id == id && other.mimeType == mimeType && other.data.contentEquals(data)
            override fun hashCode() = id.hashCode()
        }

        /** What a tool presented: a picture, a recording or a file. */
        data class Tool(val media: StemCellMedia) : Media()

        /** What a screen knows it by. */
        val key: String get() = when (this) {
            is Picture -> "sent/$id"
            is Tool -> media.key
        }

        internal val isSent: Boolean get() = this is Picture

        /** A picture, either kind; a recording or a file is not. */
        val isPicture: Boolean get() = when (this) {
            is Picture -> true
            is Tool -> media.isVisual && !media.isVideo
        }
    }

    data class Link(
        val url: String,
        /** Its Markdown text, when it had one other than itself. */
        val title: String? = null,
        /** Its host, `www.` dropped. */
        val host: String,
    )

    val isEmpty: Boolean get() = media.isEmpty() && links.isEmpty()

    /** `this` (newer), then what `older` adds: the same media or link once. */
    fun merged(older: StemCellsShared): StemCellsShared {
        val keys = media.map { it.key }.toHashSet()
        val urls = links.map { it.url }.toHashSet()
        val media = ArrayList(media)
        val links = ArrayList(links)
        for (one in older.media) if (keys.add(one.key)) media += one
        for (link in older.links) if (urls.add(link.url)) links += link
        return StemCellsShared(media, links)
    }

    /** Each one once, where it first shows. */
    fun deduplicated(): StemCellsShared = StemCellsShared().merged(this)

    /**
     * The transcript read again as it changes: an item read before and
     * unchanged isn't read again, so a reply streaming in costs its own
     * text alone.
     */
    class Reader {
        private var kept = HashMap<String, Pair<StemCellChatItem, StemCellsShared>>()

        fun read(items: List<StemCellChatItem>): StemCellsShared {
            val next = HashMap<String, Pair<StemCellChatItem, StemCellsShared>>()
            val media = ArrayList<Media>()
            val links = ArrayList<Link>()
            for (item in items.asReversed()) {
                val one = kept[item.id]?.takeIf { it.first == item }?.second ?: of(item)
                next[item.id] = item to one
                media += one.media
                links += one.links
            }
            kept = next
            return StemCellsShared(media, links).deduplicated()
        }
    }

    companion object {
        /** A sent picture's ID: its message's, and which of its pictures. */
        internal fun pictureId(messageId: String, ordinal: Int) = "$messageId#$ordinal"

        /** What the phone's transcript shows, newest first, each once. */
        fun of(items: List<StemCellChatItem>): StemCellsShared = Reader().read(items)

        /** One item's media and links, in its order. */
        internal fun of(item: StemCellChatItem): StemCellsShared {
            if (item.role is StemCellChatItem.Role.Collaborator) return StemCellsShared()
            val media = ArrayList<Media>()
            val texts = ArrayList<String>()
            for (piece in item.pieces) {
                when (piece) {
                    is StemCellChatItem.Piece.Picture -> media += Media.Picture(pictureId(item.id, media.count { it.isSent }), piece.mimeType, piece.data)
                    is StemCellChatItem.Piece.Gallery -> media += piece.media.map { Media.Tool(it) }
                    is StemCellChatItem.Piece.Tree -> media += piece.media.map { Media.Tool(it) }
                    is StemCellChatItem.Piece.File -> media += Media.Tool(piece.media)
                    is StemCellChatItem.Piece.Text -> texts += piece.text
                    is StemCellChatItem.Piece.Artifact, is StemCellChatItem.Piece.Story, is StemCellChatItem.Piece.SidebarProposal -> Unit
                }
            }
            return StemCellsShared(media, texts.flatMap(::links))
        }

        /**
         * What a page of history shows, newest first, each once, as the
         * transcript draws it: a person's pictures, a tool's media, and the
         * links in what either side wrote.
         */
        @JvmName("ofMessages")
        fun of(messages: List<AgentMessage>): StemCellsShared {
            val media = ArrayList<Media>()
            val links = ArrayList<Link>()
            for (message in messages.asReversed()) {
                if (message.role != "user" && message.role != "agent") continue
                val texts = ArrayList<String>()
                var pictures = 0
                for (block in message.content) {
                    when (block) {
                        is Block.Text -> texts += block.text
                        is Block.Image -> {
                            runCatching { Base64.getDecoder().decode(block.data) }.getOrNull()?.let {
                                media += Media.Picture(pictureId(message.id, pictures), block.mimeType, it)
                            }
                            pictures += 1
                        }
                        is Block.ToolCall -> {
                            val presentation = block.presentation ?: continue
                            if (presentation.type != "media") continue
                            presentation.items.orEmpty().forEachIndexed { index, item -> media += Media.Tool(StemCellMedia(block.id, index, item)) }
                        }
                        is Block.Reasoning, is Block.Unknown -> Unit
                    }
                }
                links += texts.flatMap(::links)
            }
            return StemCellsShared(media, links).deduplicated()
        }

        // Links

        /** `[text](url)`, not `![alt](url)`; a URL may hold balanced parentheses. */
        private val markdownLink = Regex("""(?<!!)\[([^\]\n]{1,300})\]\((https?://(?:[^$UNICODE_SPACE()]|\([^$UNICODE_SPACE()]*\))+)\)""")
        private val markdownImage = Regex("""!\[[^\]\n]{0,300}\]\([^$UNICODE_SPACE)]*\)""")
        private val bareLink = Regex("""https?://(?:[^$UNICODE_SPACE<>"'`()\[\]{}]|\([^$UNICODE_SPACE<>"'`()\[\]{}]*\))+""", RegexOption.IGNORE_CASE)
        /** What ends a sentence rather than a link. */
        private const val TRAILING = ".,;:!?*_~'\""

        /**
         * The links in `text`, in its order: a Markdown link with its text, a
         * bare one as written, punctuation after it left out.
         */
        fun links(text: String): List<Link> {
            val found = ArrayList<Pair<Int, Link>>()
            val covered = markdownImage.findAll(text).map { it.range }.toMutableList()
            for (match in markdownLink.findAll(text)) {
                val title = match.groups[1]?.value ?: continue
                val url = match.groups[2]?.value ?: continue
                val link = link(url, title) ?: continue
                found += match.range.first to link
                covered += match.range
            }
            for (match in bareLink.findAll(text)) {
                if (covered.any { it.first <= match.range.last && match.range.first <= it.last }) continue
                val link = link(match.value, null) ?: continue
                found += match.range.first to link
            }
            return found.sortedBy { it.first }.map { it.second }
        }

        private fun link(raw: String, title: String?): Link? {
            val url = raw.trimEnd { it in TRAILING }
            val host = host(url)
            if (host.isNullOrEmpty()) return null
            val name = title?.trim()
            val bare = host.removePrefix("www.")
            return Link(url, name?.takeUnless { it.isEmpty() || it == url || it == raw }, bare)
        }

        /** The authority's host, as `URLComponents` reads it: after any user info, before any port. */
        private fun host(url: String): String? {
            val start = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return null
            val end = url.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) url.length else it }
            val authority = url.substring(start, end).substringAfterLast('@')
            if (authority.startsWith("[")) return authority.substringBefore(']').removePrefix("[").takeIf { authority.contains(']') }
            return authority.substringBefore(':')
        }
    }
}

/**
 * A conversation's shared media and links, read a page of history at a
 * time from the newest back: as many pages as it takes to have what a
 * screen asks for, never more than `PAGES_PER_ASK` for one ask.
 * happy-ios `StemCellsSharedFeed`.
 */
class StemCellsSharedFeed(private val client: DirectAgentClient, private val agentId: String) {
    data class State(
        val shared: StemCellsShared = StemCellsShared(),
        /** Older history is still unread. */
        val hasMore: Boolean = true,
        val failed: Boolean = false,
    )

    private val reading = Mutex()
    private var before: String? = null

    @Volatile
    var state = State()
        private set

    /**
     * Reads on until `media` media or `links` links are in (null: that
     * section isn't asked about), history ends, or `PAGES_PER_ASK` pages
     * are read. One ask at a time; another while one reads gets what it had.
     */
    suspend fun load(media: Int?, links: Int?): State {
        if (!reading.tryLock()) return state
        try {
            var pages = 0
            fun short() = state.shared.media.size < (media ?: 0) || state.shared.links.size < (links ?: 0)
            while (state.hasMore && pages < PAGES_PER_ASK && short() && currentCoroutineContext().isActive) {
                try {
                    val page = client.messages(agentId, before = before, limit = PAGE_SIZE)
                    pages += 1
                    // Runs come oldest first; the page's oldest is the next cursor.
                    val messages = page.runs.flatMap { it.messages.orEmpty() }
                    before = page.runs.firstOrNull()?.id
                    state = State(state.shared.merged(StemCellsShared.of(messages)), page.hasMore && before != null, false)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    state = state.copy(failed = true)
                    break
                }
            }
            return state
        } finally {
            reading.unlock()
        }
    }

    companion object {
        /**
         * Small pages: a run comes whole, and a person's pictures come with
         * it as their bytes (the ones the grid draws), a few MB a run; the
         * direct connection takes 8 MB a response.
         */
        const val PAGE_SIZE = 10
        const val PAGES_PER_ASK = 6
    }
}
