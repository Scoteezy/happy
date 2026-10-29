package com.slopus.happy.layout.stemcells

import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.max

/**
 * Stem Cells home (happy-ios `StemCellsHomeLayout`): the banner under the
 * header, and the list's insets. `contentTop` is where content starts under
 * the header, `chromeTop` the header's own foot, `listBottom` the room the
 * dock keeps, `storiesHeight` the room the stories open under the header
 * (0: closed into the title, or not drawn).
 */
data class StemCellsHomeLayout(
    val banner: Rect,
    val listInsets: LayoutInsets,
    val indicatorInsets: LayoutInsets,
    val empty: Rect,
) {
    companion object {
        operator fun invoke(size: Size, contentTop: Double, chromeTop: Double, listBottom: Double, bannerHeight: Double, storiesHeight: Double = 0.0): StemCellsHomeLayout {
            val banner = Rect(0.0, contentTop + max(0.0, storiesHeight), size.width, bannerHeight)
            return StemCellsHomeLayout(
                banner = banner,
                listInsets = LayoutInsets(banner.maxY, 0.0, listBottom, 0.0),
                indicatorInsets = LayoutInsets(chromeTop, 0.0, listBottom, 0.0),
                empty = Rect(0.0, contentTop, size.width, max(0.0, size.height - contentTop - listBottom)),
            )
        }
    }
}
