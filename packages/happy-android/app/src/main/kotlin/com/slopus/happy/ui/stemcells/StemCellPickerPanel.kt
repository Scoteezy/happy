package com.slopus.happy.ui.stemcells

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import com.slopus.happy.R
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.StemCellPickerMetrics
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.TextScale
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.stemcells.StemCellPickerLineLayout
import com.slopus.happy.layout.stemcells.StemCellPickerPanelLayout
import com.slopus.happy.layout.stemcells.StemCellUsageGaugeLayout
import com.slopus.happy.stemcells.StemCellAccountUsage
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.BounceScrollView
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * What one of the composer's model control panels lists (happy-ios
 * `StemCellPickerContent`), as desktop's `ComposerModelControl` draws it: a
 * service's heading over its models, a line with its check before its
 * name, a usage gauge at a line's end, the hairline between blocks, and an
 * account's windows under its list.
 */
data class StemCellPickerContent(
    /** The panel's tag and accessibility name. */
    val id: String,
    val label: String,
    val lines: List<Line>,
    val footer: List<Line> = emptyList(),
    val minWidth: Double,
    val maxWidth: Double,
) {
    data class Gauge(val text: String, val left: Int, val tone: StemCellAccountUsage.Tone)

    /** One of an account's windows (its name, when it starts over, what is left), or a quiet line about how current they are. */
    data class Window(
        val label: String,
        val resets: String? = null,
        val gauge: Gauge? = null,
        val primary: Boolean = false,
        val critical: Boolean = false,
    )

    sealed class Line {
        data class Notice(val text: String, val warning: Boolean) : Line()
        /** A service: its name, its account, its windows' gauges, and the service whose accounts it opens. */
        data class Heading(val service: String, val account: String?, val gauges: List<Gauge>, val opens: String?, val label: String) : Line()
        data class Option(val key: String, val label: String, val checked: Boolean, val disabled: Boolean, val gauge: Gauge?) : Line()
        data object Separator : Line()
        data class Link(val key: String, val label: String) : Line()
        data class Windows(val windows: List<Window>) : Line()
    }

    /** The checked line, which the panel opens on. */
    val selected: Int? get() = lines.indexOfFirst { it is Line.Option && it.checked }.takeIf { it >= 0 }
}

/** The panel's type (happy-ios `StemCellPickerType`), at fixed sizes as iOS's menus, measured where the layout needs it. */
object StemCellPickerType {
    private val M = StemCellPickerMetrics
    val label = StemCellsFonts.ui(StemCellsFonts.Weight.Regular, M.labelSize)
    val service = StemCellsFonts.ui(StemCellsFonts.Weight.SemiBold, M.headingSize)
    val account = StemCellsFonts.ui(StemCellsFonts.Weight.Regular, M.headingSize)
    val link = StemCellsFonts.ui(StemCellsFonts.Weight.Medium, M.linkSize)
    val gauge = StemCellsFonts.ui(StemCellsFonts.Weight.Regular, M.gaugeSize)
    val notice = StemCellsFonts.ui(StemCellsFonts.Weight.Regular, M.linkSize)

    fun window(primary: Boolean) =
        StemCellsFonts.ui(if (primary) StemCellsFonts.Weight.SemiBold else StemCellsFonts.Weight.Regular, if (primary) M.linkSize else M.headingSize)

    val env get() = TextLayouts.Env(Theme.density, TextScale.Standard)

    fun size(text: String, style: TextStyle): Size {
        val size = TextLayouts.measure(text, style, Double.MAX_VALUE, env, 1)
        return Size(ceil(size.width), ceil(size.height))
    }

    fun gaugeSize(gauge: StemCellPickerContent.Gauge): Size = StemCellUsageGaugeLayout(size(gauge.text, this.gauge)).size

