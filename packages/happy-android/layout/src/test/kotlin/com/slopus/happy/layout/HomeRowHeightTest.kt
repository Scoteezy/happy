package com.slopus.happy.layout

import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.

/// The home list sizes these rows without measuring their text; any
/// content, at any width, must give the same height.
class HomeRowHeightTest {
    private val widths: List<Double> = listOf(0.0, 120.0, 393.0, 1024.0)
    private val contents: List<Measurable> = listOf<Measurable>(
        Measurable.fixed(Size.Zero),
        text("A long title that wraps if it is allowed to", lineHeight = 22.0),
        text("x", lineHeight = 40.0, maxLines = 1),
        block(height = 300.0),
    )

    @Test fun testFlatSessionRow() {
        for (width in widths) {
            XCTAssertEqual(HomeRowHeights.flatSession(width = width), 81)
            for (content in contents) {
                for (workspace in listOf(null, content)) {
                    for (divider in listOf(false, true)) {
                        val layout = FlatSessionRowLayout(FlatSessionRowLayout.Input(
                            width = width, title = content, timestamp = content, project = content,
                            workspace = workspace, showsDivider = divider
                        ))
                        XCTAssertEqual(layout.size.height, HomeRowHeights.flatSession(width = width))
                    }
                }
            }
        }
    }

    @Test fun testProjectCard() {
        for (width in widths) {
            for (content in contents) {
                for (flag in listOf(false, true)) {
                    val layout = ProjectCardLayout(ProjectCardLayout.Input(
                        width = width, hairline = 1.0 / 3, name = content, showsDot = flag,
                        changes = if (flag) content else null, showsAdd = flag, showsTree = flag
                    ))
                    XCTAssertEqual(layout.size.height, HomeRowHeights.projectCard(width = width))
                }
            }
        }
    }

    @Test fun testWorktreeRow() {
        for (width in widths) {
            for (content in contents) {
                for (flag in listOf(false, true)) {
                    val layout = WorktreeRowLayout(WorktreeRowLayout.Input(
                        width = width, hairline = 1.0 / 3, label = content, chevron = if (flag) Size(12, 30) else null,
                        showsDot = flag, changes = if (flag) content else null, last = flag
                    ))
                    XCTAssertEqual(layout.size.height, HomeRowHeights.worktree(width = width))
                }
            }
        }
    }

    @Test fun testProjectChatRow() {
        for (width in widths) {
            for (content in contents) {
                XCTAssertEqual(ProjectChatRowLayout(width = width, name = content, corner = content).size.height, HomeRowHeights.projectChat(width = width))
            }
        }
    }
}
