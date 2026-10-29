package com.slopus.happy.layout.stories

import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The story editor (happy-ios `StoryEditorLayout`), as Telegram's story
 * editor lays itself out (`MediaEditorScreen`, `MessageInputPanelComponent`,
 * `DrawingUI`): the 9:16 canvas full width under the status bar with 30pt
 * corners; the caption field over the canvas's foot; under the canvas the
 * back button at the left, the tools in one pill between, and Post at the
 * right; the frames button over the caption at the right, opening the
 * frames panel above it. A phone too short for the canvas and its bar
 * (home-button phones) starts the canvas at the top and lifts the bar 62pt
 * above the home indicator, over the canvas. A tablet fits the canvas to
 * the height.
 */
data class StoryEditorLayout(
    val canvas: Rect,
    val close: Rect,
    /** The tools' pill, and in it text and stickers, left to right. */
    val tools: Rect,
    val toolButtons: List<Rect>,
    val post: Rect,
    /** The caption's panel and the field in it. */
    val captionPanel: Rect,
    val caption: Rect,
    val framesButton: Rect,
    /** The bin's square and its circle, over the canvas's foot. */
    val bin: Rect,
    val binCircle: Rect,
) {
    object Metrics {
        const val aspect = 1.77778
        const val canvasTop = 5.0
        const val cornerRadius = 30.0
        const val button = 44.0
        const val buttonTop = 8.0
        const val side = 16.0
        const val narrowSide = 9.0
        const val tabletSide = 30.0
        const val tabletBottom = 75.0
        const val shortLift = 62.0
        const val toolSpacing = 10.0
        const val tools = 2

        /**
         * The caption field: 40 tall, 9 in from the canvas's sides, its
         * panel 6 under it and 3 over the canvas's foot.
         */
        const val captionHeight = 40.0
        const val captionSide = 9.0
        const val captionPanelTop = 14.0
        const val captionPanelBottom = 6.0
        const val captionLift = 3.0

        /** Post's pill (`GlassBarButtonComponent`): its label and arrow 12.5 in from its ends. */
        const val postPadding = 12.5
        const val frames = 33.0
        const val framesSide = 12.0
        const val panelHeight = 120.0
        const val panelGap = 10.0
        val panelItem = Size(95, 112)
        const val panelSpacing = 4.0

        /** The bin, in canvas units of a 1080-wide canvas: 180 square, 20 over the foot. */
        const val bin = 180.0
        const val binBottom = 20.0
        const val binCircle = 160.0
    }

    /**
     * The frames panel over the frames button, and in it each frame's card
     * and the add card after them.
     */
    data class Panel(val frame: Rect, val items: List<Rect>, val add: Rect?, val content: Size)

    fun panel(width: Double, frames: Int, canAdd: Boolean): Panel {
        val m = Metrics
        val count = frames + if (canAdd) 1 else 0
        var x = m.panelSpacing
        val items = mutableListOf<Rect>()
        repeat(count) {
            items += Rect(x, m.panelSpacing, m.panelItem.width, m.panelItem.height)
            x += m.panelItem.width + m.panelSpacing
        }
        val content = Size(x, m.panelItem.height + 2 * m.panelSpacing)
        val available = width - 2 * m.framesSide
        val frame = Rect(framesButton.maxX - min(available, content.width), framesButton.minY - m.panelGap - m.panelHeight, min(available, content.width), m.panelHeight)
        val add = if (canAdd) items.removeAt(items.size - 1) else null
        return Panel(frame, items, add, content)
    }

    companion object {
        /**
         * `statusBar` is the status bar's height, `homeIndicator` the safe
         * area's bottom, and `postContent` Post's label and arrow measured.
         */
        operator fun invoke(bounds: Rect, statusBar: Double, homeIndicator: Double, postContent: Double, tablet: Boolean = false): StoryEditorLayout {
            val m = Metrics
            var top = statusBar + m.canvasTop
            val size: Size
            var lift = 0.0
            val side: Double
            if (tablet) {
                val height = bounds.height - top - m.tabletBottom
                size = Size(floor(height / m.aspect), height)
                side = m.tabletSide
            } else {
                size = Size(bounds.width, floor(bounds.width * m.aspect))
                if (bounds.height < size.height + 30) {
                    top = 0.0
                    lift = -m.shortLift - homeIndicator
                    side = m.narrowSide
                } else {
                    side = if (bounds.width > 320) m.side else m.narrowSide
                }
            }
            val canvas = Rect(bounds.minX + floor((bounds.width - size.width) / 2), bounds.minY + top, size.width, size.height)
            // Telegram's safe bottom is the space under the canvas.
            val safeBottom = max(0.0, bounds.height - size.height - top)
            val row = bounds.maxY - safeBottom + m.buttonTop + lift
            val close = Rect(bounds.minX + side, row, m.button, m.button)
            val postWidth = ceil(postContent + 2 * m.postPadding)
            val post = Rect(bounds.maxX - side - postWidth, row, postWidth, m.button)
            val toolsWidth = m.button * m.tools + m.toolSpacing * (m.tools - 1)
            val tools = Rect(close.maxX + floor((post.minX - close.maxX - toolsWidth) / 2), row, toolsWidth, m.button)
            val toolButtons = (0 until m.tools).map { Rect(it * (m.button + m.toolSpacing), 0.0, m.button, m.button) }
            val panelHeight = m.captionPanelTop + m.captionHeight + m.captionPanelBottom
            val captionPanel = Rect(canvas.minX, bounds.maxY - safeBottom + lift - m.captionLift - panelHeight, canvas.width, panelHeight)
            val caption = Rect(captionPanel.minX + m.captionSide, captionPanel.minY + m.captionPanelTop, max(0.0, captionPanel.width - 2 * m.captionSide), m.captionHeight)
            val framesButton = Rect(bounds.maxX - m.frames - m.framesSide, captionPanel.minY - m.frames, m.frames, m.frames)
            val unit = canvas.width / 1080
            val bin = Rect(canvas.minX + (canvas.width - m.bin * unit) / 2, canvas.maxY - (m.bin + m.binBottom) * unit, m.bin * unit, m.bin * unit)
            val inset = (m.bin - m.binCircle) / 2 * unit
            val binCircle = Rect(bin.x + inset, bin.y + inset, bin.width - 2 * inset, bin.height - 2 * inset)
            return StoryEditorLayout(canvas, close, tools, toolButtons, post, captionPanel, caption, framesButton, bin, binCircle)
        }
    }
}

