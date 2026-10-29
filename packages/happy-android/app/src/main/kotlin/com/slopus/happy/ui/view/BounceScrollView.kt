package com.slopus.happy.ui.view

import android.content.Context
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * A vertical list that scrolls as UIScrollView does, where a screen needs
 * what Android's own scrolling doesn't have: an offset past its top that a
 * finger can pull and that springs back (happy-ios home's stories open on
 * that pull), insets that change without moving what is shown, and a say in
 * where a fling comes to rest. Its [content] is placed by frames from
 * outside; [contentOffset] is UIScrollView's `contentOffset.y` in dp, so the
 * content's top is at `-contentOffset` and at rest at its top the offset is
 * `-insetTop`.
 */
class BounceScrollView(context: Context) : ManualLayout(context) {
    interface Listener {
        fun willBeginDragging() {}
        fun didScroll() {}

        /**
         * The finger lifts with the content moving at [velocity] (dp per ms,
         * positive as the content scrolls on, the finger going up); [target]
         * holds where it would glide to and may be changed.
         */
        fun willEndDragging(velocity: Double, target: DoubleArray) {}
        fun didEndDragging(decelerate: Boolean) {}
        fun didEndDecelerating() {}
    }

    val content = FrameContainer(context)
    var listener: Listener? = null

    var insetTop = 0.0
        private set
    var insetBottom = 0.0
        private set
    var contentHeight = 0.0
        set(value) { field = value; content.contentSize = Size(px(width), value) }

    var contentOffset = 0.0
        private set
    var isDragging = false
        private set
    var isDecelerating = false
        private set

    /** The finger's speed as it last moved, dp per ms, positive downwards (UIPanGestureRecognizer's velocity). */
    var panVelocity = 0.0
        private set

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var tracker: VelocityTracker? = null
    private var downY = 0f
    private var lastY = 0f
    private var tracking = false
    /** The offset the finger has asked for, before any rubber band. */
    private var rawOffset = 0.0
    private var motion: Motion? = null

    init {
        clipChildren = true
        addView(content)
    }

    private val minOffset get() = -insetTop
    private val maxOffset get() = max(minOffset, contentHeight + insetBottom - px(height))

    fun setInsets(top: Double, bottom: Double) {
        insetTop = top
        insetBottom = bottom
    }

    /**
     * As UIScrollView's offset changes in an animation block: the offset is
     * the new one at once (one [Listener.didScroll] with it), and only what
     * is drawn moves there over [duration].
     */
    fun setContentOffset(offset: Double, animated: Boolean = false, duration: Double = 0.3) {
        stopMotion()
        if (!animated) {
            apply(offset)
            return
        }
        val from = content.translationY
        apply(offset)
        val to = content.translationY
        if (from == to) return
        content.translationY = from
        presenting = true
        // UIViewPropertyAnimator's (0.33, 0.52), (0.25, 0.99), as the harness and home animate the room.
        content.animate().translationY(to).setDuration((duration * 1000).toLong())
            .setInterpolator(PathInterpolator(0.33f, 0.52f, 0.25f, 0.99f))
            .withEndAction { presenting = false }
            .start()
    }

    /** What is drawn is still on its way to [contentOffset]. */
    private var presenting = false

    private fun apply(offset: Double) {
        if (presenting) {
            content.animate().cancel()
            presenting = false
        }
        if (offset == contentOffset) return
        contentOffset = offset
        content.translationY = -dp(offset)
        listener?.didScroll()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        content.contentSize = Size(width, contentHeight)
        content.setFrame(Rect(0.0, 0.0, width, contentHeight))
        content.layoutSubviews(width, contentHeight)
        if (!presenting) content.translationY = -dp(contentOffset)
    }

