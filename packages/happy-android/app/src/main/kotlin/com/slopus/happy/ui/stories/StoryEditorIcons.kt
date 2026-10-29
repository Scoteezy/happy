package com.slopus.happy.ui.stories

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.slopus.happy.stories.StoryTextAlign
import com.slopus.happy.stories.StoryTextFill
import com.slopus.happy.ui.view.density
import kotlin.math.PI

/**
 * The editor's glyphs (happy-ios `StoryEditorIcons`), drawn in the shapes
 * Telegram's media editor uses (white strokes on a 30dp square): back and
 * next, add text, add a sticker, the text styles, alignment, the font's
 * chevrons, the colour wheel, the link and the bin; and the viewer's sound,
 * on and off. Each is drawn in dp by [StoryGlyph.draw], in the tint it is
 * given where iOS renders a template image.
 */
class StoryGlyph(val width: Double, val height: Double, private val body: (Canvas, Pen) -> Unit) {
    /** The stroke and fill of one drawing, both in the tint. */
    class Pen(val stroke: Paint, val fill: Paint) {
        fun line(width: Float) { stroke.strokeWidth = width }
        fun strokePath(canvas: Canvas, path: Path) = canvas.drawPath(path, stroke)
    }

    /** Draws it at [x], [y] (px) in [tint], [scale] dp to a unit. */
    fun draw(canvas: Canvas, x: Float, y: Float, tint: Int, scale: Float = density) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.66f
            color = tint
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = tint }
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        body(canvas, Pen(stroke, fill))
        canvas.restore()
    }
}

object StoryEditorIcons {
    private fun path(build: Path.() -> Unit) = Path().apply(build)

    val back = StoryGlyph(24.0, 24.0) { c, pen ->
        pen.line(2.2f)
        pen.strokePath(c, path { moveTo(15f, 4.5f); lineTo(7.5f, 12f); lineTo(15f, 19.5f) })
    }

    val next = StoryGlyph(10.0, 16.0) { c, pen ->
        pen.line(2.2f)
        pen.strokePath(c, path { moveTo(2.5f, 2f); lineTo(8f, 8f); lineTo(2.5f, 14f) })
    }

    /** A rounded square open at its bottom-left corner, where a "+" sits. */
    private fun frameWithPlus(c: Canvas, pen: StoryGlyph.Pen) {
        val r = 6f
        pen.strokePath(c, path {
            moveTo(5.5f, 17f)
            lineTo(5.5f, 5.5f + r)
            arcTo(RectF(5.5f, 5.5f, 5.5f + 2 * r, 5.5f + 2 * r), 180f, 90f)
            lineTo(24.5f - r, 5.5f)
            arcTo(RectF(24.5f - 2 * r, 5.5f, 24.5f, 5.5f + 2 * r), 270f, 90f)
            lineTo(24.5f, 24.5f - r)
            arcTo(RectF(24.5f - 2 * r, 24.5f - 2 * r, 24.5f, 24.5f), 0f, 90f)
            lineTo(13f, 24.5f)
        })
        pen.strokePath(c, path { moveTo(5.5f, 20.5f); lineTo(5.5f, 28.5f); moveTo(1.5f, 24.5f); lineTo(9.5f, 24.5f) })
    }

    val addText = StoryGlyph(30.0, 30.0) { c, pen ->
        frameWithPlus(c, pen)
        pen.strokePath(c, path { moveTo(11f, 11f); lineTo(19f, 11f); moveTo(15f, 11f); lineTo(15f, 19.5f) })
    }

