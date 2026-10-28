package com.slopus.happy.ui.details

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect as AndroidRect
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.StaticLayout
import android.view.View
import android.view.ViewTreeObserver
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.CenteredStackLayout
import com.slopus.happy.layout.CodeBlockLayout
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.HorizontalStackLayout
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.SecondaryPalette
import com.slopus.happy.layout.SecondaryTextStyles
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.chat.TextChunks
import com.slopus.happy.model.details.GitPresentation
import com.slopus.happy.model.details.LineChanges
import com.slopus.happy.model.details.SessionInfoText
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** components/GitLineChanges.tsx: `≈`, `+N` and `-M`, 4 apart. */
class DetailLineChangesView(context: Context, changes: LineChanges?) : ManualLayout(context) {
    var changes: LineChanges? = changes
        set(value) { if (field != value) { field = value; apply() } }

    private val approximate = Label(context, SecondaryTextStyles.lineChangesApproximate, Palette.textSecondary, maxLines = 1).apply { text = "≈" }
    private val added = Label(context, SecondaryTextStyles.lineChanges, SecondaryPalette.gitAdded, maxLines = 1)
    private val removed = Label(context, SecondaryTextStyles.lineChanges, SecondaryPalette.gitRemoved, maxLines = 1)

    init {
        listOf(approximate, added, removed).forEach(::addView)
        apply()
    }

    private val parts: List<Label>
        get() {
            val changes = changes ?: return emptyList()
            return buildList {
                if (changes.approximate) add(approximate)
                if (changes.insertions > 0) add(added)
                if (changes.deletions > 0) add(removed)
            }
        }

    private fun layout() = HorizontalStackLayout(parts.map { it.measure(Double.MAX_VALUE) }, 4.0)

    override fun sizeThatFits(width: Double, height: Double) = layout().size

    override fun layoutSubviews(width: Double, height: Double) {
        parts.zip(layout().items).forEach { (label, frame) -> label.setFrame(frame) }
    }

    private fun apply() {
        added.text = "+" + SessionInfoText.compactCount(changes?.insertions ?: 0)
        removed.text = "-" + SessionInfoText.compactCount(changes?.deletions ?: 0)
        val shown = parts.toSet()
        listOf(approximate, added, removed).forEach { it.visibility = if (it in shown) VISIBLE else GONE }
        requestLayout()
    }
}

/** info.tsx's right element on the Changes row: "N changed files", the line counts and its own chevron, 6 apart. */
class DetailChangesAccessoryView(context: Context, presentation: GitPresentation) : ManualLayout(context) {
    private val count = Label(context, SecondaryTextStyles.changedFiles, Palette.textSecondary, maxLines = 1)
    private val lines = DetailLineChangesView(context, presentation.changes)
    private val chevron = IconView(context, Icon.ionicons("chevron-forward"), 17.0, Palette.groupedChevron)
    private val parts: List<View>

    init {
        isClickable = false
        presentation.changedFileCount?.let { count.text = context.resources.getQuantityString(R.plurals.files_changed_files, it.toInt(), it.toInt()) }
        parts = buildList {
            if (presentation.changedFileCount != null) add(count)
            if (presentation.changes != null) add(lines)
            add(chevron)
        }
        parts.forEach(::addView)
        contentDescription = count.text.takeIf { presentation.changedFileCount != null }
    }

    private fun size(view: View): Size = when (view) {
        is Label -> view.measure(Double.MAX_VALUE)
        is IconView -> view.intrinsicSize()
        is ManualLayout -> view.sizeThatFits(Double.MAX_VALUE, Double.MAX_VALUE)
        else -> Size.Zero
    }

    private fun layout() = HorizontalStackLayout(parts.map(::size), 6.0)

    override fun sizeThatFits(width: Double, height: Double) = layout().size

    override fun layoutSubviews(width: Double, height: Double) {
        parts.zip(layout().items).forEach { (view, frame) -> view.setFrame(frame) }
    }
}

/**
 * A screen's centred message on the grouped background: an optional icon
 * (or spinner) over lines of text ([CenteredStackLayout]).
 */
class DetailMessageView(
    context: Context,
    private val lead: View?,
    private val lines: List<Line>,
    private val padding: Double = 0.0,
    /** The lead's box when it isn't an icon (a spinner). */
    private val leadSize: Size = Size.Zero,
) : ManualLayout(context), ThemeAware {
    class Line(
        val text: String,
        val style: TextStyle,
        val color: ColorToken,
        val spacingBefore: Double = 0.0,
        val horizontalPadding: Double = 0.0,
        val centered: Boolean = false,
    )

    private val labels = lines.map { line ->
        Label(context, line.style, line.color, alignment = if (line.centered) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL).apply { text = line.text }
    }

    init {
        lead?.let(::addView)
        labels.forEach(::addView)
        applyTheme()
    }

    override fun applyTheme() = setBackgroundColor(Theme.color(Palette.groupedBackground))

    override fun layoutSubviews(width: Double, height: Double) {
        val items = buildList {
            lead?.let { add(CenteredStackLayout.Item(Measurable.fixed((it as? IconView)?.intrinsicSize() ?: leadSize))) }
            lines.zip(labels).forEach { (line, label) -> add(CenteredStackLayout.Item(label.measurable, line.spacingBefore, line.horizontalPadding)) }
        }
        val views: List<View> = listOfNotNull(lead) + labels
        views.zip(CenteredStackLayout(CenteredStackLayout.Input(Size(width, height), padding, items)).items).forEach { (view, frame) -> view.setFrame(frame) }
    }
}

