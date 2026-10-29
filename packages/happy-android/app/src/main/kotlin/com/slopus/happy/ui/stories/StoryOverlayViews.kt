package com.slopus.happy.ui.stories

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.slopus.happy.icons.Icon
import com.slopus.happy.icons.IconCatalog
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.stories.StoryCanvasLayout
import com.slopus.happy.stories.Sticker
import com.slopus.happy.stories.StickerFormat
import com.slopus.happy.stories.StickerRef
import com.slopus.happy.stories.StoryLinkStyle
import com.slopus.happy.stories.StoryOverlay
import com.slopus.happy.stories.StoryTextAlign
import com.slopus.happy.stories.StoryTextFill
import com.slopus.happy.stories.StoryTextFont
import com.slopus.happy.stories.StoryTextStyle
import com.slopus.happy.stories.StoryTransform
import com.slopus.happy.ui.view.density
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.toAndroidPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.net.URI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One overlay on a frame (happy-ios `StoryOverlayView`), drawn from its data
 * at its unscaled size; the canvas places it at its centre and turns and
 * scales it. Every kind draws the same on the viewer, the editor and the
 * inline story. Sizes are in dp.
 */
abstract class StoryOverlayView(context: Context, overlay: StoryOverlay) : View(context) {
    var overlay: StoryOverlay = overlay
        private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    val overlayId: String get() = overlay.id ?: ""

    /**
     * Takes a changed overlay of the same kind; false when it can't. Only
     * moving it draws nothing again.
     */
    open fun update(overlay: StoryOverlay): Boolean {
        val redraw = !sameLook(overlay, this.overlay)
        this.overlay = overlay
        if (redraw) changed()
        return true
    }

    /** The look changed: measured and drawn again. */
    protected open fun changed() {
        laidOutCanvas = null
        invalidate()
    }

    /** The unscaled size on a canvas of [canvas]. */
    abstract fun baseSize(canvas: Size): Size

    /** Whether the canvas scales this view by `transform.scale`, or the size already includes it. */
    open val scalesWithTransform: Boolean get() = false

    private var laidOutCanvas: Size? = null
    private var laidOutSize: Size? = null

    /** The unscaled size it was last placed at. */
    val baseBounds: Size get() = laidOutSize ?: Size.Zero

    /** Places it on a canvas of [canvas], in the canvas's coordinates. */
    fun place(canvas: Size) {
        val t = overlay.transform ?: return
        val size = baseSize(canvas)
        val center = StoryCanvasLayout.center(t.x, t.y, canvas)
        if (laidOutSize != size || laidOutCanvas != canvas) {
            laidOutSize = size
            laidOutCanvas = canvas
            invalidate()
        }
        setFrame(Rect(center.x - size.width / 2, center.y - size.height / 2, size.width, size.height))
        // About its middle, as a UIView's transform turns it.
        pivotX = width / 2f
        pivotY = height / 2f
        rotation = Math.toDegrees(t.rotation).toFloat()
        val scale = if (scalesWithTransform) t.scale.toFloat() else 1f
        scaleX = scale
        scaleY = scale
    }

