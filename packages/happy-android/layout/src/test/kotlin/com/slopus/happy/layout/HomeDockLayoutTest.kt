package com.slopus.happy.layout

import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/HomeDockLayoutTests.swift.

class HomeDockLayoutTest {
    private fun dock(entry: String = "Plan, ask, build…", safeBottom: Double = 34.0) =
        HomeDockLayout(HomeDockLayout.Input(size = phone, safeBottom = safeBottom, hairline = 0.5, entry = text(entry, lineHeight = 22, maxLines = 1)))

    @Test fun restingDockSitsOnTheHomeIndicator() {
        val layout = dock()
        // 8 above the capsule, 56 of capsule, the 34 home indicator below.
        assertFrame(layout.dock, rect(0, 754, 393, 98))
        assertFrame(layout.composer, rect(16, 762, 361, 56))
        // From the capsule's middle down.
        assertFrame(layout.scrim, rect(0, 790, 393, 62))
    }

    @Test fun capsuleContents() {
        val layout = dock()
        // Inside the 0.5 rim: 7 of padding, the 42 button at the end.
        assertFrame(layout.send, rect(327.5, 769, 42, 42))
        // The text box stops 8 + 4 short of the button.
        assertFrame(layout.entry, rect(23.5, 762.5, 292, 55))
        // The text starts at the tangent: 7 + 21 in from the rim.
        assertFrame(layout.entryText, rect(44.5, 779, 136, 22))
    }

    @Test fun anAccessorySitsBeforeSendAndTheEntryGivesWay() {
        val layout = HomeDockLayout(HomeDockLayout.Input(size = phone, safeBottom = 34.0, hairline = 0.5, entry = text("Plan, ask, build…", lineHeight = 22, maxLines = 1), accessory = Size(120, 32)))
        // Where the entry used to end, centred on the capsule.
        assertFrame(layout.accessory, rect(195.5, 774, 120, 32))
        assertFrame(layout.entry, rect(23.5, 762.5, 168, 55))
        assertFrame(layout.send, rect(327.5, 769, 42, 42))
        // Never more than half the row.
        val wide = HomeDockLayout(HomeDockLayout.Input(size = phone, safeBottom = 34.0, hairline = 0.5, entry = text("Plan", lineHeight = 22, maxLines = 1), accessory = Size(300, 32)))
        assertFrame(wide.accessory, rect(169.5, 774, 146, 32))
        // None: nothing given up.
        XCTAssertEqual(dock().accessory.width, 0)
        assertFrame(dock().entry, rect(23.5, 762.5, 292, 55))
    }

    @Test fun longPromptIsClampedToItsLane() {
        val layout = dock(entry = "x".repeat(40))
        assertFrame(layout.entryText, rect(44.5, 779, 267, 22))
    }

    @Test fun noHomeIndicatorKeepsTenBelow() {
        val layout = dock(safeBottom = 0.0)
        assertFrame(layout.dock, rect(0, 778, 393, 74))
        assertFrame(layout.composer, rect(16, 786, 361, 56))
        assertFrame(layout.scrim, rect(0, 814, 393, 38))
    }

    @Test fun contentInsetIsHappyAppsConstant() {
        XCTAssertEqual(HomeDockLayout.Metrics.contentInset, 108)
    }

    // Focus

    private val rowValues = listOf("MacBook Pro", "~/happy", "No worktree", "Claude Code")

    private fun focus(progress: String? = null, hint: String? = null, composerHeight: Double = 102.0) = HomeDockFocusLayout(
        HomeDockFocusLayout.Input(
            size = phone,
            safeBottom = 34.0,
            composerHeight = composerHeight,
            rows = rowValues.map { text(it, lineHeight = 22, maxLines = 1) },
            progress = progress?.let { text(it, lineHeight = 14, maxLines = 1) },
            hint = hint?.let { text(it, lineHeight = 14, maxLines = 1) },
        ),
    )

