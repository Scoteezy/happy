package com.slopus.happy.ui.tablet

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.NavigationBarLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TabletBreakpoint
import com.slopus.happy.layout.TabletShellLayout
import com.slopus.happy.layout.TabletShellRule
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.home.HomeScreen
import com.slopus.happy.ui.nav.NavigationBar
import com.slopus.happy.ui.nav.ScreenStack
import com.slopus.happy.ui.nav.SessionRoute
import com.slopus.happy.ui.onboarding.OnboardingSettingsScreen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame

/**
 * The window's root: SidebarNavigator.tsx (happy-ios
 * `TabletShellViewController`). It holds the app's one [stack] and, on a
 * tablet-wide window, the sidebar beside it with the persistent header over
 * both.
 *
 * A container of its own rather than a two-pane layout, as on iOS:
 *   - it switches at a window 768 wide ([TabletBreakpoint]), whatever the
 *     device, so a split-screen window can be a phone and a large phone on
 *     its side a tablet;
 *   - the sidebar is 30% of the window held to 250…360, framed by its own
 *     hairline on all four sides;
 *   - zen mode snaps it to nothing, with the header's controls staying put
 *     over the stack;
 *   - the stack is one stack: the sidebar pushes onto it, and switching
 *     layouts keeps everything on it.
 *
 * The stack's root is home. Beside the sidebar home draws nothing
 * (MainView.tsx `isTablet`); home reads that from [ScreenStack.isTabletLayout],
 * which this sets from the window's width. Without the shell (a phone, a
 * narrow window, signed out, the first-run screens) the stack takes the
 * whole window as before.
 *
 * The header row is Android's 56 app bar under the status bar, where iOS's
 * is the iPad's 50: the shell's controls and the sidebar's top line up with
 * the bars of the screens beside them.
 */
@SuppressLint("ViewConstructor")
class TabletShell(context: Context, private val graph: AppGraph, val stack: ScreenStack) : ManualLayout(context), ThemeAware {
    private val header = TabletPersistentHeaderView(context)
    private var sidebar: TabletSidebarView? = null
    /** The signed-in account's home, the stack's root. */
    private var home: HomeScreen? = null
    private var isTablet = false
    private var showsShell = false
    private var zenMode = false
    private var safeArea = LayoutInsets.Zero

    init {
        addView(stack)
        header.visibility = GONE
        header.zenButton.setOnClickListener { toggleZen() }
        header.backButton.setOnClickListener { goBack() }
        addView(header)
        stack.onScreensChanged = { update() }
        stack.onTopChanged = { update() }
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            safeArea = LayoutInsets(px(bars.top), px(bars.left), px(bars.bottom), px(bars.right))
            requestLayout()
            insets
        }
        applyTheme()
    }

    override fun applyTheme() = setBackgroundColor(Theme.color(Palette.groupedBackground))

    /** AppCoordinator calls this after it replaces the stack's root: sign in, sign out, an account switch. */
    fun rootDidChange() {
        val home = stack.all.firstOrNull() as? HomeScreen
        if (home !== this.home) {
            this.home = home
            removeSidebar()
            // Each account keeps its own local settings.
            zenMode = home != null && graph.localSettings.values.value.zenMode
            stack.isZenMode = zenMode
            header.isZenMode = zenMode
            requestLayout()
        }
        update()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Rotation, split screen and freeform resizes decide the layout before anything is placed.
        update(px(MeasureSpec.getSize(widthMeasureSpec)))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Decides the layout for a window [windowWidth] wide: the tablet flag, the sidebar, and whether the shell shows. */
    private fun update(windowWidth: Double = px(width)) {
        if (windowWidth <= 0) return
        val isTablet = TabletBreakpoint.isTablet(windowWidth)
        if (isTablet != this.isTablet) {
            this.isTablet = isTablet
            stack.isTabletLayout = isTablet
        }
        val home = home
        if (isTablet && sidebar == null && home != null) installSidebar(home)
        val showsShell = TabletShellRule.showsShell(
            isAuthenticated = home != null,
            isTablet = isTablet,
            showsInstallStep = sidebar?.state?.showsInstallStep ?: false,
            isOnboardingRoute = stack.top is OnboardingSettingsScreen,
        )
        if (showsShell != this.showsShell) {
            this.showsShell = showsShell
            header.visibility = if (showsShell) VISIBLE else GONE
            requestLayout()
        }
        updateStackState()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = TabletShellLayout(TabletShellLayout.Input(Size(width, height), safeArea, showsShell, zenMode, HEADER_HEIGHT))
        stack.setFrame(layout.detail)
        sidebar?.let { sidebar ->
            val frame = layout.sidebar
            sidebar.visibility = if (frame == null) GONE else VISIBLE
            sidebar.safeTop = safeArea.top
            sidebar.safeBottom = safeArea.bottom
            sidebar.headerHeight = HEADER_HEIGHT
            frame?.let(sidebar::setFrame)
        }
        val frame = layout.header
        val zen = layout.zenButton
        val icon = layout.zenIcon
        val back = layout.backButton
        if (frame != null && zen != null && icon != null && back != null) {
            header.setFrame(frame)
            header.apply(zen.offsetBy(-frame.minX, -frame.minY), icon.offsetBy(-frame.minX, -frame.minY), back.offsetBy(-frame.minX, -frame.minY))
        }
    }

    private fun installSidebar(home: HomeScreen) {
        val sidebar = TabletSidebarView(context, graph, home)
        sidebar.onStateChange = { update() }
        sidebar.visibility = GONE
        addView(sidebar, indexOfChild(header))
        this.sidebar = sidebar
    }

    private fun removeSidebar() {
        val sidebar = sidebar ?: return
        sidebar.close()
        removeView(sidebar)
        this.sidebar = null
    }

    /** The selected row and the back control follow the stack's top. */
    private fun updateStackState() {
        sidebar?.selectedSessionId = (stack.top as? SessionRoute)?.sessionId
        header.canGoBack = stack.size > 1
    }

    private fun toggleZen() {
        zenMode = !zenMode
        graph.localSettings.change { it.copy(zenMode = zenMode) }
        stack.isZenMode = zenMode
        header.isZenMode = zenMode
        // Native React Native snaps the drawer's width; so does this.
        requestLayout()
    }

    private fun goBack() {
        if (stack.size > 1) stack.pop(animated = true)
    }

    companion object {
        /** The row the shell's controls sit in: the bar beside them. */
        const val HEADER_HEIGHT = NavigationBarLayout.Metrics.height
    }
}

