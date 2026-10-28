package com.slopus.happy.layout.composer

import com.slopus.happy.layout.Size
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertFalse
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.XCTAssertTrue
import com.slopus.happy.layout.assertFrame
import com.slopus.happy.layout.assertSize
import com.slopus.happy.layout.rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/ComposerLayoutTests.swift.

class MultiTextInputLayoutTest {
    private fun resolve(content: Double, max: Double, line: Double, top: Double, bottom: Double, min: Double?, chrome: Double) =
        MultiTextInputLayout(content, maxHeight = max, lineHeight = line, paddingTop = top, paddingBottom = bottom, minimumHeight = min, containerChromeHeight = chrome)

    @Test fun testOneLine() = assertEquals(MultiTextInputLayout(22.0, 22.0, false), resolve(22.0, 120.0, 22.0, 0.0, 0.0, null, 0.0))
    @Test fun testMultilineGrowth() = assertEquals(MultiTextInputLayout(66.0, 66.0, false), resolve(66.0, 120.0, 22.0, 0.0, 0.0, null, 0.0))
    @Test fun testCapEnablesScrolling() = assertEquals(MultiTextInputLayout(120.0, 120.0, true), resolve(180.0, 120.0, 22.0, 0.0, 0.0, null, 0.0))
    @Test fun testShrinkingBelowCap() = assertEquals(MultiTextInputLayout(44.0, 44.0, false), resolve(44.0, 120.0, 22.0, 0.0, 0.0, null, 0.0))
    @Test fun testVerticalPaddingContributesMinimum() = assertEquals(MultiTextInputLayout(32.0, 32.0, false), resolve(10.0, 120.0, 22.0, 4.0, 6.0, null, 0.0))
    @Test fun testExplicitMinimumKeepsACompactInputTallEnough() =
        assertEquals(MultiTextInputLayout(44.0, 44.0, false), resolve(10.0, 120.0, 22.0, 4.0, 6.0, 44.0, 0.0))
    @Test fun testComposerChromeGrowsWithMultilineInput() =
        assertEquals(MultiTextInputLayout(66.0, 132.0, false), resolve(66.0, 120.0, 22.0, 0.0, 0.0, 44.0, 66.0))

    @Test fun testClearedTextWithAStaleMeasurementReturnsToTheMinimum() {
        val layout = MultiTextInputLayout(
            contentHeight = 120.0, hasText = false, maxHeight = 120.0, lineHeight = 24.0, paddingTop = 8.0, paddingBottom = 4.0,
            minimumHeight = 44.0, containerChromeHeight = 66.0,
        )
        assertEquals(MultiTextInputLayout(44.0, 110.0, false), layout)
    }
}

class AgentInputCardLayoutTest {
    /** A phone's card: 393 less the composer's 8 + 8. */
    private fun card(
        text: Double = 30.0,
        hasText: Boolean = true,
        attachments: Int = 0,
        border: Double = 0.0,
        showsAdd: Boolean = false,
        permission: Size? = null,
        model: Size? = null,
        modelIsMenu: Boolean = true,
        effort: Size? = null,
        minHeight: Double? = null,
        zenMode: Boolean = false,
        offersExpand: Boolean = false,
    ) = AgentInputCardLayout(
        AgentInputCardLayout.Input(
            width = 377.0,
            borderWidth = border,
            attachmentCount = attachments,
            textContentHeight = text,
            hasText = hasText,
            inputMinHeight = minHeight,
            showsAdd = showsAdd,
            permission = permission?.let { AgentInputCardLayout.Chip(it) },
            model = model?.let { AgentInputCardLayout.Chip(it) },
            modelIsMenu = modelIsMenu,
            effort = effort?.let { AgentInputCardLayout.Chip(it) },
            separator = if (effort == null) Size.Zero else Size(5, 17),
            zenMode = zenMode,
            offersExpand = offersExpand,
        ),
    )

    @Test fun testOneLineCard() {
        val layout = card()
        assertSize(layout.size, Size(377, 102))
        XCTAssertNil(layout.attachments)
        assertFrame(layout.inputContainer, rect(10, 8, 357, 44))
        assertFrame(layout.text, rect(18, 15, 341, 30))
        XCTAssertFalse(layout.textScrolls)
        assertFrame(layout.actionRow, rect(10, 52, 357, 42))
        XCTAssertNil(layout.add)
        assertFrame(layout.primary, rect(325, 52, 42, 42))
        assertFrame(layout.middle, rect(10, 52, 305, 42))
    }

