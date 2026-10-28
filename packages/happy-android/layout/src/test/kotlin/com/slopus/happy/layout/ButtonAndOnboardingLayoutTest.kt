package com.slopus.happy.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/ButtonAndOnboardingLayoutTests.swift.

class RoundButtonLayoutTest {
    @Test fun metricsFollowHappyApp() {
        assertEquals(RoundButtonMetrics(50.0, 17.0, 22.0, 24.0, 0.0), RoundButtonMetrics.of(RoundButtonSize.Large))
        assertEquals(RoundButtonMetrics(44.0, 15.0, 20.0, 20.0, 4.0), RoundButtonMetrics.of(RoundButtonSize.Normal))
        assertEquals(RoundButtonMetrics(32.0, 13.0, 17.0, 14.0, 8.0), RoundButtonMetrics.of(RoundButtonSize.Small))
        for (size in RoundButtonSize.entries) {
            val m = RoundButtonMetrics.of(size)
            assertEquals(swiftRound(m.fontSize * 1.3), m.lineHeight, 0.0)
        }
    }

    @Test fun largeFilledPill() {
        val layout = RoundButtonLayout(RoundButtonLayout.Input(345.0, label = text("Create account", 22, maxLines = 1)))
        assertSize(layout.size, Size(345, 50))
        assertFrame(layout.label, rect(24, 14, 297, 22))
        assertEquals(25.0, layout.cornerRadius, 0.0)
        assertNull(layout.spinner)
    }

    @Test fun filledLabelStaysOnOneLine() {
        val layout = RoundButtonLayout(RoundButtonLayout.Input(200.0, RoundButtonSize.Normal, label = text("w".repeat(40), 20, maxLines = 1)))
        assertSize(layout.size, Size(200, 44))
        assertFrame(layout.label, rect(20, 12, 160, 20))
    }

    @Test fun invertedLabelWrapsAndGrows() {
        val layout = RoundButtonLayout(
            RoundButtonLayout.Input(327.0, RoundButtonSize.Normal, RoundButtonDisplay.Inverted, text("Can’t scan QR code? Paste link instead", 20)),
        )
        assertSize(layout.size, Size(327, 56))
        assertFrame(layout.label, rect(20, 8, 287, 40))
        assertEquals(28.0, layout.cornerRadius, 0.0)
    }

    @Test fun shortInvertedLabelKeepsTheControlHeight() {
        val layout = RoundButtonLayout(RoundButtonLayout.Input(280.0, RoundButtonSize.Normal, RoundButtonDisplay.Inverted, text("Use a secret key instead", 20)))
        assertSize(layout.size, Size(280, 44))
        assertFrame(layout.label, rect(20, 12, 240, 20))
    }

    @Test fun minimumWidth() {
        val layout = RoundButtonLayout(RoundButtonLayout.Input(30.0, RoundButtonSize.Small, label = text("OK", 17, maxLines = 1)))
        assertEquals(64.0, layout.size.width, 0.0)
    }

    @Test fun inlineSpinnerCentresWithItsTitle() {
        val layout = RoundButtonLayout(
            RoundButtonLayout.Input(327.0, RoundButtonSize.Normal, label = text("Linking…", 20, maxLines = 1), loading = RoundButtonLayout.Loading.Inline(Size(20, 20))),
        )
        assertFrame(layout.spinner, rect(116.5, 12, 20, 20))
        assertFrame(layout.label, rect(146.5, 12, 64, 20))
    }

    @Test fun overlaySpinnerSitsWhereTheLabelWas() {
        val layout = RoundButtonLayout(
            RoundButtonLayout.Input(327.0, label = text("Restore", 22, maxLines = 1), loading = RoundButtonLayout.Loading.Overlay(Size(20, 20))),
        )
        assertFrame(layout.spinner, rect(153.5, 15, 20, 20))
        assertFrame(layout.label, rect(24, 14, 279, 22))
    }
}

class OnboardingLayoutTest {
    private val help = GetHelpButtonLayout(Size(60, 20))

    private fun input(
        size: Size = phone,
        safeArea: LayoutInsets = phoneUnderBar,
        content: Double = 300.0,
        tertiary: Measurable? = null,
        glass: Boolean = false,
        accessory: Double? = null,
    ) = OnboardingLayout.Input(size, safeArea, block(content), block(50), block(44), tertiary, help.size, glass, accessory)

    @Test fun getHelpPill() {
        assertSize(help.size, Size(107, 36))
        assertFrame(help.icon, rect(12, 0, 17, 36))
        assertFrame(help.label, rect(35, 8, 60, 20))
    }

    @Test fun blockIsCentredBetweenBarAndFooter() {
        val layout = OnboardingLayout(input())
        assertFrame(layout.help, rect(270, 770, 107, 36))
        assertFrame(layout.scroll, rect(0, 103, 393, 667))
        assertFrame(layout.content, rect(24, 128.5, 345, 300))
        assertFrame(layout.primary, rect(24, 440.5, 345, 50))
        assertFrame(layout.secondary, rect(24, 494.5, 345, 44))
        assertSize(layout.contentSize, Size(393, 667))
    }

