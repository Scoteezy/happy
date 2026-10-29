package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.swiftRound
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Stem Cells settings' profiles as story circles (happy-ios
 * `StemCellsProfileCarousel`), after Telegram's settings: the chosen one
 * centred, larger and ringed, the others smaller either side; each grows as
 * it nears the middle, so a drag scales them continuously and a stop snaps
 * to one.
 */
object StemCellsProfileCarousel {
    object Metrics {
        const val selected = 92.0
        const val other = 60.0
        /** Centre to centre. */
        const val pitch = 84.0
        const val ringWidth = 3.0
        /** Between the picture and its ring. */
        const val ringGap = 3.0
        /** The strip: the chosen circle with its ring. */
        const val height = selected + 2 * (ringWidth + ringGap)
    }

    data class Item(
        /** The picture, in the strip's content space. */
        val frame: Rect,
        /** 1 in the middle, 0 a pitch or more away: the ring's alpha. */
        val emphasis: Double,
    )

    fun contentWidth(count: Int, width: Double) = width + max(0, count - 1) * Metrics.pitch

    /** The offset that centres item `index`. */
    fun offset(index: Int) = index * Metrics.pitch

    /** The item nearest the middle at `offset`. */
    fun index(offset: Double, count: Int): Int {
        if (count <= 0) return 0
        return min(count - 1, max(0, swiftRound(offset / Metrics.pitch).toInt()))
    }

    /** Where a flick that would stop at `target` comes to rest: on an item. */
    fun snapped(target: Double, count: Int) = offset(index(target, count))

    fun item(index: Int, offset: Double, width: Double): Item {
        val m = Metrics
        val center = width / 2 + index * m.pitch
        val distance = abs(center - (offset + width / 2))
        val emphasis = 1 - min(1.0, distance / m.pitch)
        val side = m.other + (m.selected - m.other) * emphasis
        return Item(Rect(center - side / 2, (m.height - side) / 2, side, side), emphasis)
    }
}

/**
 * Stem Cells settings (happy-ios `StemCellsSettingsLayout`), after
 * Telegram's settings (techniques only): under the glass bar, scrolling,
 * the profiles as a strip of circles, the chosen one's name and line under
 * it, and grouped cards.
 */