    @Test fun focusedComposerSitsEightAboveTheSafeArea() {
        assertFrame(focus().composer, rect(16, 708, 361, 102))
    }

    @Test fun pickerRowsStackAboveTheStatusLinesRoom() {
        val layout = focus()
        XCTAssertEqual(layout.rows.size, 4)
        // 42 tall, 1 apart, 22 clear of the composer.
        assertFrame(layout.rows[0].frame, rect(24, 515, 345, 42))
        assertFrame(layout.rows[1].frame, rect(24, 558, 345, 42))
        assertFrame(layout.rows[2].frame, rect(24, 601, 345, 42))
        assertFrame(layout.rows[3].frame, rect(24, 644, 345, 42))
        // A 24 icon box after 6 of padding, the value 12 after it.
        assertFrame(layout.rows[0].icon, rect(30, 524, 24, 24))
        assertFrame(layout.rows[0].trigger, rect(66, 515, 297, 42))
        assertFrame(layout.rows[0].value, rect(66, 525, 88, 22))
        assertFrame(layout.rows[1].value, rect(66, 568, 56, 22))
    }

    @Test fun longRowValueLeavesTheTriggersSpacer() {
        val layout = HomeDockFocusLayout(
            HomeDockFocusLayout.Input(size = phone, safeBottom = 34.0, composerHeight = 102.0, rows = listOf(text("x".repeat(60), lineHeight = 22, maxLines = 1))),
        )
        // 297 of trigger less its 8dp minimum spacer.
        assertFrame(layout.rows[0].value, rect(66, 654, 289, 22))
    }

    @Test fun rowsRideOnAGrowingComposer() {
        // Halfway open: the shell is 79, and the rows sit on it.
        val layout = focus(composerHeight = 79.0)
        assertFrame(layout.composer, rect(16, 731, 361, 79))
        assertFrame(layout.rows[3].frame, rect(24, 667, 345, 42))
    }

    @Test fun statusLineWithStop() {
        val layout = focus(progress = "Starting Claude Code…", hint = "To interrupt press stop")
        assertFrame(layout.progress, rect(16, 690, 361, 18))
        assertFrame(layout.progressDot, rect(32, 696, 6, 6))
        // The hint holds the right; the label gives way 6 short of it.
        assertFrame(layout.progressHint, rect(177, 692, 184, 14))
        assertFrame(layout.progressLabel, rect(44, 692, 127, 14))
        // Showing it moves nothing.
        assertFrame(layout.rows[3].frame, focus().rows[3].frame)
    }

    @Test fun statusLineWithoutStop() {
        val layout = focus(progress = "Starting Claude Code…")
        XCTAssertNil(layout.progressHint)
        assertFrame(layout.progressLabel, rect(44, 692, 168, 14))
    }

    @Test fun noStatusLineWhileIdle() {
        val layout = focus()
        XCTAssertNil(layout.progress)
        XCTAssertNil(layout.progressDot)
        XCTAssertNil(layout.progressLabel)
    }

    @Test fun keyboardShift() {
        // Down: the guide's top is the safe area's bottom.
        XCTAssertEqual(HomeDockFocusLayout.keyboardShift(keyboardTop = 818.0, size = phone, safeBottom = 34.0), 0)
        // Up: the composer ends 8 above the keyboard.
        val shift = HomeDockFocusLayout.keyboardShift(keyboardTop = 516.0, size = phone, safeBottom = 34.0)
        XCTAssertEqual(shift, -302)
        XCTAssertEqual(focus().composer.maxY + shift, 508)
    }

    // Motion

    @Test fun motionClosed() {
        val motion = HomeDockFocusMotion(0.0)
        XCTAssertEqual(motion.backdropAlpha, 0)
        XCTAssertEqual(motion.composerHeight(full = 102.0), 56)
        XCTAssertEqual(motion.composerAlpha, 0.72, accuracy = 0.0001)
        XCTAssertEqual(motion.composerScaleX, 0.96, accuracy = 0.0001)
        XCTAssertEqual(motion.row(0).first, 0)
        XCTAssertEqual(motion.row(0).second, 10)
    }

