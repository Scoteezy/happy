package com.slopus.happy.stemcells

/**
 * The sidebar document: one `<nav>` of custom elements, every one of them
 * an item the person can drag, parsed into a typed tree and written back in
 * one canonical form. A port of happy-ios `SpaceSidebarDocument.swift`
 * (itself a port of `@slopus/happy-agent-client`'s `spaceSidebarDocument.ts`),
 * so Android, iOS and the daemon read the same skeleton from the same text.
 * `SpaceSidebarFixtureTest` pins it against the TypeScript output.
 *
 * Offsets and lengths count UTF-16 code units, as JavaScript and Kotlin do.
 */
data class SpaceSidebarDocument(val foldRibbon: SpaceSidebarFoldRibbon? = null, val items: List<SpaceSidebarNode>)

enum class SpaceSidebarFoldRibbon(val raw: String) {
    Hover("hover"), Always("always");

    companion object {
        fun of(raw: String?): SpaceSidebarFoldRibbon? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * One node: a top-level item, or a row or session nested under a row.
 * Fields a kind does not have keep their defaults.
 */
data class SpaceSidebarNode(
    val kind: Kind,
    val id: String,
    /** A section's heading. */
    val label: String = "",
    /** A section folds the items after it. */
    val collapsed: Boolean = false,
    /** A row or session draws what is nested under it. */
    val expanded: Boolean = false,
    val foldRibbon: SpaceSidebarFoldRibbon? = null,
    /** A widget's verbatim content. */
    val html: String = "",
    /** A row's or session's nested rows and sessions. */
    val children: List<SpaceSidebarNode> = emptyList(),
) {
    enum class Kind(val raw: String) {
        Search("search"), Create("create"), Section("section"), Separator("separator"), Space("space"), Widget("widget");

        companion object {
            fun of(raw: String?): Kind? = entries.firstOrNull { it.raw == raw }
        }
    }

    companion object {
        val search = SpaceSidebarNode(Kind.Search, "happy-search")
        val create = SpaceSidebarNode(Kind.Create, "happy-create")
        fun section(id: String, label: String, collapsed: Boolean = false) = SpaceSidebarNode(Kind.Section, id, label = label, collapsed = collapsed)
        fun separator(id: String) = SpaceSidebarNode(Kind.Separator, id)
        fun space(id: String, expanded: Boolean = false, foldRibbon: SpaceSidebarFoldRibbon? = null, children: List<SpaceSidebarNode> = emptyList()) =
            SpaceSidebarNode(Kind.Space, id, expanded = expanded, foldRibbon = foldRibbon, children = children)
        fun widget(id: String, html: String) = SpaceSidebarNode(Kind.Widget, id, html = html)
    }
}

/** Why a document or an operation was refused, with where in the text. */
class SpaceSidebarError(
    override val message: String,
    val offset: Int = 0,
    /** Set for an operation that cannot apply: its zero-based place in the batch. */
    val operationIndex: Int? = null,
) : Exception(message)

/** The few JavaScript text rules the port must follow exactly. */
internal object JS {
    /** `\s` and `String.prototype.trim`'s whitespace, by UTF-16 unit. */
    fun isWhitespace(unit: Char): Boolean = when (unit.code) {
        in 0x09..0x0D, 0x20, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
        else -> false
    }

    private fun isLower(unit: Char) = unit in 'a'..'z'
    private fun isNameUnit(unit: Char) = isLower(unit) || unit in '0'..'9' || unit == '-'

    /** `\w`: ASCII letters, digits and `_`. */
    private fun isWordUnit(unit: Char) = unit in 'a'..'z' || unit in 'A'..'Z' || unit in '0'..'9' || unit == '_'

    fun whitespaceRun(text: String, at: Int): Int {
        var end = at
        while (end < text.length && isWhitespace(text[end])) end++
        return end - at
    }

    /** `[a-z][a-z0-9-]*` at `at`; its length. */
    private fun name(text: String, at: Int): Int {
        if (at >= text.length || !isLower(text[at])) return 0
        var end = at + 1
        while (end < text.length && isNameUnit(text[end])) end++
        return end - at
    }

    /** `/<\/([a-z][a-z0-9-]*)\s*>/y` */
    fun matchClose(text: String, at: Int): Pair<String, Int>? {
        if (at + 1 >= text.length || text[at] != '<' || text[at + 1] != '/') return null
        val length = name(text, at + 2)
        if (length == 0) return null
        var end = at + 2 + length
        end += whitespaceRun(text, end)
        if (end >= text.length || text[end] != '>') return null
        return text.substring(at + 2, at + 2 + length) to end + 1 - at
    }

    /** `/<([a-z][a-z0-9-]*)\b/y`, backtracking the name to a word boundary. */
    fun matchOpen(text: String, at: Int): Pair<String, Int>? {
        if (at >= text.length || text[at] != '<') return null
        var length = name(text, at + 1)
        while (length > 0) {
            val last = text[at + length]
            val next = if (at + 1 + length < text.length) isWordUnit(text[at + 1 + length]) else false
            if (isWordUnit(last) != next) break
            length--
        }
        if (length == 0) return null
        return text.substring(at + 1, at + 1 + length) to 1 + length
    }

    /** `/([a-z][a-z0-9-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')/y` */
    fun matchAttribute(text: String, at: Int): Triple<String, String, Int>? {
        val length = name(text, at)
        if (length == 0) return null
        var cursor = at + length
        cursor += whitespaceRun(text, cursor)
        if (cursor >= text.length || text[cursor] != '=') return null
        cursor++
        cursor += whitespaceRun(text, cursor)
        if (cursor >= text.length || (text[cursor] != '"' && text[cursor] != '\'')) return null
        val close = text.indexOf(text[cursor], cursor + 1)
        if (close < 0) return null
        return Triple(text.substring(at, at + length), text.substring(cursor + 1, close), close + 1 - at)
    }

    /** `/<\/happy-widget\s*>/y` */
    fun matchWidgetEnd(text: String, at: Int): Int? {
        val tag = "</happy-widget"
        if (!text.startsWith(tag, at)) return null
        var end = at + tag.length
        end += whitespaceRun(text, end)
        if (end >= text.length || text[end] != '>') return null
        return end + 1 - at
    }

    /** The first ASCII-case-insensitive occurrence of `needle` from `from`. */
    fun indexOfCaseInsensitive(text: String, needle: String, from: Int): Int? {
        fun fold(unit: Char) = if (unit in 'A'..'Z') unit + 0x20 else unit
        var index = from
        while (index + needle.length <= text.length) {
            var matches = true
            for (offset in needle.indices) if (fold(text[index + offset]) != fold(needle[offset])) { matches = false; break }
            if (matches) return index
            index++
        }
        return null
    }

    /** `String.utf8.count`: a lone surrogate counts as the U+FFFD it encodes to. */
    fun utf8Count(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            val unit = text[index]
            count += when {
                unit.code < 0x80 -> 1
                unit.code < 0x800 -> 2
                unit.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> { index++; 4 }
                else -> 3
            }
            index++
        }
        return count
    }
}

object SpaceSidebar {
    const val maxBytes = 128 * 1024
    const val maxElements = 1000
    const val maxDepth = 16
    const val maxWidgetBytes = 16 * 1024
    /** The IDs of the nodes named by their tags; no other node may use them. */
    val reservedIDs = listOf("nav", "happy-search", "happy-create")

