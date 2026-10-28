package com.slopus.happy.icons

import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.VectorPath
import kotlin.math.min

// Ported from happy-ios HappyIcons/FileIcons.swift. The tables are happy-ios's
// GeneratedFileIcons.swift, copied as file-icons.txt by
// tools/icons/extract-ios-tables.py.

/**
 * A Seti theme colour name. components/FileIcon.tsx maps each to a light
 * and a dark colour (`FileIconPalette`).
 */
enum class SetiColor(val rawValue: String) {
    Blue("blue"), Grey("grey"), GreyLight("grey-light"), Green("green"), Orange("orange"), Pink("pink"),
    Purple("purple"), Red("red"), White("white"), Yellow("yellow"), Ignore("ignore");

    companion object {
        fun of(rawValue: String) = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** One filled shape of a Seti icon, in its view box. */
data class FileIconShape(
    val path: VectorPath,
    val evenOdd: Boolean,
    /** The shape's own `opacity`, applied to the fill colour. */
    val opacity: Double,
)

/** A Seti icon and the colour a file name gives it. */
data class FileIconGraphic(val name: String, val color: SetiColor, val viewBox: Rect, val shapes: List<FileIconShape>) {
    /**
     * The shapes fitted into [rect] as `<SvgXml width height>` draws them:
     * the view box scaled to fit, centred (`xMidYMid meet`).
     */
    fun fitted(rect: Rect): List<FileIconShape> {
        if (viewBox.width <= 0 || viewBox.height <= 0) return emptyList()
        val scale = min(rect.width / viewBox.width, rect.height / viewBox.height)
        val offset = Point(
            rect.minX + (rect.width - viewBox.width * scale) / 2 - viewBox.minX * scale,
            rect.minY + (rect.height - viewBox.height * scale) / 2 - viewBox.minY * scale,
        )
        return shapes.map { it.copy(path = it.path.applying(scale, offset)) }
    }
}

/**
 * components/FileIcon.tsx: @peoplesgrocers/seti-ui-file-icons' icon for a
 * file name.
 */
object FileIcons {
    const val SOURCE_VERSION = "1.11.3"

    data class Entry(val icon: String, val color: SetiColor)

    /** The package's `getIcon(fileName)`: the icon's name and colour. */
    fun lookup(fileName: String): Entry = details(fileName)

    /** The icon for a file name; null only if the tables are broken. */
    fun icon(fileName: String): FileIconGraphic? {
        val entry = details(fileName)
        val outline = outlines[entry.icon] ?: return null
        return FileIconGraphic(entry.icon, entry.color, outline.viewBox, outline.shapes)
    }

    /** Every icon's name, for tests. */
    val allIcons: List<String> get() = outlines.keys.sorted()

    /**
     * `getDetails`, step by step, with JavaScript's string semantics: keys
     * match code unit for code unit, and the extension loop starts at the
     * first dot and moves past one dot at a time.
     */
    internal fun details(fileName: String): Entry {
        tables.files[fileName]?.let { return it }
        var ext = slice(fileName, fileName.indexOf('.'))
        while (ext.isNotEmpty()) {
            tables.extensions[ext]?.let { return it }
            ext = ext.substring(1)
            ext = slice(ext, ext.indexOf('.'))
        }
        for ((partial, entry) in tables.partials) if (fileName.contains(partial)) return entry
        return DEFAULT
    }

    private val DEFAULT = Entry("default", SetiColor.White)

    /** `String.prototype.slice(start)`: a negative start counts from the end. */
    private fun slice(s: String, start: Int): String {
        val begin = if (start < 0) maxOf(0, s.length + start) else min(start, s.length)
        return s.substring(begin)
    }

    internal class Outline(val viewBox: Rect, val shapes: List<FileIconShape>)

    private class Tables(
        val icons: List<String>,
        val files: Map<String, Entry>,
        val extensions: Map<String, Entry>,
        val partials: List<Pair<String, Entry>>,
    )

    private val tables: Tables by lazy {
        val sections = HashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        FileIcons::class.java.getResourceAsStream("/com/slopus/happy/icons/file-icons.txt")!!.bufferedReader().forEachLine { line ->
            when {
                line.startsWith("@table ") -> current = sections.getOrPut(line.removePrefix("@table ")) { mutableListOf() }
                line.isNotEmpty() -> current?.add(line)
            }
        }
        fun table(name: String) = sections[name].orEmpty().mapNotNull { line ->
            val parts = line.split(' ')
            val color = parts.getOrNull(2)?.let(SetiColor::of)
            if (parts.size == 3 && color != null) parts[0] to Entry(parts[1], color) else null
        }
        fun keyed(name: String) = LinkedHashMap<String, Entry>().apply { for ((k, v) in table(name)) putIfAbsent(k, v) }
        Tables(sections["icons"].orEmpty(), keyed("files"), keyed("extensions"), table("partials"))
    }

    internal val iconLines: List<String> get() = tables.icons

    /** Parsed once, on first use: 151 icons. */
    internal val outlines: Map<String, Outline> by lazy {
        val result = HashMap<String, Outline>()
        for (line in tables.icons) {
            val parts = line.split(' ', limit = 3)
            if (parts.size != 3) continue
            val box = parts[1].split(',').mapNotNull { it.toDoubleOrNull() }
            if (box.size != 4) continue
            val shapes = parts[2].split(';').mapNotNull { shape ->
                val fields = shape.split(',', limit = 3)
                val opacity = fields.getOrNull(1)?.toDoubleOrNull()
                if (fields.size != 3 || opacity == null) return@mapNotNull null
                val path = runCatching { VectorPath.svg(fields[2]) }.getOrNull() ?: return@mapNotNull null
                FileIconShape(path, fields[0] == "e", opacity)
            }
            result[parts[0]] = Outline(Rect(box[0], box[1], box[2], box[3]), shapes)
        }
        result
    }
}
