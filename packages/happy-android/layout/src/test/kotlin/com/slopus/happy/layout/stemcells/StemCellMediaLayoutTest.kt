package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.assertFrames
import com.slopus.happy.layout.XCTAssertLessThan
import com.slopus.happy.layout.XCTAssertLessThanOrEqual
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.block
import com.slopus.happy.layout.chat.MarkdownLayout
import com.slopus.happy.layout.fixed
import com.slopus.happy.layout.rect
import com.slopus.happy.layout.stemcells.StemCellMediaFlow as Flow
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

// Ported from happy-ios Tests/HappyLayoutTests/StemCellMediaLayoutTests.swift.

/** Desktop's mediaLayout.test.tsx cases, and the phone's own frames. */
class StemCellMediaLayoutTest {
    private fun image(width: Number, height: Number, scale: Number? = null) =
        Flow.Image(width = width.toDouble(), height = height.toDouble(), scale = scale?.toDouble())

    private val wide = image(1440, 900)
    private val flat = image(1400, 200)
    private val narrow = image(300, 900)
    private val square = image(1024, 1024)
    private val portrait = image(1024, 1536)
    private val page = image(1440, 7200)

    private fun near(actual: Double, expected: Number, label: String) =
        XCTAssertLessThan(abs(actual - expected.toDouble()), 0.01, "$label: $actual vs $expected")

    private fun frames(plan: Flow.Plan, measure: Double): List<Rect> {
        val geometry = Flow.geometry(plan, measure)
        return geometry.frames.keys.sorted().map { geometry.frames.getValue(it) }
    }

    @Test fun twoColumnsKeepEveryShape() {
        val plan = Flow.columnsPlan(listOf(wide, flat, narrow, wide), columns = 2)
        val geometry = Flow.geometry(plan, 720.0)
        val tiles = frames(plan, 720.0)
        val column = (720.0 - 8) / 2
        near(tiles[0].width, column, "wide fills its column")
        near(tiles[0].height, column / 1.6, "wide keeps 16:10")
        near(tiles[1].minX, column + 8, "second column after the gap")
        near(tiles[1].height, column / 7, "the strip whole")
        near(tiles[2].width, 160, "narrow narrows to the cap")
        near(tiles[2].height, 480, "narrow capped")
        near(tiles[2].minY, tiles[0].height + 8, "second row under the taller")
        near(tiles[3].minY, tiles[2].minY, "a row shares its top")
        near(geometry.height, column / 1.6 + 8 + 480, "grid height")
    }

    @Test fun aPageCaptureLetterboxes() {
        val tile = frames(Flow.columnsPlan(listOf(page), columns = 2), 720.0)[0]
        near(tile.height, 480, "capped")
        near(tile.width, 160, "held at the minimum width")
    }

    @Test fun oneColumnNeverPastItsOwnPixels() {
        val tiles = frames(Flow.columnsPlan(listOf(wide, square, portrait, image(120, 80)), columns = 1), 720.0)
        near(tiles[0].width, 720, "landscape fills")
        near(tiles[1].width, 640, "square to the 640 cap")
        near(tiles[2].height, 640, "portrait capped")
        near(tiles[2].width, 640 / 1.5, "portrait keeps its shape")
        near(tiles[3].width, 120, "a small picture is not blown up")
    }

    @Test fun density() {
        val retina = image(1040, 720, scale = 2)
        near(frames(Flow.columnsPlan(listOf(retina), columns = 1), 900.0)[0].width, 520, "one column stops at 520")
        near(frames(Flow.justifiedPlan(listOf(retina)), 900.0)[0].width, 520, "alone at its point size")
        near(frames(Flow.columnsPlan(listOf(image(2880, 1800, scale = 2)), columns = 1), 720.0)[0].width, 720, "a wider capture fills")
        near(Flow.geometry(Flow.justifiedPlan(listOf(retina, retina)), 1400.0).height, 360, "row capped at its point height")
    }

