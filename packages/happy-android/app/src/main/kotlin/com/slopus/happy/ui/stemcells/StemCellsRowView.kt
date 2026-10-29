package com.slopus.happy.ui.stemcells

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.StaticLayout
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.RGBA
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellsMetrics
import com.slopus.happy.layout.TextScale
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.stemcells.StemCellsSidebarLayout
import com.slopus.happy.stemcells.StemCellsRow
import com.slopus.happy.stemcells.StemCellsRow.Kind
import com.slopus.happy.stemcells.StemCellsRow.Mark
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.viewScope
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.setFrameOrHide
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * One row of the Stem Cells sidebar as desktop's `Sidebar` draws it
 * (happy-ios `StemCellsRowView`): a rounded box, the mark in its leading
 * lane, the label, and the trailing square for a status or a section's
 * fold. Unread is the title in bold, nothing beside it. It applies the
 * frames [StemCellsSidebarLayout] gives it and decides nothing about where
 * anything goes.
 */
class StemCellsRowView(context: Context) : ManualLayout(context), ThemeAware {
    private val fill = GradientDrawable()
    private val stemCell = StemCellMarkView(context)
    private val picture = ImageView(context)
    private val pictureShape = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(StemCellsMetrics.pictureRadius))
        }
    }
    private val glyph = IconView(context, null, StemCellsMetrics.glyph, StemCellsPalette.stemCellsRowText)
    private val label = StemCellsShimmerLabel(context)
    private val trailing = IconView(context, null, StemCellsMetrics.questionGlyph, StemCellsPalette.stemCellsRowText)
    private val chevron = IconView(context, null, StemCellsMetrics.chevronGlyph, StemCellsPalette.stemCellsRowText)
    private val add = IconView(context, StemCellsGlyphs.icon("add"), StemCellsMetrics.addGlyph, StemCellsPalette.stemCellsRowText)
    private val rule = View(context)
    private var pictureJob: Job? = null

    var row: StemCellsRow? = null
        private set
    var images: StemCellsImages? = null
    var frames: StemCellsSidebarLayout.Frame? = null
        set(value) { if (field != value) { field = value; requestLayout() } }
    /** The row whose conversation is open (View's own `isSelected` is left alone). */
    var current = false
        set(value) { if (field != value) { field = value; applyState() } }
    /** A finger is on the row (its pressed state, which a scroll cancels). */
    var touched = false
        private set(value) { if (field != value) { field = value; applyState() } }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        touched = pressed
    }

    init {
        background = fill
        fill.cornerRadius = dp(StemCellsMetrics.cornerRadius)
        picture.scaleType = ImageView.ScaleType.FIT_CENTER
        picture.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        for (view in listOf(stemCell, picture, glyph, label, trailing, chevron, add, rule)) {
            view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(view)
        }
        isFocusable = true
    }

    /** Shows `row`; a row equal to the one shown changes nothing. */
    fun configure(row: StemCellsRow) {
        if (row == this.row) return
        val previous = this.row
        this.row = row
        if (previous?.mark != row.mark) applyMark(row.mark)
        applyText(row)
        applyTrailing(row)
        applyState()
        contentDescription = if (row.kind == Kind.Separator) null else row.label
        importantForAccessibility = if (row.kind == Kind.Separator) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = row.kind != Kind.Separator
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, row.kind == Kind.Section)
        tag = "stem-row:${row.kind.name.lowercase()}:${row.label}"
        requestLayout()
    }

    private fun applyMark(mark: Mark) {
        pictureJob?.cancel()
        pictureJob = null
        stemCell.seed = null
        stemCell.visibility = GONE
        picture.visibility = GONE
        picture.setImageDrawable(null)
        picture.scaleType = ImageView.ScaleType.FIT_CENTER
        picture.clipToOutline = false
        picture.outlineProvider = null
        glyph.visibility = GONE
        when (mark) {
            Mark.None -> {}
            is Mark.StemCell -> {
                stemCell.visibility = VISIBLE
                stemCell.seed = mark.seed
            }
            Mark.Happy -> showPicture(ClayIconImages.happy(context))
            // Clay art stands as drawn: no frame, no rounding.
            is Mark.Clay -> showPicture(ClayIconImages.image(context, mark.slug))
            is Mark.Glyph -> {
                glyph.visibility = VISIBLE
                glyph.icon = StemCellsGlyphs.icon(mark.name)
            }
            is Mark.Image -> {
                // A picture is a rounded square.
                picture.visibility = VISIBLE
                picture.scaleType = ImageView.ScaleType.CENTER_CROP
                picture.outlineProvider = pictureShape
                picture.clipToOutline = true
                val pixels = (StemCellsMetrics.mark * 3).toInt()
                val cached = images?.cachedPicture(mark.path, mark.thumbhash, pixels)
                if (cached != null) {
                    picture.setImageBitmap(cached)
                } else {
                    picture.setImageBitmap(StemCellsImages.placeholder(mark.thumbhash))
                    val images = images ?: return
                    pictureJob = viewScope.launch {
                        val image = images.picture(mark.path, mark.thumbhash, pixels) ?: return@launch
                        picture.setImageBitmap(image)
                    }
                }
            }
        }
    }

    private fun showPicture(bitmap: Bitmap?) {
        picture.visibility = VISIBLE
        picture.setImageBitmap(bitmap)
    }

    private fun applyText(row: StemCellsRow) {
        when (row.kind) {
            Kind.Section -> {
                label.style = StemCellsFonts.mono(true, StemCellsMetrics.sectionLabelSize, StemCellsMetrics.sectionLabelSize * StemCellsMetrics.sectionTracking)
                label.text = row.label.uppercase()
            }
            Kind.Separator -> label.text = ""
            else -> {
                val weight = if (row.unread) StemCellsFonts.Weight.Bold else if (current) StemCellsFonts.Weight.SemiBold else StemCellsFonts.Weight.Medium
                label.style = StemCellsFonts.ui(weight, StemCellsMetrics.labelSize)
                label.text = row.label
            }
        }
        // Working rows shimmer their title; nothing else spins.
        label.shimmering = row.status == StemCellsRow.Status.Working && (row.kind == Kind.Space || row.kind == Kind.Session)
    }

    private fun applyTrailing(row: StemCellsRow) {
        trailing.visibility = GONE
        val folds = row.kind == Kind.Section || ((row.kind == Kind.Space || row.kind == Kind.Session) && row.collapsed != null)
        chevron.visibility = if (folds) VISIBLE else GONE
        rule.visibility = if (row.kind == Kind.Separator) VISIBLE else GONE
        if (folds) {
            // Closed while folded or while it has subtasks still to show.
            val closed = row.collapsed == true || row.hasHiddenSessions
            chevron.icon = StemCellsGlyphs.icon(if (closed) "chevron-right" else "chevron-down")
        }
        when (row.status) {
            StemCellsRow.Status.AwaitingInput -> {
                trailing.visibility = VISIBLE
                trailing.icon = StemCellsGlyphs.icon("question")
                trailing.size = StemCellsMetrics.questionGlyph
            }
            StemCellsRow.Status.Waiting -> {
                trailing.visibility = VISIBLE
                trailing.icon = StemCellsGlyphs.icon("clock")
                trailing.size = StemCellsMetrics.clockGlyph
            }
            StemCellsRow.Status.Working, StemCellsRow.Status.None -> {}
        }
    }

    private fun applyState() {
        val row = row
        val ink = if (current || row?.unread == true) StemCellsPalette.stemCellsRowTextActive else StemCellsPalette.stemCellsRowText
        label.color = ink
        label.ground = if (current) StemCellsPalette.stemCellsRowSelected else Palette.surface
        glyph.tint = ink
        if (row != null && row.kind != Kind.Section && row.kind != Kind.Separator) applyText(row)
        val carryable = row?.kind != Kind.Section && row?.kind != Kind.Separator
        fill.setColor(
            when {
                current -> Theme.color(StemCellsPalette.stemCellsRowSelected)
                touched && carryable -> Theme.color(StemCellsPalette.stemCellsRowPressed)
                else -> 0
            },
        )
        rule.setBackgroundColor(Theme.color(StemCellsPalette.stemCellsDivider))
    }

    override fun applyTheme() = applyState()

    override fun layoutSubviews(width: Double, height: Double) {
        val frames = frames ?: return
        // Frames are in sidebar content coordinates; this view's own frame is the box.
        val origin = frames.box
        fun local(rect: Rect) = rect.offsetBy(-origin.minX, -origin.minY)
        val mark = frames.mark?.let(::local) ?: Rect.Zero
        stemCell.setFrame(mark)
        picture.setFrame(mark)
        glyph.setFrame(mark)
        label.setFrame(local(frames.label))
        trailing.setFrame(frames.trailing?.let(::local) ?: Rect.Zero)
        chevron.setFrame(frames.chevronGlyph?.let(::local) ?: Rect.Zero)
        add.setFrameOrHide(frames.add?.let(::local))
        rule.setFrame(frames.rule?.let(::local) ?: Rect.Zero)
    }
}

