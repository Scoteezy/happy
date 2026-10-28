package com.slopus.happy.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.changelog.Changelog
import com.slopus.happy.changelog.ChangelogEntry
import com.slopus.happy.changelog.ChangelogMarkdown
import com.slopus.happy.changelog.SummarySegment
import com.slopus.happy.changelog.parseSummary
import com.slopus.happy.chat.markdown.MarkdownSpan
import com.slopus.happy.chat.markdown.SpanStyle
import com.slopus.happy.layout.CenteredMessageLayout
import com.slopus.happy.layout.ChangelogBodyLayout
import com.slopus.happy.layout.ChangelogEntryLayout
import com.slopus.happy.layout.ChangelogTitleLayout
import com.slopus.happy.layout.GroupedListLayout
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.changelogEmpty
import com.slopus.happy.layout.changelogNavigationTitle
import com.slopus.happy.ui.chat.ChatLinkSpan
import com.slopus.happy.ui.chat.ChatStrings
import com.slopus.happy.ui.chat.MeasuredText
import com.slopus.happy.ui.chat.TokenColorSpan
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.ContentWidth
import com.slopus.happy.ui.components.GroupCardView
import com.slopus.happy.ui.components.GroupedListView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What's New (app/(app)/changelog.tsx, happy-ios `ChangelogViewController`):
 * one card per release, newest first. Opening it marks the newest one read.
 *
 * The releases are laid out off the main thread ([ChangelogPlan]) at the
 * list's width and text size, and the main thread only places and draws
 * them; a new width or text size plans them again, and the old plan stays
 * drawn until the new one is in.
 */
class ChangelogScreen(context: Context, private val graph: AppGraph) : BarScreen(context) {
    private val changelog = Changelog.bundled(context.resources)
    private val entryViews = changelog.entries.map { ChangelogEntryView(context) }
    private val list = GroupedListView(context)
    private var planned: ChangelogPlan.Input? = null
    private var planning: Job? = null
    private var empty: CenteredMessageView? = null

    override fun createContent(): View {
        bar.title = context.getString(R.string.navigation_whats_new)
        bar.titleView = ChangelogTitleView(context, context.getString(R.string.navigation_whats_new))
        if (changelog.latestTitle.isNotEmpty()) graph.changelog.markViewed(changelog.latestTitle)
        if (changelog.entries.isEmpty()) {
            return CenteredMessageView(context, context.getString(R.string.changelog_no_entries_available)).also { empty = it }
        }
        list.sections = entryViews
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> requestPlan() }
        return list
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea
        empty?.safeArea = contentSafeArea
        requestPlan()
    }

    /** Asks for the plan at the list's width and text size, unless it is the one shown or on its way. */
    private fun requestPlan() {
        if (list.width <= 0) return
        val input = ChangelogPlan.Input(
            GroupedListLayout.contentWidth(Size(px(list.width), px(list.height)), contentSafeArea),
            TextLayouts.Env(Theme.density, Theme.textScale),
            ContentWidth.maxWidth,
        )
        if (input == planned) return
        planned = input
        planning?.cancel()
        val entries = changelog.entries
        planning = scope.launch {
            val plans = withContext(Dispatchers.Default) { entries.map { ChangelogPlan.make(it, input) } }
            entryViews.zip(plans).forEach { (view, plan) -> view.plan = plan }
            list.requestLayout()
        }
    }
}

