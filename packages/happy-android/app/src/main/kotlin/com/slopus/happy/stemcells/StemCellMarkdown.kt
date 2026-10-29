package com.slopus.happy.stemcells

/**
 * Agent text as happy-desktop reads it (remark with GFM): a single newline
 * inside a paragraph is a soft break, drawn as a space, and only a blank
 * line starts a new paragraph. The shared markdown parser reads every line
 * as its own block, so soft breaks are joined first. happy-ios
 * `StemCellMarkdown`.
 */
object StemCellMarkdown {
    fun joiningSoftBreaks(markdown: String): String {
        val out = ArrayList<String>()
        var fence: String? = null
        // The previous line is paragraph text a soft break may continue.
        var continues = false
        for (text in markdown.replace("\r\n", "\n").split("\n")) {
            val trimmed = text.trimSpaces()
            val open = fence
            if (open != null) {
                out += text
                if (trimmed.startsWith(open)) fence = null
                continue
            }
            val marker = fenceMarker(trimmed)
            if (marker != null) {
                fence = marker
                out += text
                continues = false
                continue
            }
            if (trimmed.isEmpty()) {
                out += text
                continues = false
                continue
            }
            if (continues && !startsBlock(trimmed) && out.isNotEmpty()) {
                out += out.removeAt(out.size - 1).trimSpaces() + " " + trimmed
            } else {
                out += text
            }
            // A hard break (two trailing spaces or a backslash), a heading, a
            // table row or a rule ends here; anything else may run on.
            val hardBreak = text.endsWith("  ") || text.endsWith("\\")
            continues = !hardBreak && !trimmed.startsWith("#") && !trimmed.startsWith("|") && !isRule(trimmed)
        }
        return out.joinToString("\n")
    }

    internal fun fenceMarker(line: String): String? = when {
        line.startsWith("```") -> "```"
        line.startsWith("~~~") -> "~~~"
        else -> null
    }

    /** A line that opens a block of its own rather than continuing a paragraph. */
    internal fun startsBlock(line: String): Boolean {
        if (line.startsWith("#") || line.startsWith(">") || line.startsWith("|") || isRule(line)) return true
        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) return true
        val digits = line.takeWhile { it in '0'..'9' }
        if (digits.isNotEmpty() && digits.length <= 9) {
            val rest = line.substring(digits.length)
            if (rest.startsWith(". ") || rest.startsWith(") ")) return true
        }
        return false
    }

    internal fun isRule(line: String): Boolean {
        val compact = line.filter { it != ' ' }
        val first = compact.firstOrNull() ?: return false
        if (compact.length < 3 || first !in "-*_") return false
        return compact.all { it == first }
    }

    /** Swift's `.whitespaces`: spaces and tabs, never a line break. */
    private fun String.trimSpaces(): String = trim { it.isWhitespace() && it !in "\n\r\u000B\u000C\u0085\u2028\u2029" }
}
