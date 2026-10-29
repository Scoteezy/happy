package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.Size
import com.slopus.happy.layout.XCTAssertEqual
import com.slopus.happy.layout.XCTAssertNil
import com.slopus.happy.layout.fixed
import com.slopus.happy.layout.rect
import com.slopus.happy.layout.stemcells.StemCellMediaTreeLayout as Tree
import com.slopus.happy.layout.stemcells.StemCellMediaTreeLayout.Length
import org.junit.Test

// Ported from happy-ios Tests/HappyLayoutTests/StemCellMediaTreeLayoutTests.swift.

/** A tree layout's exact frames at a phone's reading column. */
class StemCellMediaTreeLayoutTest {
    /** A 2× capture of 1600 × 1000: 800 × 500 points. */
    private val shot = Tree.Item(isCard = false, ratio = 1.6, natural = Size(800, 500))

    private fun style(css: Map<String, String>) = Tree.Style.from(css)

    private fun item(index: Int, css: Map<String, String> = emptyMap(), item: Tree.Item? = null): Tree.Box =
        Tree.Box.Item(index, style(css), item ?: shot)

    @Test fun aGridSharesItsWidthByItsTracks() {
        val tree = Tree(361.0, listOf(
            Tree.Box.Container(style(mapOf("display" to "grid", "gridTemplateColumns" to "1fr 1fr", "gap" to "8px")), listOf(item(0), item(1), item(2))),
        ))
        XCTAssertEqual(tree.items[0], rect(0, 0, 176.5, 110.3125))
        XCTAssertEqual(tree.items[1], rect(184.5, 0, 176.5, 110.3125))
        XCTAssertEqual(tree.items[2], rect(0, 118.3125, 176.5, 110.3125))
        XCTAssertEqual(tree.size, Size(361, 228.625))
    }

    @Test fun tracksMixLengthsSharesAndRepeats() {
        XCTAssertEqual(Tree.tracks(listOf("100px", "3fr", "1fr"), 316.0, 8.0, 3), listOf(100.0, 150.0, 50.0))
        XCTAssertEqual(Tree.tracks(listOf("repeat(3, 1fr)"), 316.0, 8.0, 3), listOf(100.0, 100.0, 100.0))
        // At 361 a 200 minimum fits once; at 720, three times.
        XCTAssertEqual(Tree.tracks(listOf("repeat(auto-fill, minmax(200px, 1fr))"), 361.0, 8.0, 4), listOf(361.0))
        XCTAssertEqual(Tree.tracks(listOf("repeat(auto-fill, minmax(200px, 1fr))"), 720.0, 8.0, 4).size, 3)
        // `auto-fit` keeps only the tracks its two items fill.
        XCTAssertEqual(Tree.tracks(listOf("repeat(auto-fit, minmax(100px, 1fr))"), 720.0, 8.0, 2), listOf(356.0, 356.0))
        XCTAssertEqual(Tree.tracks(listOf("minmax(200px, 1fr)", "1fr"), 300.0, 0.0, 2), listOf(200.0, 100.0))
    }

    @Test fun anItemKeepsItsNaturalSizeShrinksAndNeverGrows() {
        val small = Tree.Item(isCard = false, ratio = 2.0, natural = Size(100, 50))
        val tall = Tree.Item(isCard = false, ratio = 0.2, natural = Size(400, 2000))
        val tree = Tree(361.0, listOf(
            item(0, emptyMap(), small),
            item(1, emptyMap(), tall),
            item(2, mapOf("width" to "100%"), small),
            item(3, mapOf("width" to "50%", "aspectRatio" to "1")),
        ))
        XCTAssertEqual(tree.items[0], rect(0, 0, 100, 50))
        // Past 640 tall it narrows to keep its shape.
        XCTAssertEqual(tree.items[1], rect(0, 50, 128, 640))
        XCTAssertEqual(tree.items[2], rect(0, 690, 361, 180.5))
        XCTAssertEqual(tree.items[3], rect(0, 870.5, 180.5, 180.5))
    }

