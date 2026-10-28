package com.slopus.happy.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.slopus.happy.icons.Icon
import com.slopus.happy.icons.IconCatalog
import com.slopus.happy.icons.IconGlyph
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.toAndroidPath

/**
 * One of happy-app's icons, drawn from its vector outline in a theme colour
 * (happy-ios `IconView`). It sizes itself as RN's icon `<Text>` box and
 * centres the glyph in whatever frame it gets, floored to the pixel as Yoga does.
 */
class IconView(context: Context, icon: Icon? = null, size: Double, tint: ColorToken = Palette.text) : View(context), ThemeAware {
    var icon: Icon? = icon
        set(value) { if (field != value) { field = value; update() } }

    var size: Double = size
        set(value) { if (field != value) { field = value; update() } }

    var tint: ColorToken = tint
        set(value) { if (field != value) { field = value; invalidate() } }

    /** A raw colour overriding [tint], for colours that are not tokens. */
    var tintOverride: Int? = null
        set(value) { field = value; invalidate() }

    private var glyph: IconGlyph? = null
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        update()
    }

    /** RN's text box for the icon, in dp. */
    fun intrinsicSize(): Size = glyph?.layout(size, Theme.density.toDouble())?.size ?: Size(size, size)

    private fun update() {
        glyph = icon?.let { IconCatalog.glyph(it) }
        requestLayout()
        invalidate()
    }

    override fun applyTheme() = invalidate()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = intrinsicSize()
        setMeasuredDimension(
            resolveSize((s.width * Theme.density).toInt(), widthMeasureSpec),
            resolveSize((s.height * Theme.density).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val glyph = glyph ?: return
        val d = Theme.density.toDouble()
        val layout = glyph.layout(size, d)
        val box = layout.frame(Rect(0.0, 0.0, px(width), px(height)), d)
        glyph.path.toAndroidPath(scale = (layout.glyphScale * d).toFloat(), into = path)
        paint.color = tintOverride ?: Theme.color(tint)
        canvas.save()
        canvas.translate((box.x * d).toFloat(), (box.y * d).toFloat())
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