/**
 * Telegram's interactive stickers over the sticker grid (happy-ios
 * `StoryStickerPillsLayout`; `StoryStickersContentView`): pills 34 tall and
 * a reaction bubble 52 across, in rows 18 in from the sides and 12 apart,
 * spread up to 30 apart and centred.
 */
data class StoryStickerPillsLayout(val items: List<Rect>, val height: Double) {
    object Metrics {
        const val padding = 18.0
        const val minSpacing = 8.0
        const val maxSpacing = 30.0
        const val rowSpacing = 12.0
        const val pillHeight = 34.0
        const val pillPadding = 7.0
        const val pillIcon = 20.0
        const val pillGap = 4.0
        const val reaction = 52.0
    }

    companion object {
        /** The pill a title of `width` makes. */
        fun pill(title: Double): Size {
            val m = Metrics
            return Size(m.pillPadding + m.pillIcon + m.pillGap + ceil(title) + m.pillPadding, m.pillHeight)
        }

        operator fun invoke(width: Double, sizes: List<Size>): StoryStickerPillsLayout {
            val m = Metrics
            val rows = mutableListOf<List<Int>>()
            var row = mutableListOf<Int>()
            for (index in sizes.indices) {
                val used = (row + index).sumOf { sizes[it].width }
                val spacing = if (row.isEmpty()) Double.MAX_VALUE else (width - used - 2 * m.padding) / row.size
                if (row.isNotEmpty() && spacing < m.minSpacing) {
                    rows += row
                    row = mutableListOf()
                }
                row += index
            }
            if (row.isNotEmpty()) rows += row
            var y = m.padding
            val items = MutableList(sizes.size) { Rect.Zero }
            for ((number, line) in rows.withIndex()) {
                val used = line.sumOf { sizes[it].width }
                val height = line.maxOfOrNull { sizes[it].height } ?: 0.0
                var spacing = if (line.size > 1) (width - used - 2 * m.padding) / (line.size - 1) else m.maxSpacing
                var x = m.padding
                if (spacing > m.maxSpacing || line.size == 1) {
                    spacing = m.maxSpacing
                    x = floor((width - used - spacing * (line.size - 1)) / 2)
                }
                for (index in line) {
                    val size = sizes[index]
                    items[index] = Rect(x, y + floor((height - size.height) / 2), size.width, size.height)
                    x += size.width + spacing
                }
                y += height + if (number == rows.size - 1) 0.0 else m.rowSpacing
            }
            return StoryStickerPillsLayout(items, y + m.padding - m.rowSpacing)
        }
    }
}

