package com.slopus.happy.stemcells

import com.slopus.happy.stemcells.SpaceSidebar.Location
import com.slopus.happy.stemcells.SpaceSidebar.contains
import com.slopus.happy.stemcells.SpaceSidebar.isValidID
import com.slopus.happy.stemcells.SpaceSidebar.isValidLabel
import com.slopus.happy.stemcells.SpaceSidebar.locate
import com.slopus.happy.stemcells.SpaceSidebar.maxBytes
import com.slopus.happy.stemcells.SpaceSidebar.maxDepth
import com.slopus.happy.stemcells.SpaceSidebar.maxElements
import com.slopus.happy.stemcells.SpaceSidebar.maxWidgetBytes
import com.slopus.happy.stemcells.SpaceSidebar.node
import com.slopus.happy.stemcells.SpaceSidebar.reservedIDs
import com.slopus.happy.stemcells.SpaceSidebar.runLength
import com.slopus.happy.stemcells.SpaceSidebar.siblings
import com.slopus.happy.stemcells.SpaceSidebar.widgetCheck
import com.slopus.happy.stemcells.SpaceSidebar.withNode
import com.slopus.happy.stemcells.SpaceSidebar.withSiblings
import com.slopus.happy.stemcells.SpaceSidebarNode.Kind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * The daemon's sidebar operations, ported from happy-ios
 * `SpaceSidebarOperations.swift`: keyed by node ID, applied in order, with
 * the same refusals and messages.
 */

/**
 * Where an inserted or moved node goes: immediately after a node and
 * everything nested in it, or first under a row or session (`null`: first
 * in the list).
 */
sealed interface SpaceSidebarPosition {
    data class After(val id: String) : SpaceSidebarPosition
    data class Parent(val id: String?) : SpaceSidebarPosition

    fun json(): JsonObject = when (this) {
        is After -> JsonObject(mapOf("after" to JsonPrimitive(id)))
        is Parent -> JsonObject(mapOf("parent" to (id?.let(::JsonPrimitive) ?: JsonNull)))
    }

    companion object {
        fun from(json: JsonElement?): SpaceSidebarPosition? {
            val o = json as? JsonObject ?: return null
            if (o.size != 1) return null
            o.string("after")?.let { return After(it) }
            return when (val parent = o["parent"]) {
                JsonNull -> Parent(null)
                is JsonPrimitive -> if (parent.isString) Parent(parent.content) else null
                else -> null
            }
        }
    }
}

/** A node an `insert` adds. Only the fields its kind has are written. */
data class SpaceSidebarNewNode(
    val kind: Kind,
    val id: String,
    val expanded: Boolean? = null,
    val foldRibbon: SpaceSidebarFoldRibbon? = null,
    val label: String? = null,
    val collapsed: Boolean? = null,
    val html: String? = null,
) {
    companion object {
        fun space(id: String, expanded: Boolean? = null) = SpaceSidebarNewNode(Kind.Space, id, expanded = expanded)
    }
}

/** A `set`'s optional `foldRibbon`: absent (Kotlin null), `null` (back to the default), or a value. */
sealed interface SpaceSidebarRibbonPatch {
    data object Clear : SpaceSidebarRibbonPatch
    data class Value(val ribbon: SpaceSidebarFoldRibbon) : SpaceSidebarRibbonPatch
}

data class SpaceSidebarSet(
    val id: String,
    val label: String? = null,
    val collapsed: Boolean? = null,
    val expanded: Boolean? = null,
    val foldRibbon: SpaceSidebarRibbonPatch? = null,
    val html: String? = null,
) {
    val isEmpty get() = label == null && collapsed == null && expanded == null && foldRibbon == null && html == null
}

/** One change to the sidebar document, keyed by node ID. */
sealed interface SpaceSidebarOperation {
    data class Insert(val node: SpaceSidebarNewNode, val to: SpaceSidebarPosition) : SpaceSidebarOperation
    data class Move(val id: String, val to: SpaceSidebarPosition) : SpaceSidebarOperation
    data class Remove(val id: String) : SpaceSidebarOperation
    data class Set(val fields: SpaceSidebarSet) : SpaceSidebarOperation

