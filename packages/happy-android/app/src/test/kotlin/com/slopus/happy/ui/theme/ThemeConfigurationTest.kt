package com.slopus.happy.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.config.ThemePreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The activity runs in a configuration context for its language, whose
 * resources keep the configuration it was made with; a later dark mode,
 * font size or density reaches the theme only through the configuration
 * onConfigurationChanged is handed.
 */
@RunWith(AndroidJUnit4::class)
class ThemeConfigurationTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    private fun configuration(night: Boolean, fontScale: Float = 1f, densityDpi: Int = 420) = Configuration(app.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        this.fontScale = fontScale
        this.densityDpi = densityDpi
    }

    @After
    fun tearDown() {
        Theme.setPreference(ThemePreference.Adaptive)
        Theme.install(app, configuration(night = false))
    }

    @Test
    fun anAdaptiveThemeFollowsTheConfigurationItIsHandedNotTheFrozenContexts() {
        val frozen = app.createConfigurationContext(configuration(night = false).apply { setLocale(Locale.FRENCH) })
        Theme.setPreference(ThemePreference.Adaptive)
        Theme.install(frozen)
        assertFalse(Theme.isDark)

        assertTrue(Theme.configurationChanged(frozen, configuration(night = true, fontScale = 1.3f, densityDpi = 480)))
        assertTrue(Theme.isDark)
        assertEquals(1.3, Theme.textScale.multiplier, 1e-6)
        assertEquals(3f, Theme.density, 1e-6f)

        assertTrue(Theme.configurationChanged(frozen, configuration(night = false)))
        assertFalse(Theme.isDark)
    }

    @Test
    fun aChosenThemeIgnoresTheSystem() {
        Theme.setPreference(ThemePreference.Light)
        Theme.configurationChanged(app, configuration(night = true))
        assertFalse(Theme.isDark)
    }
}