    // Schemas

    /** `spaceSidebarIdSchema`: 1–128 UTF-16 units, no whitespace or control. */
    internal fun isValidID(id: String): Boolean =
        id.length in 1..128 && id.all { !JS.isWhitespace(it) && it.code > 0x1F && it.code != 0x7F }

    /** `spaceSidebarLabelSchema`: 1–256 UTF-16 units with something that is not whitespace. */
    internal fun isValidLabel(label: String): Boolean = label.length in 1..256 && label.any { !JS.isWhitespace(it) }

    // Parse

    private class Schema(val allowed: Set<String>, val required: Set<String>)

    private val attributes = mapOf(
        "nav" to Schema(setOf("fold-ribbon"), emptySet()),
        "happy-search" to Schema(emptySet(), emptySet()),
        "happy-create" to Schema(emptySet(), emptySet()),
        "happy-section" to Schema(setOf("id", "label", "collapsed"), setOf("id", "label")),
        "happy-separator" to Schema(setOf("id"), setOf("id")),
        "happy-space" to Schema(setOf("id", "expanded", "fold-ribbon"), setOf("id")),
        "happy-widget" to Schema(setOf("id"), setOf("id")),
    )
    private val children = mapOf(
        "nav" to listOf("happy-search", "happy-create", "happy-section", "happy-separator", "happy-space", "happy-widget"),
        "happy-space" to listOf("happy-space"),
    )
    private const val widgetClose = "</happy-widget"

    private class Element(val tag: String, val attrs: Map<String, String>, val start: Int) {
        val children = ArrayList<Element>()
        var content = ""
    }