data class StemCellsSettingsLayout(
    val strip: Rect,
    val name: Rect,
    val subtitle: Rect,
    val cards: List<Card>,
    /** "Nothing found", while searching. */
    val empty: Rect,
    val contentHeight: Double,
) {
    object Metrics {
        const val stripTop = 8.0
        const val nameGap = 12.0
        const val subtitleGap = 2.0
        const val textSide = 24.0
        const val cardsTop = 28.0
        /** Telegram's inset cards on a phone, in its glass style. */
        const val side = 16.0
        const val radius = 26.0
        const val cardGap = 24.0
        const val row = 52.0
        /** The coloured square a row's glyph sits on, and the glyph. */
        const val tile = 30.0
        const val tileRadius = 8.0
        const val glyph = 20.0
        /** The title's start with a tile (Telegram's 16 + 46), and without. */
        const val textX = 62.0
        const val plainTextX = 16.0
        const val titleHeight = 22.0
        val chevron = Size(8.0, 14.0)
        const val trailing = 16.0
        const val valueGap = 8.0
        val toggle = Size(63.0, 28.0)
        const val separator = 1.0 / 3
        const val bottom = 32.0
        /** Searching with nothing found: the line, under the bar. */
        const val emptyTop = 32.0
        const val emptyHeight = 24.0
    }

    enum class Accessory { Chevron, Toggle, None }

    data class RowSpec(
        val hasIcon: Boolean,
        /** The value's width before the chevron or at the end, if any. */
        val valueWidth: Double? = null,
        val accessory: Accessory,
    )

    /** One row, in its card's space. */
    data class Row(
        val box: Rect,
        val icon: Rect?,
        val title: Rect,
        val value: Rect?,
        val chevron: Rect?,
        val toggle: Rect?,
        /** Under the row, from the title's start; null under a card's last row. */
        val separator: Rect?,
    )

    data class Card(val frame: Rect, val rows: List<Row>)

    companion object {
        /** Searching, the strip and the name are hidden and the cards move up into the strip's place. */
        operator fun invoke(width: Double, nameHeight: Double, subtitleHeight: Double, cards: List<List<RowSpec>>, searching: Boolean = false): StemCellsSettingsLayout {
            val m = Metrics
            val strip = Rect(0.0, m.stripTop, width, StemCellsProfileCarousel.Metrics.height)
            val textWidth = max(0.0, width - 2 * m.textSide)
            val name = Rect(m.textSide, strip.maxY + m.nameGap, textWidth, nameHeight)
            val subtitle = Rect(m.textSide, name.maxY + m.subtitleGap, textWidth, subtitleHeight)
            val cardWidth = max(0.0, width - 2 * m.side)
            var y = if (searching) m.stripTop else subtitle.maxY + m.cardsTop
            val empty = Rect(0.0, m.stripTop + m.emptyTop, width, m.emptyHeight)
            val placed = ArrayList<Card>()
            for (spec in cards) {
                if (spec.isEmpty()) continue
                val rows = spec.mapIndexed { index, row -> row(row, index * m.row, cardWidth, index == spec.size - 1) }
                placed += Card(Rect(m.side, y, cardWidth, spec.size * m.row), rows)
                y += spec.size * m.row + m.cardGap
            }
            val contentHeight = (placed.lastOrNull()?.frame?.maxY ?: (if (searching) m.stripTop else subtitle.maxY)) + m.bottom
            return StemCellsSettingsLayout(strip, name, subtitle, placed, empty, contentHeight)
        }

        fun row(spec: RowSpec, y: Double, width: Double, last: Boolean): Row {
            val m = Metrics
            val textX = if (spec.hasIcon) m.textX else m.plainTextX
            var right = width - m.trailing
            var chevron: Rect? = null
            var toggle: Rect? = null
            when (spec.accessory) {
                Accessory.Chevron -> {
                    chevron = Rect(right - m.chevron.width, y + (m.row - m.chevron.height) / 2, m.chevron.width, m.chevron.height)
                    right -= m.chevron.width + m.valueGap
                }
                Accessory.Toggle -> {
                    toggle = Rect(right - m.toggle.width, y + (m.row - m.toggle.height) / 2, m.toggle.width, m.toggle.height)
                    right -= m.toggle.width + m.valueGap
                }
                Accessory.None -> Unit
            }
            var value: Rect? = null
            if (spec.valueWidth != null) {
                val fitted = min(ceil(spec.valueWidth), max(0.0, (right - textX) / 2))
                value = Rect(right - fitted, y + (m.row - m.titleHeight) / 2, fitted, m.titleHeight)
                right -= fitted + m.valueGap
            }
            return Row(
                box = Rect(0.0, y, width, m.row),
                icon = if (spec.hasIcon) Rect((m.textX - m.tile) / 2, y + (m.row - m.tile) / 2, m.tile, m.tile) else null,
                title = Rect(textX, y + (m.row - m.titleHeight) / 2, max(0.0, right - textX), m.titleHeight),
                value = value,
                chevron = chevron,
                toggle = toggle,
                separator = if (last) null else Rect(textX, y + m.row - m.separator, width - textX, m.separator),
            )
        }
    }
}

/**
 * The QR sheet over the chosen profile (happy-ios `StemCellsQRSheetLayout`),
 * after Telegram's QR screen: the picture and the name in capitals above a
 * sheet with a close button, a title, a line, and the two buttons. Nothing
 * of a profile is safe to show as a code, so no code card is drawn and Scan
 * is the first button.
 */
