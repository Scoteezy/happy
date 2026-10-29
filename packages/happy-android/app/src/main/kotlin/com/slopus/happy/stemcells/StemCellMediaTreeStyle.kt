package com.slopus.happy.stemcells

import kotlin.math.abs

private typealias Grammar = (List<StemCellMediaTreeStyle.Component>) -> Boolean

/**
 * The CSS subset a media layout may use (`mediaLayoutStyle.ts` in
 * `@slopus/happy-agent-client`, via happy-ios `StemCellMediaTreeStyle`):
 * every property, and the grammar its value must match. A value is kept
 * only in the normalized spelling the daemon writes: lowercase,
 * single-spaced, a space after each comma and around each slash. Nothing it
 * accepts names a colour, loads a resource or places anything outside the
 * layout.
 */
object StemCellMediaTreeStyle {
    /** The normalized form of one value, or null when it is outside the grammar of `key` (a camelCase key of the subset). */
    fun value(key: String, value: String): String? {
        if (value.codePointCount(0, value.length) > 512) return null
        val grammar = grammars[key] ?: return null
        val text = value.trim().lowercase().split(whitespace).filter { it.isNotEmpty() }.joinToString(" ")
        val components = parse(text) ?: return null
        if (components.isEmpty() || !grammar(components)) return null
        return serialize(components)
    }

    private val whitespace = Regex("(?U)\\s+")

    // Components

    internal sealed class Component {
        data class Word(val text: String) : Component()
        data object Slash : Component()
        data object Comma : Component()
        data class Function(val name: String, val args: List<Component>) : Component()
    }

    private val functions = setOf("repeat", "minmax", "fit-content")
    private const val MAX_PX = 4000.0
    private const val MAX_EM = 100.0
    private const val MAX_FR = 100.0
    private const val MAX_GRID_LINE = 24
    private const val MAX_TRACKS = 24

    private fun parse(value: String): List<Component>? {
        if (!value.all { it in 'a'..'z' || it in '0'..'9' || it in " .,%()/+-" }) return null
        var position = 0
        fun list(depth: Int): List<Component>? {
            val components = ArrayList<Component>()
            while (position < value.length) {
                when (value[position]) {
                    ' ' -> position++
                    '/' -> { components += Component.Slash; position++ }
                    ',' -> { components += Component.Comma; position++ }
                    ')' -> {
                        if (depth <= 0) return null
                        position++
                        return components
                    }
                    '(' -> return null
                    else -> {
                        val word = StringBuilder()
                        while (position < value.length && value[position] !in " /,()") { word.append(value[position]); position++ }
                        if (position < value.length && value[position] == '(') {
                            if (word.toString() !in functions || depth >= 2) return null
                            position++
                            val args = list(depth + 1) ?: return null
                            components += Component.Function(word.toString(), args)
                        } else {
                            components += Component.Word(word.toString())
                        }
                    }
                }
            }
            return if (depth == 0) components else null
        }
        return list(0)
    }

    private fun serialize(components: List<Component>): String {
        val text = StringBuilder()
        for (component in components) {
            if (component is Component.Comma) { text.append(", "); continue }
            if (text.isNotEmpty() && !text.endsWith(", ")) text.append(' ')
            when (component) {
                Component.Slash -> text.append('/')
                is Component.Word -> text.append(component.text)
                is Component.Function -> text.append("${component.name}(${serialize(component.args)})")
                Component.Comma -> {}
            }
        }
        return text.toString()
    }

    private fun words(components: List<Component>): List<String>? = components.map { (it as? Component.Word)?.text ?: return null }

    /** Split at the top level on commas, or on slashes. */
    private fun split(components: List<Component>, slash: Boolean): List<List<Component>> {
        val parts = arrayListOf(ArrayList<Component>())
        for (component in components) {
            if (slash && component is Component.Slash) { parts.add(ArrayList()); continue }
            if (!slash && component is Component.Comma) { parts.add(ArrayList()); continue }
            parts.last() += component
        }
        return parts
    }

