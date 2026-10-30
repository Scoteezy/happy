package com.slopus.happy.layout

import org.junit.Test

/** Ported from HappyLayoutTests/HomeDockBotLayoutTests.swift. */
class BotFacePickerLayoutTest {
    private val words = BotFacePickerLayout.Text(
        text("Avatar", lineHeight = 15),
        text("Art by Lisa Wischofsky", lineHeight = 13, maxLines = 1),
        text("Art by ", lineHeight = 13, maxLines = 1),
    )

    @Test fun fourFacesAndDie() {
        val layout = BotFacePickerLayout(333.0, words)
        assertFrame(layout.heading, rect(0, 0, 48, 15))
        XCTAssertEqual(layout.faces.size, 4)
        assertFrame(layout.faces[0], rect(0, 21, 52, 52))
        assertFrame(layout.faces[1], rect(62, 21, 52, 52))
        assertFrame(layout.faces[3], rect(186, 21, 52, 52))
        assertFrame(layout.die, rect(248, 27, 40, 40))
        assertFrame(layout.credit, rect(0, 79, 176, 13))
        assertFrame(layout.creditLink, rect(56, 79, 120, 13))
        assertSize(layout.size, Size(333, 92))
    }

    @Test fun narrowPhoneShrinksAllFaces() {
        val layout = BotFacePickerLayout(260.0, words)
        assertFrame(layout.faces[0], rect(0, 21, 45, 45))
        assertFrame(layout.faces[3], rect(165, 21, 45, 45))
        assertFrame(layout.die, rect(220, 23.5, 40, 40))
        assertFrame(layout.credit, rect(0, 72, 176, 13))
    }

    @Test fun longCreditClampsLinkToOneLine() {
        val layout = BotFacePickerLayout(100.0, words)
        assertFrame(layout.credit, rect(0, layout.credit.minY, 100, 13))
        assertFrame(layout.creditLink, rect(56, layout.credit.minY, 44, 13))
    }

    @Test fun nativeTokens() {
        XCTAssertEqual(BotFacePickerLayout.Metrics.faceRadius, 14)
        XCTAssertEqual(BotFacePickerLayout.Metrics.ringWidth, 2)
        XCTAssertEqual(BotFacePickerLayout.Metrics.imageRadius, 12)
        XCTAssertEqual(BotFacePickerLayout.Metrics.dieIconSize, 22)
        XCTAssertEqual(TextStyles.botFaceHeading, TextStyle(FontFace.SemiBold, 12))
        XCTAssertEqual(TextStyles.botFaceCredit, TextStyle(FontFace.Regular, 11))
    }

    @Test fun botMachineAndFacesSitAboveComposer() {
        val layout = HomeDockFocusLayout(HomeDockFocusLayout.Input(phone, 34.0, composerHeight = 102.0,
            rows = listOf(text("MacBook Pro", lineHeight = 22, maxLines = 1)), faces = words))
        assertFrame(layout.composer, rect(16, 708, 361, 102))
        assertFrame(layout.rows.single().frame, rect(24, 541, 345, 42))
        assertFrame(layout.faces!!, rect(24, 584, 345, 102))
        assertFrame(layout.facePicker!!, rect(30, 590, 333, 92))
        XCTAssertEqual(layout.facePickerLayout, BotFacePickerLayout(333.0, words))
    }

    @Test fun ordinarySessionHasNoFaces() {
        val layout = HomeDockFocusLayout(HomeDockFocusLayout.Input(phone, 34.0, composerHeight = 102.0,
            rows = listOf(text("MacBook Pro", lineHeight = 22, maxLines = 1))))
        XCTAssertNil(layout.faces)
        XCTAssertNil(layout.facePicker)
        XCTAssertNil(layout.facePickerLayout)
        assertFrame(layout.rows.single().frame, rect(24, 644, 345, 42))
    }
}