data class StemCellsQRSheetLayout(
    val avatar: Rect,
    val name: Rect,
    val sheet: Rect,
    /** The rest in the sheet's own space. */
    val close: Rect,
    val title: Rect,
    val text: Rect,
    val primary: Rect,
    val secondary: Rect,
) {
    object Metrics {
        const val avatar = 100.0
        const val nameGap = 16.0
        /** Telegram's sheet: 38 round, its title in a 54 band. */
        const val radius = 38.0
        const val close = SheetCloseLayout.SIZE
        const val titleBand = 54.0
        const val titleCenter = 36.0
        const val titleHeight = 22.0
        const val textSide = 30.0
        const val textGap = 4.0
        const val buttonsGap = 24.0
        const val button = 52.0
        const val buttonGap = 10.0
        const val bottom = 16.0
    }

    companion object {
        operator fun invoke(size: Size, safeTop: Double, safeBottom: Double, nameHeight: Double, text: Measurable): StemCellsQRSheetLayout {
            val m = Metrics
            val inner = max(0.0, size.width - 2 * m.textSide)
            val close = SheetCloseLayout.frame(size.width)
            val title = Rect(m.textSide + m.close, m.titleCenter - m.titleHeight / 2, max(0.0, size.width - 2 * (m.textSide + m.close)), m.titleHeight)
            val textFrame = Rect(m.textSide, m.titleBand + m.textGap, inner, text.height(inner))
            val primary = Rect(m.textSide, textFrame.maxY + m.buttonsGap, inner, m.button)
            val secondary = Rect(m.textSide, primary.maxY + m.buttonGap, inner, m.button)
            val height = secondary.maxY + max(safeBottom, m.bottom)
            val sheet = Rect(0.0, size.height - height, size.width, height)
            val block = m.avatar + m.nameGap + nameHeight
            val top = safeTop + max(0.0, swiftRound((sheet.minY - safeTop - block) / 2))
            val avatar = Rect(swiftRound((size.width - m.avatar) / 2), top, m.avatar, m.avatar)
            return StemCellsQRSheetLayout(avatar, Rect(m.textSide, avatar.maxY + m.nameGap, inner, nameHeight), sheet, close, title, textFrame, primary, secondary)
        }
    }
}

/**
 * A conversation's detail (happy-ios `StemCellSessionDetailLayout`), opened
 * from its header's capsule: the legacy app's session screen made over in
 * Settings' glass style. Under the glass bar, scrolling: the mark, the name
 * (renamed where it stands) and its line; tiles two to a row (an odd last
 * one the row's width); the actions as Settings' rows; then MEDIA (three to
 * a row, Telegram's grid, and Show more), LINKS, FAMILY (the sidebar's
 * tree) and ACTIVITY (what is at work, then what settled, folded). A
 * section with nothing in it isn't drawn.
 */