/** One release laid out: the frames and every text, measured once (happy-ios `ChangelogPlan.Entry`). */
class ChangelogPlan(
    val layout: ChangelogEntryLayout,
    val title: MeasuredText,
    val summary: MeasuredText?,
    /** The paragraphs and bullet items, in order. */
    val texts: List<MeasuredText>,
    /** The "•" beside a bullet item; null when the release has none. */
    val bullet: MeasuredText?,
) {
    data class Input(
        /** The list's content width. */
        val width: Double,
        val env: TextLayouts.Env,
        /** ItemGroup.tsx's `layout.maxWidth`. */
        val maxWidth: Double?,
    )

    companion object {
        /** Off the main thread. */
        fun make(entry: ChangelogEntry, input: Input): ChangelogPlan {
            val env = input.env
            val title = MeasuredText(ChatStrings.string(entry.title, Palette.text), TextStyles.changelogTitle, env)
            val summary = if (entry.summary.isEmpty()) null else MeasuredText(summaryText(entry.summary), TextStyles.changelogSummary, env)
            val items = mutableListOf<ChangelogBodyLayout.Item>()
            val texts = ChangelogMarkdown.parse(entry.markdown).map { block ->
                val measured = MeasuredText(text(block.spans, env), TextStyles.changelogBody, env)
                items += ChangelogBodyLayout.Item(measured.measurable, bulleted = block is ChangelogMarkdown.Block.Bullet)
                measured
            }
            val bullet = if (items.any { it.bulleted }) MeasuredText(ChatStrings.string("•", Palette.text), TextStyles.changelogBody, env, maxLines = 1) else null
            val layout = ChangelogEntryLayout(
                input.width,
                title.measurable,
                summary?.measurable,
                items,
                bullet?.unwrapped()?.second ?: Size.Zero,
                input.maxWidth,
            )
            return ChangelogPlan(layout, title, summary, texts, bullet)
        }

        /** MarkdownView's text: regular, bold spans semibold, http(s) links underlined and opened (`RenderSpans`). */
        fun text(spans: List<ChangelogMarkdown.Span>, env: TextLayouts.Env): CharSequence = ChatStrings.spans(
            spans.map { span ->
                when (span) {
                    is ChangelogMarkdown.Span.Text -> MarkdownSpan(if (span.bold) setOf(SpanStyle.Bold) else emptySet(), span.text)
                    is ChangelogMarkdown.Span.Link -> MarkdownSpan(emptySet(), span.label, span.url)
                }
            },
            TextStyles.changelogBody,
            Palette.text,
            env,
        )

        /** `SummaryLine`: plain text and underlined links, which all open. The changelog bundles no images in summaries, so an image has nothing to show. */
        fun summaryText(summary: String): CharSequence {
            val builder = SpannableStringBuilder()
            for (segment in parseSummary(summary)) {
                when (segment) {
                    is SummarySegment.Text -> builder.append(segment.text)
                    is SummarySegment.Link -> {
                        val start = builder.length
                        builder.append(segment.label)
                        builder.setSpan(ChatLinkSpan(segment.url), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    is SummarySegment.Image -> Unit
                }
            }
            builder.setSpan(TokenColorSpan(Palette.text), 0, builder.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            return builder
        }
    }
}

/** Draws a planned text at its frame. Nothing is measured here. */
class PlannedTextView(context: Context) : View(context), ThemeAware {
    var text: MeasuredText? = null
        set(value) { if (field !== value) { field = value; invalidate() } }

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The layout drawn at the current size. */
    fun layout(): android.text.StaticLayout? = text?.layout(Rect(0.0, 0.0, px(width), px(height)))

    override fun applyTheme() = invalidate()

    override fun onDraw(canvas: Canvas) {
        layout()?.draw(canvas)
    }
}

/**
 * One What's New release (changelog.tsx's ItemGroup with `cardContent`):
 * the title over the card holding the summary line, the paragraphs and the
 * bullets, drawn from its [ChangelogPlan]. A tap on a link opens it; a long
 * press on a paragraph or bullet item offers Copy, as RN's `selectable` Text
 * does.
 */
class ChangelogEntryView(context: Context) : ManualLayout(context) {
    var plan: ChangelogPlan? = null
        set(value) {
            field = value
            apply()
        }

    private val card = GroupCardView(context)
    private val titleView = PlannedTextView(context)
    private val summaryView = PlannedTextView(context)
    private val textViews = mutableListOf<PlannedTextView>()
    private val bulletViews = mutableListOf<PlannedTextView>()

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val url = link(e.x, e.y) ?: return false
                Alerts.open(context, url)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val text = copyable(e.x, e.y) ?: return
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                val anchor = this@ChangelogEntryView
                PopupMenu(context, anchor).apply {
                    menu.add(context.getString(android.R.string.copy)).setOnMenuItemClickListener {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
                        true
                    }
                    show()
                }
            }
        },
    )

    init {
        addView(titleView)
        addView(card)
        card.addView(summaryView)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    override fun sizeThatFits(width: Double, height: Double) = plan?.layout?.size ?: Size(width, 0.0)

    private fun apply() {
        val plan = plan ?: return
        titleView.text = plan.title
        titleView.contentDescription = plan.title.string
        summaryView.text = plan.summary
        summaryView.visibility = if (plan.summary == null) GONE else VISIBLE
        sync(textViews, plan.texts.size)
        plan.texts.zip(textViews).forEach { (text, view) -> view.text = text }
        val bullets = plan.layout.body.bullets.count { it != null }
        sync(bulletViews, bullets)
        bulletViews.forEach { it.text = plan.bullet }
        requestLayout()
    }

    private fun sync(views: MutableList<PlannedTextView>, count: Int) {
        while (views.size > count) card.removeView(views.removeAt(views.size - 1))
        while (views.size < count) views += PlannedTextView(context).also(card::addView)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val plan = plan ?: return
        val group = plan.layout.group
        val body = plan.layout.body
        card.setFrame(group.card)
        group.header?.let { titleView.setFrame(it) }
        body.summary?.let { summaryView.setFrame(it) }
        textViews.zip(body.texts).forEach { (view, frame) -> view.setFrame(frame) }
        bulletViews.zip(body.bullets.filterNotNull()).forEach { (view, frame) -> view.setFrame(frame) }
    }

    /** The text view under ([x], [y]) px, with the point in its own coordinates. */
    private fun hit(x: Float, y: Float, views: List<PlannedTextView>): Pair<PlannedTextView, Pair<Float, Float>>? {
        val slop = 4 * Theme.density
        for (view in views) {
            if (view.visibility != VISIBLE || view.text == null) continue
            val left = card.left + view.left
            val top = card.top + view.top
            if (x >= left - slop && x <= left + view.width + slop && y >= top - slop && y <= top + view.height + slop) {
                return view to ((x - left) to (y - top))
            }
        }
        return null
    }

    private fun link(x: Float, y: Float): String? {
        val (view, point) = hit(x, y, listOf(summaryView) + textViews) ?: return null
        val layout = view.layout() ?: return null
        val spanned = view.text?.text as? Spanned ?: return null
        val line = layout.getLineForVertical(point.second.toInt())
        val offset = layout.getOffsetForHorizontal(line, point.first)
        return spanned.getSpans(offset, offset, ChatLinkSpan::class.java).firstOrNull()?.url
    }

    private fun copyable(x: Float, y: Float): String? = hit(x, y, textViews)?.first?.text?.string
}

/** What's New's navigation title: the words and the mouse on the phone. */
class ChangelogTitleView(context: Context, title: String) : ManualLayout(context) {
    private val label = Label(context, TextStyles.changelogNavigationTitle, Palette.headerTint, maxLines = 1)
    private val imageView = ImageView(context).apply {
        setImageResource(R.drawable.changelog_mouse_on_the_phone)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        label.text = title
        contentDescription = title
        addView(label)
        addView(imageView)
    }

    override fun sizeThatFits(width: Double, height: Double) = ChangelogTitleLayout(label.measurable, width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = ChangelogTitleLayout(label.measurable, width)
        label.setFrame(layout.title)
        imageView.setFrame(layout.image)
    }
}

/** A message centred on the grouped background. */
class CenteredMessageView(context: Context, message: String) : ManualLayout(context), ThemeAware {
    var safeArea: LayoutInsets = LayoutInsets.Zero
        set(value) { if (field != value) { field = value; requestLayout() } }

    private val label = Label(context, TextStyles.changelogEmpty, Palette.textSecondary, alignment = Layout.Alignment.ALIGN_CENTER)

    init {
        label.text = message
        addView(label)
        applyTheme()
    }

    override fun applyTheme() = setBackgroundColor(Theme.color(Palette.groupedBackground))

    override fun layoutSubviews(width: Double, height: Double) {
        label.setFrame(CenteredMessageLayout(Size(width, height), safeArea, label.measurable).message)
    }
}
