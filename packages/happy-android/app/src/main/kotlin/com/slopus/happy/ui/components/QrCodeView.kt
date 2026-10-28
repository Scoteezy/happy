package com.slopus.happy.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.slopus.happy.connect.QrMatrix

/**
 * QRCode.tsx (happy-ios `QRCodeView`): the code with two modules of quiet
 * zone on a background rounded by 1.5 modules. A module's corner is rounded
 * (by min(module / 3, 1% of the size)) where neither neighbouring side is
 * dark; modules overlap by half a dp so no seams show. The finder patterns
 * are rounded squares: a 7-module ring around a 3-module eye.
 */
class QrCodeView(context: Context) : View(context) {
    var payload: String? = null
        set(value) {
            if (field == value) return
            field = value
            matrix = value?.let { QrMatrix.encode(it) }
            contentDescription = value
            invalidate()
        }

    var foreground: Int = 0xFF000000.toInt()
    var backgroundFill: Int = 0xFFFFFFFF.toInt()

    private var matrix: QrMatrix? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val radii = FloatArray(8)

    override fun onDraw(canvas: Canvas) {
        val matrix = matrix ?: return
        val side = width.toFloat()
        val n = matrix.size
        val m = side / (n + 4)
        val half = 0.5f * resources.displayMetrics.density
        val corner = minOf(m / 3, side * 0.01f)

        paint.color = backgroundFill
        rect.set(0f, 0f, side, side)
        canvas.drawRoundRect(rect, m * 1.5f, m * 1.5f, paint)

        canvas.save()
        canvas.translate(m * 2, m * 2)
        path.reset()
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (!matrix.dark(x, y)) continue
                val left = !matrix.dark(x - 1, y)
                val right = !matrix.dark(x + 1, y)
                val above = !matrix.dark(x, y - 1)
                val below = !matrix.dark(x, y + 1)
                fun r(round: Boolean) = if (round) corner else 0f
                val tl = r(left && above)
                val tr = r(right && above)
                val br = r(right && below)
                val bl = r(left && below)
                radii[0] = tl; radii[1] = tl; radii[2] = tr; radii[3] = tr
                radii[4] = br; radii[5] = br; radii[6] = bl; radii[7] = bl
                rect.set(x * m - half, y * m - half, x * m + m + half, y * m + m + half)
                path.addRoundRect(rect, radii, Path.Direction.CW)
            }
        }
        paint.color = foreground
        canvas.drawPath(path, paint)

        for ((fx, fy) in listOf(0 to 0, (n - 7) to 0, 0 to (n - 7))) {
            val ox = fx * m
            val oy = fy * m
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            rect.set(ox, oy, ox + 7 * m, oy + 7 * m)
            path.addRoundRect(rect, m * 1.5f, m * 1.5f, Path.Direction.CW)
            rect.set(ox + m, oy + m, ox + 6 * m, oy + 6 * m)
            path.addRoundRect(rect, m * 0.5f, m * 0.5f, Path.Direction.CW)
            canvas.drawPath(path, paint)
            path.fillType = Path.FillType.WINDING
            rect.set(ox + 2 * m, oy + 2 * m, ox + 5 * m, oy + 5 * m)
            canvas.drawRoundRect(rect, m, m, paint)
        }
        canvas.restore()
    }
}