/**
 * ItemGroup.tsx gives every row a divider but the child in the group's last
 * slot. When that slot is a conditional that rendered nothing, React still
 * counts it, so the last visible row keeps its divider.
 */
fun ItemGroupView.keepsLastDivider(keeps: Boolean) {
    if (keeps) rows.forEach { (it as? ItemRowView)?.showsDivider = true }
}

/**
 * components/CodeView.tsx as info.tsx places it in a group: Menlo 12/18 on
 * `surfaceHigh`, radius 6, padding 12, inset 16 with 12 below.
 *
 * A raw JSON block runs to megabytes in a long session, so the code is cut
 * into pieces ([TextChunks]) laid out off the main thread, and only the
 * pieces within a screen's height of what the window shows are drawn. Until
 * the first layout is in, the box is empty.
 */
class CodeBlockView(context: Context, code: String) : View(context), ThemeAware {
    var code: String = code
        set(value) { if (field != value) { field = value; requestPlan() } }

    private class Plan(val width: Double, val key: Any, val texts: List<String>, val pieces: List<StaticLayout>, val layout: CodeBlockLayout)

    private val box = GradientDrawable().apply { cornerRadius = dp(CodeBlockLayout.Metrics.cornerRadius) }
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var plan: Plan? = null
    private var job: Job? = null
    /** The width the list measured it at last. */
    private var measuredAt: Double? = null
    private var drawn = IntRange.EMPTY
    private val visible = AndroidRect()
    private val scrolled = ViewTreeObserver.OnScrollChangedListener { if (wanted() != drawn) invalidate() }

    init {
        applyTheme()
    }

    override fun applyTheme() {
        box.setColor(Theme.color(Palette.surfaceHigh))
        requestPlan()
        invalidate()
    }

    /** The layout at [width]: the plan's, or while one for a new width is on its way, a box as tall as the last. */
    fun layout(width: Double): CodeBlockLayout {
        if (measuredAt != width) {
            measuredAt = width
            requestPlan()
        }
        val plan = plan ?: return CodeBlockLayout(width, emptyList())
        return if (abs(plan.width - width) < 0.5) plan.layout else CodeBlockLayout(width, listOf(plan.layout.text.height))
    }

    private fun requestPlan() {
        val width = measuredAt ?: return
        val code = code
        val env = TextLayouts.Env(Theme.density, Theme.textScale)
        val key = Triple(env, Theme.isDark, CodeBlockLayout.textWidth(width))
        val color = Theme.color(Palette.text)
        val taken = plan
        job?.cancel()
        job = scope.launch {
            val next = withContext(Dispatchers.Default) {
                val textWidth = CodeBlockLayout.textWidth(width)
                // A piece whose text was laid out at the same width and text size keeps its layout.
                val kept = if (taken != null && taken.key == key) taken.texts.zip(taken.pieces).toMap() else emptyMap()
                val chunks = TextChunks.split(code)
                val texts = chunks.mapIndexed { index, chunk -> if (index == chunks.size - 1) chunk else TextChunks.drawn(chunk) }
                val pieces = texts.map { text -> kept[text] ?: TextLayouts.build(text, ChatTextStyles.codeView, textWidth, env, color) }
                Plan(width, key, texts, pieces, CodeBlockLayout(width, pieces.map { TextLayouts.size(it, env).height }))
            }
            plan = next
            requestLayout()
            (parent as? View)?.requestLayout()
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val size = layout(px(w)).size
        setMeasuredDimension(w, if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else dp(size.height).toInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrolled)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(scrolled)
        job?.cancel()
        super.onDetachedFromWindow()
    }

    /** The pieces within a screen's height of the part on screen. */
    private fun wanted(): IntRange {
        val plan = plan ?: return IntRange.EMPTY
        if (!getLocalVisibleRect(visible)) return IntRange.EMPTY
        val margin = px(rootView.height)
        return plan.layout.pieces(px(visible.top) - margin, px(visible.bottom) + margin)
    }

    override fun onDraw(canvas: Canvas) {
        val plan = plan ?: return
        if (abs(plan.width - px(width)) >= 0.5) return
        val layout = plan.layout
        box.setBounds(dp(layout.box.x).toInt(), dp(layout.box.y).toInt(), dp(layout.box.maxX).toInt(), dp(layout.box.maxY).toInt())
        box.draw(canvas)
        drawn = wanted()
        for (index in drawn) {
            val frame = layout.pieces[index]
            canvas.save()
            canvas.translate(dp(frame.x), dp(frame.y))
            plan.pieces[index].draw(canvas)
            canvas.restore()
        }
    }
}