/**
 * One line of text that can carry desktop's ShimmerText in its `sheen`
 * sweep (happy-ios `StemCellsShimmerLabel`): the letters keep their own
 * colour and a pale band, the ink 15% into the ground, wipes through them,
 * the letters never moving. The band is a gradient over a second copy of
 * the text; its ramp is three widths of the text sliding two widths a
 * sweep, as desktop's background is. A change of text or width keeps the
 * sweep where it was. With animations off the text simply stays.
 */
class StemCellsShimmerLabel(context: Context) : View(context), ThemeAware {
    var text: String = ""
        set(value) { if (field != value) { field = value; invalidateText() } }
    var style: TextStyle = StemCellsFonts.ui(StemCellsFonts.Weight.Medium, StemCellsMetrics.labelSize)
        set(value) { if (field != value) { field = value; invalidateText() } }
    var color: ColorToken = StemCellsPalette.stemCellsRowText
        set(value) { if (field != value) { field = value; invalidateText() } }
    /** What the band goes toward: the surface behind the text. */
    var ground: ColorToken = Palette.surface
        set(value) { if (field != value) { field = value; invalidateText() } }

    var shimmering = false
        set(value) {
            if (field == value) return
            field = value
            sweepStart = if (value) SystemClock.uptimeMillis() else null
            invalidate()
        }

