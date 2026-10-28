package com.slopus.happy.ui.diff

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.slopus.happy.R
import com.slopus.happy.chat.markdown.SyntaxToken
import com.slopus.happy.chat.markdown.SyntaxTokenType
import com.slopus.happy.chat.markdown.tokenizeCode
import com.slopus.happy.diff.syntax.DiffSyntax
import com.slopus.happy.files.FileViewer
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.DiffPalette
import com.slopus.happy.layout.FileLinesLayout
import com.slopus.happy.layout.FileViewerTextStyles
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.ui.chat.ChatSyntax
import com.slopus.happy.ui.chat.FaceSpan
import com.slopus.happy.ui.chat.TokenColorSpan
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The file viewer's content measured for one width and text size (happy-ios
 * `FileLinesPlan`): each line's text, colours and wrapped height. Built away
 * from the main thread.
 *
 * [env] is the text size the screen draws at: the mono 14 on its 20 line
 * grow with it, the paddings and the diff's bar keep their size.
 */
class FileLinesPlan(val source: Source, val width: Double, val maxWidth: Double, val env: TextLayouts.Env) {
    sealed interface Source {
        /** DiffDisplay over `git diff` output. */
        data class Diff(val diff: String) : Source
        /** SimpleSyntaxHighlighter over the file. */
        data class Code(val content: String, val language: String?) : Source
    }

    /** One line: its text (colours and faces as spans), the face it is laid in, and the diff's fills. */
    class Row(val text: CharSequence, val style: TextStyle, val background: ColorToken?, val bar: ColorToken?)

    val rows: List<Row>
    val layout: FileLinesLayout

    init {
        val insets: FileLinesLayout.RowInsets
        when (val source = source) {
            is Source.Diff -> {
                insets = FileLinesLayout.RowInsets.diff
                rows = FileViewer.diffLines(source.diff).map { diffRow(it.text, it.kind) }
            }
            is Source.Code -> {
                insets = FileLinesLayout.RowInsets.code
                val tokens = if (source.language != null && withinLimits(source.content)) {
                    runCatching { tokenizeCode(source.content, source.language) }.getOrNull()
                } else {
                    null
                } ?: listOf(SyntaxToken(source.content, SyntaxTokenType.Default))
                val boldPx = (FileViewerTextStyles.codeBold.scaled(env.scale).size * env.density).toFloat()
                rows = FileViewer.codeLines(tokens).map { Row(codeText(it, boldPx), FileViewerTextStyles.code, null, null) }
            }
        }
        val plain = FileLinesLayout.textWidth(width, maxWidth, insets, bar = false)
        val barred = FileLinesLayout.textWidth(width, maxWidth, insets, bar = true)
        val line = TextLayouts.lineHeight(FileViewerTextStyles.code, env)
        val advance = advance(env)
        val heights = rows.map { row -> height(row, if (row.bar == null) plain else barred, line, advance) }
        layout = FileLinesLayout(width, maxWidth, insets, heights, rows.map { it.bar != null })
    }

    /** The text of row [index] laid out at its width, for drawing. */
    fun textLayout(index: Int): StaticLayout {
        val row = rows[index]
        return TextLayouts.build(row.text, row.style, layout.text(index).width, env, Theme.color(Palette.text))
    }

    private fun height(row: Row, width: Double, line: Double, advance: Double?): Double {
        fun box(measured: Double) = FileLinesLayout.textHeight(measured, line, env.density.toDouble())
        if (row.text.isEmpty()) return box(0.0)
        if (advance != null && FileLinesLayout.fitsOneLine(row.text.toString(), advance, width)) return box(0.0)
        val built = TextLayouts.build(row.text, row.style, width, env, 0)
        return box(built.height / env.density.toDouble())
    }

