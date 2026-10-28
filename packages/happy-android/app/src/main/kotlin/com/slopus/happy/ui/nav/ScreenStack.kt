package com.slopus.happy.ui.nav

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.ui.view.px
import kotlin.math.max

/**
 * The window's stack of screens (happy-ios's `UINavigationController`,
 * Telegram's `ActionBarLayout`): push slides the new screen in from the
 * right over the one below, which shifts a little and dims; pop reverses it.
 * The system back gesture drives an interactive pop (predictive back), the
 * screen following the finger as UIKit's edge swipe does, and back pressed
 * pops at once. It hands every screen the window's insets in dp, the IME's
 * frame by frame as it animates. A modal screen ([Screen.isModal]) rises
 * from the bottom instead, and the one below stays put.
 */
class ScreenStack(context: Context) : FrameLayout(context) {
    private val screens = mutableListOf<Screen>()
    val top: Screen? get() = screens.lastOrNull()
    val all: List<Screen> get() = screens.toList()
    val size get() = screens.size

    private var safeArea = LayoutInsets.Zero
    private var keyboard = 0.0
    private var animating: Animator? = null
    /** 0 = top screen in place, 1 = fully slid away (the pop's progress). */
    private var popProgress = 0f
    private val dimPaint = Paint()

    /** Called whenever the top screen changes (after the transition). */
    var onTopChanged: ((Screen?) -> Unit)? = null

    val backCallback = object : OnBackPressedCallback(false) {
        private var interactive = false

        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            interactive = canInteractivelyPop()
            if (interactive) beginInteractivePop()
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            if (interactive) setPopProgress(backEvent.progress)
        }

        override fun handleOnBackCancelled() {
            if (interactive) finishInteractivePop(commit = false)
            interactive = false
        }

