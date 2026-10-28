package com.slopus.happy.layout.diff

import com.slopus.happy.layout.Size
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.assertFrame
import com.slopus.happy.layout.assertSize
import com.slopus.happy.layout.block
import com.slopus.happy.layout.fixed
import com.slopus.happy.layout.rect
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/DiffLayoutTests.swift
// (DiffFileHeaderLayoutTests, DiffImageLayoutTests, DiffSectionLayoutTests,
// DiffFilesListLayoutTests).

class DiffFileHeaderLayoutTest {
    private val dir = DiffTextBox(Size(80.0, 16.0), 12.0)
    private val fileName = DiffTextBox(Size(60.0, 17.0), 13.0)

    @Test fun everything() {
        val layout = DiffFileHeaderLayout(
            DiffFileHeaderLayout.Input(
                width = 393.0,
                chevron = Size(14.0, 15.0),
                dir = dir,
                fileName = fileName,
                badge = Size(24.0, 14.0),
                additions = Size(20.0, 16.0),
                deletions = Size(18.0, 16.0),
            ),
        )
        assertSize(layout.size, Size(393.0, 35.0))
        assertFrame(layout.chevron, rect(12, 10, 14, 15))
        assertFrame(layout.icon, rect(34, 9.5, 16, 16))
        assertFrame(layout.dir, rect(58, 10, 80, 16))
        assertFrame(layout.fileName, rect(138, 9, 60, 17))
        assertFrame(layout.badge, rect(303, 10.5, 24, 14))
        assertFrame(layout.additions, rect(335, 9.5, 20, 16))
        assertFrame(layout.deletions, rect(363, 9.5, 18, 16))
    }

    @Test fun theDirectoryGivesWayFirst() {
        fun layout(width: Double) = DiffFileHeaderLayout(DiffFileHeaderLayout.Input(width, null, dir, fileName))
        XCTAssertNil(layout(200.0).chevron)
        assertFrame(layout(200.0).icon, rect(12, 9.5, 16, 16))
        assertFrame(layout(200.0).dir, rect(36, 10, 80, 16))
        assertFrame(layout(200.0).fileName, rect(116, 9, 60, 17))
        assertFrame(layout(150.0).dir, rect(36, 10, 42, 16))
        assertFrame(layout(150.0).fileName, rect(78, 9, 60, 17))
        assertFrame(layout(100.0).dir, rect(36, 10, 0, 16))
        assertFrame(layout(100.0).fileName, rect(36, 9, 52, 17))
    }

    @Test fun theTallestPartSetsTheRow() {
        val layout = DiffFileHeaderLayout(DiffFileHeaderLayout.Input(393.0, Size(14.0, 20.0), dir, fileName))
        assertSize(layout.size, Size(393.0, 38.0))
        assertFrame(layout.icon, rect(34, 11, 16, 16))
        assertFrame(layout.fileName, rect(58 + 80, 10.5, 60, 17))
        assertFrame(layout.dir, rect(58, 11.5, 80, 16))
    }
}

class DiffImageLayoutTest {
    @Test fun twoSides() {
        val layout = DiffImageLayout(393.0, 2, 14.0)
        assertSize(layout.size, Size(393.0, 204.0))
        assertFrame(layout.panes[0].label, rect(12, 12, 178.5, 14))
        assertFrame(layout.panes[0].box, rect(12, 32, 178.5, 160))
        assertFrame(layout.panes[0].image, rect(13, 33, 176.5, 158))
        assertFrame(layout.panes[1].box, rect(202.5, 32, 178.5, 160))
    }

    @Test fun oneSide() {
        val layout = DiffImageLayout(393.0, 1, 14.0)
        XCTAssertEqual(layout.panes.size, 1)
        assertFrame(layout.panes[0].box, rect(12, 32, 369, 160))
    }
}

class DiffSectionLayoutTest {
    @Test fun message() {
        val layout = DiffSectionLayout(393.0, headerHeight = 35.0, body = DiffSectionLayout.Body.Message(Size(120.0, 17.0)))
        assertFrame(layout.frame, rect(0, 0, 393, 85))
        assertFrame(layout.header, rect(0, 0, 393, 35))
        assertFrame(layout.content, rect(136.5, 51, 120, 17))
        assertFrame(layout.border, rect(0, 84, 393, 1))
        XCTAssertNil(layout.hintRow)
    }

    @Test fun spinnerAndHint() {
        val spinner = DiffSectionLayout(393.0, headerHeight = 35.0, body = DiffSectionLayout.Body.Spinner(Size(20.0, 20.0)))
        assertFrame(spinner.content, rect(186.5, 51, 20, 20))
        XCTAssertEqual(spinner.height, 88.0)
        val hint = DiffSectionLayout(393.0, headerHeight = 35.0, body = DiffSectionLayout.Body.Hint(Size(150.0, 17.0)))
        assertFrame(hint.hintRow, rect(0, 35, 393, 41))
        assertFrame(hint.content, rect(121.5, 47, 150, 17))
        assertFrame(hint.border, rect(0, 76, 393, 1))
    }

    @Test fun collapsedFilesAndImages() {
        val collapsed = DiffSectionLayout(393.0, headerHeight = 35.0, body = DiffSectionLayout.Body.None)
        XCTAssertEqual(collapsed.height, 36.0)
        XCTAssertNil(collapsed.content)
        val files = DiffSectionLayout(393.0, headerHeight = 35.0, body = DiffSectionLayout.Body.Files(listOf(226.0, 180.0)))
        assertFrame(files.files[0], rect(0, 35, 393, 226))
        assertFrame(files.files[1], rect(0, 261, 393, 180))
        XCTAssertEqual(files.height, 442.0)
        val image = DiffSectionLayout(393.0, headerHeight = 35.0, body = DiffSectionLayout.Body.Image(204.0))
        assertFrame(image.image, rect(0, 35, 393, 204))
        XCTAssertEqual(image.height, 240.0)
    }

    @Test fun centredAtMaxWidth() {
        val layout = DiffSectionLayout(1000.0, 800.0, 35.0, DiffSectionLayout.Body.Message(Size(900.0, 34.0)))
        assertFrame(layout.frame, rect(100, 0, 800, 102))
        assertFrame(layout.header, rect(0, 0, 800, 35))
        assertFrame(layout.content, rect(0, 51, 800, 34))
    }
}

class DiffFilesListLayoutTest {
    @Test fun sectionsAfterTheHeader() {
        val layout = DiffFilesListLayout(393.0, 50.0, listOf(36.0, 85.0), Size(0.0, 0.0))
        assertFrame(layout.header, rect(0, 0, 393, 50))
        assertFrame(layout.sections[0], rect(0, 50, 393, 36))
        assertFrame(layout.sections[1], rect(0, 86, 393, 85))
        XCTAssertNil(layout.empty)
        XCTAssertEqual(layout.contentHeight, 203.0)
    }

    @Test fun empty() {
        val layout = DiffFilesListLayout(393.0, null, emptyList(), Size(100.0, 20.0))
        XCTAssertNil(layout.header)
        assertFrame(layout.empty, rect(146.5, 32, 100, 20))
        XCTAssertEqual(layout.contentHeight, 116.0)
    }

    @Test fun notices() {
        val (frames, height) = DiffFilesListLayout.notices(393.0, listOf(fixed(300, 20), block(40)))
        assertFrame(frames[0], rect(16, 16, 300, 20))
        assertFrame(frames[1], rect(16, 44, 361, 40))
        XCTAssertEqual(height, 100.0)
    }
}
