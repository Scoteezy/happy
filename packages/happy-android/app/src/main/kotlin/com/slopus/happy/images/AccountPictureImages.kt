package com.slopus.happy.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.slopus.happy.attachments.AttachmentPictures
import com.slopus.happy.layout.SettingsProfileHeaderLayout
import kotlin.math.max

/** Native AccountPictureImages: bounded, upright pixels, decoded before reaching a view. */
object AccountPictureImages {
    val maxPixelSize = (SettingsProfileHeaderLayout.Metrics.avatarSize * 3).toInt()

    fun image(bytes: ByteArray?, thumbhash: String?): Bitmap? =
        bytes?.let(::decode) ?: thumbhash?.let(::placeholder)

    fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample / 2 >= maxPixelSize) sample *= 2
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
        }) ?: return null
        val orientation = runCatching {
            bytes.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val scale = minOf(1f, maxPixelSize.toFloat() / max(image.width, image.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
            }
        }
        if (matrix.isIdentity) image else Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
            .also { if (it !== image) image.recycle() }
    }.getOrNull()

    fun placeholder(thumbhash: String): Bitmap? = AttachmentPictures.thumbhashBitmap(thumbhash)
}