    companion object {
        /** The same overlay but for where it is. */
        fun sameLook(a: StoryOverlay, b: StoryOverlay): Boolean =
            a.withTransform(StoryTransform.FITTED) == b.withTransform(StoryTransform.FITTED)

        fun make(context: Context, overlay: StoryOverlay, media: StoriesMedia?): StoryOverlayView? = when (overlay) {
            is StoryOverlay.Text -> StoryTextOverlayView(context, overlay)
            is StoryOverlay.Link -> StoryLinkOverlayView(context, overlay)
            is StoryOverlay.Emoji -> StoryEmojiOverlayView(context, overlay)
            is StoryOverlay.Reaction -> StoryReactionOverlayView(context, overlay)
            is StoryOverlay.Sticker -> StoryStickerOverlayView(context, overlay, media)
            is StoryOverlay.Unknown -> null
        }

        /** `#RRGGBB`, or null. */
        fun color(hex: String?): Int? {
            if (hex == null || hex.length != 7 || !hex.startsWith("#")) return null
            val value = hex.substring(1).toLongOrNull(16) ?: return null
            return (0xFF000000 or value).toInt()
        }

        /** `#RRGGBB` of [color]. */
        fun hex(color: Int): String = String.format("#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color))

        /** Black text on a light box, white on a dark one. */
        fun contrasting(color: Int): Int {
            val luminance = 0.2126 * Color.red(color) / 255 + 0.7152 * Color.green(color) / 255 + 0.0722 * Color.blue(color) / 255
            return if (luminance > 0.6) Color.BLACK else Color.WHITE
        }

        /** One emoji centred in [box] (px), in the system's emoji font at 0.82 of its height. */
        fun drawEmoji(canvas: Canvas, emoji: String, box: RectF, paint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)) {
            paint.textSize = box.height() * 0.82f
            paint.textAlign = Paint.Align.CENTER
            val fm = paint.fontMetrics
            canvas.drawText(emoji, box.centerX(), box.centerY() - (fm.ascent + fm.descent) / 2, paint)
        }

        /** [emoji] drawn in a [size] dp square, for buttons and the burst. */
        fun emojiBitmap(emoji: String, size: Double): Bitmap {
            val side = max(1, (size * density).roundToInt())
            val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            drawEmoji(Canvas(bitmap), emoji, RectF(0f, 0f, side.toFloat(), side.toFloat()))
            return bitmap
        }

        /** One of happy-app's icons at [size] dp, centred in [box] (px), as an icon image is drawn aspect-fit. */
        fun drawIcon(canvas: Canvas, icon: Icon, size: Double, box: RectF, color: Int, path: Path = Path(), paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)) {
            val glyph = IconCatalog.glyph(icon) ?: return
            val d = density.toDouble()
            val layout = glyph.layout(size, d)
            // The icon's box, fitted into the frame it's given.
            val fit = min(box.width() / (layout.size.width * d), box.height() / (layout.size.height * d)).toFloat()
            glyph.path.toAndroidPath(scale = (layout.glyphScale * d).toFloat(), into = path)
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.save()
            canvas.translate(box.centerX() - (layout.size.width * d * fit / 2).toFloat(), box.centerY() - (layout.size.height * d * fit / 2).toFloat())
            canvas.scale(fit, fit)
            canvas.drawPath(path, paint)
            canvas.restore()
        }
    }
}

// Text

/**
 * Telegram's text entity (happy-ios `StoryTextOverlayView`): the font at
 * 1/16 of the canvas's width, wrapped at its width, and its colour used as
 * the fill says: the text itself, a rounded box per line behind
 * contrasting text (whole or at half), an outline around white text, or
 * white text on a frosted box. Android has no backdrop blur for a view, so
 * the frosted box is the material's dark tint alone.
 */
class StoryTextOverlayView(context: Context, overlay: StoryOverlay) : StoryOverlayView(context, overlay) {
    override val scalesWithTransform: Boolean get() = true

    private class Measured(
        val layout: StaticLayout,
        val stroke: StaticLayout?,
        /** Each line's used box, in this view's px. */
        val lines: List<RectF>,
        val size: Size,
        /** Where the wrapped text is drawn from (px), its first line flush with the box. */
        val originX: Float,
        val originY: Float,
        val paddingX: Float,
        val paddingY: Float,
        val radius: Float,
        val wrap: Float,
    )

    private var measured: Triple<Size, StoryOverlay, Measured>? = null
    private var canvasSize = Size.Zero
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxPath = Path()

    override fun changed() {
        measured = null
        super.changed()
    }

    /** Where the wrapped text is laid out in this view's unscaled dp: the editor types into a field placed there. */
    fun textFrame(canvas: Size): Rect? {
        val m = measure(canvas) ?: return null
        val d = density.toDouble()
        return Rect(m.originX / d, m.originY / d, m.wrap / d, m.size.height - 2 * m.paddingY / d)
    }

