package com.slopus.happy.stemcells

import java.util.UUID

/**
 * Happy reference tags (`<happy-KIND id="…"/>`) an agent writes inline in
 * its Markdown: a tag carries only identity, and the reader shows the live
 * name of what it names. A port of happy-ios `HappyReferences.swift`
 * (`@slopus/happy-agent-client`'s `happyReferences.ts` and the
 * `markdownBlocks.ts` it scans with), over UTF-16 as the reference counts,
 * so they agree on exactly what is a tag: never inside a code span or fenced
 * code, never after a backslash, and a malformed tag is ordinary text.
 */
object HappyReferences {
    data class Reference(
        val kind: String,
        val id: String,
        /** An artifact's version. */
        val version: Int? = null,
        /** A service's workspace. */
        val workspaceId: String? = null,
    )

    sealed class Segment {
        data class Text(val text: String) : Segment()
        /** `source` is the tag exactly as written. */
        data class Ref(val reference: Reference, val source: String) : Segment()
    }

    /** The kinds a reader resolves; any other well-formed kind reads as inert text. */
    val knownKinds = setOf("artifact", "agent", "workspace", "project", "service")

    private const val MAX_TAG_LENGTH = 512
    private const val MAX_VALUE_LENGTH = 256

    /** Splits Markdown into text and references. Still `streaming`, a trailing tag that has only partly arrived is held back. */
    fun parse(markdown: String, streaming: Boolean = false): List<Segment> {
        val (tags, end) = scan(markdown, streaming)
        val segments = ArrayList<Segment>()
        var position = 0
        for (tag in tags) {
            if (tag.start > position) segments += Segment.Text(markdown.substring(position, tag.start))
            segments += Segment.Ref(tag.reference, markdown.substring(tag.start, tag.end))
            position = tag.end
        }
        if (end > position) segments += Segment.Text(markdown.substring(position, end))
        return segments
    }

    /** Every complete tag outside code replaced by `replace`'s text. */
    fun replace(markdown: String, streaming: Boolean = false, replace: (Reference) -> String): String {
        if ('<' !in markdown) return markdown
        return parse(markdown, streaming).joinToString("") {
            when (it) {
                is Segment.Text -> it.text
                is Segment.Ref -> replace(it.reference)
            }
        }
    }

    /** Words for a reference nobody can resolve, such as "an unavailable artifact". */
    fun placeholder(reference: Reference): String =
        if (reference.kind in knownKinds) "an unavailable ${reference.kind}" else "an unavailable reference"

    /** Streaming text without a trailing tag still arriving, held back rather than flashed as raw text. */
    fun droppingPartialTag(text: String): String {
        if ('<' !in text) return text
        val end = scan(text, true).second
        return if (end == text.length) text else text.substring(0, end)
    }

    // Reading

    /**
     * The scheme of the links written for references. Minted once per
     * launch, as desktop's is, so a link an agent wrote by hand never passes
     * for one: only a tag the grammar accepted becomes one.
     */
    val linkPrefix = "happy-reference-${UUID.randomUUID().toString().take(8).lowercase()}:"

    /** `kind/id`, and `/version` when the reference pins one. */
    fun link(reference: Reference): String = "$linkPrefix${reference.kind}/${reference.id}" + (reference.version?.let { "/$it" } ?: "")

    /** The reference a link written by [markdown] points at. */
    fun reference(link: String): Reference? {
        if (!link.startsWith(linkPrefix)) return null
        val parts = link.substring(linkPrefix.length).split("/")
        if ((parts.size != 2 && parts.size != 3) || !isID(parts[1])) return null
        if (parts.size == 3) {
            if (!isVersion(parts[2])) return null
            return Reference(parts[0], parts[1], version = parts[2].toInt())
        }
        return Reference(parts[0], parts[1])
    }

    /**
     * The Markdown a message is drawn from (desktop's
     * `messageReferencesResolve`): a reference that opens is a link with its
     * name, any other its words; a kind this build doesn't know is never
     * asked about.
     */
    fun markdown(text: String, view: (Reference) -> StemCellReferences.View): String = replace(text) { reference ->
        val shown = if (reference.kind in knownKinds) view(reference) else StemCellReferences.View.Text(placeholder(reference))
        val name = label(shown.label).ifEmpty { label(placeholder(reference)) }
        if (shown is StemCellReferences.View.Link) "[$name](${link(reference)})" else name
    }

    /** The same words as plain text, for what is read aloud or copied (`messageReferencesPlainText`). */
    fun plain(text: String, view: (Reference) -> StemCellReferences.View): String = replace(text) { reference ->
        if (reference.kind in knownKinds) view(reference).label else placeholder(reference)
    }

    private val lineBreaks = Regex("(?U)\\s*[\\r\\n]+\\s*")

