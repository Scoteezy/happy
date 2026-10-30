package com.slopus.happy.home

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.model.*
import com.slopus.happy.ui.home.BotFacePickerView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BotFacePainterTest {
    @Test fun actualBundledStyleProducesDeterministicOpaque256PngOffMain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var loadedOffMain = false
        val painter = BotFacePainter {
            loadedOffMain = android.os.Looper.myLooper() != android.os.Looper.getMainLooper()
            context.assets.open(BOT_FACE_STYLE_ASSET).bufferedReader().use { BotFaceStyle.parse(it.readText()) }
        }
        val bytes = painter.png("happy")
        assertTrue(loadedOffMain)
        assertArrayEquals(bytes, painter.png("happy"))
        assertFalse(bytes.contentEquals(painter.png("other")))
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(256, image.width)
        assertEquals(256, image.height)
        assertEquals(255, Color.alpha(image.getPixel(0, 0)))
        image.recycle()
    }

    @Test fun nestedInheritanceTranslationNoneAndEvenOddAreRendered() = runBlocking {
        val shapes = listOf(
            FaceShape.Group(FaceFill.Literal("#00ff00"), listOf(
                FaceShape.Path(null, "M0 0H8V8H0Z M2 2H6V6H2Z", true),
                FaceShape.Ellipse(FaceFill.Literal("#0000ff"), 4f, 4f, 1f, 1f),
                FaceShape.Ellipse(FaceFill.Literal("none"), 4f, 4f, 1f, 1f),
                FaceShape.Ellipse(FaceFill.Literal("white"), 7f, 1f, 0.5f, 0.5f),
            )),
            FaceShape.Ellipse(null, 8.5f, 8.5f, 0.5f, 0.5f),
        )
        val style = BotFaceStyle(10f, 10f, listOf(FaceSlot("part", 1f, 1f)),
            mapOf("part" to FaceComponent(100.0, mapOf("one" to FaceVariant(1.0, shapes)))),
            mapOf("background" to listOf("#ff0000")), "artist", "url")
        val image = BotFacePainter { style }.image("seed", 100)
        assertEquals(Color.RED, image.getPixel(2, 2))
        assertEquals(Color.GREEN, image.getPixel(20, 20))
        assertEquals(Color.RED, image.getPixel(35, 35))
        assertEquals(Color.BLUE, image.getPixel(50, 50))
        assertEquals(Color.WHITE, image.getPixel(80, 20))
        assertEquals("Root fill is none", Color.RED, image.getPixel(95, 95))
        image.recycle()
    }

    @Test fun staticCreditMatchesBundledLicenseMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val style = context.assets.open(BOT_FACE_STYLE_ASSET).bufferedReader().use { BotFaceStyle.parse(it.readText()) }
        assertEquals(style.artist, BotFacePickerView.ARTIST)
        assertEquals(style.artistUrl, BotFacePickerView.ARTIST_URL)
    }
}