    /** Parses and validates a whole document; throws [SpaceSidebarError]. */
    fun parse(html: String): SpaceSidebarDocument {
        val text = html
        if (text.indexOf('\u0000') >= 0) throw SpaceSidebarError("The sidebar must contain valid Unicode text without null characters.")
        if (JS.utf8Count(html) > maxBytes) throw SpaceSidebarError("The sidebar exceeds 128 KiB.")
        val stack = ArrayList<Element>()
        var root: Element? = null
        var count = 0
        var at = 0
        fun fail(message: String, offset: Int? = null) = SpaceSidebarError(message, offset ?: at)

        while (at < text.length) {
            if (text[at] != '<') {
                var next = at
                while (next < text.length && text[next] != '<') next++
                for (index in at until next) if (!JS.isWhitespace(text[index])) throw fail("The sidebar holds elements, not text.")
                at = next
                continue
            }
            val start = at
            val close = JS.matchClose(text, at)
            if (close != null) {
                val node = stack.removeLastOrNull()
                if (node == null || node.tag != close.first) throw fail("Closing element does not match the open element.")
                at += close.second
                continue
            }
            val (tag, openLength) = JS.matchOpen(text, at) ?: throw fail("Expected an allowed element.")
            val schema = attributes[tag] ?: throw fail("Element $tag is not allowed.")
            at += openLength
            val attrs = HashMap<String, String>()
            while (at < text.length) {
                val whitespace = JS.whitespaceRun(text, at)
                at += whitespace
                if (at < text.length && (text[at] == '>' || (text[at] == '/' && at + 1 < text.length && text[at + 1] == '>'))) break
                if (whitespace == 0) throw fail("Attributes must be separated by whitespace.")
                val (key, value, length) = JS.matchAttribute(text, at) ?: throw fail("Attribute values must be quoted.")
                if (attrs.containsKey(key)) throw fail("Duplicate attribute $key.")
                attrs[key] = decodeEntities(value)
                at += length
            }
            if (!attributesValid(attrs, schema)) throw fail("Invalid attributes on $tag.", start)
            val selfClosing = at + 1 < text.length && text[at] == '/' && text[at + 1] == '>'
            if (selfClosing && tag == "nav") throw fail("The nav cannot be self-closing.")
            if (!(at < text.length && text[at] == '>') && !selfClosing) throw fail("The opening element is incomplete.")
            at += if (selfClosing) 2 else 1
            val parent = stack.lastOrNull()
            if (tag == "nav" && (parent != null || root != null)) throw fail("The sidebar needs exactly one nav root.", start)
            if (parent == null && tag != "nav") throw fail("The sidebar needs a nav root.", start)
            if (parent != null && tag !in children[parent.tag].orEmpty()) {
                throw fail(if (parent.tag == "happy-space") "A happy-space may contain only happy-space elements." else "A ${parent.tag} cannot contain $tag.", start)
            }
            count++
            if (count > maxElements || stack.size >= maxDepth) throw fail("The sidebar exceeds 1,000 elements or depth 16.", start)
            val node = Element(tag, attrs, start)
            if (parent != null) parent.children += node else root = node
            if (tag == "happy-widget" && !selfClosing) {
                // A widget's content runs verbatim to its closing tag, which it may never contain.
                val end = JS.indexOfCaseInsensitive(text, widgetClose, at)
                val contentStart = at
                at = end ?: start
                val closing = end?.let { JS.matchWidgetEnd(text, it) }
                if (end == null || closing == null) throw fail("A widget's content may not contain $widgetClose other than its own closing tag.")
                node.content = text.substring(contentStart, end)
                widgetCheck(node.content, contentStart)
                at = end + closing
                continue
            }
            if (!selfClosing) stack += node
        }
        if (stack.isNotEmpty() || root == null) throw fail("The sidebar has unclosed elements or no nav root.")
        return documentOf(root)
    }

    private fun attributesValid(attrs: Map<String, String>, schema: Schema): Boolean {
        if (!schema.allowed.containsAll(attrs.keys) || !attrs.keys.containsAll(schema.required)) return false
        for ((key, value) in attrs) {
            val valid = when (key) {
                "id" -> isValidID(value)
                "label" -> isValidLabel(value)
                "collapsed", "expanded" -> value == "true" || value == "false"
                "fold-ribbon" -> SpaceSidebarFoldRibbon.of(value) != null
                else -> false
            }
            if (!valid) return false
        }
        return true
    }

    /** Checks a widget's content, as parsing and every operation do. */
    fun widgetCheck(html: String, offset: Int = 0) {
        if (html.indexOf('\u0000') >= 0) throw SpaceSidebarError("A widget must contain valid Unicode text without null characters.", offset)
        if (JS.utf8Count(html) > maxWidgetBytes) throw SpaceSidebarError("A widget's content exceeds 16 KiB.", offset)
        if (JS.indexOfCaseInsensitive(html, widgetClose, 0) != null) {
            throw SpaceSidebarError("A widget's content may not contain $widgetClose.", offset)
        }
    }