    /**
     * A name as running words on one line. The chat's Markdown has no
     * escapes, so what would open a span or end the link goes instead.
     */
    internal fun label(text: String): String {
        val oneLine = lineBreaks.replace(text, " ")
        val out = StringBuilder()
        for (character in oneLine) {
            when (character) {
                '[' -> out.append('(')
                ']' -> out.append(')')
                '*', '`' -> {}
                else -> out.append(character)
            }
        }
        return out.toString().trim { it == ' ' || it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }
    }

    // Scan

    private class Tag(val start: Int, val end: Int, val reference: Reference)

    private const val OPEN = "<happy-"

    private fun isLower(c: Char) = c in 'a'..'z'
    private fun isUpper(c: Char) = c in 'A'..'Z'
    private fun isDigit(c: Char) = c in '0'..'9'
    private fun isBlank(c: Char) = c == ' ' || c == '\t'

    /** The complete tags outside code, and where the text ends once a streaming partial is dropped. */
    private fun scan(units: String, streaming: Boolean): Pair<List<Tag>, Int> {
        val tags = ArrayList<Tag>()
        for ((blockStart, blockEnd) in textBlocks(units)) {
            val last = blockEnd == units.length
            var index = blockStart
            while (index < blockEnd) {
                when (units[index]) {
                    '\\' -> index += 2
                    '`' -> index = skipCodeSpan(units, index, blockEnd, streaming && last)
                    '<' -> {
                        val tag = matchTag(units, index)
                        if (tag != null) {
                            tags += tag
                            index = tag.end
                        } else if (streaming && last && isTagPrefix(units.substring(index))) {
                            return tags to index
                        } else {
                            index++
                        }
                    }
                    else -> index++
                }
            }
        }
        return tags to units.length
    }

    /** `^[ \t]*(?:>[ \t]?)*[ \t]*`: where a line's quote markers end. */
    private fun afterQuotes(units: String, start: Int, end: Int): Int {
        var index = start
        while (index < end && isBlank(units[index])) index++
        while (index < end && units[index] == '>') {
            index++
            if (index < end && isBlank(units[index])) index++
        }
        while (index < end && isBlank(units[index])) index++
        return index
    }

    private fun run(units: String, from: Int, end: Int, of: Char): Int {
        var index = from
        while (index < end && units[index] == of) index++
        return index - from
    }

    /**
     * `textBlocks`: the runs that are neither fenced code nor blank lines,
     * each with its line endings. A fence never closed runs to the end.
     */
    internal fun textBlocks(units: String): List<Pair<Int, Int>> {
        val blocks = ArrayList<Pair<Int, Int>>()
        var blockStart: Int? = null
        var fenceMarker: Char? = null
        var fenceLength = 0
        var lineStart = 0
        fun blank(from: Int, to: Int) = (from until to).all { isBlank(units[it]) || units[it] == '\r' }
        while (lineStart < units.length) {
            val newline = units.indexOf('\n', lineStart).takeIf { it >= 0 }
            val lineEnd = newline?.let { it + 1 } ?: units.length
            val end = newline ?: units.length
            val marker = fenceMarker
            if (marker != null) {
                // `^…(`+|~+)[ \t\r]*$`
                val at = afterQuotes(units, lineStart, end)
                if (at < end && units[at] == marker) {
                    val length = run(units, at, end, marker)
                    if (length >= fenceLength && blank(at + length, end)) fenceMarker = null
                }
            } else {
                val at = afterQuotes(units, lineStart, end)
                var opened = false
                if (at < end && (units[at] == '`' || units[at] == '~')) {
                    val opener = units[at]
                    val length = run(units, at, end, opener)
                    val infoStart = at + length
                    // `(.*)$` crosses no line terminator; a backtick fence's info has no backtick.
                    val terminated = (infoStart until end).any { units[it] == '\r' || units[it] == '\u2028' || units[it] == '\u2029' }
                    val backtickInInfo = (infoStart until end).any { units[it] == '`' }
                    if (length >= 3 && !terminated && !(opener == '`' && backtickInInfo)) {
                        blockStart?.let { blocks += it to lineStart }
                        blockStart = null
                        fenceMarker = opener
                        fenceLength = length
                        opened = true
                    }
                }
                if (!opened) {
                    if (blank(lineStart, end)) {
                        blockStart?.let { blocks += it to lineStart }
                        blockStart = null
                    } else if (blockStart == null) {
                        blockStart = lineStart
                    }
                }
            }
            lineStart = lineEnd
        }
        blockStart?.let { blocks += it to units.length }
        return blocks
    }

    /**
     * `skipCodeSpan`: past a code span opening at `start`. Backticks with no
     * closing run of the same length are literal, except at the end of
     * streaming text, where everything after the opening run is code.
     */
    internal fun skipCodeSpan(units: String, start: Int, end: Int, open: Boolean): Int {
        val length = run(units, start, units.length, '`')
        var index = start + length
        while (index < end) {
            val next = units.indexOf('`', index).takeIf { it in 0 until end } ?: break
            val closing = run(units, next, units.length, '`')
            if (closing == length) return next + closing
            index = next + closing
        }
        return if (open) end else start + length
    }

