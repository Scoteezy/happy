package com.slopus.happy.layout

import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/SettingsLayoutTests.swift and
// SettingsPageLayoutTests.swift.

class SettingsPageLayoutTest {
    @Test fun testProfileHeader() {
        val layout = SettingsProfileHeaderLayout(
            width = 393.0,
            name = text("Ada Lovelace", lineHeight = 25, maxLines = 1),
            bio = text("Math", lineHeight = 19),
        )
        assertFrame(layout.card, rect(16, 16, 361, 206))
        assertFrame(layout.avatar, rect(135.5, 24, 90, 90))
        assertFrame(layout.name, rect(132.5, 126, 96, 25))
        assertFrame(layout.bio, rect(164.5, 155, 32, 19))
        assertSize(layout.size, Size(393, 222))

        val plain = SettingsProfileHeaderLayout(width = 393.0, name = text("Ada Lovelace", lineHeight = 25, maxLines = 1), bio = null)
        XCTAssertNil(plain.bio)
        assertFrame(plain.card, rect(16, 16, 361, 183))
    }

    @Test fun testDropdownValue() {
        val layout = DropdownValueLayout(preview = Size(46, 28), label = text("Gray", lineHeight = 20, maxLines = 1))
        assertFrame(layout.preview, rect(0, 0, 46, 28))
        assertFrame(layout.label, rect(54, 4, 32, 20))
        assertFrame(layout.chevron, rect(94, 5, 18, 18))
        assertSize(layout.size, Size(112, 28))

        val long = DropdownValueLayout(preview = Size(46, 28), label = text("Something very long name", lineHeight = 20, maxLines = 1))
        XCTAssertEqual(long.label.width, 104.0, "the name gives way first")
        XCTAssertEqual(long.size.width, 184.0)
    }

    @Test fun testOptionRowsAndList() {
        val row = OptionRowLayout(width = 361.0, preview = Size(46, 28), title = text("Blue", lineHeight = 21, maxLines = 1))
        assertFrame(row.preview, rect(16, 10, 46, 28))
        assertFrame(row.title, rect(74, 13.5, 239, 21))
        assertFrame(row.check, rect(325, 14, 20, 20))
        assertSize(row.size, Size(361, 48))

        val list = OptionListLayout(width = 361.0, rows = listOf(block(48), block(48), block(48)))
        XCTAssertEqual(list.rows.map { it.minY }, listOf(6.0, 54.0, 102.0))
        assertSize(list.size, Size(361, 156))
    }

    @Test fun testBubblePreviewLines() {
        XCTAssertEqual(BubblePreviewLayout.lines, listOf(rect(19, 9, 18, 3), rect(11, 16, 26, 3)))
    }

    @Test fun testConnectCommandIsCentred() {
        val layout = ConnectCommandLayout(
            size = phone,
            safeArea = phoneUnderBar,
            title = text("Connect Claude", lineHeight = 23),
            body = text("Run the following command in your terminal:", lineHeight = 19),
            command = text("$ happy connect claude", lineHeight = 19, maxLines = 1),
        )
        assertFrame(layout.title, rect(140.5, 392, 112, 23))
        assertFrame(layout.body, rect(24.5, 435, 344, 19))
        assertFrame(layout.terminal, rect(56.5, 478, 280, 51))
        assertFrame(layout.command, rect(16, 16, 176, 19))
    }

    @Test fun testChangelogBodyListsAndParagraphs() {
        val items = listOf(
            ChangelogBodyLayout.Item(text("aaaa", lineHeight = 25), bulleted = true),
            ChangelogBodyLayout.Item(text("bb", lineHeight = 25), bulleted = true),
            ChangelogBodyLayout.Item(text("cc", lineHeight = 25), bulleted = false),
        )
        val layout = ChangelogBodyLayout(width = 361.0, summary = null, items = items, bullet = Size(7, 25))
        XCTAssertNil(layout.summary)
        assertFrame(layout.bullets[0], rect(16, 15, 7, 25))
        assertFrame(layout.texts[0], rect(31, 14, 314, 25))
        assertFrame(layout.bullets[1], rect(16, 47, 7, 25))
        assertFrame(layout.texts[1], rect(31, 46, 314, 25))
        XCTAssertNil(layout.bullets[2])
        assertFrame(layout.texts[2], rect(16, 88, 329, 25))
        assertSize(layout.size, Size(361, 129))

        val summarised = ChangelogBodyLayout(width = 361.0, summary = text("s", lineHeight = 23), items = items, bullet = Size(7, 25))
        assertFrame(summarised.summary, rect(16, 14, 329, 23))
        XCTAssertEqual(summarised.texts[0].minY, 53.0)
    }
}