    private companion object {
        /** DiffDisplay's colours by line. */
        fun diffRow(text: String, kind: FileViewer.DiffLineKind): Row {
            val (color, background, bar) = when (kind) {
                FileViewer.DiffLineKind.Added -> Triple(DiffPalette.addedText, DiffPalette.addedBackground, DiffPalette.addedBorder)
                FileViewer.DiffLineKind.Removed -> Triple(DiffPalette.removedText, DiffPalette.removedBackground, DiffPalette.removedBorder)
                FileViewer.DiffLineKind.Hunk -> Triple(DiffPalette.hunkText, DiffPalette.hunkBackground, null)
                FileViewer.DiffLineKind.FileHeader -> Triple(Palette.text, null, null)
                FileViewer.DiffLineKind.Context -> Triple(DiffPalette.contextText, null, null)
            }
            val string = SpannableStringBuilder(text)
            string.setSpan(TokenColorSpan(color), 0, string.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            return Row(string, if (kind.isBold) FileViewerTextStyles.codeBold else FileViewerTextStyles.code, background, bar)
        }

        fun codeText(tokens: List<SyntaxToken>, boldPx: Float): CharSequence {
            val builder = SpannableStringBuilder()
            for (token in tokens) {
                val start = builder.length
                builder.append(token.text)
                builder.setSpan(TokenColorSpan(ChatSyntax.color(token.type, token.nestLevel)), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (token.type.emphasized) builder.setSpan(FaceSpan(com.slopus.happy.layout.FontFace.MonoSemiBold, boldPx), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return builder
        }

        /** `DiffSyntax.withinLimits` for a whole file: past them it stays plain. */
        fun withinLimits(content: String): Boolean {
            var lines = 1
            var length = 0
            var characters = 0
            for (c in content) {
                characters++
                if (c == '\n') {
                    if (++lines > DiffSyntax.MAX_LINES) return false
                    length = 0
                } else if (++length > DiffSyntax.MAX_LINE) {
                    return false
                }
            }
            return characters + 1 <= DiffSyntax.MAX_CHARACTERS
        }

        val advances = ConcurrentHashMap<Pair<Double, Float>, Double>()
        const val NONE = -1.0

        /**
         * The mono advance at a size, when the regular and semibold faces
         * share it; lines of plain ASCII that clearly fit then skip
         * measuring. Measured once per size.
         */
        fun advance(env: TextLayouts.Env): Double? {
            val size = FileViewerTextStyles.code.scaled(env.scale).size
            val value = advances.getOrPut(size to env.density) {
                val sample = "M".repeat(50)
                val regular = TextLayouts.paint(FileViewerTextStyles.code, env, 0).measureText(sample) / 50.0 / env.density
                val bold = TextLayouts.paint(FileViewerTextStyles.codeBold, env, 0).measureText(sample) / 50.0 / env.density
                if (regular > 0 && abs(regular - bold) < 0.001) regular else NONE
            }
            return value.takeIf { it != NONE }
        }
    }
}

/**
 * file.tsx's ScrollView (happy-ios `FileLinesView`): the plan's rows in a
 * list that makes views only near the visible part, or one message in
 * their place. With [copyText] set, a long press on the text offers Copy
 * for all of it, as a selectable Text does.
 */
class FileLinesView(context: Context) : ManualLayout(context), ThemeAware {
    var plan: FileLinesPlan? = null
        private set
    /** What Copy puts on the clipboard; null leaves the text unselectable. */
    var copyText: String? = null

    /** Room below the last row: the navigation bar. */
    var bottomInset = 0.0
        set(value) {
            if (field == value) return
            field = value
            applyPadding()
        }

    private val recycler = RecyclerView(context)
    private val manager = LinearLayoutManager(context)
    private val adapter = Adapter()
    private val message = Label(context, FileViewerTextStyles.message, Palette.textSecondary)
    private var maxWidth = Double.MAX_VALUE
    /** Where the last long press landed, in the list. */
    private var pressX = 0f
    private var pressY = 0f

    init {
        recycler.layoutManager = manager
        recycler.itemAnimator = null
        recycler.clipToPadding = false
        recycler.adapter = adapter
        recycler.overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        // Rows are made this far past either edge, so a fling meets drawn text.
        manager.isItemPrefetchEnabled = true
        recycler.setItemViewCacheSize(8)
        val presses = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) = longPressed(e)
        })
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                presses.onTouchEvent(e)
                return false
            }
        })
        message.visibility = GONE
        addView(recycler)
        addView(message)
        applyPadding()
    }

    override fun applyTheme() {
        recycler.invalidate()
        for (i in 0 until recycler.childCount) recycler.getChildAt(i).invalidate()
    }

    private fun applyPadding() {
        val padding = FileLinesLayout.Metrics.padding
        recycler.setPadding(0, (padding * Theme.density).roundToInt(), 0, ((padding + bottomInset) * Theme.density).roundToInt())
    }

    override fun layoutSubviews(width: Double, height: Double) {
        recycler.setFrame(Rect(0.0, 0.0, width, height))
        if (message.visibility == VISIBLE) {
            message.setFrame(FileLinesLayout.message(width, maxWidth, message.measurable).first)
        }
    }

    /** Shows a plan's rows; the same plan keeps the views it has. */
    fun show(plan: FileLinesPlan) {
        message.visibility = GONE
        recycler.visibility = VISIBLE
        if (plan === this.plan) return
        this.plan = plan
        adapter.notifyDataSetChanged()
    }

    /** Shows "File is empty" / "No changes to display" instead of rows. */
    fun showMessage(text: String, maxWidth: Double) {
        clearRows()
        this.maxWidth = maxWidth
        message.text = text
        message.visibility = VISIBLE
        requestLayout()
    }

    /** Nothing at all: the diff was asked for and there is none. */
    fun clear() {
        clearRows()
        message.visibility = GONE
    }

    private fun clearRows() {
        plan = null
        adapter.notifyDataSetChanged()
        recycler.visibility = GONE
    }

    /** The content's offset at the view's top, in dp, as a scroll view's contentOffset. */
    var contentOffset: Double
        get() {
            val plan = plan ?: return 0.0
            val first = recycler.getChildAt(0) ?: return 0.0
            val index = recycler.getChildAdapterPosition(first).takeIf { it >= 0 } ?: return 0.0
            return plan.layout.row(index).minY - px(first.top)
        }
        set(value) {
            val plan = plan ?: return
            val layout = plan.layout
            if (layout.count == 0) return
            val y = max(0.0, value)
            val index = if (y < FileLinesLayout.Metrics.padding) 0 else layout.rows(y, y + 1).firstOrNull() ?: (layout.count - 1)
            val top = layout.row(index).minY - y
            manager.scrollToPositionWithOffset(index, ((top - FileLinesLayout.Metrics.padding) * Theme.density).roundToInt())
        }

    /** The scrolling's furthest offset, as a scroll view's `contentSize - bounds`. */
    val maxOffset: Double
        get() {
            val plan = plan ?: return 0.0
            return max(0.0, plan.layout.contentSize.height + bottomInset - px(height))
        }

    // Copy

    private fun longPressed(e: MotionEvent) {
        val text = copyText ?: return
        val plan = plan ?: return
        if (!plan.layout.block.contains(px(e.x), px(e.y) + contentOffset)) return
        pressX = e.x
        pressY = e.y
        recycler.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val anchor = View(context)
        addView(anchor)
        anchor.setFrame(Rect(px(pressX), px(pressY), 1.0, 1.0))
        val popup = PopupMenu(context, anchor, Gravity.START)
        popup.menu.add(context.getString(R.string.common_copy))
        popup.setOnMenuItemClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, text))
            true
        }
        popup.setOnDismissListener { removeView(anchor) }
        popup.show()
    }

    // Rows

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = plan?.rows?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(FileLineRowView(parent.context)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val plan = plan ?: return
            (holder.itemView as FileLineRowView).apply(plan, position)
        }
    }
}

