package com.slopus.happy.ui.stemcells

import android.content.Context
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.stemcells.StemCellsGlassBarLayout
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.components.TextField
import com.slopus.happy.ui.home.HeaderControlView
import com.slopus.happy.ui.home.HeaderScrimView
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.setFrameOrHide

/**
 * The glass bar of Stem Cells settings and the screens around it (happy-ios
 * `StemCellsGlassBar`), as a conversation's header is: a glass circle to go
 * back, or to close a sheet at the right; the title centred in a glass
 * capsule; the actions in one glass capsule at the right, split by
 * hairlines; floating over the screen, which scrolls under them and a
 * scrim. Searching, a field in a glass capsule takes the row beside a
 * circle that ends the search, the row's glass crossing to it over 0.25 s.
 * Frames are [StemCellsGlassBarLayout]'s. It covers its screen; touches
 * anywhere but on its glass go through.
 */
class StemCellsGlassBarView(context: Context) : ManualLayout(context), ThemeAware {
    data class Action(val icon: Icon, val label: String, val id: String, val run: () -> Unit)

    var hasBack = false
        set(value) { if (field != value) { field = value; requestLayout() } }
    var onBack: (() -> Unit)? = null
    /** A sheet's first screen: a close circle at the right, the row dropped. */
    var closes = false
        set(value) { if (field != value) { field = value; requestLayout() } }
    var onClose: (() -> Unit)? = null

    var title: String? = null
        set(value) {
            if (field == value) return
            field = value
            titleLabel.text = value ?: ""
            requestLayout()
        }

    /** Left to right. */
    var actions: List<Action> = emptyList()
        set(value) { field = value; applyActions() }

