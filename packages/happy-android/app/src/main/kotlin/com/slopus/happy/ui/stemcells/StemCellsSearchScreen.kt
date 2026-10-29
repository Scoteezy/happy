package com.slopus.happy.ui.stemcells

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.inputmethod.EditorInfo
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.stemcells.StemCellsSearchLayout
import com.slopus.happy.stemcells.StemCellsRow
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.TextField
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.BounceScrollView
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame
import java.text.Normalizer

/**
 * The sidebar's search (happy-ios `StemCellsSearchViewController`): every
 * row of the sidebar, folded away or not, whose name holds what was typed,
 * drawn as the sidebar draws them, flat. The field takes the keyboard as
 * the screen comes up empty; scrolling the list puts it away.
 */
class StemCellsSearchScreen(
    context: Context,
    private val session: StemCellsSession,
    private val onOpen: (StemCellsRow) -> Unit,
) : BarScreen(context) {
    private val content = Content(context)
    private val searchBar = SearchBar(context)
    private val list = StemCellsSidebarView(context)
    private var unwatch: (() -> Unit)? = null
    private var all: List<StemCellsRow> = emptyList()
    private var query = ""

    override val contentUnderBar: Boolean get() = false

    /** What the probe reads: the query and the rows it found. */
    val probe: String get() = "search=${query.replace(' ', '_')} found=${list.rows.size}"

    override fun createContent(): View {
        bar.title = context.getString(R.string.stem_search)
        content.tag = "stem-search-screen"
        content.addView(list)
        content.addView(searchBar)
        list.images = session.images
        list.onSelect = { row -> onOpen(row) }
        list.list.listener = object : BounceScrollView.Listener {
            override fun willBeginDragging() = searchBar.field.dismissKeyboard()
        }
        searchBar.field.onChange = { text ->
            query = text
            searchBar.update()
            filter()
        }
        unwatch = session.watch { snapshot ->
            all = snapshot.allRows
            filter()
        }
        return content
    }

    override fun onAppear() {
        super.onAppear()
        if (query.isEmpty()) searchBar.field.post { searchBar.field.focus() }
    }

    override fun onDisappear() {
        searchBar.field.dismissKeyboard()
    }

    override fun onDestroy() {
        unwatch?.invoke()
    }

    private fun filter() {
        val needle = fold(query.trim())
        val matches = all.filter { row ->
            (row.kind == StemCellsRow.Kind.Space || row.kind == StemCellsRow.Kind.Session) && (needle.isEmpty() || needle in fold(row.label))
        }.map { it.copy(depth = 0) }
        list.apply(matches)
        // A new query reads from its first match.
        list.list.setContentOffset(-list.list.insetTop)
        content.requestLayout()
    }

    /** Case and diacritics set aside, as iOS's `[.caseInsensitive, .diacriticInsensitive]`. */
    private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

    /** UISearchBar's minimal field: a raised pill with the glass, the field and, once there is text, its clear button. */
    private inner class SearchBar(context: Context) : ManualLayout(context), ThemeAware {
        private val pill = View(context)
        private val icon = IconView(context, Icon.ionicons("search"), 20.0, Palette.textSecondary)
        val field = TextField(context, StemCellsFonts.ui(StemCellsFonts.Weight.Regular, 17.0))
        private val clear = IconView(context, Icon.ionicons("close-circle"), 20.0, Palette.text)

        init {
            pill.elevation = dp(3.0)
            addView(pill)
            addView(icon)
            field.hint = context.getString(R.string.stem_search)
            field.isSingleLine = true
            field.background = null
            field.setPadding(0, 0, 0, 0)
            field.imeOptions = EditorInfo.IME_ACTION_SEARCH
            field.onReturn = { field.dismissKeyboard() }
            field.tag = "stem-search-field"
            addView(field)
            clear.contentDescription = context.getString(R.string.stem_clear_search)
            clear.setOnClickListener {
                field.setText("")
                field.focus()
            }
            addView(clear)
            for (view in listOf(icon, field, clear)) view.elevation = pill.elevation
            update()
            applyTheme()
        }

        fun update() {
            clear.visibility = if (field.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        override fun applyTheme() {
            pill.background = GradientDrawable().apply {
                setColor(Theme.color(Palette.surface))
                cornerRadius = dp(PILL / 2)
            }
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val top = (height - PILL) / 2
            pill.setFrame(Rect(8.0, top, width - 16, PILL))
            icon.setFrame(Rect(20.0, (height - 20) / 2, 20.0, 20.0))
            clear.setFrame(Rect(width - 40, (height - 20) / 2, 20.0, 20.0))
            field.setFrame(Rect(48.0, top, width - 48 - 48, PILL))
        }
    }

    private inner class Content(context: Context) : ManualLayout(context), ThemeAware {
        init {
            applyTheme()
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(Palette.surface))
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val layout = StemCellsSearchLayout(width, LayoutInsets(0.0, 0.0, safeArea.bottom + keyboard, 0.0), BAR)
            searchBar.setFrame(layout.bar)
            list.setFrame(Rect(0.0, 0.0, width, height))
            val scroll = list.list
            val insets = layout.listInsets
            if (scroll.insetTop != insets.top || scroll.insetBottom != insets.bottom) {
                val atTop = scroll.contentOffset <= -scroll.insetTop + 1
                scroll.setInsets(insets.top, insets.bottom)
                // The list starts under the field, and stays there while it is at its top.
                if (atTop) scroll.setContentOffset(-insets.top)
            }
        }
    }

    private companion object {
        /** UISearchBar's height as it fits, and its field's. */
        const val BAR = 56.0
        const val PILL = 44.0
        val MARKS = Regex("\\p{Mn}+")
    }
}