    /** The width a line asks of its panel. */
    fun width(line: StemCellPickerContent.Line): Double {
        // A name starts past the check's column, picked or not.
        val sides = 2 * M.inset + 2 * M.padding + M.checkColumn
        return when (line) {
            is StemCellPickerContent.Line.Option -> sides + size(line.label, label).width + (line.gauge?.let { M.checkGap + gaugeSize(it).width } ?: 0.0)
            is StemCellPickerContent.Line.Link -> sides + size(line.label, link).width
            // A heading and the windows keep to the line's leading edge.
            is StemCellPickerContent.Line.Heading -> {
                var width = 2 * M.inset + 2 * M.padding + size(line.service, service).width
                if (line.account != null) width += M.headingGap + min(M.accountMax, size(line.account, account).width)
                if (line.gauges.isNotEmpty() || line.opens != null) width += M.headingGap
                width += line.gauges.sumOf { gaugeSize(it).width } + max(0, line.gauges.size - 1) * M.usageGap
                if (line.opens != null) width += (if (line.gauges.isEmpty()) 0.0 else M.headingGap) + M.chevron
                width
            }
            is StemCellPickerContent.Line.Windows -> 2 * M.inset + 2 * M.padding + (line.windows.maxOfOrNull(::windowWidth) ?: 0.0)
            // A notice wraps.
            is StemCellPickerContent.Line.Notice, StemCellPickerContent.Line.Separator -> 0.0
        }
    }

    /** One window's line: its name, when it starts over, what is left. */
    fun windowWidth(window: StemCellPickerContent.Window): Double {
        var width = size(window.label, window(window.primary)).width
        window.resets?.let { width = max(width, M.detailsLabelMin) + M.detailsGap + size(it, account).width }
        window.gauge?.let { width += M.detailsGap + gaugeSize(it).width }
        return width
    }

    fun item(line: StemCellPickerContent.Line, width: Double): StemCellPickerPanelLayout.Item = when (line) {
        is StemCellPickerContent.Line.Option -> StemCellPickerPanelLayout.Item.row
        is StemCellPickerContent.Line.Link -> StemCellPickerPanelLayout.Item.block(M.row)
        is StemCellPickerContent.Line.Heading -> StemCellPickerPanelLayout.Item.heading
        StemCellPickerContent.Line.Separator -> StemCellPickerPanelLayout.Item.separator
        is StemCellPickerContent.Line.Notice -> {
            val textWidth = max(1.0, width - 2 * M.inset - 2 * M.padding - M.checkColumn)
            StemCellPickerPanelLayout.Item.block(ceil(TextLayouts.measure(line.text, notice, textWidth, env).height) + 2 * M.detailsInsetY)
        }
        is StemCellPickerContent.Line.Windows ->
            StemCellPickerPanelLayout.Item.block(StemCellPickerPanelLayout.detailsHeight(line.windows.size, line.windows.firstOrNull()?.primary == true))
    }

    /** The panel's width: its widest line, between its least and most. */
    fun panelWidth(content: StemCellPickerContent): Double {
        val widest = (content.lines + content.footer).maxOfOrNull(::width) ?: 0.0
        return min(content.maxWidth, max(content.minWidth, widest))
    }
}

/**
 * A usage gauge as desktop's (happy-ios `StemCellUsageGaugeView`): what is
 * left, then a ring that drains as the window is spent, yellow when little
 * is left, red when almost nothing is.
 */
class StemCellUsageGaugeView(context: Context) : ManualLayout(context), ThemeAware {
    private val label = Label(context, StemCellPickerType.gauge, StemCellsPalette.stemCellsPanelSecondary, maxLines = 1).apply { followsTextSize = false }
    private val ring = Ring(context)
    private var gauge: StemCellPickerContent.Gauge? = null
    private var strong = false
    var layout = StemCellUsageGaugeLayout(Size.Zero)
        private set