    val describe: String
        get() = when (this) {
            is Insert -> "insert ${node.id}"
            is Move -> "move $id"
            is Remove -> "remove $id"
            is Set -> "set ${fields.id}"
        }

    fun json(): JsonObject = when (this) {
        is Insert -> {
            val o = LinkedHashMap<String, JsonElement>()
            o["type"] = JsonPrimitive(node.kind.raw)
            o["id"] = JsonPrimitive(node.id)
            node.expanded?.let { o["expanded"] = JsonPrimitive(it) }
            node.foldRibbon?.let { o["foldRibbon"] = JsonPrimitive(it.raw) }
            node.label?.let { o["label"] = JsonPrimitive(it) }
            node.collapsed?.let { o["collapsed"] = JsonPrimitive(it) }
            node.html?.let { o["html"] = JsonPrimitive(it) }
            JsonObject(mapOf("type" to JsonPrimitive("insert"), "node" to JsonObject(o), "to" to to.json()))
        }
        is Move -> JsonObject(mapOf("type" to JsonPrimitive("move"), "id" to JsonPrimitive(id), "to" to to.json()))
        is Remove -> JsonObject(mapOf("type" to JsonPrimitive("remove"), "id" to JsonPrimitive(id)))
        is Set -> {
            val o = LinkedHashMap<String, JsonElement>()
            o["type"] = JsonPrimitive("set")
            o["id"] = JsonPrimitive(fields.id)
            fields.label?.let { o["label"] = JsonPrimitive(it) }
            fields.collapsed?.let { o["collapsed"] = JsonPrimitive(it) }
            fields.expanded?.let { o["expanded"] = JsonPrimitive(it) }
            when (val ribbon = fields.foldRibbon) {
                SpaceSidebarRibbonPatch.Clear -> o["foldRibbon"] = JsonNull
                is SpaceSidebarRibbonPatch.Value -> o["foldRibbon"] = JsonPrimitive(ribbon.ribbon.raw)
                null -> {}
            }
            fields.html?.let { o["html"] = JsonPrimitive(it) }
            JsonObject(o)
        }
    }