    private fun documentOf(root: Element): SpaceSidebarDocument {
        val seen = HashSet(reservedIDs)
        var searches = 0
        var creates = 0
        fun identify(node: Element): String {
            val id = node.attrs.getValue("id")
            if (id in seen) throw SpaceSidebarError(if (id in reservedIDs) "The ID $id is reserved." else "Duplicate ID $id.", node.start)
            seen += id
            return id
        }
        fun space(node: Element): SpaceSidebarNode {
            val id = identify(node)
            return SpaceSidebarNode.space(
                id, expanded = node.attrs["expanded"] == "true",
                foldRibbon = SpaceSidebarFoldRibbon.of(node.attrs["fold-ribbon"]),
                children = node.children.map(::space),
            )
        }
        val items = ArrayList<SpaceSidebarNode>()
        for (node in root.children) {
            items += when (node.tag) {
                "happy-search" -> { searches++; SpaceSidebarNode.search }
                "happy-create" -> { creates++; SpaceSidebarNode.create }
                "happy-section" -> SpaceSidebarNode.section(identify(node), node.attrs.getValue("label"), node.attrs["collapsed"] == "true")
                "happy-separator" -> SpaceSidebarNode.separator(identify(node))
                "happy-widget" -> SpaceSidebarNode.widget(identify(node), node.content)
                else -> space(node)
            }
        }
        if (searches != 1) throw SpaceSidebarError("The sidebar needs exactly one happy-search.")
        if (creates != 1) throw SpaceSidebarError("The sidebar needs exactly one happy-create.")
        return SpaceSidebarDocument(SpaceSidebarFoldRibbon.of(root.attrs["fold-ribbon"]), items)
    }

    /** `&#…;`, `&#x…;` and the five named entities, any letter case. */
    internal fun decodeEntities(value: String): String {
        if ('&' !in value) return value
        val out = StringBuilder()
        var index = 0
        while (index < value.length) {
            if (value[index] == '&') {
                val semicolon = value.indexOf(';', index)
                val replacement = if (semicolon >= 0) entity(value.substring(index + 1, semicolon)) else null
                if (replacement != null) {
                    out.append(replacement)
                    index = semicolon + 1
                    continue
                }
            }
            out.append(value[index])
            index++
        }
        return out.toString()
    }

    private fun entity(body: String): String? {
        when (val lower = body.lowercase()) {
            "amp" -> return "&"
            "lt" -> return "<"
            "gt" -> return ">"
            "quot" -> return "\""
            "apos" -> return "'"
            else -> {
                if (!lower.startsWith("#")) return null
                val digits = lower.substring(1)
                val hex = digits.startsWith("x")
                val number = if (hex) digits.substring(1) else digits
                if (number.isEmpty() || !number.all { if (hex) it in '0'..'9' || it in 'a'..'f' else it in '0'..'9' }) return null
                // Too large for a code point reads as one; it decodes like any other unusable value.
                val code = number.toLongOrNull(if (hex) 16 else 10) ?: Long.MAX_VALUE
                if (code in 1..0x10FFFF && code !in 0xD800..0xDFFF) return String(Character.toChars(code.toInt()))
                return "\uFFFD"
            }
        }
    }

    // Format

    /**
     * The canonical text: one element a line, two spaces a level, attributes
     * in grammar order, false switches and defaults left out, empty elements
     * self-closed, widget content verbatim. With `widgets = false` widgets
     * are left out.
     */
    fun format(document: SpaceSidebarDocument, widgets: Boolean = true): String {
        fun attribute(name: String, value: String): String {
            val escaped = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
            return " $name=\"$escaped\""
        }
        fun ribbon(value: SpaceSidebarFoldRibbon?) = value?.let { attribute("fold-ribbon", it.raw) } ?: ""
        val out = StringBuilder("<nav${ribbon(document.foldRibbon)}>\n")
        fun space(node: SpaceSidebarNode, indent: String) {
            out.append("$indent<happy-space${attribute("id", node.id)}${if (node.expanded) attribute("expanded", "true") else ""}${ribbon(node.foldRibbon)}")
            if (node.children.isEmpty()) { out.append("/>\n"); return }
            out.append(">\n")
            for (child in node.children) space(child, "$indent  ")
            out.append("$indent</happy-space>\n")
        }
        for (node in document.items) {
            when (node.kind) {
                SpaceSidebarNode.Kind.Search -> out.append("  <happy-search/>\n")
                SpaceSidebarNode.Kind.Create -> out.append("  <happy-create/>\n")
                SpaceSidebarNode.Kind.Section ->
                    out.append("  <happy-section${attribute("id", node.id)}${attribute("label", node.label)}${if (node.collapsed) attribute("collapsed", "true") else ""}/>\n")
                SpaceSidebarNode.Kind.Separator -> out.append("  <happy-separator${attribute("id", node.id)}/>\n")
                SpaceSidebarNode.Kind.Widget -> if (widgets) {
                    out.append(if (node.html.isEmpty()) "  <happy-widget${attribute("id", node.id)}/>\n" else "  <happy-widget${attribute("id", node.id)}>${node.html}</happy-widget>\n")
                }
                SpaceSidebarNode.Kind.Space -> space(node, "  ")
            }
        }
        return out.append("</nav>\n").toString()
    }

