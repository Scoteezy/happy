package com.slopus.happy.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconCatalogTest {
    @Test fun everyGlyphDecodes() {
        val all = IconCatalog.allIcons
        assertTrue(all.size > 150)
        for (icon in all) assertNotNull("$icon", IconCatalog.glyph(icon))
    }

    @Test fun ioniconsMetricsMatchTheFont() {
        val glyph = IconCatalog.glyph(Icon.ionicons("chevron-forward"))!!
        assertEquals(512.0, glyph.unitsPerEm, 0.0)
        assertEquals(454.0, glyph.ascender, 0.0)
        assertEquals(-71.0, glyph.descender, 0.0)
        // A 29dp Ionicon is a 29 x 30 box at 3x (29.74 rounded up to the pixel).
        val layout = glyph.layout(29.0, 3.0)
        assertEquals(30.0, layout.size.height, 0.001)
    }

    @Test fun unknownIconIsNull() {
        assertNull(IconCatalog.glyph(Icon.ionicons("no-such-icon")))
    }
}
