package com.slopus.happy.ui.stemcells

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellsMetrics
import com.slopus.happy.layout.stemcells.StemCellsSidebarLayout
import com.slopus.happy.stemcells.StemCellsRow
import com.slopus.happy.stemcells.StemCellsRow.Kind
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.BounceScrollView
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.density
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame

/**
 * The Stem Cells sidebar as one scrolling column (happy-ios
 * `StemCellsSidebarView`). Rows are views keyed by their ID: a row that did
 * not change is not touched, a changed one is reconfigured in place, and a
 * fold only moves the rows after it. Nothing here ever resets the scroll
 * position. [list] is the scroll, which home's stories pull on.
 */
class StemCellsSidebarView(context: Context) : ManualLayout(context), ThemeAware {
    var onSelect: ((StemCellsRow) -> Unit)? = null
    /** A finger came down on a row that opens: it may be opened next. */
    var onIntent: ((StemCellsRow) -> Unit)? = null
    var onFold: ((StemCellsRow) -> Unit)? = null
    /** A section's "+": new work in that section. Sections show it only when set. */
    var onAdd: ((StemCellsRow) -> Unit)? = null
    var images: StemCellsImages? = null
    /** Room above the first row and below the last; the sidebar's own when null. */
    var padding: Pair<Double, Double>? = null

    /** The row whose conversation is open, which stays selected. */
    var selectedRowId: String? = null
        set(value) {
            if (field == value) return
            field = value
            for ((id, view) in views) view.current = id == value
        }

    val list = BounceScrollView(context)
    var rows: List<StemCellsRow> = emptyList()
        private set
    private val views = HashMap<String, StemCellsRowView>()
    private val connectors = ConnectorsView(context)
    private var laidOut: Pair<Double, List<StemCellsSidebarLayout.Row>>? = null
    var layoutResult: StemCellsSidebarLayout.Result? = null
        private set
    /** Where the last finger went down on a row, in the row's own dp. */
    private var downX = 0.0

    init {
        addView(list)
        list.content.addView(connectors)
        list.content.frames[connectors] = null
    }

    /** Shows `rows`, touching only the rows that changed. Returns how many it touched. */
    fun apply(rows: List<StemCellsRow>): Int {
        val previous = this.rows.associateBy { it.id }
        // Other rows in the same shapes (one swapped for another) are placed again: their views are new.
        if (this.rows.map { it.id } != rows.map { it.id }) laidOut = null
        this.rows = rows
        var touched = 0
        val live = HashSet<String>()
        for (row in rows) {
            live += row.id
            val existing = views[row.id]
            val view = if (existing != null) {
                if (previous[row.id] == row) continue
                existing
            } else {
                makeRow().also { views[row.id] = it }
            }
            view.configure(row)
            view.current = row.id == selectedRowId
            touched++
        }
        val gone = views.keys.filter { it !in live }
        for (id in gone) {
            val view = views.remove(id) ?: continue
            list.content.removeView(view)
            list.content.frames.remove(view)
            touched++
        }
        requestLayout()
        return touched
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeRow(): StemCellsRowView {
        val view = StemCellsRowView(context)
        view.images = images
        list.content.addView(view)
        view.setOnTouchListener { touched, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = px(event.x) + px(touched.left)
                val row = (touched as StemCellsRowView).row
                if (row != null && onSelect != null && row.opens) onIntent?.invoke(row)
            }
            false
        }
        view.setOnClickListener { tapped(view) }
        return view
    }

    private fun tapped(view: StemCellsRowView) {
        val row = view.row ?: return
        val frames = view.frames
        val x = downX
        when (row.kind) {
            Kind.Section -> {
                // Its "+", with a finger's room around it, makes new work in it.
                val add = frames?.add
                val onAdd = onAdd
                if (add != null && onAdd != null && x >= add.minX - 7 && x < add.maxX + StemCellsMetrics.trailingGap / 2) onAdd(row) else onFold?.invoke(row)
            }
            Kind.Separator -> {}
            else -> {
                // A row's fold is its chevron, with a finger's room around it;
                // a row that opens nothing (a proposal's) folds wherever it's tapped.
                val chevron = frames?.chevron
                if (chevron != null && (onSelect == null || x >= chevron.minX - 14)) onFold?.invoke(row) else onSelect?.invoke(row)
            }
        }
    }

    /** The rows on screen now, top first. */
    val visibleRows: List<StemCellsRow>
        get() {
            val frames = layoutResult?.frames ?: return emptyList()
            if (frames.size != rows.size) return emptyList()
            val top = list.contentOffset
            val bottom = top + px(list.height)
            return rows.zip(frames).filter { (_, frame) -> frame.box.maxY > top && frame.box.minY < bottom }.map { it.first }
        }

    fun view(rowId: String): StemCellsRowView? = views[rowId]

    private fun layoutRows(rows: List<StemCellsRow>) = layoutRows(rows, onAdd != null)

    override fun layoutSubviews(width: Double, height: Double) {
        list.setFrame(Rect(0.0, 0.0, width, height))
        val input = layoutRows(rows)
        if (laidOut?.first != width || laidOut?.second != input || layoutResult?.frames?.size != rows.size) place(width, input)
    }

    /** Lays the rows out and moves each to its place. */
    private fun place(width: Double, input: List<StemCellsSidebarLayout.Row>) {
        val result = StemCellsSidebarLayout.layout(
            input, width,
            top = padding?.first ?: StemCellsMetrics.contentTop,
            bottom = padding?.second ?: StemCellsMetrics.contentBottom,
            scale = density.toDouble(),
        )
        layoutResult = result
        laidOut = width to input
        for ((row, frame) in rows.zip(result.frames)) {
            val view = views[row.id] ?: continue
            view.frames = frame
            list.content.frames[view] = frame.box
        }
        connectors.lines = result.connectors
        list.content.frames[connectors] = Rect(0.0, 0.0, width, result.contentHeight)
        list.contentHeight = result.contentHeight
        list.requestLayout()
    }

    override fun applyTheme() = connectors.invalidate()

    /** The tree's lines: its stems and elbows. */
    private class ConnectorsView(context: Context) : View(context) {
        var lines: List<Rect> = emptyList()
            set(value) { if (field != value) { field = value; invalidate() } }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            val ink = Theme.rgba(StemCellsPalette.stemCellsRowText)
            paint.color = ink.copy(alpha = ink.alpha * StemCellsMetrics.connectorAlpha).argb
            for (line in lines) canvas.drawRect(dp(line.minX), dp(line.minY), dp(line.maxX), dp(line.maxY), paint)
        }
    }

    companion object {
        fun layoutRows(rows: List<StemCellsRow>, adds: Boolean = false): List<StemCellsSidebarLayout.Row> = rows.map { row ->
            val kind = when (row.kind) {
                Kind.Section -> StemCellsSidebarLayout.Kind.Section
                Kind.Separator -> StemCellsSidebarLayout.Kind.Separator
                Kind.Widget -> StemCellsSidebarLayout.Kind.Widget
                else -> StemCellsSidebarLayout.Kind.Row
            }
            StemCellsSidebarLayout.Row(
                kind = kind,
                depth = row.depth,
                hasMark = row.mark != StemCellsRow.Mark.None,
                hasTrailing = row.status == StemCellsRow.Status.Waiting || row.status == StemCellsRow.Status.AwaitingInput,
                hasFold = (row.kind == Kind.Space || row.kind == Kind.Session) && row.collapsed != null,
                hasAdd = adds && row.kind == Kind.Section,
                foldClosed = row.collapsed == true || row.hasHiddenSessions,
            )
        }
    }
}