/**
 * SidebarNavigator.tsx `PersistentHeader`: zen and back, floating over the
 * sidebar's top and staying put in zen mode. Only the two controls take
 * touches (`pointerEvents="box-none"`); the rest of the row passes them
 * through to whatever is under it. Each control takes touches 10 past its
 * 28 box (`hitSlop`), Material's 48 touch target.
 */
class TabletPersistentHeaderView(context: Context) : ManualLayout(context), ThemeAware {
    val zenButton = ManualControl(context)
    val backButton = ManualControl(context)
    private val zenIcon = ImageView(context)
    private val backIcon = IconView(context, NavigationBar.BACK_ICON, TabletShellLayout.Metrics.backIconSize, Palette.headerTint)
    private var zenIconFrame = Rect.Zero

    var isZenMode = false
        set(value) { if (field != value) { field = value; applyTheme() } }

    /** Back dims to 0.3 with nothing to go back to. */
    var canGoBack = false
        set(value) {
            field = value
            backButton.isEnabled = value
            backButton.alpha = if (value) 1f else TabletShellLayout.Metrics.disabledOpacity.toFloat()
        }

    init {
        zenIcon.setImageResource(R.drawable.zen_icon)
        zenIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        zenButton.addView(zenIcon)
        zenButton.contentDescription = context.getString(R.string.zen_toggle)
        backButton.addView(backIcon)
        backButton.contentDescription = context.getString(R.string.common_back)
        addView(zenButton)
        addView(backButton)
        canGoBack = false
        applyTheme()
    }

    /** Applies the shell's frames, given in this view's coordinates; the controls grow by their hit slop around them. */
    fun apply(zen: Rect, icon: Rect, back: Rect) {
        val slop = TabletShellLayout.Metrics.hitSlop
        val zenTouch = zen.insetBy(-slop, -slop)
        zenButton.setFrame(zenTouch)
        zenIconFrame = icon.offsetBy(-zenTouch.minX, -zenTouch.minY)
        zenIcon.setFrame(zenIconFrame)
        val backTouch = back.insetBy(-slop, -slop)
        backButton.setFrame(backTouch)
        backIcon.setFrame(back.offsetBy(-backTouch.minX, -backTouch.minY))
    }

    override fun applyTheme() {
        // The glyph turns the link colour while zen mode is on.
        zenIcon.imageTintList = ColorStateList.valueOf(Theme.color(if (isZenMode) Palette.textLink else Palette.headerTint))
        zenButton.isSelected = isZenMode
        for (button in listOf(zenButton, backButton)) {
            button.background = RippleDrawable(ColorStateList.valueOf(Theme.color(Palette.surfacePressedOverlay)), null, null)
        }
    }

    override fun layoutSubviews(width: Double, height: Double) = Unit

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = ev.x.toInt()
            val y = ev.y.toInt()
            val hit = listOf(zenButton, backButton).any { x in it.left until it.right && y in it.top until it.bottom }
            if (!hit) return false
        }
        return super.dispatchTouchEvent(ev)
    }
}

/** A tappable box laid out by its owner. */
class ManualControl(context: Context) : ManualLayout(context) {
    init {
        isClickable = true
        isFocusable = true
    }

    override fun layoutSubviews(width: Double, height: Double) = Unit
}
