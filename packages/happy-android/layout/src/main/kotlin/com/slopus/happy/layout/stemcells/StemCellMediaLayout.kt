package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.swiftRound
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Desktop's `mediaLayout.ts` (happy-ios `StemCellMediaFlow`): where
 * pictures shown together go, and how big each one is. Nothing crops:
 * every picture keeps its own shape. A plan is made once from the pictures'
 * shapes alone, then drawn at a measure.
 *
 * Two kinds of row: `Cells`, equal columns, each picture at its column's
 * width and never past its own point size or the row's height cap; and
 * `Justified`, pictures sharing one height and filling the width together.
 */
object StemCellMediaFlow {
    /** What the layout knows about one picture before its bytes arrive. */
    data class Image(
        /** Intrinsic pixel size, when the producer stated it. */
        val width: Double? = null,
        val height: Double? = null,
        /** Pixels per point: a 2× capture is never drawn past half its pixels. */
        val scale: Double? = null,
        /** The shape the thumbhash's header carries, for a size not stated. */
        val hashedRatio: Double? = null,
    )

    /** The bounds one surface draws its pictures within. */
    data class Limits(
        /** A picture on a row of its own, and a one-column grid. */
        val singleMaxHeight: Double,
        /** A two-column grid's cells. */
        val cellMaxHeight: Double,
        /** A justified row's shared height. */
        val rowMaxHeight: Double,
    )

    /** What a tool call presents, at the transcript's full measure. */
    val tool = Limits(640.0, 480.0, 480.0)
    /** Photos attached to a message. */
    val message = Limits(360.0, 320.0, 280.0)
    /** The most photos a message shows inline. */
    const val MESSAGE_MAX = 4
    /** A message's photos stay a bubble's width, not the transcript's. */
    const val MESSAGE_MAX_WIDTH = 560.0

    const val GAP = 8.0
    /** A recording's play button, a circle in the middle of its tile. */
    const val PLAY_SIZE = 48.0

    fun playButton(tile: Size) = Rect(swiftRound((tile.width - PLAY_SIZE) / 2), swiftRound((tile.height - PLAY_SIZE) / 2), PLAY_SIZE, PLAY_SIZE)

    /** The narrowest a picture is drawn because its height is capped. */
    const val MIN_WIDTH = 160.0
    internal const val EXTREME_WIDE = 3.0
    internal const val EXTREME_NARROW = 0.4
    internal const val JUSTIFIED_REFERENCE_WIDTH = 720.0
    internal const val JUSTIFIED_TARGET_HEIGHT = 300.0
    internal const val JUSTIFIED_MAX_PER_ROW = 4
    internal const val FALLBACK_RATIO = 4.0 / 3

    data class Tile(
        val index: Int,
        /** Width over height. */
        val ratio: Double,
        /** The widest this tile is ever drawn. */
        val maxWidth: Double,
    )

    sealed interface Row {
        data class Cells(val columns: Int, val maxHeight: Double, val tiles: List<Tile>) : Row
        data class Justified(val maxHeight: Double, val tiles: List<Tile>) : Row
    }

    data class Plan(val rows: List<Row>)

    /** A picture's width over height: its stated size, else its thumbhash, else 4:3. */
    fun ratio(image: Image): Double {
        val width = image.width
        val height = image.height
        if (width != null && height != null && width > 0 && height > 0) return width / height
        return image.hashedRatio ?: FALLBACK_RATIO
    }

    /** Its pixel size over its density; null when no size was stated. */
    fun pointSize(image: Image): Size? {
        val width = image.width ?: return null
        val height = image.height ?: return null
        if (width <= 0 || height <= 0) return null
        val scale = (image.scale ?: 1.0).takeIf { it > 0 } ?: 1.0
        return Size(width / scale, height / scale)
    }

    internal fun cellTile(image: Image, index: Int, maxHeight: Double): Tile {
        val ratio = ratio(image)
        val native = pointSize(image)?.width ?: Double.POSITIVE_INFINITY
        return Tile(index, ratio, min(native, max(maxHeight * ratio, MIN_WIDTH)))
    }

