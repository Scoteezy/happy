package com.slopus.happy.ui.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.widget.NestedScrollView
import com.slopus.happy.icons.IconCatalog
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.ui.diff.DiffChunkView
import com.slopus.happy.ui.stories.StoryInlineView
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.toAndroidPath
import kotlin.math.roundToInt

/** Draws a [ChatRowPlan]'s items. Nothing is measured here. */
internal class ChatPlanPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val elapsedPaints = HashMap<ChatTextEnv, android.text.TextPaint>()

    fun draw(canvas: Canvas, items: List<ChatDrawItem>, clip: Rect? = null) {
        val d = Theme.density
        for (item in items) {
            when (item) {
                is ChatDrawItem.Box -> drawBox(canvas, item.box, d)
                is ChatDrawItem.Text -> {
                    val f = item.frame
                    if (clip != null && (f.maxY < clip.minY || f.minY > clip.maxY)) continue
                    canvas.save()
                    canvas.translate((f.minX * d).toFloat(), (f.minY * d).toFloat())
                    canvas.clipRect(0f, 0f, (f.width * d).toFloat() + 1, (f.height * d).toFloat() + 1)
                    if (item.alpha < 1) {
                        canvas.saveLayerAlpha(0f, 0f, (f.width * d).toFloat() + 1, (f.height * d).toFloat() + 1, (item.alpha * 255).roundToInt())
                        item.layout.draw(canvas)
                        canvas.restore()
                    } else {
                        item.layout.draw(canvas)
                    }
                    canvas.restore()
                }
                is ChatDrawItem.Glyph -> {
                    val icon = IconCatalog.glyph(item.icon) ?: continue
                    val scale = d.toDouble()
                    val layout = icon.layout(item.size, scale)
                    val box = layout.frame(item.frame, scale)
                    icon.path.toAndroidPath(scale = (layout.glyphScale * scale).toFloat(), into = path)
                    glyph.color = Theme.color(item.tint)
                    canvas.save()
                    canvas.translate((box.x * d).toFloat(), (box.y * d).toFloat())
                    canvas.drawPath(path, glyph)
                    canvas.restore()
                }
                is ChatDrawItem.Elapsed -> {
                    val paint = elapsedPaints.getOrPut(item.env) { TextLayouts.paint(ChatTextStyles.toolElapsed, item.env, 0) }
                    paint.color = Theme.color(Palette.textSecondary)
                    val seconds = (System.currentTimeMillis() - item.since).coerceAtLeast(0) / 1000.0
                    val text = String.format("%.1fs", seconds)
                    val f = item.frame
                    val width = paint.measureText(text)
                    val fm = paint.fontMetrics
                    val baseline = (f.midY * d).toFloat() - (fm.ascent + fm.descent) / 2
                    canvas.drawText(text, (f.maxX * d).toFloat() - width, baseline, paint)
                }
                is ChatDrawItem.Pulse -> {
                    // iOS's opacity animation: 0.18 to 0.52 and back, 0.72s each way, ease in-out.
                    val t = (SystemClock.uptimeMillis() % PULSE_PERIOD_MS) / PULSE_HALF_MS.toDouble()
                    val p = if (t <= 1) t else 2 - t
                    val eased = (1 - kotlin.math.cos(Math.PI * p)) / 2
                    drawBox(canvas, item.box.copy(alpha = 0.18 + (0.52 - 0.18) * eased), d)
                }
                is ChatDrawItem.Scroller, is ChatDrawItem.Spinner, is ChatDrawItem.Diff, is ChatDrawItem.DiffHeader, is ChatDrawItem.Mermaid, is ChatDrawItem.WebImage, is ChatDrawItem.Attachment, is ChatDrawItem.Media, is ChatDrawItem.Story -> Unit
            }
        }
    }

    private fun drawBox(canvas: Canvas, box: ChatBox, d: Float) {
        val f = box.frame
        rect.set((f.minX * d).toFloat(), (f.minY * d).toFloat(), (f.maxX * d).toFloat(), (f.maxY * d).toFloat())
        val r = (box.radius * d).toFloat()
        val alpha = (box.alpha * 255).roundToInt()
        box.fill?.let {
            fill.color = Theme.color(it)
            fill.alpha = (fill.alpha * box.alpha).roundToInt()
            if (box.topCornersOnly && r > 0) {
                path.reset()
                path.addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
                canvas.drawPath(path, fill)
            } else {
                canvas.drawRoundRect(rect, r, r, fill)
            }
        }
        val border = box.border
        if (border != null && box.borderWidth > 0) {
            val w = (box.borderWidth * d).toFloat()
            stroke.strokeWidth = w
            stroke.color = Theme.color(border)
            stroke.alpha = (stroke.alpha * alpha / 255f).roundToInt()
            rect.inset(w / 2, w / 2)
            canvas.drawRoundRect(rect, maxOf(0f, r - w / 2), maxOf(0f, r - w / 2), stroke)
        }
    }

    fun hasElapsed(items: List<ChatDrawItem>) = items.any { it is ChatDrawItem.Elapsed }

    fun hasPulse(items: List<ChatDrawItem>) = items.any { it is ChatDrawItem.Pulse }

    private companion object {
        const val PULSE_HALF_MS = 720L
        const val PULSE_PERIOD_MS = 2 * PULSE_HALF_MS
    }
}

