package com.slopus.happy.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordmarkTest {
    @Test fun bothLayersParseAndFitTheViewBox() {
        val layers = Wordmark.graphic.layers
        assertEquals(2, layers.size)
        for (layer in layers) {
            assertTrue(layer.path.elements.size > 50)
            val bounds = layer.path.controlBounds!!
            assertTrue(bounds.minX >= -1 && bounds.maxX <= 5275)
            assertTrue(bounds.minY >= -701 && bounds.maxY <= 1233)
        }
    }
}
