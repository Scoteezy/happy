package com.slopus.happy.ui.components

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.ui.theme.Theme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A label follows the person's text size unless it is one happy-ios draws
 * as designed (outside `FollowsTextSize`: the bar's own title and buttons,
 * home's floating header and resting dock).
 */
@RunWith(AndroidJUnit4::class)
class LabelTextSizeTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    private fun install(fontScale: Float) {
        Theme.install(app, Configuration(app.resources.configuration).apply { this.fontScale = fontScale })
    }

    @After
    fun tearDown() = install(1f)

    @Test
    fun onlyAFollowingLabelGrowsWithTheTextSize() {
        install(1f)
        val following = Label(app, TextStyles.headerTitle, Palette.text, maxLines = 1).apply { text = "Sessions" }
        val fixed = Label(app, TextStyles.headerTitle, Palette.text, maxLines = 1).apply { text = "Sessions"; followsTextSize = false }
        val designed = following.measure(400.0)
        assertEquals(designed, fixed.measure(400.0))

        install(1.353f)
        // The 20 line grows to 27.06; Robolectric measures widths by characters, not size.
        assertEquals(20.0 * 1.353, following.measure(400.0).height, 0.5)
        assertEquals(designed, fixed.measure(400.0))
    }
}