    /** `^(\d+(\.\d+)?|\.\d+)$`. */
    private fun number(text: String): Double? {
        val parts = text.split(".")
        if (parts.size > 2 || text.isEmpty()) return null
        fun digits(part: String) = part.isNotEmpty() && part.all { it in '0'..'9' }
        if (parts.size == 1) return if (digits(parts[0])) text.toDouble() else null
        if (!digits(parts[1]) || !(parts[0].isEmpty() || digits(parts[0]))) return null
        return (if (parts[0].isEmpty()) "0$text" else text).toDouble()
    }

    private fun dimension(text: String): Pair<Double, String>? {
        for (unit in listOf("rem", "px", "em", "fr", "%")) {
            if (!text.endsWith(unit)) continue
            val value = number(text.dropLast(unit.length)) ?: return null
            return value to unit
        }
        return null
    }

    /** `0`, or a non-negative px, %, em or rem length within bounds. */
    private fun lengthPercentage(text: String): Boolean {
        if (text == "0") return true
        val (value, unit) = dimension(text) ?: return false
        return when (unit) {
            "px" -> value <= MAX_PX
            "%" -> value <= 100
            "em", "rem" -> value <= MAX_EM
            else -> false
        }
    }

    private fun keyword(vararg allowed: String): Grammar = keywords(allowed.toList())

    private fun keywords(allowed: List<String>): Grammar {
        val set = allowed.toSet()
        return { components ->
            val texts = words(components)
            texts != null && texts.isNotEmpty() && texts.joinToString(" ") in set
        }
    }

    private fun each(most: Int, accept: (String) -> Boolean): Grammar = { components ->
        val texts = words(components)
        texts != null && texts.size in 1..most && texts.all(accept)
    }

    private fun pair(first: Grammar, second: Grammar): Grammar = { components ->
        first(components) || (1 until components.size).any { first(components.subList(0, it)) && second(components.subList(it, components.size)) }
    }

    private fun either(vararg grammars: Grammar): Grammar = { components -> grammars.any { it(components) } }

    private fun fitContent(components: List<Component>): Boolean {
        val only = components.singleOrNull() as? Component.Function ?: return false
        return only.name == "fit-content" && each(1, ::lengthPercentage)(only.args)
    }

    private val size: Grammar = either(keyword("auto", "min-content", "max-content", "fit-content"), each(1, ::lengthPercentage), ::fitContent)
    private val maxSize: Grammar = either(keyword("none"), size)
    private val spacing: Grammar = each(1, ::lengthPercentage)
    private val margin: Grammar = each(1) { it == "auto" || lengthPercentage(it) }

    private val contentPosition = listOf("normal", "start", "end", "center", "flex-start", "flex-end")
    private val distribution = listOf("space-between", "space-around", "space-evenly", "stretch")
    private val baseline = listOf("baseline", "first baseline", "last baseline")
    private val selfPosition = listOf("self-start", "self-end")

    private val justifyContent = keywords(contentPosition + distribution + listOf("left", "right"))
    private val alignContent = keywords(contentPosition + distribution + baseline)
    private val justifyItems = keywords(listOf("normal", "stretch", "start", "end", "center", "flex-start", "flex-end", "left", "right") + selfPosition + baseline)
    private val alignItems = keywords(listOf("normal", "stretch", "start", "end", "center", "flex-start", "flex-end") + selfPosition + baseline)
    private val justifySelf = either(keyword("auto"), justifyItems)
    private val alignSelf = either(keyword("auto"), alignItems)

    private fun trackBreadth(text: String, flexible: Boolean): Boolean {
        if (text in listOf("auto", "min-content", "max-content")) return true
        if (flexible) {
            val found = dimension(text)
            if (found != null && found.second == "fr") return found.first > 0 && found.first <= MAX_FR
        }
        return lengthPercentage(text)
    }

    /** One track: how many tracks it is, or null when invalid. */
    private fun trackSize(component: Component): Int? = when {
        component is Component.Word -> if (trackBreadth(component.text, true)) 1 else null
        component is Component.Function && component.name == "fit-content" -> if (fitContent(listOf(component))) 1 else null
        component is Component.Function && component.name == "minmax" -> {
            val parts = split(component.args, false).map(::words)
            val min = parts.getOrNull(0)
            val max = parts.getOrNull(1)
            if (parts.size == 2 && min != null && max != null && min.size == 1 && max.size == 1 &&
                trackBreadth(min[0], false) && trackBreadth(max[0], true)
            ) 1 else null
        }
        else -> null
    }

