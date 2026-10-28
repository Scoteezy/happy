package com.slopus.happy.layout

import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.

class ItemRowLayoutTest {
    val chevron = Size(9, 17)

    @Test fun testTitleOnlyRowIsPaddedPastTheMinimumHeight() {
        val layout = ItemRowLayout(ItemRowLayout.Input(width = 375.0, title = text("Logout", lineHeight = 22.0)))
        // 12 + 22 + 12 = 46, which beats the 44 minimum.
        XCTAssertEqual(layout.rowHeight, 46)
        assertSize(layout.size, Size(375, 46))
        // Title spans to the right section's 8pt margin: 375 - 16 - 16 - 8.
        assertFrame(layout.title, rect(16, 12, 335, 22))
        XCTAssertNil(layout.icon)
        XCTAssertNil(layout.subtitle)
        XCTAssertNil(layout.divider)
    }

    @Test fun testShortContentKeepsTheMinimumHeight() {
        val layout = ItemRowLayout(ItemRowLayout.Input(width = 375.0, title = text("Hi", lineHeight = 14.0)))
        XCTAssertEqual(layout.rowHeight, 44)
        assertFrame(layout.title, rect(16, 15, 335, 14))
    }

    @Test fun testIconSubtitleChevronAndDivider() {
        val layout = ItemRowLayout(ItemRowLayout.Input(
            width = 375.0,
            hasIcon = true,
            title = text("Link New Device", lineHeight = 22.0, maxLines = 1),
            subtitle = text("Scan QR code to link device", lineHeight = 20.0, maxLines = 2),
            accessory = chevron,
            showsDivider = true
        ))
        // Subtitle rows pad 11: 11 + (22 + 2 + 20) + 11 = 66.
        XCTAssertEqual(layout.rowHeight, 66)
        assertFrame(layout.icon, rect(16, 18.5, 29, 29))
        // Text starts after icon and its 12pt margin: 16 + 29 + 12 = 57.
        // Width: 375 - 57 - 16 - 8 - (4 + 9) = 281.
        assertFrame(layout.title, rect(57, 11, 281, 22))
        assertFrame(layout.subtitle, rect(57, 35, 281, 20))
        assertFrame(layout.accessory, rect(350, 24.5, 9, 17))
        // Divider inset 15 + 16 + 29 + 15 = 75, hairline under the row.
        assertFrame(layout.divider, rect(75, 66, 300, 0.33))
        assertSize(layout.size, Size(375, 66.33))
    }

    @Test fun testDividerWithoutIconInsetsBy31() {
        val layout = ItemRowLayout(ItemRowLayout.Input(width = 375.0, title = text("Status", lineHeight = 22.0), showsDivider = true))
        assertFrame(layout.divider, rect(31, 46, 344, 0.33))
    }

    @Test fun testDetailSitsBeforeTheChevron() {
        val layout = ItemRowLayout(ItemRowLayout.Input(
            width = 375.0,
            title = text("Version", lineHeight = 22.0),
            detail = text("1.8.0", lineHeight = 22.0, maxLines = 1),
            accessory = chevron
        ))
        // Right section: detail 40 + 6 + 4 + chevron 9 = 59.
        assertFrame(layout.detail, rect(300, 12, 40, 22))
        assertFrame(layout.accessory, rect(350, 14.5, 9, 17))
        assertFrame(layout.title, rect(16, 12, 276, 22))
    }

    @Test fun testDetailWithoutChevronHugsTheTrailingPadding() {
        val layout = ItemRowLayout(ItemRowLayout.Input(width = 375.0, title = text("Status", lineHeight = 22.0), detail = text("Active", lineHeight = 22.0, maxLines = 1)))
        assertFrame(layout.detail, rect(311, 12, 48, 22))
        XCTAssertNil(layout.accessory)
    }

    @Test fun testLongDetailIsCappedSoTheTitleStays() {
        val layout = ItemRowLayout(ItemRowLayout.Input(
            width = 375.0,
            title = text("Analytics ID", lineHeight = 22.0),
            detail = text("a".repeat(100), lineHeight = 22.0, maxLines = 1),
            accessory = chevron
        ))
        // (335 - 13 - 6) × 0.6 = 189.6
        XCTAssertEqual(layout.detail?.width ?: 0, 189.6, accuracy = 0.001)
        XCTAssertEqual(layout.title.width, 335 - (189.6 + 6 + 13), accuracy = 0.001)
    }

    @Test fun testTitleWrapsToTwoLinesWithoutSubtitle() {
        val layout = ItemRowLayout(ItemRowLayout.Input(width = 375.0, title = text("x".repeat(60), lineHeight = 22.0, maxLines = 2)))
        XCTAssertEqual(layout.rowHeight, 68)
        assertFrame(layout.title, rect(16, 12, 335, 44))
    }

    @Test fun testZeroWidthDoesNotGoNegative() {
        val layout = ItemRowLayout(ItemRowLayout.Input(width = 0.0, hasIcon = true, title = text("Title", lineHeight = 22.0), accessory = chevron, showsDivider = true))
        XCTAssertEqual(layout.title.width, 0)
        XCTAssertEqual(layout.divider?.width, 0)
    }
}

