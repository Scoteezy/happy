package com.slopus.happy.attachments

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Where picked images wait to be sent (happy-ios
 * `ComposerAttachmentStaging`): a folder of their own under the cache. The
 * selection lives no longer than the composer that made it, as
 * useImagePicker's state does, so the composer calls [close] when it goes;
 * folders left by an earlier process go when the next one is made. The
 * upload reads the files before the send is accepted, so nothing outlives
 * the send.
 */
class ComposerAttachmentStaging(context: Context) {
    private val root = File(context.cacheDir, "composer")
    val directory: File

    init {
        val name = UUID.randomUUID().toString()
        directory = File(root, name)
        val alive = synchronized(live) {
            live += name
            live.toSet()
        }
        root.listFiles()?.filter { it.name !in alive }?.forEach { it.deleteRecursively() }
    }

    /** The folder, made if it is not there yet. */
    fun folder(): File = directory.apply { mkdirs() }

    /** Deletes a file this folder holds; anything else is left alone. */
    fun remove(file: File) {
        if (file.parentFile?.canonicalPath == directory.canonicalPath) file.delete()
    }

    fun close() {
        synchronized(live) { live -= directory.name }
        directory.deleteRecursively()
    }

    private companion object {
        val live = mutableSetOf<String>()
    }
}
