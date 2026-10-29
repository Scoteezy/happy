package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.Size
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.rect
import com.slopus.happy.layout.stemcells.StemCellSidebarProposalLayout.Footer
import com.slopus.happy.layout.stemcells.StemCellSidebarProposalLayout.Input
import com.slopus.happy.layout.stemcells.StemCellsSidebarLayout.Kind
import com.slopus.happy.layout.stemcells.StemCellsSidebarLayout.Row
import org.junit.Assert.fail
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/StemCellSidebarProposalLayoutTests.swift.

/** Exact frames for a sidebar proposal in Prioritizing's chat (`sidebar-proposal.css`). */
class StemCellSidebarProposalLayoutTest {
    /**
     * A section, a row with one under it, and a separator, at the phone
     * sidebar's desktop half again: 9 + 42 + 3 + 48 + 3 + 48 + 3 + 26 + 9 tall.
     */
    private val rows = listOf(Row(Kind.Section), Row(Kind.Row), Row(Kind.Row, depth = 1), Row(Kind.Separator))
    private val answers = Footer.Answers(keep = 50.2, use = 80.0, notice = false)

    @Test fun anOpenProposalHoldsItsAnswersAtTheRight() {
        val layout = StemCellSidebarProposalLayout(400.0, Input(rows, 103.4, answers))
        // 360 at most, 12 inside the hairline.
        XCTAssertEqual(layout.size, Size(360, 279))
        XCTAssertEqual(layout.title, rect(12, 12, 104, 20))
        XCTAssertEqual(layout.version, rect(122, 12, 226, 20))
        XCTAssertEqual(layout.frame, rect(12, 40, 336, 191))
        XCTAssertEqual(layout.footer, rect(12, 239, 336, 28))
        // Each label padded 10 either side inside a 1pt ring, 8 apart.
        XCTAssertEqual(layout.use, rect(246, 239, 102, 28))
        XCTAssertEqual(layout.keep, rect(165, 239, 73, 28))
        XCTAssertNil(layout.notice)
        XCTAssertNil(layout.status)
    }

    @Test fun aNoticeTakesTheFootersLeftWithoutAddingALine() {
        val layout = StemCellSidebarProposalLayout(400.0, Input(rows, 103.4, answers.copy(notice = true)))
        XCTAssertEqual(layout.notice, rect(12, 245, 145, 16))
        XCTAssertEqual(layout.size.height, 279)
    }

    @Test fun answeringChangesOnlyTheLineUnderTheRows() {
        val open = StemCellSidebarProposalLayout(400.0, Input(rows, 103.4, answers))
        val answered = StemCellSidebarProposalLayout(400.0, Input(rows, 103.4, Footer.Status))
        XCTAssertEqual(answered.size, open.size)
        XCTAssertEqual(answered.frame, open.frame)
        XCTAssertEqual(answered.status, rect(12, 245, 336, 16))
        XCTAssertNil(answered.keep)
        XCTAssertNil(answered.use)
    }

    @Test fun aNarrowColumnTakesTheCardAcross() {
        val layout = StemCellSidebarProposalLayout(300.0, Input(rows, 103.4, answers))
        XCTAssertEqual(layout.size, Size(300, 279))
        XCTAssertEqual(layout.frame, rect(12, 40, 276, 191))
        XCTAssertEqual(layout.use, rect(186, 239, 102, 28))
        XCTAssertEqual(layout.keep, rect(105, 239, 73, 28))
    }

    @Test fun foldingMovesOnlyWhatIsUnderTheRows() {
        val folded = StemCellSidebarProposalLayout(400.0, Input(listOf(Row(Kind.Section), Row(Kind.Separator)), 103.4, Footer.Status))
        // 12 + 56 + 4 + 34 + 12.
        XCTAssertEqual(folded.frame, rect(12, 40, 336, 89))
        XCTAssertEqual(folded.status, rect(12, 143, 336, 16))
        XCTAssertEqual(folded.size, Size(360, 177))
    }

    @Test fun theMessagePlacesTheCardAtTheColumnsLeft() {
        val input = Input(rows, 103.4, Footer.Status)
        val message = StemCellMessageLayout.agent(400.0, parts = listOf(StemCellMessageLayout.Part.Proposal(input)))
        val placed = message.parts.first() as? StemCellMessageLayout.Placed.Proposal ?: return fail("no proposal")
        XCTAssertEqual(placed.frame, rect(16, 8, 360, 279))
        XCTAssertEqual(placed.layout.size, placed.frame.size)
        XCTAssertEqual(message.size, Size(400, 295))
    }
}