    /** When the running sweep began; a restart picks it up from here. */
    private var sweepStart: Long? = null
    private var base: StaticLayout? = null
    private var pale: StaticLayout? = null
    private var builtWidth = -1

    private val env get() = TextLayouts.Env(Theme.density, TextScale.Standard)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun invalidateText() {
        base = null
        pale = null
        invalidate()
    }

    override fun applyTheme() = invalidateText()

    private val sweeps get() = shimmering && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || text.isEmpty()) return
        if (base == null || builtWidth != width) {
            val ink = Theme.rgba(color)
            base = TextLayouts.build(text, style, px(width), env, ink.argb, maxLines = 1)
            pale = TextLayouts.build(text, style, px(width), env, mix(ink, Theme.rgba(ground), StemCellsMetrics.sheenInk).argb, maxLines = 1)
            builtWidth = width
        }
        val base = base ?: return
        val top = (height - base.height) / 2f
        canvas.save()
        canvas.translate(0f, top)
        base.draw(canvas)
        val pale = pale
        val start = sweepStart
        if (sweeps && pale != null && start != null) {
            val textWidth = max(1f, min(width.toFloat(), base.getLineWidth(0)))
            val period = (StemCellsMetrics.sheenDuration * 1000).toLong()
            val phase = ((SystemClock.uptimeMillis() - start) % period) / period.toFloat()
            // The band's middle runs from half a width before the text to half a width after it.
            val peak = -0.5f * textWidth + 2f * textWidth * phase
            val reach = 0.3f * textWidth
            val tone = pale.paint.color
            pale.paint.shader = LinearGradient(peak - reach, 0f, peak + reach, 0f, intArrayOf(tone and 0x00FFFFFF, tone, tone and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            pale.draw(canvas)
            pale.paint.shader = null
            postInvalidateOnAnimation()
        }
        canvas.restore()
    }

    private companion object {
        /** `color-mix(in srgb, ink keep%, ground)`. */
        fun mix(ink: RGBA, ground: RGBA, keep: Double): RGBA {
            val rest = 1 - keep
            return RGBA(ink.red * keep + ground.red * rest, ink.green * keep + ground.green * rest, ink.blue * keep + ground.blue * rest, ink.alpha * keep + ground.alpha * rest)
        }
    }
}
