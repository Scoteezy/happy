package com.slopus.happy.ui.nav

import android.content.Context
import android.view.View
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.NavigationBarLayout
import com.slopus.happy.layout.Palette
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
    /** Opaque in the grouped background, as AppCoordinator.applyAppearance makes every pushed bar. */
    val bar = NavigationBar(context).apply { fill = Palette.groupedBackground }

    /** The safe area with the bar's height on top: what the content keeps clear. */
    val contentSafeArea: LayoutInsets
        get() = LayoutInsets(NavigationBarLayout.height(safeArea.top), safeArea.left, safeArea.bottom, safeArea.right)

    /** Whether the content runs under the bar (it is clear) or starts below it. */
    protected open val contentUnderBar: Boolean get() = true

    protected abstract fun createContent(): View

    private var content: View? = null

    override fun createView(): View = Chrome(context)

    /**
     * navigation/Header.tsx :322: happy-app's plain header (settings, session
     * info, machine, What's New) has no back button on a tablet, where the
     * tablet shell's persistent back serves instead (happy-ios
     * `dropsBackButtonOnTablet`). The system back still pops.
     */
    protected open val dropsBackButtonOnTablet: Boolean get() = false

    /** False for a screen that puts something else where back goes (Cancel on a sheet). */
    protected open val wantsBackButton: Boolean get() = true

    override fun onAppear() {
        super.onAppear()
        updateBackButton()
    }

    override fun onTabletLayoutChanged() {
        super.onTabletLayoutChanged()
        updateBackButton()
    }

    private fun updateBackButton() {
        val stack = stack ?: return
        val shows = wantsBackButton && stack.all.indexOf(this) > 0 && !(dropsBackButtonOnTablet && isTabletLayout)
        if (shows && bar.onBack == null) bar.onBack = { pop() } else if (!shows && bar.onBack != null) bar.onBack = null
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
