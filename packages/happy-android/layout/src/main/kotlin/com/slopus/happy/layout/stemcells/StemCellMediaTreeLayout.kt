package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A layout an agent wrote for media it showed (happy-agent `API.md`, a
 * `tree` layout; happy-ios `StemCellMediaTreeLayout`), laid out at the
 * reading column's width: block flow, flexbox and grid in the subset the
 * daemon allows, as desktop's `MediaTree` draws them in CSS. Nothing paints
 * outside the box, which is never wider than the column nor taller than
 * `MAX_HEIGHT`.
 *
 * An item without a width is drawn at its natural size, shrunk to fit and
 * never enlarged, no taller than `ITEM_MAX_HEIGHT` at its own shape; a
 * document or file is a card `CARD_HEIGHT` tall across its container.
 */
class StemCellMediaTreeLayout(width: Double, root: List<Box>) {
    /** Each item's frame by index, each paragraph's by id, and the size. */
    val items = HashMap<Int, Rect>()
    val texts = HashMap<Int, Rect>()
    val size: Size

    /** One item's box before layout: its shape, and its natural size in points. */
    class Item(val isCard: Boolean, val ratio: Double, val natural: Size?)

    sealed interface Box {
        class Container(val style: Style, val children: List<Box>) : Box
        class Item(val index: Int, val style: Style, val item: StemCellMediaTreeLayout.Item) : Box
        /** A paragraph, measured at a width; `id` names its frame. */
        class Text(val id: Int, val measurable: Measurable) : Box
    }

    init {
        val laid = flow(root, max(0.0, width))
        val height = min(laid.height, MAX_HEIGHT)
        for (placed in laid.placed) {
            if (placed.frame.minY >= height) continue
            val frame = placed.frame.copy(height = min(placed.frame.height, height - placed.frame.minY))
            when (val kind = placed.kind) {
                is Kind.Item -> items[kind.index] = frame
                is Kind.Text -> texts[kind.id] = frame
            }
        }
        size = Size(max(0.0, width), height)
    }

    override fun equals(other: Any?) = other is StemCellMediaTreeLayout && other.items == items && other.texts == texts && other.size == size
    override fun hashCode() = size.hashCode()

    // Style

    sealed interface Length {
        data class Px(val value: Double) : Length
        data class Percent(val value: Double) : Length
        data object Auto : Length

        fun resolve(base: Double): Double? = when (this) {
            is Px -> value
            is Percent -> base * value / 100
            Auto -> null
        }

        companion object {
            fun parse(text: String?): Length? {
                if (text == null) return null
                if (text == "0") return Px(0.0)
                if (text == "auto") return Auto
                for ((unit, scale) in listOf("rem" to REM, "px" to 1.0, "em" to EM)) {
                    if (text.endsWith(unit)) return text.dropLast(unit.length).toDoubleOrNull()?.let { Px(it * scale) }
                }
                if (text.endsWith("%")) return text.dropLast(1).toDoubleOrNull()?.let { Percent(it) }
                return null
            }
        }
    }

    sealed interface Track {
        data class Fixed(val length: Length) : Track
        data class Fr(val share: Double) : Track
        /** `minmax(min, max)`, `max` a length or a share. */
        data class MinMax(val min: Length, val max: Track) : Track
        data object Auto : Track
    }

    enum class Display { Block, Flex, Grid, None }

