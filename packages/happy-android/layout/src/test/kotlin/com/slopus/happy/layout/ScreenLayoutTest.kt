package com.slopus.happy.layout

import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.

class RestoreQRLayoutTest {
    fun input(size: Size = phone, safeArea: LayoutInsets = phoneUnderBar, showsCode: Boolean): RestoreQRLayout.Input {
        return RestoreQRLayout.Input(
            size = size,
            safeArea = safeArea,
            instruction = text("Scan this code with your other phone.", lineHeight = 22.0),
            path = text("Happy › Settings › Account › Link New Device", lineHeight = 20.0),
            showsCode = showsCode,
            button = block(height = 44.0)
        )
    }

    @Test fun testCodeColumnIsCentred() {
        val layout = RestoreQRLayout(input(showsCode = true))
        // Column 22 + 8 + 40 + 32 + 284 + 32 + 44 = 462 in 715 visible:
        // 103 + 24 + (715 - 48 - 462) / 2 = 229.5.
        assertFrame(layout.instruction, rect(32, 229.5, 329, 22))
        assertFrame(layout.path, rect(32, 259.5, 329, 40))
        // 260 code on a 12pt white frame.
        assertFrame(layout.codeContainer, rect(54.5, 331.5, 284, 284))
        assertFrame(layout.code, rect(66.5, 343.5, 260, 260))
        // Button column is 280 wide, 32 under the code.
        assertFrame(layout.button, rect(56.5, 647.5, 280, 44))
        assertSize(layout.contentSize, phone)
    }

    @Test fun testPlaceholderIsTheCodeSize() {
        val layout = RestoreQRLayout(input(showsCode = false))
        XCTAssertNil(layout.code)
        XCTAssertEqual(layout.codeContainer.size, Size(260, 260))
        XCTAssertEqual(layout.codeContainer.minX, 66.5)
    }

    @Test fun testCodeShrinksOnSmallPhones() {
        val layout = RestoreQRLayout(input(size = Size(320, 568), safeArea = LayoutInsets(top = 64.0), showsCode = true))
        // 320 - 64 = 256 wide: the code gets 256 - 24.
        XCTAssertEqual(layout.code?.width, 232)
        XCTAssertEqual(layout.codeContainer.width, 256)
        XCTAssertEqual(layout.button.width, 256)
        // Taller than the screen, so it scrolls from the top padding.
        XCTAssertEqual(layout.instruction.minY, 64 + 24)
        XCTAssertEqual(layout.contentSize.height, layout.button.maxY + 24)
    }
}

class RestoreSecretKeyLayoutTest {
    fun input(keyboard: Double = 0.0, fieldLines: Int = 1): RestoreSecretKeyLayout.Input {
        return RestoreSecretKeyLayout.Input(
            size = phone,
            safeArea = phoneUnderBar,
            keyboardOverlap = keyboard,
            instruction = text("i".repeat(82), lineHeight = 22.0),
            fieldText = Measurable { width -> Size(width, (fieldLines).toDouble() * 22) },
            button = block(height = 50.0)
        )
    }

    @Test fun testFormBelowTheBar() {
        val layout = RestoreSecretKeyLayout(input())
        assertFrame(layout.scroll, rect(0, 0, 393, 852))
        assertFrame(layout.instruction, rect(24, 127, 345, 44))
        // An empty field keeps its 120 minimum.
        assertFrame(layout.field, rect(24, 191, 345, 120))
        assertFrame(layout.button, rect(24, 335, 345, 50))
        XCTAssertEqual(layout.contentSize.height, 385 + 24 + 34)
        XCTAssertEqual(RestoreSecretKeyLayout.fieldTextWidth(size = phone, safeArea = phoneUnderBar), 313)
    }

    @Test fun testKeyboardShrinksTheScrollView() {
        val layout = RestoreSecretKeyLayout(input(keyboard = 336.0))
        assertFrame(layout.scroll, rect(0, 0, 393, 516))
        // The keyboard covers the home indicator inset.
        XCTAssertEqual(layout.contentSize.height, 385 + 24)
    }

    @Test fun testFieldGrowsWithText() {
        val layout = RestoreSecretKeyLayout(input(fieldLines = 6))
        XCTAssertEqual(layout.field.height, 6 * 22 + 32)
        XCTAssertEqual(layout.button.minY, layout.field.maxY + 24)
    }
}

