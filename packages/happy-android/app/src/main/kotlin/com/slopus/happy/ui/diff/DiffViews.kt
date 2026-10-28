package com.slopus.happy.ui.diff

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import com.slopus.happy.diff.DiffLineType
import com.slopus.happy.layout.DiffPalette
import com.slopus.happy.layout.DiffTextStyles
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame
import kotlin.math.roundToInt

/**
 * components/diff/DiffChunk.tsx: what an agent's edit looks like in the chat
 * (happy-ios `DiffChunkView`). Apply a plan; on "show more", the owner plans
 * again with that file opened and measures the row again.
 */
class DiffChunkView(context: Context) : ViewGroup(context), ThemeAware {
    /** "Show N more lines" on file `index`. */
    var onShowMore: ((Int) -> Unit)? = null

    var plan: DiffChunkPlan? = null
        private set
    private val fileViews = ArrayList<DiffFileView>()

    fun apply(plan: DiffChunkPlan) {
        if (plan === this.plan) return
        this.plan = plan
        while (fileViews.size < plan.files.size) {
            val index = fileViews.size
            val view = DiffFileView(context).apply { onShowMore = { this@DiffChunkView.onShowMore?.invoke(index) } }
            addView(view)
            fileViews += view
        }
        while (fileViews.size > plan.files.size) removeView(fileViews.removeAt(fileViews.lastIndex))
        fileViews.zip(plan.files).forEach { (view, file) -> view.apply(file) }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(plan?.size?.height ?: 0.0).roundToInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val plan = plan ?: return
        fileViews.zip(plan.layout.frames).forEach { (view, frame) -> view.setFrame(frame) }
    }

    override fun applyTheme() = Theme.applyTree(this)
}

/**
 * components/diff/DiffFileView.tsx's scroll layout: code under a horizontal
 * scroll, line numbers and markers in a gutter pinned over its left edge,
 * then "Show N more lines" when rows are held back.
 */
class DiffFileView(context: Context) : ViewGroup(context) {
    var onShowMore: (() -> Unit)? = null

    private var plan: DiffFilePlan? = null
    private val code = DiffCodeView(context)
    private val scroll = object : HorizontalScrollView(context) {
        // Only a diff wider than its room takes the touch, so a tap on one
        // that fits reaches the row.
        override fun onTouchEvent(ev: MotionEvent): Boolean = scrollable && super.onTouchEvent(ev)
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = scrollable && super.onInterceptTouchEvent(ev)
    }.apply {
        isHorizontalScrollBarEnabled = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        isFillViewport = false
        addView(code)
    }
    private var scrollable = false
    private val gutter = DiffGutterView(context)
    private val showMore = DiffShowMoreBar(context).apply { setOnClickListener { onShowMore?.invoke() } }

    init {
        addView(scroll)
        addView(gutter)
        addView(showMore)
        setWillNotDraw(false)
    }

    fun apply(plan: DiffFilePlan) {
        if (plan === this.plan) return
        val keepOffset = this.plan?.file?.path == plan.file.path
        this.plan = plan
        code.plan = plan
        gutter.plan = plan
        showMore.plan = plan
        if (!keepOffset) scroll.scrollTo(0, 0)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(plan?.height ?: 0.0).roundToInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val plan = plan ?: return
        val layout = plan.layout
        scrollable = layout.scrollable
        scroll.setFrame(layout.scroll)
        code.setFrame(Rect(0.0, 0.0, layout.scrollContentSize.width, layout.scrollContentSize.height))
        gutter.setFrame(layout.gutter)
        val bar = layout.showMore
        if (bar != null) {
            showMore.visibility = VISIBLE
            showMore.setFrame(bar)
        } else {
            showMore.visibility = GONE
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Theme.color(DiffSurface))
    }
}

/** Paints shared by a diff's views: the mono styles at a plan's metrics, coloured at draw time. */
private class DiffPaints {
    private var key: Any? = null
    lateinit var code: TextPaint
    lateinit var small: TextPaint
    lateinit var message: TextPaint
    val fill = Paint()