    @Test fun aTertiaryActionSitsUnderTheSecondaryAndTheBlockStaysCentred() {
        val layout = OnboardingLayout(input(tertiary = block(44)))
        assertFrame(layout.content, rect(24, 104.5, 345, 300))
        assertFrame(layout.primary, rect(24, 416.5, 345, 50))
        assertFrame(layout.secondary, rect(24, 470.5, 345, 44))
        assertEquals(rect(24, 518.5, 345, 44), layout.tertiary)
        assertSize(layout.contentSize, Size(393, 667))
        assertNull(OnboardingLayout(input()).tertiary)
    }

    @Test fun anAccessorySitsJustAboveThePrimaryAcrossTheBlock() {
        val layout = OnboardingLayout(input(accessory = 36.0))
        assertFrame(layout.content, rect(24, 100.5, 345, 300))
        assertEquals(rect(24, 416.5, 345, 36), layout.accessory)
        assertFrame(layout.primary, rect(24, 468.5, 345, 50))
        assertFrame(layout.secondary, rect(24, 522.5, 345, 44))
    }

    @Test fun glassActionsAreSpacedAsPills() {
        val layout = OnboardingLayout(input(tertiary = block(44), glass = true))
        assertFrame(layout.content, rect(24, 96.5, 345, 300))
        assertFrame(layout.primary, rect(24, 408.5, 345, 50))
        assertFrame(layout.secondary, rect(24, 470.5, 345, 44))
        assertEquals(rect(24, 526.5, 345, 44), layout.tertiary)
    }

    @Test fun tallBlockScrolls() {
        val layout = OnboardingLayout(input(content = 700.0))
        assertClose(8, layout.content.minY)
        assertClose(818, layout.secondary.maxY)
        assertClose(826, layout.contentSize.height)
    }

    @Test fun blockCapsAt480OnTablets() {
        val size = Size(1024, 1366)
        val insets = LayoutInsets(top = 74, bottom = 20)
        val layout = OnboardingLayout(input(size, insets))
        assertClose(432, OnboardingLayout.blockWidth(size, insets))
        assertClose(296, layout.content.minX)
        assertClose(432, layout.content.width)
    }

    @Test fun landscapeRespectsSideInsets() {
        val size = Size(852, 393)
        val insets = LayoutInsets(top = 32, left = 59, bottom = 21, right = 59)
        val layout = OnboardingLayout(input(size, insets, content = 100.0))
        assertClose(59 + 127 + 24, layout.content.minX)
        assertClose(852 - 59 - 16, layout.help.maxX)
    }

    @Test fun buttonsLandInTheSamePlaceForEitherContent() {
        val reserved = Measurable.largest(listOf(block(276), block(274)))
        assertClose(276, reserved.height(345.0))
    }

    @Test fun welcomeContent() {
        val layout = WelcomeContentLayout(
            345.0,
            Measurable { w -> Size(w, 68.0) },
            listOf(text("End-to-end encrypted", 24), text("No email, no password", 24), text("Open source, MIT license", 24)),
        )
        assertFrame(layout.wordmark, rect(0, 0, 229, 84))
        assertFrame(layout.title, rect(0, 112, 345, 68))
        assertFrame(layout.props[0], rect(0, 192, 345, 24))
        assertFrame(layout.props[1], rect(0, 216, 345, 24))
        assertFrame(layout.props[2], rect(0, 240, 345, 24))
        assertSize(layout.size, Size(345, 276))
    }

    @Test fun wordmarkScalesDownWhenNarrow() {
        val layout = WelcomeContentLayout(200.0, block(34), emptyList())
        assertFrame(layout.wordmark, rect(0, 0, 200, 84.0 * 200 / 229))
    }

    @Test fun linkComputerChecklist() {
        val layout = LinkComputerContentLayout(
            345.0,
            text("Link Your Computer", 34),
            listOf(
                LinkComputerContentLayout.Row(text("Install Happy on your computer", 22), text("b".repeat(70), 21)),
                LinkComputerContentLayout.Row(text("Open Happy and choose Link Mobile App", 22), text("b".repeat(70), 21)),
            ),
            20.0,
        )
        assertFrame(layout.title, rect(0, 0, 345, 34))
        assertFrame(layout.rows[0].head, rect(0, 58, 345, 32))
        assertFrame(layout.rows[0].box, rect(0, 61, 26, 26))
        assertFrame(layout.rows[0].title, rect(38, 63, 307, 22))
        assertFrame(layout.rows[0].body, rect(38, 96, 307, 42))
        assertFrame(layout.rows[1].head, rect(0, 158, 345, 32))
        assertFrame(layout.rows[1].body, rect(38, 196, 307, 42))
        assertFrame(layout.error, rect(0, 254, 345, 20))
        assertSize(layout.size, Size(345, 274))
    }

    @Test fun navigationTitleWithServerSubtitle() {
        val layout = NavigationTitleLayout(Size(80, 20), Size(150, 14), 200.0)
        assertSize(layout.size, Size(150, 34))
        assertFrame(layout.title, rect(35, 0, 80, 20))
        assertFrame(layout.subtitle, rect(0, 20, 150, 14))
    }

    @Test fun navigationSubtitleOnlyIsClampedToTheBar() {
        val layout = NavigationTitleLayout(null, Size(300, 14), 200.0)
        assertNull(layout.title)
        assertFrame(layout.subtitle, rect(0, 0, 200, 14))
    }
}
