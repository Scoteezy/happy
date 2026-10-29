package com.slopus.happy.ui.stories

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import com.slopus.happy.R
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.storiesAvatarText
import com.slopus.happy.layout.storiesAvatarFill
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.stories.StoryViewersRowLayout
import com.slopus.happy.layout.stories.StoryViewersSheetLayout
import com.slopus.happy.layout.storiesViewerText
import com.slopus.happy.layout.storiesViewersGround
import com.slopus.happy.layout.storiesViewersSecondary
import com.slopus.happy.layout.storiesViewersSeparator
import com.slopus.happy.stories.StoriesRow
import com.slopus.happy.stories.Story
import com.slopus.happy.stories.StoryViewer
import com.slopus.happy.stories.storyViewers
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.FrameContainer
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.density
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Who has seen one's own story (happy-ios `StoriesViewersViewController`),
 * as Telegram's story view list shows them: a sheet from the foot of the
 * screen, edge to edge, "N views" in its header over the list, those who
 * reacted first with when they reacted under their name and their reaction
 * on the right, read a page at a time. It opens half way; a drag takes it
 * full or away, as does a tap above it. Dark whatever the system's
 * appearance, over a story.
 */
class StoriesViewersSheet(context: Context, private val session: StoriesSession, private val story: Story) : ManualLayout(context) {
    var onClose: (() -> Unit)? = null
    private val panel = Panel(context)
    private val titleLabel = StoryLineLabel(context, 17.0, StoryViewerPageView.semibold, Palette.storiesViewerText.resolve(true).argb, centered = true)
    private val headerLine = View(context)
    private val list = ScrollView(context)
    private val rows = FrameContainer(context)
    private val empty = StoryLineLabel(context, 15.0, null, Palette.storiesViewersSecondary.resolve(true).argb, centered = true)
    private var viewers: List<StoryViewer> = emptyList()
    private val rowViews = ArrayList<ViewerRow>()
    private var cursor: String? = null
    private var loading = false
    private var ended = false
    private var failures = 0
    private val scope = MainScope()
    private var full = false
    /** How much of the sheet shows above the screen's foot (dp). */
    private var shown = 0.0
    private var animator: ValueAnimator? = null
    private var closing = false

    init {
        tag = "story-viewers"
        clipChildren = true
        panel.tag = "story-viewers-panel"
        titleLabel.tag = "story-viewers-title"
        titleLabel.text = story.viewCount?.let { StoriesStrings.views(context, it) } ?: context.getString(R.string.stories_viewers)
        headerLine.setBackgroundColor(Palette.storiesViewersSeparator.resolve(true).argb)
        list.isVerticalScrollBarEnabled = false
        list.overScrollMode = OVER_SCROLL_NEVER
        list.addView(rows)
        list.setOnScrollChangeListener { _, _, y, _, _ -> if (y + list.height >= rows.height - dp(StoryViewersRowLayout.Metrics.height * 5)) loadMore() }
        list.tag = "story-viewers-list"
        empty.text = context.getString(R.string.stories_no_viewers)
        empty.visibility = GONE
        panel.addView(list)
        panel.addView(empty)
        panel.addView(titleLabel)
        panel.addView(headerLine)
        addView(panel)
        loadMore()
    }

    private val half: Double get() = StoryViewersSheetLayout.half(px(height))
    private val fullHeight: Double get() = StoryViewersSheetLayout.full(px(height))
    private val resting: Double get() = if (full) fullHeight else half

    /** Slides up into place, half open. */
    fun present() {
        shown = 0.0
        requestLayout()
        post { animate(to = half) }
    }

    /** Slides down away from wherever it is. */
    fun dismiss(animated: Boolean = true) {
        if (closing) return
        closing = true
        val done = {
            (parent as? android.view.ViewGroup)?.removeView(this)
            scope.cancel()
            onClose?.invoke()
            onClose = null
        }
        if (!animated) return done()
        animate(to = 0.0, end = done)
    }

