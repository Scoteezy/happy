package com.slopus.happy.layout.composer

import com.slopus.happy.layout.Size
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertFalse
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.XCTAssertTrue
import com.slopus.happy.layout.assertFrame
import com.slopus.happy.layout.assertSize
import com.slopus.happy.layout.rect
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/AgentInputDesktopLayoutTests.swift.

/** AgentInput.tsx's flat panel with `desktopActionControls`, as Yoga lays it out. */
class AgentInputDesktopLayoutTest {
    /** One line: 22 with the input's 8 + 8. */
    private fun input(
        width: Double = 800.0,
        attachments: Int = 0,
        text: Double = 38.0,
        hasText: Boolean = true,
        minHeight: Double? = null,
        settings: Boolean = true,
        stop: Double? = 16.0,
        image: Double? = 16.0,
        zen: Boolean = false,
    ) = AgentInputDesktopLayout.Input(
        width = width, attachmentCount = attachments, textContentHeight = text, hasText = hasText, inputMinHeight = minHeight,
        showsSettings = settings, stopContentWidth = stop, imageContentWidth = image, zenMode = zen,
    )

    @Test fun testTheFullRow() {
        val layout = AgentInputDesktopLayout(input())
        XCTAssertNil(layout.attachments)
        // 2 down, the 40 minimum outgrown by 38 + 4 + 4.
        assertFrame(layout.inputContainer, rect(8, 2, 784, 46))
        assertFrame(layout.text, rect(10, 6, 774, 38))
        XCTAssertFalse(layout.textScrolls)
        // The gear sets the row's height; send is centred on it, stop and image keep to its top.
        assertFrame(layout.actionRow, rect(8, 48, 784, 40))
        assertFrame(layout.left, rect(8, 48, 744, 40))
        assertFrame(layout.leftContent.settings, rect(0, 0, 40, 40))
        assertFrame(layout.leftContent.stop, rect(48, 0, 32, 32))
        assertFrame(layout.leftContent.image, rect(88, 0, 32, 32))
        assertFrame(layout.send, rect(760, 52, 32, 32))
        assertSize(layout.size, Size(800, 96))
        XCTAssertEqual(AgentInputDesktopLayout.textWidth(800.0), 774)
    }

    @Test fun testWithoutTheGearTheRowIsSendsHeight() {
        val layout = AgentInputDesktopLayout(input(settings = false, image = null))
        assertFrame(layout.actionRow, rect(8, 48, 784, 32))
        assertFrame(layout.left, rect(8, 48, 744, 32))
        XCTAssertNil(layout.leftContent.settings)
        assertFrame(layout.leftContent.stop, rect(0, 0, 32, 32))
        XCTAssertNil(layout.leftContent.image)
        assertFrame(layout.send, rect(760, 48, 32, 32))
        assertSize(layout.size, Size(800, 88))

        // Nothing on the left at all: the group is there, empty.
        val bare = AgentInputDesktopLayout(input(settings = false, stop = null, image = null))
        assertFrame(bare.left, rect(8, 64, 744, 0))
        assertFrame(bare.send, rect(760, 48, 32, 32))
        XCTAssertEqual(bare.leftContent, AgentInputDesktopLayout.LeftContent())
    }

    @Test fun testZenModeLeavesSendAlone() {
        val layout = AgentInputDesktopLayout(input(zen = true))
        XCTAssertNil(layout.left)
        XCTAssertEqual(layout.leftContent, AgentInputDesktopLayout.LeftContent())
        assertFrame(layout.actionRow, rect(8, 48, 784, 32))
        assertFrame(layout.send, rect(760, 48, 32, 32))
        assertSize(layout.size, Size(800, 88))
    }

    @Test fun testPicturesAboveTheTextAndAStopInFlight() {
        // The spinner is wider than the glyph and pushes image along.
        val layout = AgentInputDesktopLayout(input(attachments = 2, stop = 20.0))
        assertFrame(layout.attachments, rect(16, 2, 768, 64))
        assertFrame(layout.inputContainer, rect(8, 74, 784, 46))
        assertFrame(layout.text, rect(10, 78, 774, 38))
        assertFrame(layout.leftContent.stop, rect(48, 0, 36, 32))
        assertFrame(layout.leftContent.image, rect(92, 0, 32, 32))
        assertFrame(layout.send, rect(760, 124, 32, 32))
        assertSize(layout.size, Size(800, 168))
    }

    @Test fun testTheTextGrowsToItsCapThenScrolls() {
        val tall = AgentInputDesktopLayout(input(text = 200.0))
        assertFrame(tall.inputContainer, rect(8, 2, 784, 128))
        assertFrame(tall.text, rect(10, 6, 774, 120))
        XCTAssertTrue(tall.textScrolls)
        // Emptied: one line, whatever was measured last.
        val empty = AgentInputDesktopLayout(input(text = 200.0, hasText = false))
        assertFrame(empty.text, rect(10, 6, 774, 38))
        XCTAssertFalse(empty.textScrolls)
        // A taller minimum centres the text in it.
        val roomy = AgentInputDesktopLayout(input(minHeight = 60.0))
        assertFrame(roomy.inputContainer, rect(8, 2, 784, 60))
        assertFrame(roomy.text, rect(10, 13, 774, 38))
    }

    @Test fun testANarrowPanelClipsTheLeftGroup() {
        val layout = AgentInputDesktopLayout(input(width = 100.0))
        assertFrame(layout.send, rect(60, 52, 32, 32))
        // Stop and image lie past its edge, cut off.
        assertFrame(layout.left, rect(8, 48, 44, 40))
        assertFrame(layout.leftContent.stop, rect(48, 0, 32, 32))
    }

    @Test fun testThePanelInTheComposer() {
        val layout = AgentInputLayout(
            AgentInputLayout.Input(
                width = 1024.0,
                paddingH = AgentInputLayout.paddingH(1024.0),
                screenWidth = 1024.0,
                desktop = input(width = 0.0),
            ),
        )
        XCTAssertTrue(layout.isWide)
        XCTAssertNil(layout.cardLayout)
        assertFrame(layout.card, rect(12, 8, 1000, 96))
        assertFrame(layout.desktopLayout?.send, rect(960, 52, 32, 32))
        assertSize(layout.size, Size(1024, 112))
        assertFrame(layout.autocomplete(rows = 1, border = 0.0), rect(12, -48, 1000, 48))
    }

    @Test fun testTheWindowNotTheComposerDecidesWide() {
        // A 768 window with the sidebar out: the chat is 518 wide, and happy-app's `screenWidth` is still the window's.
        val wide = AgentInputLayout(
            AgentInputLayout.Input(
                width = 518.0,
                paddingH = AgentInputLayout.paddingH(768.0),
                screenWidth = 768.0,
                desktop = input(width = 0.0),
            ),
        )
        XCTAssertTrue(wide.isWide)
        assertFrame(wide.card, rect(12, 8, 494, 96))
        assertFrame(wide.autocomplete(rows = 1, border = 0.0), rect(12, -48, 494, 48))
        // A 694 split-screen half of a wide tablet is not.
        XCTAssertFalse(AgentInputLayout.isWide(694.0))
        XCTAssertFalse(AgentInputLayout.isWide(700.0))
        XCTAssertTrue(AgentInputLayout.isWide(701.0))
        XCTAssertEqual(AgentInputLayout.paddingH(700.0), 8)
    }
}