class SettingsPagesLayoutTest {
    @Test fun testChangelogTitle() {
        val layout = ChangelogTitleLayout(title = text("What's New", lineHeight = 21, maxLines = 1), maxWidth = 240.0)
        assertFrame(layout.title, rect(0, 9.5, 80, 21))
        assertFrame(layout.image, rect(86, 0, 40, 40))
        assertSize(layout.size, Size(126, 40))

        val narrow = ChangelogTitleLayout(title = text("What's New", lineHeight = 21, maxLines = 1), maxWidth = 100.0)
        XCTAssertEqual(narrow.title.width, 54.0, "the words give way, the picture keeps its size")
        assertFrame(narrow.image, rect(60, 0, 40, 40))
    }

    @Test fun testCenteredMessage() {
        val layout = CenteredMessageLayout(size = phone, safeArea = phoneUnderBar, message = text("No changelog entries available.", lineHeight = 24))
        assertFrame(layout.message, rect(72.5, 448.5, 248, 24))

        val wrapped = CenteredMessageLayout(size = phone, safeArea = phoneUnderBar, message = text("x".repeat(50), lineHeight = 24))
        assertFrame(wrapped.message, rect(40.5, 436.5, 312, 48))
    }

    /** Appearance › User Bubble Color: the dropdown value replaces the chevron, and the two-line subtitle sets the height. */
    @Test fun testDropdownRow() {
        val value = DropdownValueLayout(preview = BubblePreviewLayout.size, label = text("Gray", lineHeight = 20, maxLines = 1))
        val row = ItemRowLayout(ItemRowLayout.Input(
            width = 361.0,
            hasIcon = true,
            title = text("User Bubble Color", lineHeight = 22, maxLines = 1),
            subtitle = text("Make your messages easier to spot in long chats", lineHeight = 20, maxLines = 2),
            trailing = value.size,
        ))
        XCTAssertEqual(row.rowHeight, 86)
        assertFrame(row.icon, rect(16, 28.5, 29, 29))
        assertFrame(row.title, rect(57, 11, 168, 22))
        assertFrame(row.subtitle, rect(57, 35, 168, 40))
        assertFrame(row.trailing, rect(233, 29, 112, 28))
        XCTAssertNil(row.accessory)
    }

    /** Appearance › Avatar Style: a 28dp avatar in the value and the options. */
    @Test fun testAvatarStyleDropdown() {
        val value = DropdownValueLayout(preview = Size(28, 28), label = text("Brutalist", lineHeight = 20, maxLines = 1))
        assertFrame(value.preview, rect(0, 0, 28, 28))
        assertFrame(value.label, rect(36, 4, 72, 20))
        assertFrame(value.chevron, rect(116, 5, 18, 18))
        assertSize(value.size, Size(134, 28))

        val option = OptionRowLayout(width = 361.0, preview = Size(28, 28), title = text("Pixelated", lineHeight = 21, maxLines = 1))
        assertFrame(option.preview, rect(16, 10, 28, 28))
        assertFrame(option.title, rect(56, 13.5, 257, 21))
        assertFrame(option.check, rect(325, 14, 20, 20))
        assertSize(option.size, Size(361, 48))
    }

    /** Settings › Account › Registered Tokens: a subtitle with its own line breaks grows the row, and the icon stays centred. */
    @Test fun testPushTokenRow() {
        val subtitle = Measurable { Size(200, 100) }
        val row = ItemRowLayout(ItemRowLayout.Input(
            width = 361.0,
            hasIcon = true,
            title = text("abcdef…klmnop", lineHeight = 22, maxLines = 1),
            subtitle = subtitle,
            showsDivider = true,
        ))
        XCTAssertEqual(row.rowHeight, 146)
        assertFrame(row.icon, rect(16, 58.5, 29, 29))
        assertFrame(row.subtitle, rect(57, 35, 280, 100))
        assertFrame(row.divider, rect(75, 146, 286, 0.33))
    }
}

class SettingsTokenTest {
    @Test fun testBubbleGlass() {
        val gray = BubblePalette.Gray.glass
        XCTAssertEqual(gray.background.light, RGBA.hex("#f0eee6")!!.withAlpha(0.48))
        XCTAssertEqual(gray.background.dark, RGBA.hex("#2C2C2E")!!.withAlpha(0.34))
        XCTAssertEqual(gray.border.light, RGBA(1.0, 1.0, 1.0, 0.76))
        XCTAssertEqual(gray.border.dark, RGBA(1.0, 1.0, 1.0, 0.18))

        val blue = BubblePalette.Blue.glass
        XCTAssertEqual(blue.background.light, RGBA.hex("#E8F2FF")!!.withAlpha(0.54))
        XCTAssertEqual(blue.background.dark, RGBA.hex("#17324D")!!.withAlpha(0.46))
        XCTAssertEqual(blue.border.light, RGBA.hex("#9CC9FF")!!.withAlpha(0.70))
        XCTAssertEqual(blue.border.dark, RGBA.hex("#2F6EA8")!!.withAlpha(0.56))
    }

    @Test fun testPressedOption() {
        XCTAssertEqual(Palette.optionPressed.light, RGBA(1.0, 1.0, 1.0, 0.84))
        XCTAssertEqual(Palette.optionPressed.dark, RGBA(28.0 / 255, 28.0 / 255, 28.0 / 255, 0.68))
    }
}
