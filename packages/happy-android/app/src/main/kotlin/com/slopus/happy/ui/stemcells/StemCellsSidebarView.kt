package com.slopus.happy.ui.stemcells

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellsMetrics
import com.slopus.happy.layout.stemcells.StemCellsSidebarLayout
import com.slopus.happy.stemcells.StemCellsRow
import com.slopus.happy.stemcells.StemCellsDrag
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
    var onHold: ((StemCellsRow, StemCellsRowView) -> Boolean)? = null
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
    private var arranged: List<String>? = null
    private var heldIds = emptySet<String>()
    private var carryIds: List<String>? = null
    var onReorderInvalidated: (() -> Unit)? = null
    private val settleOrder = Runnable { arranged = null; laidOut = null; requestLayout() }
    private val drawingRows: List<StemCellsRow>
        get() = arranged?.let { ids -> val current = rows.associateBy { it.id }; ids.mapNotNull(current::get) } ?: rows

    data class CarryPicture(val image: Bitmap, val frame: Rect, val heights: List<Double>)

    /** Snapshots the whole carried block, including children, bounded to four megapixels. */
    fun prepareReorder(drag: StemCellsDrag): CarryPicture? {
        if (arranged != null || isEditing || carryIds != null) return null
        val rect = heldFrame(drag) ?: return null
        val frames = layoutResult?.frames ?: return null
        if (drag.units.any { unit -> unit.any { it !in frames.indices } }) return null
        val w = dp(rect.width).toInt().coerceAtLeast(1)
        val h = dp(rect.height).toInt().coerceAtLeast(1)
        val scale = minOf(1.0, kotlin.math.sqrt(4_000_000.0 / (w.toDouble() * h)))
        val bitmap = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale.toFloat(), scale.toFloat())
        canvas.translate(-dp(rect.minX), -dp(rect.minY))
        list.content.draw(canvas)
        val heights = drag.units.map { frames[it.last()].box.maxY - frames[it.first()].box.minY + StemCellsMetrics.rowGap }
        carryIds = rows.map { it.id }
        heldIds = drag.held.map { it.id }.toSet()
        list.setContentOffset(list.contentOffset) // stop any glide before lifting
        list.isScrollEnabled = false
        // FrameContainer places its children with setFrameOrHide, which restores visibility.
        // Alpha keeps the original block hidden through every scroll/layout pass.
        heldIds.forEach { views[it]?.alpha = 0f }
        return CarryPicture(bitmap, rect, heights)
    }

    fun heldFrame(drag: StemCellsDrag): Rect? = drag.held.mapNotNull { views[it.id]?.frames?.box }.reduceOrNull(Rect::union)

    fun arrangeReorder(drag: StemCellsDrag, to: Int) {
        val order = drag.order(to)
        arranged = order.map { it.id }
        place(px(width), layoutRows(order), order, animated = true)
    }

    /** Keeps a dropped order until its document arrives (at most two seconds). */
    fun endReorder(dropped: Boolean) {
        carryIds = null
        list.isScrollEnabled = true
        removeCallbacks(settleOrder)
        if (dropped) postDelayed(settleOrder, 2000) else {
            arranged = null
            place(px(width), layoutRows(rows), rows, animated = true)
        }
    }

    fun revealReorder() {
        heldIds.forEach { views[it]?.alpha = 1f }
        heldIds = emptySet()
        if (arranged == rows.map { it.id }) { removeCallbacks(settleOrder); arranged = null; laidOut = null; requestLayout() }
    }
    /** Where the last finger went down on a row, in the row's own dp. */
    private var downX = 0.0
    private var editing: String? = null
    val isEditing: Boolean get() = editing != null
    var onEditingChanged: (() -> Unit)? = null

    fun beginRename(rowId: String, text: String, commit: (String) -> Unit) {
        finishRename()
        val view = views[rowId] ?: return
        editing = rowId
        onEditingChanged?.invoke()
        view.beginEditing(text) { value ->
            if (editing != rowId) return@beginEditing
            editing = null
            onEditingChanged?.invoke()
            val title = value?.trim()?.takeIf { it.isNotEmpty() && it != text } ?: return@beginEditing
            view.holdLabel(title)
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            commit(title)
        }
        post { revealRename() }
    }

    fun finishRename(keep: Boolean = true) {
        val wasEditing = editing != null
        editing?.let { views[it]?.finishEditing(keep) }
        editing = null
        if (wasEditing) onEditingChanged?.invoke()
    }

    /** Native keyboard insets can change after the field took focus. Keep it above the IME. */
    fun revealRename() {
        val frame = editing?.let { views[it]?.frames?.box } ?: return
        val top = list.contentOffset + list.insetTop
        val bottom = list.contentOffset + px(list.height) - list.insetBottom
        if (frame.maxY + 24 > bottom) list.setContentOffset(frame.maxY + 24 - px(list.height) + list.insetBottom)
        else if (frame.minY - 24 < top) list.setContentOffset(frame.minY - 24 - list.insetTop)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val field = editing?.let { views[it]?.findViewWithTag<View>("stem-rename") }
            if (field != null) {
                val at = IntArray(2).also(field::getLocationOnScreen)
                if (event.rawX < at[0] || event.rawX > at[0] + field.width || event.rawY < at[1] || event.rawY > at[1] + field.height) finishRename()
            }
        }
        return super.dispatchTouchEvent(event)
    }

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
        if (carryIds != null && carryIds != rows.map { it.id }) post { onReorderInvalidated?.invoke() }
        if (carryIds == null && heldIds.isEmpty() && arranged == rows.map { it.id }) {
            removeCallbacks(settleOrder)
            arranged = null
            laidOut = null
        }
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
            if (editing == id) finishRename(keep = false)
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
        view.setOnLongClickListener {
            val row = view.row
            if (editing != null || row == null) false else onHold?.invoke(row, view) == true
        }
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
            return drawingRows.zip(frames).filter { (_, frame) -> frame.box.maxY > top && frame.box.minY < bottom }.map { it.first }
        }

    fun view(rowId: String): StemCellsRowView? = views[rowId]

    private fun layoutRows(rows: List<StemCellsRow>) = layoutRows(rows, onAdd != null)

    override fun layoutSubviews(width: Double, height: Double) {
        val drawn = drawingRows
        val input = layoutRows(drawn)
        if (laidOut?.first != width || laidOut?.second != input || layoutResult?.frames?.size != drawn.size) place(width, input, drawn)
        // After the rows are placed: the list lays its content out at their height in this pass.
        list.setFrame(Rect(0.0, 0.0, width, height))
    }

    /** Lays the rows out and moves each to its place. */
    private fun place(width: Double, input: List<StemCellsSidebarLayout.Row>, drawn: List<StemCellsRow> = rows, animated: Boolean = false) {
        val result = StemCellsSidebarLayout.layout(
            input, width,
            top = padding?.first ?: StemCellsMetrics.contentTop,
            bottom = padding?.second ?: StemCellsMetrics.contentBottom,
            scale = density.toDouble(),
        )
        layoutResult = result
        laidOut = width to input
        for ((row, frame) in drawn.zip(result.frames)) {
            val view = views[row.id] ?: continue
            val oldTop = view.top + view.translationY
            view.animate().cancel()
            view.frames = frame
            list.content.frames[view] = frame.box
            view.setFrame(frame.box)
            if (animated && row.id !in heldIds) {
                view.translationY = oldTop - view.top
                view.animate().translationY(0f).setDuration(160).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            } else view.translationY = 0f
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
