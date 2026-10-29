package com.slopus.happy.ui.stories

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import com.slopus.happy.R
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.stories.StoryCardLayout
import com.slopus.happy.layout.storiesInlineTint
import com.slopus.happy.layout.storiesUnseenBottom
import com.slopus.happy.layout.storiesUnseenTop
import com.slopus.happy.layout.surfacePressed
import com.slopus.happy.stories.Story
import com.slopus.happy.stories.StoriesSnapshot
import com.slopus.happy.stories.StoryRefusal
import com.slopus.happy.stories.StoryStatus
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.min

/**
 * The screen whose computer's Stories an inline story reads, found up the
 * view tree from it, then on its activity (happy-ios `StoriesProviding`,
 * found up the responder chain).
 */
interface StoriesProviding {
    val stories: StoriesSession?
}

/**
 * A story drawn inline in a conversation (happy-ios `StoryInlineView`), as
 * `draft_story` presents it (happy-agent `API.md` "The inline draft"): its
 * first frame drawn by the viewer's own canvas, followed live. A draft has
 * Post and Edit under it, Post turning to a spinner while it posts; posted,
 * it reads "Posted" after a story ring and opens the viewer; deleted, a
 * mark and the words say so where it was, as Telegram marks a story no
 * longer there. Each change crossfades.
 */
class StoryInlineView(context: Context) : ManualLayout(context), ThemeAware {
    private val canvas = StoryCanvasView(context)
    private val placeholder = Placeholder(context)
    private val message = TextView(context)
    private val spinner = ProgressBar(context)
    private val postButton = TextView(context)
    private val editButton = TextView(context)
    private val status = TextView(context)
    private val ring = Ring(context)
    private val notice = GoneMark(context)
    private val postSpinner = ProgressBar(context)
    /** What was last drawn, so a change can crossfade. */
    private var drawnState: String? = null
    private var storyId: String? = null
    private var session: StoriesSession? = null
    private var watch: Job? = null
    private var story: Story? = null
    private var posting = false
    /** The person reading is the story's author, so a draft is theirs to post. */
    private var isAuthor = false
    /** Whether the store was asked for the story while it could answer. */
    private var asked = false
    private var wasLive = false
    private val scope = MainScope()

    /** What it shows, for tests and the harness's probe: `draft#2`, `posted#3`, `deleted`, `gone` or `loading`. */
    var state: String = "loading"
        private set