    private fun measure(canvas: Size): Measured? {
        val text = overlay as? StoryOverlay.Text ?: return null
        measured?.let { (c, o, value) -> if (c == canvas && sameLook(o, overlay)) return value }
        val d = density
        val size = (StoryCanvasLayout.textFontSize(canvas) * d).toFloat()
        val wrap = (StoryCanvasLayout.textWrap(text.style.width, canvas) * d).toFloat()
        val paint = paint(context, text.style, canvas, inked = true)
        val alignment = alignment(text.style.align)
        val layout = StaticLayout.Builder.obtain(text.text, 0, text.text.length, paint, max(1, ceil(wrap).toInt()))
            .setAlignment(alignment).setIncludePad(false).build()
        val stroke = if (text.style.fill == StoryTextFill.Stroke) {
            val outline = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                // CoreText's -5: a stroke 5% of the size, about the outline.
                strokeWidth = size * 0.05f
                strokeJoin = Paint.Join.ROUND
                color = color(text.style.color) ?: Color.WHITE
            }
            StaticLayout.Builder.obtain(text.text, 0, text.text.length, outline, max(1, ceil(wrap).toInt()))
                .setAlignment(alignment).setIncludePad(false).build()
        } else null
        val paddingX = size * 0.35f
        val paddingY = size * 0.12f
        val lines = mutableListOf<RectF>()
        if (text.text.isNotEmpty()) {
            for (i in 0 until layout.lineCount) {
                lines += RectF(layout.getLineLeft(i), layout.getLineTop(i).toFloat(), layout.getLineRight(i), layout.getLineBottom(i).toFloat())
            }
        }
        // No text yet is a box around where the caret stands.
        val caret = when (text.style.align) {
            StoryTextAlign.Left -> 0f
            StoryTextAlign.Center -> wrap / 2
            StoryTextAlign.Right -> wrap
        }
        val usedMax = lines.maxOfOrNull { it.right } ?: caret
        val usedMin = lines.minOfOrNull { it.left } ?: caret
        val height = if (text.text.isEmpty()) ceil(paint.fontSpacing) else layout.height.toFloat()
        // The box is the lines' extent, so alignment holds within it.
        val width = ceil(usedMax - usedMin) + 2 * paddingX
        val placed = lines.map { RectF(it.left + paddingX - usedMin, it.top + paddingY, it.right + paddingX - usedMin, it.bottom + paddingY) }
        val value = Measured(
            layout, stroke, placed, Size(width / d.toDouble(), (height + 2 * paddingY) / d.toDouble()),
            paddingX - usedMin, paddingY, paddingX, paddingY, size * 0.3f, wrap,
        )
        measured = Triple(canvas, overlay, value)
        return value
    }

    override fun baseSize(canvas: Size): Size {
        canvasSize = canvas
        return measure(canvas)?.size ?: Size.Zero
    }

    override fun onDraw(canvas: Canvas) {
        val text = overlay as? StoryOverlay.Text ?: return
        val m = measure(canvasSize) ?: return
        val color = color(text.style.color) ?: Color.WHITE
        when (text.style.fill) {
            StoryTextFill.Filled, StoryTextFill.Semi -> {
                boxPaint.color = if (text.style.fill == StoryTextFill.Semi) (color and 0x00FFFFFF) or 0x80000000.toInt() else color
                // One rounded box per line, overlapping into one shape.
                boxPath.reset()
                for (line in m.lines) {
                    if (line.width() <= 0) continue
                    boxPath.addRoundRect(RectF(line.left - m.paddingX, line.top - m.paddingY, line.right + m.paddingX, line.bottom + m.paddingY), m.radius, m.radius, Path.Direction.CW)
                }
                canvas.drawPath(boxPath, boxPaint)
            }
            StoryTextFill.Blur -> {
                boxPaint.color = BLUR_TINT
                canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), m.radius, m.radius, boxPaint)
            }
            else -> {}
        }
        canvas.save()
        canvas.translate(m.originX, m.originY)
        m.layout.draw(canvas)
        m.stroke?.draw(canvas)
        canvas.restore()
    }

    companion object {
        /** UIBlurEffect `systemUltraThinMaterialDark`'s tint, without the blur under it. */
        const val BLUR_TINT = 0x66000000

        /** The style's typeface: the platform's nearest to each of iOS's eight fonts. */
        fun typeface(font: StoryTextFont): Typeface = when (font) {
            StoryTextFont.SanFrancisco -> weighted(Typeface.SANS_SERIF, 600, false)
            StoryTextFont.Typewriter -> Typeface.create("serif-monospace", Typeface.NORMAL)
            StoryTextFont.AvenirNext -> weighted(Typeface.SANS_SERIF, 600, true)
            StoryTextFont.CourierNew -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            StoryTextFont.Noteworthy -> Typeface.create("casual", Typeface.BOLD)
            StoryTextFont.Georgia -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
            StoryTextFont.Papyrus -> Typeface.create("sans-serif-smallcaps", Typeface.NORMAL)
            StoryTextFont.SnellRoundhand -> Typeface.create("cursive", Typeface.BOLD)
        }

        private fun weighted(family: Typeface, weight: Int, italic: Boolean): Typeface =
            if (Build.VERSION.SDK_INT >= 28) Typeface.create(family, weight, italic)
            else Typeface.create(family, if (italic) Typeface.BOLD_ITALIC else Typeface.BOLD)

        fun alignment(align: StoryTextAlign): Layout.Alignment = when (align) {
            StoryTextAlign.Left -> Layout.Alignment.ALIGN_NORMAL
            StoryTextAlign.Center -> Layout.Alignment.ALIGN_CENTER
            StoryTextAlign.Right -> Layout.Alignment.ALIGN_OPPOSITE
        }

        /** The text's paint on a canvas of [canvas] (dp); with [inked] its colour as the fill uses it too. */
        fun paint(context: Context, style: StoryTextStyle, canvas: Size, inked: Boolean = true): TextPaint {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
            paint.typeface = typeface(style.font)
            paint.textSize = (StoryCanvasLayout.textFontSize(canvas) * density).toFloat()
            if (!inked) return paint
            val color = color(style.color) ?: Color.WHITE
            paint.color = when (style.fill) {
                StoryTextFill.Regular -> color
                StoryTextFill.Filled, StoryTextFill.Semi -> contrasting(color)
                StoryTextFill.Stroke, StoryTextFill.Blur -> Color.WHITE
            }
            return paint
        }
    }
}