/** One line: its background, the diff bar, and the wrapped text. */
private class FileLineRowView(context: Context) : View(context) {
    private var plan: FileLinesPlan? = null
    private var index = 0
    private var text: StaticLayout? = null
    private val fill = Paint()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun apply(plan: FileLinesPlan, index: Int) {
        this.plan = plan
        this.index = index
        text = null
        val layout = plan.layout
        // Each row's edges sit on the pixels the layout's offsets round to, so the rows tile without drift.
        val d = Theme.density
        val frame = layout.row(index)
        val height = (frame.maxY * d).roundToInt() - (frame.minY * d).roundToInt()
        layoutParams = (layoutParams as? RecyclerView.LayoutParams ?: RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)).also { it.height = height }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val plan = plan ?: return
        val layout = plan.layout
        val row = plan.rows[index]
        val d = Theme.density
        val frame = layout.row(index)
        val left = (frame.minX * d).roundToInt().toFloat()
        val right = (frame.maxX * d).roundToInt().toFloat()
        row.background?.let {
            fill.color = Theme.color(it)
            canvas.drawRect(left, 0f, right, height.toFloat(), fill)
        }
        val bar = layout.bar(index)
        if (bar != null && row.bar != null) {
            fill.color = Theme.color(row.bar)
            canvas.drawRect(left, 0f, left + (bar.width * d).toFloat(), height.toFloat(), fill)
        }
        val textFrame = layout.text(index)
        val built = text ?: plan.textLayout(index).also { text = it }
        canvas.save()
        canvas.translate(left + (textFrame.minX * d).toFloat(), (textFrame.minY * d).toFloat())
        built.draw(canvas)
        canvas.restore()
    }
}
