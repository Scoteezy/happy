package com.slopus.happy.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.util.Base64
import android.util.LruCache
import android.view.View
import androidx.core.graphics.withClip
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.images.HappyImageLoader
import com.slopus.happy.layout.GeneratedAvatar
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.model.AvatarHarness
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.viewScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** utils/avatarStyle.ts: the synced setting is a free string, so every read normalizes it. */
enum class AvatarStyle(val key: String) {
    Brutalist("brutalist"),
    Pixelated("pixelated"),
    Gradient("gradient");

    companion object {
        fun normalize(value: String?): AvatarStyle = entries.find { it.key == value } ?: Brutalist
    }
}

/**
 * components/Avatar.tsx for generated session avatars (happy-ios
 * `AvatarView`): brutalist (a tinted glyph on a coloured disc), gradient, or
 * an 8×8 pixel grid, a chip for a bot without a picture, a picture when the
 * session or project has one, and the optional harness badge. Every pick
 * comes from [GeneratedAvatar], so a session has the same face in every app.
 */
class AvatarView(context: Context) : View(context), ThemeAware {
    data class Content(
        val seed: String,
        val style: AvatarStyle,
        val monochrome: Boolean,
        val isBot: Boolean = false,
        /** A `data:` URI or a URL; wins over the generated face once loaded. */
        val image: String? = null,
        val harness: AvatarHarness? = null,
        val square: Boolean = false,
    )

    var content: Content? = null
        set(value) {
            if (field == value) return
            val imageChanged = field?.image != value?.image
            field = value
            if (imageChanged) loadPicture()
            loadArt()
            invalidate()
        }

    private var art: Bitmap? = null
    private var picture: Bitmap? = null
    private var artJob: Job? = null
    private var pictureJob: Job? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val chip = IconView(context, Icon.ionicons("hardware-chip-outline"), 0.0)
    private val bounds = RectF()

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun applyTheme() = invalidate()

    private fun loadArt() {
        artJob?.cancel()
        val content = content ?: return run { art = null }
        val key = when {
            content.isBot -> null
            content.style == AvatarStyle.Brutalist -> "avatars/brutalist/${GeneratedAvatar.brutalist(content.seed, content.monochrome).imageName}.png"
            content.style == AvatarStyle.Gradient -> "avatars/gradients/${GeneratedAvatar.gradientImageName(content.seed).removePrefix("gradient-")}.png"
            else -> null
        }
        if (key == null) {
            art = null
            return
        }
        val cached = AvatarImages.cached(key)
        if (cached != null) {
            art = cached
            return
        }
        art = null
        artJob = viewScope.launch {
            val bitmap = withContext(Dispatchers.IO) { AvatarImages.asset(context, key) }
            if (this@AvatarView.content === content) {
                art = bitmap
                invalidate()
            }
        }
    }