class ServerFormLayoutTest {
    @Test fun testDefaultServerForm() {
        val layout = ServerFormLayout(ServerFormLayout.Input(width = 343.0, label = text("SERVER URL", lineHeight = 15.0), fieldLineHeight = 20.0, save = block(height = 44.0)))
        assertFrame(layout.label, rect(16, 12, 311, 15))
        // 20 + 12 + 12 = 44, the minimum anyway.
        assertFrame(layout.field, rect(16, 35, 311, 44))
        // 8 under the field and 4 above the button column.
        assertFrame(layout.save, rect(16, 91, 311, 44))
        XCTAssertNil(layout.reset)
        XCTAssertNil(layout.status)
        assertSize(layout.size, Size(343, 159))
    }

    @Test fun testCustomServerWithError() {
        val layout = ServerFormLayout(ServerFormLayout.Input(
            width = 343.0,
            label = text("SERVER URL", lineHeight = 15.0),
            fieldLineHeight = 20.0,
            error = text("Not a valid Happy Server", lineHeight = 15.0),
            save = block(height = 44.0),
            reset = block(height = 44.0),
            status = text("Currently using custom server", lineHeight = 15.0)
        ))
        assertFrame(layout.error, rect(16, 87, 311, 15))
        assertFrame(layout.save, rect(16, 118, 311, 44))
        assertFrame(layout.reset, rect(16, 166, 311, 44))
        assertFrame(layout.status, rect(16, 222, 311, 15))
        assertSize(layout.size, Size(343, 249))
    }

    @Test fun testValidatingLineFollowsTheError() {
        val layout = ServerFormLayout(ServerFormLayout.Input(
            width = 343.0,
            label = text("SERVER URL", lineHeight = 15.0),
            fieldLineHeight = 20.0,
            error = text("x", lineHeight = 15.0),
            validating = text("Validating server...", lineHeight = 15.0),
            save = block(height = 44.0)
        ))
        assertFrame(layout.validating, rect(16, 114, 311, 15))
        XCTAssertEqual(layout.save.minY, 145)
    }

    @Test fun testServerScreenComposesFormGroupAndList() {
        val cardWidth = ItemGroupLayout.cardWidth(width = GroupedListLayout.contentWidth(size = phone, safeArea = phoneUnderBar))
        XCTAssertEqual(cardWidth, 361)
        val form = Measurable { width ->
            ServerFormLayout(ServerFormLayout.Input(width = width, label = text("SERVER URL", lineHeight = 15.0), fieldLineHeight = 20.0, save = block(height = 44.0))).size
        }
        val group = Measurable { width ->
            ItemGroupLayout(ItemGroupLayout.Input(width = width, footer = text("f".repeat(64), lineHeight = 18.0), rows = listOf(form))).size
        }
        val list = GroupedListLayout(GroupedListLayout.Input(size = phone, safeArea = phoneUnderBar, sections = listOf(group)))
        // 20 above the card, the 159 form, then the two-line footer: 6 + 36 + 8.
        assertFrame(list.sections[0], rect(0, 103, 393, 229))
    }
}

class GeometryTest {
    @Test fun testMeasurableNeverExceedsTheOffer() {
        val wide = Measurable.fixed(500, 20)
        XCTAssertEqual(wide.size(100), Size(100, 20))
        XCTAssertEqual(wide.size(-5), Size(0, 20))
        val negative = Measurable { _ -> Size(-1, -1) }
        XCTAssertEqual(negative.size(10), Size.Zero)
    }

    @Test fun testLargestTakesEachDimensionsMaximum() {
        val largest = Measurable.largest(listOf(Measurable.fixed(10, 50), Measurable.fixed(40, 20)))
        XCTAssertEqual(largest.size(100), Size(40, 50))
        XCTAssertEqual(Measurable.largest(emptyList()).size(100), Size.Zero)
    }

    @Test fun testPixelSnappingRoundsEdges() {
        XCTAssertEqual(PixelGrid.snap(rect(10.2, 20.6, 100.5, 0.33), 2.0), rect(10, 20.5, 100.5, 0.5))
        XCTAssertEqual(PixelGrid.snap(rect(10.2, 20.6, 100.5, 0.33), 3.0).height, 1.0 / 3, accuracy = 0.0001)
        // Adjacent frames still meet after snapping.
        val top = PixelGrid.snap(rect(0, 0, 10, 46.33), 3.0)
        val bottom = PixelGrid.snap(rect(0, 46.33, 10, 46), 3.0)
        XCTAssertEqual(top.maxY, bottom.minY, accuracy = 0.0001)
        XCTAssertEqual(PixelGrid.snap(Rect.Zero, 2.0), Rect.Zero)
    }

    @Test fun testInsets() {
        val insets = LayoutInsets(top = 1.0, left = 2.0, bottom = 3.0, right = 4.0)
        XCTAssertEqual(insets.horizontal, 6)
        XCTAssertEqual(insets.vertical, 4)
    }
}
