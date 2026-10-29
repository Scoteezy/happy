package com.slopus.happy.direct

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest

/** One item of a paired computer's event stream (happy-ios `StemCellsStore.ConversationEvent`). */
sealed class DirectEvent {
    data class Event(val envelope: JsonObject) : DirectEvent()

    /** The stream connected again: what it would have said is to be read whole. */
    data object Resync : DirectEvent()
}

/**
 * What one computer keeps on the phone between launches (happy-ios
 * `StemCellsOfflineFile`'s files): one folder per computer, holding
 * `snapshot.json`, other features' named files and `pictures/`, each
 * written whole.
 *
 * Every write to the folder goes through its gate, and removing the
 * computer closes the gate and deletes the folder under the same lock: a
 * write still on its way when the computer goes finds the gate shut and
 * writes nothing, so nothing of a removed computer comes back.
 */
class DirectOfflineFile(val directory: File) {
    private val gate = Gate.open(directory)

    val snapshotFile: File get() = File(directory, "snapshot.json")
    val picturesDirectory: File get() = File(directory, "pictures")

    /** A file another feature keeps for this computer (`stories.json`), written whole through the gate. */
    fun writeExtra(name: String, data: ByteArray) = gate.write { atomically(File(directory, name), data) }

    fun readExtra(name: String): ByteArray? = if (gate.closed) null else runCatching { File(directory, name).takeIf { it.isFile }?.readBytes() }.getOrNull()

    /** A picture's bytes as last read, by where it is served and its hash. */
    fun picture(path: String, thumbhash: String?): ByteArray? =
        if (gate.closed) null else runCatching { pictureFile(path, thumbhash).takeIf { it.isFile }?.readBytes() }.getOrNull()

    /**
     * Keeps a picture, in place of any other kept from the same path: a new
     * avatar replaces the old one rather than piling up beside it.
     */
    fun keepPicture(data: ByteArray, path: String, thumbhash: String?) {
        val file = pictureFile(path, thumbhash)
        gate.write {
            file.parentFile?.deleteRecursively()
            atomically(file, data)
        }
    }

    /** `pictures/<path's digest>/<hash's digest>`: short, safe names, one folder per path. */
    fun pictureFile(path: String, thumbhash: String?): File = File(File(picturesDirectory, digest(path)), digest(thumbhash ?: ""))

    private fun atomically(target: File, data: ByteArray) {
        runCatching {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeBytes(data)
            if (!temporary.renameTo(target)) {
                target.delete()
                temporary.renameTo(target)
            }
        }
    }

    /** The one lock every write to a folder takes, shared by every file open on that folder. */
    private class Gate private constructor() {
        @Volatile
        var closed = false
            private set

        fun write(body: () -> Unit) = synchronized(this) { if (!closed) body() }

        companion object {
            private val gates = HashMap<String, Gate>()

            fun open(directory: File): Gate = synchronized(gates) { gates.getOrPut(directory.absolutePath) { Gate() } }

            /** Shuts the folder's gate and deletes it; a later file opens a new one. */
            fun close(directory: File) {
                val gate = synchronized(gates) { gates.remove(directory.absolutePath) } ?: Gate()
                synchronized(gate) {
                    gate.closed = true
                    directory.deleteRecursively()
                }
            }
        }
    }

    companion object {
        /** Forgets everything kept for a computer, and refuses what any file still open on it would write later. */
        fun remove(directory: File) = Gate.close(directory)

        internal fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }
}
