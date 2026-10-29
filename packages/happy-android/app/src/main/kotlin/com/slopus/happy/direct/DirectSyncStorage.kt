package com.slopus.happy.direct

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.slopus.happy.stemcells.DirectQueuedMessage
import com.slopus.happy.stemcells.OutgoingImage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.slopus.happy.stemcells.AgentMode
import java.io.File

/** The daemon's setting object, null tombstones and its monotonic version included (happy-ios `DirectUserSetting`). */
data class DirectUserSetting(val key: String, val value: JsonElement, val updatedAt: Double? = null, val version: String? = null) {
    companion object {
        fun from(json: JsonElement): DirectUserSetting? = runCatching {
            val o = Wire.obj(json)
            DirectUserSetting(Wire.string(o, "key"), o["value"] ?: JsonNull, Wire.doubleOrNull(o, "updatedAt"), Wire.stringOrNull(o, "version"))
        }.getOrNull()
    }
}

/**
 * A direct account's durable state, in framework SQLite (happy-ios
 * `DirectSyncStorage`, GRDB there). It never holds relay credentials or
 * records. A snapshot and its cursor commit together, so a process never
 * resumes beyond what reached the disk. `file` null is in memory. Calls
 * are blocking; callers run them off the main thread.
 */
class DirectSyncStorage(file: File? = null) {
    data class Checkpoint(val value: JsonElement, val cursor: String?)

    data class PendingSetting(val key: String, val value: JsonElement, val mutationId: String, val updatedAt: Double)

    /** A bootstrap began before settings changed: committing it would erase newer ones. */
    class ChangedDuringRead : Exception("The settings changed during the read.")

    private val database: SQLiteDatabase
    private var closed = false
    private var settingRevision = 0