    fun update(plan: DiffFilePlan) {
        val next = plan.metrics to plan.env
        if (next == key) return
        key = next
        code = TextLayouts.paint(DiffStyle.code(plan.metrics), plan.env, 0)
        small = TextLayouts.paint(DiffStyle.small(plan.metrics), plan.env, 0)
        message = TextLayouts.paint(DiffStyle.message(plan.metrics), plan.env, 0)
    }

    /**
     * The baseline in a row of [style]'s fixed line, as happy-ios places it
     * (TextLayouts' exact line): the slack above the glyphs, a quarter of it
     * moved under them.
     */
    fun baseline(paint: TextPaint, style: TextStyle, plan: DiffFilePlan): Float {
        val line = (style.scaled(plan.env.scale).lineHeight ?: 0.0) * plan.env.density
        val fm = paint.fontMetricsInt
        val slack = line.roundToInt() - (fm.descent - fm.ascent)
        return (line.roundToInt() - fm.descent - slack / 4).toFloat()
    }
}

/** The scrolling half: each row's background across the canvas, and its text. */
private class DiffCodeView(context: Context) : View(context) {
    var plan: DiffFilePlan? = null
        set(value) {
            field = value
            invalidate()
        }
    private val paints = DiffPaints()

    override fun onDraw(canvas: Canvas) {
        val plan = plan ?: return
        paints.update(plan)
        val layout = plan.layout
        val d = plan.env.density
        val originX = dp(layout.canvas.minX)
        val originY = dp(layout.canvas.minY)
        val rowHeight = dp(layout.lineHeight)
        val width = dp(layout.contentWidth)
        val codeBaseline = paints.baseline(paints.code, DiffStyle.code(plan.metrics), plan)
        val smallBaseline = paints.baseline(paints.small, DiffStyle.small(plan.metrics), plan)
        val messageBaseline = paints.baseline(paints.message, DiffStyle.message(plan.metrics), plan)
        val textX = originX + (com.slopus.happy.layout.diff.DiffFileLayout.Metrics.codePaddingLeft * d).toFloat()
        plan.rows.forEachIndexed { index, row ->
            val top = originY + index * rowHeight
            when (row) {
                is DiffRowDraw.Line -> {
                    if (row.type != DiffLineType.Ctx) {
                        paints.fill.color = Theme.color(DiffStyle.rowBackground(row.type))
                        canvas.drawRect(originX, top, originX + width, top + rowHeight, paints.fill)
                    }
                    for (run in row.runs) {
                        val background = run.background ?: continue
                        paints.fill.color = Theme.color(background)
                        canvas.drawRect(textX + run.x, top, textX + run.x + run.width, top + rowHeight, paints.fill)
                    }
                    for (run in row.runs) {
                        paints.code.color = Theme.color(run.color)
                        canvas.drawText(row.text, run.start, run.end, textX + run.x, top + codeBaseline, paints.code)
                    }
                }
                is DiffRowDraw.Hunk -> {
                    paints.fill.color = Theme.color(DiffPalette.hunkBackground)
                    canvas.drawRect(originX, top, originX + width, top + rowHeight, paints.fill)
                    paints.small.color = Theme.color(DiffPalette.hunkText)
                    val head = TextUtils.ellipsize(row.head, paints.small, width, TextUtils.TruncateAt.END)
                    canvas.drawText(head, 0, head.length, originX, top + smallBaseline, paints.small)
                    if (row.section.isNotEmpty()) {
                        val x = originX + paints.small.measureText(head, 0, head.length)
                        paints.small.color = Theme.color(DiffPalette.sectionText)
                        val section = TextUtils.ellipsize("   ${row.section}", paints.small, maxOf(0f, originX + width - x), TextUtils.TruncateAt.END)
                        canvas.drawText(section, 0, section.length, x, top + smallBaseline, paints.small)
                    }
                }
                is DiffRowDraw.Message -> {
                    paints.message.color = Theme.color(Palette.textSecondary)
                    val text = TextUtils.ellipsize(row.text, paints.message, width, TextUtils.TruncateAt.END)
                    canvas.drawText(text, 0, text.length, originX, top + messageBaseline, paints.message)
                }
            }
        }
    }
}