    private fun animate(to: Double, end: (() -> Unit)? = null) {
        animator?.cancel()
        val from = shown
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = android.view.animation.DecelerateInterpolator(2f)
            addUpdateListener {
                shown = from + (to - from) * (it.animatedValue as Float)
                placePanel()
            }
            if (end != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = end()
            })
            start()
        }
    }

    private fun placePanel() {
        val frame = StoryViewersSheetLayout.sheet(Size(px(width), px(height)), max(0.0, shown))
        panel.setFrame(frame)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        placePanel()
    }

    private fun loadMore() {
        if (loading || ended) return
        loading = true
        val client = session.client
        val cursor = cursor
        scope.launch {
            val page = runCatching { client.storyViewers(story.id, cursor) }.getOrNull()
            loading = false
            if (page == null) return@launch retry()
            failures = 0
            // A viewer who reacts meanwhile moves up into a page already read.
            val seen = viewers.map { it.userId }.toSet()
            viewers = viewers + page.viewers.filter { it.userId !in seen }
            this@StoriesViewersSheet.cursor = page.nextCursor
            ended = page.nextCursor == null
            empty.visibility = if (viewers.isEmpty()) VISIBLE else GONE
            if (story.viewCount != null || viewers.isNotEmpty()) titleLabel.text = StoriesStrings.views(context, max(story.viewCount ?: 0, viewers.size))
            reload()
        }
    }

    /** A page that failed is asked for again a little later, a few times; after that the list stops where it is. */
    private fun retry() {
        failures += 1
        if (failures >= 3) {
            ended = true
            empty.visibility = if (viewers.isEmpty()) VISIBLE else GONE
            return
        }
        val wait = failures
        scope.launch {
            delay(wait * 1000L)
            if (isAttachedToWindow) loadMore()
        }
    }

    private fun reload() {
        while (rowViews.size < viewers.size) {
            val row = ViewerRow(context)
            rowViews += row
            rows.addView(row)
        }
        for ((index, viewer) in viewers.withIndex()) rowViews[index].configure(viewer, last = index == viewers.size - 1)
        panel.requestLayout()
    }

    // Dragging

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var tracker: VelocityTracker? = null
    private var downY = 0f
    private var lastY = 0f
    private var start = 0.0
    private var dragging = false
    private var onList = false

    private val listScrolled: Boolean get() = list.scrollY > 0

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                downY = ev.y
                lastY = ev.y
                dragging = false
                // Above the sheet: a tap there closes it.
                if (ev.y < panel.top) return true
                animator?.cancel()
                onList = ev.y >= panel.top + list.top && ev.y < panel.top + list.bottom
                start = shown
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dy = ev.y - downY
                if (!dragging && abs(dy) > slop) {
                    // Only a full sheet's list scrolls; below full the sheet moves.
                    val fullNow = shown >= fullHeight - 0.5
                    val listTakes = onList && fullNow && (dy < 0 || listScrolled)
                    if (!listTakes) {
                        dragging = true
                        lastY = ev.y
                        return true
                    }
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (event.y < panel.top && !dragging && abs(event.y - downY) <= slop) return true
                if (!dragging) {
                    if (abs(event.y - downY) <= slop) return true
                    dragging = true
                    start = shown
                    downY = event.y
                }
                var next = start - px(event.y - downY)
                if (next >= fullHeight) next = fullHeight else list.scrollTo(0, 0)
                shown = max(0.0, next)
                placePanel()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                dragging = false
                if (!wasDragging) {
                    if (event.actionMasked == MotionEvent.ACTION_UP && downY < panel.top) dismiss()
                    return true
                }
                tracker?.computeCurrentVelocity(1000)
                val velocity = px(tracker?.yVelocity ?: 0f)
                // A cancelled drag only puts the sheet back where it was.
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (shown < half - 80 || (velocity > 800 && shown <= half + 1)) return true.also { dismiss() }
                    full = if (velocity < -500) true else if (velocity > 500) false else shown > (half + fullHeight) / 2
                }
                if (!full) list.scrollTo(0, 0)
                animate(to = resting)
            }
        }
        return true
    }

    /** The sheet itself: its ground with the top corners rounded, the header and the list. */
    private inner class Panel(context: Context) : ManualLayout(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shape = Path()

        init {
            setWillNotDraw(false)
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            fill.color = Palette.storiesViewersGround.resolve(true).argb
            val r = dp(StoryViewersSheetLayout.Metrics.corner)
            shape.reset()
            shape.addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
            canvas.drawPath(shape, fill)
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val titleSize = Size(ceil(px(titleLabel.textWidth)), ceil(px(titleLabel.paint.fontSpacing)))
            val layout = StoryViewersSheetLayout(Size(width, height), titleSize)
            titleLabel.setFrame(layout.title)
            val hairline = 1 / density.toDouble()
            headerLine.setFrame(Rect(0.0, layout.header.height - hairline, layout.header.width, hairline))
            var y = 0.0
            for ((index, row) in rowViews.withIndex()) {
                if (index >= viewers.size) break
                val h = row.layout(width).height
                rows.frames[row] = Rect(0.0, y, width, h)
                y += h
            }
            rows.contentSize = Size(width, y)
            list.setFrame(layout.list)
            rows.setFrame(Rect(0.0, 0.0, width, y))
            rows.layoutSubviews(width, y)
            empty.setFrame(Rect(0.0, layout.list.minY, width, max(0.0, layout.list.height / 2)))
        }
    }

    /** One viewer (happy-ios `StoriesViewerCell`): avatar, name, when they reacted, the reaction. */
    private class ViewerRow(context: Context) : View(context) {
        private var viewer: StoryViewer? = null
        private var last = false
        private val name = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (StoryViewersRowLayout.Metrics.titleSize * Theme.density).toFloat()
            typeface = StoryViewerPageView.semibold
            color = Palette.storiesViewerText.resolve(true).argb
        }
        private val time = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (StoryViewersRowLayout.Metrics.subtitleSize * Theme.density).toFloat()
            color = Palette.storiesViewersSecondary.resolve(true).argb
        }
        private val avatarFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val initials = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 16 * Theme.density
            typeface = StoryViewerPageView.semibold
            textAlign = Paint.Align.CENTER
        }
        private val separator = Paint().apply { color = Palette.storiesViewersSeparator.resolve(true).argb }
        private val emoji = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            // An emoji's glyph at 19 fills Telegram's 22 reaction square.
            textSize = 19 * Theme.density
            textAlign = Paint.Align.CENTER
        }
        private var subtitle: String? = null

        fun configure(viewer: StoryViewer, last: Boolean) {
            this.viewer = viewer
            this.last = last
            subtitle = viewer.reactedAt?.let { StoriesStrings.`when`(context, it, System.currentTimeMillis()) }
            tag = "story-viewer:${viewer.name}"
            contentDescription = listOfNotNull(viewer.name, subtitle, viewer.reaction).joinToString(", ")
            invalidate()
        }

        fun layout(width: Double): StoryViewersRowLayout {
            val viewer = viewer
            val titleHeight = px(name.fontMetrics.let { it.descent - it.ascent })
            val subtitleHeight = if (viewer?.reactedAt == null) null else px(time.fontMetrics.let { it.descent - it.ascent })
            return StoryViewersRowLayout(width, titleHeight, subtitleHeight, viewer?.reaction != null)
        }

        override fun onDraw(canvas: Canvas) {
            val viewer = viewer ?: return
            val row = layout(px(width))
            avatarFill.color = Palette.storiesAvatarFill.resolve(true).argb
            initials.color = Palette.storiesAvatarText.resolve(true).argb
            val a = row.avatar
            canvas.drawCircle(dp(a.midX), dp(a.midY), dp(a.width / 2), avatarFill)
            val ifm = initials.fontMetrics
            canvas.drawText(StoriesRow.initials(viewer.name), dp(a.midX), dp(a.midY) - (ifm.ascent + ifm.descent) / 2, initials)
            val t = row.title
            val shownName = android.text.TextUtils.ellipsize(viewer.name, name, dp(t.width), android.text.TextUtils.TruncateAt.END)
            canvas.drawText(shownName, 0, shownName.length, dp(t.minX), dp(t.minY) - name.fontMetrics.ascent, name)
            row.subtitle?.let { frame ->
                val text = subtitle ?: return@let
                canvas.drawText(text, dp(frame.minX), dp(frame.minY) - time.fontMetrics.ascent, time)
            }
            row.reaction?.let { frame ->
                val reaction = viewer.reaction ?: return@let
                val efm = emoji.fontMetrics
                canvas.drawText(reaction, dp(frame.midX), dp(frame.midY) - (efm.ascent + efm.descent) / 2, emoji)
            }
            if (!last) {
                val hairline = 1 / density
                canvas.drawRect(dp(row.separatorLeading), height - hairline * density, width - dp(row.separatorTrailing), height.toFloat(), separator)
            }
        }
    }
}