    init {
        database = if (file == null) {
            SQLiteDatabase.create(null)
        } else {
            file.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null)
        }
        if (database.version < 1) {
            database.execSQL("CREATE TABLE IF NOT EXISTS checkpoint (name TEXT PRIMARY KEY NOT NULL, value BLOB NOT NULL, cursor TEXT)")
            database.execSQL("CREATE TABLE IF NOT EXISTS setting (key TEXT PRIMARY KEY NOT NULL, value BLOB NOT NULL, version TEXT)")
            database.execSQL("CREATE TABLE IF NOT EXISTS pendingSetting (key TEXT PRIMARY KEY NOT NULL, value BLOB NOT NULL, mutationID TEXT NOT NULL, updatedAt DOUBLE NOT NULL)")
            database.version = 1
        }
        if (database.version < 2) {
            database.execSQL("CREATE TABLE IF NOT EXISTS outbox (id TEXT PRIMARY KEY NOT NULL, value BLOB NOT NULL)")
            database.version = 2
        }
    }

    @Synchronized fun confirmedSettingsRevision(): Int = settingRevision

    /** Late work from an account that was removed cannot recreate its cache. */
    @Synchronized
    fun close() {
        closed = true
        database.close()
    }

    private fun checkOpen() {
        if (closed) throw CancellationException("This account's storage is closed.")
    }

    @Synchronized
    fun checkpoint(name: String): Checkpoint? {
        checkOpen()
        database.rawQuery("SELECT value, cursor FROM checkpoint WHERE name = ?", arrayOf(name)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return Checkpoint(parse(cursor.getBlob(0)), if (cursor.isNull(1)) null else cursor.getString(1))
        }
    }

    @Synchronized
    fun commit(checkpoint: Checkpoint, name: String, settings: List<DirectUserSetting>? = null, expectedSettingsRevision: Int? = null) {
        checkOpen()
        if (expectedSettingsRevision != null && settingRevision != expectedSettingsRevision) throw ChangedDuringRead()
        val data = checkpoint.value.toString().toByteArray()
        transaction {
            if (settings != null) {
                database.execSQL("DELETE FROM setting")
                for (setting in settings) saveSetting(setting.key, setting.value.toString().toByteArray(), setting.version)
            }
            database.execSQL(
                """
                INSERT INTO checkpoint(name, value, cursor) VALUES (?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET value = excluded.value, cursor = excluded.cursor
                WHERE value != excluded.value OR cursor IS NOT excluded.cursor
                """.trimIndent(),
                arrayOf<Any?>(name, data, checkpoint.cursor),
            )
        }
        if (settings != null) settingRevision += 1
    }

    /**
     * The person's intent is durable before it is shown or sent. One row a
     * key coalesces offline edits without growing an unbounded queue.
     */
    @Synchronized
    fun enqueueSetting(key: String, value: JsonElement, updatedAt: Double = System.currentTimeMillis().toDouble()): PendingSetting {
        checkOpen()
        val pending = PendingSetting(key, value, ClientId.make(), updatedAt)
        database.execSQL(
            """
            INSERT INTO pendingSetting(key, value, mutationID, updatedAt) VALUES (?, ?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value, mutationID = excluded.mutationID, updatedAt = excluded.updatedAt
            """.trimIndent(),
            arrayOf<Any?>(key, value.toString().toByteArray(), pending.mutationId, updatedAt),
        )
        return pending
    }

    @Synchronized
    fun pendingSettings(): List<PendingSetting> {
        checkOpen()
        database.rawQuery("SELECT key, value, mutationID, updatedAt FROM pendingSetting ORDER BY key", null).use { cursor ->
            val result = ArrayList<PendingSetting>()
            while (cursor.moveToNext()) result += PendingSetting(cursor.getString(0), parse(cursor.getBlob(1)), cursor.getString(2), cursor.getDouble(3))
            return result
        }
    }

    /** A late acknowledgement may update the confirmed copy, but never deletes a newer intent for the same key. */
    @Synchronized
    fun acknowledge(pending: PendingSetting, authoritative: DirectUserSetting? = null) {
        checkOpen()
        settingRevision += 1
        val data = (authoritative?.value ?: pending.value).toString().toByteArray()
        transaction {
            saveSetting(pending.key, data, authoritative?.version)
            database.execSQL("DELETE FROM pendingSetting WHERE key = ? AND mutationID = ?", arrayOf<Any?>(pending.key, pending.mutationId))
        }
    }

    @Synchronized
    fun receiveSetting(key: String, value: JsonElement, version: String? = null) {
        checkOpen()
        settingRevision += 1
        saveSetting(key, value.toString().toByteArray(), version)
    }

    /** Pending values overlay the daemon's copy until acknowledged. */
    @Synchronized
    fun settings(): Map<String, JsonElement> {
        checkOpen()
        val result = LinkedHashMap<String, JsonElement>()
        for (table in listOf("setting", "pendingSetting")) {
            database.rawQuery("SELECT key, value FROM $table", null).use { cursor ->
                while (cursor.moveToNext()) result[cursor.getString(0)] = parse(cursor.getBlob(1))
            }
        }
        return result
    }

    private fun saveSetting(key: String, data: ByteArray, version: String?) {
        database.execSQL(
            """
            INSERT INTO setting(key, value, version) VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value, version = excluded.version
            WHERE setting.version IS NULL OR excluded.version > setting.version
            """.trimIndent(),
            arrayOf<Any?>(key, data, version),
        )
    }

    /** A duplicate enqueue never changes a payload already accepted. */
    @Synchronized
    fun enqueueMessage(message: DirectQueuedMessage) {
        checkOpen()
        val values = ContentValues().apply {
            put("id", message.id)
            put("value", messageJson(message).toString().toByteArray())
        }
        database.insertWithOnConflict("outbox", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized
    fun queuedMessages(): List<DirectQueuedMessage> {
        checkOpen()
        database.rawQuery("SELECT value FROM outbox ORDER BY rowid", null).use { cursor ->
            val result = ArrayList<DirectQueuedMessage>()
            while (cursor.moveToNext()) message(parse(cursor.getBlob(0)))?.let(result::add)
            return result
        }
    }

    @Synchronized
    fun updateMessage(message: DirectQueuedMessage) {
        checkOpen()
        database.execSQL("UPDATE outbox SET value = ? WHERE id = ?", arrayOf<Any?>(messageJson(message).toString().toByteArray(), message.id))
    }

    @Synchronized
    fun acknowledgeMessage(id: String) {
        checkOpen()
        database.execSQL("DELETE FROM outbox WHERE id = ?", arrayOf<Any?>(id))
    }

    private inline fun transaction(body: () -> Unit) {
        database.beginTransaction()
        try {
            body()
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    companion object {
        private fun parse(data: ByteArray): JsonElement = DirectAgentClient.parse(data)

        /** happy-ios `DirectQueuedMessage`'s Codable shape. */
        internal fun messageJson(message: DirectQueuedMessage): JsonObject = Wire.objectOf(
            "id" to JsonPrimitive(message.id), "agentID" to JsonPrimitive(message.agentId), "text" to JsonPrimitive(message.text),
            "pictures" to JsonArray(message.pictures.map {
                JsonObject(mapOf("mimeType" to JsonPrimitive(it.mimeType), "data" to JsonPrimitive(java.util.Base64.getEncoder().encodeToString(it.data))))
            }),
            "mode" to message.mode?.json(), "failure" to message.failure?.let(::JsonPrimitive),
        )

        internal fun message(json: JsonElement): DirectQueuedMessage? = runCatching {
            val o = Wire.obj(json)
            DirectQueuedMessage(
                Wire.string(o, "id"), Wire.string(o, "agentID"), Wire.string(o, "text"),
                Wire.array(o["pictures"]).map { val p = Wire.obj(it); OutgoingImage(Wire.string(p, "mimeType"), java.util.Base64.getDecoder().decode(Wire.string(p, "data"))) },
                o["mode"]?.takeIf { it != JsonNull }?.let(AgentMode::from), Wire.stringOrNull(o, "failure"),
            )
        }.getOrNull()
    }
}