    // Locating

    /**
     * Where a node sits: the path of indexes to it, its parent's ID (`nav`
     * at the top), and its element depth (`nav` is 1, the top level 2).
     */
    internal class Location(val path: List<Int>, val parentId: String, val depth: Int) {
        val index get() = path.last()
        val parentPath get() = path.dropLast(1)
    }

    internal fun locate(document: SpaceSidebarDocument, id: String): Location? {
        fun search(nodes: List<SpaceSidebarNode>, path: List<Int>, parentId: String, depth: Int): Location? {
            for ((index, node) in nodes.withIndex()) {
                if (node.id == id) return Location(path + index, parentId, depth)
                if (node.kind == SpaceSidebarNode.Kind.Space) search(node.children, path + index, node.id, depth + 1)?.let { return it }
            }
            return null
        }
        return search(document.items, emptyList(), "nav", 2)
    }

    internal fun node(document: SpaceSidebarDocument, path: List<Int>): SpaceSidebarNode {
        var node = document.items[path[0]]
        for (index in path.drop(1)) node = node.children[index]
        return node
    }

    internal fun siblings(document: SpaceSidebarDocument, parentPath: List<Int>): List<SpaceSidebarNode> =
        if (parentPath.isEmpty()) document.items else node(document, parentPath).children

    /** The document with the siblings under `parentPath` edited in place by `body`. */
    internal fun withSiblings(document: SpaceSidebarDocument, parentPath: List<Int>, body: (MutableList<SpaceSidebarNode>) -> Unit): SpaceSidebarDocument {
        fun descend(nodes: List<SpaceSidebarNode>, path: List<Int>): List<SpaceSidebarNode> {
            val list = nodes.toMutableList()
            val index = path[0]
            val holder = list[index]
            list[index] = holder.copy(children = if (path.size == 1) holder.children.toMutableList().also(body) else descend(holder.children, path.drop(1)))
            return list
        }
        return if (parentPath.isEmpty()) document.copy(items = document.items.toMutableList().also(body)) else document.copy(items = descend(document.items, parentPath))
    }

    internal fun withNode(document: SpaceSidebarDocument, path: List<Int>, body: (SpaceSidebarNode) -> SpaceSidebarNode): SpaceSidebarDocument =
        withSiblings(document, path.dropLast(1)) { it[path.last()] = body(it[path.last()]) }

    /**
     * How many items a move of the item at `index` takes along: a section
     * takes its block, anything else only itself.
     */
    internal fun runLength(siblings: List<SpaceSidebarNode>, index: Int): Int {
        if (index !in siblings.indices || siblings[index].kind != SpaceSidebarNode.Kind.Section) return 1
        var end = index + 1
        while (end < siblings.size && siblings[end].kind != SpaceSidebarNode.Kind.Section && siblings[end].kind != SpaceSidebarNode.Kind.Separator) end++
        return end - index
    }

    internal fun contains(node: SpaceSidebarNode, id: String): Boolean =
        node.id == id || (node.kind == SpaceSidebarNode.Kind.Space && node.children.any { contains(it, id) })

    // Operations and diff live in SpaceSidebarOperations.kt and SpaceSidebarDiff.kt.

    fun apply(document: SpaceSidebarDocument, operations: List<SpaceSidebarOperation>, base: SpaceSidebarDocument? = null): SpaceSidebarApplication =
        SpaceSidebarOperations.apply(document, operations, base)

    fun diff(current: SpaceSidebarDocument, proposed: SpaceSidebarDocument, widgets: Boolean = true): List<SpaceSidebarOperation> =
        SpaceSidebarDiff.diff(current, proposed, widgets)
}
