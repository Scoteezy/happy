package com.slopus.happy.direct

import kotlinx.serialization.json.JsonObject
import java.io.File

/** One item of a paired computer's event stream (happy-ios `StemCellsStore.ConversationEvent`). */
sealed class DirectEvent {
    data class Event(val envelope: JsonObject) : DirectEvent()

    /** The stream connected again: what it would have said is to be read whole. */
    data object Resync : DirectEvent()
}

/**
 * What one computer keeps on the phone between launches (happy-ios
 * `StemCellsOfflineFile`'s extras): named files in its own folder, each
 * written whole.
 */
class DirectOfflineFile(private val directory: File) {
    fun writeExtra(name: String, data: ByteArray) {
        directory.mkdirs()
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        runCatching {
            temporary.writeBytes(data)
            if (!temporary.renameTo(target)) {
                target.delete()
                temporary.renameTo(target)
            }
        }
    }

    fun readExtra(name: String): ByteArray? = runCatching { File(directory, name).takeIf { it.isFile }?.readBytes() }.getOrNull()
}
