package com.slopus.happy.changelog

import android.content.SharedPreferences
import android.content.res.Resources
import com.slopus.happy.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** changelog/types.ts `ChangelogEntry`. [titleImage] is a path relative to sources/changelog/. */
@Serializable
data class ChangelogEntry(
    val title: String,
    val summary: String = "",
    val markdown: String = "",
    val titleImage: String? = null,
)

/**
 * changelog/types.ts `ChangelogData`: happy-app's changelog.json, which
 * scripts/parseChangelog.ts builds from CHANGELOG.md. This app ships a copy
 * as res/raw/changelog.json; ChangelogTest fails when the two differ.
 */
@Serializable
data class Changelog(val entries: List<ChangelogEntry> = emptyList(), val latestTitle: String = "") {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        val Empty = Changelog()

        fun parse(text: String): Changelog = runCatching { json.decodeFromString<Changelog>(text) }.getOrDefault(Empty)

        /** changelog/parser.ts `getChangelogData`: the bundled file, or none when it cannot be read. */
        fun bundled(resources: Resources): Changelog =
            runCatching { resources.openRawResource(R.raw.changelog).bufferedReader().use { it.readText() } }
                .map(::parse)
                .getOrDefault(Empty)

        /** changelog.tsx CHANGELOG_IMAGES: the images CHANGELOG.md may use, by path. */
        val images = mapOf("images/mouse-on-the-phone.webp" to R.drawable.changelog_mouse_on_the_phone)
    }
}

/** One piece of an entry's summary line (changelog.tsx `SummarySegment`). */
sealed interface SummarySegment {
    data class Text(val text: String) : SummarySegment
    data class Link(val label: String, val url: String) : SummarySegment
    data class Image(val path: String) : SummarySegment
}

private val summaryInline = Regex("""!\[[^\]]*\]\(([^)]+)\)|\[([^\]]+)\]\(([^)]+)\)""")

/** changelog.tsx `parseSummary`: inline `![alt](path)` images and `[label](url)` links among text. */
fun parseSummary(summary: String): List<SummarySegment> {
    val segments = mutableListOf<SummarySegment>()
    var last = 0
    for (match in summaryInline.findAll(summary)) {
        if (match.range.first > last) segments.add(SummarySegment.Text(summary.substring(last, match.range.first)))
        val image = match.groups[1]?.value
        segments.add(if (image != null) SummarySegment.Image(image) else SummarySegment.Link(match.groupValues[2], match.groupValues[3]))
        last = match.range.last + 1
    }
    if (last < summary.length) segments.add(SummarySegment.Text(summary.substring(last)))
    return segments
}

/**
 * changelog/storage.ts: the title of the newest entry the user has seen,
 * for Home's update banner (hooks/useChangelog.ts) and What's New, which
 * marks the newest read when opened.
 */
class ChangelogReadMarker(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(prefs.getString(LAST_VIEWED_KEY, null).orEmpty())
    val lastViewedTitle: StateFlow<String> = state.asStateFlow()

    fun markViewed(title: String) {
        prefs.edit().putString(LAST_VIEWED_KEY, title).apply()
        state.value = title
    }

    /** Logout: as on a first install, the next start marks the newest entry seen. */
    fun clear() {
        prefs.edit().remove(LAST_VIEWED_KEY).commit()
        state.value = ""
    }

    /** hasUnreadChangelog. */
    fun hasUnread(latestTitle: String): Boolean = latestTitle.isNotEmpty() && latestTitle != state.value

    companion object {
        const val PREFS_NAME = "changelog"
        const val LAST_VIEWED_KEY = "changelog-last-viewed-title"
    }
}

/**
 * The markdown CHANGELOG.md entries use, as changelog.tsx's MarkdownView
 * shows it (happy-ios `ChangelogMarkdown`): bullet items (`- ` or `* `) and
 * paragraphs (consecutive lines joined by a space), with bold runs and links.
 */
object ChangelogMarkdown {
    sealed interface Span {
        data class Text(val text: String, val bold: Boolean) : Span
        data class Link(val label: String, val url: String) : Span
    }

    sealed interface Block {
        val spans: List<Span>

        data class Bullet(override val spans: List<Span>) : Block
        data class Paragraph(override val spans: List<Span>) : Block
    }

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            blocks += Block.Paragraph(spans(paragraph.joinToString(" ")))
            paragraph.clear()
        }
        for (rawLine in markdown.split("\n")) {
            val line = rawLine.trim(' ', '\t')
            when {
                line.isEmpty() -> flushParagraph()
                line.startsWith("- ") || line.startsWith("* ") -> {
                    flushParagraph()
                    blocks += Block.Bullet(spans(line.substring(2)))
                }
                else -> paragraph += line
            }
        }
        flushParagraph()
        return blocks
    }

    /** Inline spans. Unclosed markers are kept as text. */
    fun spans(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        val plain = StringBuilder()
        var bold = false
        var index = 0
        fun flush() {
            if (plain.isEmpty()) return
            spans += Span.Text(plain.toString(), bold)
            plain.clear()
        }
        while (index < text.length) {
            if (text.startsWith("**", index) && (bold || text.indexOf("**", index + 2) >= 0)) {
                flush()
                bold = !bold
                index += 2
                continue
            }
            if (text[index] == '[') {
                val close = text.indexOf("](", index)
                val end = if (close >= 0) text.indexOf(')', close + 2) else -1
                if (close >= 0 && end >= 0) {
                    val label = text.substring(index + 1, close)
                    if (!label.contains(']')) {
                        flush()
                        spans += Span.Link(label, text.substring(close + 2, end))
                        index = end + 1
                        continue
                    }
                }
            }
            plain.append(text[index])
            index += 1
        }
        flush()
        return spans
    }
}
