package com.slopus.happy.stemcells

/**
 * HTML read into a document the way a browser reads it, as far as layout
 * HTML needs: the tokenizer's tags, attributes, character references and
 * raw text, and the tree builder's "in body" rules with their repairs —
 * implied end tags, paragraphs closed by blocks, the adoption agency for
 * misnested formatting and its reconstruction. Tables, forms and foreign
 * content are read as ordinary elements. [StemCellMediaTree.parse] walks
 * what this builds the way the reference walks parse5's tree. A port of
 * happy-ios `StemCellMediaHTML`, over code points as it reads scalars.
 */
internal object StemCellMediaHTML {
    class Attribute(val name: String, val value: String)

    class Element(val name: String, val attributes: List<Attribute>) {
        var children: MutableList<Child> = ArrayList()
        var parent: Element? = null

        fun attribute(name: String): String? = attributes.firstOrNull { it.name == name }?.value

        fun append(element: Element) {
            element.detach()
            element.parent = this
            children += Child.Node(element)
        }

        fun append(text: String) {
            val last = children.lastOrNull()
            if (last is Child.Words) last.text.value += text else children += Child.Words(Text(text))
        }

        fun detach() {
            val parent = parent ?: return
            parent.children.removeAll { it is Child.Node && it.element === this }
            this.parent = null
        }
    }

    class Text(var value: String)

    sealed class Child {
        class Node(val element: Element) : Child()
        class Words(val text: Text) : Child()
    }

    // Tokens

    sealed class Token {
        class Words(val text: String) : Token()
        class Start(val name: String, val attributes: List<Attribute>) : Token()
        class End(val name: String) : Token()
    }

    /** Elements whose content is text up to their own end tag. */
    private val rawText = setOf("script", "style", "xmp", "iframe", "noembed", "noframes", "noscript", "textarea", "title", "plaintext")
    /** Of those, the ones whose text still decodes character references. */
    private val escapable = setOf("textarea", "title")

    fun isSpace(c: Int) = c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code || c == 0x0C
    private fun isLetter(c: Int) = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code

    fun asciiLowercase(text: String): String = buildString { for (c in text) append(if (c in 'A'..'Z') c + 32 else c) }

    private fun IntArray.text(from: Int, to: Int): String = buildString { for (i in from until to) appendCodePoint(this@text[i]) }

    fun tokens(html: String): List<Token> {
        // The input stream: CR and CRLF are LF.
        val s = html.replace("\r\n", "\n").replace("\r", "\n").codePoints().toArray()
        val tokens = ArrayList<Token>()
        val text = StringBuilder()
        var i = 0
        fun flush() {
            if (text.isNotEmpty()) tokens += Token.Words(decode(text.toString(), false))
            text.setLength(0)
        }
        fun starts(word: String, index: Int, folding: Boolean = false): Boolean {
            val w = word.codePoints().toArray()
            if (index + w.size > s.size) return false
            for (offset in w.indices) {
                var c = s[index + offset]
                if (folding && c in 'A'.code..'Z'.code) c += 32
                if (c != w[offset]) return false
            }
            return true
        }
        while (i < s.size) {
            if (s[i] != '<'.code || i + 1 >= s.size) { text.appendCodePoint(s[i]); i++; continue }
            val next = s[i + 1]
            if (starts("<!--", i)) {
                flush()
                // `<!-->` and `<!--->` end at once.
                var j = i + 4
                if (starts(">", j)) { i = j + 1; continue }
                if (starts("->", j)) { i = j + 2; continue }
                while (j < s.size && !starts("-->", j) && !starts("--!>", j)) j++
                i = if (j >= s.size) s.size else j + if (starts("-->", j)) 3 else 4
                continue
            }
            if (next == '!'.code || next == '?'.code) {
                // A declaration or processing instruction: a bogus comment.
                flush()
                var j = i + 2
                while (j < s.size && s[j] != '>'.code) j++
                i = j + 1
                continue
            }
            if (next == '/'.code) {
                if (i + 2 >= s.size) { text.appendCodePoint(s[i]); text.appendCodePoint(next); i += 2; continue }
                if (s[i + 2] == '>'.code) { flush(); i += 3; continue }
                if (!isLetter(s[i + 2])) {
                    flush()
                    var j = i + 2
                    while (j < s.size && s[j] != '>'.code) j++
                    i = j + 1
                    continue
                }
                flush()
                val tag = tag(s, i + 2) ?: return tokens
                tokens += Token.End(tag.name)
                i = tag.end
                continue
            }
            if (!isLetter(next)) { text.appendCodePoint(s[i]); i++; continue }
            flush()
            val tag = tag(s, i + 1) ?: return tokens
            tokens += Token.Start(tag.name, tag.attributes)
            i = tag.end
            if (tag.name !in rawText) continue
            // Raw text: everything to `</name` followed by a space, `/` or `>`.
            var j = i
            val body = StringBuilder()
            val nameLength = tag.name.codePointCount(0, tag.name.length)
            while (j < s.size) {
                if (s[j] == '<'.code && tag.name != "plaintext" && starts("</" + tag.name, j, folding = true)) {
                    val after = j + 2 + nameLength
                    if (after >= s.size || isSpace(s[after]) || s[after] == '/'.code || s[after] == '>'.code) break
                }
                body.appendCodePoint(s[j])
                j++
            }
            if (body.isNotEmpty()) {
                val raw = body.toString()
                tokens += Token.Words(if (tag.name in escapable) decode(raw, false) else raw)
            }
            if (j >= s.size) return tokens
            val closing = tag(s, j + 2) ?: return tokens
            tokens += Token.End(closing.name)
            i = closing.end
        }
        flush()
        return tokens
    }