    init {
        addView(label)
        addView(ring)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** [strong] draws the number in full ink (the week under the accounts). */
    fun configure(gauge: StemCellPickerContent.Gauge, strong: Boolean = false) {
        this.gauge = gauge
        this.strong = strong
        label.text = gauge.text
        layout = StemCellUsageGaugeLayout(StemCellPickerType.size(gauge.text, StemCellPickerType.gauge))
        applyTheme()
        requestLayout()
    }

    override fun applyTheme() {
        val gauge = gauge ?: return
        val critical = gauge.tone == StemCellAccountUsage.Tone.Critical
        label.color = when {
            critical -> StemCellsPalette.stemCellsUsageCritical
            strong -> StemCellsPalette.stemCellsPanelText
            else -> StemCellsPalette.stemCellsPanelSecondary
        }
        ring.arc = Theme.color(
            when (gauge.tone) {
                StemCellAccountUsage.Tone.Ample -> StemCellsPalette.stemCellsUsageArc
                StemCellAccountUsage.Tone.Low -> StemCellsPalette.stemCellsUsageLow
                StemCellAccountUsage.Tone.Critical -> StemCellsPalette.stemCellsUsageCritical
            },
        )
        val track = Theme.color(if (critical) StemCellsPalette.stemCellsUsageCritical else StemCellsPalette.stemCellsUsageTrack)
        ring.track = if (critical) (track and 0x00ffffff) or ((((track ushr 24) * 0.4).toInt()) shl 24) else track
        ring.left = gauge.left / 100f
        ring.invalidate()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        label.setFrame(layout.text)
        ring.setFrame(layout.ring)
    }

    private class Ring(context: Context) : View(context) {
        var track = 0
        var arc = 0
        var left = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val oval = RectF()

        override fun onDraw(canvas: Canvas) {
            val stroke = dp(2.0)
            paint.strokeWidth = stroke
            val inset = dp(1.0)
            oval.set(inset, inset, width - inset, height - inset)
            paint.color = track
            canvas.drawOval(oval, paint)
            if (left <= 0f) return
            paint.color = arc
            canvas.drawArc(oval, -90f, 360f * left, false, paint)
        }
    }
}

/**
 * One line of a panel (happy-ios `StemCellPickerLineView`): a model, effort
 * or account with its check in the column before its name, a service's
 * heading, the benchmarks link, a notice, a hairline, or an account's
 * windows. Names, the link and notices start past the check's column, as a
 * menu's titles; headings and windows keep to its leading edge.
 */
class StemCellPickerLineView(context: Context, val line: StemCellPickerContent.Line) : ManualLayout(context), ThemeAware {
    private val M = StemCellPickerMetrics
    private val highlight = View(context)
    private val highlightShape = GradientDrawable().apply { cornerRadius = dp(StemCellPickerMetrics.highlightRadius) }
    private val title = Label(context, StemCellPickerType.label, StemCellsPalette.stemCellsPanelText, maxLines = 1).apply { followsTextSize = false }
    private val detail = Label(context, StemCellPickerType.account, StemCellsPalette.stemCellsPanelSecondary, maxLines = 1).apply { followsTextSize = false }
    private val check = IconView(context, StemCellsGlyphs.icon("checkmark"), StemCellPickerMetrics.check, StemCellsPalette.stemCellsPanelText)
    private val chevron = IconView(context, StemCellsGlyphs.icon("chevron-right"), StemCellPickerMetrics.chevron, StemCellsPalette.stemCellsPanelSecondary)
    private val gauges = ArrayList<StemCellUsageGaugeView>()

    private class Row(val label: Label, val resets: Label, val gauge: StemCellUsageGaugeView?, val window: StemCellPickerContent.Window)

    private val rows = ArrayList<Row>()

    init {
        highlight.background = highlightShape
        highlight.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(highlight)
        for (label in listOf(title, detail)) {
            label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(label)
        }
        for (icon in listOf(check, chevron)) icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        configure()
        applyTheme()
    }