    data class Style(
        var display: Display = Display.Block,
        var row: Boolean = false,
        var reversed: Boolean = false,
        var wrap: Boolean = false,
        var grow: Double = 0.0,
        var shrink: Double = 1.0,
        var basis: Length? = null,
        var order: Int = 0,
        var justifyContent: String = "normal",
        var alignItems: String = "normal",
        var alignSelf: String = "auto",
        var justifyItems: String = "normal",
        var justifySelf: String = "auto",
        var rowGap: Length? = null,
        var columnGap: Length? = null,
        var columns: List<String>? = null,
        var autoRows: Length? = null,
        var columnStart: Int? = null,
        var columnSpan: Int = 1,
        var width: Length? = null,
        var height: Length? = null,
        var minWidth: Length? = null,
        var maxWidth: Length? = null,
        var minHeight: Length? = null,
        var maxHeight: Length? = null,
        var aspectRatio: Double? = null,
        /** Top, right, bottom, left. */
        var padding: List<Length> = ZERO_EDGES,
        var margin: List<Length> = ZERO_EDGES,
    ) {
        internal fun apply(flexWord: String) {
            when (flexWord) {
                "row" -> { row = true; reversed = false }
                "row-reverse" -> { row = true; reversed = true }
                "column" -> { row = false; reversed = false }
                "column-reverse" -> { row = false; reversed = true }
                "wrap", "wrap-reverse" -> wrap = true
                "nowrap" -> wrap = false
            }
        }

        internal fun applyFlex(value: String) {
            val words = value.split(" ").filter { it.isNotEmpty() }
            when (value) {
                "none" -> { grow = 0.0; shrink = 0.0; basis = Length.Auto }
                "auto" -> { grow = 1.0; shrink = 1.0; basis = Length.Auto }
                else -> {
                    val first = words.firstOrNull() ?: return
                    val factor = first.toDoubleOrNull()
                    if (factor != null) {
                        grow = factor
                        shrink = 1.0
                        basis = Length.Px(0.0)
                        if (words.size > 1) {
                            val second = words[1].toDoubleOrNull()
                            if (second != null) shrink = second else basis = Length.parse(words[1])
                        }
                        if (words.size > 2) basis = Length.parse(words[2])
                    } else {
                        grow = 1.0; shrink = 1.0; basis = Length.parse(first)
                    }
                }
            }
        }

        internal fun applyColumn(shorthand: String?, start: String?, end: String?) {
            val parts = if (shorthand == null) listOf(start ?: "auto", end ?: "auto") else shorthand.split(" / ")
            fun line(text: String): Pair<Int?, Int?> =
                if (text.startsWith("span ")) null to text.drop(5).toIntOrNull() else text.toIntOrNull() to null
            val first = line(parts[0])
            val second = if (parts.size > 1) line(parts[1]) else null to null
            val begin = first.first
            if (begin != null && begin > 0) {
                columnStart = begin - 1
                val stop = second.first
                columnSpan = if (stop != null && stop > begin) stop - begin else second.second ?: 1
            } else {
                columnSpan = first.second ?: second.second ?: 1
            }
            columnSpan = max(1, columnSpan)
        }

        companion object {
            /** From the normalized CSS values a tree carries. */
            fun from(css: Map<String, String>): Style {
                val style = Style()
                when (css["display"]) {
                    "flex", "inline-flex" -> { style.display = Display.Flex; style.row = true }
                    "grid", "inline-grid" -> style.display = Display.Grid
                    "none" -> style.display = Display.None
                    else -> style.display = Display.Block
                }
                for (word in (css["flexFlow"] ?: "").split(" ")) if (word.isNotEmpty()) style.apply(word)
                css["flexDirection"]?.let(style::apply)
                css["flexWrap"]?.let(style::apply)
                css["flex"]?.let(style::applyFlex)
                css["flexGrow"]?.toDoubleOrNull()?.let { style.grow = it }
                css["flexShrink"]?.toDoubleOrNull()?.let { style.shrink = it }
                css["flexBasis"]?.let { if (it != "content") style.basis = Length.parse(it) }
                style.order = css["order"]?.toIntOrNull() ?: 0
                style.justifyContent = css["justifyContent"] ?: second(css["placeContent"]) ?: "normal"
                style.alignItems = css["alignItems"] ?: first(css["placeItems"]) ?: "normal"
                style.justifyItems = css["justifyItems"] ?: second(css["placeItems"]) ?: "normal"
                style.alignSelf = css["alignSelf"] ?: first(css["placeSelf"]) ?: "auto"
                style.justifySelf = css["justifySelf"] ?: second(css["placeSelf"]) ?: "auto"
                css["gap"]?.split(" ")?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { gap ->
                    style.rowGap = Length.parse(gap[0])
                    style.columnGap = Length.parse(if (gap.size > 1) gap[1] else gap[0])
                }
                css["rowGap"]?.let { style.rowGap = Length.parse(it) }
                css["columnGap"]?.let { style.columnGap = Length.parse(it) }
                css["gridTemplateColumns"]?.let { if (it != "none") style.columns = tokens(it) }
                style.autoRows = css["gridAutoRows"]?.let(Length::parse)
                style.applyColumn(css["gridColumn"], css["gridColumnStart"], css["gridColumnEnd"])
                style.width = Length.parse(css["width"])
                style.height = Length.parse(css["height"])
                style.minWidth = Length.parse(css["minWidth"])
                style.maxWidth = Length.parse(css["maxWidth"])
                style.minHeight = Length.parse(css["minHeight"])
                style.maxHeight = Length.parse(css["maxHeight"])
                style.aspectRatio = css["aspectRatio"]?.let(::ratio)
                style.padding = edges(css["padding"], css["paddingTop"], css["paddingRight"], css["paddingBottom"], css["paddingLeft"])
                style.margin = edges(css["margin"], css["marginTop"], css["marginRight"], css["marginBottom"], css["marginLeft"])
                return style
            }

            internal fun first(pair: String?): String? = pair?.split(" ")?.firstOrNull { it.isNotEmpty() } ?: pair?.let { "" }

            internal fun second(pair: String?): String? {
                val words = pair?.split(" ")?.filter { it.isNotEmpty() } ?: return null
                if (words.isEmpty()) return null
                return if (words.size > 1) words[words.size - 1] else words[0]
            }

            internal fun ratio(value: String): Double? {
                val numbers = value.split(" ").filter { it.isNotEmpty() && it != "auto" && it != "/" }.mapNotNull { it.toDoubleOrNull() }
                val width = numbers.firstOrNull() ?: return null
                if (width <= 0) return null
                val height = if (numbers.size > 1) numbers[1] else 1.0
                return if (height > 0) width / height else null
            }

            internal fun edges(all: String?, top: String?, right: String?, bottom: String?, left: String?): List<Length> {
                val edges = ZERO_EDGES.toMutableList()
                val values = all?.split(" ")?.filter { it.isNotEmpty() }?.mapNotNull { Length.parse(it) }
                if (values != null && values.isNotEmpty()) {
                    val chosen = when (values.size) {
                        1 -> listOf(values[0], values[0], values[0], values[0])
                        2 -> listOf(values[0], values[1], values[0], values[1])
                        3 -> listOf(values[0], values[1], values[2], values[1])
                        else -> values.take(4)
                    }
                    for (index in 0 until 4) edges[index] = chosen[index]
                }
                for ((index, side) in listOf(top, right, bottom, left).withIndex()) Length.parse(side)?.let { edges[index] = it }
                return edges
            }

            /** A value's top-level words, a function kept whole. */
            internal fun tokens(text: String): List<String> {
                val tokens = ArrayList<String>()
                val current = StringBuilder()
                var depth = 0
                for (character in text) {
                    if (character == '(') depth += 1
                    if (character == ')') depth -= 1
                    if (character == ' ' && depth == 0) {
                        if (current.isNotEmpty()) tokens += current.toString()
                        current.clear()
                    } else {
                        current.append(character)
                    }
                }
                if (current.isNotEmpty()) tokens += current.toString()
                return tokens
            }
        }
    }

