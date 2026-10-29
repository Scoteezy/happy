package com.slopus.happy.stemcells

/**
 * Layout blocks in an agent's text (happy-agent `API.md`, "Media layouts in
 * text"): plain HTML that arranges media the conversation already showed.
 * [StemCellMediaTree.parse] reads each into the tree it is drawn as; the
 * HTML itself is never drawn. A port of happy-ios `StemCellMediaBlocks`.
 */
object StemCellMediaBlocks {
    sealed class Segment {
        data class Markdown(val text: String) : Segment()
        data class Layout(
            /** The block as written. */
            val raw: String,
            /** False when its root never closed or it ends inside a tag. */
            val complete: Boolean,
        ) : Segment()
    }

    private val roots = listOf("div", "section", "figure", "p", "img")

    /**
     * `splitMediaLayoutBlocks` (`@slopus/happy-agent-client`), over UTF-16 as
     * JavaScript counts: Markdown and the layout blocks in it, in order;
     * their texts joined are the input. A block holding no `<img` stays
     * Markdown, and an incomplete block is always the last segment.
     */
    fun split(text: String): List<Segment> {
        val segments = ArrayList<Segment>()
        fun pushMarkdown(from: Int, to: Int) {
            if (from >= to) return
            val last = segments.lastOrNull()
            if (last is Segment.Markdown) segments[segments.size - 1] = Segment.Markdown(last.text + text.substring(from, to))
            else segments += Segment.Markdown(text.substring(from, to))
        }
        var markdownStart = 0
        var lineStart = 0
        var fence: Fence? = null
        val spans = CodeSpans(text)
        var codeUntil = 0
        while (lineStart < text.length) {
            val newline = text.indexOf('\n', lineStart).takeIf { it >= 0 }
            val lineEnd = newline?.let { it + 1 } ?: text.length
            val line = text.substring(lineStart, newline ?: text.length)
            val open = fence
            if (open != null) {
                if (isFenceClose(line, open)) fence = null
                lineStart = lineEnd
                continue
            }
            val opening = fenceOpening(line)
            if (opening != null) {
                fence = opening
                lineStart = lineEnd
                continue
            }
            if (isBlank(line, 0)) {
                lineStart = lineEnd
                continue
            }
            if (lineStart >= codeUntil) {
                val start = blockStart(line)
                if (start != null) {
                    val (tag, tagStart) = start
                    val block = scanBlock(text, lineStart + tagStart, tag)
                    pushMarkdown(markdownStart, lineStart)
                    val html = text.substring(lineStart, block.end)
                    if (!block.complete) {
                        segments += Segment.Layout(html, false)
                        return segments
                    }
                    if (block.image) segments += Segment.Layout(html, true) else pushMarkdown(lineStart, block.end)
                    markdownStart = block.end
                    lineStart = block.end
                    continue
                }
                if (newline == null && isPartialStart(line)) {
                    pushMarkdown(markdownStart, lineStart)
                    segments += Segment.Layout(line, false)
                    return segments
                }
            }
            codeUntil = spans.skip(maxOf(lineStart, codeUntil), lineEnd, codeUntil)
            lineStart = lineEnd
        }
        pushMarkdown(markdownStart, text.length)
        return segments
    }

    // The reference's scanning, unit for unit

    private class Fence(val marker: Char, val length: Int)

    private fun isBlank(line: String, from: Int): Boolean = (from until line.length).all { line[it] == ' ' || line[it] == '\t' || line[it] == '\r' }

    /**
     * `BLOCK_START`: up to three spaces, `<` and a root tag, then a tab,
     * newline, form feed, return, space, `/`, `>` or the end. The tag and where its `<` is.
     */
    private fun blockStart(line: String): Pair<String, Int>? {
        var indent = 0
        while (indent < line.length && indent < 4 && line[indent] == ' ') indent++
        if (indent > 3 || indent >= line.length || line[indent] != '<') return null
        for (tag in roots) {
            val end = indent + 1 + tag.length
            if (end > line.length) continue
            val written = line.substring(indent + 1, end).map { if (it in 'A'..'Z') it + 0x20 else it }.joinToString("")
            if (written != tag) continue
            if (end == line.length || line[end] in "\t\n\u000C\r />") return tag to indent
        }
        return null
    }

    /** `PARTIAL_START`: a last line that is only `<` and the start of a root tag. */
    private fun isPartialStart(line: String): Boolean {
        var indent = 0
        while (indent < line.length && line[indent] == ' ') indent++
        if (indent > 3 || indent >= line.length || line[indent] != '<') return false
        val rest = line.substring(indent + 1)
        if (!rest.all { it in 'A'..'Z' || it in 'a'..'z' }) return false
        val name = rest.lowercase()
        return roots.any { it.startsWith(name) }
    }

    private fun fencePrefixEnd(line: String): Int {
        var index = 0
        while (index < line.length && (line[index] == ' ' || line[index] == '\t')) index++
        while (index < line.length && line[index] == '>') {
            index++
            if (index < line.length && (line[index] == ' ' || line[index] == '\t')) index++
        }
        while (index < line.length && (line[index] == ' ' || line[index] == '\t')) index++
        return index
    }

    private fun runLength(line: String, start: Int, marker: Char): Int {
        var length = 0
        while (start + length < line.length && line[start + length] == marker) length++
        return length
    }

