package com.slopus.happy.ui.view

import android.content.Context
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.PathElement
import com.slopus.happy.layout.PixelGrid
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.VectorPath
import com.slopus.happy.ui.theme.Theme
import kotlin.math.roundToInt

// The bridge between :layout's frames (dp, Double) and Android's views
// (device pixels, Int), as happy-ios `setLayoutFrame` bridges HappyLayout and
// UIKit: a view applies the frames a layout computed, snapped to the pixel
// grid, and contains no arithmetic that decides where something goes.

val density: Float get() = Theme.density

fun dp(value: Double): Float = (value * density).toFloat()
fun dp(value: Int): Int = (value * density).roundToInt()
fun px(value: Int): Double = value / density.toDouble()
fun px(value: Float): Double = value / density.toDouble()

/** Measures and places this view at [frame] (dp, in its parent's coordinates), snapped to device pixels. */
fun View.setFrame(frame: Rect) {
    val snapped = PixelGrid.snap(frame, density.toDouble())
    val l = (snapped.minX * density).roundToInt()
    val t = (snapped.minY * density).roundToInt()
    val r = (snapped.maxX * density).roundToInt()
    val b = (snapped.maxY * density).roundToInt()
    val w = r - l
    val h = b - t
    if (measuredWidth != w || measuredHeight != h || isLayoutRequested) {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
    }
    layout(l, t, r, b)
}

/** This view's current size in dp. */
val View.sizeDp: Size get() = Size(px(width), px(height))

/** Hides a view whose frame is absent, places it otherwise. */
fun View.setFrameOrHide(frame: Rect?) {
    if (frame == null) {
        visibility = View.GONE
    } else {
        visibility = View.VISIBLE
        setFrame(frame)
    }
}

/**
 * A ViewGroup laid out by hand (happy-ios views with `layoutSubviews`):
 * [sizeThatFits] answers its size for an offered width, and [layoutSubviews]
 * applies frames from a pure layout. By default it fills what its parent gives it.
 */
abstract class ManualLayout(context: Context) : ViewGroup(context) {
    /** The size this view wants when offered [width] x [height] dp; the offer by default. */
    open fun sizeThatFits(width: Double, height: Double): Size = Size(width, height)

    abstract fun layoutSubviews(width: Double, height: Double)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val w = px(MeasureSpec.getSize(widthMeasureSpec))
        val h = if (hMode == MeasureSpec.UNSPECIFIED) Double.MAX_VALUE else px(MeasureSpec.getSize(heightMeasureSpec))
        val fit = sizeThatFits(w, h)
        val width = if (wMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else dp(fit.width).roundToInt()
        val height = if (hMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else dp(fit.height).roundToInt()
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        layoutSubviews(px(r - l), px(b - t))
    }

    override fun shouldDelayChildPressedState() = false
}

/** A system inset or padding in dp. */
fun androidx.core.graphics.Insets.toLayoutInsets() =
    LayoutInsets(px(top), px(left), px(bottom), px(right))

/** The outline as an Android path (dp units multiplied by [scale]). */
fun VectorPath.toAndroidPath(scale: Float = 1f, into: Path = Path()): Path {
    into.reset()
    for (e in elements) {
        when (e) {
            is PathElement.Move -> into.moveTo((e.to.x * scale).toFloat(), (e.to.y * scale).toFloat())
            is PathElement.Line -> into.lineTo((e.to.x * scale).toFloat(), (e.to.y * scale).toFloat())
            is PathElement.QuadCurve -> into.quadTo(
                (e.control.x * scale).toFloat(), (e.control.y * scale).toFloat(),
                (e.to.x * scale).toFloat(), (e.to.y * scale).toFloat(),
            )
            is PathElement.Curve -> into.cubicTo(
                (e.control1.x * scale).toFloat(), (e.control1.y * scale).toFloat(),
                (e.control2.x * scale).toFloat(), (e.control2.y * scale).toFloat(),
                (e.to.x * scale).toFloat(), (e.to.y * scale).toFloat(),
            )
            PathElement.Close -> into.close()
        }
    }
    return into
}