    private val trackList: Grammar = grammar@{ components ->
        if (keyword("none")(components)) return@grammar true
        if (components.isEmpty()) return@grammar false
        var tracks = 0
        for (component in components) {
            if (component is Component.Function && component.name == "repeat") {
                val parts = split(component.args, false)
                val count = if (parts.size == 2) words(parts[0]) else null
                if (count == null || count.size != 1) return@grammar false
                val repeats = if (count[0] == "auto-fill" || count[0] == "auto-fit") 1 else if (count[0].all { it in '0'..'9' }) count[0].toIntOrNull() ?: 0 else 0
                if (repeats < 1 || repeats > MAX_TRACKS || parts[1].isEmpty()) return@grammar false
                for (track in parts[1]) {
                    val counted = trackSize(track) ?: return@grammar false
                    tracks += counted * repeats
                }
            } else {
                val counted = trackSize(component) ?: return@grammar false
                tracks += counted
            }
            if (tracks > MAX_TRACKS) return@grammar false
        }
        true
    }

    private val autoTracks: Grammar = { components -> components.size in 1..MAX_TRACKS && components.all { trackSize(it) != null } }

    private fun integer(text: String): Int? {
        val digits = text.removePrefix("-")
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        return text.toIntOrNull()
    }

    private val gridLine: Grammar = grammar@{ components ->
        val texts = words(components) ?: return@grammar false
        if (texts.size == 1) {
            if (texts[0] == "auto") return@grammar true
            val line = integer(texts[0]) ?: return@grammar false
            return@grammar line != 0 && abs(line) <= MAX_GRID_LINE
        }
        if (texts.size == 2 && texts[0] == "span" && !texts[1].startsWith("-")) {
            val span = integer(texts[1]) ?: return@grammar false
            return@grammar span in 1..MAX_GRID_LINE
        }
        false
    }

    private val gridLines: Grammar = { components ->
        val parts = split(components, true)
        parts.size <= 2 && parts.all(gridLine)
    }

    private val ratio: Grammar = grammar@{ components ->
        val parts = split(components, true)
        if (parts.size > 2) return@grammar false
        val values = parts.map { part -> words(part)?.singleOrNull()?.let(::number) }
        if (!values.all { it != null && it > 0 && it <= 10_000 }) return@grammar false
        val shape = values[0]!! / (if (values.size > 1) values[1]!! else 1.0)
        shape in 0.1..10.0
    }

    private val aspectRatio: Grammar = { components ->
        when {
            keyword("auto")(components) -> true
            components.firstOrNull() == Component.Word("auto") -> ratio(components.drop(1))
            components.lastOrNull() == Component.Word("auto") -> ratio(components.dropLast(1))
            else -> ratio(components)
        }
    }

    private fun flexFactor(text: String): Boolean = number(text)?.let { it <= 100 } ?: false

    private val flexBasis: Grammar = either(keyword("content"), size)

    private val flex: Grammar = grammar@{ components ->
        if (keyword("none", "auto")(components)) return@grammar true
        val texts = words(components)
        if (texts != null && texts.size in 1..3 && flexFactor(texts[0])) {
            if (texts.size == 1) return@grammar true
            if (texts.size == 2) return@grammar flexFactor(texts[1]) || flexBasis(components.drop(1))
            return@grammar flexFactor(texts[1]) && flexBasis(components.drop(2))
        }
        flexBasis(components)
    }

    private val objectPosition: Grammar = each(2) { it in listOf("left", "center", "right", "top", "bottom") || lengthPercentage(it) }

    private val lineHeight: Grammar = either(keyword("normal"), { components ->
        val texts = words(components)
        if (texts == null || texts.size != 1) false else number(texts[0])?.let { it <= 10 } ?: lengthPercentage(texts[0])
    })