/** The outer RecyclerView must not intercept a drag begun inside the capped list. */
private class PermissionScrollView(context: Context) : NestedScrollView(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && (canScrollVertically(-1) || canScrollVertically(1))) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }
}

/** Draws a plan that scrolls sideways (a code block's strip, a table's grid). */
internal class ChatPlanView(context: Context) : View(context) {
    private val painter = ChatPlanPainter()
    var plan: ChatRowPlan? = null
        set(value) { field = value; requestLayout(); invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = plan?.size
        setMeasuredDimension(size?.let { (it.width * Theme.density).roundToInt() } ?: 0, size?.let { (it.height * Theme.density).roundToInt() } ?: 0)
    }

    override fun onDraw(canvas: Canvas) {
        plan?.let { painter.draw(canvas, it.items) }
    }
}

/**
 * One chat row: draws its plan, hosts what scrolls sideways and the
 * spinners, and turns taps and long presses into the plan's actions
 * (happy-ios `ChatRowCell`).
 */
class ChatRowView(context: Context) : ViewGroup(context), ThemeAware {
    private val painter = ChatPlanPainter()
    var plan: ChatRowPlan? = null
        private set

    /** A tap or a long press on something with an action; the frame is the hit's, in the row. */
    var onAction: ((ChatRowAction, Rect, Boolean) -> Unit)? = null

