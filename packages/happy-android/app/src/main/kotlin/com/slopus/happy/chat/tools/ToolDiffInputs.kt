package com.slopus.happy.chat.tools

import com.slopus.happy.chat.arr
import com.slopus.happy.chat.num
import com.slopus.happy.chat.obj
import com.slopus.happy.chat.prettyJson
import com.slopus.happy.chat.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// Ported from happy-ios HappyCore/ChatDisplay/ToolDiffInputs.swift.

/**
 * What one DiffChunk in a tool view compares: the text before and after, and
 * the file it names (for its language).
 */
data class ToolDiffChunk(val oldText: String, val newText: String, /** Null reads as "file.txt" (ToolDiffView). */ val fileName: String?)

/**
 * The diffs EditView, WriteView, MultiEditView and GeminiEditView draw, read
 * from the tool's input the way their zod schemas read it: an input that
 * doesn't parse still draws, from empty text.
 */
object ToolDiffInputs {
    fun chunks(kind: ToolViewKind, input: JsonElement?): List<ToolDiffChunk> = when (kind) {
        ToolViewKind.Edit -> listOf(edit(input))
        ToolViewKind.Write -> listOf(write(input))
        ToolViewKind.MultiEdit -> multiEdit(input)
        ToolViewKind.GeminiEdit -> listOf(geminiEdit(input))
        else -> emptyList()
    }

    /** knownTools.Edit.input: file_path, old_string and new_string strings, replace_all a boolean when present. */
    fun edit(input: JsonElement?): ToolDiffChunk {
        val o = input.obj
        val path = o?.get("file_path").str
        val old = o?.get("old_string").str
        val new = o?.get("new_string").str
        if (path == null || old == null || new == null || !optionalBool(o?.get("replace_all"))) return ToolDiffChunk("", "", null)
        return ToolDiffChunk(old, new, path)
    }

    /** knownTools.Write.input; "<no contents>" when it doesn't parse. */
    fun write(input: JsonElement?): ToolDiffChunk {
        val path = input.obj?.get("file_path").str
        val content = input.obj?.get("content").str
        if (path == null || content == null) return ToolDiffChunk("", "<no contents>", null)
        return ToolDiffChunk("", content, path)
    }

    /** knownTools.MultiEdit.input: one diff per edit; nothing when any edit doesn't parse. */
    fun multiEdit(input: JsonElement?): List<ToolDiffChunk> {
        val path = input.obj?.get("file_path").str ?: return emptyList()
        val edits = input.obj?.get("edits").arr ?: return emptyList()
        return edits.map { edit ->
            val o = edit.obj ?: return emptyList()
            val old = o["old_string"].str ?: return emptyList()
            val new = o["new_string"].str ?: return emptyList()
            if (!optionalBool(o["replace_all"])) return emptyList()
            ToolDiffChunk(old, new, path)
        }
    }

    /** GeminiEditView's `extractEditContent`: `toolCall.content[0]`, then `input[0]`, then the input's own fields. */
    fun geminiEdit(input: JsonElement?): ToolDiffChunk {
        // `value || ''`: anything falsy reads as empty.
        fun text(value: JsonElement?): String {
            if (!truthy(value)) return ""
            return value.str ?: prettyJson(value!!)
        }
        fun chunk(old: JsonElement?, new: JsonElement?, path: JsonElement?): ToolDiffChunk {
            val name = text(path)
            return ToolDiffChunk(text(old), text(new), name.ifEmpty { null })
        }
        val o = input.obj
        o?.get("toolCall").obj?.get("content").arr?.firstOrNull()?.takeIf(::truthy)?.let { content ->
            return chunk(content.obj?.get("oldText"), content.obj?.get("newText"), content.obj?.get("path"))
        }
        o?.get("input").arr?.firstOrNull()?.takeIf(::truthy)?.let { content ->
            return chunk(content.obj?.get("oldText"), content.obj?.get("newText"), content.obj?.get("path"))
        }
        fun either(first: String, second: String): JsonElement? = o?.get(first)?.takeIf(::truthy) ?: o?.get(second)
        return chunk(either("oldText", "old_string"), either("newText", "new_string"), either("path", "file_path"))
    }

    /** An optional zod boolean: absent, or a boolean. */
    private fun optionalBool(value: JsonElement?): Boolean =
        value == null || (value is JsonPrimitive && !value.isString && value.booleanOrNull != null)

    /** JavaScript truthiness. */
    private fun truthy(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonPrimitive -> when {
            value.isString -> value.content.isNotEmpty()
            value.booleanOrNull != null -> value.booleanOrNull!!
            else -> value.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: false
        }
        is JsonObject, is JsonArray -> true
    }
}

/**
 * FileView's input: the picture a person attached, read the way its zod
 * schema reads it. Null when it doesn't parse, and FileView draws nothing.
 */
data class ToolFileInput(
    /** The encrypted blob, under the session's attachments. */
    val ref: String,
    val name: String,
    /** The picture's size, when the sender reported it. */
    val imageWidth: Double? = null,
    val imageHeight: Double? = null,
    /** Shown until the picture itself arrives. */
    val thumbhash: String? = null,
) {
    companion object {
        fun of(input: JsonElement?): ToolFileInput? {
            val o = input.obj ?: return null
            val ref = o["ref"].str ?: return null
            val name = o["name"].str ?: return null
            if (o.containsKey("size") && o["size"].num == null) return null
            if (!o.containsKey("image")) return ToolFileInput(ref, name)
            // image: { width: number, height: number, thumbhash?: string }
            val image = o["image"].obj ?: return null
            val width = image["width"].num ?: return null
            val height = image["height"].num ?: return null
            val thumbhash = if (image.containsKey("thumbhash")) image["thumbhash"].str ?: return null else null
            return ToolFileInput(ref, name, width, height, thumbhash)
        }
    }
}
