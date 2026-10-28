package com.slopus.happy.layout

import kotlin.math.max

/**
 * A settings row: optional icon, a title with an optional subtitle, and on
 * the right an optional detail, a trailing element (a switch, checkmark,
 * spinner or dropdown value) and a chevron. Ported from components/Item.tsx
 * (iOS values), as happy-ios `ItemRowLayout`.
 *
 *     |16| icon 29 |12| title / 2 / subtitle  |8| detail |6| trailing |6|4| › |16|
 *     ───────────────── divider, inset 15 + (icon ? 60 : 16) ───────────────────
 */
data class ItemRowLayout(
    /** The whole row, divider included. */
    val size: Size,
    /** The tappable part, without the divider under it. */
    val rowHeight: Double,
    val icon: Rect?,
    val title: Rect,
    val subtitle: Rect?,
    val detail: Rect?,
    val trailing: Rect?,
    val accessory: Rect?,
    val divider: Rect?,
) {
    object Metrics {
        const val minHeight = 44.0
        const val paddingHorizontal = 16.0
        const val paddingVertical = 12.0
        const val paddingVerticalWithSubtitle = 11.0
        const val iconSize = 29.0
        const val iconSpacing = 12.0
        const val subtitleSpacing = 2.0
        const val rightSectionSpacing = 8.0
        const val detailAccessorySpacing = 6.0
        const val accessoryLeading = 4.0
        const val dividerHeight = 0.33
        const val dividerInset = 15.0
        /** A long detail gets at most this share of the row, so the title always shows. */
        const val detailMaxShare = 0.6
    }

    class Input(
        val width: Double,
        val hasIcon: Boolean = false,
        val title: Measurable,
        val subtitle: Measurable? = null,
        val detail: Measurable? = null,
        /** A switch, checkmark, spinner or dropdown value, sized by itself. */
        val trailing: Size? = null,
        /** The chevron's glyph size; null when the row shows no chevron. */
        val accessory: Size? = null,
        val showsDivider: Boolean = false,
    )

    companion object {
        operator fun invoke(input: Input): ItemRowLayout {
            val m = Metrics
            val width = max(0.0, input.width)
            val padV = if (input.subtitle == null) m.paddingVertical else m.paddingVerticalWithSubtitle
            val centerX = m.paddingHorizontal + if (input.hasIcon) m.iconSize + m.iconSpacing else 0.0
            val available = max(0.0, width - centerX - m.paddingHorizontal - m.rightSectionSpacing)

            val accessoryWidth = input.accessory?.let { m.accessoryLeading + it.width } ?: 0.0
            val trailingGap = if (input.trailing != null && input.accessory != null) m.detailAccessorySpacing else 0.0
            val trailingWidth = input.trailing?.let { it.width + trailingGap } ?: 0.0
            val detailGap = if (input.detail != null && (input.accessory != null || input.trailing != null)) m.detailAccessorySpacing else 0.0
            val detailSize = input.detail?.size(max(0.0, available - accessoryWidth - trailingWidth - detailGap) * m.detailMaxShare)
            val rightWidth = (detailSize?.width ?: 0.0) + detailGap + trailingWidth + accessoryWidth
            val centerWidth = max(0.0, available - rightWidth)

            val titleHeight = input.title.height(centerWidth)
            val subtitleHeight = input.subtitle?.height(centerWidth)
            val centerHeight = titleHeight + (subtitleHeight?.let { m.subtitleSpacing + it } ?: 0.0)

            val contentHeight = listOf(
                centerHeight,
                if (input.hasIcon) m.iconSize else 0.0,
                detailSize?.height ?: 0.0,
                input.trailing?.height ?: 0.0,
                input.accessory?.height ?: 0.0,
            ).max()
            val rowHeight = max(m.minHeight, contentHeight + padV * 2)
            val midY = rowHeight / 2

            val icon = if (input.hasIcon) Rect(m.paddingHorizontal, midY - m.iconSize / 2, m.iconSize, m.iconSize) else null
            val title = Rect(centerX, midY - centerHeight / 2, centerWidth, titleHeight)
            val subtitle = subtitleHeight?.let { Rect(centerX, title.maxY + m.subtitleSpacing, centerWidth, it) }
            val rightX = width - m.paddingHorizontal - rightWidth
            val detail = detailSize?.let { Rect(rightX, midY - it.height / 2, it.width, it.height) }
            val trailing = input.trailing?.let {
                Rect(width - m.paddingHorizontal - accessoryWidth - trailingGap - it.width, midY - it.height / 2, it.width, it.height)
            }
            val accessory = input.accessory?.let { Rect(width - m.paddingHorizontal - it.width, midY - it.height / 2, it.width, it.height) }
            val divider = if (input.showsDivider) {
                val inset = m.dividerInset + if (input.hasIcon) m.paddingHorizontal + m.iconSize + 15 else m.paddingHorizontal
                Rect(inset, rowHeight, max(0.0, width - inset), m.dividerHeight)
            } else {
                null
            }
            return ItemRowLayout(
                Size(width, rowHeight + if (input.showsDivider) m.dividerHeight else 0.0),
                rowHeight, icon, title, subtitle, detail, trailing, accessory, divider,
            )
        }
    }
}
