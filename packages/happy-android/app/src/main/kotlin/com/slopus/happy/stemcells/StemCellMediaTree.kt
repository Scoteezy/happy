package com.slopus.happy.stemcells

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/**
 * A layout an agent wrote for media it showed (happy-agent `API.md`: a
 * `media` presentation's `tree`, and "Media layouts in text"): typed nodes,
 * never HTML. A presentation's tree arrives parsed; a block in the agent's
 * text is parsed here, the way `parseMediaLayoutHtml` parses it. A port of
 * happy-ios `StemCellMediaTree`.
 */
data class StemCellMediaTree(val root: List<Node>) {
    sealed class Node {
        data class Element(val tag: String, val style: Map<String, String>, val children: List<Node>) : Node()
        /** The item at `index` of the media the tree arranges. */
        data class Item(val index: Int, val alt: String?, val style: Map<String, String>) : Node()
        data class Text(val text: String) : Node()
        data object LineBreak : Node()
    }

    /** What a layout block's HTML became. */
    data class Parsed(
        val tree: StemCellMediaTree,
        /** The indexes the tree places, in order, each once. */
        val placed: List<Int>,
        /** Content was lost to a limit: the block is then shown as written. */
        val truncated: Boolean,
    )

    /** One run of a paragraph's text with what its elements made of it. */
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        /** `small`, `normal`, `large` or `title`. */
        val size: String? = null,
        /** `default`, `muted` or `accent`. */
        val tone: String? = null,
    )

    /**
     * The tree as boxes: containers, items, and paragraphs of inline text
     * carrying their inherited text style. Text is drawn by the phone; the
     * boxes are laid out by `StemCellMediaTreeLayout`.
     */
    sealed class Block {
        data class Container(val style: Map<String, String>, val children: List<Block>) : Block()
        data class Item(val index: Int, val alt: String?, val style: Map<String, String>) : Block()
        /** `align` is the inherited `textAlign`. */
        data class Paragraph(val align: String?, val runs: List<Run>) : Block()
    }

    val blocks: List<Block>
        get() {
            val out = ArrayList<Block>()
            val runs = ArrayList<Run>()
            walk(root, Inherited(), runs, out)
            flush(runs, null, out)
            return out
        }

    private data class Inherited(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val size: String? = null,
        val tone: String? = null,
        val align: String? = null,
        val code: Boolean = false,
    ) {
        fun applying(tag: String, style: Map<String, String>): Inherited {
            var next = this
            if (tag == "strong") next = next.copy(bold = true)
            if (tag == "em") next = next.copy(italic = true)
            if (tag == "code") next = next.copy(code = true)
            style["fontWeight"]?.let { weight -> next = next.copy(bold = weight == "bold" || weight == "bolder" || (weight.toIntOrNull()?.let { it >= 600 } ?: false)) }
            style["fontStyle"]?.let { next = next.copy(italic = it != "normal") }
            style["fontSize"]?.let { next = next.copy(size = it) }
            style["tone"]?.let { next = next.copy(tone = it) }
            style["textAlign"]?.let { next = next.copy(align = it) }
            return next
        }
    }

    companion object {
        private val tags = setOf("div", "section", "figure", "figcaption", "p", "span", "strong", "em", "code")
        private val blockTags = setOf("div", "section", "figure", "figcaption", "p")

        // A presentation's tree

        /**
         * Desktop's `mediaLayoutProject`: a `tree` layout as the phone can
         * draw it, every item exactly once. An index out of range or named
         * again is skipped, an unknown tag holds its content as a `div`, and
         * every item the tree never named follows it. Null for any other layout.
         */
        fun decode(layout: JsonElement?, itemCount: Int): StemCellMediaTree? {
            val o = layout as? JsonObject ?: return null
            if (o.string("type") != "tree") return null
            val values = o["root"] as? JsonArray ?: return null
            val seen = HashSet<Int>()
            val root = nodes(values, itemCount, seen, 0).toMutableList()
            for (index in 0 until itemCount) if (index !in seen) root += Node.Item(index, null, emptyMap())
            return if (root.isEmpty()) null else StemCellMediaTree(root)
        }

        /** Deeper than the daemon ever sends (it stops at 12); a guard, not a limit. */
        private const val MAX_DECODE_DEPTH = 32

        private fun JsonElement?.string(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun nodes(values: List<JsonElement>, itemCount: Int, seen: MutableSet<Int>, depth: Int): List<Node> {
            val result = ArrayList<Node>()
            for (value in values) {
                when (value.string("type")) {
                    "break" -> result += Node.LineBreak
                    "text" -> value.string("text")?.takeIf { it.isNotEmpty() }?.let { result += Node.Text(it) }
                    "item" -> {
                        val number = ((value as JsonObject)["index"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: continue
                        if (number != floor(number) || number < 0 || number >= itemCount) continue
                        if (!seen.add(number.toInt())) continue
                        val alt = value.string("alt")?.takeIf { it.isNotEmpty() }
                        result += Node.Item(number.toInt(), alt, style(value["style"]))
                    }
                    "element" -> {
                        if (depth >= MAX_DECODE_DEPTH) continue
                        val tag = value.string("tag") ?: continue
                        val children = nodes((value as JsonObject)["children"] as? JsonArray ?: emptyList(), itemCount, seen, depth + 1)
                        result += Node.Element(if (tag in tags) tag else "div", style(value["style"]), children)
                    }
                    else -> continue
                }
            }
            return result
        }

        /** The fields the subset names with values its grammar accepts; any other is left out, as a client must. */
        private fun style(value: JsonElement?): Map<String, String> {
            val o = value as? JsonObject ?: return emptyMap()
            val style = HashMap<String, String>()
            for ((key, field) in o) {
                val text = (field as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                StemCellMediaTreeStyle.value(key, text)?.let { style[key] = it }
            }
            return style
        }

        // Layout HTML

        const val MAX_HTML_LENGTH = 32 * 1024
        private const val MAX_DEPTH = 12
        private const val MAX_HTML_DEPTH = 32
        private const val MAX_NODES = 500
        private const val MAX_TEXT = 8000
        private const val MAX_ALT = 1000

        /** Left out together with everything inside them. */
        private val droppedTags = setOf(
            "applet", "area", "audio", "base", "button", "canvas", "dialog", "embed", "form", "frame", "frameset", "head",
            "iframe", "input", "link", "map", "math", "meta", "noscript", "object", "option", "portal", "script", "select",
            "slot", "source", "style", "svg", "template", "textarea", "title", "track", "video",
        )

        /**
         * `parseMediaLayoutHtml`: the HTML read as a browser reads it, then
         * rebuilt from what the subset allows, never failing. A tag the subset
         * lacks is unwrapped, one that runs or loads something is left out
         * with its content, an `<img>` whose `src` `resolve` doesn't name or
         * that is already placed is left out.
         */
        fun parse(html: String, resolve: (String) -> Int?): Parsed {
            if (html.length > MAX_HTML_LENGTH) return Parsed(StemCellMediaTree(emptyList()), emptyList(), true)
            val walk = Walk(resolve)
            val body = StemCellMediaHTML.body(html)
            val root = walk.children(body.children, false, 0, 0)
            return Parsed(StemCellMediaTree(root), walk.placed, walk.truncated)
        }

        /** The reference's walk over the document, with its budgets. */
        private class Walk(val resolve: (String) -> Int?) {
            val placed = ArrayList<Int>()
            var nodes = 0
            var text = 0
            var truncated = false

            fun count(): Boolean {
                if (nodes >= MAX_NODES) { truncated = true; return false }
                nodes++
                return true
            }

            fun children(children: List<StemCellMediaHTML.Child>, ignoresWhitespace: Boolean, depth: Int, htmlDepth: Int): List<Node> {
                val converted = ArrayList<Node>()
                for (child in children) convert(child, depth, htmlDepth, converted)
                return tidy(converted, ignoresWhitespace)
            }

            fun convert(child: StemCellMediaHTML.Child, depth: Int, htmlDepth: Int, into: MutableList<Node>) {
                when (child) {
                    is StemCellMediaHTML.Child.Words -> {
                        val collapsed = collapse(child.text.value)
                        val room = MAX_TEXT - text
                        if (room <= 0 || !count()) { truncated = true; return }
                        if (collapsed.length > room) truncated = true
                        val kept = collapsed.take(room)
                        text += minOf(collapsed.length, room)
                        into += Node.Text(kept)
                    }
                    is StemCellMediaHTML.Child.Node -> {
                        val element = child.element
                        val tag = element.name
                        if (htmlDepth >= MAX_HTML_DEPTH) { truncated = true; return }
                        if (!count()) return
                        if (tag in droppedTags) return
                        if (tag == "img") {
                            image(element)?.let { into += it }
                            return
                        }
                        if (tag == "br") {
                            into += Node.LineBreak
                            return
                        }
                        if (tag !in tags || depth >= MAX_DEPTH) {
                            for (inner in element.children) convert(inner, depth, htmlDepth + 1, into)
                            return
                        }
                        val style = parseStyle(element.attribute("style"))
                        val display = style["display"] ?: ""
                        val flow = "flex" in display || "grid" in display
                        into += Node.Element(tag, style, children(element.children, flow, depth + 1, htmlDepth + 1))
                    }
                }
            }

            fun image(element: StemCellMediaHTML.Element): Node? {
                val src = element.attribute("src") ?: return null
                val index = resolve(src) ?: return null
                if (index < 0 || index in placed) return null
                placed += index
                val style = parseStyle(element.attribute("style"))
                var alt: String? = null
                element.attribute("alt")?.let { raw ->
                    val trimmed = trimJS(collapse(raw))
                    val room = maxOf(0, minOf(MAX_ALT, MAX_TEXT - text))
                    val units = trimmed.take(room)
                    text += units.length
                    alt = units.ifEmpty { null }
                }
                return Node.Item(index, alt, style)
            }
        }

        /** Runs of tab, newline, form feed, return and space as one space. */
        internal fun collapse(text: String): String {
            val out = StringBuilder()
            var space = false
            for (c in text) {
                if (StemCellMediaHTML.isSpace(c.code)) {
                    if (!space) out.append(' ')
                    space = true
                } else {
                    out.append(c)
                    space = false
                }
            }
            return out.toString()
        }

        /** JavaScript's `trim`: its whitespace and line terminators off both ends. */
        internal fun trimJS(text: String): String = text.trim { JS.isWhitespace(it) }

        /**
         * Neighbouring text joined; whitespace that only separates blocks,
         * starts or ends a list, or sits between a flex or grid's items, dropped.
         */
        private fun tidy(nodes: List<Node>, ignoresWhitespace: Boolean): List<Node> {
            val joined = ArrayList<Node>()
            for (node in nodes) {
                val last = joined.lastOrNull()
                if (node is Node.Text && last is Node.Text) {
                    var merged = last.text + node.text
                    while ("  " in merged) merged = merged.replace("  ", " ")
                    joined[joined.size - 1] = Node.Text(merged)
                } else {
                    joined += node
                }
            }
            fun isBlock(node: Node?) = node is Node.Element && node.tag in blockTags
            return joined.filterIndexed { position, node ->
                if (node != Node.Text(" ")) return@filterIndexed true
                val dropped = ignoresWhitespace || position == 0 || position == joined.size - 1 ||
                    isBlock(joined[position - 1]) || isBlock(joined[position + 1])
                !dropped
            }
        }

        // Style

        /**
         * `parseMediaLayoutHtmlStyle`: one `style` attribute's declarations in
         * the subset, normalized; one holding a backslash or a quote is left
         * out whole. Later declarations replace earlier ones.
         */
        fun parseStyle(source: String?): Map<String, String> {
            if (source == null || '\\' in source || '"' in source || '\'' in source) return emptyMap()
            var text: String = source
            while (true) {
                val open = text.indexOf("/*")
                if (open < 0) break
                val close = text.indexOf("*/", open + 2)
                text = text.substring(0, open) + " " + (if (close >= 0) text.substring(close + 2) else "")
            }
            val style = HashMap<String, String>()
            for (declaration in text.split(";")) {
                val trimmed = trimJS(declaration)
                if (trimmed.isEmpty()) continue
                val colon = trimmed.indexOf(':').takeIf { it >= 0 }
                val property = StemCellMediaHTML.asciiLowercase(trimJS(trimmed.substring(0, colon ?: trimmed.length)))
                val key = StemCellMediaTreeStyle.keys[property] ?: continue
                val value = colon?.let { trimJS(trimmed.substring(it + 1)) } ?: ""
                val normalized = StemCellMediaTreeStyle.value(key, value) ?: continue
                style[key] = normalized
            }
            return style
        }

        // Blocks and runs

        private fun walk(nodes: List<Node>, inherited: Inherited, runs: MutableList<Run>, out: MutableList<Block>) {
            for (node in nodes) {
                when (node) {
                    is Node.Text -> runs += Run(node.text, inherited.bold, inherited.italic, inherited.code, inherited.size, inherited.tone)
                    Node.LineBreak -> runs += Run("\n", inherited.bold, inherited.italic, inherited.code, inherited.size, inherited.tone)
                    is Node.Item -> {
                        flush(runs, inherited.align, out)
                        out += Block.Item(node.index, node.alt, node.style)
                    }
                    is Node.Element -> {
                        val display = node.style["display"]
                        if (display == "none") continue
                        val next = inherited.applying(node.tag, node.style)
                        val inline = node.tag !in blockTags && (display == null || display == "inline")
                        if (inline) {
                            walk(node.children, next, runs, out)
                        } else {
                            flush(runs, inherited.align, out)
                            val inner = ArrayList<Block>()
                            val innerRuns = ArrayList<Run>()
                            walk(node.children, next, innerRuns, inner)
                            flush(innerRuns, next.align, inner)
                            out += Block.Container(node.style, inner)
                        }
                    }
                }
            }
        }

        /** The runs so far as one paragraph, its outer spaces trimmed; nothing when they are only whitespace. */
        private fun flush(runs: MutableList<Run>, align: String?, out: MutableList<Block>) {
            val kept = runs.toMutableList()
            runs.clear()
            while (kept.isNotEmpty() && kept.first().text.all { it == ' ' }) kept.removeAt(0)
            while (kept.isNotEmpty() && kept.last().text.all { it == ' ' }) kept.removeAt(kept.size - 1)
            if (kept.isEmpty()) return
            kept[0] = kept[0].copy(text = kept[0].text.trimStart(' '))
            kept[kept.size - 1] = kept[kept.size - 1].copy(text = kept[kept.size - 1].text.trimEnd(' '))
            out += Block.Paragraph(align, kept)
        }
    }
}
