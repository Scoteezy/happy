package com.slopus.happy.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import com.slopus.happy.layout.RGBA
import com.slopus.happy.model.BOT_FACE_PAINT_SIZE
import com.slopus.happy.model.BOT_FACE_STYLE_ASSET
import com.slopus.happy.model.BotFaceStyle
import com.slopus.happy.model.FaceFill
import com.slopus.happy.model.FaceShape
import com.slopus.happy.model.resolveBotFace
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native Canvas equivalent of happy-ios BotFacePainter. Parsing, rasterizing and PNG encoding stay off main. */
class BotFacePainter(private val loadStyle: () -> BotFaceStyle) {
    private val prepared by lazy {
        val style = loadStyle()
        require(style.width > 0 && style.height > 0)
        val paths = IdentityHashMap<FaceShape.Path, Path>()
        fun prepare(shapes: List<FaceShape>) {
            for (shape in shapes) when (shape) {
                is FaceShape.Group -> prepare(shape.children)
                is FaceShape.Path -> paths[shape] = requireNotNull(PathParser.createPathFromPathData(shape.d)).apply {
                    fillType = if (shape.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                }
                is FaceShape.Ellipse -> Unit
            }
        }
        style.components.values.forEach { it.variants.values.forEach { prepare(it.shapes) } }
        style to paths
    }

    suspend fun image(seed: String, pixels: Int): Bitmap = withContext(Dispatchers.Default) { render(seed, pixels) }

    suspend fun png(seed: String): ByteArray = withContext(Dispatchers.Default) {
        val bitmap = render(seed, BOT_FACE_PAINT_SIZE)
        try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "The face could not be painted." }
                output.toByteArray().also { check(it.isNotEmpty()) }
            }
        } finally { bitmap.recycle() }
    }

    private fun render(seed: String, pixels: Int): Bitmap {
        require(pixels in 1..2048)
        val (style, paths) = prepared
        val face = resolveBotFace(style, seed)
        val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.scale(pixels / style.width, pixels / style.height)
            face.background?.let { RGBA.hex(it) }?.let {
                paint.color = it.argb
                canvas.drawRect(0f, 0f, style.width, style.height, paint)
            }
            fun draw(shapes: List<FaceShape>, inherited: FaceFill?) {
                for (shape in shapes) {
                    val fill = shape.fill ?: inherited
                    if (shape is FaceShape.Group) {
                        draw(shape.children, fill)
                    } else {
                        val color = face.color(fill)?.let {
                            // The bundled glasses use SVG's named white, as the native generator does.
                            when (it.lowercase()) {
                                "white" -> RGBA.White
                                "black" -> RGBA.Black
                                else -> RGBA.hex(it)
                            }
                        } ?: continue
                        paint.color = color.argb
                        when (shape) {
                            is FaceShape.Path -> canvas.drawPath(paths.getValue(shape), paint)
                            is FaceShape.Ellipse -> canvas.drawOval(shape.cx - shape.rx, shape.cy - shape.ry, shape.cx + shape.rx, shape.cy + shape.ry, paint)
                            is FaceShape.Group -> Unit
                        }
                    }
                }
            }
            for (part in face.parts) {
                canvas.save()
                canvas.translate(part.x, part.y)
                // Adventurer Neutral's root has fill="none", not SVG's default black.
                draw(part.shapes, null)
                canvas.restore()
            }
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    companion object {
        @Volatile private var shared: BotFacePainter? = null
        fun of(context: Context): BotFacePainter = shared ?: synchronized(this) {
            shared ?: context.applicationContext.assets.let { assets ->
                BotFacePainter { assets.open(BOT_FACE_STYLE_ASSET).bufferedReader().use { BotFaceStyle.parse(it.readText()) } }
            }.also { shared = it }
        }
    }
}