// Link

/**
 * A link pill (happy-ios `StoryLinkOverlayView`): a link glyph and the
 * label (or the host), 1/12 of the canvas's width tall at scale 1, in one
 * of five styles.
 */
class StoryLinkOverlayView(context: Context, overlay: StoryOverlay) : StoryOverlayView(context, overlay) {
    override val scalesWithTransform: Boolean get() = true

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD).let { if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, 600, false) else it } }
    private val iconPath = Path()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var textWidth = 0f

    private data class Colors(val fill: Int, val ink: Int, val blurred: Boolean)

    private val colors: Colors
        get() {
            val link = overlay as? StoryOverlay.Link ?: return Colors(Color.WHITE, Color.BLACK, false)
            return when (link.style) {
                StoryLinkStyle.White -> Colors(Color.WHITE, Color.BLACK, false)
                StoryLinkStyle.Black -> Colors(Color.BLACK, Color.WHITE, false)
                StoryLinkStyle.Transparent -> Colors(0x40FFFFFF, Color.WHITE, false)
                StoryLinkStyle.Blur -> Colors(StoryTextOverlayView.BLUR_TINT, Color.WHITE, true)
                StoryLinkStyle.Custom -> {
                    val fill = color(link.color) ?: Color.WHITE
                    Colors(fill, contrasting(fill), false)
                }
            }
        }

    override fun baseSize(canvas: Size): Size {
        val h = StoryCanvasLayout.linkHeight(canvas)
        label.textSize = (h * 0.42 * density).toFloat()
        val text = label.measureText(title(overlay)) / density
        val limit = canvas.width * 0.8
        textWidth = (min(text.toDouble(), limit) * density).toFloat()
        return Size(ceil(h * 0.3 + h * 0.5 + h * 0.18 + min(text.toDouble(), limit) + h * 0.38), h)
    }

    override fun onDraw(canvas: Canvas) {
        val colors = colors
        // Drawn at its base size; the transform scales it.
        val hp = height.toFloat()
        val h = hp / density
        fill.color = colors.fill
        canvas.drawRoundRect(0f, 0f, width.toFloat(), hp, hp / 2f, hp / 2f, fill)
        val icon = RectF(hp * 0.3f, hp * 0.25f, hp * 0.8f, hp * 0.75f)
        drawIcon(canvas, Icon.ionicons("link-outline"), h * 0.5, icon, colors.ink, iconPath, iconPaint)
        label.color = colors.ink
        val x = icon.right + hp * 0.18f
        val room = max(0f, width - x - hp * 0.38f)
        val shown = android.text.TextUtils.ellipsize(title(overlay), label, room, android.text.TextUtils.TruncateAt.END)
        val fm = label.fontMetrics
        canvas.drawText(shown, 0, shown.length, x, height / 2f - (fm.ascent + fm.descent) / 2, label)
    }

    companion object {
        fun title(overlay: StoryOverlay): String {
            val link = overlay as? StoryOverlay.Link ?: return ""
            if (!link.label.isNullOrEmpty()) return link.label
            val host = runCatching { URI(link.url).host }.getOrNull() ?: return link.url
            return host.removePrefix("www.")
        }
    }
}

