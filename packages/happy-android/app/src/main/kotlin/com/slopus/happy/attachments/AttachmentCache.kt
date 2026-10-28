package com.slopus.happy.attachments

import android.content.Context
import java.io.File

/** Normalized copies of images picked to attach; logout wipes them. */
fun attachmentCacheDir(context: Context): File = File(context.cacheDir, "attachments").apply { mkdirs() }