    @Test fun justifiedRows() {
        val connect = image(3680, 2392)
        val profile = image(3456, 2168)
        val pair = Flow.justifiedPlan(listOf(connect, profile))
        XCTAssertEqual(pair.rows.size, 1)
        val tiles = frames(pair, 720.0)
        near(tiles[0].height, tiles[1].height, "one height")
        near(tiles[0].width + 8 + tiles[1].width, 720, "fills the measure")

        val extremes = Flow.justifiedPlan(listOf(wide, flat, square, narrow, portrait))
        XCTAssertEqual(extremes.rows.size, 5)
        near(frames(extremes, 720.0)[1].width, 720, "the strip spans the measure")

        val many = listOf(wide, square, portrait, wide, square, wide, portrait, square, wide)
        val plan = Flow.justifiedPlan(many)
        val geometry = Flow.geometry(plan, 720.0)
        val order = mutableListOf<Int>()
        for (row in plan.rows) {
            val justified = row as? Flow.Row.Justified ?: return fail("a justified row")
            XCTAssertLessThanOrEqual(justified.tiles.size.toDouble(), 4)
            order += justified.tiles.map { it.index }
            near(geometry.frames.getValue(justified.tiles.last().index).maxX, 720, "ends at the measure")
        }
        XCTAssertEqual(order, (0 until 9).toList())

        val small = frames(Flow.justifiedPlan(listOf(image(100, 100), image(150, 100))), 720.0)
        near(small[0].height, 100, "held at their own height")
        near(small[1].width, 150, "second at its own width")
        XCTAssertEqual(frames(Flow.justifiedPlan(listOf(image(300, 200))), 720.0), listOf(rect(0, 0, 300, 200)))
    }

    @Test fun recordingsFollowThePicturesAtTheirBox() {
        XCTAssertEqual(Flow.videoBox(width = null, height = null), Size(380, 214))
        XCTAssertEqual(Flow.videoBox(width = 1920.0, height = 1080.0), Size(380, 214))
        XCTAssertEqual(Flow.videoBox(width = 1080.0, height = 1920.0), Size(240, 320))
        XCTAssertEqual(Flow.videoBox(width = 320.0, height = 240.0), Size(320, 240))

        // A phone's 358: a picture, then a 16:9 recording scaled to fit.
        val items = listOf(
            StemCellMediaLayout.Item(isVideo = true, image = image(1920, 1080)),
            StemCellMediaLayout.Item(isVideo = false, image = image(600, 400)),
        )
        val layout = StemCellMediaLayout(width = 358.0, items = items, columns = 0)
        val picture = 358 / 1.5
        assertFrames(layout.frames, listOf(rect(0, picture + 8, 358, 202), rect(0, 0, 358, picture)))
        XCTAssertEqual(layout.size, Size(358.0, picture + 8 + 202))
    }

    @Test fun anAgentsPictureSitsUnderItsText() {
        val layout = StemCellMessageLayout.agent(
            390.0,
            parts = listOf(
                StemCellMessageLayout.Part.Markdown(listOf(MarkdownLayout.Block.Text(block(24)))),
                StemCellMessageLayout.Part.Media(listOf(StemCellMediaLayout.Item(isVideo = false, image = image(300, 200))), columns = 0),
            ),
        )
        val media = layout.parts.getOrNull(1) as? StemCellMessageLayout.Placed.Media
        if (layout.parts.size != 2 || media == null) return fail("two parts")
        assertFrames(media.frames, listOf(rect(16, 40, 300, 200)))
        XCTAssertEqual(layout.size, Size(390, 248))
    }

    @Test fun aPersonsPhotosHugTheRightUnderTheBubble() {
        val layout = StemCellMessageLayout.user(390.0, text = fixed(40, 20), pictures = listOf(image(4032, 3024)), showsStatus = false)
        XCTAssertEqual(layout.bubble, rect(310, 8, 64, 40))
        // 0.76 of 358 is 272: the photo fills it at 4:3.
        assertFrames(layout.pictures, listOf(rect(102, 56, 272, 204)))
        XCTAssertEqual(layout.size.height, 268)

        val alone = StemCellMessageLayout.user(390.0, text = null, pictures = listOf(image(100, 200), image(100, 200)), showsStatus = false)
        XCTAssertNil(alone.bubble)
        // Justified at their own 200 height, pushed to the right edge.
        assertFrames(alone.pictures, listOf(rect(166, 8, 100, 200), rect(274, 8, 100, 200)))
        XCTAssertEqual(alone.size.height, 216)
    }

    @Test fun aPlayButtonSitsInTheMiddleOfItsTile() {
        XCTAssertEqual(Flow.playButton(Size(380, 214)), rect(166, 83, 48, 48))
        XCTAssertEqual(Flow.playButton(Size(161, 161)), rect(57, 57, 48, 48))
    }
}
