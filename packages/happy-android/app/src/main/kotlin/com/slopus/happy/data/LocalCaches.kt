package com.slopus.happy.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.slopus.happy.attachments.attachmentCacheDir
import com.slopus.happy.config.LocalSettings
import com.slopus.happy.images.HappyImageLoader
import com.slopus.happy.sync.SessionFileCache
import java.io.File

/**
 * The files this app keeps outside the database: images picked to attach
 * (normalized copies) and the image loader's caches, which hold decrypted
 * attachments in memory and downloaded pictures on disk; and each
 * account's own directory (happy-ios `AccountSession.accountDirectory`):
 * its database and the file viewer's reads. Logout wipes them with the
 * account.
 */
object LocalCaches {
    /** Signing out the active account: the caches every account shares. */
    fun wipeShared(context: Context) {
        attachmentCacheDir(context).deleteRecursively()
        HappyImageLoader.clear(context)
    }

    /**
     * A signed-out account's directory, its database included: the caller
     * has emptied and closed that database first (happy-ios erases, closes,
     * then removes the directory), so no reader is left on a removed file.
     */
    fun removeAccount(context: Context, accountId: String) {
        accountDir(context, accountId).deleteRecursively()
    }

    /** Every signed-in account's directory, by its anonymous id, and the registry beside them. */
    private fun accountsDir(context: Context) = File(context.filesDir, "accounts")

    private fun accountDir(context: Context, accountId: String) = File(accountsDir(context), accountId)

    /** happy-ios `accounts.json`: which accounts are signed in here, no secrets. */
    fun registryFile(context: Context) = File(accountsDir(context), "accounts.json")

    /** [accountId]'s database file if it has one, nothing made. */
    fun storedDatabase(context: Context, accountId: String, name: String): File? =
        File(accountDir(context, accountId), name).takeIf { it.exists() }

    /** [accountId]'s database file, its directory made. */
    fun accountDatabase(context: Context, accountId: String, name: String): File =
        File(accountDir(context, accountId).apply { mkdirs() }, name)

    /**
     * The first launch with several accounts: the data of the one account
     * this device had (the database and local settings kept for the whole
     * app) becomes [accountId]'s own. Once moved, nothing happens again.
     */
    fun adoptLegacyAccount(context: Context, accountId: String, databaseName: String?, preferences: SharedPreferences) {
        if (databaseName == null) return
        val legacy = context.getDatabasePath(databaseName)
        val target = File(accountDir(context, accountId), databaseName)
        if (!legacy.exists() || target.exists()) return
        target.parentFile?.mkdirs()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val from = File(legacy.path + suffix)
            if (from.exists() && !from.renameTo(File(target.path + suffix))) {
                Log.w(TAG, "Could not move ${from.name}; the account syncs again")
            }
        }
        val device = context.getSharedPreferences(LocalSettings.PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.all.isEmpty() && device.all.isNotEmpty()) {
            val editor = preferences.edit()
            for ((key, value) in device.all) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
            device.edit().clear().commit()
        }
    }

    /**
     * The file viewer's cache directory for [anonId], made ready off the
     * main thread as the account starts (happy-ios `prepareDirectory`);
     * null, so reads stay in memory, if it can't be made.
     */
    fun prepareFileCache(context: Context, anonId: String): File? = try {
        val account = accountDir(context, anonId)
        account.mkdirs()
        SessionFileCache.directory(account).also(SessionFileCache::prepare)
    } catch (_: java.io.IOException) {
        null
    }

    private const val TAG = "LocalCaches"
}