    private class Tag(val name: String, val attributes: List<Attribute>, val end: Int)

    /** A tag's name and attributes from just past its `<` or `</`, and where it ends; null when the input ends inside it. */
    private fun tag(s: IntArray, start: Int): Tag? {
        var j = start
        val name = StringBuilder()
        while (j < s.size && !isSpace(s[j]) && s[j] != '/'.code && s[j] != '>'.code) { name.appendCodePoint(s[j]); j++ }
        val attributes = ArrayList<Attribute>()
        while (j < s.size) {
            val c = s[j]
            if (isSpace(c) || c == '/'.code) { j++; continue }
            if (c == '>'.code) return Tag(asciiLowercase(name.toString()), attributes, j + 1)
            val attribute = StringBuilder().appendCodePoint(c)
            j++
            while (j < s.size && !isSpace(s[j]) && s[j] != '/'.code && s[j] != '>'.code && s[j] != '='.code) { attribute.appendCodePoint(s[j]); j++ }
            while (j < s.size && isSpace(s[j])) j++
            var value = ""
            if (j < s.size && s[j] == '='.code) {
                j++
                while (j < s.size && isSpace(s[j])) j++
                if (j < s.size && (s[j] == '"'.code || s[j] == '\''.code)) {
                    val quote = s[j]
                    var end = j + 1
                    while (end < s.size && s[end] != quote) end++
                    if (end >= s.size) return null
                    value = s.text(j + 1, end)
                    j = end + 1
                } else {
                    val raw = StringBuilder()
                    while (j < s.size && !isSpace(s[j]) && s[j] != '>'.code) { raw.appendCodePoint(s[j]); j++ }
                    value = raw.toString()
                }
            }
            val lowered = asciiLowercase(attribute.toString())
            // The first of a repeated attribute wins.
            if (attributes.none { it.name == lowered }) attributes += Attribute(lowered, decode(value, true))
        }
        return null
    }