    @Test fun aWrappingRowBreaksWhereItsBoxesNoLongerFit() {
        val row = Tree.Box.Container(style(mapOf("display" to "flex", "flexWrap" to "wrap", "gap" to "12px")), listOf(
            item(0, mapOf("width" to "200px")), item(1, mapOf("width" to "149px")), item(2, mapOf("width" to "200px")),
        ))
        val tree = Tree(361.0, listOf(row))
        XCTAssertEqual(tree.items[0], rect(0, 0, 200, 125))
        XCTAssertEqual(tree.items[1], rect(212, 0, 149, 93.125))
        XCTAssertEqual(tree.items[2], rect(0, 137, 200, 125))
        XCTAssertEqual(tree.size.height, 262)
    }

    @Test fun aRowSharesFreeRoomByGrowAndCentresAcross() {
        val row = Tree.Box.Container(style(mapOf("display" to "flex", "gap" to "10px", "alignItems" to "center")), listOf(
            item(0, mapOf("flex" to "1")), item(1, mapOf("flex" to "1")),
        ))
        val tree = Tree(210.0, listOf(row))
        XCTAssertEqual(tree.items[0], rect(0, 0, 100, 62.5))
        XCTAssertEqual(tree.items[1], rect(110, 0, 100, 62.5))
    }

    @Test fun textAndCardsTakeTheirRows() {
        val card = Tree.Item(isCard = true, ratio = 1.0, natural = null)
        val figure = Tree.Box.Container(style(mapOf("padding" to "8px 12px")), listOf(
            item(0),
            Tree.Box.Text(0, fixed(120, 20)),
            item(1, emptyMap(), card),
        ))
        val tree = Tree(361.0, listOf(figure))
        XCTAssertEqual(tree.items[0], rect(12, 8, 337, 210.625))
        XCTAssertEqual(tree.texts[0], rect(12, 218.625, 337, 20))
        XCTAssertEqual(tree.items[1], rect(12, 238.625, 337, 64))
        XCTAssertEqual(tree.size.height, 310.625)
    }

    @Test fun marginsCollapseAndAutoCentres() {
        val small = Tree.Item(isCard = false, ratio = 1.0, natural = Size(100, 100))
        val tree = Tree(300.0, listOf(
            item(0, mapOf("marginBottom" to "16px"), small),
            item(1, linkedMapOf("marginTop" to "8px", "margin" to "8px auto"), small),
        ))
        XCTAssertEqual(tree.items[0], rect(0, 0, 100, 100))
        XCTAssertEqual(tree.items[1], rect(100, 116, 100, 100))
    }

    @Test fun aTallLayoutIsCutAtItsCap() {
        val tall = Tree.Item(isCard = false, ratio = 0.5, natural = Size(320, 640))
        val tree = Tree(361.0, listOf(item(0, emptyMap(), tall), item(1, emptyMap(), tall), item(2, emptyMap(), tall)))
        XCTAssertEqual(tree.size.height, 1200)
        XCTAssertEqual(tree.items[1], rect(0, 640, 320, 560))
        XCTAssertNil(tree.items[2])
    }

    @Test fun stylesReadTheirShorthands() {
        val style = style(mapOf("flex" to "2 0 120px", "gridColumn" to "span 2", "padding" to "4px 8px 12px", "gap" to "8px 4px", "placeItems" to "center end"))
        XCTAssertEqual(style.grow, 2)
        XCTAssertEqual(style.shrink, 0)
        XCTAssertEqual(style.basis, Length.Px(120.0))
        XCTAssertEqual(style.columnSpan, 2)
        XCTAssertEqual(style.padding, listOf(Length.Px(4.0), Length.Px(8.0), Length.Px(12.0), Length.Px(8.0)))
        XCTAssertEqual(style.rowGap, Length.Px(8.0))
        XCTAssertEqual(style.columnGap, Length.Px(4.0))
        XCTAssertEqual(style.alignItems, "center")
        XCTAssertEqual(style.justifyItems, "end")
        XCTAssertEqual(style(mapOf("gridColumn" to "2 / 4")).columnStart, 1)
        XCTAssertEqual(style(mapOf("gridColumn" to "2 / 4")).columnSpan, 2)
        XCTAssertEqual(style(mapOf("aspectRatio" to "16 / 9")).aspectRatio, 16.0 / 9)
    }
}