class ItemGroupLayoutTest {
    @Test fun testTitledGroupWithFooter() {
        val layout = ItemGroupLayout(ItemGroupLayout.Input(
            width = 375.0,
            title = text("Backup", lineHeight = 18.0),
            footer = text("f".repeat(60), lineHeight = 18.0),
            rows = listOf(fixed(343, 46.33), fixed(343, 46))
        ))
        assertFrame(layout.header, rect(32, 35, 311, 18))
        // Header: 35 + 18 + 6 = 59.
        assertFrame(layout.card, rect(16, 59, 343, 92.33))
        assertFrame(layout.rows[0], rect(0, 0, 343, 46.33))
        assertFrame(layout.rows[1], rect(0, 46.33, 343, 46))
        // 60 characters wrap at 38 per line: two 18pt lines.
        assertFrame(layout.footer, rect(32, 157.33, 311, 36))
        assertSize(layout.size, Size(375, 151.33 + 6 + 36 + 8))
    }

    @Test fun testUntitledGroupStartsTwentyDown() {
        val layout = ItemGroupLayout(ItemGroupLayout.Input(width = 375.0, rows = listOf(fixed(343, 46))))
        XCTAssertNil(layout.header)
        XCTAssertNil(layout.footer)
        assertFrame(layout.card, rect(16, 20, 343, 46))
        assertSize(layout.size, Size(375, 66))
    }

    @Test fun testRowsAreMeasuredAtTheCardWidth() {
        val offered = mutableListOf<Double>()
        val row = Measurable { width ->
            offered.add(width)
            Size(width, 44)
        }
        ItemGroupLayout(ItemGroupLayout.Input(width = 375.0, rows = listOf(row)))
        XCTAssertEqual(offered, listOf(343.0))
        XCTAssertEqual(ItemGroupLayout.cardWidth(width = 375.0), 343)
    }

    @Test fun testGroupCentresAtMaxWidth() {
        val layout = ItemGroupLayout(ItemGroupLayout.Input(width = 1000.0, title = text("About", lineHeight = 18.0), rows = listOf(block(height = 44.0)), maxWidth = 600.0))
        assertFrame(layout.header, rect(232, 35, 536, 18))
        assertFrame(layout.card, rect(216, 59, 568, 44))
        XCTAssertEqual(layout.size.width, 1000)
    }

    @Test fun testEmptyGroupHasAnEmptyCard() {
        val layout = ItemGroupLayout(ItemGroupLayout.Input(width = 375.0, rows = emptyList()))
        assertFrame(layout.card, rect(16, 20, 343, 0))
    }
}

class GroupedListLayoutTest {
    @Test fun testSectionsStackBelowTheBarAndFillTheViewport() {
        val layout = GroupedListLayout(GroupedListLayout.Input(size = phone, safeArea = phoneUnderBar, sections = listOf(block(height = 150.0), block(height = 100.0))))
        assertFrame(layout.sections[0], rect(0, 103, 393, 150))
        assertFrame(layout.sections[1], rect(0, 253, 393, 100))
        // Short content still fills the screen.
        assertSize(layout.contentSize, phone)
    }

    @Test fun testTallContentAddsBottomPaddingAndHomeIndicator() {
        val layout = GroupedListLayout(GroupedListLayout.Input(size = phone, safeArea = phoneUnderBar, sections = listOf(block(height = 500.0), block(height = 400.0))))
        // 103 + 900 + 34 padding + 34 home indicator.
        XCTAssertEqual(layout.contentSize.height, 1071)
    }

    @Test fun testLandscapeInsetsSectionsBySafeArea() {
        val size = Size(852, 393)
        val insets = LayoutInsets(top = 32.0, left = 59.0, bottom = 21.0, right = 59.0)
        val layout = GroupedListLayout(GroupedListLayout.Input(size = size, safeArea = insets, sections = listOf(block(height = 100.0))))
        assertFrame(layout.sections[0], rect(59, 32, 734, 100))
        XCTAssertEqual(GroupedListLayout.contentWidth(size = size, safeArea = insets), 734)
    }
}

class SettingsHeaderLayoutTest {
    @Test fun testLogoCard() {
        val layout = SettingsHeaderLayout(width = 375.0)
        // 24 + 90 + 12 + 24 = 150, 16 below the top.
        assertFrame(layout.card, rect(16, 16, 343, 150))
        assertFrame(layout.logo, rect(21.5, 24, 300, 90))
        assertSize(layout.size, Size(375, 166))
    }

    @Test fun testLogoShrinksOnNarrowScreens() {
        val layout = SettingsHeaderLayout(width = 320.0)
        assertFrame(layout.logo, rect(16, 24, 256, 76.8))
        XCTAssertEqual(layout.card.height, 24 + 76.8 + 12 + 24, accuracy = 0.001)
    }
}

class SecretKeyCardLayoutTest {
    @Test fun testLabelIconAndWrappedKey() {
        val key = "X".repeat(59)
        val layout = SecretKeyCardLayout(
            width = 343.0,
            label = text("SECRET KEY (TAP TO COPY)", lineHeight = 13.0, maxLines = 1),
            value = text(key, lineHeight = 20.0)
        )
        // Header row is the icon's 18; the 13pt label centres in it.
        assertFrame(layout.label, rect(16, 16.5, 285, 13))
        assertFrame(layout.icon, rect(309, 14, 18, 18))
        // 59 characters at 38 per line: two 20pt lines, 10 under the header.
        assertFrame(layout.value, rect(16, 42, 311, 40))
        assertSize(layout.size, Size(343, 96))
    }
}
