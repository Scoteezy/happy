package com.slopus.happy.data

import android.content.Context
import com.slopus.happy.attachments.attachmentCacheDir
import com.slopus.happy.images.HappyImageLoader
import com.slopus.happy.sync.SessionFileCache
import java.io.File

/**
 * The files this app keeps outside the database: images picked to attach
 * (normalized copies) and the image loader's caches, which hold decrypted
 * attachments in memory and downloaded pictures on disk; and each
 * account's own files (the file viewer's reads). Logout wipes them with
 * the account.
 */
object LocalCaches {
    fun wipe(context: Context) {
        attachmentCacheDir(context).deleteRecursively()
        HappyImageLoader.clear(context)
        accountsDir(context).deleteRecursively()
    }

    /** Each signed-in account's own files (happy-ios `AccountSession.accountDirectory`), by its anonymous id. */
    private fun accountsDir(context: Context) = File(context.filesDir, "accounts")

    /**
     * The file viewer's cache directory for [anonId], made ready off the
     * main thread as the account starts (happy-ios `prepareDirectory`);
     * null, so reads stay in memory, if it can't be made.
     */
    fun prepareFileCache(context: Context, anonId: String): File? = try {
        val account = File(accountsDir(context), anonId)
        account.mkdirs()
        SessionFileCache.directory(account).also(SessionFileCache::prepare)
    } catch (_: java.io.IOException) {
        null
    }
}
