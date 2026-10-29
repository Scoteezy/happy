package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.*
import com.slopus.happy.layout.chat.*
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests.


// happy-desktop's AgentMessageActivity on a phone's width.
class StemCellAgentMessageLayoutTest {
    @Test fun aClosedMessageIsOneLineWithItsChevronAfterTheWords() {
        val layout = StemCellAgentMessageLayout(width = 402.0, verbWidth = 60.2, textWidth = 120.4, opens = true, body = null)
        XCTAssertEqual(layout.header, Rect(x = 16.0, y = 6.0, width = 370.0, height = 20.0))
        XCTAssertEqual(layout.glyph, Rect(x = 16.0, y = 9.0, width = 14.0, height = 14.0))
        XCTAssertEqual(layout.verb, Rect(x = 36.0, y = 6.0, width = 61.0, height = 20.0))
        XCTAssertEqual(layout.text, Rect(x = 103.0, y = 6.0, width = 121.0, height = 20.0))
        XCTAssertEqual(layout.chevron, Rect(x = 230.0, y = 9.0, width = 14.0, height = 14.0))
        XCTAssertNil(layout.body)
        XCTAssertEqual(layout.size, Size(402, 32))
    }

    @Test fun theSenderGivesWayAt45PercentAndTheWordsKeepRoomForTheChevron() {
        val layout = StemCellAgentMessageLayout(width = 402.0, verbWidth = 400.0, textWidth = 400.0, opens = true, body = null)
        // 45% of the 370 line.
        XCTAssertEqual(layout.verb?.width, 166)
        XCTAssertEqual(layout.text, Rect(x = 208.0, y = 6.0, width = 158.0, height = 20.0))
        XCTAssertEqual(layout.chevron, Rect(x = 372.0, y = 9.0, width = 14.0, height = 14.0))
    }

    @Test fun withoutASenderWordOrABodyTheLineIsTheWordsAlone() {
        val layout = StemCellAgentMessageLayout(width = 402.0, verbWidth = null, textWidth = 90.0, opens = false, body = null)
        XCTAssertNil(layout.verb)
        XCTAssertNil(layout.chevron)
        XCTAssertEqual(layout.text, Rect(x = 36.0, y = 6.0, width = 90.0, height = 20.0))
    }

    @Test fun openedTheMessageSits4Under16In() {
        val block = MarkdownLayout.Block.Text(fixed(200, 36))
        val layout = StemCellAgentMessageLayout(width = 402.0, verbWidth = 60.0, textWidth = 120.0, opens = true, body = listOf(block, block))
        XCTAssertEqual(layout.bodyOrigin, Point(32, 30))
        XCTAssertEqual(layout.body?.size?.height, 78)
        // 6 + 20 + 4 + 36 + 6 + 36 + 6.
        XCTAssertEqual(layout.size.height, 114)
    }

    @Test fun aWideScreenKeepsTheReadingColumn() {
        val layout = StemCellAgentMessageLayout(width = 1000.0, maxContentWidth = 800.0, verbWidth = null, textWidth = 50.0, opens = true, body = null)
        XCTAssertEqual(layout.header, Rect(x = 116.0, y = 6.0, width = 768.0, height = 20.0))
    }
}