    /** The grid the producer asked for (`columns` > 0), or justified rows. */
    fun plan(images: List<Image>, columns: Int, limits: Limits = tool): Plan =
        if (columns > 0) columnsPlan(images, columns, limits) else justifiedPlan(images, limits)

    fun columnsPlan(images: List<Image>, columns: Int, limits: Limits = tool): Plan {
        val maxHeight = if (columns == 1) limits.singleMaxHeight else limits.cellMaxHeight
        val rows = ArrayList<Row>()
        var start = 0
        while (start < images.size) {
            val end = min(images.size, start + columns)
            rows += Row.Cells(columns, maxHeight, (start until end).map { cellTile(images[it], it, maxHeight) })
            start += columns
        }
        return Plan(rows)
    }

    /**
     * A picture of extreme shape takes a row of its own; the runs between
     * are cut into the rows whose heights land closest to the target.
     */
    fun justifiedPlan(images: List<Image>, limits: Limits = tool): Plan {
        if (images.size == 1) {
            return Plan(listOf(Row.Cells(1, limits.singleMaxHeight, listOf(cellTile(images[0], 0, limits.singleMaxHeight)))))
        }
        val rows = ArrayList<Row>()
        var run = ArrayList<Int>()
        fun flush() {
            for (row in partition(run.map { ratio(images[it]) })) rows += justifiedRow(images, row.map { run[it] }, limits)
            run = ArrayList()
        }
        for ((index, image) in images.withIndex()) {
            val ratio = ratio(image)
            if (ratio <= EXTREME_WIDE && ratio >= EXTREME_NARROW) {
                run += index
                continue
            }
            flush()
            rows += Row.Cells(1, limits.singleMaxHeight, listOf(cellTile(image, index, limits.singleMaxHeight)))
        }
        flush()
        return Plan(rows)
    }

    internal fun justifiedRow(images: List<Image>, indexes: List<Int>, limits: Limits): Row {
        var maxHeight = limits.rowMaxHeight
        for (index in indexes) pointSize(images[index])?.let { maxHeight = min(maxHeight, it.height) }
        return Row.Justified(maxHeight, indexes.map { index ->
            val ratio = ratio(images[index])
            Tile(index, ratio, maxHeight * ratio)
        })
    }

    /**
     * The cut of `ratios`, in order, into rows of at most four that
     * minimises the summed squared distance of each row's height from the target.
     */
    internal fun partition(ratios: List<Double>): List<List<Int>> {
        val count = ratios.size
        if (count == 0) return emptyList()
        val best = DoubleArray(count + 1) { if (it == 0) 0.0 else Double.POSITIVE_INFINITY }
        val cut = IntArray(count + 1)
        for (end in 1..count) {
            var sum = 0.0
            var start = end - 1
            while (start >= max(0, end - JUSTIFIED_MAX_PER_ROW)) {
                sum += ratios[start]
                val size = (end - start).toDouble()
                val height = (JUSTIFIED_REFERENCE_WIDTH - (size - 1) * GAP) / sum
                val cost = best[start] + (height - JUSTIFIED_TARGET_HEIGHT) * (height - JUSTIFIED_TARGET_HEIGHT)
                if (cost < best[end]) {
                    best[end] = cost
                    cut[end] = start
                }
                start -= 1
            }
        }
        val rows = ArrayList<List<Int>>()
        var end = count
        while (end > 0) {
            rows.add(0, (cut[end] until end).toList())
            end = cut[end]
        }
        return rows
    }

    class Geometry(val frames: Map<Int, Rect>, val height: Double)

