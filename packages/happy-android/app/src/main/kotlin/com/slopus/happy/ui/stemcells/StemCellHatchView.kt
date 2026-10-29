package com.slopus.happy.ui.stemcells

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.stemcells.StemCellHatch
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** happy-ios StemCellHatchView: the same 60 Hz keyframes, drawn by a native View. */
class StemCellHatchView(context: Context) : View(context) {
    private data class Drawing(val frame: StemCellHatch.Frame, val membrane: Path, val core: Path)
    private var drawings = emptyList<Drawing>()
    private var index = 0
    private var image: Bitmap? = null
    private var tint = Color.TRANSPARENT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var animator: ValueAnimator? = null
    private var done: (() -> Unit)? = null
    private var finished = false

    init {
        tag = "stem-hatch"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
    }

    fun play(seed: String, picture: Bitmap, complete: () -> Unit) {
        if (finished) { complete(); return }
        done = complete
        val size = px(minOf(width, height))
        if (size <= 0 || !canPlay(this)) { finish(); return }
        image = picture
        val sample = Bitmap.createScaledBitmap(picture, 1, 1, true)
        tint = sample.getPixel(0, 0).let { if (Color.alpha(it) == 0) Theme.color(StemCellsPalette.stemCellMembrane) else ColorUtils.setAlphaComponent(it, 255) }
        if (sample !== picture) sample.recycle()
        val count = (StemCellHatch.TOTAL * 60).toInt() + 1
        val frames = StemCellHatch.frames(List(count) { it.toDouble() / (count - 1) * StemCellHatch.TOTAL }, size, seed)
        fun path(frame: StemCellHatch.Frame, scale: Double) = Path().apply {
            for (cell in frame.cells) {
                val radius = cell.radius * scale
                addOval(dp(cell.center.x - radius), dp(cell.center.y - radius), dp(cell.center.x + radius), dp(cell.center.y + radius), Path.Direction.CW)
            }
        }
        drawings = frames.map { Drawing(it, path(it, 1.0), path(it, 0.42)) }
        StemCellHatchHaptics.play(this, size)
        animator = ValueAnimator.ofFloat(0f, (count - 1).toFloat()).apply {
            duration = (StemCellHatch.TOTAL * 1000).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (finished) return@addUpdateListener
                if (!canPlay(this@StemCellHatchView)) { finish(); return@addUpdateListener }
                index = (it.animatedValue as Float).roundToInt().coerceIn(drawings.indices)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) = finish() })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val drawing = drawings.getOrNull(index) ?: return
        val frame = drawing.frame
        paint.color = ColorUtils.blendARGB(Theme.color(StemCellsPalette.stemCellMembrane), tint, frame.tint.toFloat())
        paint.alpha = (frame.cellAlpha * 255).roundToInt()
        canvas.drawPath(drawing.membrane, paint)
        paint.color = Theme.color(StemCellsPalette.stemCellCore)
        paint.alpha = (frame.coreAlpha * frame.cellAlpha * 255).roundToInt()
        canvas.drawPath(drawing.core, paint)
        val picture = image ?: return
        paint.alpha = (frame.pictureAlpha * 255).roundToInt()
        val fit = minOf(width.toDouble() / picture.width, height.toDouble() / picture.height) * frame.pictureScale
        val w = (picture.width * fit).toFloat()
        val h = (picture.height * fit).toFloat()
        destination.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(picture, null, destination, paint)
    }

    /** Replacement, backgrounding and recycling always restore the destination mark once. */
    fun finish() = finish(inLifecycle = false)

    private fun finish(inLifecycle: Boolean) {
        if (finished) return
        finished = true
        animator?.removeAllListeners()
        animator?.removeAllUpdateListeners()
        animator?.cancel()
        animator = null
        StemCellHatchHaptics.stop(this)
        drawings = emptyList()
        image = null // shared clay bitmap belongs to the image cache
        (parent as? ViewGroup)?.let { container ->
            // Window/detach callbacks may run inside ViewGroup.removeView's child iteration.
            // Release resources now, but never remove the same child re-entrantly.
            if (inLifecycle) container.post { if (parent === container) container.removeView(this) }
            else container.removeView(this)
        }
        val callback = done
        done = null
        callback?.invoke()
    }

    override fun onDetachedFromWindow() { finish(inLifecycle = true); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); if (drawings.isNotEmpty() && (w != oldw || h != oldh)) finish() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility != VISIBLE) finish(inLifecycle = true) }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (!hasWindowFocus) finish(inLifecycle = true) }

    companion object {
        fun canPlay(view: View): Boolean {
            if (!ValueAnimator.areAnimatorsEnabled() || !view.isAttachedToWindow || !view.hasWindowFocus() || !view.isShown) return false
            var current: View? = view
            while (current != null) {
                if (current.alpha < 0.01f) return false
                current = current.parent as? View
            }
            return view.getGlobalVisibleRect(Rect())
        }
    }
}

/** Native Android taps at the same cleavage/formation times; no extra vibration permission. */
internal object StemCellHatchHaptics {
    private var owner: WeakReference<View>? = null
    private val callbacks = ArrayList<Runnable>()
    private var playingUntil = 0L

    fun play(view: View, size: Double) {
        if (SystemClock.uptimeMillis() < playingUntil || !view.isHapticFeedbackEnabled) return
        owner?.get()?.let(::stop)
        owner = WeakReference(view)
        playingUntil = SystemClock.uptimeMillis() + (StemCellHatch.TOTAL * 1000).toLong()
        for (tap in StemCellHatch.haptics(size).taps) {
            val callback = Runnable {
                if (owner?.get() === view && StemCellHatchView.canPlay(view)) {
                    view.performHapticFeedback(if (tap.sharpness > 0.7) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
            callbacks += callback
            view.postDelayed(callback, (tap.time * 1000).toLong())
        }
    }

    fun stop(view: View) {
        if (owner?.get() !== view) return
        callbacks.forEach(view::removeCallbacks)
        callbacks.clear()
        owner = null
        playingUntil = 0
    }
}