    // Touches

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                begin(ev)
                // Caught while gliding: the finger holds it.
                if (motion != null) {
                    stopMotion()
                    startDrag()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (!isDragging && abs(ev.y - downY) > slop) {
                    lastY = ev.y
                    startDrag()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endTracking()
        }
        return isDragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                begin(event)
                stopMotion()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                if (!isDragging) {
                    if (abs(event.y - downY) <= slop) return true
                    lastY = event.y
                    startDrag()
                }
                val dy = px(event.y - lastY)
                lastY = event.y
                tracker?.let {
                    it.computeCurrentVelocity(1)
                    panVelocity = px(it.yVelocity)
                }
                rawOffset -= dy
                apply(banded(rawOffset))
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1)
                val velocity = -(tracker?.yVelocity?.let { px(it) } ?: 0.0)
                endTracking()
                if (isDragging) release(velocity)
            }
            MotionEvent.ACTION_CANCEL -> {
                endTracking()
                if (isDragging) release(0.0)
            }
        }
        return true
    }

    private fun begin(ev: MotionEvent) {
        tracker?.recycle()
        tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
        downY = ev.y
        lastY = ev.y
        tracking = true
    }

    private fun endTracking() {
        tracking = false
        tracker?.recycle()
        tracker = null
    }

    private fun startDrag() {
        if (isDragging) return
        isDragging = true
        rawOffset = unbanded(contentOffset)
        parent?.requestDisallowInterceptTouchEvent(true)
        listener?.willBeginDragging()
    }

    /** UIScrollView's rubber band past either end: the further, the less it gives. */
    private fun banded(raw: Double): Double {
        val viewport = max(1.0, px(height))
        fun band(over: Double) = (1 - 1 / (over * 0.55 / viewport + 1)) * viewport
        return when {
            raw < minOffset -> minOffset - band(minOffset - raw)
            raw > maxOffset -> maxOffset + band(raw - maxOffset)
            else -> raw
        }
    }

    private fun unbanded(shown: Double): Double {
        val viewport = max(1.0, px(height))
        fun unband(over: Double): Double {
            val f = min(0.999, over / viewport)
            return (1 / (1 - f) - 1) * viewport / 0.55
        }
        return when {
            shown < minOffset -> minOffset - unband(minOffset - shown)
            shown > maxOffset -> maxOffset + unband(shown - maxOffset)
            else -> shown
        }
    }

    private fun release(velocity: Double) {
        isDragging = false
        panVelocity = 0.0
        val outside = contentOffset < minOffset - 0.5 || contentOffset > maxOffset + 0.5
        // Where it would glide to, decelerating as a scroll view does (0.998 a ms).
        var natural = contentOffset + (if (outside) 0.0 else velocity * DECELERATION / (1 - DECELERATION))
        if (outside) natural = contentOffset.coerceIn(minOffset, maxOffset)
        val target = doubleArrayOf(natural)
        listener?.willEndDragging(velocity, target)
        val goal = target[0]
        val moves = abs(goal - contentOffset) > 0.5 || contentOffset < minOffset - 0.5 || contentOffset > maxOffset + 0.5
        listener?.didEndDragging(moves)
        if (!moves) return
        glide(goal, velocity)
    }

    /** Glides to [goal], or springs back inside the ends from past them. */
    private fun glide(goal: Double, velocity: Double) {
        isDecelerating = true
        val start = contentOffset
        val begin = System.nanoTime()
        val k = ln(DECELERATION)
        val inside = start in minOffset..maxOffset
        val clamped = goal.coerceIn(minOffset, maxOffset)
        motion = Motion { now ->
            val ms = (now - begin) / 1e6
            if (!inside || goal != clamped) {
                // From past an end, or to past one: springs to the end, critically damped.
                val end = clamped
                val omega = 0.022
                val x0 = start - end
                val v0 = if (inside) velocity else 0.0
                val x = (x0 + (v0 + omega * x0) * ms) * exp(-omega * ms)
                apply(end + x)
                abs(x) > 0.2 || ms < 16
            } else {
                val f = 1 - exp(k * ms)
                apply(start + (goal - start) * f)
                abs((goal - start) * (1 - f)) > 0.2
            }
        }
        frame()
    }

    private fun interface Motion {
        /** Moves to where it is at [now] (ns); false once done. */
        fun step(now: Long): Boolean
    }

    private val callback = Choreographer.FrameCallback { frameTime ->
        val running = motion ?: return@FrameCallback
        if (running.step(frameTime)) {
            frame()
        } else if (motion === running) {
            motion = null
            if (isDecelerating) {
                isDecelerating = false
                listener?.didEndDecelerating()
            }
        }
    }

    private fun frame(): Unit = Choreographer.getInstance().postFrameCallback(callback)

    private fun stopMotion() {
        Choreographer.getInstance().removeFrameCallback(callback)
        motion = null
        if (isDecelerating) {
            isDecelerating = false
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopMotion()
    }

    companion object {
        /** UIScrollView.DecelerationRate.normal: what's left of the speed after a ms. */
        const val DECELERATION = 0.998
    }
}