    private fun configure() {
        val res = context.resources
        isClickable = true
        isFocusable = true
        when (val line = line) {
            is StemCellPickerContent.Line.Option -> {
                title.text = line.label
                if (line.checked) addView(check)
                line.gauge?.let(::addGauge)
                isEnabled = !line.disabled
                alpha = if (line.disabled) 0.48f else 1f
                tag = "stem-picker-row:${line.key}"
                contentDescription = listOfNotNull(line.label, line.gauge?.let { res.getString(R.string.stem_percent_left, it.left) }).joinToString(", ")
                isSelected = line.checked
            }
            is StemCellPickerContent.Line.Heading -> {
                title.style = StemCellPickerType.service
                title.text = line.service
                detail.text = line.account ?: ""
                line.gauges.forEach(::addGauge)
                if (line.opens != null) addView(chevron)
                isEnabled = line.opens != null
                isClickable = line.opens != null
                tag = "stem-picker-heading:${line.opens ?: line.service}"
                contentDescription = line.label
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = line.opens == null
            }
            is StemCellPickerContent.Line.Link -> {
                title.style = StemCellPickerType.link
                title.text = line.label
                tag = "stem-picker-row:${line.key}"
                contentDescription = line.label
            }
            is StemCellPickerContent.Line.Notice -> {
                title.style = StemCellPickerType.notice
                title.text = line.text
                title.maxLines = 0
                isEnabled = false
                isClickable = false
                contentDescription = line.text
            }
            StemCellPickerContent.Line.Separator -> {
                isEnabled = false
                isClickable = false
                isFocusable = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            is StemCellPickerContent.Line.Windows -> {
                isEnabled = false
                isClickable = false
                title.visibility = GONE
                detail.visibility = GONE
                for (window in line.windows) {
                    val label = Label(context, StemCellPickerType.window(window.primary), StemCellsPalette.stemCellsPanelSecondary, maxLines = 1).apply { followsTextSize = false }
                    val resets = Label(context, StemCellPickerType.account, StemCellsPalette.stemCellsPanelSecondary, maxLines = 1).apply { followsTextSize = false }
                    label.text = window.label
                    resets.text = window.resets ?: ""
                    addView(label)
                    addView(resets)
                    val gauge = window.gauge?.let { gauge ->
                        StemCellUsageGaugeView(context).also {
                            it.configure(gauge, strong = window.primary)
                            addView(it)
                        }
                    }
                    rows += Row(label, resets, gauge, window)
                }
                contentDescription = line.windows.joinToString(", ") { window ->
                    listOfNotNull(window.label, window.resets, window.gauge?.let { res.getString(R.string.stem_percent_left, it.left) }).joinToString(" ")
                }
            }
        }
    }

    private fun addGauge(gauge: StemCellPickerContent.Gauge) {
        val view = StemCellUsageGaugeView(context)
        view.configure(gauge)
        addView(view)
        gauges += view
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        applyTheme()
    }

    override fun applyTheme() {
        val pressedFill = if (isPressed) Theme.color(StemCellsPalette.stemCellsPanelPressed) else 0
        highlightShape.setColor(pressedFill)
        when (val line = line) {
            is StemCellPickerContent.Line.Option -> title.color = StemCellsPalette.stemCellsPanelText
            is StemCellPickerContent.Line.Heading -> {
                // Pressed, a heading reads in full ink.
                val ink = if (isPressed) StemCellsPalette.stemCellsPanelText else StemCellsPalette.stemCellsPanelSecondary
                title.color = ink
                detail.color = ink
                chevron.tint = ink
            }
            is StemCellPickerContent.Line.Link -> title.color = StemCellsPalette.stemCellsPanelSecondary
            is StemCellPickerContent.Line.Notice ->
                title.color = if (line.warning) StemCellsPalette.stemCellsUsageCritical else StemCellsPalette.stemCellsPanelSecondary
            StemCellPickerContent.Line.Separator -> setBackgroundColor(Theme.color(StemCellsPalette.stemCellsPanelDivider))
            is StemCellPickerContent.Line.Windows -> for (row in rows) {
                row.label.color = when {
                    row.window.critical -> StemCellsPalette.stemCellsUsageCritical
                    row.window.primary -> StemCellsPalette.stemCellsPanelText
                    else -> StemCellsPalette.stemCellsPanelSecondary
                }
                row.resets.color = StemCellsPalette.stemCellsPanelSecondary
                row.gauge?.applyTheme()
            }
        }
        for (gauge in gauges) gauge.applyTheme()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val bounds = Rect(0.0, 0.0, width, height)
        // Past the check's column.
        val named = Rect(M.checkColumn, 0.0, max(0.0, width - M.checkColumn), height)
        highlight.setFrame(Rect(0.0, M.highlightInset, width, max(0.0, height - 2 * M.highlightInset)))
        val type = StemCellPickerType
        when (val line = line) {
            is StemCellPickerContent.Line.Option -> {
                val placed = StemCellPickerLineLayout(
                    line = named,
                    leading = listOf(StemCellPickerLineLayout.Piece(type.size(line.label, type.label), shrinks = true)),
                    trailing = line.gauge?.let { listOf(StemCellPickerLineLayout.Piece(type.gaugeSize(it))) } ?: emptyList(),
                )
                title.setFrame(placed.leading[0])
                if (line.checked) check.setFrame(Rect(M.padding, height / 2 - M.check / 2, M.check, M.check))
                placed.trailing.firstOrNull()?.let { gauges.firstOrNull()?.setFrame(it) }
            }
            is StemCellPickerContent.Line.Heading -> {
                // The account gives way first, then the service.
                val leading = ArrayList<StemCellPickerLineLayout.Piece>()
                leading += StemCellPickerLineLayout.Piece(type.size(line.service, type.service), shrinks = true)
                if (line.account != null) {
                    leading += StemCellPickerLineLayout.Piece(type.size(line.account, type.account), maxWidth = M.accountMax, shrinks = true, gapBefore = M.headingGap)
                }
                val trailing = ArrayList<StemCellPickerLineLayout.Piece>()
                if (line.opens != null) trailing += StemCellPickerLineLayout.Piece(Size(M.chevron, M.chevron))
                for (index in line.gauges.indices.reversed()) {
                    val gap = if (index == line.gauges.size - 1) M.headingGap else M.usageGap
                    trailing += StemCellPickerLineLayout.Piece(type.gaugeSize(line.gauges[index]), gapBefore = gap)
                }
                val placed = StemCellPickerLineLayout(line = bounds, leading = leading, trailing = trailing, trailingGap = M.headingGap)
                title.setFrame(placed.leading[0])
                if (line.account != null) detail.setFrame(placed.leading[1])
                var frames = placed.trailing
                if (line.opens != null) {
                    chevron.setFrame(frames[0])
                    frames = frames.drop(1)
                }
                for ((view, frame) in gauges.reversed().zip(frames)) view.setFrame(frame)
            }
            is StemCellPickerContent.Line.Link -> title.setFrame(
                StemCellPickerLineLayout(line = named, leading = listOf(StemCellPickerLineLayout.Piece(type.size(line.label, type.link), shrinks = true))).leading[0],
            )
            is StemCellPickerContent.Line.Notice ->
                title.setFrame(Rect(named.minX + M.padding, M.detailsInsetY, max(0.0, named.width - 2 * M.padding), max(0.0, height - 2 * M.detailsInsetY)))
            StemCellPickerContent.Line.Separator -> Unit
            is StemCellPickerContent.Line.Windows -> {
                var y = M.detailsInsetY
                for (row in rows) {
                    val lineHeight = if (row.window.primary) M.detailsPrimaryLine else M.detailsLine
                    val band = Rect(0.0, y, width, lineHeight)
                    val trailing = row.gauge?.let { listOf(StemCellPickerLineLayout.Piece(it.layout.size)) } ?: emptyList()
                    val labelSize = type.size(row.window.label, type.window(row.window.primary))
                    val leading = ArrayList<StemCellPickerLineLayout.Piece>()
                    if (row.window.resets != null) {
                        leading += StemCellPickerLineLayout.Piece(Size(max(labelSize.width, M.detailsLabelMin), labelSize.height))
                        leading += StemCellPickerLineLayout.Piece(type.size(row.window.resets, type.account), shrinks = true, gapBefore = M.detailsGap)
                    } else {
                        leading += StemCellPickerLineLayout.Piece(labelSize, shrinks = true)
                    }
                    val placed = StemCellPickerLineLayout(line = band, leading = leading, trailing = trailing, trailingGap = M.detailsGap)
                    row.label.setFrame(placed.leading[0])
                    if (placed.leading.size > 1) row.resets.setFrame(placed.leading[1])
                    placed.trailing.firstOrNull()?.let { row.gauge?.setFrame(it) }
                    y += lineHeight + M.gap
                }
            }
        }
    }
}

/**
 * A panel (happy-ios `StemCellPickerPanelView`): iOS's menus are liquid
 * glass; here, Android's raised surface with its soft shadow, its lines
 * scrolling in its viewport and its footer under them.
 */
class StemCellPickerPanelView(context: Context) : ManualLayout(context), ThemeAware {
    private val M = StemCellPickerMetrics
    private val shape = GradientDrawable().apply { cornerRadius = dp(StemCellPickerMetrics.radius) }
    val scroll = BounceScrollView(context)
    private val footerViews = ArrayList<StemCellPickerLineView>()
    var content: StemCellPickerContent? = null
        private set
    var layout: StemCellPickerPanelLayout? = null
        private set
    var onTap: ((StemCellPickerContent.Line) -> Unit)? = null