    private fun fenceOpening(line: String): Fence? {
        val start = fencePrefixEnd(line)
        if (start >= line.length || (line[start] != '`' && line[start] != '~')) return null
        val marker = line[start]
        val length = runLength(line, start, marker)
        if (length < 3) return null
        val info = line.substring(start + length)
        if (info.any { it == '\r' || it == '\u2028' || it == '\u2029' }) return null
        if (marker == '`' && '`' in info) return null
        return Fence(marker, length)
    }

    private fun isFenceClose(line: String, fence: Fence): Boolean {
        val start = fencePrefixEnd(line)
        if (start >= line.length || line[start] != fence.marker) return false
        val length = runLength(line, start, fence.marker)
        return length >= fence.length && isBlank(line, start + length)
    }

    private fun isTagNameStart(unit: Char) = unit in 'A'..'Z' || unit in 'a'..'z'
    private fun isTagNameUnit(unit: Char) = isTagNameStart(unit) || unit in '0'..'9' || unit == '-'

    private class Scanned(val end: Int, val complete: Boolean, val image: Boolean)

    /** `scanBlock`: past the line the root closes on; incomplete when it never closes or a tag or comment is cut off. */
    private fun scanBlock(units: String, start: Int, tag: String): Scanned {
        val incomplete = Scanned(units.length, false, false)
        var depth = 0
        var image = false
        var index = start
        while (index < units.length) {
            if (units[index] != '<') { index++; continue }
            if (units.startsWith("<!--", index)) {
                val close = units.indexOf("-->", index + 4)
                if (close < 0) return incomplete
                index = close + 3
                continue
            }
            val closing = index + 1 < units.length && units[index + 1] == '/'
            val nameStart = index + if (closing) 2 else 1
            if (nameStart >= units.length || !isTagNameStart(units[nameStart])) { index++; continue }
            var nameEnd = nameStart
            while (nameEnd < units.length && isTagNameUnit(units[nameEnd])) nameEnd++
            val name = units.substring(nameStart, nameEnd).lowercase()
            val tagEnd = (if (closing) units.indexOf('>', nameEnd).takeIf { it >= 0 } else openTagEnd(units, nameEnd)) ?: return incomplete
            index = tagEnd + 1
            if (!closing && name == "img") image = true
            if (name != tag) continue
            if (closing) depth-- else if (tag != "img") depth++
            if (depth > 0) continue
            val newline = units.indexOf('\n', index)
            return Scanned(if (newline >= 0) newline + 1 else units.length, true, image)
        }
        return incomplete
    }

    /** `openTagEnd`: the `>` ending an open tag, past values quoted after `=`. */
    private fun openTagEnd(units: String, start: Int): Int? {
        var position = start
        var afterEquals = false
        while (position < units.length) {
            val unit = units[position]
            if (unit == '>') return position
            if ((unit == '"' || unit == '\'') && afterEquals) {
                val close = units.indexOf(unit, position + 1)
                if (close < 0) return null
                position = close + 1
                afterEquals = false
                continue
            }
            if (unit == '=') afterEquals = true else if (!JS.isWhitespace(unit)) afterEquals = false
            position++
        }
        return null
    }

    /**
     * The reference's `CodeSpans`: where the code spans opening on a line
     * end, bounded by their paragraph. Asked in increasing order.
     */
    private class CodeSpans(val units: String) {
        var runs: Map<Int, List<Int>>? = null
        val passed = HashMap<Int, Int>()
        var paragraphFrom = -1
        var paragraphEnd = -1

        fun skip(from: Int, lineEnd: Int, codeUntil: Int): Int {
            var until = codeUntil
            var index = from
            while (index < lineEnd) {
                val next = units.indexOf('`', index).takeIf { it in 0 until lineEnd } ?: break
                if (isEscaped(index, next)) { index = next + 1; continue }
                var length = 1
                while (next + length < units.length && units[next + length] == '`') length++
                val close = closing(next + length, length, paragraphEnd(lineEnd))
                index = close?.let { it + length } ?: (next + length)
                if (close != null) until = index
            }
            return until
        }

        fun isEscaped(from: Int, at: Int): Boolean {
            var backslashes = 0
            while (at - backslashes - 1 >= from && units[at - backslashes - 1] == '\\') backslashes++
            return backslashes % 2 == 1
        }

        fun closing(from: Int, length: Int, end: Int): Int? {
            if (runs == null) {
                val found = HashMap<Int, MutableList<Int>>()
                var index = units.indexOf('`')
                while (index >= 0) {
                    var run = 1
                    while (index + run < units.length && units[index + run] == '`') run++
                    found.getOrPut(run) { ArrayList() } += index
                    index = units.indexOf('`', index + run)
                }
                runs = found
            }
            val list = runs?.get(length) ?: return null
            var count = passed[length] ?: 0
            while (count < list.size && list[count] < from) count++
            passed[length] = count
            if (count >= list.size || list[count] >= end) return null
            return list[count]
        }

        fun paragraphEnd(from: Int): Int {
            if (paragraphFrom >= 0 && paragraphFrom <= from && from <= paragraphEnd) return paragraphEnd
            var lineStart = from
            var end = units.length
            while (lineStart < units.length) {
                val newline = units.indexOf('\n', lineStart).takeIf { it >= 0 }
                val line = units.substring(lineStart, newline ?: units.length)
                if ((line.all { it == ' ' || it == '\t' || it == '\r' }) || fenceOpeningOf(line)) { end = lineStart; break }
                if (newline == null) break
                lineStart = newline + 1
            }
            paragraphFrom = from
            paragraphEnd = end
            return end
        }

        private fun fenceOpeningOf(line: String) = fenceOpening(line) != null
    }
}
