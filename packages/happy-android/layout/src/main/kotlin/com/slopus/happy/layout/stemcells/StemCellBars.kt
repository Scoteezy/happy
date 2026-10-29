package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.HeaderScrim
import com.slopus.happy.layout.HomeHeaderLayout
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.swiftRound
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A conversation's header (happy-ios `StemCellChatHeaderLayout`), as
 * Telegram's chat header on iOS 26: floating glass over the transcript,
 * which scrolls under it. In home's 52-tall row under the status bar, the
 * back button is a 44 glass circle 16 in, where home's account picture is,
 * and the name is centred in a glass capsule that runs from 8 past the
 * circle to 16 short of the screen's right edge, with its status under it
 * in grey when there is one. The capsule opens the conversation's detail.
 *
 *     ( ‹ ) (       Pineapple poem       )
 *           (          Working           )
 *
 * Home's scrim reaches 8 below the row, as on every glass bar, and on here
 * past the capsule by the scrim's feather, so what scrolls under the header
 * has faded out by the time it meets the capsule. The transcript starts at
 * the row's foot.
 */
data class StemCellChatHeaderLayout(
    val back: Rect,
    val pill: Rect,
    /** In the screen, inside the capsule. */
    val title: Rect,
    val status: Rect?,
    /** The glass bars' scrim, 8 below the row. */
    val backdrop: Rect,
    /** The scrim drawn here: `backdrop`, reaching the feather past the capsule as tall as it gets (with a status), so it never moves. */
    val scrim: Rect,
    /** The transcript's top inset. */
    val listTop: Double,
    /** The widest the name may be. */
    val titleMaxWidth: Double,
) {
    object Metrics {
        const val rowHeight = HomeHeaderLayout.Metrics.contentHeight
        const val padding = HomeHeaderLayout.Metrics.paddingHorizontal
        const val control = HomeHeaderLayout.Metrics.controlSize
        /** Between the circle and the capsule. */
        const val gap = 8.0
        /** The capsule: 44 with the name alone, 48 with its status. */
        const val pillHeight = 44.0
        const val pillHeightWithStatus = 48.0
        const val pillPadding = 16.0
        /** The name's line and the status's, touching. */
        const val titleHeight = 22.0
        const val statusHeight = 16.0
        const val backdropOverhang = HomeHeaderLayout.Metrics.backdropOverhang
    }

    companion object {
        /** `titleWidth` and `statusWidth` are their single lines' widths; no status, no second line. */
        operator fun invoke(width: Double, safeTop: Double, titleWidth: Double, statusWidth: Double?): StemCellChatHeaderLayout {
            val m = Metrics
            val w = max(0.0, width)
            val rowTop = safeTop
            val back = Rect(m.padding, rowTop + (m.rowHeight - m.control) / 2, m.control, m.control)
            val left = m.padding + m.control + m.gap
            val pillWidth = max(0.0, w - left - m.padding)
            val titleMaxWidth = max(0.0, pillWidth - 2 * m.pillPadding)
            val title = min(ceil(titleWidth), titleMaxWidth)
            val status = statusWidth?.let { min(ceil(it), titleMaxWidth) }
            val height = if (status == null) m.pillHeight else m.pillHeightWithStatus
            val pill = Rect(left, rowTop + (m.rowHeight - height) / 2, pillWidth, height)
            val lines = m.titleHeight + (if (status == null) 0.0 else m.statusHeight)
            val top = pill.minY + swiftRound((height - lines) / 2)
            val backdrop = Rect(0.0, 0.0, w, rowTop + m.rowHeight + m.backdropOverhang)
            val tallest = rowTop + (m.rowHeight + m.pillHeightWithStatus) / 2
            return StemCellChatHeaderLayout(
                back = back,
                pill = pill,
                title = Rect(swiftRound(pill.midX - title / 2), top, title, m.titleHeight),
                status = status?.let { Rect(swiftRound(pill.midX - it / 2), top + m.titleHeight, it, m.statusHeight) },
                backdrop = backdrop,
                scrim = Rect(0.0, 0.0, w, max(backdrop.height, tallest + HeaderScrim.rampPoints)),
                listTop = rowTop + m.rowHeight,
                titleMaxWidth = titleMaxWidth,
            )
        }
    }
}

/**
 * The glass bar of settings and the screens around it (happy-ios
 * `StemCellsGlassBarLayout`), as a conversation's header is: in home's
 * 52-tall row under the status bar, back (or close) is a 44 glass circle
 * where home's picture is, the title is centred in a glass capsule, and
 * the actions share one glass capsule at the right, as home's do, split by
 * hairlines:
 *
 *     ( ‹ )        ( Appearance )        ( ⌕ │ ▦ )
 *
 * The first screen of a sheet closes from a glass circle at the right: the
 * row drops so the circle is as far from the sheet's top as from its side,
 * and the actions go left of it. The title's capsule keeps 8 clear of
 * whatever is beside it, on both sides alike, so it stays centred.
 * Searching, a field in a glass capsule takes the row, with a glass circle
 * at the right that ends the search. A scrim reaches 8 below the row; what
 * scrolls starts at the row's foot.
 */
