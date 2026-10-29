package com.slopus.happy.ui.stemcells

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.stemcells.StemCellGeometry
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import kotlin.math.min

/**
 * The stem cell drawn for one ID (happy-ios `StemCellMarkView`): its
 * membrane and core as two filled shapes, the same shape desktop draws for
 * the same ID.
 */
class StemCellMarkView(context: Context) : View(context), ThemeAware {
    private val membrane = Path()
    private val core = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var drawn: Pair<String, Int>? = null

    var seed: String? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun applyTheme() = invalidate()

    override fun onDraw(canvas: Canvas) {
        val seed = seed ?: return
        val size = min(width, height)
        if (size <= 0) return
        if (drawn != seed to size) {
            drawn = seed to size
            val shape = StemCellGeometry.shape(seed)
            path(shape.membrane, size.toDouble(), membrane)
            path(shape.core, size.toDouble(), core)
        }
        paint.color = Theme.color(StemCellsPalette.stemCellMembrane)
        canvas.drawPath(membrane, paint)
        paint.color = Theme.color(StemCellsPalette.stemCellCore)
        canvas.drawPath(core, paint)
    }

    companion object {
        /** The shape's outline at `size` device pixels. */
        fun path(points: List<Point>, size: Double, into: Path = Path()): Path {
            val (start, segments) = StemCellGeometry.smooth(points, size)
            into.reset()
            into.moveTo(start.x.toFloat(), start.y.toFloat())
            for (segment in segments) {
                into.cubicTo(
                    segment.control1.x.toFloat(), segment.control1.y.toFloat(),
                    segment.control2.x.toFloat(), segment.control2.y.toFloat(),
                    segment.end.x.toFloat(), segment.end.y.toFloat(),
                )
            }
            into.close()
            return into
        }
    }
}
