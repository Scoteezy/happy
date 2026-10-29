package com.slopus.happy.ui.stories

import android.content.Context
import com.slopus.happy.R
import com.slopus.happy.stories.StoryTextFont
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * happy-ios `Strings.Stories`'s functions, over `strings_stories.xml`.
 * English only, as iOS has them.
 */
object StoriesStrings {
    const val HEART = "❤️"
    val reactions = listOf("❤️", "👍", "🔥", "🎉", "😂", "😮", "😢", "👏")

    fun author(context: Context, name: String, unseen: Int): String =
        if (unseen > 0) context.getString(R.string.stories_author_unseen, name, unseen) else name

    /** The avatars closed into the header's title. */
    fun collapsed(context: Context, names: List<String>): String =
        if (names.isEmpty()) context.getString(R.string.stories_collapsed) else context.getString(R.string.stories_collapsed_names, names.joinToString(", "))

    /** "Ana", "Ana and Steve", "Ana, Steve, and Bo", as en_US's ListFormatter joins them. */
    fun list(context: Context, names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> context.getString(R.string.stories_list_two, names[0], names[1])
        else -> context.getString(R.string.stories_list_more, names.dropLast(1).joinToString(", "), names.last())
    }

    /** The one-time tooltip under them (ChatList.StoryFeedTooltipUsers). */
    fun tooltip(context: Context, names: List<String>): String = context.getString(R.string.stories_tooltip, list(context, names))

    fun views(context: Context, count: Int): String =
        if (count == 1) context.getString(R.string.stories_one_view) else context.getString(R.string.stories_views, count)

    fun ago(context: Context, milliseconds: Long): String {
        val minutes = maxOf(0L, milliseconds / 60_000)
        if (minutes < 1) return context.getString(R.string.stories_just_now)
        if (minutes < 60) return context.getString(R.string.stories_minutes_ago, minutes.toInt())
        return context.getString(R.string.stories_hours_ago, (minutes / 60).toInt())
    }

    /**
     * When something happened, both in epoch milliseconds: how long ago
     * within a day, then "yesterday at" its time as Telegram's list says,
     * then the date.
     */
    fun `when`(context: Context, milliseconds: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        if (now - milliseconds < 86_400_000) return ago(context, now - milliseconds)
        val date = Calendar.getInstance(zone).apply { timeInMillis = milliseconds }
        val yesterday = Calendar.getInstance(zone).apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1) }
        if (date.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) && date.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)) {
            val time = SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = zone }.format(Date(milliseconds))
            return context.getString(R.string.stories_yesterday_at, time)
        }
        return SimpleDateFormat("MMM d", Locale.US).apply { timeZone = zone }.format(Date(milliseconds))
    }

    fun fontName(context: Context, font: StoryTextFont): String = context.getString(
        when (font) {
            StoryTextFont.SanFrancisco -> R.string.stories_font_san_francisco
            StoryTextFont.Typewriter -> R.string.stories_font_typewriter
            StoryTextFont.AvenirNext -> R.string.stories_font_avenir_next
            StoryTextFont.CourierNew -> R.string.stories_font_courier_new
            StoryTextFont.Noteworthy -> R.string.stories_font_noteworthy
            StoryTextFont.Georgia -> R.string.stories_font_georgia
            StoryTextFont.Papyrus -> R.string.stories_font_papyrus
            StoryTextFont.SnellRoundhand -> R.string.stories_font_snell_roundhand
        },
    )
}