    /**
     * Every tile's box with the plan drawn at `measure`, by index, and the
     * height it takes. `alignEnd` pushes short rows and cells to the right,
     * as a person's own photos are.
     */
    fun geometry(plan: Plan, measure: Double, alignEnd: Boolean = false): Geometry {
        val frames = HashMap<Int, Rect>()
        var y = 0.0
        for ((rowIndex, row) in plan.rows.withIndex()) {
            if (rowIndex > 0) y += GAP
            var rowHeight = 0.0
            when (row) {
                is Row.Cells -> {
                    val column = (measure - (row.columns - 1) * GAP) / row.columns
                    // At the end, the row's cells sit against the right edge.
                    val shift = if (alignEnd) measure - (row.tiles.size * (column + GAP) - GAP) else 0.0
                    for ((cell, tile) in row.tiles.withIndex()) {
                        val width = min(column, tile.maxWidth)
                        val height = min(width / tile.ratio, row.maxHeight)
                        var x = cell * (column + GAP) + shift
                        if (alignEnd) x += column - width
                        frames[tile.index] = Rect(x, y, width, height)
                        rowHeight = max(rowHeight, height)
                    }
                }
                is Row.Justified -> {
                    val free = measure - (row.tiles.size - 1) * GAP
                    val sum = row.tiles.sumOf { it.ratio }
                    rowHeight = min(free / sum, row.maxHeight)
                    val used = row.tiles.sumOf { rowHeight * it.ratio } + (row.tiles.size - 1) * GAP
                    var x = if (alignEnd) measure - used else 0.0
                    for (tile in row.tiles) {
                        val width = rowHeight * tile.ratio
                        frames[tile.index] = Rect(x, y, width, rowHeight)
                        x += width + GAP
                    }
                }
            }
            y += rowHeight
        }
        return Geometry(frames, y)
    }

    /**
     * The box a recording is drawn in, from its intrinsic size alone: scaled
     * down into 380 × 320, never up; 380 × 214 when none was stated.
     */
    fun videoBox(width: Double?, height: Double?): Size {
        if (width == null || height == null || width <= 0 || height <= 0) return Size(380.0, 214.0)
        val ratio = width / height
        val boxWidth = min(width, 380.0)
        val boxHeight = boxWidth / ratio
        if (boxHeight <= 320) return Size(swiftRound(boxWidth), swiftRound(boxHeight))
        return Size(swiftRound(min(max(320 * ratio, 240.0), boxWidth)), 320.0)
    }
}

/**
 * One tool call's pictures and recordings, as desktop's ConversationMedia
 * draws them (happy-ios `StemCellMediaLayout`): every picture first,
 * together, as one flow; then each recording at its own box, no wider than
 * the measure.
 */
data class StemCellMediaLayout(
    /** One frame per item, in the items' order. */
    val frames: List<Rect>,
    val size: Size,
) {
    data class Item(val isVideo: Boolean, val image: StemCellMediaFlow.Image)

    companion object {
        /** `columns` 0 lets the pictures fall into justified rows. */
        operator fun invoke(
            width: Double,
            items: List<Item>,
            columns: Int,
            limits: StemCellMediaFlow.Limits = StemCellMediaFlow.tool,
            alignEnd: Boolean = false,
        ): StemCellMediaLayout {
            val gap = StemCellMediaFlow.GAP
            val frames = MutableList(items.size) { Rect.Zero }
            val pictures = items.indices.filter { !items[it].isVideo }
            var y = 0.0
            if (pictures.isNotEmpty()) {
                val plan = StemCellMediaFlow.plan(pictures.map { items[it].image }, columns, limits)
                val flow = StemCellMediaFlow.geometry(plan, width, alignEnd)
                for ((position, index) in pictures.withIndex()) frames[index] = flow.frames[position] ?: Rect.Zero
                y = flow.height
            }
            for (index in items.indices) {
                if (!items[index].isVideo) continue
                if (y > 0) y += gap
                var box = StemCellMediaFlow.videoBox(items[index].image.width, items[index].image.height)
                if (box.width > width && box.width > 0) box = Size(width, swiftRound(box.height * width / box.width))
                frames[index] = Rect(if (alignEnd) width - box.width else 0.0, y, box.width, box.height)
                y += box.height
            }
            val used = frames.maxOfOrNull { it.maxX } ?: 0.0
            return StemCellMediaLayout(frames, Size(if (alignEnd) width else used, y))
        }
    }
}

/**
 * C-311 SidebarProposal (happy-ios `StemCellSidebarProposalLayout`;
 * `SidebarProposal.tsx`, `sidebar-proposal.css`): a sidebar Prioritizing
 * proposed, in its chat, at the column's left. The card holds the real
 * sidebar rows at their full height, never scrolled:
 *
 *     card    column width, max 360 · 1pt hairline · 12 radius · 12 padding · gap 8
 *     header  20 tall: "Proposed sidebar", then the version, muted
 *     frame   the rows on the sidebar's ground, 8 radius
 *     footer  28 tall whatever it holds: the two answers at the right, a
 *             notice left of them, or one muted line once answered, so
 *             answering moves nothing
 */