    /** A sticker: the square with its bottom-right corner peeled, and a smile. */
    val addSticker = StoryGlyph(30.0, 30.0) { c, pen ->
        val r = 6f
        pen.strokePath(c, path {
            moveTo(5.5f, 17f)
            lineTo(5.5f, 5.5f + r)
            arcTo(RectF(5.5f, 5.5f, 5.5f + 2 * r, 5.5f + 2 * r), 180f, 90f)
            lineTo(24.5f - r, 5.5f)
            arcTo(RectF(24.5f - 2 * r, 5.5f, 24.5f, 5.5f + 2 * r), 270f, 90f)
            lineTo(24.5f, 16.5f)
            lineTo(16.5f, 24.5f)
            lineTo(13f, 24.5f)
        })
        pen.strokePath(c, path { moveTo(24.5f, 16.5f); quadTo(16.5f, 16.5f, 16.5f, 24.5f) })
        c.drawOval(RectF(10.6f, 10.6f, 13f, 13f), pen.fill)
        c.drawOval(RectF(17f, 10.6f, 19.4f, 13f), pen.fill)
        pen.strokePath(c, path { moveTo(10.5f, 16.5f); quadTo(12.5f, 19.2f, 16f, 19f) })
        pen.strokePath(c, path { moveTo(5.5f, 20.5f); lineTo(5.5f, 28.5f); moveTo(1.5f, 24.5f); lineTo(9.5f, 24.5f) })
    }

    val link = StoryGlyph(20.0, 20.0) { c, pen ->
        pen.line(1.8f)
        pen.strokePath(c, path { moveTo(8.6f, 11.4f); lineTo(11.4f, 8.6f) })
        // Two links, each a rounded bar turned 45°.
        for ((x, y) in listOf(6.6f to 13.4f, 13.4f to 6.6f)) {
            c.save()
            c.translate(x, y)
            c.rotate(-45f)
            c.drawRoundRect(RectF(-5.2f, -2.9f, 5.2f, 2.9f), 2.9f, 2.9f, pen.stroke)
            c.restore()
        }
    }

    // Text tool