data class StemCellSessionDetailLayout(
    val mark: Rect,
    val name: Rect,
    val subtitle: Rect,
    val tiles: List<Tile>,
    val actions: StemCellsSettingsLayout.Card,
    val mediaHeading: Rect?,
    val media: MediaCard?,
    val linksHeading: Rect?,
    val links: LinksCard?,
    val familyHeading: Rect?,
    val family: Rect?,
    val activityHeading: Rect?,
    /** What is at work: the children, then the terminals. */
    val running: ActivityCard?,
    val completed: ActivityCard?,
    val contentHeight: Double,
) {
    object Metrics {
        const val top = 12.0
        const val mark = 88.0
        const val nameGap = 14.0
        const val subtitleGap = 2.0
        const val textSide = StemCellsSettingsLayout.Metrics.textSide
        const val side = StemCellsSettingsLayout.Metrics.side
        const val radius = StemCellsSettingsLayout.Metrics.radius
        const val tilesTop = 24.0
        const val tileGap = 10.0
        const val tileHeight = 80.0
        const val tileRadius = 22.0
        const val tileInset = 16.0
        const val valueTop = 14.0
        const val valueHeight = 30.0
        const val labelHeight = 18.0
        const val cardGap = StemCellsSettingsLayout.Metrics.cardGap
        /** A section's heading over its card: Settings' footnote caps, a card's text start in. */
        const val headingHeight = 18.0
        const val headingGap = 8.0
        const val headingInset = 16.0
        /** The media grid: three to a row, square, 2 apart, as Telegram's. */
        const val mediaColumns = 3
        const val mediaGap = 2.0
        /** A link, an agent or a terminal: what it is over a quieter line. */
        const val activityRow = 62.0
        const val activityTitleTop = 10.0
        const val activityTitleHeight = 22.0
        const val activityMetaHeight = 18.0
        const val bottom = 32.0
    }

    data class Tile(
        val frame: Rect,
        /** In the tile's own space. */
        val value: Rect,
        val label: Rect,
    )

    /** One link, agent or terminal, in its card's space. */
    data class ActivityRow(val box: Rect, val icon: Rect, val title: Rect, val meta: Rect, val separator: Rect?)

    data class ActivityCard(
        val frame: Rect,
        /** The fold's row on the settled card: "Completed", its count, its chevron. */
        val disclosure: StemCellsSettingsLayout.Row?,
        val rows: List<ActivityRow>,
    )

    /** The media grid's card: its cells, and "Show more" under them, in the card's space. */
    data class MediaCard(val frame: Rect, val cells: List<Rect>, val more: StemCellsSettingsLayout.Row?)

    /** The links' card: a row each, and "Show more" under them. */
    data class LinksCard(val frame: Rect, val rows: List<ActivityRow>, val more: StemCellsSettingsLayout.Row?)

    /** What a shared section shows: `count` of it, and whether there is more. */
    data class Shown(val count: Int, val more: Boolean)

    companion object {
        /**
         * `media` and `links` what the shared sections show (null or none:
         * not drawn); `running` rows at work, `completed` settled (shown
         * under the fold while `open`, its count `countWidth` wide);
         * `actions` Settings rows with a glyph each.
         */
        operator fun invoke(
            width: Double,
            nameHeight: Double,
            subtitleHeight: Double,
            tiles: Int,
            actions: Int,
            media: Shown? = null,
            links: Shown? = null,
            family: Double?,
            running: Int,
            completed: Int,
            countWidth: Double = 0.0,
            open: Boolean,
        ): StemCellSessionDetailLayout {
            val m = Metrics
            val settings = StemCellsSettingsLayout.Metrics
            val cardWidth = max(0.0, width - 2 * m.side)
            val textWidth = max(0.0, width - 2 * m.textSide)
            val mark = Rect(swiftRound((width - m.mark) / 2), m.top, m.mark, m.mark)
            val name = Rect(m.textSide, mark.maxY + m.nameGap, textWidth, nameHeight)
            val subtitle = Rect(m.textSide, name.maxY + m.subtitleGap, textWidth, subtitleHeight)

            var y = subtitle.maxY + m.tilesTop
            val tileWidth = max(0.0, floor((cardWidth - m.tileGap) / 2))
            val count = max(0, tiles)
            val tileFrames = (0 until count).map { index ->
                val column = index % 2
                val row = index / 2
                // An odd last tile takes the row; the right column ends on the
                // cards' edge, whatever the rounding.
                val alone = index == count - 1 && column == 0
                val x = if (column == 0) m.side else m.side + cardWidth - tileWidth
                val frameWidth = if (alone) cardWidth else tileWidth
                val inner = max(0.0, frameWidth - 2 * m.tileInset)
                Tile(
                    Rect(x, y + row * (m.tileHeight + m.tileGap), frameWidth, m.tileHeight),
                    Rect(m.tileInset, m.valueTop, inner, m.valueHeight),
                    Rect(m.tileInset, m.valueTop + m.valueHeight, inner, m.labelHeight),
                )
            }
            tileFrames.lastOrNull()?.let { y = it.frame.maxY + m.cardGap }

            val actionRows = (0 until max(0, actions)).map { index ->
                StemCellsSettingsLayout.row(StemCellsSettingsLayout.RowSpec(true, accessory = StemCellsSettingsLayout.Accessory.None), index * settings.row, cardWidth, index == actions - 1)
            }
            val actionCard = StemCellsSettingsLayout.Card(Rect(m.side, y, cardWidth, max(0, actions) * settings.row), actionRows)
            if (actions > 0) y = actionCard.frame.maxY + m.cardGap

            fun heading(): Rect {
                val frame = Rect(m.side + m.headingInset, y, max(0.0, cardWidth - 2 * m.headingInset), m.headingHeight)
                y = frame.maxY + m.headingGap
                return frame
            }
            /** "Show more" at `top`, the card's last row. */
            fun more(shown: Shown, top: Double) =
                if (shown.more) StemCellsSettingsLayout.row(StemCellsSettingsLayout.RowSpec(false, accessory = StemCellsSettingsLayout.Accessory.None), top, cardWidth, true) else null
            /** `followed`, the last row keeps its hairline for what comes under it. */
            fun listRows(count: Int, top: Double, followed: Boolean = false): List<ActivityRow> {
                val textX = settings.textX
                val tile = settings.tile
                return (0 until max(0, count)).map { index ->
                    val rowY = top + index * m.activityRow
                    val titleY = rowY + m.activityTitleTop
                    val rowText = max(0.0, cardWidth - textX - settings.trailing)
                    val last = index == count - 1 && !followed
                    ActivityRow(
                        Rect(0.0, rowY, cardWidth, m.activityRow),
                        Rect((textX - tile) / 2, rowY + (m.activityRow - tile) / 2, tile, tile),
                        Rect(textX, titleY, rowText, m.activityTitleHeight),
                        Rect(textX, titleY + m.activityTitleHeight, rowText, m.activityMetaHeight),
                        if (last) null else Rect(textX, rowY + m.activityRow - settings.separator, cardWidth - textX, settings.separator),
                    )
                }
            }

            var mediaHeading: Rect? = null
            var mediaCard: MediaCard? = null
            if (media != null && media.count > 0) {
                mediaHeading = heading()
                val columns = m.mediaColumns
                val cell = max(0.0, floor((cardWidth - (columns - 1) * m.mediaGap) / columns))
                val cells = (0 until media.count).map { index ->
                    val column = index % columns
                    val row = index / columns
                    // The last column ends on the card's edge.
                    val x = if (column == columns - 1) cardWidth - cell else column * (cell + m.mediaGap)
                    Rect(x, row * (cell + m.mediaGap), cell, cell)
                }
                val grid = cells.lastOrNull()?.maxY ?: 0.0
                val moreRow = more(media, grid)
                mediaCard = MediaCard(Rect(m.side, y, cardWidth, moreRow?.box?.maxY ?: grid), cells, moreRow)
                y = mediaCard.frame.maxY + m.cardGap
            }

            var linksHeading: Rect? = null
            var linksCard: LinksCard? = null
            if (links != null && links.count > 0) {
                linksHeading = heading()
                val shown = listRows(links.count, 0.0, links.more)
                val end = shown.lastOrNull()?.box?.maxY ?: 0.0
                val moreRow = more(links, end)
                linksCard = LinksCard(Rect(m.side, y, cardWidth, moreRow?.box?.maxY ?: end), shown, moreRow)
                y = linksCard.frame.maxY + m.cardGap
            }

            var familyHeading: Rect? = null
            var familyFrame: Rect? = null
            if (family != null) {
                familyHeading = heading()
                familyFrame = Rect(m.side, y, cardWidth, family)
                y += family + m.cardGap
            }

            val activityHeading = if (running > 0 || completed > 0) heading() else null
            var runningCard: ActivityCard? = null
            if (running > 0) {
                runningCard = ActivityCard(Rect(m.side, y, cardWidth, running * m.activityRow), null, listRows(running, 0.0))
                y = runningCard.frame.maxY + m.cardGap
            }
            var completedCard: ActivityCard? = null
            if (completed > 0) {
                val row = settings.row
                // The count sits before the chevron, as a settings value does.
                val disclosure = StemCellsSettingsLayout.row(StemCellsSettingsLayout.RowSpec(true, countWidth, StemCellsSettingsLayout.Accessory.Chevron), 0.0, cardWidth, !open)
                val shown = if (open) completed else 0
                completedCard = ActivityCard(Rect(m.side, y, cardWidth, row + shown * m.activityRow), disclosure, listRows(shown, row))
                y = completedCard.frame.maxY + m.cardGap
            }
            return StemCellSessionDetailLayout(
                mark, name, subtitle, tileFrames, actionCard, mediaHeading, mediaCard, linksHeading, linksCard,
                familyHeading, familyFrame, activityHeading, runningCard, completedCard, y - m.cardGap + m.bottom,
            )
        }
    }
}