    init {
        val radius = StoryCardLayout.Metrics.cornerRadius
        canvas.outlineProvider = StoryEditorView.rounded(radius)
        canvas.clipToOutline = true
        canvas.visibility = GONE
        // The viewer grows out of it with its corners.
        canvas.setTag(R.id.stories_corner_radius, radius)
        canvas.setOnClickListener { canvasTapped() }
        addView(canvas)
        placeholder.outlineProvider = StoryEditorView.rounded(radius)
        placeholder.clipToOutline = true
        addView(placeholder)
        message.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        message.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        message.includeFontPadding = false
        placeholder.addView(message)
        placeholder.addView(spinner)
        notice.visibility = GONE
        placeholder.addView(notice)
        ring.visibility = GONE
        addView(ring)
        postSpinner.visibility = GONE
        addView(postSpinner)
        for ((button, title, id) in listOf(
            Triple(postButton, R.string.stories_post, "story-inline-post"),
            Triple(editButton, R.string.stories_edit, "story-inline-edit"),
        )) {
            button.text = context.getString(title)
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            button.typeface = StoryViewerPageView.semibold
            button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            button.includeFontPadding = false
            button.tag = id
            button.isClickable = true
            addView(button)
        }
        postButton.setOnClickListener { postTapped() }
        editButton.setOnClickListener { editTapped() }
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        status.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, 500, false)
        status.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        status.isSingleLine = true
        status.ellipsize = TextUtils.TruncateAt.END
        status.includeFontPadding = false
        status.tag = "story-inline-status"
        addView(status)
        applyTheme()
    }

    override fun applyTheme() {
        placeholder.setBackgroundColor(Theme.color(Palette.surfacePressed))
        val secondary = Theme.color(Palette.textSecondary)
        message.setTextColor(secondary)
        status.setTextColor(secondary)
        notice.tint = secondary
        // UIButton's system tint.
        val accent = Theme.color(Palette.storiesInlineTint)
        postButton.setTextColor(accent)
        editButton.setTextColor(if (editButton.isEnabled) accent else secondary)
        ring.invalidate()
    }

    /** Shows the story [id], or nothing. */
    fun apply(id: String?) {
        if (id == storyId) return
        storyId = id
        story = null
        posting = false
        contentDescription = null
        tag = id?.let { "story-inline:$it" }
        watch?.cancel()
        watch = null
        canvas.stop()
        // Another story, or none: nothing to crossfade from.
        drawnState = null
        postSpinner.visibility = GONE
        attach()
        if (watch == null) render(null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attach()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        watch?.cancel()
        watch = null
    }

    private fun attach() {
        if (watch != null || !isAttachedToWindow || storyId == null) return
        val session = provider() ?: return
        this.session = session
        asked = false
        watch = session.watch { render(it) }
    }

    /**
     * Asks the store for the story while it is missing, again each time
     * the connection comes back, so a read lost offline is made later.
     */
    private fun want(snapshot: StoriesSnapshot) {
        val storyId = storyId ?: return
        val session = session ?: return
        if (!snapshot.isOn || snapshot.stories[storyId] != null || storyId in snapshot.gone) return
        if (asked && (wasLive || !snapshot.live)) {
            wasLive = snapshot.live
            return
        }
        asked = true
        wasLive = snapshot.live
        session.store.want(storyId)
    }

    private fun provider(): StoriesSession? {
        var view: View? = this
        while (view != null) {
            if (view is StoriesProviding) return view.stories
            view = view.parent as? View
        }
        var context: Context? = context
        while (context != null) {
            if (context is StoriesProviding) return context.stories
            context = (context as? ContextWrapper)?.baseContext
        }
        return null
    }

    // Drawing

    private fun render(snapshot: StoriesSnapshot?) {
        val storyId = storyId ?: return
        if (snapshot != null) want(snapshot)
        val story = snapshot?.stories?.get(storyId)
        val off = snapshot?.let { it.availability == StoriesSnapshot.Availability.Off || it.availability == StoriesSnapshot.Availability.Unsupported } ?: false
        val gone = off || snapshot?.gone?.contains(storyId) == true
        this.story = story
        val shown = if (off) null else story?.takeIf { it.status != StoryStatus.Deleted }
        // In standalone mode there is no user ID, and every story is the person's.
        val mine = snapshot?.feed?.authors?.firstOrNull { it.isSelf }?.userId
        isAuthor = shown?.let { it.authorUserId == null || it.authorUserId == mine } ?: false
        canvas.visibility = if (shown == null) GONE else VISIBLE
        placeholder.visibility = if (shown != null) GONE else VISIBLE
        val frame = shown?.frames?.firstOrNull()
        if (shown != null && frame != null) canvas.show(shown, frame, session?.media, playsVideo = false)
        message.text = when {
            story?.status == StoryStatus.Deleted && !off -> context.getString(R.string.stories_deleted)
            gone -> context.getString(R.string.stories_unavailable)
            else -> null
        }
        val noMessage = message.text.isNullOrEmpty()
        spinner.visibility = if (noMessage && shown == null) VISIBLE else GONE
        notice.visibility = if (noMessage) GONE else VISIBLE
        val mayPost = shown?.status == StoryStatus.Draft && isAuthor
        postButton.visibility = if (!mayPost || posting) GONE else VISIBLE
        editButton.visibility = if (mayPost) VISIBLE else GONE
        editButton.isEnabled = !posting
        postSpinner.visibility = if (mayPost && posting) VISIBLE else GONE
        status.visibility = if (mayPost) GONE else VISIBLE
        status.text = when (shown?.status) {
            StoryStatus.Posted -> context.getString(R.string.stories_posted)
            StoryStatus.Draft -> context.getString(R.string.stories_draft)
            else -> null
        }
        ring.visibility = if (shown?.status == StoryStatus.Posted) VISIBLE else GONE
        applyTheme()
        // A story that changes state while in sight crossfades to it; the
        // placeholder drawn before the first snapshot is no state to leave.
        val next = if (mayPost && posting) "posting" else shown?.status?.wire ?: message.text?.toString() ?: "loading"
        val drawn = drawnState
        if (drawn != null && drawn != next && isAttachedToWindow) crossfade()
        if (snapshot != null) drawnState = next
        state = shown?.let { "${it.status.wire}#${it.artifactVersion}" }
            ?: if (story?.status == StoryStatus.Deleted && !off) "deleted" else if (gone) "gone" else "loading"
        contentDescription = shown?.caption
        requestLayout()
    }

    /** UIView.transition's cross dissolve: what was drawn fades out over what is drawn now. */
    private fun crossfade() {
        if (width <= 0 || height <= 0) return
        val before = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        draw(Canvas(before))
        val cover = View(context)
        cover.background = android.graphics.drawable.BitmapDrawable(resources, before)
        cover.isClickable = false
        val host = parent as? ViewGroup ?: return before.recycle()
        val index = host.indexOfChild(this)
        host.addView(cover, index + 1, ViewGroup.LayoutParams(width, height))
        cover.layout(left, top, right, bottom)
        cover.animate().alpha(0f).setDuration(200).withEndAction {
            host.removeView(cover)
            before.recycle()
        }.start()
    }

    override fun sizeThatFits(width: Double, height: Double) = StoryCardLayout.size(width)

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = StoryCardLayout(width)
        canvas.setFrame(layout.canvas)
        placeholder.setFrame(layout.canvas)
        notice.setFrame(layout.notice)
        val inner = Rect(0.0, 0.0, layout.canvas.width, layout.canvas.height)
        if (notice.visibility == GONE) {
            val inset = StoryCardLayout.Metrics.noticeInset
            message.gravity = Gravity.CENTER
            message.setFrame(Rect(inset, 0.0, inner.width - 2 * inset, inner.height))
        } else {
            val text = layout.noticeText
            message.measure(MeasureSpec.makeMeasureSpec(dp(text.width.toInt()), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            message.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            message.setFrame(Rect(text.minX, text.minY, text.width, min(text.height, ceil(px(message.measuredHeight)))))
        }
        val spin = 20.0
        spinner.setFrame(Rect((inner.width - spin) / 2, (inner.height - spin) / 2, spin, spin))
        postButton.setFrame(layout.post)
        postSpinner.setFrame(Rect(layout.post.minX, layout.post.midY - spin / 2, spin, spin))
        editButton.setFrame(layout.edit)
        ring.setFrame(layout.ring)
        status.setFrame(if (ring.visibility == GONE) layout.label else layout.postedLabel)
    }

    // Acts

    private fun canvasTapped() {
        val story = story ?: return
        val session = session ?: return
        when (story.status) {
            StoryStatus.Posted -> StoriesViewerPresenter.present(session, story, this, canvas)
            StoryStatus.Draft -> if (isAuthor) editTapped()
            StoryStatus.Deleted -> {}
        }
    }

    /** Posts the version shown; a refusal says why and the draft stays. */
    private fun postTapped() {
        val story = story ?: return
        val session = session ?: return
        if (story.status != StoryStatus.Draft || !isAuthor || posting) return
        posting = true
        render(session.snapshot)
        val id = story.id
        scope.launch {
            val refusal = try {
                session.store.post(id, story.artifactVersion)
                null
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                context.getString(if (StoryRefusal.of(error)?.isConflict == true) R.string.stories_newer_version else R.string.stories_post_failed)
            }
            posted(id, refusal)
        }
    }

    private fun posted(id: String, refusal: String?) {
        if (storyId != id || !isAttachedToWindow) return
        StoriesHaptics.play(if (refusal == null) StoriesHaptics.Moment.Post else StoriesHaptics.Moment.Refused, this)
        posting = false
        session?.let { render(it.snapshot) }
        if (refusal != null) Alerts.show(context, refusal)
    }

    private fun editTapped() {
        val story = story ?: return
        val session = session ?: return
        if (story.status != StoryStatus.Draft || !isAuthor || posting) return
        StoriesEditorPresenter.present(session, story, this)
    }

    // Marks

    private class Placeholder(context: Context) : ManualLayout(context) {
        override fun layoutSubviews(width: Double, height: Double) {}
    }

    /** The row's unseen ring, small: a posted story's. */
    private class Ring(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        override fun onDraw(canvas: Canvas) {
            val line = dp(2.0)
            paint.strokeWidth = line
            paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Theme.color(Palette.storiesUnseenTop), Theme.color(Palette.storiesUnseenBottom), Shader.TileMode.CLAMP)
            canvas.drawOval(RectF(line / 2, line / 2, width - line / 2, height - line / 2), paint)
        }
    }

    /** A story no longer there: a seen ring broken into dashes, struck through. */
    private class GoneMark(context: Context) : View(context) {
        var tint = 0
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val size = width.toFloat()
            paint.color = tint
            paint.strokeWidth = dp(2.0)
            val inset = dp(3.0)
            val circle = RectF(inset, inset, size - inset, size - inset)
            val circumference = (Math.PI * circle.width()).toFloat()
            val gap = dp(4.0)
            paint.pathEffect = DashPathEffect(floatArrayOf(circumference / 12 - gap, gap), 0f)
            canvas.drawOval(circle, paint)
            paint.pathEffect = null
            canvas.drawLine(size * 0.28f, size * 0.28f, size * 0.72f, size * 0.72f, paint)
        }
    }
}
