package com.slopus.happy.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountPictureImagesTest {
    private fun png(width: Int, height: Int) = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.MAGENTA); it.compress(Bitmap.CompressFormat.PNG, 100, out); it.recycle()
        }
    }.toByteArray()

    @Test fun largePixelsAreBoundedAndSmallPicturesKeepTheirSizeAndColor() {
        val large = AccountPictureImages.decode(png(2700, 1350))!!
        assertEquals(270 to 135, large.width to large.height)
        assertEquals(Color.MAGENTA, large.getPixel(100, 50))
        val small = AccountPictureImages.decode(png(20, 10))!!
        assertEquals(20 to 10, small.width to small.height)
    }

    @Test fun jpegOrientationIsAppliedBeforeThePictureIsShown() {
        val file = File.createTempFile("account-picture-", ".jpg", ApplicationProvider.getApplicationContext<Context>().cacheDir)
        try {
            val source = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
            for (x in 0 until 40) for (y in 0 until 20) source.setPixel(x, y, if (x < 20) Color.RED else Color.BLUE)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            source.recycle()
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes()
            }
            val result = AccountPictureImages.decode(file.readBytes())!!
            assertEquals(20 to 40, result.width to result.height)
            assertTrue(Color.red(result.getPixel(10, 5)) > 200)
            assertTrue(Color.blue(result.getPixel(10, 35)) > 200)
        } finally { file.delete() }
    }

    @Test fun bytesWinOverPlaceholderAndInvalidBytesFallBackToThumbhash() {
        val hash = "HwgKPxxwd3dweHiHiHiHd4h3Bwd4gI8I"
        assertEquals(Color.MAGENTA, AccountPictureImages.image(png(10, 10), hash)!!.getPixel(5, 5))
        val placeholder = AccountPictureImages.image(byteArrayOf(1, 2, 3), hash)!!
        assertTrue(placeholder.width in 1..32 && placeholder.height in 1..32)
        assertNull(AccountPictureImages.image(byteArrayOf(1), "not a thumbhash"))
        assertNull(AccountPictureImages.image(null, null))
    }
}