    // Character references

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0", "copy" to "©", "reg" to "®",
        "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“",
        "rdquo" to "”", "bull" to "•", "middot" to "·", "times" to "×", "divide" to "÷", "deg" to "°", "plusmn" to "±",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "harr" to "↔", "laquo" to "«", "raquo" to "»", "sect" to "§",
        "para" to "¶", "cent" to "¢", "pound" to "£", "euro" to "€", "yen" to "¥", "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
        "AMP" to "&", "LT" to "<", "GT" to ">", "QUOT" to "\"", "COPY" to "©", "REG" to "®",
    )

    /** The references HTML still reads without their semicolon. */
    private val legacy = listOf(
        "AMP", "COPY", "GT", "LT", "QUOT", "REG", "amp", "cent", "copy", "deg", "divide", "frac12", "frac14", "frac34",
        "gt", "laquo", "lt", "middot", "nbsp", "para", "plusmn", "pound", "quot", "raquo", "reg", "sect", "times", "yen",
    ).sortedByDescending { it.length }

    /** Windows-1252's reading of the C1 range, which numeric references take. */
    private val c1 = mapOf(
        0x80 to 0x20AC, 0x82 to 0x201A, 0x83 to 0x0192, 0x84 to 0x201E, 0x85 to 0x2026, 0x86 to 0x2020, 0x87 to 0x2021, 0x88 to 0x02C6,
        0x89 to 0x2030, 0x8A to 0x0160, 0x8B to 0x2039, 0x8C to 0x0152, 0x8E to 0x017D, 0x91 to 0x2018, 0x92 to 0x2019, 0x93 to 0x201C,
        0x94 to 0x201D, 0x95 to 0x2022, 0x96 to 0x2013, 0x97 to 0x2014, 0x98 to 0x02DC, 0x99 to 0x2122, 0x9A to 0x0161, 0x9B to 0x203A,
        0x9C to 0x0153, 0x9E to 0x017E, 0x9F to 0x0178,
    )

    fun decode(text: String, inAttribute: Boolean): String {
        if ('&' !in text) return text
        val s = text.codePoints().toArray()
        val out = StringBuilder()
        var i = 0
        fun isAlphanumeric(c: Int) = isLetter(c) || c in '0'.code..'9'.code
        fun isHex(c: Int) = c in '0'.code..'9'.code || c in 'a'.code..'f'.code || c in 'A'.code..'F'.code
        while (i < s.size) {
            if (s[i] != '&'.code || i + 1 >= s.size) { out.appendCodePoint(s[i]); i++; continue }
            if (s[i + 1] == '#'.code) {
                var j = i + 2
                val hex = j < s.size && (s[j] == 'x'.code || s[j] == 'X'.code)
                if (hex) j++
                val digits = StringBuilder()
                while (j < s.size && (if (hex) isHex(s[j]) else s[j] in '0'.code..'9'.code)) { digits.appendCodePoint(s[j]); j++ }
                if (digits.isEmpty()) { out.appendCodePoint(s[i]); i++; continue }
                if (j < s.size && s[j] == ';'.code) j++
                var value = if (digits.length > 8) 0xFFFD else digits.toString().toLong(if (hex) 16 else 10).let { if (it > 0x10FFFF) 0x110000 else it.toInt() }
                if (value == 0 || value > 0x10FFFF || value in 0xD800..0xDFFF) value = 0xFFFD
                value = c1[value] ?: value
                out.appendCodePoint(value)
                i = j
                continue
            }
            var j = i + 1
            val name = StringBuilder()
            while (j < s.size && isAlphanumeric(s[j]) && name.length < 32) { name.appendCodePoint(s[j]); j++ }
            val value = named[name.toString()]
            if (j < s.size && s[j] == ';'.code && value != null) {
                out.append(value)
                i = j + 1
                continue
            }
            // The longest legacy name the text starts with.
            val match = legacy.firstOrNull { name.startsWith(it) }
            if (match != null) {
                val after = i + 1 + match.length
                val following = if (after < s.size) s[after] else null
                if (inAttribute && following != null && (following == '='.code || isAlphanumeric(following))) {
                    out.appendCodePoint(s[i]); i++; continue
                }
                out.append(named.getValue(match))
                i = after
                continue
            }
            out.appendCodePoint(s[i])
            i++
        }
        return out.toString()
    }

    // Tree construction

    private val special = setOf(
        "address", "applet", "area", "article", "aside", "base", "basefont", "bgsound", "blockquote", "body", "br", "button",
        "caption", "center", "col", "colgroup", "dd", "details", "dir", "div", "dl", "dt", "embed", "fieldset", "figcaption",
        "figure", "footer", "form", "frame", "frameset", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hgroup", "hr",
        "html", "iframe", "img", "input", "keygen", "li", "link", "listing", "main", "marquee", "menu", "meta", "nav",
        "noembed", "noframes", "noscript", "object", "ol", "p", "param", "plaintext", "pre", "script", "search", "section",
        "select", "source", "style", "summary", "table", "tbody", "td", "template", "textarea", "tfoot", "th", "thead",
        "title", "tr", "track", "ul", "wbr", "xmp",
    )
    private val formatting = setOf("a", "b", "big", "code", "em", "font", "i", "nobr", "s", "small", "strike", "strong", "tt", "u")
    private val closesParagraph = setOf(
        "address", "article", "aside", "blockquote", "center", "details", "dialog", "dir", "div", "dl", "fieldset",
        "figcaption", "figure", "footer", "header", "hgroup", "main", "menu", "nav", "ol", "p", "search", "section",
        "summary", "ul", "pre", "listing", "form", "hr", "xmp", "plaintext",
    )
    private val headings = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val void = setOf("area", "base", "basefont", "bgsound", "br", "col", "embed", "hr", "img", "input", "keygen", "link", "meta", "param", "source", "track", "wbr")
    /** What goes in the head while the body has not begun. */
    private val headContent = setOf("base", "basefont", "bgsound", "link", "meta", "title", "noscript", "noframes", "style", "script", "template")
    private val scoping = setOf("applet", "caption", "html", "table", "td", "th", "marquee", "object", "template")
    private val impliedEnd = setOf("dd", "dt", "li", "optgroup", "option", "p", "rb", "rp", "rt", "rtc")
    private val blockEnds = setOf(
        "address", "article", "aside", "blockquote", "button", "center", "details", "dialog", "dir", "div", "dl",
        "fieldset", "figcaption", "figure", "footer", "header", "hgroup", "listing", "main", "menu", "nav", "ol", "pre",
        "search", "section", "summary", "ul",
    )

    /** The document's body, built from `html`. */
    fun body(html: String): Element {
        val builder = Builder()
        for (token in tokens(html)) builder.take(token)
        return builder.body
    }

    private class Builder {
        val body = Element("body", emptyList())
        val open = arrayListOf(body)
        /** The active formatting elements; null is a marker. */
        val active = ArrayList<Element?>()
        var started = false
        /** A head element's content, left out until it closes. */
        var discardingName: String? = null
        var discardingDepth = 0

        val current: Element get() = open[open.size - 1]

        fun take(token: Token) {
            val skip = discardingName
            if (skip != null) {
                when {
                    token is Token.Start && token.name == skip -> discardingDepth++
                    token is Token.End && token.name == skip -> if (discardingDepth > 1) discardingDepth-- else discardingName = null
                }
                return
            }
            if (!started) {
                when (token) {
                    is Token.Words -> {
                        // Whitespace before the body is not content.
                        val points = token.text.codePoints().toArray()
                        var first = 0
                        while (first < points.size && isSpace(points[first])) first++
                        if (first == points.size) return
                        started = true
                        take(Token.Words(points.text(first, points.size)))
                        return
                    }
                    is Token.End -> if (token.name != "br") return
                    is Token.Start -> {
                        val name = token.name
                        if (name == "html" || name == "head" || name == "body") { if (name == "body") started = true; return }
                        if (name in headContent) {
                            if (name !in void) { discardingName = name; discardingDepth = 1 }
                            return
                        }
                    }
                }
                started = true
            }
            when (token) {
                is Token.Words -> insertText(token.text)
                is Token.Start -> start(if (token.name == "image") "img" else token.name, token.attributes)
                is Token.End -> end(token.name)
            }
        }

        fun insertText(text: String) {
            val kept = text.replace("\u0000", "")
            if (kept.isEmpty()) return
            reconstruct()
            current.append(kept)
        }

        fun insert(name: String, attributes: List<Attribute>): Element {
            val element = Element(name, attributes)
            current.append(element)
            open += element
            return element
        }

        fun start(name: String, attributes: List<Attribute>) {
            when {
                name == "html" || name == "body" || name == "head" || name == "frameset" -> return
                name in headings -> {
                    closeParagraph()
                    if (current.name in headings) open.removeAt(open.size - 1)
                    insert(name, attributes)
                }
                name in closesParagraph -> {
                    closeParagraph()
                    insert(name, attributes)
                    if (name in void) open.removeAt(open.size - 1)
                }
                name == "li" || name == "dd" || name == "dt" -> {
                    val matches = if (name == "li") setOf("li") else setOf("dd", "dt")
                    for (element in open.reversed()) {
                        if (element.name in matches) {
                            generateImpliedEnds(element.name)
                            popThrough(element)
                            break
                        }
                        if (element.name in special && element.name !in listOf("address", "div", "p")) break
                    }
                    closeParagraph()
                    insert(name, attributes)
                }
                name == "a" -> {
                    lastActive("a")?.let { existing ->
                        adopt("a")
                        removeActive(existing)
                        open.removeAll { it === existing }
                    }
                    reconstruct()
                    pushActive(insert(name, attributes))
                }
                name == "nobr" -> {
                    reconstruct()
                    if (inScope("nobr")) {
                        adopt("nobr")
                        reconstruct()
                    }
                    pushActive(insert(name, attributes))
                }
                name in formatting -> {
                    reconstruct()
                    pushActive(insert(name, attributes))
                }
                name == "applet" || name == "marquee" || name == "object" -> {
                    reconstruct()
                    insert(name, attributes)
                    active += null
                }
                // Without a doctype the document is in quirks mode, where a table does not close a paragraph.
                name == "table" -> insert(name, attributes)
                name in void -> {
                    reconstruct()
                    insert(name, attributes)
                    open.removeAt(open.size - 1)
                }
                else -> {
                    reconstruct()
                    insert(name, attributes)
                }
            }
        }

        fun end(name: String) {
            when {
                name == "body" || name == "html" -> return
                name == "br" -> start("br", emptyList())
                name == "p" -> {
                    if (!inScope("p", button = true)) insert("p", emptyList())
                    generateImpliedEnds("p")
                    popThrough("p")
                }
                name == "li" -> {
                    if (!inScope("li", list = true)) return
                    generateImpliedEnds("li")
                    popThrough("li")
                }
                name == "dd" || name == "dt" -> {
                    if (!inScope(name)) return
                    generateImpliedEnds(name)
                    popThrough(name)
                }
                name in headings -> {
                    if (headings.none { inScope(it) }) return
                    generateImpliedEnds(null)
                    while (open.size > 1) {
                        val last = open.removeAt(open.size - 1)
                        if (last.name in headings) break
                    }
                }
                name in blockEnds || name == "form" -> {
                    if (!inScope(name)) return
                    generateImpliedEnds(null)
                    popThrough(name)
                }
                name in formatting -> adopt(name)
                name == "applet" || name == "marquee" || name == "object" -> {
                    if (!inScope(name)) return
                    generateImpliedEnds(null)
                    popThrough(name)
                    clearToMarker()
                }
                else -> otherEnd(name)
            }
        }

        /** "Any other end tag". */
        fun otherEnd(name: String) {
            for (index in open.indices.reversed()) {
                if (index == 0) continue
                val element = open[index]
                if (element.name == name) {
                    generateImpliedEnds(name)
                    while (open.size > index) open.removeAt(open.size - 1)
                    return
                }
                if (element.name in special) return
            }
        }

        fun closeParagraph() {
            if (!inScope("p", button = true)) return
            generateImpliedEnds("p")
            popThrough("p")
        }

        fun generateImpliedEnds(except: String?) {
            while (open.size > 1 && current.name in impliedEnd && current.name != except) open.removeAt(open.size - 1)
        }

        fun popThrough(name: String) {
            while (open.size > 1) {
                val last = open.removeAt(open.size - 1)
                if (last.name == name) return
            }
        }

        fun popThrough(element: Element) {
            while (open.size > 1) {
                if (open.removeAt(open.size - 1) === element) return
            }
        }

        fun inScope(name: String, button: Boolean = false, list: Boolean = false): Boolean {
            for (element in open.reversed()) {
                if (element.name == name) return true
                if (element.name in scoping || element === body) return false
                if (button && element.name == "button") return false
                if (list && (element.name == "ol" || element.name == "ul")) return false
            }
            return false
        }

        fun elementInScope(target: Element): Boolean {
            for (element in open.reversed()) {
                if (element === target) return true
                if (element.name in scoping) return false
            }
            return false
        }

        // Active formatting elements

        fun lastActive(name: String): Element? {
            for (entry in active.reversed()) {
                val element = entry ?: return null
                if (element.name == name) return element
            }
            return null
        }

        fun removeActive(element: Element) {
            active.removeAll { it === element }
        }

        /** Pushes, keeping at most three alike since the last marker. */
        fun pushActive(element: Element) {
            val alike = ArrayList<Int>()
            for (index in active.indices.reversed()) {
                val entry = active[index] ?: break
                if (entry.name == element.name && entry.attributes.size == element.attributes.size &&
                    entry.attributes.all { element.attribute(it.name) == it.value }
                ) {
                    alike += index
                }
            }
            if (alike.size >= 3) active.removeAt(alike.last())
            active += element
        }

        fun clearToMarker() {
            while (active.isNotEmpty()) {
                if (active.removeAt(active.size - 1) == null) return
            }
        }

        fun reconstruct() {
            val element = active.lastOrNull() ?: return
            if (open.any { it === element }) return
            var index = active.size - 1
            while (index > 0) {
                val previous = active[index - 1] ?: break
                if (open.any { it === previous }) break
                index--
            }
            for (position in index until active.size) {
                val entry = active[position] ?: continue
                active[position] = insert(entry.name, entry.attributes)
            }
        }

        /** The adoption agency algorithm, for an end tag of `subject`. */
        fun adopt(subject: String) {
            if (current.name == subject && active.none { it === current }) {
                open.removeAt(open.size - 1)
                return
            }
            repeat(8) {
                val formatting = lastActive(subject) ?: return otherEnd(subject)
                val formattingIndex = open.indexOfFirst { it === formatting }
                if (formattingIndex < 0) {
                    removeActive(formatting)
                    return
                }
                if (!elementInScope(formatting)) return
                val furthestIndex = open.indices.firstOrNull { it > formattingIndex && open[it].name in special }
                if (furthestIndex == null) {
                    while (open.size > formattingIndex) open.removeAt(open.size - 1)
                    removeActive(formatting)
                    return
                }
                val furthest = open[furthestIndex]
                val common = open[formattingIndex - 1]
                var bookmark = active.indexOfFirst { it === formatting }
                var node: Element
                var nodeIndex = furthestIndex
                var lastNode = furthest
                var inner = 0
                while (true) {
                    inner++
                    nodeIndex--
                    node = open[nodeIndex]
                    if (node === formatting) break
                    if (inner > 3) {
                        val position = active.indexOfFirst { it === node }
                        if (position >= 0) {
                            active.removeAt(position)
                            if (position < bookmark) bookmark--
                        }
                    }
                    val position = active.indexOfFirst { it === node }
                    if (position < 0) {
                        open.removeAt(nodeIndex)
                        continue
                    }
                    val replacement = Element(node.name, node.attributes)
                    active[position] = replacement
                    open[nodeIndex] = replacement
                    node = replacement
                    if (lastNode === furthest) bookmark = position + 1
                    node.append(lastNode)
                    lastNode = node
                }
                common.append(lastNode)
                val replacement = Element(formatting.name, formatting.attributes)
                for (child in furthest.children) if (child is Child.Node) child.element.parent = replacement
                replacement.children = furthest.children
                furthest.children = ArrayList()
                furthest.append(replacement)
                val position = active.indexOfFirst { it === formatting }
                if (position >= 0) {
                    active.removeAt(position)
                    if (position < bookmark) bookmark--
                }
                active.add(minOf(bookmark, active.size), replacement)
                open.removeAll { it === formatting }
                val below = open.indexOfFirst { it === furthest }
                open.add(below + 1, replacement)
            }
        }
    }
}