    @Test fun testHeightMatchesTheMetricContract() {
        val metrics = MobileComposerMetrics.mobile
        XCTAssertEqual(card(text = 30.0).size.height, metrics.composerHeight(30.0))
        XCTAssertEqual(card(text = 52.0).size.height, metrics.composerHeight(52.0))
        XCTAssertEqual(card(text = 120.0).size.height, metrics.composerHeight(120.0))
        XCTAssertEqual(card(attachments = 2).size.height, metrics.composerHeight(30.0, hasAttachments = true))
    }

    @Test fun testTextGrowsThenScrollsAtTheCap() {
        val grown = card(text = 74.0)
        assertFrame(grown.inputContainer, rect(10, 8, 357, 82))
        assertFrame(grown.text, rect(18, 12, 341, 74))
        XCTAssertFalse(grown.textScrolls)
        val capped = card(text = 300.0)
        assertFrame(capped.inputContainer, rect(10, 8, 357, 128))
        assertFrame(capped.text, rect(18, 12, 341, 120))
        XCTAssertTrue(capped.textScrolls)
        XCTAssertEqual(capped.size.height, 186)
    }

    @Test fun testExpandShowsOnceTheTextScrolls() {
        val five = card(text = 118.0, offersExpand = true)
        XCTAssertFalse(five.textScrolls)
        XCTAssertNil(five.expand)
        XCTAssertEqual(five.textTrailingInset, 0)
        val six = card(text = 140.0, offersExpand = true)
        XCTAssertTrue(six.textScrolls)
        assertFrame(six.text, rect(18, 12, 341, 120))
        assertFrame(six.expand, rect(329, 12, 30, 30))
        XCTAssertEqual(six.textTrailingInset, 30)
        assertEquals(card(text = 140.0).size, six.size)
        val unoffered = card(text = 140.0)
        XCTAssertNil(unoffered.expand)
    }

    @Test fun testEmptyInputIgnoresAStaleMeasurement() {
        val layout = card(text = 120.0, hasText = false)
        assertFrame(layout.text, rect(18, 15, 341, 30))
        XCTAssertEqual(layout.size.height, 102)
    }

    @Test fun testMinimumHeightOverride() {
        val layout = card(minHeight = 60.0)
        assertFrame(layout.inputContainer, rect(10, 8, 357, 60))
        assertFrame(layout.text, rect(18, 23, 341, 30))
        XCTAssertEqual(layout.size.height, 118)
    }

    @Test fun testAttachmentStripAboveTheInput() {
        val layout = card(attachments = 3)
        assertFrame(layout.attachments, rect(0, 12, 377, 70))
        assertEquals(AgentInputCardLayout.AttachmentInsets(18.0, 6.0, 18.0), layout.attachmentInsets)
        assertFrame(layout.inputContainer, rect(10, 90, 357, 44))
        assertFrame(layout.actionRow, rect(10, 134, 357, 42))
        XCTAssertEqual(layout.size.height, 184)
    }

    @Test fun testPhotosLineUpWithTheTextAndNothingIsCut() {
        for (border in listOf(0.0, 0.5)) {
            val layout = card(text = 52.0, attachments = 3, border = border)
            val strip = layout.attachments!!
            val (frames, contentWidth) = AgentInputCardLayout.attachmentThumbnails(3, layout.attachmentInsets)
            val photos = frames.map { it.offsetBy(strip.minX, strip.minY) }
            XCTAssertEqual(photos[0].minX, layout.text.minX, accuracy = 1e-9)
            XCTAssertEqual(photos[0].minY, layout.text.minX, accuracy = 1e-9)
            for (photo in frames) {
                val remove = AgentInputCardLayout.attachmentRemoveButton.offsetBy(photo.minX, photo.minY)
                assertTrue(remove.minY >= 0)
                assertTrue(remove.maxY <= strip.height)
                assertTrue(remove.maxX <= contentWidth)
                val inCard = remove.offsetBy(strip.minX, strip.minY)
                assertTrue(inCard.minY >= border)
                assertTrue(inCard.maxY <= layout.inputContainer.minY)
            }
        }
        val (frames, contentWidth) = AgentInputCardLayout.attachmentThumbnails(3, card(attachments = 3).attachmentInsets)
        assertFrame(frames[0], rect(18, 6, 64, 64))
        assertFrame(frames[2], rect(162, 6, 64, 64))
        XCTAssertEqual(contentWidth, 244)
    }