    @Test fun motionOpen() {
        val motion = HomeDockFocusMotion(1.0)
        XCTAssertEqual(motion.backdropAlpha, 1)
        XCTAssertEqual(motion.composerHeight(full = 102.0), 102)
        XCTAssertEqual(motion.composerAlpha, 1)
        XCTAssertEqual(motion.composerScaleX, 1)
        XCTAssertEqual(motion.row(3).first, 1)
    }

    @Test fun motionMidway() {
        XCTAssertEqual(HomeDockFocusMotion(0.35).backdropAlpha, 1, accuracy = 0.0001)
        XCTAssertEqual(HomeDockFocusMotion(0.06).composerAlpha, 0.86, accuracy = 0.0001)
        // Each row a beat after the one above.
        val rows = HomeDockFocusMotion(0.32)
        XCTAssertEqual(rows.row(0).first, 0.5, accuracy = 0.0001)
        XCTAssertEqual(rows.row(0).second, 5, accuracy = 0.0001)
        XCTAssertEqual(rows.row(1).first, 0.5 - 0.09 / 0.28, accuracy = 0.0001)
        XCTAssertEqual(rows.row(2).first, 0)
        XCTAssertEqual(HomeDockFocusMotion(0.5).composerHeight(full = 102.0), 79)
    }

    @Test fun easings() {
        XCTAssertEqual(HomeDockFocusMotion.easeOut(0.5), 0.875, accuracy = 0.0001)
        XCTAssertEqual(HomeDockFocusMotion.easeIn(0.5), 0.125, accuracy = 0.0001)
        XCTAssertEqual(HomeDockFocusMotion.easeOut(2.0), 1)
        XCTAssertEqual(HomeDockFocusMotion.easeIn(-1.0), 0)
    }

    @Test fun refusalFlashIsTwoBeats() {
        XCTAssertEqual(HomeDockRefusal.flashDuration, 0.65, accuracy = 0.0001)
        XCTAssertEqual(HomeDockRefusal.flash(0.0), 0)
        XCTAssertEqual(HomeDockRefusal.flash(0.045), 0.5, accuracy = 0.0001)
        XCTAssertEqual(HomeDockRefusal.flash(0.09), 1, accuracy = 0.0001)
        XCTAssertEqual(HomeDockRefusal.flash(0.22), 0, accuracy = 0.0001)
        XCTAssertEqual(HomeDockRefusal.flash(0.31), 1, accuracy = 0.0001)
        XCTAssertEqual(HomeDockRefusal.flash(0.65), 0)
    }

    @Test fun dockTokensFromHappyApp() {
        XCTAssertEqual(Palette.homeDockBackdropDim.light.alpha, 0.88)
        XCTAssertEqual(TextStyles.homeDockEntry, TextStyle(FontFace.Regular, 17))
        XCTAssertEqual(TextStyles.homeDockProgress, TextStyle(FontFace.Regular, 11))
    }

    // Fade (HomeDockFadeTests.swift's layout half)

    @Test fun fadeShowsAtOnceOffWindowOrWithoutMotion() {
        XCTAssertNil(HomeDockFade.shown(HomeDockFade.Origin.Appearing, onWindow = false, reduceMotion = false))
        XCTAssertNil(HomeDockFade.shown(HomeDockFade.Origin.Focus, onWindow = true, reduceMotion = true))
        val appear = HomeDockFade.shown(HomeDockFade.Origin.Appearing, onWindow = true, reduceMotion = false)!!
        XCTAssertEqual(appear.presence(0.0), 0.0 to 0.0)
        XCTAssertEqual(appear.presence(0.22), 1.0 to 1.0)
        val back = HomeDockFade.shown(HomeDockFade.Origin.Focus, onWindow = true, reduceMotion = false)!!
        XCTAssertEqual(back.surfaceFrom, 0.72, accuracy = 0.0001)
        XCTAssertEqual(back.duration, 0.18)
    }
}