// Emoji

/** One emoji in the system's emoji font, its box `scale` of the canvas's width. */
class StoryEmojiOverlayView(context: Context, overlay: StoryOverlay) : StoryOverlayView(context, overlay) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    override fun baseSize(canvas: Size): Size {
        val t = overlay.transform ?: return Size.Zero
        return StoryCanvasLayout.sticker(Size(1, 1), t.scale, canvas)
    }

    override fun onDraw(canvas: Canvas) {
        val emoji = overlay as? StoryOverlay.Emoji ?: return
        drawEmoji(canvas, emoji.emoji, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
    }
}

// Reaction

/**
 * A round speech bubble holding one emoji (happy-ios
 * `StoryReactionOverlayView`, Telegram's reaction area): white (or dark)
 * with its tail at the bottom left (or right), and once posted the count of
 * people who reacted with it under the emoji.
 */
class StoryReactionOverlayView(context: Context, overlay: StoryOverlay) : StoryOverlayView(context, overlay) {
    /** How many reacted with its emoji; null hides the count. */
    var count: Int? = null
        set(value) { if (field != value) { field = value; invalidate() } }

    /** The person's own reaction is this emoji. */
    var chosen = false
        set(value) { if (field != value) { field = value; invalidate() } }

    val emoji: String get() = (overlay as? StoryOverlay.Reaction)?.emoji ?: ""

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emojiPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, 600, false) else Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun baseSize(canvas: Size): Size {
        val t = overlay.transform ?: return Size.Zero
        return StoryCanvasLayout.reaction(t.scale, canvas)
    }

    override fun onDraw(canvas: Canvas) {
        val reaction = overlay as? StoryOverlay.Reaction ?: return
        val side = width.toFloat()
        val bubble = RectF(side * 0.06f, side * 0.06f, side - side * 0.06f, height - side * 0.06f)
        val dark = reaction.dark == true
        fill.color = if (dark) 0x80000000.toInt() else Color.WHITE
        fill.setShadowLayer(side * 0.06f, 0f, side * 0.02f, 0x2E000000)
        canvas.drawOval(bubble, fill)
        // The tail: a small circle and a smaller one beyond it.
        val right = reaction.flipped == true
        val tailX = if (right) bubble.right - side * 0.2f else bubble.left + side * 0.06f
        canvas.drawOval(RectF(tailX, bubble.bottom - side * 0.2f, tailX + side * 0.14f, bubble.bottom - side * 0.06f), fill)
        val tipX = if (right) bubble.right - side * 0.04f else bubble.left - side * 0.04f
        canvas.drawOval(RectF(tipX, bubble.bottom - side * 0.04f, tipX + side * 0.07f, bubble.bottom + side * 0.03f), fill)
        fill.clearShadowLayer()
        val count = count ?: 0
        val hasCount = count > 0
        val top = bubble.centerY() - side * (if (hasCount) 0.32f else 0.25f)
        val emojiBox = RectF(bubble.centerX() - side * 0.25f, top, bubble.centerX() + side * 0.25f, top + side * 0.5f)
        drawEmoji(canvas, reaction.emoji, emojiBox, emojiPaint)
        if (hasCount) {
            countPaint.color = if (dark) Color.WHITE else Color.BLACK
            countPaint.textSize = side * 0.15f
            val fm = countPaint.fontMetrics
            canvas.drawText(short(count), bubble.centerX(), emojiBox.bottom + side * 0.01f - fm.ascent, countPaint)
        }
    }

    companion object {
        /** 1234 as "1.2K", as Telegram counts reactions. */
        fun short(count: Int): String {
            if (count < 1000) return "$count"
            val thousands = count / 1000.0
            return if (thousands < 10) String.format(java.util.Locale.US, "%.1fK", thousands).replace(".0K", "K") else "${thousands.toInt()}K"
        }
    }
}

// Sticker

/**
 * A sticker from its pack (happy-ios `StoryStickerOverlayView`), its longer
 * side `scale` of the canvas's width. A still one is its picture; an
 * animated one plays in what the sticker players give it. One the computer
 * no longer serves draws nothing.
 */
