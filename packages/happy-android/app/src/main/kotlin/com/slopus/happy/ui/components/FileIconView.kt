package com.slopus.happy.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.slopus.happy.icons.FileIconGraphic
import com.slopus.happy.icons.FileIcons
import com.slopus.happy.icons.SetiColor
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.FileIconPalette
import com.slopus.happy.layout.Rect
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.toAndroidPath

/**
 * components/FileIcon.tsx (happy-ios `FileIconView`): the Seti icon for a
 * file name, filled with its theme colour, fitted into the view's bounds.
 */
class FileIconView(context: Context) : View(context), ThemeAware {
    var fileName = ""
        set(value) {
            if (field == value) return
            field = value
            graphic = FileIcons.icon(value)
            invalidate()
        }

    private var graphic: FileIconGraphic? = null
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun applyTheme() = invalidate()

    override fun onDraw(canvas: Canvas) {
        val graphic = graphic ?: return
        val fill = Theme.color(color(graphic.color))
        val d = Theme.density
        for (shape in graphic.fitted(Rect(0.0, 0.0, px(width), px(height)))) {
            shape.path.toAndroidPath(d, path)
            path.fillType = if (shape.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            paint.color = fill
            paint.alpha = (android.graphics.Color.alpha(fill) * shape.opacity).toInt()
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        fun color(color: SetiColor): ColorToken = when (color) {
            SetiColor.Blue -> FileIconPalette.blue
            SetiColor.Grey -> FileIconPalette.grey
            SetiColor.GreyLight -> FileIconPalette.greyLight
            SetiColor.Green -> FileIconPalette.green
            SetiColor.Orange -> FileIconPalette.orange
            SetiColor.Pink -> FileIconPalette.pink
            SetiColor.Purple -> FileIconPalette.purple
            SetiColor.Red -> FileIconPalette.red
            SetiColor.White -> FileIconPalette.white
            SetiColor.Yellow -> FileIconPalette.yellow
            SetiColor.Ignore -> FileIconPalette.ignore
        }
    }
}