/** A pack's kind as the layout sizes it (the layout module doesn't see the app's wire types). */
enum class StickerPackKindLayout { Stickers, Emoji }

/**
 * The sticker picker's scroll (happy-ios `StickerPickerLayout`), as
 * Telegram's sticker panel: sections one under another, each a centred
 * uppercase header over a grid of square cells (`StickerGrid` columns), the
 * stickers fitted inside them.
 */
data class StickerPickerLayout(val sections: List<Placed>, val height: Double) {
    object Metrics {
        const val inset = 8.0
        const val spacing = 4.0
        const val header = 32.0
        const val sectionGap = 8.0

        /** Room under the last row for the floating switch. */
        const val bottom = 72.0
        const val clear = 32.0
    }

    data class Section(val kind: StickerPackKindLayout, val count: Int, val header: Boolean = true)

    data class Placed(
        val header: Rect?,
        /** The ✕ in the header (Recently used). */
        val clear: Rect?,
        val cells: List<Rect>,
        val columns: Int,
    )

    companion object {
        /** `clears` marks the sections whose header has a ✕. */
        operator fun invoke(width: Double, sections: List<Section>, clears: Set<Int> = emptySet()): StickerPickerLayout {
            val m = Metrics
            val grid = max(0.0, width - 2 * m.inset)
            var y = 0.0
            val placed = mutableListOf<Placed>()
            for ((index, section) in sections.withIndex()) {
                var header: Rect? = null
                var clear: Rect? = null
                if (section.header) {
                    header = Rect(m.inset, y, grid, m.header)
                    if (index in clears) clear = Rect(m.inset + grid - m.clear, y, m.clear, m.header)
                    y += m.header
                }
                val columns = columns(section.kind, grid)
                val side = if (columns > 0) floor((grid - (columns - 1) * m.spacing) / columns) else 0.0
                // Whole-point cells, the rounding left spread at the right.
                val cells = (0 until section.count).map { item ->
                    val row = item / columns
                    val column = item % columns
                    Rect(m.inset + column * (side + m.spacing), y + row * (side + m.spacing), side, side)
                }
                val rows = (section.count + columns - 1) / columns
                y += if (rows > 0) rows * side + (rows - 1) * m.spacing else 0.0
                y += m.sectionGap
                placed += Placed(header, clear, cells, columns)
            }
            return StickerPickerLayout(placed, y + m.bottom)
        }

        /** `max(5, ⌊(w + s) / (70 + s)⌋)` for stickers, `max(8, ⌊(w + s) / (40 + s)⌋)` for emoji. */
        fun columns(kind: StickerPackKindLayout, width: Double): Int {
            val (minimum, cell) = if (kind == StickerPackKindLayout.Stickers) 5 to 70.0 else 8 to 40.0
            return max(minimum, floor((width + Metrics.spacing) / (cell + Metrics.spacing)).toInt())
        }
    }
}

/**
 * Typing a text overlay (happy-ios `StoryTextEntryLayout`), as Telegram's
 * text tool: Cancel and Done over the top, the size slider at the left
 * edge centred between the top and the keyboard, the text typed on the
 * canvas's centre, and on the keyboard's own bar the colour swatch, the
 * fill and the alignment at the left and the font at the right.
 */
