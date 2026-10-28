package com.slopus.happy.ui.components

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ScrollView
import com.slopus.happy.layout.GroupedListLayout
import com.slopus.happy.layout.ItemGroupLayout
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlin.math.max

/** A view's size at a width, as the layouts measure: [ManualLayout]s answer [ManualLayout.sizeThatFits], others are measured. */
val View.measurable: Measurable
    get() = Measurable { width ->
        when (val v = this) {
            is ManualLayout -> v.sizeThatFits(width, Double.MAX_VALUE)
            is Label -> v.measure(width)
            else -> {
                v.measure(
                    View.MeasureSpec.makeMeasureSpec(dp(width).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                Size(width, px(v.measuredHeight))
            }
        }
    }

/** ItemGroup.tsx's card: the rounded surface with a hairline border that holds the rows. */
class GroupCardView(context: Context) : ManualLayout(context), ThemeAware {
    private val shape = GradientDrawable()

    init {
        background = shape
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(ItemGroupLayout.Metrics.cardCornerRadius))
            }
        }
        applyTheme()
    }

    /** Rows are placed by the group. */
    override fun layoutSubviews(width: Double, height: Double) {}

    override fun applyTheme() {
        shape.cornerRadius = dp(ItemGroupLayout.Metrics.cardCornerRadius)
        shape.setColor(Theme.color(Palette.surface))
        shape.setStroke(1, Theme.color(Palette.divider))
    }
}

/**
 * components/ItemGroup.tsx (happy-ios `ItemGroupView`): header, rounded card
 * of rows, footer. Rows are any views that measure; [ItemRowView]s get their
 * dividers switched on for all but the last. The layout is kept per width
 * until something in the group asks for layout again.
 */
class ItemGroupView(context: Context, title: String? = null, footer: String? = null, rows: List<View> = emptyList()) : ManualLayout(context) {
    var title: String? = title
        set(value) { field = value; applyText() }

    var footer: String? = footer
        set(value) { field = value; applyText() }

    var rows: List<View> = emptyList()
        set(value) {
            val old = field
            field = value
            installRows(old)
        }

    /** `headerStyle` padding over an untitled card; null keeps the default. */
    var untitledPaddingTop: Double? = null
        set(value) { field = value; requestLayout() }

    /** `style.paddingBottom` under the group. */
    var paddingBottom: Double = 0.0
        set(value) { field = value; requestLayout() }

    val headerLabel = Label(context, TextStyles.groupHeader, Palette.groupedSectionTitle)
    val footerLabel = Label(context, TextStyles.groupFooter, Palette.groupedSectionTitle)
    val card = GroupCardView(context)
    private var cached: Pair<Double, ItemGroupLayout>? = null

    init {
        addView(headerLabel)
        addView(card)
        addView(footerLabel)
        applyText()
        this.rows = rows
    }

    fun layout(width: Double): ItemGroupLayout {
        cached?.let { (w, layout) -> if (w == width) return layout }
        val layout = ItemGroupLayout(
            ItemGroupLayout.Input(
                width = width,
                title = if (title == null) null else headerLabel.measurable,
                footer = if (footer == null) null else footerLabel.measurable,
                rows = rows.map { it.measurable },
                maxWidth = ContentWidth.maxWidth,
                untitledPaddingTop = untitledPaddingTop,
                paddingBottom = paddingBottom,
            ),
        )
        cached = width to layout
        return layout
    }

    override fun requestLayout() {
        cached = null
        super.requestLayout()
    }

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        layout.header?.let { headerLabel.setFrame(it) }
        card.setFrame(layout.card)
        rows.zip(layout.rows).forEach { (row, frame) -> row.setFrame(frame) }
        layout.footer?.let { footerLabel.setFrame(it) }
    }

    private fun applyText() {
        headerLabel.text = title.orEmpty()
        headerLabel.visibility = if (title == null) GONE else VISIBLE
        footerLabel.text = footer.orEmpty()
        footerLabel.visibility = if (footer == null) GONE else VISIBLE
        requestLayout()
    }

    private fun installRows(old: List<View>) {
        val kept = rows.toSet()
        old.filter { it.parent === card && it !in kept }.forEach(card::removeView)
        rows.forEachIndexed { index, row ->
            if (row.parent !== card) {
                (row.parent as? android.view.ViewGroup)?.removeView(row)
                card.addView(row)
            }
            (row as? ItemRowView)?.showsDivider = index < rows.size - 1
        }
        requestLayout()
    }
}

/** Tablet (happy-ios ContentWidth): the width grouped content is centred at; none on a phone. */
object ContentWidth {
    var maxWidth: Double? = null
}

/**
 * components/ItemList.tsx (happy-ios `GroupedListView`): a vertical scroll of
 * sections on the grouped background, the safe area applied through
 * [GroupedListLayout] so every frame comes from the pure layout.
 */
class GroupedListView(context: Context) : ScrollView(context), ThemeAware {
    private val content = Content(context)

    var sections: List<View> = emptyList()
        set(value) {
            val kept = value.toSet()
            field.filter { it.parent === content && it !in kept }.forEach(content::removeView)
            field = value
            value.forEach { if (it.parent !== content) content.addView(it) }
            content.requestLayout()
        }

    var safeArea: LayoutInsets = LayoutInsets.Zero
        set(value) { if (field != value) { field = value; content.requestLayout() } }

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(content)
        applyTheme()
    }

    override fun applyTheme() {
        setBackgroundColor(Theme.color(Palette.groupedBackground))
    }

    private inner class Content(context: Context) : ManualLayout(context) {
        private var viewport = Size.Zero

        fun layout(width: Double): GroupedListLayout =
            GroupedListLayout(GroupedListLayout.Input(Size(width, viewport.height), safeArea, sections.map { it.measurable }))

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = px(MeasureSpec.getSize(widthMeasureSpec))
            viewport = Size(width, px(this@GroupedListView.height))
            val size = layout(width).contentSize
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), max(dp(size.height).toInt(), this@GroupedListView.height))
        }

        override fun layoutSubviews(width: Double, height: Double) {
            sections.zip(layout(width).sections).forEach { (section, frame) -> section.setFrame(frame) }
        }
    }
}