    // Boxes

    internal sealed interface Kind {
        data class Item(val index: Int) : Kind
        data class Text(val id: Int) : Kind
    }

    internal class Placed(val kind: Kind, val frame: Rect)

    /** A box laid out at a width: its border box and what it placed, relative to it. */
    internal class Laid(val width: Double, val height: Double, val placed: List<Placed> = emptyList()) {
        fun offset(x: Double, y: Double) = placed.map { Placed(it.kind, it.frame.offsetBy(x, y)) }
    }

    internal class Margins(val top: Double, val right: Double, val bottom: Double, val left: Double)

    companion object {
        const val MAX_HEIGHT = 1200.0
        const val ITEM_MAX_HEIGHT = 640.0
        const val CARD_HEIGHT = 64.0
        /** The tree's own type size and the page's, for `em` and `rem`. */
        internal const val EM = 14.0
        internal const val REM = 16.0

        private val ZERO_EDGES: List<Length> = listOf(Length.Px(0.0), Length.Px(0.0), Length.Px(0.0), Length.Px(0.0))

        private fun styleOf(box: Box): Style? = when (box) {
            is Box.Container -> box.style
            is Box.Item -> box.style
            is Box.Text -> null
        }

        private fun margins(box: Box, base: Double): Margins {
            val margin = styleOf(box)?.margin ?: return Margins(0.0, 0.0, 0.0, 0.0)
            return Margins(margin[0].resolve(base) ?: 0.0, margin[1].resolve(base) ?: 0.0, margin[2].resolve(base) ?: 0.0, margin[3].resolve(base) ?: 0.0)
        }

        private fun visible(boxes: List<Box>): List<Box> =
            // `order` sorts, keeping the written order among equals (sortedBy is stable).
            boxes.filter { styleOf(it)?.display != Display.None }.sortedBy { styleOf(it)?.order ?: 0 }

        /** The widest a box would like to be, for a flex row's basis. */
        private fun preferredWidth(box: Box, available: Double): Double = when (box) {
            is Box.Text -> min(available, ceil(box.measurable.size(Double.MAX_VALUE).width))
            is Box.Item -> layoutItem(box.style, box.item, available).width
            is Box.Container -> box.style.width?.resolve(available)?.let { min(available, it) } ?: min(available, layout(box, available, fill = false).width)
        }

        /**
         * One box at `width`, the room its container gives it. `fill` makes a
         * container without a width as wide as that room, as a block is.
         */
        private fun layout(box: Box, width: Double, fill: Boolean = true): Laid = when (box) {
            is Box.Text -> {
                val height = ceil(box.measurable.size(width).height)
                val used = if (fill) width else min(width, ceil(box.measurable.size(width).width))
                Laid(used, height, listOf(Placed(Kind.Text(box.id), Rect(0.0, 0.0, used, height))))
            }
            is Box.Item -> {
                val size = layoutItem(box.style, box.item, width)
                Laid(size.width, size.height, listOf(Placed(Kind.Item(box.index), Rect(0.0, 0.0, size.width, size.height))))
            }
            is Box.Container -> {
                val style = box.style
                var outer = style.width?.resolve(width) ?: width
                style.maxWidth?.resolve(width)?.let { outer = min(outer, it) }
                style.minWidth?.resolve(width)?.let { outer = max(outer, it) }
                outer = min(outer, width)
                val padding = style.padding.map { it.resolve(width) ?: 0.0 }
                val inner = max(0.0, outer - padding[1] - padding[3])
                val children = visible(box.children)
                val content = when (style.display) {
                    Display.Grid -> grid(style, children, inner)
                    Display.Flex -> if (style.row) flexRow(style, children, inner) else flexColumn(style, children, inner)
                    Display.Block, Display.None -> block(children, inner)
                }
                val shrinkWraps = !fill && style.width == null
                val used = if (shrinkWraps) min(outer, content.width + padding[1] + padding[3]) else outer
                var height = content.height + padding[0] + padding[2]
                val ratio = style.aspectRatio
                if (ratio != null && style.height == null) height = max(height, used / ratio)
                style.height?.resolve(0.0)?.let { height = it }
                style.minHeight?.resolve(0.0)?.let { height = max(height, it) }
                style.maxHeight?.resolve(0.0)?.let { height = min(height, it) }
                // Overflow is clipped: nothing paints past the box.
                val placed = content.offset(padding[3], padding[0]).filter { it.frame.minY < height }
                Laid(used, height, placed)
            }
        }

        /** An item's size: its width as asked, else natural and never past the room; its height from its shape, at most `ITEM_MAX_HEIGHT`. */
        private fun layoutItem(style: Style, item: Item, available: Double): Size {
            val ratio = style.aspectRatio ?: item.ratio
            var width = style.width?.resolve(available)
            if (item.isCard) return Size(max(0.0, min(available, width ?: available)), CARD_HEIGHT)
            val height = style.height?.resolve(0.0)
            if (width == null && height != null) width = height * ratio
            var used = width ?: min(item.natural?.width ?: available, available)
            style.maxWidth?.resolve(available)?.let { used = min(used, it) }
            style.minWidth?.resolve(available)?.let { used = max(used, it) }
            used = max(0.0, min(used, available))
            var tall = height ?: (if (ratio > 0) used / ratio else used)
            val cap = min(ITEM_MAX_HEIGHT, style.maxHeight?.resolve(0.0) ?: Double.MAX_VALUE)
            if (tall > cap) {
                tall = cap
                if (height == null && width == null) used = min(used, cap * ratio)
            }
            return Size(used, tall)
        }

        /** Block flow: one under another, each at its margins; `margin: auto` on both sides centres a box narrower than the room. */
        private fun flow(boxes: List<Box>, width: Double): Laid = block(visible(boxes), width)

        private fun block(boxes: List<Box>, width: Double): Laid {
            val placed = ArrayList<Placed>()
            var y = 0.0
            var used = 0.0
            var previousBottom = 0.0
            for ((index, box) in boxes.withIndex()) {
                val margin = margins(box, width)
                // Adjoining vertical margins collapse to the larger.
                if (index > 0) { y -= previousBottom; y += max(previousBottom, margin.top) } else y += margin.top
                val room = max(0.0, width - margin.left - margin.right)
                val laid = layout(box, room)
                var x = margin.left
                val style = styleOf(box)
                if (style != null && style.margin[1] == Length.Auto && style.margin[3] == Length.Auto) x = max(0.0, (width - laid.width) / 2)
                else if (style != null && style.margin[3] == Length.Auto) x = max(0.0, width - margin.right - laid.width)
                placed += laid.offset(x, y)
                y += laid.height + margin.bottom
                previousBottom = margin.bottom
                used = max(used, x + laid.width + margin.right)
            }
            return Laid(min(width, used), y, placed)
        }

        private fun flexColumn(style: Style, boxes: List<Box>, width: Double): Laid {
            val gap = style.rowGap?.resolve(width) ?: 0.0
            val ordered = if (style.reversed) boxes.reversed() else boxes
            val placed = ArrayList<Placed>()
            var y = 0.0
            var used = 0.0
            for ((index, box) in ordered.withIndex()) {
                if (index > 0) y += gap
                val margin = margins(box, width)
                y += margin.top
                val room = max(0.0, width - margin.left - margin.right)
                val align = alignment(styleOf(box)?.alignSelf, style.alignItems)
                val laid = layout(box, room, fill = align == "stretch")
                val x = margin.left + offset(align, room - laid.width)
                placed += laid.offset(x, y)
                y += laid.height + margin.bottom
                used = max(used, x + laid.width + margin.right)
            }
            return Laid(min(width, used), y, placed)
        }

        private fun alignment(value: String?, items: String): String {
            val chosen = if (value == null || value == "auto") items else value
            return if (chosen == "normal") "stretch" else chosen
        }

        private fun offset(align: String, free: Double): Double {
            if (free <= 0) return 0.0
            return when (align) {
                "center" -> free / 2
                "end", "flex-end", "self-end", "right" -> free
                else -> 0.0
            }
        }

        private class Entry(val box: Box, val basis: Double, val margin: Double, val grow: Double, val shrink: Double)

        /**
         * A flex row: each box at its basis, lines broken when it wraps, the
         * free room shared by `flex-grow` or the overflow by `flex-shrink`.
         */
        private fun flexRow(style: Style, boxes: List<Box>, width: Double): Laid {
            val columnGap = style.columnGap?.resolve(width) ?: 0.0
            val rowGap = style.rowGap?.resolve(width) ?: 0.0
            val ordered = if (style.reversed) boxes.reversed() else boxes
            val entries = ordered.map { box ->
                val margin = margins(box, width)
                val room = max(0.0, width - margin.left - margin.right)
                val own = styleOf(box)
                val basis = own?.basis?.resolve(width) ?: own?.width?.resolve(width) ?: preferredWidth(box, room)
                Entry(box, basis, margin.left + margin.right, own?.grow ?: 0.0, own?.shrink ?: 1.0)
            }
            val lines = ArrayList<List<Entry>>()
            var line = ArrayList<Entry>()
            var lineWidth = 0.0
            for (entry in entries) {
                val size = entry.basis + entry.margin
                if (style.wrap && line.isNotEmpty() && lineWidth + columnGap + size > width) {
                    lines += line
                    line = ArrayList()
                    lineWidth = 0.0
                }
                lineWidth += (if (line.isEmpty()) 0.0 else columnGap) + size
                line += entry
            }
            if (line.isNotEmpty()) lines += line
            val placed = ArrayList<Placed>()
            var y = 0.0
            var used = 0.0
            for ((lineIndex, current) in lines.withIndex()) {
                if (lineIndex > 0) y += rowGap
                val gaps = (current.size - 1) * columnGap
                val total = current.sumOf { it.basis + it.margin } + gaps
                var free = width - total
                val sizes = current.map { it.basis }.toMutableList()
                if (free > 0) {
                    val grow = current.sumOf { it.grow }
                    if (grow > 0) for (index in current.indices) sizes[index] += free * current[index].grow / grow
                } else if (free < 0) {
                    val weight = current.sumOf { it.shrink * it.basis }
                    if (weight > 0) for (index in current.indices) sizes[index] = max(0.0, sizes[index] + free * current[index].shrink * current[index].basis / weight)
                }
                val laid = current.indices.map { layout(current[it].box, sizes[it]) }
                val lineHeight = current.indices.maxOfOrNull { laid[it].height + margins(current[it].box, width).top + margins(current[it].box, width).bottom } ?: 0.0
                // Each box's own width counts toward what's left over.
                free = width - gaps - current.indices.sumOf { laid[it].width + current[it].margin }
                var x = 0.0
                var spacing = columnGap
                when (style.justifyContent) {
                    "center" -> x = max(0.0, free / 2)
                    "end", "flex-end", "right" -> x = max(0.0, free)
                    "space-between" -> if (current.size > 1 && free > 0) spacing += free / (current.size - 1)
                    "space-around" -> if (free > 0) { x = free / current.size / 2; spacing += free / current.size }
                    "space-evenly" -> if (free > 0) { x = free / (current.size + 1); spacing += free / (current.size + 1) }
                }
                for (index in current.indices) {
                    val margin = margins(current[index].box, width)
                    val box = laid[index]
                    val align = alignment(styleOf(current[index].box)?.alignSelf, style.alignItems)
                    val top = y + margin.top + offset(align, lineHeight - margin.top - margin.bottom - box.height)
                    placed += box.offset(x + margin.left, top)
                    x += margin.left + box.width + margin.right + spacing
                }
                used = max(used, x - spacing)
                y += lineHeight
            }
            return Laid(min(width, used), y, placed)
        }

        /** The column tracks' widths at `width`, `count` boxes to place. */
        internal fun tracks(template: List<String>?, width: Double, gap: Double, count: Int): List<Double> {
            if (template.isNullOrEmpty()) return listOf(width)
            fun track(text: String): Track {
                if (text.endsWith("fr")) text.dropLast(2).toDoubleOrNull()?.let { return Track.Fr(it) }
                if (text.startsWith("minmax(") && text.endsWith(")")) {
                    val args = text.drop(7).dropLast(1).split(", ")
                    if (args.size == 2) return Track.MinMax(Length.parse(args[0]) ?: Length.Px(0.0), track(args[1]))
                }
                if (text.startsWith("fit-content(") && text.endsWith(")")) {
                    return Track.MinMax(Length.Px(0.0), Track.Fixed(Length.parse(text.drop(12).dropLast(1)) ?: Length.Auto))
                }
                val length = Length.parse(text)
                if (length != null && length != Length.Auto) return Track.Fixed(length)
                return Track.Auto
            }
            fun minimum(track: Track): Double = when (track) {
                is Track.Fixed -> track.length.resolve(width) ?: 0.0
                is Track.MinMax -> track.min.resolve(width) ?: 0.0
                is Track.Fr, Track.Auto -> 0.0
            }
            var list = ArrayList<Track>()
            for (token in template) {
                if (!(token.startsWith("repeat(") && token.endsWith(")"))) {
                    list += track(token)
                    continue
                }
                val inner = token.drop(7).dropLast(1)
                val comma = inner.indexOf(',')
                if (comma < 0) continue
                val times = inner.substring(0, comma)
                val repeated = Style.tokens(inner.substring(comma + 1).trim()).map(::track)
                var repeats = times.toIntOrNull() ?: 1
                if (times == "auto-fill" || times == "auto-fit") {
                    val span = repeated.sumOf { max(1.0, minimum(it)) } + (repeated.size - 1) * gap
                    repeats = max(1, floor((width + gap) / (span + gap)).toInt())
                    // `auto-fit` folds away the tracks nothing fills.
                    if (times == "auto-fit") repeats = max(1, min(repeats, ceil(count.toDouble() / max(1, repeated.size)).toInt()))
                }
                repeat(min(24, repeats)) { list += repeated }
            }
            list = ArrayList(list.take(24))
            val free = width - max(0, list.size - 1) * gap
            val sizes: MutableList<Double?> = list.map { (it as? Track.Fixed)?.length?.resolve(width) }.toMutableList()
            fun share(track: Track): Double = when (track) {
                is Track.Fr -> track.share
                is Track.MinMax -> share(track.max)
                Track.Auto -> 1.0
                is Track.Fixed -> 0.0
            }
            // Shares take what fixed tracks leave; one below its minimum keeps that.
            var flexible = list.indices.filter { sizes[it] == null }
            repeat(3) {
                val fixed = sizes.filterNotNull().sum()
                val left = max(0.0, free - fixed)
                val shares = flexible.sumOf { share(list[it]) }
                var settled = false
                for (index in flexible) {
                    val size = if (shares > 0) left * share(list[index]) / shares else 0.0
                    if (size < minimum(list[index])) { sizes[index] = minimum(list[index]); settled = true }
                }
                flexible = flexible.filter { sizes[it] == null }
                if (!settled) {
                    for (index in flexible) sizes[index] = if (shares > 0) left * share(list[index]) / shares else 0.0
                    return sizes.map { it ?: 0.0 }
                }
            }
            return sizes.map { it ?: 0.0 }
        }

        private class Cell(val box: Box, val row: Int, val column: Int, val span: Int)

        /** A grid: boxes placed in order, row by row, each in the columns it spans; a row as tall as its tallest cell. */
        private fun grid(style: Style, boxes: List<Box>, width: Double): Laid {
            val columnGap = style.columnGap?.resolve(width) ?: 0.0
            val rowGap = style.rowGap?.resolve(width) ?: 0.0
            val columns = tracks(style.columns, width, columnGap, boxes.size)
            val count = max(1, columns.size)
            val cells = ArrayList<Cell>()
            var row = 0
            var column = 0
            for (box in boxes) {
                val own = styleOf(box)
                val span = min(count, own?.columnSpan ?: 1)
                val start = own?.columnStart?.let { min(it, count - span) }
                if (start != null) {
                    if (start < column) row += 1
                    column = start
                } else if (column + span > count) {
                    row += 1
                    column = 0
                }
                cells += Cell(box, row, column, span)
                column += span
                if (column >= count) { row += 1; column = 0 }
            }
            val rows = (cells.maxOfOrNull { it.row } ?: -1) + 1
            var heights = MutableList(rows) { 0.0 }
            val laid = ArrayList<Laid>()
            fun span(cell: Cell) = columns.subList(cell.column, cell.column + cell.span).sum() + (cell.span - 1) * columnGap
            for (cell in cells) {
                val cellWidth = span(cell)
                val margin = margins(cell.box, width)
                val room = max(0.0, cellWidth - margin.left - margin.right)
                val justify = alignment(styleOf(cell.box)?.justifySelf, style.justifyItems)
                val box = layout(cell.box, room, fill = justify == "stretch")
                laid += box
                heights[cell.row] = max(heights[cell.row], box.height + margin.top + margin.bottom)
            }
            style.autoRows?.resolve(0.0)?.let { fixed -> heights = MutableList(rows) { fixed } }
            val placed = ArrayList<Placed>()
            var used = 0.0
            for ((index, cell) in cells.withIndex()) {
                val x0 = columns.subList(0, cell.column).sum() + cell.column * columnGap
                val cellWidth = span(cell)
                val y0 = heights.subList(0, cell.row).sum() + cell.row * rowGap
                val margin = margins(cell.box, width)
                val box = laid[index]
                val justify = alignment(styleOf(cell.box)?.justifySelf, style.justifyItems)
                val align = alignment(styleOf(cell.box)?.alignSelf, style.alignItems)
                val x = x0 + margin.left + offset(justify, cellWidth - margin.left - margin.right - box.width)
                val y = y0 + margin.top + offset(align, heights[cell.row] - margin.top - margin.bottom - box.height)
                placed += box.offset(x, y).filter { it.frame.minY < y0 + heights[cell.row] }
                used = max(used, x0 + cellWidth)
            }
            val height = heights.sum() + max(0, rows - 1) * rowGap
            return Laid(min(width, max(used, columns.sum() + (count - 1) * columnGap)), height, placed)
        }
    }
}