    @Test fun testBorderInsetsEverything() {
        val layout = card(border = 0.5)
        assertFrame(layout.inputContainer, rect(10.5, 8.5, 356, 44))
        assertFrame(layout.primary, rect(324.5, 52.5, 42, 42))
        XCTAssertEqual(layout.size.height, 103)
    }

    @Test fun testChipsInTheMiddleBox() {
        val layout = card(showsAdd = true, permission = Size(30, 17), model = Size(80, 17), effort = Size(40, 17))
        assertFrame(layout.add, rect(10, 52, 42, 42))
        assertFrame(layout.middle, rect(54, 52, 261, 42))
        assertFrame(layout.primary, rect(325, 52, 42, 42))
        val content = layout.middleContent
        assertFrame(content.permission?.frame, rect(0, 1, 42, 40))
        assertFrame(content.permission?.label, rect(6, 11.5, 30, 17))
        assertFrame(content.model?.frame, rect(92, 1, 96, 40))
        assertFrame(content.model?.label, rect(16, 11.5, 80, 17))
        assertFrame(content.separator, rect(190, 12.5, 5, 17))
        assertFrame(content.effort?.frame, rect(197, 1, 64, 40))
        assertFrame(content.effort?.label, rect(0, 11.5, 40, 17))
    }

    @Test fun testZenModeLeavesTheTextAndSend() {
        val layout = card(attachments = 1, showsAdd = true, permission = Size(30, 17), model = Size(80, 17), effort = Size(40, 17), zenMode = true)
        XCTAssertNil(layout.add)
        assertFrame(layout.middle, rect(10, 134, 305, 42))
        assertFrame(layout.primary, rect(325, 134, 42, 42))
        assertEquals(AgentInputCardLayout.MiddleContent(), layout.middleContent)
        XCTAssertEqual(layout.size.height, card(attachments = 1, showsAdd = true).size.height)
        assertEquals(card(), card(zenMode = true))
    }

    @Test fun testOnlyTheModelShrinks() {
        val content = card(showsAdd = true, permission = Size(30, 17), model = Size(300, 17), effort = Size(40, 17)).middleContent
        assertFrame(content.permission?.frame, rect(0, 1, 42, 40))
        assertFrame(content.model?.frame, rect(46, 1, 142, 40))
        assertFrame(content.model?.label, rect(0, 11.5, 142, 17))
        assertFrame(content.effort?.frame, rect(197, 1, 64, 40))
    }

    @Test fun testModelWithoutAMenuUsesThePlainChipPadding() {
        val layout = card(model = Size(80, 17), modelIsMenu = false)
        assertFrame(layout.middleContent.model?.frame, rect(217, 1, 88, 40))
        assertFrame(layout.middleContent.model?.label, rect(8, 11.5, 80, 17))
        XCTAssertNil(layout.middleContent.permission)
        XCTAssertNil(layout.middleContent.effort)
    }

    @Test fun testAttachmentThumbnails() {
        val (frames, contentWidth) = AgentInputCardLayout.attachmentThumbnails(3)
        assertFrame(frames[0], rect(4, 0, 64, 64))
        assertFrame(frames[2], rect(148, 0, 64, 64))
        XCTAssertEqual(contentWidth, 216)
        XCTAssertEqual(AgentInputCardLayout.attachmentThumbnails(0).second, 0)
        assertFrame(AgentInputCardLayout.attachmentRemoveButton, rect(52, -6, 18, 18))
    }
}

class AgentInputLayoutTest {
    private val oneLine = AgentInputCardLayout.Input(width = 0.0, textContentHeight = 30.0, hasText = true)