    /** Searching: the field takes the row. */
    var isSearching = false
        set(value) {
            if (field == value) return
            field = value
            crossSearch()
        }
    var onEndSearch: (() -> Unit)? = null
    /** The search field's text, as it is typed. */
    var onSearchText: ((String) -> Unit)? = null
    val searchField = TextField(context, StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 17.0))

    /** The status bar's height: the row starts under it. */
    var safeTop = 0.0
        set(value) { if (field != value) { field = value; requestLayout() } }

    private val scrim = HeaderScrimView(context)
    private val back = HeaderControlView(context)
    private val backIcon = IconView(context, Icon.ionicons("chevron-back"), 28.0, Palette.headerTint)
    private val close = HeaderControlView(context)
    private val closeIcon = IconView(context, Icon.ionicons("close-outline"), 28.0, Palette.headerTint)
    private val pill = HeaderControlView(context)
    private val titleLabel = Label(context, TextStyles.nativeHeaderTitle, Palette.headerTint, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER).apply {
        followsTextSize = false
    }
    private val actionsGlass = HeaderControlView(context)
    private var buttons: List<IconView> = emptyList()
    private var dividers: List<View> = emptyList()
    private val searchGlass = HeaderControlView(context)
    private val searchIcon = IconView(context, StemCellsGlyphs.icon("search"), 18.0, Palette.textSecondary)
    private val clear = IconView(context, Icon.ionicons("close-circle"), 18.0, Palette.textSecondary)
    private val endSearch = HeaderControlView(context)
    private val endSearchIcon = IconView(context, Icon.ionicons("close-outline"), 28.0, Palette.headerTint)
    /** What searching moves between: the row's glass, then the search's. */
    private val rowViews get() = listOf<View>(back, close, pill, titleLabel, actionsGlass)
    private val searchViews get() = listOf<View>(searchGlass, endSearch)

    init {
        addView(scrim)
        back.tag = "stem-glass-back"
        back.contentDescription = context.getString(R.string.stem_back)
        back.isClickable = true
        back.setOnClickListener { onBack?.invoke() }
        back.addView(backIcon)
        addView(back)
        close.tag = "stem-glass-close"
        close.contentDescription = context.getString(R.string.stem_close)
        close.isClickable = true
        close.setOnClickListener { onClose?.invoke() }
        close.addView(closeIcon)
        addView(close)
        addView(pill)
        // Over the capsule, which stands at its shadow's elevation; casting nothing.
        titleLabel.outlineProvider = null
        titleLabel.elevation = pill.elevation + 1
        androidx.core.view.ViewCompat.setAccessibilityHeading(titleLabel, true)
        addView(titleLabel)
        addView(actionsGlass)
        searchField.isSingleLine = true
        searchField.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        searchField.onReturn = { searchField.dismissKeyboard() }
        searchField.onChange = { text ->
            clear.visibility = if (text.isEmpty()) GONE else VISIBLE
            onSearchText?.invoke(text)
        }
        searchField.tag = "stem-glass-search-field"
        clear.visibility = GONE
        clear.contentDescription = context.getString(R.string.stem_clear_search)
        clear.isClickable = true
        clear.setOnClickListener {
            searchField.setText("")
            searchField.focus()
        }
        for (view in listOf(searchIcon, searchField, clear)) searchGlass.addView(view)
        addView(searchGlass)
        endSearch.tag = "stem-glass-end-search"
        endSearch.contentDescription = context.getString(R.string.common_cancel)
        endSearch.isClickable = true
        endSearch.setOnClickListener { onEndSearch?.invoke() }
        endSearch.addView(endSearchIcon)
        addView(endSearch)
        for (view in searchViews) view.visibility = GONE
    }

    private var crossing: android.animation.ValueAnimator? = null

    /** The row's glass fades as the search's comes, or back, over 0.25 s. */
    private fun crossSearch() {
        crossing?.cancel()
        val searching = isSearching
        val (going, coming) = if (searching) rowViews to searchViews else searchViews to rowViews
        for (view in coming) {
            view.alpha = 0f
            view.visibility = VISIBLE
        }
        requestLayout()
        crossing = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            addUpdateListener {
                val t = it.animatedValue as Float
                for (view in going) view.alpha = 1 - t
                for (view in coming) view.alpha = t
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    for (view in going) {
                        view.visibility = GONE
                        view.alpha = 1f
                    }
                    for (view in coming) view.alpha = 1f
                    requestLayout()
                }
            })
            start()
        }
    }

    private fun applyActions() {
        for (view in buttons + dividers) actionsGlass.removeView(view)
        buttons = actions.map { action ->
            IconView(context, action.icon, 24.0, Palette.headerTint).apply {
                contentDescription = action.label
                tag = action.id
                isClickable = true
                isFocusable = true
                setOnClickListener { action.run() }
                actionsGlass.addView(this)
            }
        }
        dividers = actions.drop(1).map {
            View(context).apply {
                setBackgroundColor(Theme.color(Palette.divider))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                actionsGlass.addView(this)
            }
        }
        requestLayout()
    }

    fun layout(searching: Boolean = false) = StemCellsGlassBarLayout(
        px(width), safeTop, 1.0 / resources.displayMetrics.density.coerceAtLeast(1f), hasBack, actions.size,
        title?.let { titleLabel.measure(UNBOUNDED).width }, searching = searching, closes = closes,
    )

    val layout: StemCellsGlassBarLayout get() = layout()

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout
        scrim.setFrame(layout.backdrop)
        // Nothing on the row, nothing to keep legible.
        scrim.visibility = if (!hasBack && !closes && title == null && actions.isEmpty() && !isSearching) GONE else VISIBLE
        // While the two cross, both are placed: each where it stands in its own row.
        val crossing = crossing?.isRunning == true
        if (!isSearching || crossing) placeRow(layout)
        if (isSearching || crossing) placeSearch(layout(searching = true))
    }

    private fun placeSearch(layout: StemCellsGlassBarLayout) {
        val pill = layout.searchPill ?: return
        searchGlass.setFrame(pill)
        val field = layout.field ?: return
        fun local(rect: Rect) = Rect(rect.minX - pill.minX, rect.minY - pill.minY, rect.width, rect.height)
        val icon = 18.0
        val f = local(field)
        searchIcon.setFrame(Rect(f.minX, f.midY - icon / 2, icon, icon))
        clear.setFrame(Rect(f.maxX - icon, f.midY - icon / 2, icon, icon))
        searchField.setFrame(Rect(f.minX + icon + 8, f.minY, f.width - 2 * (icon + 8), f.height))
        layout.cancel?.let {
            endSearch.setFrame(it)
            endSearchIcon.setFrame(Rect(0.0, 0.0, it.width, it.height))
        }
    }

    private fun placeRow(layout: StemCellsGlassBarLayout) {
        back.setFrameOrHide(layout.leading)
        layout.leading?.let { backIcon.setFrame(Rect(0.0, 0.0, it.width, it.height)) }
        close.setFrameOrHide(layout.close)
        layout.close?.let { closeIcon.setFrame(Rect(0.0, 0.0, it.width, it.height)) }
        pill.setFrameOrHide(layout.pill)
        val title = layout.title
        if (title != null) {
            // The title's own line, centred on its place in the capsule.
            val line = titleLabel.lineHeight
            titleLabel.setFrame(Rect(title.minX, title.midY - line / 2, title.width, line))
        } else {
            titleLabel.setFrameOrHide(null)
        }
        actionsGlass.setFrameOrHide(layout.actions)
        layout.actions?.let { capsule ->
            fun local(rect: Rect) = Rect(rect.minX - capsule.minX, rect.minY - capsule.minY, rect.width, rect.height)
            for ((button, frame) in buttons.zip(layout.buttons)) button.setFrame(local(frame))
            for ((divider, frame) in dividers.zip(layout.dividers)) divider.setFrame(local(frame))
        }
    }

    /** Only its glass takes touches. */
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun applyTheme() {
        scrim.applyTheme()
        for (glass in listOf(back, close, pill, actionsGlass, searchGlass, endSearch)) glass.applyTheme()
        for (icon in listOf(searchIcon, clear, endSearchIcon)) icon.applyTheme()
        searchField.applyTheme()
        backIcon.applyTheme()
        closeIcon.applyTheme()
        titleLabel.applyTheme()
        for (button in buttons) button.applyTheme()
        for (divider in dividers) divider.setBackgroundColor(Theme.color(Palette.divider))
    }

    private companion object {
        /** A single line's whole width. */
        const val UNBOUNDED = 100_000.0
    }
}