    /** The tag starting at `start`: `<happy-KIND (name="value")+ />`. */
    private fun matchTag(units: String, start: Int): Tag? {
        val count = units.length
        if (!units.startsWith(OPEN, start)) return null
        var index = start + OPEN.length
        // KIND: [a-z][a-z0-9]*(?:-[a-z0-9]+)*
        val kindStart = index
        if (index >= count || !isLower(units[index])) return null
        index++
        while (index < count) {
            if (isLower(units[index]) || isDigit(units[index])) {
                index++
            } else if (units[index] == '-' && index + 1 < count && (isLower(units[index + 1]) || isDigit(units[index + 1]))) {
                index += 2
            } else {
                break
            }
        }
        val kind = units.substring(kindStart, index)
        val attributes = HashMap<String, String>()
        var duplicate = false
        var seen = 0
        while (true) {
            val blanks = index
            while (index < count && isBlank(units[index])) index++
            if (index >= count) return null
            if (units[index] == '/') {
                if (seen == 0 || index + 1 >= count || units[index + 1] != '>') return null
                index += 2
                break
            }
            // Another attribute, after at least one blank.
            if (index <= blanks || !isLower(units[index])) return null
            val nameStart = index
            index++
            while (index < count && (isLower(units[index]) || isDigit(units[index]) || units[index] == '-')) index++
            val name = units.substring(nameStart, index)
            if (index + 1 >= count || units[index] != '=' || units[index + 1] != '"') return null
            index += 2
            val valueStart = index
            while (index < count && units[index] != '"' && units[index] != '<' && units[index] != '>' && units[index] != '\r' && units[index] != '\n') index++
            if (index >= count || units[index] != '"' || index - valueStart > MAX_VALUE_LENGTH) return null
            val value = units.substring(valueStart, index)
            index++
            if (name in attributes) duplicate = true
            attributes[name] = value
            seen++
        }
        if (index - start > MAX_TAG_LENGTH || duplicate) return null
        val id = attributes["id"]?.takeIf(::isID) ?: return null
        var version: Int? = null
        attributes["version"]?.let {
            if (!isVersion(it)) return null
            version = it.toInt()
        }
        val workspaceId = attributes["workspace"]
        if (workspaceId != null && !isID(workspaceId)) return null
        return Tag(start, index, Reference(kind, id, version, workspaceId))
    }

    /** `[A-Za-z0-9_-]{1,128}` */
    private fun isID(value: String): Boolean =
        value.length in 1..128 && value.all { isLower(it) || isUpper(it) || isDigit(it) || it == '_' || it == '-' }

    /** `[1-9][0-9]{0,8}` */
    private fun isVersion(value: String): Boolean = value.length in 1..9 && value[0] != '0' && value.all(::isDigit)

    private enum class State { KindStart, Kind, KindDash, Space, Name, Equals, Value, AfterValue, Slash }

    /**
     * Whether `text`, which starts at a `<`, could still grow into a
     * complete tag. It walks code points, as the reference does.
     */
    private fun isTagPrefix(text: String): Boolean {
        if (text.length >= MAX_TAG_LENGTH) return false
        if (text.length <= OPEN.length) return OPEN.startsWith(text)
        if (!text.startsWith(OPEN)) return false
        var state = State.KindStart
        var attributeSeen = false
        var valueLength = 0
        val rest = text.substring(OPEN.length)
        var offset = 0
        while (offset < rest.length) {
            val scalar = rest.codePointAt(offset)
            offset += Character.charCount(scalar)
            val lower = scalar in 0x61..0x7A
            val digit = scalar in 0x30..0x39
            val blank = scalar == ' '.code || scalar == '\t'.code
            when (state) {
                State.KindStart, State.KindDash -> {
                    if (!(lower || (digit && state == State.KindDash))) return false
                    state = State.Kind
                }
                State.Kind -> when {
                    scalar == '-'.code -> state = State.KindDash
                    blank -> state = State.Space
                    !lower && !digit -> return false
                }
                State.Space -> when {
                    lower -> state = State.Name
                    scalar == '/'.code && attributeSeen -> state = State.Slash
                    !blank -> return false
                }
                State.Name -> when {
                    scalar == '='.code -> state = State.Equals
                    !lower && !digit && scalar != '-'.code -> return false
                }
                State.Equals -> {
                    if (scalar != '"'.code) return false
                    state = State.Value
                    valueLength = 0
                }
                State.Value -> {
                    if (scalar == '"'.code) {
                        attributeSeen = true
                        state = State.AfterValue
                    } else {
                        if (scalar == '<'.code || scalar == '>'.code || scalar == '\r'.code || scalar == '\n'.code) return false
                        valueLength++
                        if (valueLength > MAX_VALUE_LENGTH) return false
                    }
                }
                // A closed value is followed by a blank or `/>`, never directly by a name.
                State.AfterValue -> when {
                    blank -> state = State.Space
                    scalar == '/'.code -> state = State.Slash
                    else -> return false
                }
                State.Slash -> return false
            }
        }
        return true
    }
}