    init {
        background = shape
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(M.shadowRadius / 2)
        addView(scroll)
        applyTheme()
    }

    override fun applyTheme() {
        shape.setColor(Theme.color(Palette.surfaceHigh))
        shape.setStroke(1, Theme.color(Palette.divider))
        outlineSpotShadowColor = Theme.color(StemCellsPalette.stemCellsPanelShadow)
        outlineAmbientShadowColor = Theme.color(StemCellsPalette.stemCellsPanelShadow)
    }

    /**
     * Shows [content] where [layout] puts it. Opened, the lines stand at the
     * layout's first offset; redrawn, they keep where they were. The same
     * lines are moved, not made again, so a finger down on one stays.
     */
    fun apply(content: StemCellPickerContent, layout: StemCellPickerPanelLayout, opening: Boolean) {
        if (!opening && content == this.content && layout == this.layout) return
        val offset = scroll.contentOffset
        val same = content == this.content
        this.content = content
        this.layout = layout
        tag = content.id
        contentDescription = content.label
        setFrame(layout.frame)
        val frames = scroll.content.frames
        if (!same) {
            for (view in frames.keys) scroll.content.removeView(view)
            frames.clear()
            footerViews.forEach(::removeView)
            footerViews.clear()
            for (line in content.lines) {
                val view = make(line)
                scroll.content.addView(view)
                frames[view] = null
            }
            for (line in content.footer) {
                val view = make(line)
                addView(view)
                footerViews += view
            }
        }
        for ((view, frame) in frames.keys.toList().zip(layout.items)) frames[view] = place((view as StemCellPickerLineView).line, frame)
        scroll.contentHeight = layout.contentHeight
        scroll.content.requestLayout()
        val scrolls = max(0.0, layout.contentHeight - layout.viewport.height)
        scroll.isScrollEnabled = scrolls > 0
        scroll.setContentOffset(if (opening) layout.initialOffset else min(scrolls, offset))
        requestLayout()
    }

