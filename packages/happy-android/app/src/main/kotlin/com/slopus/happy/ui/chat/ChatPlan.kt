package com.slopus.happy.ui.chat

import android.text.StaticLayout
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.chat.ChatStackLayout
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size

// A row's plan (happy-ios Chat/List/ChatRowPlan.swift): everything a row
// draws, already measured and placed, made off the main thread. The row's
// view only draws it and hit-tests it.

/** A filled and/or stroked rounded rectangle. */
data class ChatBox(
    val frame: Rect,
    val fill: ColorToken? = null,
    val border: ColorToken? = null,
    val borderWidth: Double = 0.0,
    val radius: Double = 0.0,
    /** Only the top corners are rounded (a card's header with a body under it). */
    val topCornersOnly: Boolean = false,
    val alpha: Double = 1.0,
)

sealed interface ChatDrawItem {
    class Text(val layout: StaticLayout, val frame: Rect, val alpha: Double = 1.0) : ChatDrawItem
    class Box(val box: ChatBox) : ChatDrawItem
    class Glyph(val icon: Icon, val size: Double, val frame: Rect, val tint: ColorToken) : ChatDrawItem
    /** Content wider than the row that scrolls sideways in [frame] (a code block, a table). */
    class Scroller(val plan: ChatRowPlan, val frame: Rect, val radius: Double = 0.0) : ChatDrawItem
    /** A running tool's spinner. */
    class Spinner(val frame: Rect, val tint: ColorToken) : ChatDrawItem
    /** A running tool's timer, counting from [since] (ms). */
    class Elapsed(val since: Long, val frame: Rect, val env: ChatTextEnv) : ChatDrawItem
}

/** What a tap on a row does. */
sealed interface ChatRowAction {
    data class CopyMessage(val id: String, val text: String) : ChatRowAction
    /** LongPressCopyable: a menu with Copy over the message. */
    data class CopyMenu(val text: String) : ChatRowAction
    data class ToggleTool(val id: String) : ChatRowAction
    data class OpenMessage(val id: String) : ChatRowAction
    data class OpenLink(val url: String) : ChatRowAction
    /** An `<option>` the agent offered: sent as the next message. */
    data class Option(val text: String) : ChatRowAction
    data class Retry(val localId: String) : ChatRowAction
    data class ToggleGroup(val id: String) : ChatRowAction
    /** A permission footer button: the answer, and the mode the session switches to locally. */
    data class Permission(val toolId: String, val answer: com.slopus.happy.chat.PermissionAnswer, val mode: String?) : ChatRowAction
}

class ChatHit(val frame: Rect, val action: ChatRowAction, val hold: Boolean = false)

class ChatRowPlan(
    val id: String,
    val size: Size,
    val items: List<ChatDrawItem>,
    val hits: List<ChatHit> = emptyList(),
    val alpha: Double = 1.0,
    val accessibilityLabel: String? = null,
)

/** Part of a row: its size at a width, and once a layout has placed it, what to draw there. */
class ChatPiece(val measurable: Measurable, val draw: (Rect) -> Pair<List<ChatDrawItem>, List<ChatHit>>) {
    companion object {
        fun text(text: MeasuredText) = ChatPiece(text.measurable) { frame -> listOf(ChatDrawItem.Text(text.layout(frame), frame)) to emptyList() }

        /** Pieces one above the other ([ChatStackLayout]), each with its own margins, padding and background. */
        fun stack(entries: List<ChatStackEntry>, gap: Double = 0.0): ChatPiece {
            val items = entries.map { it.item }
            return ChatPiece(ChatStackLayout.measurable(gap) { items }) { frame ->
                val layout = ChatStackLayout(frame.width, items, gap)
                val drawn = ArrayList<ChatDrawItem>()
                val hits = ArrayList<ChatHit>()
                entries.forEachIndexed { index, entry ->
                    entry.background?.let { drawn += ChatDrawItem.Box(it.copy(frame = layout.boxes[index].offsetBy(frame.minX, frame.minY))) }
                    val (items_, hits_) = entry.piece.draw(layout.contents[index].offsetBy(frame.minX, frame.minY))
                    drawn += items_
                    hits += hits_
                }
                drawn to hits
            }
        }
    }
}

class ChatStackEntry(
    val piece: ChatPiece,
    val marginTop: Double = 0.0,
    val marginBottom: Double = 0.0,
    val padding: LayoutInsets = LayoutInsets.Zero,
    val placement: ChatStackLayout.Placement = ChatStackLayout.Placement.Fill,
    val maxHeight: Double? = null,
    /** Drawn behind the entry, its padding included; the frame is filled in. */
    val background: ChatBox? = null,
) {
    val item get() = ChatStackLayout.Item(piece.measurable, marginTop, marginBottom, padding, placement, maxHeight)
}