data class StemCellSidebarProposalLayout(
    val size: Size,
    val title: Rect,
    /** The rest of the header's line, after the title. */
    val version: Rect,
    /** The rows' ground; the sidebar inside it lays itself out at its width. */
    val frame: Rect,
    val footer: Rect,
    val keep: Rect? = null,
    val use: Rect? = null,
    val notice: Rect? = null,
    val status: Rect? = null,
) {
    object Metrics {
        const val maxWidth = 360.0
        const val padding = 12.0
        const val radius = 12.0
        const val border = 1.0
        const val gap = 8.0
        /** The header's line: 13pt, the title 600 and the version 400. */
        const val headerHeight = 20.0
        const val headerSize = 13.0
        const val versionGap = 6.0
        const val frameRadius = 8.0
        const val footerHeight = 28.0
        /** `Button size="small"`: 28 tall, 10 either side of a 12/16 label inside a 1pt ring, a pill. */
        const val buttonHeight = 28.0
        const val buttonPadding = 10.0
        const val buttonSize = 12.0
        const val buttonGap = 8.0
        /** The answered line and the notice: 12/16. */
        const val noteSize = 12.0
        const val noteHeight = 16.0
        /** A declined proposal's rows. */
        const val declinedAlpha = 0.6
        /** Both answers while one is on its way. */
        const val disabledAlpha = 0.48
    }

    sealed interface Footer {
        /** Open: the two answers' label widths, and whether a notice stands left of them. */
        data class Answers(val keep: Double, val use: Double, val notice: Boolean) : Footer
        /** Answered: one line. */
        data object Status : Footer
    }

    data class Input(
        /** The rows shown, as the sidebar lays them out. */
        val rows: List<StemCellsSidebarLayout.Row>,
        val titleWidth: Double,
        val footer: Footer,
    )

    companion object {
        /** `width` is the column the card stands in. */
        operator fun invoke(width: Double, input: Input): StemCellSidebarProposalLayout {
            val m = Metrics
            val cardWidth = max(0.0, min(width, m.maxWidth))
            val inner = max(0.0, cardWidth - 2 * m.padding)
            val left = m.padding
            var y = m.padding
            val titleWidth = min(ceil(input.titleWidth), inner)
            val title = Rect(left, y, titleWidth, m.headerHeight)
            val versionX = min(left + inner, title.maxX + m.versionGap)
            val version = Rect(versionX, y, max(0.0, left + inner - versionX), m.headerHeight)
            y += m.headerHeight + m.gap
            val rows = StemCellsSidebarLayout.layout(input.rows, inner)
            val frame = Rect(left, y, inner, rows.contentHeight)
            y += rows.contentHeight + m.gap
            val footer = Rect(left, y, inner, m.footerHeight)
            val noteY = footer.midY - m.noteHeight / 2
            y += m.footerHeight + m.padding
            val size = Size(cardWidth, y)
            return when (val answer = input.footer) {
                is Footer.Answers -> {
                    val buttonY = footer.midY - m.buttonHeight / 2
                    val useWidth = buttonWidth(answer.use)
                    val keepWidth = buttonWidth(answer.keep)
                    val use = Rect(footer.maxX - useWidth, buttonY, useWidth, m.buttonHeight)
                    val keep = Rect(use.minX - m.buttonGap - keepWidth, buttonY, keepWidth, m.buttonHeight)
                    val notice = if (answer.notice) Rect(left, noteY, max(0.0, keep.minX - m.buttonGap - left), m.noteHeight) else null
                    StemCellSidebarProposalLayout(size, title, version, frame, footer, keep, use, notice, null)
                }
                // The footer ends its content at the right (`justify-content: flex-end`).
                Footer.Status -> StemCellSidebarProposalLayout(size, title, version, frame, footer, status = Rect(left, noteY, inner, m.noteHeight))
            }
        }

        /** A small button around a label `text` wide: its padding and ring. */
        fun buttonWidth(text: Double) = ceil(text) + 2 * Metrics.buttonPadding + 2 * Metrics.border
    }
}
