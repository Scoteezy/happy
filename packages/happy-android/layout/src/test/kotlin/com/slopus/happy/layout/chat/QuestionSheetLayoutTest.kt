package com.slopus.happy.layout.chat

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.assertFrame
import com.slopus.happy.layout.rect
import com.slopus.happy.layout.text
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/ChatLayoutTests.swift (QuestionSheetLayoutTests).

class QuestionSheetLayoutTest {
    private fun sheet(keyboardTop: Double? = null) = QuestionSheetLayout(
        QuestionSheetLayout.Input(
            size = Size(393, 852), safeArea = LayoutInsets(top = 59, bottom = 34), keyboardTop = keyboardTop, hairline = 0.5,
            cancel = text("Cancel", lineHeight = 20), title = text("Question", lineHeight = 20), submit = text("Send answer", lineHeight = 20),
            questions = listOf(
                QuestionSheetLayout.Question(
                    chip = text("SCOPE", lineHeight = 16), showsDot = true, text = text("Which part?", lineHeight = 22),
                    options = listOf(
                        QuestionSheetLayout.Option(text("List", lineHeight = 20)),
                        QuestionSheetLayout.Option(text("Composer", lineHeight = 20), text("The input", lineHeight = 18)),
                    ),
                    custom = QuestionSheetLayout.Custom(text("Your own answer", lineHeight = 18), text("", lineHeight = 20)),
                ),
                QuestionSheetLayout.Question(
                    chip = text("NAME", lineHeight = 16), showsDot = false, text = text("What?", lineHeight = 22),
                    hint = text("Choose as many as apply", lineHeight = 18), options = emptyList(),
                ),
            ),
            error = text("Could not send", lineHeight = 20),
        ),
    )

    @Test fun testTheHeaderAndFooterFrameTheQuestions() {
        val layout = sheet()
        assertFrame(layout.cancel, rect(16, 73, 48, 20))
        assertFrame(layout.title, rect(158.5, 73, 64, 20))
        assertFrame(layout.header, rect(0, 59, 393, 48.5))
        assertFrame(layout.headerDivider, rect(0, 107, 393, 0.5))
        assertFrame(layout.footer, rect(0, 743.5, 393, 108.5))
        assertFrame(layout.footerDivider, rect(0, 743.5, 393, 0.5))
        assertFrame(layout.submit, rect(16, 756, 361, 50))
        assertFrame(layout.submitLabel, rect(152.5, 771, 88, 20))
        assertFrame(layout.scroll, rect(0, 107.5, 393, 636))
    }

    @Test fun testQuestionsStackWithTheirOptionsAndOwnAnswer() {
        val layout = sheet()
        val first = layout.questions[0]
        assertFrame(first.chip, rect(16, 16, 56, 24))
        assertFrame(first.chipText, rect(24, 20, 40, 16))
        assertFrame(first.dot, rect(80, 25, 6, 6))
        assertFrame(first.text, rect(16, 48, 361, 22))
        XCTAssertNil(first.hint)
        assertFrame(first.options[0].box, rect(16, 78, 361, 51))
        assertFrame(first.options[0].checkbox, rect(30, 93, 22, 22))
        assertFrame(first.options[0].label, rect(64, 92, 299, 20))
        XCTAssertNil(first.options[0].description)
        assertFrame(first.options[1].box, rect(16, 137, 361, 68))
        assertFrame(first.options[1].description, rect(64, 173, 299, 18))
        assertFrame(first.customLabel, rect(16, 217, 361, 18))
        assertFrame(first.input, rect(16, 241, 361, 48))
        val second = layout.questions[1]
        assertFrame(second.chip, rect(16, 317, 48, 24))
        XCTAssertNil(second.dot)
        assertFrame(second.hint, rect(16, 379, 361, 18))
        XCTAssertNil(second.input)
        assertFrame(layout.error, rect(16, 425, 361, 20))
        XCTAssertEqual(layout.contentSize, Size(393, 575))
    }

    @Test fun testTheFooterRidesTheKeyboard() {
        val layout = sheet(keyboardTop = 500.0)
        assertFrame(layout.footer, rect(0, 425.5, 393, 74.5))
        assertFrame(layout.submit, rect(16, 438, 361, 50))
        assertFrame(layout.scroll, rect(0, 107.5, 393, 318))
    }
}
