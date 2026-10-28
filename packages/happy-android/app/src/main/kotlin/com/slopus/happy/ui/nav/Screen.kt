package com.slopus.happy.ui.nav

import android.content.Context
import android.view.View
import com.slopus.happy.layout.LayoutInsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * One screen, as happy-ios's view controllers are (and Telegram's
 * `BaseFragment`): it makes its view once, hears when it appears and
 * disappears, and opens others through its [stack]. Its [scope] lives as
 * long as the screen is on a stack.
 */
abstract class Screen(val context: Context) {
    var stack: ScreenStack? = null
        internal set

    private var made: View? = null
    val view: View get() = made ?: createView().also { made = it; viewCreated(it) }
    val isViewLoaded get() = made != null

    /** Cancelled when the screen leaves its stack for good. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** System bars and display cutout, in dp; what [view] lays out against. */
    var safeArea: LayoutInsets = LayoutInsets.Zero
        internal set

    /** The IME's height over the bottom of the window, in dp (0 when hidden). */
    var keyboard: Double = 0.0
        internal set

    protected abstract fun createView(): View
    protected open fun viewCreated(view: View) {}

    /** The screen is about to show (pushed, or uncovered by a pop). */
    open fun onAppear() {}
    open fun onDisappear() {}

    /** The insets changed; the default asks the view to lay out again. */
    open fun onInsetsChanged() {
        made?.requestLayout()
    }

    /** Back was pressed with this screen on top; true when it handled it (a sheet closed, say). */
    open fun onBackPressed(): Boolean = false

    /** Whether the system back gesture may pop this screen. */
    open val canPop: Boolean get() = true

    /**
     * A full-screen modal (UIKit's `.fullScreen` presentation): it rises from
     * the bottom over the screen below, which stays in place, and drops back
     * down when it goes.
     */
    open val isModal: Boolean get() = false

    internal fun destroy() {
        onDestroy()
        scope.cancel()
    }

    open fun onDestroy() {}

    fun push(screen: Screen, animated: Boolean = true) {
        stack?.push(screen, animated)
    }

    fun pop(animated: Boolean = true) {
        val s = stack ?: return
        if (s.top === this) s.pop(animated)
    }
}
