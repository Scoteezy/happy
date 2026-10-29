package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.XCTAssertNotNil
import com.slopus.happy.layout.assertFrames
import com.slopus.happy.layout.rect
import com.slopus.happy.layout.stemcells.StemCellSessionDetailLayout as Layout
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/StemCellSessionDetailLayoutTests.swift.

/** Exact frames for a conversation's detail on a 402-wide phone. */
class StemCellSessionDetailLayoutTest {
    private fun layout(
        media: Layout.Shown? = Layout.Shown(5, true),
        links: Layout.Shown? = Layout.Shown(2, false),
        family: Double? = 100.0,
        running: Int = 2,
        completed: Int = 3,
        open: Boolean = false,
    ) = Layout(
        width = 402.0, nameHeight = 34.0, subtitleHeight = 22.0, tiles = 3, actions = 1,
        media = media, links = links, family = family, running = running, completed = completed, countWidth = 10.0, open = open,
    )

    /**
     * The mark centred, the name and its line under it, then Model and
     * Effort side by side, Started under them the row's width.
     */
    @Test fun theHeroAndTheTiles() {
        val detail = layout()
        XCTAssertEqual(detail.mark, rect(157, 12, 88, 88))
        XCTAssertEqual(detail.name, rect(24, 114, 354, 34))
        XCTAssertEqual(detail.subtitle, rect(24, 150, 354, 22))
        assertFrames(detail.tiles.map { it.frame }, listOf(rect(16, 196, 180, 80), rect(206, 196, 180, 80), rect(16, 286, 370, 80)))
        XCTAssertEqual(detail.tiles[0].value, rect(16, 14, 148, 30))
        XCTAssertEqual(detail.tiles[0].label, rect(16, 44, 148, 18))
        XCTAssertEqual(detail.tiles[2].value, rect(16, 14, 338, 30))
        XCTAssertEqual(detail.tiles[1].frame.maxX, 402 - 16)
        XCTAssertEqual(detail.actions.frame, rect(16, 390, 370, 52))
    }

    /**
     * Media first: three square cells to a row 2 apart, the last column
     * on the card's edge, "Show more" under them in the same card.
     */
    @Test fun theMediaGrid() {
        val detail = layout()
        XCTAssertEqual(detail.mediaHeading, rect(32, 466, 338, 18))
        val media = detail.media!!
        XCTAssertEqual(media.frame, rect(16, 492, 370, 298))
        assertFrames(media.cells, listOf(
            rect(0, 0, 122, 122), rect(124, 0, 122, 122), rect(248, 0, 122, 122),
            rect(0, 124, 122, 122), rect(124, 124, 122, 122),
        ))
        XCTAssertEqual(media.more?.box, rect(0, 246, 370, 52))
        XCTAssertNil(media.more?.separator)
        XCTAssertNil(media.more?.icon)
        // All of it shown: no "Show more".
        val all = layout(media = Layout.Shown(3, false)).media!!
        XCTAssertEqual(all.frame.height, 122)
        XCTAssertNil(all.more)
    }

    /**
     * Links under the media, a row each, a title over its host; with more
     * to show, the last keeps its hairline over "Show more".
     */
    @Test fun theLinks() {
        val detail = layout()
        XCTAssertEqual(detail.linksHeading, rect(32, 814, 338, 18))
        val links = detail.links!!
        XCTAssertEqual(links.frame, rect(16, 840, 370, 124))
        val row = links.rows[0]
        XCTAssertEqual(row.box, rect(0, 0, 370, 62))
        XCTAssertEqual(row.icon, rect(16, 16, 30, 30))
        XCTAssertEqual(row.title, rect(62, 10, 292, 22))
        XCTAssertEqual(row.meta, rect(62, 32, 292, 18))
        XCTAssertEqual(row.separator, rect(62, 62 - 1.0 / 3, 308, 1.0 / 3))
        XCTAssertNil(links.rows[1].separator)
        XCTAssertNil(links.more)
        val more = layout(links = Layout.Shown(2, true)).links!!
        XCTAssertNotNil(more.rows[1].separator)
        XCTAssertEqual(more.more?.box, rect(0, 124, 370, 52))
        XCTAssertEqual(more.frame.height, 176)
    }

    /**
     * Then the family, then what is at work and what settled, folded
     * under one row with its count, opening in the same card.
     */
    @Test fun theFamilyThenTheActivity() {
        val closed = layout()
        XCTAssertEqual(closed.familyHeading, rect(32, 988, 338, 18))
        XCTAssertEqual(closed.family, rect(16, 1014, 370, 100))
        XCTAssertEqual(closed.activityHeading, rect(32, 1138, 338, 18))
        XCTAssertEqual(closed.running?.frame, rect(16, 1164, 370, 124))
        XCTAssertNil(closed.running?.rows?.get(1)?.separator)
        val completed = closed.completed!!
        XCTAssertEqual(completed.frame, rect(16, 1312, 370, 52))
        XCTAssertEqual(completed.disclosure?.chevron, rect(346, 19, 8, 14))
        XCTAssertEqual(completed.disclosure?.value, rect(328, 15, 10, 22))
        XCTAssertNil(completed.disclosure?.separator)
        XCTAssertEqual(completed.rows, emptyList<Layout.ActivityRow>())
        XCTAssertEqual(closed.contentHeight, 1396)

        val open = layout(open = true)
        XCTAssertEqual(open.completed?.frame, rect(16, 1312, 370, 238))
        XCTAssertEqual(open.completed?.rows?.map { it.box.minY }, listOf(52.0, 114.0, 176.0))
        XCTAssertEqual(open.completed?.disclosure?.separator, rect(62, 52 - 1.0 / 3, 308, 1.0 / 3))
        XCTAssertEqual(open.contentHeight, 1396 + 186)
    }

    /**
     * Nothing shared, no family, nothing at work or settled: none of
     * those sections, the page ending under the actions.
     */
    @Test fun emptySectionsAreNotDrawn() {
        val bare = layout(media = null, links = Layout.Shown(0, true), family = null, running = 0, completed = 0)
        XCTAssertNil(bare.mediaHeading)
        XCTAssertNil(bare.media)
        XCTAssertNil(bare.linksHeading)
        XCTAssertNil(bare.links)
        XCTAssertNil(bare.familyHeading)
        XCTAssertNil(bare.activityHeading)
        XCTAssertNil(bare.running)
        XCTAssertNil(bare.completed)
        XCTAssertEqual(bare.contentHeight, 442 + 32)
        // Only links: they follow the actions.
        XCTAssertEqual(layout(media = null, family = null, running = 0, completed = 0).linksHeading?.minY, 466)
    }
}
