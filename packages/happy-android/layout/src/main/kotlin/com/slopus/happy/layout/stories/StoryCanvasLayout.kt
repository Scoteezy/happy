package com.slopus.happy.layout.stories

import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Where a story's frame is drawn (happy-ios `StoryCanvasLayout`): the
 * 1080 × 1920 canvas fitted to the space, and everything on it placed by
 * fractions of the canvas (happy-agent `API.md` "Frames" and "Overlays").
 * The same numbers draw the viewer, the editor and the inline story, at any
 * size.
 */
object StoryCanvasLayout {
    const val aspect = 1080.0 / 1920.0

    /**
     * The largest 9:16 rectangle in `bounds`, centred across, at its top
     * when it is held by the width (a phone: Telegram's story under the
     * status bar, its bar below), centred down when held by the height.
     */
    fun fit(bounds: Rect): Rect {
        if (bounds.width <= 0 || bounds.height <= 0) return Rect.Zero
        val byWidthHeight = bounds.width / aspect
        if (byWidthHeight <= bounds.height) return integralSize(Rect(bounds.minX, bounds.minY, bounds.width, byWidthHeight))
        val width = bounds.height * aspect
        return integralSize(Rect(bounds.minX + (bounds.width - width) / 2, bounds.minY, width, bounds.height))
    }

    /** Whole points for its size, keeping its origin. */
    private fun integralSize(rect: Rect) = Rect(rect.minX, rect.minY, floor(rect.width), floor(rect.height))

    /** A transform's centre on a canvas of `size`. */
    fun center(x: Double, y: Double, canvas: Size) = Point(x * canvas.width, y * canvas.height)

    /**
     * The media's unrotated box on the canvas: fitted whole at scale 1 and
     * centred at `x`, `y`; `scale` grows it (and the canvas crops it).
     * `pixels` null (a video not measured yet) fills the canvas's width as a
     * 16:9 picture would.
     */
    fun media(pixels: Size?, x: Double, y: Double, scale: Double, canvas: Size): Rect {
        val source = pixels?.takeIf { it.width > 0 && it.height > 0 } ?: Size(16, 9)
        val fit = min(canvas.width / source.width, canvas.height / source.height)
        val width = source.width * fit * scale
        val height = source.height * fit * scale
        val center = center(x, y, canvas)
        return Rect(center.x - width / 2, center.y - height / 2, width, height)
    }

    /** Text's base font size at scale 1: 1/16 of the canvas's width. */
    fun textFontSize(canvas: Size): Double = canvas.width / 16

    /** The width text wraps at before scaling. */
    fun textWrap(fraction: Double, canvas: Size): Double = min(1.0, max(0.1, fraction)) * canvas.width

    /** A link pill's height at scale 1: 1/12 of the canvas's width. */
    fun linkHeight(canvas: Size): Double = canvas.width / 12

    /**
     * A sticker's or emoji's box: its longer side `scale` of the canvas's
     * width, its shape the sticker's.
     */
    fun sticker(pixels: Size, scale: Double, canvas: Size): Size {
        val longer = scale * canvas.width
        if (pixels.width <= 0 || pixels.height <= 0) return Size(longer, longer)
        return if (pixels.width >= pixels.height) Size(longer, longer * pixels.height / pixels.width) else Size(longer * pixels.width / pixels.height, longer)
    }

    /** A reaction bubble: `scale` of the canvas's width across, round. */
    fun reaction(scale: Double, canvas: Size): Size {
        val side = scale * canvas.width
        return Size(side, side)
    }

    /**
     * The caption at the bottom of the canvas, folded to a few lines under
     * its fade, and opened in full over the darkened frame (Telegram's
     * story caption): the open text keeps its last line where the folded
     * one ended and grows up to `topInset`; longer, it starts there and
     * scrolls.
     */
    data class Caption(
        val fade: Rect,
        /** The folded text. */
        val text: Rect,
        /** The box the caption scrolls in, its bottom just under the text. */
        val scroll: Rect,
        /** The open text, in the scroll box's content. */
        val openText: Rect,
        /**
         * The shade an open caption rises with: from 64 above its text (or
         * the top) to the foot, as the fold's fade.
         */
        val shade: Rect,
        val content: Size,
        /** The open text is taller than the room it has. */
        val scrolls: Boolean,
    )

    /**
     * 16 in from the sides and from `bottomInset`, under a fade that
     * begins 64 above it; the scroll box reaches 8 below the text.
     */
    fun caption(textHeight: Double, fullHeight: Double? = null, canvas: Size, bottomInset: Double = 16.0, topInset: Double = 72.0): Caption {
        val inset = 16.0
        val pad = 8.0
        val width = max(0.0, canvas.width - 2 * inset)
        val foot = canvas.height - bottomInset
        val text = Rect(inset, foot - ceil(textHeight), width, ceil(textHeight))
        val fadeTop = max(0.0, text.minY - 64)
        val scroll = Rect(0.0, 0.0, canvas.width, min(canvas.height, foot + pad))
        val full = ceil(fullHeight ?: textHeight)
        val room = max(0.0, foot - topInset)
        val scrolls = full > room
        val openText = Rect(inset, if (scrolls) topInset else foot - full, width, full)
        val content = Size(canvas.width, if (scrolls) openText.maxY + pad else scroll.height)
        val shadeTop = max(0.0, openText.minY - 64)
        return Caption(
            fade = Rect(0.0, fadeTop, canvas.width, canvas.height - fadeTop), text = text,
            scroll = scroll, openText = openText,
            shade = Rect(0.0, shadeTop, canvas.width, canvas.height - shadeTop),
            content = content, scrolls = scrolls,
        )
    }
}