    private val bold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    /** The style button's four looks: plain, on a white box, on a grey box, outlined. */
    fun style(fill: StoryTextFill) = StoryGlyph(30.0, 30.0) { c, pen ->
        val box = RectF(1f, 1f, 29f, 29f)
        val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = bold
            textSize = 19f
            textAlign = Paint.Align.CENTER
            color = pen.fill.color
        }
        var outline: Paint? = null
        when (fill) {
            StoryTextFill.Filled -> {
                c.drawRoundRect(box, 7f, 7f, pen.fill)
                letter.color = Color.BLACK
            }
            StoryTextFill.Semi -> {
                val grey = Paint(pen.fill).apply { alpha = (0.3f * 255).toInt() }
                c.drawRoundRect(box, 7f, 7f, grey)
            }
            StoryTextFill.Stroke, StoryTextFill.Blur -> {
                letter.color = Color.BLACK
                outline = Paint(letter).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 19f * 0.06f
                    color = pen.fill.color
                }
            }
            StoryTextFill.Regular -> {}
        }
        val fm = letter.fontMetrics
        val baseline = 15f - (fm.ascent + fm.descent) / 2
        c.drawText("A", 15f, baseline, letter)
        outline?.let { c.drawText("A", 15f, baseline, it) }
    }

    /** Three lines, set left, centred or right. */
    fun align(align: StoryTextAlign) = StoryGlyph(30.0, 30.0) { c, pen ->
        pen.line(2f)
        val lines = Path()
        for ((index, w) in listOf(18f, 11f, 18f).withIndex()) {
            val x = when (align) {
                StoryTextAlign.Left -> 6f
                StoryTextAlign.Center -> 15 - w / 2
                StoryTextAlign.Right -> 24 - w
            }
            val y = 9 + index * 6f
            lines.moveTo(x, y)
            lines.lineTo(x + w, y)
        }
        pen.strokePath(c, lines)
    }

    /** The font pill's chevrons, up and down. */
    val fontArrow = StoryGlyph(16.0, 16.0) { c, pen ->
        pen.stroke.alpha = 128
        pen.line(1.5f)
        pen.strokePath(c, path {
            moveTo(4.5f, 6f); lineTo(8f, 2.5f); lineTo(11.5f, 6f)
            moveTo(4.5f, 10f); lineTo(8f, 13.5f); lineTo(11.5f, 10f)
        })
    }

    /**
     * The colour swatch: a hue wheel washing to white at its middle, as a
     * 24dp ring around the chosen colour.
     */
    fun swatch(color: Int) = StoryGlyph(24.0, 24.0) { c, _ ->
        val steps = 72
        val wedge = Paint(Paint.ANTI_ALIAS_FLAG)
        val box = RectF(0f, 0f, 24f, 24f)
        for (step in 0 until steps) {
            val start = step.toFloat() / steps * 360f - 90f
            wedge.color = Color.HSVToColor(floatArrayOf(step.toFloat() / steps * 360f, 0.9f, 1f))
            c.drawArc(box, start, 360f / steps + 1.2f, true, wedge)
        }
        wedge.color = Color.BLACK
        c.drawOval(RectF(3f, 3f, 21f, 21f), wedge)
        wedge.color = color
        c.drawOval(RectF(4.5f, 4.5f, 19.5f, 19.5f), wedge)
    }

    // Bin

    /** The bin's can and its lid, apart, so the lid can open. */
    val binCan = StoryGlyph(30.0, 30.0) { c, pen ->
        pen.line(2f)
        pen.strokePath(c, path {
            moveTo(8f, 10f)
            lineTo(9.4f, 24.2f)
            quadTo(9.6f, 26f, 11.4f, 26f)
            lineTo(18.6f, 26f)
            quadTo(20.4f, 26f, 20.6f, 24.2f)
            lineTo(22f, 10f)
            moveTo(12.8f, 14f); lineTo(13.2f, 22f)
            moveTo(17.2f, 14f); lineTo(16.8f, 22f)
        })
    }

    val binLid = StoryGlyph(30.0, 30.0) { c, pen ->
        pen.line(2f)
        pen.strokePath(c, path {
            moveTo(6f, 7.5f); lineTo(24f, 7.5f)
            moveTo(12f, 7.5f)
            lineTo(12.6f, 5f)
            lineTo(17.4f, 5f)
            lineTo(18f, 7.5f)
        })
    }

    val plus = StoryGlyph(28.0, 28.0) { c, pen ->
        pen.line(2.2f)
        pen.strokePath(c, path { moveTo(14f, 6f); lineTo(14f, 22f); moveTo(6f, 14f); lineTo(22f, 14f) })
    }

    /** A speaker with its waves, or struck through. */
    fun sound(on: Boolean) = StoryGlyph(30.0, 30.0) { c, pen ->
        c.drawPath(path {
            moveTo(5.5f, 12f); lineTo(9.5f, 12f); lineTo(15f, 7f); lineTo(15f, 23f); lineTo(9.5f, 18f); lineTo(5.5f, 18f); close()
        }, pen.fill)
        if (on) {
            for ((radius, half) in listOf(4.5f to (PI / 3.6), 8.5f to (PI / 3.4))) {
                val box = RectF(15 - radius, 15 - radius, 15 + radius, 15 + radius)
                val degrees = Math.toDegrees(half).toFloat()
                c.drawArc(box, -degrees, 2 * degrees, false, pen.stroke)
            }
        } else {
            pen.line(2f)
            pen.strokePath(c, path { moveTo(18.5f, 11.5f); lineTo(25.5f, 18.5f); moveTo(25.5f, 11.5f); lineTo(18.5f, 18.5f) })
        }
    }

    /** The reaction sticker in the sheet: a white round speech bubble. */
    val reactionBubble = StoryGlyph(52.0, 52.0) { c, pen ->
        c.drawOval(RectF(4f, 3f, 48f, 47f), pen.fill)
        // The tail at the bottom left.
        c.drawPath(path { moveTo(9f, 36f); quadTo(8f, 45f, 4f, 49f); quadTo(13f, 48f, 19f, 44f); close() }, pen.fill)
    }
}

/** One [StoryGlyph] centred in its bounds, in a tint (a button's template image). */
class GlyphView(context: Context, glyph: StoryGlyph? = null, tint: Int = Color.WHITE) : View(context) {
    var glyph: StoryGlyph? = glyph
        set(value) { field = value; invalidate() }
    var tint: Int = tint
        set(value) { field = value; invalidate() }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val glyph = glyph ?: return
        val w = (glyph.width * density).toFloat()
        val h = (glyph.height * density).toFloat()
        glyph.draw(canvas, (width - w) / 2, (height - h) / 2, tint)
    }
}
