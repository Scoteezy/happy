package com.slopus.happy.ui.stemcells

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.FontFace
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.stemcells.StemCellsQRSheetLayout
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlin.math.ceil

/**
 * The QR sheet (happy-ios `StemCellsQRSheetViewController`), after
 * Telegram's QR screen: the profile's picture and name in capitals over
 * the blurred page, and a sheet at the foot with a close circle, the
 * title, a line and two buttons. Nothing of a profile is safe to show as a
 * code (a computer's would carry its address, an account's its key), so
 * there is no code card: Scan comes first and pasting a link second. The
 * sheet rises on a spring as the ground fades in, and sinks as it goes.
 * Frames are [StemCellsQRSheetLayout]'s.
 */
class StemCellsQRSheet(context: Context, name: String, image: Bitmap?, isDirect: Boolean) : ManualLayout(context), ThemeAware {
    var onScan: (() -> Unit)? = null
    var onEnterLink: (() -> Unit)? = null
    /** Gone, after its way out. */
    var onGone: (() -> Unit)? = null
    var safeArea = LayoutInsets.Zero
        set(value) { if (field != value) { field = value; requestLayout() } }

    private val ground = View(context)
    private val avatar = ManualLayoutCircle(context, image, isDirect)
    private val name = Label(context, NAME, StemCellsPalette.stemCellsSettingsText, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER).apply { followsTextSize = false }
    private val sheet = object : ManualLayout(context) { override fun layoutSubviews(width: Double, height: Double) {} }
    private val sheetFill = GradientDrawable()
    private val close = View(context)
    private val closeFill = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val closeIcon = IconView(context, StemCellsGlyphs.icon("close"), 24.0, StemCellsPalette.stemCellsSettingsText)
    private val title = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.SemiBold, 17.0), StemCellsPalette.stemCellsSettingsText, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER).apply { followsTextSize = false }
    private val text = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 15.0), StemCellsPalette.stemCellsSettingsSecondary, alignment = Layout.Alignment.ALIGN_CENTER).apply { followsTextSize = false }
    private val primary = Button(context, context.getString(R.string.stem_scan_qr_code), "qr-code-outline", StemCellsPalette.stemCellsSettingsPrimary, StemCellsPalette.stemCellsSettingsPrimaryInk)
    private val secondary = Button(context, context.getString(R.string.stem_enter_link), "link-outline", StemCellsPalette.stemCellsSettingsSecondaryFill, StemCellsPalette.stemCellsSettingsPrimary)
    private var shown = false
    private var closing = false
    private var rise: SpringAnimation? = null

    init {
        tag = "stem-qr"
        isClickable = true
        addView(ground)
        addView(avatar)
        this.name.text = name.uppercase()
        addView(this.name)
        sheet.tag = "stem-qr-sheet"
        sheet.background = sheetFill
        sheet.isClickable = true
        addView(sheet)
        close.background = closeFill
        close.tag = "stem-qr-close"
        close.contentDescription = context.getString(R.string.stem_close)
        close.isClickable = true
        close.setOnClickListener { dismiss() }
        closeIcon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        title.text = context.getString(R.string.stem_qr_code)
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true)
        text.text = context.getString(R.string.stem_qr_text)
        primary.tag = "stem-qr-scan"
        primary.setOnClickListener { finish(onScan) }
        secondary.tag = "stem-qr-link"
        secondary.setOnClickListener { finish(onEnterLink) }
        for (view in listOf(close, closeIcon, title, text, primary, secondary)) sheet.addView(view)
        ground.alpha = 0f
        avatar.alpha = 0f
        this.name.alpha = 0f
        applyTheme()
    }

    /** The ground fades in and the sheet rises. */
    fun present() {
        for (view in listOf(ground, avatar, name)) view.animate().alpha(1f).setDuration(250).start()
        shown = true
        post {
            sheet.translationY = sheet.height.toFloat()
            // UIKit's spring of 0.42 s at 0.9 damping.
            rise = SpringAnimation(sheet, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                spring.dampingRatio = 0.9f
                spring.stiffness = 300f
                start()
            }
        }
    }

    fun dismiss() = finish(null)

    /** Sinks and fades over 0.3 s, then whatever was asked for. */
    private fun finish(next: (() -> Unit)?) {
        if (closing) return
        closing = true
        rise?.cancel()
        for (view in listOf(ground, avatar, name)) view.animate().alpha(0f).setDuration(300).start()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(300).withEndAction {
            (parent as? android.view.ViewGroup)?.removeView(this)
            onGone?.invoke()
            onGone = null
            next?.invoke()
        }.start()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        ground.setFrame(Rect(0.0, 0.0, width, height))
        val layout = StemCellsQRSheetLayout(
            Size(width, height), safeArea.top, safeArea.bottom, ceil(name.lineHeight),
            text.measurable,
        )
        avatar.setFrame(layout.avatar)
        // As UILabel's fit-to-width: the name shrinks to 0.6 of its size before it truncates.
        var size = NAME.size
        name.style = NAME
        while (size > NAME.size * 0.6 && name.measure(100_000.0).width > layout.name.width) {
            size -= 1
            name.style = NAME.copy(size = size)
        }
        name.setFrame(layout.name)
        sheet.setFrame(layout.sheet)
        sheetFill.cornerRadii = FloatArray(8).also { radii ->
            val r = dp(StemCellsQRSheetLayout.Metrics.radius)
            for (i in 0..3) radii[i] = r
        }
        close.setFrame(layout.close)
        closeIcon.setFrame(layout.close)
        title.setFrame(layout.title)
        text.setFrame(layout.text)
        primary.setFrame(layout.primary)
        secondary.setFrame(layout.secondary)
        if (!shown) sheet.translationY = dp(layout.sheet.height)
    }

    /** A touch on the ground goes no further. */
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    override fun applyTheme() {
        // Over the blurred page, the ground's thin material: the page's own colour, mostly through.
        ground.setBackgroundColor(Theme.rgba(StemCellsPalette.stemCellsSettingsGround).withAlpha(0.8).argb)
        sheetFill.setColor(Theme.color(StemCellsPalette.stemCellsSettingsCard))
        closeFill.setColor(Theme.color(StemCellsPalette.stemCellsSettingsAddFill))
        for (view in listOf(closeIcon, name, title, text)) (view as ThemeAware).applyTheme()
        avatar.applyTheme()
        primary.applyTheme()
        secondary.applyTheme()
    }

    private companion object {
        /** iOS's rounded system face, bold, at 23: Android's own sans in bold. */
        val NAME = TextStyle(FontFace.SystemBold, 23.0, null)
    }

    /** The picture in its circle, or Happy's mark or a person on the add fill. */
    private class ManualLayoutCircle(context: Context, image: Bitmap?, isDirect: Boolean) : ManualLayout(context), ThemeAware {
        private val fill = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        private val picture = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val mark = HappyMarkView(context)
        private val person = IconView(context, StemCellsGlyphs.icon("person-outline"), 44.0, StemCellsPalette.stemCellsSettingsText)

        init {
            background = fill
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
            picture.setImageBitmap(image)
            picture.visibility = if (image != null) VISIBLE else GONE
            mark.visibility = if (image == null && isDirect) VISIBLE else GONE
            person.visibility = if (image == null && !isDirect) VISIBLE else GONE
            for (view in listOf(picture, mark, person)) addView(view)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        override fun layoutSubviews(width: Double, height: Double) {
            picture.setFrame(Rect(0.0, 0.0, width, height))
            person.setFrame(Rect(0.0, 0.0, width, height))
            val side = width * 0.36
            mark.setFrame(Rect((width - side) / 2, (height - side) / 2, side, side))
        }

        override fun applyTheme() {
            fill.setColor(Theme.color(StemCellsPalette.stemCellsSettingsAddFill))
            mark.applyTheme()
            person.applyTheme()
        }
    }

    /** A capsule button: a glyph and its title, centred together. */
    private class Button(context: Context, title: String, glyph: String, private val fillToken: ColorToken, private val ink: ColorToken) : ManualLayout(context), ThemeAware {
        private val fill = GradientDrawable()
        private val icon = IconView(context, StemCellsGlyphs.icon(glyph), 20.0, ink)
        private val label = Label(context, StemCellsFonts.ui(StemCellsFonts.Weight.SemiBold, 17.0), ink, maxLines = 1).apply { followsTextSize = false }

        init {
            background = fill
            label.text = title
            contentDescription = title
            for (view in listOf(icon, label)) {
                view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(view)
            }
            isClickable = true
            isFocusable = true
            applyTheme()
        }

        override fun layoutSubviews(width: Double, height: Double) {
            fill.cornerRadius = dp(height / 2)
            val text = label.measure(100_000.0).width
            val block = 20 + 6 + text
            val x = (width - block) / 2
            icon.setFrame(Rect(x, (height - 20) / 2, 20.0, 20.0))
            val line = label.lineHeight
            label.setFrame(Rect(x + 26, (height - line) / 2, text, line))
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            alpha = if (pressed) 0.7f else 1f
        }

        override fun applyTheme() {
            fill.setColor(Theme.color(fillToken))
            icon.applyTheme()
            label.applyTheme()
        }
    }
}