    private fun loadPicture() {
        pictureJob?.cancel()
        picture = null
        val source = content?.image ?: return
        AvatarImages.cached(source)?.let { picture = it; return }
        pictureJob = viewScope.launch {
            val bitmap = if (source.startsWith("data:")) {
                withContext(Dispatchers.IO) { AvatarImages.dataUri(source) }
            } else {
                val request = ImageRequest.Builder(context).data(source).allowHardware(false).build()
                HappyImageLoader.get(context).execute(request).image?.toBitmap()?.also { AvatarImages.put(source, it) }
            }
            if (content?.image == source) {
                picture = bitmap
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val content = content ?: return
        val size = width.toFloat()
        val sizeDp = px(width)
        bounds.set(0f, 0f, size, size)
        clip.reset()
        if (content.square) clip.addRect(bounds, Path.Direction.CW) else clip.addOval(bounds, Path.Direction.CW)
        canvas.withClip(clip) {
            val picture = picture
            when {
                picture != null -> drawCover(canvas, picture)
                content.isBot -> {
                    paint.colorFilter = null
                    paint.color = Theme.color(Palette.surface)
                    canvas.drawRect(bounds, paint)
                    val frame = GeneratedAvatar.botGlyphFrame(sizeDp)
                    chip.size = frame.width
                    chip.tint = if (content.monochrome) Palette.textSecondary else Palette.text
                    drawChild(canvas, chip, frame)
                }
                content.style == AvatarStyle.Pixelated -> {
                    paint.colorFilter = null
                    val frames = GeneratedAvatar.pixelFrames(sizeDp)
                    GeneratedAvatar.pixels(content.seed, content.monochrome).forEachIndexed { index, color ->
                        paint.color = color.argb
                        val f = frames[index]
                        val d = Theme.density
                        // A hair of overlap so neighbouring cells never show a seam.
                        canvas.drawRect((f.minX * d).toFloat() - 0.5f, (f.minY * d).toFloat() - 0.5f, (f.maxX * d).toFloat() + 0.5f, (f.maxY * d).toFloat() + 0.5f, paint)
                    }
                }
                content.style == AvatarStyle.Gradient -> {
                    art?.let { bitmap ->
                        paint.colorFilter = if (content.monochrome) GRAYSCALE else null
                        canvas.drawBitmap(bitmap, null, bounds, paint)
                    }
                }
                else -> {
                    val pick = GeneratedAvatar.brutalist(content.seed, content.monochrome)
                    paint.colorFilter = null
                    paint.color = pick.background.argb
                    canvas.drawRect(bounds, paint)
                    art?.let { bitmap ->
                        val f = GeneratedAvatar.brutalistGlyphFrame(sizeDp)
                        val d = Theme.density
                        paint.colorFilter = PorterDuffColorFilter(pick.tint.argb, PorterDuff.Mode.SRC_IN)
                        canvas.drawBitmap(bitmap, null, RectF((f.minX * d).toFloat(), (f.minY * d).toFloat(), (f.maxX * d).toFloat(), (f.maxY * d).toFloat()), paint)
                    }
                }
            }
        }
        paint.colorFilter = null
        drawBadge(canvas, content, sizeDp)
    }

    private fun drawCover(canvas: Canvas, bitmap: Bitmap) {
        paint.colorFilter = if (content?.monochrome == true) GRAYSCALE else null
        val scale = maxOf(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        canvas.drawBitmap(bitmap, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), paint)
    }

    /** Avatar.tsx `harnessBadgeSizes`: a surface circle at the bottom-right holding the harness mark. */
    private fun drawBadge(canvas: Canvas, content: Content, sizeDp: Double) {
        val harness = content.harness ?: return
        if (content.isBot && picture == null) return
        val (circle, glyph) = GeneratedAvatar.badge(sizeDp, GeneratedAvatar.badgeGlyphRatio(harness.name.lowercase()))
        val d = Theme.density
        paint.color = Theme.color(Palette.surface)
        paint.setShadowLayer(2 * d, 0f, 1 * d, 0x33000000)
        canvas.drawOval((circle.minX * d).toFloat(), (circle.minY * d).toFloat(), (circle.maxX * d).toFloat(), (circle.maxY * d).toFloat(), paint)
        paint.clearShadowLayer()
        val mark = AvatarImages.harness(context, harness) ?: return
        val tinted = harness == AvatarHarness.Codex || harness == AvatarHarness.Rig
        paint.colorFilter = if (tinted) PorterDuffColorFilter(Theme.color(Palette.text), PorterDuff.Mode.SRC_IN) else null
        canvas.drawBitmap(mark, null, RectF((glyph.minX * d).toFloat(), (glyph.minY * d).toFloat(), (glyph.maxX * d).toFloat(), (glyph.maxY * d).toFloat()), paint)
        paint.colorFilter = null
    }

    private fun drawChild(canvas: Canvas, view: View, frame: Rect) {
        val d = Theme.density
        val w = (frame.width * d).toInt()
        val h = (frame.height * d).toInt()
        view.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        canvas.save()
        canvas.translate((frame.minX * d).toFloat(), (frame.minY * d).toFloat())
        view.draw(canvas)
        canvas.restore()
    }

    companion object {
        private val GRAYSCALE = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
}

/** The bundled avatar artwork and decoded pictures, shared by every row. */
object AvatarImages {
    private val cache = LruCache<String, Bitmap>(200)

    fun cached(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }

    fun asset(context: Context, path: String): Bitmap? = cache.get(path) ?: try {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }?.also { cache.put(path, it) }
    } catch (_: java.io.IOException) {
        null
    }

    fun dataUri(uri: String): Bitmap? {
        cache.get(uri)?.let { return it }
        val comma = uri.indexOf(',')
        if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) return null
        return try {
            val bytes = Base64.decode(uri.substring(comma + 1), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { cache.put(uri, it) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun harness(context: Context, harness: AvatarHarness): Bitmap? {
        val key = "harness:${harness.name}"
        cache.get(key)?.let { return it }
        val id = when (harness) {
            AvatarHarness.Claude -> R.drawable.icon_claude
            AvatarHarness.Codex -> R.drawable.icon_gpt
            AvatarHarness.Agy -> R.drawable.icon_agy
            AvatarHarness.Rig -> R.drawable.happy_logo
        }
        return BitmapFactory.decodeResource(context.resources, id)?.also { cache.put(key, it) }
    }
}