data class StoryTextEntryLayout(val cancel: Rect, val done: Rect, val slider: Rect) {
    object Metrics {
        const val topRow = 10.0
        const val topRowSafe = 20.0
        const val topButtonHeight = 30.0
        const val topSide = 13.0
        val slider = Size(30, 240)
        const val knob = 52.0

        /** The slider's track runs 12 past its ends for the knob. */
        const val sliderOverhang = 12.0

        /** New text's size on the slider, and the canvas's width it wraps at. */
        const val newSize = 0.08
        const val newWidth = 0.9
        const val bar = 44.0
        const val swatchCenter = 24.0
        const val style = 48.0
        const val align = 92.0
        const val barButton = 44.0
        const val fontSide = 16.0
        const val fontHeight = 30.0

        /** The font pill: its name 10 in, 30 at the right for the chevron. */
        const val fontLeft = 10.0
        const val fontRight = 30.0
    }

    /** The keyboard bar's controls, on a bar `width` wide. */
    data class Bar(val swatch: Rect, val style: Rect, val align: Rect, val font: Rect)

    companion object {
        /**
         * `safeTop` and `safeBottom` are the safe area's, and `keyboard` how
         * far the keyboard (with its bar) reaches up from the bottom.
         */
        operator fun invoke(bounds: Rect, statusBar: Double, safeTop: Double, safeBottom: Double, keyboard: Double, cancelWidth: Double, doneWidth: Double): StoryTextEntryLayout {
            val m = Metrics
            val row = bounds.minY + max(statusBar + m.topRow, safeTop + m.topRowSafe)
            val cancel = Rect(bounds.minX + m.topSide, row, ceil(cancelWidth), m.topButtonHeight)
            val done = Rect(bounds.maxX - m.topSide - ceil(doneWidth), row, ceil(doneWidth), m.topButtonHeight)
            val top = max(safeTop, statusBar)
            val bottom = if (keyboard > 0) keyboard else safeBottom
            val slider = Rect(bounds.minX, bounds.minY + floor(top + (bounds.height - top - bottom) / 2 - m.slider.height / 2), m.slider.width, m.slider.height)
            return StoryTextEntryLayout(cancel, done, slider)
        }

        /**
         * The knob's centre on a slider of `height` at `value`, and the value
         * a finger at `y` sets, both in the slider's own coordinates.
         */
        fun knob(value: Double, height: Double): Double {
            val o = Metrics.sliderOverhang
            return -o + (height + 2 * o - Metrics.knob) * (1 - min(1.0, max(0.0, value))) + Metrics.knob / 2
        }

        fun value(y: Double, height: Double): Double {
            val o = Metrics.sliderOverhang
            return 1 - min(1.0, max(0.0, (y - o) / (height - 2 * o)))
        }

        fun bar(width: Double, fontTitle: Double): Bar {
            val m = Metrics
            val fontWidth = m.fontLeft + ceil(fontTitle) + m.fontRight
            return Bar(
                swatch = Rect(m.swatchCenter - m.barButton / 2, 0.0, m.barButton, m.bar),
                style = Rect(m.style, 0.0, m.barButton, m.bar),
                align = Rect(m.align, 0.0, m.barButton, m.bar),
                font = Rect(width - m.fontSide - fontWidth, (m.bar - m.fontHeight) / 2, fontWidth, m.fontHeight),
            )
        }

        /**
         * The text's size from the slider, as Telegram maps it: the font from
         * 2.5% to 25% of the canvas's longer side, which is `scale` 0.71 to
         * 7.1 of the API's 1/16 of the width.
         */
        fun scale(slider: Double): Double {
            val font = 0.025 + (0.25 - 0.025) * min(1.0, max(0.0, slider))
            return font * 1920 / (1080.0 / 16)
        }

        fun slider(scale: Double): Double {
            val font = scale * (1080.0 / 16) / 1920
            return min(1.0, max(0.0, (font - 0.025) / (0.25 - 0.025)))
        }
    }
}