/** The pinned half: each line's number, right-aligned, and its marker centred after it; or a hunk's dots. */
private class DiffGutterView(context: Context) : View(context) {
    var plan: DiffFilePlan? = null
        set(value) {
            field = value
            invalidate()
        }
    private val paints = DiffPaints()

    override fun onDraw(canvas: Canvas) {
        val plan = plan ?: return
        paints.update(plan)
        val layout = plan.layout
        canvas.drawColor(Theme.color(DiffSurface))
        val rowHeight = dp(layout.lineHeight)
        val gutterWidth = dp(layout.gutterWidth)
        val number = layout.lineNumber
        val marker = layout.marker
        val codeBaseline = paints.baseline(paints.code, DiffStyle.code(plan.metrics), plan)
        val smallBaseline = paints.baseline(paints.small, DiffStyle.small(plan.metrics), plan)
        plan.rows.forEachIndexed { index, row ->
            val top = index * rowHeight
            when (row) {
                is DiffRowDraw.Line -> {
                    if (row.type != DiffLineType.Ctx) {
                        paints.fill.color = Theme.color(DiffStyle.gutterBackground(row.type))
                        canvas.drawRect(0f, top, gutterWidth, top + rowHeight, paints.fill)
                    }
                    if (number != null && row.number != null) {
                        paints.small.color = Theme.color(DiffPalette.lineNumber)
                        val w = paints.small.measureText(row.number)
                        canvas.drawText(row.number, dp(number.maxX) - w, top + smallBaseline, paints.small)
                    }
                    if (row.marker.isNotEmpty()) {
                        paints.code.color = Theme.color(DiffStyle.marker(row.type))
                        val w = paints.code.measureText(row.marker)
                        canvas.drawText(row.marker, dp(marker.midX) - w / 2, top + codeBaseline, paints.code)
                    }
                }
                is DiffRowDraw.Hunk -> {
                    paints.fill.color = Theme.color(DiffPalette.hunkBackground)
                    canvas.drawRect(0f, top, gutterWidth, top + rowHeight, paints.fill)
                    // The dots at the font's own line, centred in the row.
                    paints.small.color = Theme.color(DiffPalette.hunkText)
                    val fm = paints.small.fontMetrics
                    val baseline = top + rowHeight / 2 - (fm.ascent + fm.descent) / 2
                    canvas.drawText("···", dp(layout.hunkDots.minX), baseline, paints.small)
                }
                is DiffRowDraw.Message -> Unit
            }
        }
    }
}

/** DiffFileView.tsx's "Show N more lines": a 1dp divider over a `hunkBg` bar, semibold 13 centred. */
private class DiffShowMoreBar(context: Context) : View(context) {
    var plan: DiffFilePlan? = null
        set(value) {
            field = value
            contentDescription = value?.showMoreText
            invalidate()
        }
    private val fill = Paint()
    private var paint: TextPaint? = null
    private var paintKey: Any? = null

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        val plan = plan ?: return
        if (paintKey != plan.env) {
            paintKey = plan.env
            paint = TextLayouts.paint(DiffTextStyles.showMore, plan.env, 0)
        }
        val paint = paint ?: return
        canvas.drawColor(Theme.color(DiffPalette.hunkBackground))
        fill.color = Theme.color(Palette.divider)
        canvas.drawRect(0f, 0f, width.toFloat(), dp(com.slopus.happy.layout.diff.DiffFileLayout.Metrics.showMoreBorder), fill)
        val textWidth = paint.measureText(plan.showMoreText) / plan.env.density
        val frame = plan.layout.showMoreText(textWidth.toDouble()) ?: return
        paint.color = Theme.color(DiffPalette.hunkText)
        val fm = paint.fontMetrics
        val baseline = dp(frame.midY) - (fm.ascent + fm.descent) / 2
        val text = TextUtils.ellipsize(plan.showMoreText, paint, dp(frame.width) + 1, TextUtils.TruncateAt.END)
        canvas.drawText(text, 0, text.length, dp(frame.minX), baseline, paint)
        if (isPressed) {
            fill.color = Theme.color(Palette.text)
            fill.alpha = 20
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}
