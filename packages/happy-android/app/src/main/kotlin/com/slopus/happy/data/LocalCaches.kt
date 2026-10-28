package com.slopus.happy.data

import android.content.Context
import com.slopus.happy.attachments.attachmentCacheDir
import com.slopus.happy.images.HappyImageLoader

/**
 * The files this app keeps outside the database: images picked to attach
 * (normalized copies) and the image loader's caches, which hold decrypted
 * attachments in memory and downloaded pictures on disk. Logout wipes
 * them with the account.
 */
object LocalCaches {
    fun wipe(context: Context) {
        attachmentCacheDir(context).deleteRecursively()
        HappyImageLoader.clear(context)
    }
}