    companion object {
        fun from(json: JsonElement?): SpaceSidebarOperation? {
            val o = json as? JsonObject ?: return null
            return when (o.string("type")) {
                "insert" -> {
                    val node = o["node"] as? JsonObject ?: return null
                    val kind = Kind.of(node.string("type")) ?: return null
                    val id = node.string("id") ?: return null
                    val to = SpaceSidebarPosition.from(o["to"]) ?: return null
                    Insert(
                        SpaceSidebarNewNode(
                            kind, id, expanded = node.bool("expanded"), foldRibbon = SpaceSidebarFoldRibbon.of(node.string("foldRibbon")),
                            label = node.string("label"), collapsed = node.bool("collapsed"), html = node.string("html"),
                        ),
                        to,
                    )
                }
                "move" -> Move(o.string("id") ?: return null, SpaceSidebarPosition.from(o["to"]) ?: return null)
                "remove" -> Remove(o.string("id") ?: return null)
                "set" -> {
                    val id = o.string("id") ?: return null
                    val ribbon = when (val value = o["foldRibbon"]) {
                        JsonNull -> SpaceSidebarRibbonPatch.Clear
                        is JsonPrimitive -> if (value.isString) SpaceSidebarFoldRibbon.of(value.content)?.let(SpaceSidebarRibbonPatch::Value) else null
                        else -> null
                    }
                    Set(SpaceSidebarSet(id, o.string("label"), o.bool("collapsed"), o.bool("expanded"), ribbon, o.string("html")))
                }
                else -> null
            }
        }
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/**
 * The document a batch produced, and the operations that changed it, as
 * they applied: an `after` anchor that fell back is replaced by the
 * position used, and operations that changed nothing are left out.
 */
data class SpaceSidebarApplication(val document: SpaceSidebarDocument, val operations: List<SpaceSidebarOperation>)

internal object SpaceSidebarOperations {
    /**
     * Applies `operations` in order to `document`. With `base` — the version
     * the operations were made against — an `after` anchor that is gone
     * falls back to the nearest node that preceded it among its siblings in
     * `base` and still sits under the same parent, or first under that
     * parent. Throws for the first operation that cannot apply.
     */
    fun apply(document: SpaceSidebarDocument, operations: List<SpaceSidebarOperation>, base: SpaceSidebarDocument?): SpaceSidebarApplication {
        var working = document
        val applied = ArrayList<SpaceSidebarOperation>()
        for ((index, operation) in operations.withIndex()) {
            val (next, done) = applyOperation(working, operation, base, index)
            working = next
            if (done != null) applied += done
        }
        if (JS.utf8Count(SpaceSidebar.format(working)) > maxBytes) {
            var probe = document
            for ((index, operation) in operations.withIndex()) {
                probe = applyOperation(probe, operation, base, index).first
                if (JS.utf8Count(SpaceSidebar.format(probe)) > maxBytes) throw operationError(index, operation, "the sidebar would exceed 128 KiB.")
            }
        }
        return SpaceSidebarApplication(working, applied)
    }

    private fun operationError(index: Int, operation: SpaceSidebarOperation, reason: String) =
        SpaceSidebarError("Operation $index (${operation.describe}) cannot apply: $reason", operationIndex = index)

    /** Applies one operation; returns the document and the operation as applied, or null when it changed nothing. */
    fun applyOperation(document: SpaceSidebarDocument, operation: SpaceSidebarOperation, base: SpaceSidebarDocument?, index: Int): Pair<SpaceSidebarDocument, SpaceSidebarOperation?> {
        if (!isValid(operation)) throw operationError(index, operation, "it is not a valid sidebar operation.")
        try {
            return when (operation) {
                is SpaceSidebarOperation.Insert -> insert(document, operation.node, operation.to, base)
                is SpaceSidebarOperation.Move -> move(document, operation.id, operation.to, base)
                is SpaceSidebarOperation.Remove -> remove(document, operation.id)
                is SpaceSidebarOperation.Set -> set(document, operation.fields)
            }
        } catch (error: SpaceSidebarError) {
            if (error.operationIndex != null) throw error
            throw operationError(index, operation, error.message)
        }
    }

    /** `spaceSidebarOperationSchema`. */
    private fun isValid(operation: SpaceSidebarOperation): Boolean {
        fun position(to: SpaceSidebarPosition) = when (to) {
            is SpaceSidebarPosition.After -> isValidID(to.id)
            is SpaceSidebarPosition.Parent -> to.id?.let(::isValidID) ?: true
        }
        return when (operation) {
            is SpaceSidebarOperation.Insert -> {
                val node = operation.node
                if (!isValidID(node.id) || !position(operation.to)) return false
                when (node.kind) {
                    Kind.Space -> node.label == null && node.collapsed == null && node.html == null
                    Kind.Section -> node.label?.let(::isValidLabel) == true && node.expanded == null && node.foldRibbon == null && node.html == null
                    Kind.Separator -> node.label == null && node.collapsed == null && node.expanded == null && node.foldRibbon == null && node.html == null
                    Kind.Widget -> node.html?.let { it.length <= maxWidgetBytes } == true && node.label == null && node.collapsed == null && node.expanded == null && node.foldRibbon == null
                    Kind.Search, Kind.Create -> false
                }
            }
            is SpaceSidebarOperation.Move -> isValidID(operation.id) && position(operation.to)
            is SpaceSidebarOperation.Remove -> isValidID(operation.id)
            is SpaceSidebarOperation.Set -> {
                val fields = operation.fields
                isValidID(fields.id) && (fields.label?.let(::isValidLabel) ?: true) && (fields.html?.let { it.length <= maxWidgetBytes } ?: true)
            }
        }
    }

    private class Place(val parentPath: List<Int>, val index: Int, val parentId: String, val depth: Int, val position: SpaceSidebarPosition)

    private fun insert(document: SpaceSidebarDocument, node: SpaceSidebarNewNode, to: SpaceSidebarPosition, base: SpaceSidebarDocument?): Pair<SpaceSidebarDocument, SpaceSidebarOperation> {
        if (node.id in reservedIDs) throw SpaceSidebarError("the ID ${node.id} is reserved.")
        if (locate(document, node.id) != null) throw SpaceSidebarError("${node.id} is already in the sidebar.")
        if (node.kind == Kind.Widget) widgetCheck(node.html ?: "")
        val place = resolve(document, to, base)
        standCheck(node.kind, place.parentId)
        if (place.depth > maxDepth) throw SpaceSidebarError("the sidebar would nest deeper than 16.")
        if (elementCount(document) + 1 > maxElements) throw SpaceSidebarError("the sidebar would hold more than 1,000 elements.")
        return withSiblings(document, place.parentPath) { it.add(place.index, nodeOf(node)) } to SpaceSidebarOperation.Insert(node, place.position)
    }

    private fun move(document: SpaceSidebarDocument, id: String, to: SpaceSidebarPosition, base: SpaceSidebarDocument?): Pair<SpaceSidebarDocument, SpaceSidebarOperation?> {
        if (id == "nav") throw SpaceSidebarError("the nav root cannot move.")
        val at = locate(document, id) ?: throw SpaceSidebarError("$id is no longer in the sidebar.")
        val from = at.index
        val all = siblings(document, at.parentPath)
        val moving = all.subList(from, from + runLength(all, from)).toList()
        val target = when (to) {
            is SpaceSidebarPosition.After -> to.id
            is SpaceSidebarPosition.Parent -> to.id
        }
        if (target != null && moving.any { contains(it, target) }) throw SpaceSidebarError("$id cannot move into itself or what it carries.")
        val lifted = withSiblings(document, at.parentPath) { it.subList(from, from + moving.size).clear() }
        val place = resolve(lifted, to, base)
        for (node in moving) {
            standCheck(node.kind, place.parentId)
            if (place.depth + height(node) - 1 > maxDepth) throw SpaceSidebarError("the sidebar would nest deeper than 16.")
        }
        val moved = withSiblings(lifted, place.parentPath) { it.addAll(place.index, moving) }
        if (place.parentId == at.parentId && place.index == from) return moved to null
        return moved to SpaceSidebarOperation.Move(id, place.position)
    }

    private fun remove(document: SpaceSidebarDocument, id: String): Pair<SpaceSidebarDocument, SpaceSidebarOperation?> {
        if (id in reservedIDs) throw SpaceSidebarError("$id cannot be removed.")
        // Already gone: what the operation asked for is true.
        val at = locate(document, id) ?: return document to null
        return withSiblings(document, at.parentPath) { it.removeAt(at.index) } to SpaceSidebarOperation.Remove(id)
    }

    private val allowedFields = mapOf(
        "nav" to listOf("foldRibbon"), "section" to listOf("label", "collapsed"), "space" to listOf("expanded", "foldRibbon"), "widget" to listOf("html"),
    )

    private fun set(document: SpaceSidebarDocument, fields: SpaceSidebarSet): Pair<SpaceSidebarDocument, SpaceSidebarOperation?> {
        if (fields.isEmpty) throw SpaceSidebarError("it changes nothing.")
        val kind: String
        var location: Location? = null
        if (fields.id == "nav") {
            kind = "nav"
        } else {
            val found = locate(document, fields.id) ?: throw SpaceSidebarError("${fields.id} is no longer in the sidebar.")
            location = found
            kind = node(document, found.path).kind.raw
        }
        // Checked in the schema's field order, as `Object.keys` lists them.
        val given = listOf("label" to (fields.label != null), "collapsed" to (fields.collapsed != null), "expanded" to (fields.expanded != null), "foldRibbon" to (fields.foldRibbon != null), "html" to (fields.html != null))
            .filter { it.second }.map { it.first }
        for (field in given) if (field !in allowedFields[kind].orEmpty()) {
            throw SpaceSidebarError("${if (kind == "nav") "the nav" else "a $kind"} has no $field.")
        }
        fields.html?.let { widgetCheck(it) }
        var changed = SpaceSidebarSet(fields.id)
        fun update(ribbon: SpaceSidebarFoldRibbon?): SpaceSidebarFoldRibbon? = when (val patch = fields.foldRibbon) {
            null -> ribbon
            SpaceSidebarRibbonPatch.Clear -> {
                if (ribbon != null) changed = changed.copy(foldRibbon = SpaceSidebarRibbonPatch.Clear)
                null
            }
            is SpaceSidebarRibbonPatch.Value -> {
                if (ribbon != patch.ribbon) changed = changed.copy(foldRibbon = patch)
                patch.ribbon
            }
        }
        val edited = if (location != null) {
            withNode(document, location.path) { node ->
                var next = node
                fields.label?.let { if (next.label != it) { next = next.copy(label = it); changed = changed.copy(label = it) } }
                fields.collapsed?.let { if (next.collapsed != it) { next = next.copy(collapsed = it); changed = changed.copy(collapsed = it) } }
                fields.expanded?.let { if (next.expanded != it) { next = next.copy(expanded = it); changed = changed.copy(expanded = it) } }
                fields.html?.let { if (next.html != it) { next = next.copy(html = it); changed = changed.copy(html = it) } }
                next.copy(foldRibbon = update(next.foldRibbon))
            }
        } else {
            document.copy(foldRibbon = update(document.foldRibbon))
        }
        return edited to if (changed.isEmpty) null else SpaceSidebarOperation.Set(changed)
    }

    private fun resolve(document: SpaceSidebarDocument, to: SpaceSidebarPosition, base: SpaceSidebarDocument?): Place {
        val anchorId = when (to) {
            is SpaceSidebarPosition.Parent -> return first(document, to.id)
            is SpaceSidebarPosition.After -> to.id
        }
        if (anchorId == "nav") throw SpaceSidebarError("nothing stands after the nav root.")
        locate(document, anchorId)?.let { anchor ->
            return Place(anchor.parentPath, anchor.index + 1, anchor.parentId, anchor.depth, SpaceSidebarPosition.After(anchorId))
        }
        val before = base?.let { locate(it, anchorId) } ?: throw SpaceSidebarError("$anchorId is not in the sidebar.")
        // The anchor left: follow the nearest node that preceded it and is still under its parent.
        val previous = siblings(base, before.parentPath)
        for (index in before.index - 1 downTo 0) {
            val candidate = locate(document, previous[index].id)
            if (candidate != null && candidate.parentId == before.parentId) {
                return Place(candidate.parentPath, candidate.index + 1, candidate.parentId, candidate.depth, SpaceSidebarPosition.After(previous[index].id))
            }
        }
        return first(document, if (before.parentId == "nav") null else before.parentId)
    }

    private fun first(document: SpaceSidebarDocument, parent: String?): Place {
        if (parent == null) return Place(emptyList(), 0, "nav", 2, SpaceSidebarPosition.Parent(null))
        val holder = locate(document, parent) ?: throw SpaceSidebarError("$parent is no longer in the sidebar.")
        if (node(document, holder.path).kind != Kind.Space) throw SpaceSidebarError("only a row or a session holds sessions, not $parent.")
        return Place(holder.path, 0, parent, holder.depth + 1, SpaceSidebarPosition.Parent(parent))
    }

    /** Rows and sessions may stand under a row or session; every other kind only in the list. */
    private fun standCheck(kind: Kind, parentId: String) {
        if (parentId != "nav" && kind != Kind.Space) throw SpaceSidebarError("a ${kind.raw} stands only in the top-level list.")
    }

    fun nodeOf(node: SpaceSidebarNewNode): SpaceSidebarNode = when (node.kind) {
        Kind.Space -> SpaceSidebarNode.space(node.id, expanded = node.expanded == true, foldRibbon = node.foldRibbon)
        Kind.Section -> SpaceSidebarNode.section(node.id, node.label ?: "", node.collapsed == true)
        Kind.Separator -> SpaceSidebarNode.separator(node.id)
        Kind.Widget -> SpaceSidebarNode.widget(node.id, node.html ?: "")
        Kind.Search -> SpaceSidebarNode.search
        Kind.Create -> SpaceSidebarNode.create
    }

    private fun height(node: SpaceSidebarNode): Int =
        if (node.kind == Kind.Space) 1 + (node.children.maxOfOrNull(::height) ?: 0) else 1

    private fun elementCount(document: SpaceSidebarDocument): Int {
        fun count(node: SpaceSidebarNode): Int = 1 + if (node.kind == Kind.Space) node.children.sumOf(::count) else 0
        return 1 + document.items.sumOf(::count)
    }
}