    @Test fun testCardAloneInTheContainerPadding() {
        val layout = AgentInputLayout(AgentInputLayout.Input(width = 393.0, card = oneLine))
        XCTAssertNil(layout.status)
        XCTAssertNil(layout.usage)
        assertFrame(layout.card, rect(8, 8, 377, 102))
        XCTAssertEqual(layout.cardOffset, 8)
        assertSize(layout.size, Size(393, 118))
        assertFrame(layout.inner, rect(8, 8, 377, 102))
    }

    @Test fun testStatusAndUsageRowsAroundTheCard() {
        val layout = AgentInputLayout(
            AgentInputLayout.Input(width = 393.0, statusText = Size(100, 13), card = oneLine, usageWeek = Size(60, 13), usageContext = Size(70, 13)),
        )
        assertFrame(layout.status?.frame, rect(8, 8, 377, 20))
        assertFrame(layout.status?.dot, rect(24, 13.5, 6, 6))
        assertFrame(layout.status?.text, rect(34, 9.5, 100, 13))
        assertFrame(layout.card, rect(8, 28, 377, 102))
        assertFrame(layout.usage?.frame, rect(8, 130, 377, 20))
        assertFrame(layout.usage?.context, rect(278, 136, 89, 14))
        assertFrame(layout.usage?.contextText, rect(278, 136.5, 70, 13))
        assertFrame(layout.usage?.gauge, rect(353, 136, 14, 14))
        assertFrame(layout.usage?.week, rect(208, 136.5, 60, 13))
        assertSize(layout.size, Size(393, 158))
        assertFrame(layout.inner, rect(8, 8, 377, 142))
    }

    @Test fun testWeekAloneSitsAtTheRightEdge() {
        val layout = AgentInputLayout(AgentInputLayout.Input(width = 393.0, card = oneLine, usageWeek = Size(60, 13)))
        assertFrame(layout.usage?.frame, rect(8, 110, 377, 19))
        assertFrame(layout.usage?.week, rect(307, 116, 60, 13))
        XCTAssertNil(layout.usage?.context)
    }

    @Test fun testStatusTextIsCutAtTheRowsPadding() {
        val layout = AgentInputLayout(AgentInputLayout.Input(width = 393.0, statusText = Size(500, 13), card = oneLine))
        assertFrame(layout.status?.text, rect(34, 9.5, 335, 13))
    }

    @Test fun testWideScreensCentreTheInnerContainer() {
        val layout = AgentInputLayout(
            AgentInputLayout.Input(width = 900.0, paddingH = AgentInputLayout.Metrics.containerPaddingHWide, maxContentWidth = 800.0, card = oneLine),
        )
        assertFrame(layout.card, rect(50, 8, 800, 102))
        assertFrame(layout.autocomplete(rows = 2, border = 0.5), rect(50, -97, 800, 97))
    }

    @Test fun testAutocompleteFloatsAboveTheComposer() {
        val layout = AgentInputLayout(AgentInputLayout.Input(width = 393.0, card = oneLine))
        assertFrame(layout.autocomplete(rows = 3, border = 0.5), rect(16, -145, 361, 145))
        assertFrame(layout.autocomplete(rows = 10, border = 0.5), rect(16, -320, 361, 320))
        assertFrame(layout.autocomplete(rows = 0, border = 0.0), rect(16, 0, 361, 0))
    }
}

class ComposerEditorLayoutTest {
    @Test fun testOverTheKeyboard() {
        val layout = ComposerEditorLayout(Size(393, 852), com.slopus.happy.layout.LayoutInsets(top = 59, bottom = 34), keyboardTop = 516.0)
        assertFrame(layout.collapse, rect(341, 63, 44, 44))
        assertFrame(layout.text, rect(16, 67, 361, 441))
        assertFrame(layout.collapseClearance, rect(325, 0, 36, 40))
    }
}

class ChatFollowMotionTest {
    @Test fun testNewestInserts() {
        assertEquals(listOf("c"), com.slopus.happy.layout.chat.ChatFollowMotion.newestInserts(listOf("b", "a"), listOf("c", "b", "a")))
        assertEquals(emptyList<String>(), com.slopus.happy.layout.chat.ChatFollowMotion.newestInserts(listOf("b", "a"), listOf("b", "a")))
        XCTAssertNil(com.slopus.happy.layout.chat.ChatFollowMotion.newestInserts(listOf("b", "a"), listOf("b", "x", "a")))
    }
}
