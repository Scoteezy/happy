package com.slopus.happy.ui.nav

import android.content.Context
import android.view.View
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.NavigationBarLayout
import com.slopus.happy.layout.Rect
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.setFrame

/**
 * A screen under a [NavigationBar], as a view controller sits in a
 * UINavigationController: the content fills the screen and lays out against
 * [contentSafeArea], whose top is the bar's bottom. The bar shows back
 * whenever there is a screen beneath.
 */
abstract class BarScreen(context: Context) : Screen(context) {
    val bar = NavigationBar(context)

    /** The safe area with the bar's height on top: what the content keeps clear. */
    val contentSafeArea: LayoutInsets
        get() = LayoutInsets(NavigationBarLayout.height(safeArea.top), safeArea.left, safeArea.bottom, safeArea.right)

    /** Whether the content runs under the bar (it is clear) or starts below it. */
    protected open val contentUnderBar: Boolean get() = true

    protected abstract fun createContent(): View

    private var content: View? = null

    override fun createView(): View = Chrome(context)

    override fun onAppear() {
        super.onAppear()
        val stack = stack
        if (stack != null && stack.all.indexOf(this) > 0 && bar.onBack == null) bar.onBack = { pop() }
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        bar.safeArea = safeArea
        content?.requestLayout()
    }

    private inner class Chrome(context: Context) : ManualLayout(context) {
        init {
            val c = createContent()
            content = c
            addView(c)
            addView(bar)
        }

        override fun layoutSubviews(width: Double, height: Double) {
            bar.safeArea = safeArea
            val barHeight = NavigationBarLayout.height(safeArea.top)
            bar.setFrame(Rect(0.0, 0.0, width, barHeight))
            content?.setFrame(if (contentUnderBar) Rect(0.0, 0.0, width, height) else Rect(0.0, barHeight, width, height - barHeight))
        }
    }
}