class StoryStickerOverlayView(context: Context, overlay: StoryOverlay, private val media: StoriesMedia?) : StoryOverlayView(context, overlay) {
    private var sticker: Sticker? = null
    private var loading: Job? = null
    private val scope: CoroutineScope = MainScope()
    private val content = StoryStickerContent(this)

    init {
        load()
    }

    override fun update(overlay: StoryOverlay): Boolean {
        val next = overlay as? StoryOverlay.Sticker ?: return false
        val was = this.overlay as? StoryOverlay.Sticker ?: return false
        super.update(overlay)
        if (next.packId != was.packId || next.stickerId != was.stickerId) load()
        return true
    }

    private fun load() {
        val sticker = overlay as? StoryOverlay.Sticker ?: return
        val media = media ?: return
        loading?.cancel()
        loading = scope.launch {
            val found = media.sticker(StickerRef(sticker.packId, sticker.stickerId)) ?: return@launch
            this@StoryStickerOverlayView.sticker = found
            (parent as? View)?.requestLayout()
            content.show(found, media)
        }
    }

    override fun baseSize(canvas: Size): Size {
        val t = overlay.transform ?: return Size.Zero
        val pixels = sticker?.let { Size(it.width.toDouble(), it.height.toDouble()) } ?: Size(512, 512)
        return StoryCanvasLayout.sticker(pixels, t.scale, canvas)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        content.read()
    }

    override fun onDraw(canvas: Canvas) = content.draw(canvas)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        content.visible = true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        content.visible = false
    }
}

/**
 * A sticker's picture or animation, fitted in its host's bounds
 * (happy-ios `StoryStickerContentView`). Still `webp` stickers draw here;
 * animated ones are given to [StoryStickerPlayers].
 */
class StoryStickerContent(private val host: View) {
    var sticker: Sticker? = null
        private set
    private var picture: Bitmap? = null
    private var loading: Job? = null
    private var media: StoriesMedia? = null
    private var animation: StoryStickerAnimating? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    /** The pixels the picture was read at; read again only to grow. */
    private var readPixels = 0

    /** Animated stickers play only while visible. */
    var visible = true
        set(value) { if (field != value) { field = value; animation?.setPlaying(value) } }

    fun show(sticker: Sticker, media: StoriesMedia) {
        if (sticker == this.sticker) return
        this.sticker = sticker
        this.media = media
        loading?.cancel()
        picture = null
        readPixels = 0
        animation?.stop()
        animation = null
        when (sticker.format) {
            StickerFormat.Webp -> read()
            StickerFormat.Tgs, StickerFormat.Webm -> {
                val player = StoryStickerPlayers.make?.invoke(sticker, media, host) ?: return
                animation = player
                player.setPlaying(visible)
            }
        }
        host.invalidate()
    }

    /** A still sticker at the size it is drawn, once it has one. */
    fun read() {
        val sticker = sticker ?: return
        val media = media ?: return
        if (sticker.format != StickerFormat.Webp || host.width <= 0) return
        val pixels = min(512, max(host.width, host.height))
        if (pixels <= readPixels) return
        readPixels = pixels
        loading?.cancel()
        loading = scope.launch {
            val image = media.stickerImage(sticker, pixels) ?: return@launch
            if (this@StoryStickerContent.sticker != sticker) return@launch
            picture = image
            host.invalidate()
        }
    }

    fun draw(canvas: Canvas) {
        animation?.let { it.draw(canvas, host.width, host.height); return }
        val image = picture ?: return
        val fit = min(host.width / image.width.toFloat(), host.height / image.height.toFloat())
        val w = image.width * fit
        val h = image.height * fit
        canvas.drawBitmap(image, null, RectF((host.width - w) / 2, (host.height - h) / 2, (host.width + w) / 2, (host.height + h) / 2), paint)
    }
}

/** An animated sticker's player: draws its current frame into the host, looping while playing. */
interface StoryStickerAnimating {
    fun setPlaying(playing: Boolean)
    fun draw(canvas: Canvas, width: Int, height: Int)
    fun stop()
}

/**
 * Where animated sticker players come from (happy-ios
 * `StoryStickerPlayers`): Lottie for `tgs`; nil draws nothing for any.
 * Replaceable for tests.
 */
object StoryStickerPlayers {
    var make: ((Sticker, StoriesMedia, View) -> StoryStickerAnimating?)? = { sticker, media, host ->
        if (sticker.format == StickerFormat.Tgs) StoryStickerLottiePlayer(sticker, media, host) else null
    }
}
