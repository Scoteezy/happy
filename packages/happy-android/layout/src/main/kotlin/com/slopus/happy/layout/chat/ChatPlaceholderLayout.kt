package com.slopus.happy.layout.chat

import com.slopus.happy.layout.CenteredStackLayout
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size

// Ported from happy-ios Sources/HappyLayout/Chat/ChatPlaceholderLayout.swift.

/**
 * EmptyMessages, PendingChatPlaceholder and the deleted state
 * (SessionView.tsx): a column centred in the frame, the glyph, then
 * PendingChatPlaceholder's title, then the lines, each its margin below the
 * last and inside its own padding, with EmptyMessages' status (Retry, Load
 * more or the paging spinner) among them; or the first load's Retry alone
 * in the middle. Its parts come measured; it measures nothing.
 */
data class ChatPlaceholderLayout(
    val icon: Rect?,
    val title: Rect?,
    val lines: List<Rect>,
    val status: Rect?,
) {
    object Metrics {
        /** The icon's marginBottom over PendingChatPlaceholder's title. */
        const val titleSpacing = 16.0
        /** The title's and the status's paddingHorizontal. */
        const val padding = 48.0
    }

    /** A line: its size, the previous line's marginBottom, and its own padding. */
    data class Line(val size: Size, val spacing: Double, val padding: Double)

    /** The status in the column: before the line at [index]. */
    data class Status(val index: Int, val size: Size, val spacing: Double)

    companion object {
        operator fun invoke(size: Size, icon: Size?, title: Size?, lines: List<Line>, status: Status?): ChatPlaceholderLayout {
            val m = Metrics
            val items = ArrayList<CenteredStackLayout.Item>()
            if (icon != null) items += CenteredStackLayout.Item(Measurable.fixed(icon))
            if (title != null) items += CenteredStackLayout.Item(Measurable.fixed(title), spacingBefore = m.titleSpacing, horizontalPadding = m.padding)
            val lineItems = lines.map { CenteredStackLayout.Item(Measurable.fixed(it.size), spacingBefore = it.spacing, horizontalPadding = it.padding) }.toMutableList()
            val placedStatus = status?.takeIf { it.index <= lines.size }
            if (placedStatus != null) {
                lineItems.add(placedStatus.index, CenteredStackLayout.Item(Measurable.fixed(placedStatus.size), spacingBefore = placedStatus.spacing, horizontalPadding = m.padding))
            }
            val frames = CenteredStackLayout(CenteredStackLayout.Input(size, items = items + lineItems)).items.toMutableList()
            val iconFrame = if (icon != null) frames.removeAt(0) else null
            val titleFrame = if (title != null) frames.removeAt(0) else null
            val statusFrame = placedStatus?.let { frames.removeAt(it.index) }
            return ChatPlaceholderLayout(iconFrame, titleFrame, frames, statusFrame)
        }

        /** The first load's Retry, centred in a frame of [size]. */
        fun button(size: Size, button: Size) = ChatPlaceholderLayout(
            icon = null,
            title = null,
            lines = emptyList(),
            status = Rect((size.width - button.width) / 2, (size.height - button.height) / 2, button.width, button.height),
        )
    }
}