        override fun handleOnBackPressed() {
            if (interactive) {
                interactive = false
                finishInteractivePop(commit = true)
                return
            }
            val top = top ?: return
            if (top.onBackPressed()) return
            if (screens.size > 1 && top.canPop) pop(animated = true)
        }
    }

    init {
        setWillNotDraw(false)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            safeArea = LayoutInsets(px(bars.top), px(bars.left), px(bars.bottom), px(bars.right))
            if (!imeAnimating) keyboard = max(0.0, px(ime.bottom) - px(bars.bottom))
            screens.forEach(::deliverInsets)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(this, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimating = true
            }

            override fun onProgress(insets: WindowInsetsCompat, running: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                if (running.none { it.typeMask and WindowInsetsCompat.Type.ime() != 0 }) return insets
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                keyboard = max(0.0, px(ime.bottom) - px(bars.bottom))
                screens.forEach(::deliverInsets)
                return insets
            }

            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                    imeAnimating = false
                    ViewCompat.requestApplyInsets(this@ScreenStack)
                }
            }
        })
    }

    private var imeAnimating = false

    private fun deliverInsets(screen: Screen) {
        if (screen.safeArea == safeArea && screen.keyboard == keyboard) return
        screen.safeArea = safeArea
        screen.keyboard = keyboard
        screen.onInsetsChanged()
    }

    private fun attach(screen: Screen, index: Int = -1) {
        screen.stack = this
        screen.safeArea = safeArea
        screen.keyboard = keyboard
        val v = screen.view
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        addView(v, index, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        screen.onInsetsChanged()
    }

    private fun detach(screen: Screen) {
        removeView(screen.view)
        screen.stack = null
        screen.destroy()
    }

    /** Replaces the whole stack with [screens] (the root first). */
    fun setScreens(next: List<Screen>, animated: Boolean = false) {
        animating?.end()
        val old = screens.toList()
        old.lastOrNull()?.onDisappear()
        screens.clear()
        next.forEachIndexed { i, s ->
            screens += s
            if (s.stack !== this) attach(s)
            s.view.visibility = if (i == next.lastIndex) View.VISIBLE else View.INVISIBLE
            s.view.translationX = 0f
        }
        val removed = old.filter { it !in next }
        if (animated && removed.isNotEmpty()) {
            val incoming = next.last().view
            incoming.alpha = 0f
            incoming.animate().alpha(1f).setDuration(250).withEndAction {
                removed.forEach(::detach)
            }.start()
        } else {
            removed.forEach(::detach)
        }
        next.lastOrNull()?.onAppear()
        updateBack()
        onTopChanged?.invoke(top)
    }

    fun push(screen: Screen, animated: Boolean = true) {
        animating?.end()
        val below = top
        screens += screen
        attach(screen)
        below?.onDisappear()
        screen.onAppear()
        updateBack()
        if (!animated || below == null || width == 0) {
            below?.view?.visibility = View.INVISIBLE
            onTopChanged?.invoke(screen)
            return
        }
        popProgress = 1f
        applyProgress()
        animateProgress(to = 0f, duration = PUSH_MS) {
            below.view.visibility = View.INVISIBLE
            below.view.translationX = 0f
            onTopChanged?.invoke(screen)
        }
    }

    fun pop(animated: Boolean = true) {
        if (screens.size < 2) return
        animating?.end()
        val leaving = screens.last()
        val below = screens[screens.size - 2]
        below.view.visibility = View.VISIBLE
        leaving.onDisappear()
        below.onAppear()
        if (!animated || width == 0) {
            screens.removeAt(screens.lastIndex)
            detach(leaving)
            below.view.translationX = 0f
            popProgress = 0f
            updateBack()
            onTopChanged?.invoke(below)
            invalidate()
            return
        }
        popProgress = 0f
        animateProgress(to = 1f, duration = POP_MS) { completePop() }
    }

    /**
     * UIKit's popToViewController: the screens between [target] and the top
     * go at once, then the top pops off to [target].
     */
    fun popTo(target: Screen, animated: Boolean = true) {
        val index = screens.indexOf(target)
        if (index < 0 || index == screens.lastIndex) return
        screens.subList(index + 1, screens.size - 1).toList().forEach { s -> screens.remove(s); detach(s) }
        pop(animated)
    }

    /** Pops everything above the root. */
    fun popToRoot(animated: Boolean = true) {
        if (screens.size < 2) return
        val root = screens.first()
        val keep = listOf(root, screens.last())
        screens.subList(1, screens.size - 1).toList().forEach { s -> screens.remove(s); detach(s) }
        if (keep.size == 2) pop(animated) else setScreens(listOf(root))
    }

    private fun completePop() {
        val leaving = screens.removeAt(screens.lastIndex)
        detach(leaving)
        top?.view?.translationX = 0f
        popProgress = 0f
        updateBack()
        onTopChanged?.invoke(top)
        invalidate()
    }

    private fun canInteractivelyPop(): Boolean {
        val top = top ?: return false
        return screens.size > 1 && top.canPop && animating == null && !top.interceptsBack()
    }

    private fun beginInteractivePop() {
        val below = screens[screens.size - 2]
        below.view.visibility = View.VISIBLE
        popProgress = 0f
        applyProgress()
    }

    private fun setPopProgress(progress: Float) {
        popProgress = progress.coerceIn(0f, 1f)
        applyProgress()
    }

    private fun finishInteractivePop(commit: Boolean) {
        if (screens.size < 2) return
        val leaving = screens.last()
        val below = screens[screens.size - 2]
        val remaining = if (commit) 1f - popProgress else popProgress
        val duration = (POP_MS * remaining.coerceIn(0.35f, 1f)).toLong()
        if (commit) {
            leaving.onDisappear()
            below.onAppear()
            animateProgress(1f, duration) { completePop() }
        } else {
            animateProgress(0f, duration) {
                below.view.visibility = View.INVISIBLE
                below.view.translationX = 0f
            }
        }
    }

    private fun animateProgress(to: Float, duration: Long, end: () -> Unit) {
        val animator = ValueAnimator.ofFloat(popProgress, to)
        animator.duration = duration
        animator.interpolator = EASE
        animator.addUpdateListener {
            popProgress = it.animatedValue as Float
            applyProgress()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                animating = null
                popProgress = to
                applyProgress()
                end()
            }
        })
        animating = animator
        animator.start()
    }

    private fun applyProgress() {
        val top = screens.lastOrNull() ?: return
        val below = screens.getOrNull(screens.size - 2)
        if (top.isModal) {
            top.view.translationY = height.toFloat() * popProgress
            below?.view?.translationX = 0f
        } else {
            val w = width.toFloat()
            top.view.translationX = w * popProgress
            below?.view?.translationX = -w * PARALLAX * (1f - popProgress)
        }
        invalidate()
    }

    /** The dim over the screen below during a transition. */
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val result = super.drawChild(canvas, child, drawingTime)
        val below = screens.getOrNull(screens.size - 2)?.view
        if (child === below && below.visibility == View.VISIBLE && popProgress < 1f) {
            dimPaint.color = Color.argb((DIM * (1f - popProgress) * 255).toInt(), 0, 0, 0)
            canvas.drawRect(child.left + child.translationX, 0f, child.right + child.translationX, height.toFloat(), dimPaint)
        }
        return result
    }

    /** No touches while a transition runs. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = animating != null || super.onInterceptTouchEvent(ev)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (animating != null) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun updateBack() {
        backCallback.isEnabled = screens.size > 1 || (top?.interceptsBack() == true)
    }

    /** Re-reads whether the top screen wants back (a sheet opened in it, say). */
    fun backStateChanged() = updateBack()

    companion object {
        const val PUSH_MS = 350L
        const val POP_MS = 300L
        const val PARALLAX = 0.3f
        const val DIM = 0.12f
        val EASE = PathInterpolator(0.2f, 0.9f, 0.25f, 1f)
    }
}

/** Whether the screen handles back itself right now (an open sheet), so no interactive pop starts. */
fun Screen.interceptsBack(): Boolean = (this as? BackInterceptor)?.interceptsBack == true

interface BackInterceptor {
    val interceptsBack: Boolean
}
