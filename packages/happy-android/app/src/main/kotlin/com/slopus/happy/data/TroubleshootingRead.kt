package com.slopus.happy.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.slopus.happy.net.HappyJson
import com.slopus.happy.troubleshoot.TroubleshootSession
import kotlinx.serialization.json.*
import java.util.WeakHashMap

private val jsonSupport = WeakHashMap<SQLiteDatabase, Boolean>()

/** Older Android framework SQLite builds can omit JSON1; never require a schema migration for this UI. */
private fun supportsJsonProjection(db: SQLiteDatabase): Boolean = synchronized(jsonSupport) {
    jsonSupport.getOrPut(db) {
        try {
            db.rawQuery("SELECT json_extract('{}', '$.x'), json_type('{}', '$.x')", null).use { it.moveToFirst() }
            true
        } catch (error: SQLiteException) {
            if (!error.message.orEmpty().contains("no such function", ignoreCase = true)) throw error
            false
        }
    }
}

private fun Cursor.optionalString(index: Int): String? = if (isNull(index)) null else getString(index)
private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.strictTrue() = this == JsonPrimitive(true)
private fun document(text: String?): JsonObject? = text?.let(HappyJson::parseToJsonElement) as? JsonObject
private fun fields(text: String?, count: Int, optional: Boolean = false): JsonArray {
    if (text == null && optional) return JsonArray(List(count) { JsonNull })
    val array = text?.let(HappyJson::parseToJsonElement) as? JsonArray
    check(array != null && array.size == count) { "Invalid troubleshooting projection" }
    return array
}

/** Caller owns a read transaction. [narrow] also lets compatibility tests exercise both SQLite paths. */
internal fun readTroubleshootingSnapshot(db: SQLiteDatabase, narrow: Boolean = supportsJsonProjection(db)): TroubleshootingSnapshot {
    val projection = if (narrow) """
        json_extract(metadata, '$.displayName', '$.host', '$.siblingMachineId',
            '$.machineKind', '$.rigOnly', '$.client.id', '$.cliAvailability.rig', '$.happyHomeDir')
    """.trimIndent() else "metadata"
    val machines = db.rawQuery("SELECT id, created_at, active, active_at, $projection FROM machines ORDER BY created_at DESC, id", null).use { c ->
        buildList {
            while (c.moveToNext()) {
                val values = if (narrow) fields(c.optionalString(4), 8, optional = true) else {
                    val metadata = document(c.optionalString(4))
                    JsonArray(listOf(metadata?.get("displayName"), metadata?.get("host"), metadata?.get("siblingMachineId"),
                        metadata?.get("machineKind"), metadata?.get("rigOnly"),
                        (metadata?.get("client") as? JsonObject)?.get("id"),
                        (metadata?.get("cliAvailability") as? JsonObject)?.get("rig"), metadata?.get("happyHomeDir"))
                        .map { it ?: JsonNull })
                }
                add(TroubleshootingMachine(c.getString(0), c.getLong(1), c.getInt(2) != 0, c.getLong(3),
                    values[0].string(), values[1].string(), values[2].string(),
                    values[3].string() == "rig" || values[4].strictTrue() || values[5].string() == "rig" || values[6].strictTrue(),
                    values[7].string()))
            }
        }
    }
    val recent = if (narrow) narrowRecent(db) else compatibleRecent(db, machines.mapTo(HashSet()) { it.id })
    return TroubleshootingSnapshot(machines, recent)
}

private fun narrowRecent(db: SQLiteDatabase): TroubleshootSession? = db.rawQuery("""
    SELECT s.updated_at,
           json_extract(s.metadata, '$.machineId', '$.project.name', '$.path', '$.happyHomeDir')
    FROM sessions s JOIN machines m ON m.id = json_extract(s.metadata, '$.machineId')
    WHERE json_type(s.metadata, '$.isSideChat') IS NOT 'true'
          AND json_type(s.metadata, '$.machineId') = 'text'
    ORDER BY s.updated_at DESC, s.active DESC,
             coalesce(CASE WHEN json_type(s.metadata, '$.lastMeaningfulMessageAt') IN ('integer', 'real')
                           THEN json_extract(s.metadata, '$.lastMeaningfulMessageAt') END,
                      s.last_message_sent_at, s.created_at) DESC, s.id
    LIMIT 1
""".trimIndent(), null).use { c ->
    if (!c.moveToFirst()) null else {
        val values = fields(c.optionalString(1), 4)
        TroubleshootSession(c.getLong(0), values[0].string(), values[1].string(), values[2].string(), values[3].string())
    }
}

/**
 * JSON1-free Android fallback. Stream metadata only until the newest matching
 * update/activity bucket is exhausted; never build the full session catalog.
 * Ciphertexts, daemon state, messages and outbox rows are never fetched.
 */
private fun compatibleRecent(db: SQLiteDatabase, known: Set<String>): TroubleshootSession? {
    if (known.isEmpty()) return null
    var best: TroubleshootSession? = null
    var bestActive = false
    var bestMeaningful = Double.NEGATIVE_INFINITY
    db.rawQuery("""
        SELECT id, updated_at, active, created_at, last_message_sent_at, metadata
        FROM sessions ORDER BY updated_at DESC, active DESC, id
    """.trimIndent(), null).use { c ->
        while (c.moveToNext()) {
            val updated = c.getLong(1)
            val active = c.getInt(2) != 0
            val current = best
            if (current != null && (updated < current.updatedAt || (updated == current.updatedAt && bestActive && !active))) break
            val metadata = document(c.optionalString(5)) ?: continue
            if (metadata["isSideChat"].strictTrue()) continue
            val machineId = metadata["machineId"].string()?.takeIf { it in known } ?: continue
            val meaningful = (metadata["lastMeaningfulMessageAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                ?: if (!c.isNull(4)) c.getLong(4).toDouble() else c.getLong(3).toDouble()
            // IDs are already ascending: keep the first when every ranking field ties.
            if (current != null && meaningful <= bestMeaningful) continue
            best = TroubleshootSession(updated, machineId, (metadata["project"] as? JsonObject)?.get("name").string(),
                metadata["path"].string(), metadata["happyHomeDir"].string())
            bestActive = active
            bestMeaningful = meaningful
        }
    }
    return best
}