data class StemCellsGlassBarLayout(
    /** Back; null for a screen without it. */
    val leading: Rect? = null,
    /** A sheet's close circle, at the right; null elsewhere. */
    val close: Rect? = null,
    /** The actions' capsule, rim included; null without actions. */
    val actions: Rect? = null,
    /** Each action, in the screen. */
    val buttons: List<Rect> = emptyList(),
    /** Between neighbouring actions. */
    val dividers: List<Rect> = emptyList(),
    val pill: Rect? = null,
    val title: Rect? = null,
    /** Searching: the field's capsule, the field in it, and the circle that ends the search. */
    val searchPill: Rect? = null,
    val field: Rect? = null,
    val cancel: Rect? = null,
    val backdrop: Rect,
    /** Where what scrolls under the bar starts. */
    val contentTop: Double,
    /** The widest the title may be. */
    val titleMaxWidth: Double,
) {
    object Metrics {
        const val rowHeight = StemCellChatHeaderLayout.Metrics.rowHeight
        const val padding = StemCellChatHeaderLayout.Metrics.padding
        const val control = StemCellChatHeaderLayout.Metrics.control
        const val border = HomeHeaderLayout.Metrics.controlBorder
        const val divider = HomeHeaderLayout.Metrics.actionDividerHeight
        const val gap = StemCellChatHeaderLayout.Metrics.gap
        const val pillHeight = StemCellChatHeaderLayout.Metrics.pillHeight
        const val pillPadding = StemCellChatHeaderLayout.Metrics.pillPadding
        const val titleHeight = StemCellChatHeaderLayout.Metrics.titleHeight
        const val backdropOverhang = StemCellChatHeaderLayout.Metrics.backdropOverhang
        /** The search field inside its capsule. */
        const val fieldInset = 12.0
        /** How far a sheet's row drops, for its close circle to sit `SheetCloseLayout.INSET` from the sheet's top. */
        const val sheetDrop = SheetCloseLayout.INSET - (rowHeight - control) / 2

        /** The height the bar adds to its screen's safe area. */
        fun span(closes: Boolean) = rowHeight + (if (closes) sheetDrop else 0.0)
    }

    companion object {
        /** `titleWidth` is the title's single line's width, null for none; `hairline` is one device pixel. */
        operator fun invoke(
            width: Double,
            safeTop: Double,
            hairline: Double,
            hasLeading: Boolean,
            actions: Int,
            titleWidth: Double?,
            searching: Boolean = false,
            closes: Boolean = false,
        ): StemCellsGlassBarLayout {
            val m = Metrics
            val w = max(0.0, width)
            val rowTop = safeTop + (if (closes) m.sheetDrop else 0.0)
            fun circle(x: Double) = Rect(x, rowTop + (m.rowHeight - m.control) / 2, m.control, m.control)
            val backdrop = Rect(0.0, 0.0, w, rowTop + m.rowHeight + m.backdropOverhang)
            val contentTop = rowTop + m.rowHeight
            if (searching) {
                val cancel = circle(w - m.padding - m.control)
                val searchPill = Rect(m.padding, cancel.minY, max(0.0, cancel.minX - m.gap - m.padding), m.control)
                return StemCellsGlassBarLayout(
                    cancel = cancel, searchPill = searchPill, field = searchPill.insetBy(m.fieldInset, 0.0),
                    backdrop = backdrop, contentTop = contentTop, titleMaxWidth = 0.0,
                )
            }
            val leading = if (hasLeading) circle(m.padding) else null
            val close = if (closes) circle(w - m.padding - m.control) else null
            val end = close?.let { it.minX - m.gap } ?: (w - m.padding)
            val buttons = ArrayList<Rect>()
            val dividers = ArrayList<Rect>()
            var capsule: Rect? = null
            if (actions > 0) {
                val n = actions.toDouble()
                val capsuleWidth = 2 * m.border + n * m.control + (n - 1) * hairline
                val capsuleHeight = 2 * m.border + m.control
                val frame = Rect(end - capsuleWidth, rowTop + (m.rowHeight - capsuleHeight) / 2, capsuleWidth, capsuleHeight)
                val y = frame.minY + m.border
                var x = frame.minX + m.border
                for (index in 0 until actions) {
                    if (index > 0) {
                        dividers += Rect(x, y + (m.control - m.divider) / 2, hairline, m.divider)
                        x += hairline
                    }
                    buttons += Rect(x, y, m.control, m.control)
                    x += m.control
                }
                capsule = frame
            }
            // As wide a margin on both sides as the wider of the two needs.
            val left = m.padding + m.control + m.gap
            val right = (capsule?.minX ?: close?.minX)?.let { w - it + m.gap } ?: left
            val side = max(left, right)
            val pillMax = max(0.0, w - 2 * side)
            val titleMaxWidth = max(0.0, pillMax - 2 * m.pillPadding)
            var pill: Rect? = null
            var title: Rect? = null
            if (titleWidth != null) {
                val text = min(ceil(titleWidth), titleMaxWidth)
                val pillWidth = min(pillMax, text + 2 * m.pillPadding)
                val frame = Rect(swiftRound((w - pillWidth) / 2), rowTop + (m.rowHeight - m.pillHeight) / 2, pillWidth, m.pillHeight)
                pill = frame
                title = Rect(swiftRound(frame.midX - text / 2), frame.minY + swiftRound((m.pillHeight - m.titleHeight) / 2), text, m.titleHeight)
            }
            return StemCellsGlassBarLayout(leading, close, capsule, buttons, dividers, pill, title, null, null, null, backdrop, contentTop, titleMaxWidth)
        }
    }
}