    private fun make(line: StemCellPickerContent.Line) = StemCellPickerLineView(context, line).also { view ->
        view.setOnClickListener { onTap?.invoke(view.line) }
    }

    /** A hairline spans the text column, `padding` in from the line's sides. */
    private fun place(line: StemCellPickerContent.Line, frame: Rect) =
        if (line == StemCellPickerContent.Line.Separator) Rect(frame.minX + M.padding, frame.minY, max(0.0, frame.width - 2 * M.padding), frame.height) else frame

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout ?: return
        scroll.setFrame(layout.viewport)
        for ((view, frame) in footerViews.zip(layout.footer)) view.setFrame(place(view.line, frame))
    }

    /** The panel grows in from its bottom trailing corner, where its chip is. */
    fun grow() {
        pivotX = dp(layout?.frame?.width ?: 0.0)
        pivotY = dp(layout?.frame?.height ?: 0.0)
        scaleX = 0.9f
        scaleY = 0.9f
        alpha = 0f
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320).setInterpolator(PathInterpolator(0.2f, 0.9f, 0.3f, 1f)).setListener(null).start()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    /** It dissolves, then leaves its parent. */
    fun dissolve(animated: Boolean) {
        val parent = parent as? android.view.ViewGroup ?: return
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        tag = null
        if (!animated) {
            parent.removeView(this)
            return
        }
        animate().alpha(0f).setDuration(StemCellPickerOverlay.POP_DURATION).setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    parent.removeView(this@StemCellPickerPanelView)
                }
            }).start()
    }
}

/**
 * The panels over the screen (happy-ios `StemCellPickerOverlay`): a touch
 * outside the top one closes it (a stacked one alone, the panel under it
 * staying), as desktop's backdrop.
 */
class StemCellPickerOverlay(context: Context) : ManualLayout(context) {
    val panels = ArrayList<StemCellPickerPanelView>()
    var onOutside: (() -> Unit)? = null
    var onLayout: (() -> Unit)? = null
    private var downOutside = false

    companion object {
        /** How long a panel takes to dissolve. */
        const val POP_DURATION = 160L
    }

    init {
        tag = "stem-picker-overlay"
        isClickable = true
    }

    /** A panel on top, placed before it is first drawn. */
    fun push(panel: StemCellPickerPanelView) {
        panels += panel
        addView(panel)
    }

    fun pop(animated: Boolean) {
        val panel = panels.removeLastOrNull() ?: return
        panel.dissolve(animated)
    }

    private fun inTop(event: MotionEvent): Boolean {
        val top = panels.lastOrNull() ?: return false
        return event.x >= top.left && event.x < top.right && event.y >= top.top && event.y < top.bottom
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) downOutside = !inTop(event)
        // Only the top panel's lines take a touch; one elsewhere closes it.
        if (!downOutside) {
            val top = panels.lastOrNull() ?: return true
            val local = MotionEvent.obtain(event)
            local.offsetLocation(-top.left.toFloat(), -top.top.toFloat())
            val handled = top.dispatchTouchEvent(local)
            local.recycle()
            return handled || true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            downOutside = false
            onOutside?.invoke()
        }
        return true
    }

    override fun layoutSubviews(width: Double, height: Double) {
        onLayout?.invoke()
        for (panel in panels) panel.layout?.frame?.let { panel.setFrame(it) }
    }
}