    private val scrollers = ArrayList<Pair<FrameLayout, ChatDrawItem.Scroller>>()
    private val spinners = ArrayList<Pair<ProgressBar, ChatDrawItem.Spinner>>()
    private val diffs = ArrayList<Pair<DiffChunkView, ChatDrawItem.Diff>>()
    private val diffHeaders = ArrayList<Pair<com.slopus.happy.ui.diff.DiffFileHeaderView, ChatDrawItem.DiffHeader>>()
    private val mermaids = ArrayList<Pair<ChatMermaidView, ChatDrawItem.Mermaid>>()
    private val images = ArrayList<Pair<ChatWebImageView, ChatDrawItem.WebImage>>()
    private val attachments = ArrayList<Pair<ChatAttachmentView, ChatDrawItem.Attachment>>()
    private val media = ArrayList<Pair<ChatMediaView, ChatDrawItem.Media>>()
    private val stories = ArrayList<Pair<StoryInlineView, ChatDrawItem.Story>>()
    private var ticking = false
    /** A pulse redraws every frame; a timer every 100ms. */
    private var pulsing = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = hit(e, hold = false) != null || hit(e, hold = true) != null

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val hit = hit(e, hold = false) ?: return false
            playSoundEffect(android.view.SoundEffectConstants.CLICK)
            onAction?.invoke(hit.action, hit.frame, false)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val hit = hit(e, hold = true) ?: return
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onAction?.invoke(hit.action, hit.frame, true)
        }
    })

    init {
        setWillNotDraw(false)
        clipChildren = true
    }

    private fun hit(e: MotionEvent, hold: Boolean): ChatHit? {
        val x = px(e.x)
        val y = px(e.y)
        return plan?.hits?.firstOrNull { it.hold == hold && it.frame.contains(x, y) }
    }

    fun bind(plan: ChatRowPlan) {
        if (this.plan === plan) return
        val previousId = this.plan?.id
        this.plan = plan
        alpha = plan.alpha.toFloat()
        contentDescription = plan.accessibilityLabel
        val previousScrollers = scrollers.toList()
        scrollers.clear()
        for ((view, _) in spinners) removeView(view)
        spinners.clear()
        for ((view, _) in diffHeaders) removeView(view)
        diffHeaders.clear()
        // Diff views are kept, by position, so an opened diff keeps its scroll.
        val spareDiffs = diffs.map { it.first }.toMutableList()
        diffs.clear()
        // Diagram pages too: loading one is the slow part.
        val spareMermaids = mermaids.map { it.first }.toMutableList()
        mermaids.clear()
        val spareImages = images.map { it.first }.toMutableList()
        images.clear()
        val spareAttachments = attachments.map { it.first }.toMutableList()
        attachments.clear()
        val spareMedia = media.map { it.first }.toMutableList()
        media.clear()
        val spareStories = stories.map { it.first }.toMutableList()
        stories.clear()
        for (item in plan.items) {
            when (item) {
                is ChatDrawItem.Scroller -> {
                    // A permission update keeps the same list's scroll, but a recycled
                    // chat row must never inherit another tool's position or actions.
                    val kept = previousScrollers.getOrNull(scrollers.size)?.takeIf {
                        previousId == plan.id && item.vertical && it.second.vertical
                    }?.first
                    val scroll = kept ?: if (item.vertical) {
                        PermissionScrollView(context).apply {
                            isFillViewport = false
                            addView(ChatRowView(context))
                        }
                    } else {
                        HorizontalScrollView(context).apply {
                            isHorizontalScrollBarEnabled = false
                            addView(ChatPlanView(context))
                        }
                    }
                    when (val content = scroll.getChildAt(0)) {
                        is ChatRowView -> {
                            content.onAction = { action, frame, hold ->
                                onAction?.invoke(action, frame.offsetBy(item.frame.minX - px(scroll.scrollX), item.frame.minY - px(scroll.scrollY)), hold)
                            }
                            content.bind(item.plan)
                        }
                        is ChatPlanView -> content.plan = item.plan
                    }
                    scroll.overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
                    scroll.clipToOutline = item.radius > 0
                    if (item.radius > 0) scroll.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(item.radius))
                        }
                    }
                    if (scroll.parent == null) addView(scroll)
                    scrollers += scroll to item
                }
                is ChatDrawItem.Spinner -> {
                    val spinner = ProgressBar(context).apply {
                        isIndeterminate = true
                        indeterminateTintList = ColorStateList.valueOf(Theme.color(item.tint))
                    }
                    addView(spinner)
                    spinners += spinner to item
                }
                is ChatDrawItem.Diff -> {
                    val view = if (spareDiffs.isNotEmpty()) spareDiffs.removeAt(0) else DiffChunkView(context).also { addView(it) }
                    view.onShowMore = { file -> onAction?.invoke(ChatRowAction.DiffShowMore(item.toolId, item.chunk, file), item.frame, false) }
                    view.apply(item.plan)
                    view.clipRadius = item.radius
                    view.clipBottomCornersOnly = item.bottomCornersOnly
                    diffs += view to item
                }
                is ChatDrawItem.DiffHeader -> {
                    val view = com.slopus.happy.ui.diff.DiffFileHeaderView(context, drawsBackground = false)
                    view.apply(item.plan, item.path)
                    // The parent's header hit includes the move destination and chevron.
                    view.isClickable = item.opens != null
                    view.isFocusable = item.opens != null
                    view.importantForAccessibility = if (item.opens == null) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
                    item.opens?.let { action -> view.setOnClickListener { onAction?.invoke(action, item.frame, false) } }
                    addView(view)
                    diffHeaders += view to item
                }
                is ChatDrawItem.Mermaid -> {
                    val view = spareMermaids.firstOrNull { it.diagram == item.diagram }?.also { spareMermaids.remove(it) }
                        ?: (if (spareMermaids.isNotEmpty()) spareMermaids.removeAt(0) else ChatMermaidView(context).also { addView(it) })
                    view.onHeight = { diagram, height -> onAction?.invoke(ChatRowAction.MermaidHeight(diagram.row, diagram.index, height), item.frame, false) }
                    view.apply(item.diagram)
                    mermaids += view to item
                }
                is ChatDrawItem.WebImage -> {
                    val view = if (spareImages.isNotEmpty()) spareImages.removeAt(0) else ChatWebImageView(context).also { addView(it) }
                    view.apply(item.url, item.label)
                    images += view to item
                }
                is ChatDrawItem.Attachment -> {
                    val view = spareAttachments.firstOrNull { it.attachment == item.attachment }?.also { spareAttachments.remove(it) }
                        ?: (if (spareAttachments.isNotEmpty()) spareAttachments.removeAt(0) else ChatAttachmentView(context).also { addView(it) })
                    view.apply(item.attachment)
                    attachments += view to item
                }
                is ChatDrawItem.Media -> {
                    val view = spareMedia.firstOrNull { it.media == item.media }?.also { spareMedia.remove(it) }
                        ?: (if (spareMedia.isNotEmpty()) spareMedia.removeAt(0) else ChatMediaView(context).also { addView(it) })
                    view.apply(item.media)
                    media += view to item
                }
                is ChatDrawItem.Story -> {
                    val view = spareStories.firstOrNull { it.tag == "story-inline:${item.id}" }?.also { spareStories.remove(it) }
                        ?: (if (spareStories.isNotEmpty()) spareStories.removeAt(0) else StoryInlineView(context).also { addView(it) })
                    view.apply(item.id)
                    stories += view to item
                }
                else -> Unit
            }
        }
        pulsing = painter.hasPulse(plan.items)
        for ((view, _) in previousScrollers) if (scrollers.none { it.first === view }) removeView(view)
        for (view in spareDiffs) removeView(view)
        for (view in spareMermaids) removeView(view)
        for (view in spareImages) removeView(view)
        for (view in spareAttachments) removeView(view)
        for (view in spareMedia) removeView(view)
        for (view in spareStories) { view.close(); removeView(view) }
        ticking = pulsing || painter.hasElapsed(plan.items)
        requestLayout()
        invalidate()
        if (ticking) tick()
    }

    /** Pictures that failed are asked for again: the computer is back. */
    fun retryMedia() {
        for ((view, _) in media) view.retry()
    }

    /** The owning conversation is gone, rather than temporarily off screen. */
    fun closeStories() {
        for ((view, _) in stories) view.close()
    }

    private fun tick() {
        if (!ticking || !isAttachedToWindow) return
        invalidate()
        if (pulsing) postOnAnimation { tick() } else postOnAnimationDelayed({ tick() }, 100)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ticking) tick()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = plan?.size
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, size?.let { (it.height * Theme.density).roundToInt() } ?: 0)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((view, item) in scrollers) view.setFrame(item.frame)
        for ((view, item) in diffs) view.setFrame(item.frame)
        for ((view, item) in diffHeaders) view.setFrame(item.frame)
        for ((view, item) in mermaids) view.setFrame(item.frame)
        for ((view, item) in images) view.setFrame(item.frame)
        for ((view, item) in attachments) view.setFrame(item.frame)
        for ((view, item) in media) view.setFrame(item.frame)
        for ((view, item) in stories) view.setFrame(item.frame)
        for ((view, item) in spinners) {
            // The platform's spinner at 0.8 of the glyph's box, as iOS scales its own.
            val f = item.frame
            val side = f.width * 0.8
            view.setFrame(Rect(f.midX - side / 2, f.midY - side / 2, side, side))
        }
    }

    override fun onDraw(canvas: Canvas) {
        plan?.let { painter.draw(canvas, it.items) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    override fun applyTheme() {
        for ((view, item) in spinners) view.indeterminateTintList = ColorStateList.valueOf(Theme.color(item.tint))
        for ((view, _) in diffs) view.applyTheme()
        for ((view, _) in diffHeaders) Theme.applyTree(view)
        for ((view, _) in mermaids) view.applyTheme()
        for ((view, _) in attachments) view.applyTheme()
        for ((view, _) in media) view.applyTheme()
        for ((view, _) in stories) view.applyTheme()
        invalidate()
        for ((view, _) in scrollers) view.getChildAt(0)?.invalidate()
    }

    override fun shouldDelayChildPressedState() = false

    @Suppress("unused")
    private val clock = SystemClock::uptimeMillis
}