/**
 * A held row's preview and menu over the blurred sidebar (happy-ios
 * `StemCellsHoldLayout`), measured as Telegram's context controller places
 * a controller's preview and its actions.
 *
 * The preview is centred on the screen with the menu 10 under it, its right
 * edge on the preview's; when the two overrun the foot (10 above the bottom
 * safe area) both move up, and when that would take the preview above the
 * top (8 under the top safe area) the preview gives up height instead. A
 * row with no preview keeps its place, its menu under it, or over it when
 * there is no room under.
 *
 * The menu is 220–240 wide; each item is 11 above and below its title, its
 * icon in a 32 column 20 in and its title 60 in; groups are parted by a 20
 * tall separator, and the whole is 10 in from the top and bottom.
 */
data class StemCellsHoldLayout(
    /** Where the preview (or the held row, with none) shows. */
    val card: Rect,
    val menu: Rect,
) {
    object Metrics {
        const val spacing = 10.0
        const val edgeInset = 12.0
        const val bottomInset = 10.0
        const val topInset = 8.0
        const val cardCorner = 30.0
        const val menuMinWidth = 220.0
        const val menuMaxWidth = 240.0
        const val menuPadding = 10.0
        const val menuMaxCorner = 30.0
        const val itemVerticalInset = 11.0
        const val itemSideInset = 18.0
        const val iconSideInset = 20.0
        const val iconColumn = 32.0
        const val iconSpacing = 8.0
        const val titleLeading = 60.0
        const val separatorHeight = 20.0
        const val separatorInset = 18.0
        const val iconSize = 24.0
    }

    /** The menu, in its own space. */
    data class Menu(
        val size: com.slopus.happy.layout.Size,
        /** Each item's row, icon and title, by group. */
        val items: List<List<Item>>,
        /** The lines between groups. */
        val separators: List<Rect>,
        val corner: Double,
    ) {
        data class Item(val frame: Rect, val icon: Rect, val title: Rect)

        companion object {
            /** `titles` are each item's title width by group; `titleHeight` one line. */
            operator fun invoke(titles: List<List<Double>>, titleHeight: Double): Menu {
                val m = Metrics
                val widest = titles.flatten().maxOrNull() ?: 0.0
                val natural = ceil(widest) + m.itemSideInset + m.iconColumn + m.iconSideInset + m.iconSpacing
                val width = min(m.menuMaxWidth, max(m.menuMinWidth, natural))
                val lineHeight = ceil(titleHeight)
                val itemHeight = lineHeight + m.itemVerticalInset * 2
                var y = m.menuPadding
                val items = ArrayList<List<Item>>()
                val separators = ArrayList<Rect>()
                for ((index, group) in titles.withIndex()) {
                    if (index > 0) {
                        separators += Rect(m.separatorInset, y + kotlin.math.floor((m.separatorHeight - 1) / 2), width - m.separatorInset * 2, 1.0)
                        y += m.separatorHeight
                    }
                    items += group.map { titleWidth ->
                        val frame = Rect(0.0, y, width, itemHeight)
                        y += itemHeight
                        val icon = Rect(m.iconSideInset + kotlin.math.floor((m.iconColumn - m.iconSize) / 2), frame.minY + kotlin.math.floor((itemHeight - m.iconSize) / 2), m.iconSize, m.iconSize)
                        val title = Rect(m.titleLeading, frame.minY + m.itemVerticalInset, min(ceil(titleWidth), width - m.titleLeading - m.itemSideInset), lineHeight)
                        Item(frame, icon, title)
                    }
                }
                y += m.menuPadding
                return Menu(com.slopus.happy.layout.Size(width, y), items, separators, min(m.menuMaxCorner, y / 2))
            }
        }
    }

    companion object {
        /** `card` is the preview's size, null for a row without one, which then stays at `source`. */
        operator fun invoke(
            screen: com.slopus.happy.layout.Size,
            top: Double,
            bottom: Double,
            source: Rect,
            card: com.slopus.happy.layout.Size?,
            menu: com.slopus.happy.layout.Size,
        ): StemCellsHoldLayout {
            val m = Metrics
            val ceiling = top + m.topInset
            val floorY = screen.height - bottom - m.bottomInset
            if (card == null) {
                val right = min(screen.width - m.edgeInset, source.maxX)
                val x = max(m.edgeInset, right - menu.width)
                val under = source.maxY + m.spacing
                val y = if (under + menu.height <= floorY) under else max(ceiling, source.minY - m.spacing - menu.height)
                return StemCellsHoldLayout(source, Rect(x, y, menu.width, menu.height))
            }
            val room = max(0.0, floorY - ceiling - m.spacing - menu.height)
            val height = min(card.height, room)
            var cardY = kotlin.math.floor((screen.height - height) / 2)
            val cardX = kotlin.math.floor((screen.width - card.width) / 2)
            val overrun = cardY + height + m.spacing + menu.height - floorY
            if (overrun > 0) cardY -= overrun
            cardY = max(ceiling, cardY)
            val cardFrame = Rect(cardX, cardY, card.width, height)
            val x = max(m.edgeInset, min(screen.width - m.edgeInset, cardFrame.maxX) - menu.width)
            return StemCellsHoldLayout(cardFrame, Rect(x, cardFrame.maxY + m.spacing, menu.width, menu.height))
        }
    }
}

