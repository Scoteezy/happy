package com.slopus.happy.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// happy-ios SecondaryLayoutTests.swift.

/** info.tsx's loading and deleted states, "Machine not found", and the file viewer's centred states. */
class CenteredStackLayoutTest {
    @Test fun deletedSessionState() {
        // Icon 48, title 20/24 at marginTop 16, description 15/20 at marginTop 8 with paddingHorizontal 32, centred in 390 × 600.
        val layout = CenteredStackLayout(CenteredStackLayout.Input(Size(390.0, 600.0), items = listOf(
            CenteredStackLayout.Item(fixed(48, 48)),
            CenteredStackLayout.Item(text("Session has been deleted", 10.0, 24.0), spacingBefore = 16.0),
            CenteredStackLayout.Item(text("x".repeat(40), 10.0, 20.0), spacingBefore = 8.0, horizontalPadding = 32.0),
        )))
        // 48 + 16 + 24 + 8 + 40 = 136 tall: starts at (600 - 136) / 2 = 232.
        assertFrame(layout.items[0], rect(171, 232, 48, 48))
        assertFrame(layout.items[1], rect(75, 296, 240, 24))
        // 400 wide doesn't fit in 390 - 64: wraps to 32 characters a line.
        assertFrame(layout.items[2], rect(35, 328, 320, 40))
    }

    @Test fun paddingNarrowsAndOffsets() {
        val layout = CenteredStackLayout(CenteredStackLayout.Input(Size(200.0, 100.0), padding = 20.0, items = listOf(
            CenteredStackLayout.Item(text("x".repeat(30), 10.0, 10.0)),
        )))
        // 160 wide → two lines of 16, centred in the 60 left after padding.
        assertFrame(layout.items[0], rect(20, 40, 160, 20))
    }

    @Test fun empty() {
        assertTrue(CenteredStackLayout(CenteredStackLayout.Input(Size(10.0, 10.0), items = emptyList())).items.isEmpty())
    }
}

/** GitLineChanges (gap 4) and the Changes row's right element (gap 6). */
class HorizontalStackLayoutTest {
    @Test fun centresEachPartVertically() {
        val layout = HorizontalStackLayout(listOf(Size(60.0, 15.0), Size(30.0, 14.0), Size(10.0, 21.0)), 6.0)
        assertSize(layout.size, Size(112.0, 21.0))
        assertFrame(layout.items[0], rect(0, 3, 60, 15))
        assertFrame(layout.items[1], rect(66, 3.5, 30, 14))
        assertFrame(layout.items[2], rect(102, 0, 10, 21))
    }

    @Test fun emptyIsZero() {
        assertSize(HorizontalStackLayout(emptyList(), 4.0).size, Size(0.0, 0.0))
    }
}

/** components/CodeView.tsx inside info.tsx's raw JSON group. */
class CodeBlockLayoutTest {
    @Test fun insetsAndPadding() {
        val layout = CodeBlockLayout(358.0, listOf(36.0))
        assertEquals(302.0, CodeBlockLayout.textWidth(358.0), 0.0)
        assertFrame(layout.box, rect(16, 0, 326, 60))
        assertFrame(layout.text, rect(28, 12, 302, 36))
        assertFrame(layout.pieces[0], rect(28, 12, 302, 36))
        assertSize(layout.size, Size(358.0, 72.0))
    }

    @Test fun piecesStackWithNoGap() {
        val layout = CodeBlockLayout(358.0, listOf(720.0, 18.0, 0.0, 1080.0))
        assertFrame(layout.pieces[0], rect(28, 12, 302, 720))
        assertFrame(layout.pieces[1], rect(28, 732, 302, 18))
        assertFrame(layout.pieces[2], rect(28, 750, 302, 0))
        assertFrame(layout.pieces[3], rect(28, 750, 302, 1080))
        assertFrame(layout.text, rect(28, 12, 302, 1818))
        assertFrame(layout.box, rect(16, 0, 326, 1842))
        assertSize(layout.size, Size(358.0, 1854.0))
    }

    @Test fun noPiecesIsAnEmptyBox() {
        val layout = CodeBlockLayout(358.0, emptyList())
        assertFrame(layout.text, rect(28, 12, 302, 0))
        assertFrame(layout.box, rect(16, 0, 326, 24))
        assertSize(layout.size, Size(358.0, 36.0))
        assertTrue(layout.pieces(0.0, 1000.0).isEmpty())
    }

    @Test fun narrowerThanItsInsetsIsZeroWide() {
        val layout = CodeBlockLayout(40.0, listOf(18.0))
        assertEquals(0.0, CodeBlockLayout.textWidth(40.0), 0.0)
        assertFrame(layout.box, rect(16, 0, 8, 42))
        assertFrame(layout.pieces[0], rect(28, 12, 0, 18))
    }

    /** Pieces at 12..<732, 732..<1452, 1452..<2172, 2172..<2892. */
    @Test fun thePiecesMeetingARange() {
        val layout = CodeBlockLayout(358.0, listOf(720.0, 720.0, 720.0, 720.0))
        assertTrue(layout.pieces(0.0, 12.0).isEmpty())
        assertEquals(0 until 1, layout.pieces(0.0, 13.0))
        assertEquals(0 until 2, layout.pieces(731.0, 733.0))
        assertEquals(1 until 2, layout.pieces(732.0, 1452.0))
        assertEquals(1 until 4, layout.pieces(1000.0, 2500.0))
        assertEquals(0 until 4, layout.pieces(-5000.0, 50_000.0))
        assertTrue(layout.pieces(2892.0, 3000.0).isEmpty())
        assertTrue(layout.pieces(900.0, 900.0).isEmpty())
    }

    @Test fun anEmptyPieceInsideTheRangeIsIncluded() {
        val layout = CodeBlockLayout(358.0, listOf(100.0, 0.0, 100.0))
        assertEquals(0 until 3, layout.pieces(0.0, 1000.0))
        // The empty piece sits at 112; a range ending there stops before it.
        assertEquals(0 until 1, layout.pieces(0.0, 112.0))
    }
}
