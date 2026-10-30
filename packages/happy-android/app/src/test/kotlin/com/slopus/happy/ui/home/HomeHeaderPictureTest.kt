package com.slopus.happy.ui.home

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.layout.Rect
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.setFrame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeHeaderPictureTest {
    @Test fun originalPictureFillsARoundMarkAndKeepsSettingsAccessibility() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Theme.install(context, Configuration(context.resources.configuration).apply { densityDpi = 160; fontScale = 1f })
        val header = HomeHeaderView(context)
        val picture = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        for (x in 0 until 80) for (y in 0 until 40) picture.setPixel(x, y, when { x < 20 -> Color.GREEN; x < 60 -> Color.RED; else -> Color.BLUE })
        var opened = false
        header.logoControl.contentDescription = "Open settings"
        header.logoControl.setOnClickListener { opened = true }
        header.accountPicture = picture
        header.setFrame(Rect(0, 0, 393, 100))
        val mark = header.logoControl.getChildAt(0)
        val rendered = Bitmap.createBitmap(mark.width, mark.height, Bitmap.Config.ARGB_8888)
        mark.draw(Canvas(rendered))
        assertTrue(header.showsAccountAvatar)
        assertEquals(Color.RED, rendered.getPixel(mark.width / 2, mark.height / 2))
        assertEquals(Color.RED, rendered.getPixel(mark.width / 2, 3))
        assertEquals(0, Color.alpha(rendered.getPixel(0, 0)))
        assertEquals("Open settings", header.logoControl.contentDescription)
        header.logoControl.performClick()
        assertTrue(opened)
        header.accountPicture = null
        assertFalse(header.showsAccountAvatar)
        assertFalse(picture.isRecycled)
    }
}