/**
 * A row's conversation previewed by a hold (happy-ios
 * `StemCellsPreviewLayout`), as Telegram's chat list previews a chat: the
 * screen less 12 a side, at most 400 wide on a regular width, and the
 * screen's height less 12 above and below, the top inset and the bottom
 * safe area, at most 460. In it the conversation's newest messages rest at
 * the foot under its name in a capsule, 10 from the top, 44 tall and 16 in
 * on either side of the name. Above the list the card's own ground covers
 * the messages down to the capsule's foot and lets them in over the next
 * 24, so older ones go into the card unseen.
 */
data class StemCellsPreviewLayout(
    val pill: Rect,
    val title: Rect,
    /** The messages' insets inside the card. */
    val listTop: Double,
    val listBottom: Double,
    /** The ground over the messages: whole down to `fadeClear` (the capsule's foot), gone by `fadeOpaque`. */
    val fadeClear: Double,
    val fadeOpaque: Double,
) {
    object Metrics {
        const val inset = 12.0
        const val maxWidth = 400.0
        const val maxHeight = 460.0
        const val pillTop = 10.0
        const val pillHeight = 44.0
        const val pillPadding = 16.0
        /** The least room left beside the capsule. */
        const val pillMargin = 16.0
        /** Between the capsule and the messages under it, and under the last. */
        const val listGap = 8.0
        /** How far under the capsule the messages take to come in whole. */
        const val fade = 24.0
    }

    companion object {
        /** The card's size on a screen this big. */
        fun size(screen: com.slopus.happy.layout.Size, top: Double, bottom: Double, regular: Boolean): com.slopus.happy.layout.Size {
            val m = Metrics
            var width = max(0.0, screen.width - m.inset * 2)
            if (regular) width = min(width, m.maxWidth)
            val height = min(m.maxHeight, max(0.0, screen.height - m.inset * 2 - top - bottom))
            return com.slopus.happy.layout.Size(width, height)
        }

        operator fun invoke(size: com.slopus.happy.layout.Size, titleSize: com.slopus.happy.layout.Size): StemCellsPreviewLayout {
            val m = Metrics
            val room = max(0.0, size.width - m.pillMargin * 2 - m.pillPadding * 2)
            val titleWidth = min(ceil(titleSize.width), room)
            val width = titleWidth + m.pillPadding * 2
            val pill = Rect(kotlin.math.floor((size.width - width) / 2), m.pillTop, width, m.pillHeight)
            val titleHeight = ceil(titleSize.height)
            val title = Rect(pill.minX + m.pillPadding, pill.minY + kotlin.math.floor((m.pillHeight - titleHeight) / 2), titleWidth, titleHeight)
            return StemCellsPreviewLayout(pill, title, pill.maxY + m.listGap, m.listGap, pill.maxY, pill.maxY + m.fade)
        }
    }
}