    private val fontWeight: Grammar = either(keyword("normal", "bold", "bolder", "lighter"), { components ->
        val texts = words(components)
        val weight = texts?.singleOrNull()?.takeIf { text -> text.all { it in '0'..'9' } }?.toIntOrNull()
        weight != null && weight in 1..1000
    })

    private val borderRadius: Grammar = each(4) { text ->
        if (text == "0") true else dimension(text)?.let { it.second == "px" && it.first <= 999 } ?: false
    }

    private val overflow = keyword("visible", "hidden", "clip")
    private val direction = keyword("row", "row-reverse", "column", "column-reverse", "nowrap", "wrap", "wrap-reverse")

    private val grammars: Map<String, Grammar> = mapOf(
        "display" to keyword("block", "inline", "inline-block", "flex", "inline-flex", "grid", "inline-grid", "none"),
        "flexDirection" to keyword("row", "row-reverse", "column", "column-reverse"),
        "flexWrap" to keyword("nowrap", "wrap", "wrap-reverse"),
        "flexFlow" to pair(direction, keyword("nowrap", "wrap", "wrap-reverse")),
        "flex" to flex,
        "flexGrow" to each(1, ::flexFactor),
        "flexShrink" to each(1, ::flexFactor),
        "flexBasis" to flexBasis,
        "order" to each(1) { text -> integer(text)?.let { abs(it) <= 100 } ?: false },
        "justifyContent" to justifyContent,
        "alignContent" to alignContent,
        "placeContent" to pair(alignContent, justifyContent),
        "justifyItems" to justifyItems,
        "alignItems" to alignItems,
        "placeItems" to pair(alignItems, justifyItems),
        "justifySelf" to justifySelf,
        "alignSelf" to alignSelf,
        "placeSelf" to pair(alignSelf, justifySelf),
        "gap" to each(2) { it == "normal" || lengthPercentage(it) },
        "rowGap" to each(1) { it == "normal" || lengthPercentage(it) },
        "columnGap" to each(1) { it == "normal" || lengthPercentage(it) },
        "gridTemplateColumns" to trackList,
        "gridTemplateRows" to trackList,
        "gridAutoColumns" to autoTracks,
        "gridAutoRows" to autoTracks,
        "gridAutoFlow" to keyword("row", "column", "dense", "row dense", "column dense", "dense row", "dense column"),
        "gridColumn" to gridLines,
        "gridRow" to gridLines,
        "gridColumnStart" to gridLine,
        "gridColumnEnd" to gridLine,
        "gridRowStart" to gridLine,
        "gridRowEnd" to gridLine,
        "width" to size,
        "height" to size,
        "minWidth" to size,
        "minHeight" to size,
        "maxWidth" to maxSize,
        "maxHeight" to maxSize,
        "aspectRatio" to aspectRatio,
        "boxSizing" to keyword("content-box", "border-box"),
        "padding" to each(4, ::lengthPercentage),
        "paddingTop" to spacing,
        "paddingRight" to spacing,
        "paddingBottom" to spacing,
        "paddingLeft" to spacing,
        "margin" to each(4) { it == "auto" || lengthPercentage(it) },
        "marginTop" to margin,
        "marginRight" to margin,
        "marginBottom" to margin,
        "marginLeft" to margin,
        "borderRadius" to borderRadius,
        "overflow" to pair(overflow, overflow),
        "overflowX" to overflow,
        "overflowY" to overflow,
        "objectFit" to keyword("fill", "contain", "cover", "none", "scale-down"),
        "objectPosition" to objectPosition,
        "verticalAlign" to keyword("baseline", "top", "middle", "bottom", "text-top", "text-bottom", "sub", "super"),
        "textAlign" to keyword("left", "right", "center", "justify", "start", "end"),
        "fontWeight" to fontWeight,
        "fontStyle" to keyword("normal", "italic", "oblique"),
        "fontSize" to keyword("small", "normal", "large", "title"),
        "lineHeight" to lineHeight,
        "tone" to keyword("default", "muted", "accent"),
    )

    /** CSS property name to the key it is carried under. */
    val keys: Map<String, String> = grammars.keys.associateBy { key -> buildString { for (c in key) if (c.isUpperCase()) append('-').append(c.lowercaseChar()) else append(c) } }
}
