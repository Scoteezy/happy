package com.slopus.happy.ui.stories

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import com.slopus.happy.R
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.stories.StoryCube
import com.slopus.happy.layout.stories.StoryReactionBurst
import com.slopus.happy.layout.stories.StoryViewerDrag
import com.slopus.happy.layout.stories.StoryViewerLayout
import com.slopus.happy.layout.storiesViewerGround
import com.slopus.happy.stories.StoriesRow
import com.slopus.happy.stories.StoriesSnapshot
import com.slopus.happy.stories.Story
import com.slopus.happy.stories.StoryFeed
import com.slopus.happy.stories.StoryFeedAuthor
import com.slopus.happy.stories.StoryFrame
import com.slopus.happy.stories.StoryOverlay
import com.slopus.happy.stories.StorySource
import com.slopus.happy.stories.StoryStatus
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.density
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The full-screen story viewer (happy-ios `StoryViewerViewController`), as
 * Telegram's: one author at a time, one progress segment per frame of their
 * stories, a tap on the left third back and elsewhere forward, a hold
 * pausing it with the chrome hidden, a sideways drag turning a cube to the
 * next or previous author, and a drag down closing it. Seeing a story's
 * first frame marks it seen at once. It lies over the whole window, as
 * Telegram for Android lays its story viewer; [StoriesViewerPresenter] puts
 * it there.
 */
class StoryViewerController(
    context: Context,
    private val session: StoriesSession,
    feed: StoryFeed,
    authorIndex: Int,
    storyId: String? = null,
) : ManualLayout(context) {
    /** The person asked for a story's conversation (their own). */
    var onOpenConversation: ((StorySource) -> Unit)? = null
    /** The viewer went away. */
    var onClose: (() -> Unit)? = null
    /** The viewer starts going away, back into what it opened from. */
    var onClosing: (() -> Unit)? = null
    /** The circle the viewer opened from. */
    var origin: View? = null
    /** The circle of an author by key, in the row it opened from. */
    var originFor: ((String) -> View?)? = null

    val closingOrigin: View?
        get() {
            val find = originFor ?: return origin
            return authors.getOrNull(authorIndex)?.let { find(it.key) }
        }

    private var authors: MutableList<StoryFeedAuthor> = feed.authors.filter { it.storyIds.isNotEmpty() }.toMutableList()
    private val stories: MutableMap<String, Story> = (feed.stories + session.snapshot.stories).toMutableMap()
    var authorIndex: Int
        private set
    var position = 0
        private set
    var page = StoryViewerPageView(context)
        private set
    private var neighbor = StoryViewerPageView(context)
    private var watch: Job? = null
    private var lastTick = 0L
    private var elapsedMs = 0.0
    private val pauses = HashSet<Pause>()
    private var drag: Drag = Drag.None
        set(value) {
            field = value
            if (value is Drag.None) applyPending()
        }
    /** A change that arrived mid-turn, shown once the cube settles. */
    private var pendingSnapshot: StoriesSnapshot? = null
    private var cube: CubeAnimation? = null
    private val picker = StoryReactionPicker(context)
    private var held = false
    /** The story asked for, kept among its author's while the viewer is open. */
    private val opened: String? = storyId
    private var tooltip: StoriesTooltipView? = null
    private var burst: StoryReactionBurstView? = null
    private val scope = MainScope()
    private var finished = false
    var isClosing = false
        private set
    private var presented = false

    private enum class Pause { Hold, Drag, Sheet, Background, Picker, Appearing, Caption, Tooltip }

    /**
     * A recording's sound, as Telegram's viewer keeps it while open: at
     * first as the phone's ringer has it, then off or on, past the ringer,
     * by the sound button or a volume key.
     */
    enum class Audio { Ambient, Off, On }

    var audio = Audio.Ambient
        private set

    private sealed class Drag {
        data object None : Drag()
        data class Sideways(val next: Int) : Drag()
        data object Down : Drag()
    }

    /** The turn between two authors, run a frame at a time. */
    private data class CubeAnimation(val from: Double, val to: Double, val start: Long, val duration: Double, val target: Int, val acted: Boolean)

    /** The last few things the viewer did, for the harness to report. */
    val trail = ArrayList<String>()

    val isPaused: Boolean get() = pauses.isNotEmpty()

    private fun note(step: String) {
        trail += step
        if (trail.size > 8) trail.removeAt(0)
    }

    init {
        val key = feed.authors.getOrNull(authorIndex)?.key
        this.authorIndex = authors.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: 0
        authors.getOrNull(this.authorIndex)?.let { author ->
            position = startPosition(author)
            if (storyId != null) {
                val index = frames(author).indexOfFirst { it.first.id == storyId }
                if (index >= 0) position = index
            }
        }
        setBackgroundColor(Theme.color(Palette.storiesViewerGround))
        clipChildren = false
        isFocusable = true
        isFocusableInTouchMode = true
        tag = "story-viewer"
        for (page in listOf(neighbor, page)) {
            addView(page)
            wire(page)
        }
        neighbor.visibility = GONE
        picker.visibility = GONE
        picker.onPick = { pickReaction(it) }
        addView(picker)
    }

    /** Called once it is in the window: it starts, as `viewDidLoad` and `viewDidAppear`. */
    fun start() {
        session.store.hold(true)
        held = true
        watch = session.watch { apply(it) }
        showCurrent()
        startTicking()
        requestFocus()
    }

    fun setBackgrounded(background: Boolean) = pause(Pause.Background, background)

    private fun finish() {
        if (finished) return
        finished = true
        ticking = false
        holdRunnable?.let { removeCallbacks(it) }
        page.canvas.release()
        neighbor.canvas.release()
        watch?.cancel()
        watch = null
        scope.cancel()
        if (held) {
            held = false
            session.store.hold(false)
        }
    }

    override fun layoutSubviews(width: Double, height: Double) {
        for (page in listOf(page, neighbor)) {
            if (page.rotationY == 0f && page.translationY == 0f && page.scaleX == 1f) page.setFrame(Rect(0.0, 0.0, width, height))
        }
        if (picker.visibility == VISIBLE) placePicker()
        sheet?.setFrame(Rect(0.0, 0.0, width, height))
    }

    /** The insets the pages keep clear. */
    var safeArea: LayoutInsets = LayoutInsets.Zero
        set(value) {
            field = value
            page.safeArea = value
            neighbor.safeArea = value
        }

    private fun wire(page: StoryViewerPageView) {
        // The caption opened in full holds the story still, as Telegram's.
        page.canvas.onCaptionExpanded = { open -> pause(Pause.Caption, open) }
        page.closeButton.setOnClickListener { dismissViewer() }
        page.moreButton.setOnClickListener { moreTapped() }
        page.viewsButton.setOnClickListener { viewsTapped() }
        page.heartButton.setOnClickListener { heartTapped() }
        page.heartButton.setOnLongClickListener { heartHeld(); true }
        page.soundButton.setOnClickListener { soundTapped() }
        page.canvas.onAudioKnown = { updateSound() }
        page.canvas.onReady = {
            if (page === this.page) {
                lastTick = SystemClock.uptimeMillis()
                if (pauses.isEmpty()) page.canvas.play()
            }
        }
        page.canvas.onVideoEnd = { if (page === this.page) forward(acted = false) }
    }

    // What is shown

    private fun frames(author: StoryFeedAuthor): List<Pair<Story, StoryFrame>> =
        author.storyIds.mapNotNull { stories[it] }.filter { it.status == StoryStatus.Posted }.flatMap { story -> story.frames.map { story to it } }

    private fun startPosition(author: StoryFeedAuthor): Int {
        val start = StoriesRow.start(author, stories)
        val earlier = author.storyIds.take(start).mapNotNull { stories[it] }.filter { it.status == StoryStatus.Posted }
        return earlier.sumOf { it.frames.size }
    }

    private val currentFrames: List<Pair<Story, StoryFrame>>
        get() = authors.getOrNull(authorIndex)?.let { frames(it) } ?: emptyList()

    val current: Pair<Story, StoryFrame>? get() = currentFrames.getOrNull(position)

    private val now: Long get() = System.currentTimeMillis()

    private fun showCurrent() {
        burst?.let { removeView(it) }
        tooltip?.dismiss()
        val author = authors.getOrNull(authorIndex) ?: return
        val frames = frames(author)
        if (frames.isEmpty()) return
        position = position.coerceIn(0, frames.size - 1)
        page.show(author, frames, position, session.media, now)
        began()
    }

    /** The page now shows [position]: its time starts, it is seen, and the next is read. */
    private fun began() {
        val author = authors.getOrNull(authorIndex) ?: return
        val frames = frames(author)
        val (story, _) = frames.getOrNull(position) ?: return
        elapsedMs = 0.0
        lastTick = SystemClock.uptimeMillis()
        if (author.isSelf) {
            // The view count moves without an event.
            if (frames.indexOfFirst { it.first.id == story.id } == position) session.store.refresh(story.id)
        } else {
            session.store.seen(story.id)
        }
        if (pauses.isEmpty()) page.canvas.play()
        preload(frames, position)
        updateSound()
    }

    // Sound

    private val ringerSilent: Boolean
        get() = (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.ringerMode != AudioManager.RINGER_MODE_NORMAL

    /** The button and the players as the frame shown and [audio] have them. */
    private fun updateSound() {
        for (page in listOf(page, neighbor)) {
            val muted = audio == Audio.Off || (audio == Audio.Ambient && ringerSilent)
            page.canvas.isMuted = muted
            val video = page.canvas.shownFrame?.kind == StoryFrame.Kind.Video
            page.setSound(
                when {
                    !video -> StoryViewerPageView.Sound.None
                    page.canvas.hasAudio == false -> StoryViewerPageView.Sound.Silent
                    muted -> StoryViewerPageView.Sound.Off
                    else -> StoryViewerPageView.Sound.On
                },
            )
        }
    }

    /** A volume key turns the sound on, as Telegram's does. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (audio != Audio.On && !pauses.contains(Pause.Background) && !isClosing && current?.second?.kind == StoryFrame.Kind.Video && page.canvas.hasAudio != false) {
                audio = Audio.On
                note("sound:on")
                updateSound()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun soundTapped() {
        if (tooltip != null) return
        if (page.canvas.hasAudio == false) return showNoSound()
        val muted = audio == Audio.Off || (audio == Audio.Ambient && ringerSilent)
        audio = if (muted) Audio.On else Audio.Off
        note("sound:${if (audio == Audio.On) "on" else "off"}")
        updateSound()
    }

    /** Telegram's "This video has no sound" under the button, holding the story until a touch, which it takes. */
    private fun showNoSound() {
        val button = page.soundButton
        val tooltip = StoriesTooltipView(context, context.getString(R.string.stories_video_has_no_sound), takesTouch = true, dark = true)
        tooltip.point(Point(px(button.left + button.width / 2) + 1, px(button.bottom) - 10))
        tooltip.onDismiss = {
            this.tooltip = null
            pause(Pause.Tooltip, false)
        }
        addView(tooltip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        tooltip.layout(0, 0, width, height)
        tooltip.show()
        this.tooltip = tooltip
        pause(Pause.Tooltip, true)
        note("sound:silent")
    }

    /** The next frame's picture, read while this one shows. */
    private fun preload(frames: List<Pair<Story, StoryFrame>>, position: Int) {
        val media = session.media
        val pixels = StoryCanvasView.pixels(page.layout.canvas.size)
        val next = frames.getOrNull(position + 1) ?: authors.getOrNull(authorIndex + 1)?.let { frames(it).firstOrNull() } ?: return
        val (story, frame) = next
        if (frame.kind != StoryFrame.Kind.Image || !frame.available) return
        scope.launch { media.frame(story.id, frame, pixels) }
    }

    private fun applyPending() {
        if (cube != null || drag !is Drag.None) return
        val pending = pendingSnapshot ?: return
        pendingSnapshot = null
        apply(pending)
    }

    private fun apply(snapshot: StoriesSnapshot) {
        if (!snapshot.isOn) return dismissViewer()
        // Authors don't change under a turning cube.
        if (cube != null || drag !is Drag.None) {
            pendingSnapshot = snapshot
            return
        }
        val shown = current
        stories.putAll(snapshot.stories)
        // Authors keep their order while the viewer is open; their stories follow.
        snapshot.feed?.let { feed ->
            for ((index, author) in authors.withIndex()) {
                val fresh = feed.authors.firstOrNull { it.key == author.key } ?: continue
                // The story opened stays, even one that left the feed.
                val ids = author.storyIds.filter { it in fresh.storyIds || it == opened } + fresh.storyIds.filter { it !in author.storyIds }
                authors[index] = fresh.copy(storyIds = ids)
            }
        }
        for ((index, author) in authors.withIndex()) {
            authors[index] = author.copy(storyIds = author.storyIds.filter { stories[it]?.status != StoryStatus.Deleted && it !in snapshot.gone })
        }
        if (authorIndex !in authors.indices) return dismissViewer()
        val frames = currentFrames
        if (frames.isEmpty()) {
            // The author's last story went: on to the next, or close.
            authors.removeAt(authorIndex)
            if (authorIndex !in authors.indices) return dismissViewer()
            position = startPosition(authors[authorIndex])
            return showCurrent()
        }
        val index = shown?.let { (story, frame) -> frames.indexOfFirst { it.first.id == story.id && it.second.id == frame.id } } ?: -1
        if (index >= 0) {
            val restart = index != position
            position = index
            val elapsed = elapsedMs
            page.show(authors[authorIndex], frames, position, session.media, now)
            if (!restart) elapsedMs = elapsed
        } else {
            showCurrent()
        }
    }

    // Time

    private var ticking = false
        set(value) {
            if (field == value) return
            field = value
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            if (value) Choreographer.getInstance().postFrameCallback(frameCallback)
        }

    private val frameCallback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            tick(frameTimeNanos / 1_000_000)
            if (ticking) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startTicking() {
        lastTick = SystemClock.uptimeMillis()
        ticking = true
    }

    private fun tick(time: Long) {
        val dt = max(0L, time - lastTick)
        lastTick = time
        cube?.let { return stepCube(it, time) }
        if (pauses.isNotEmpty() || !page.canvas.isReady) return
        val (_, frame) = current ?: return
        val video = page.canvas.videoProgress
        val progress = if (video != null) video else {
            // A picture, or a recording that couldn't play: its own clock.
            elapsedMs += dt
            elapsedMs / (frame.showMs * showScale)
        }
        page.setProgress(progress, position)
        if (progress >= 1) forward(acted = false)
    }

    private fun pause(reason: Pause, on: Boolean) {
        val was = pauses.isEmpty()
        if (on) pauses += reason else pauses -= reason
        if (was && pauses.isNotEmpty()) page.canvas.pause()
        if (!was && pauses.isEmpty()) {
            lastTick = SystemClock.uptimeMillis()
            page.canvas.play()
        }
    }

    // Moving

    private fun forward(acted: Boolean) {
        note(if (acted) "forward" else "timeout")
        val count = currentFrames.size
        if (position + 1 < count) {
            position += 1
            if (acted) StoriesHaptics.play(StoriesHaptics.Moment.Advance, this)
            showCurrent()
        } else if (authorIndex + 1 < authors.size) {
            turn(authorIndex + 1, acted)
        } else {
            dismissViewer()
        }
    }

    private fun back(acted: Boolean) {
        note("back")
        if (position > 0) {
            position -= 1
            if (acted) StoriesHaptics.play(StoriesHaptics.Moment.Advance, this)
            showCurrent()
        } else if (authorIndex > 0) {
            turn(authorIndex - 1, acted)
        } else {
            // The first frame of all starts again.
            page.setProgress(0.0, position)
            elapsedMs = 0.0
            page.canvas.restart()
        }
    }

    /** Turns the cube to another author, from a tap or the end of a frame. */
    private fun turn(index: Int, acted: Boolean) {
        note("turn")
        burst?.let { removeView(it) }
        tooltip?.dismiss()
        page.canvas.setCaptionExpanded(false, animated = false)
        if (cube != null || index !in authors.indices) return
        prepareNeighbor(index)
        val direction = if (index > authorIndex) -1.0 else 1.0
        cube = CubeAnimation(0.0, direction, SystemClock.uptimeMillis(), 0.35, index, acted)
        pause(Pause.Drag, true)
    }

    private fun prepareNeighbor(index: Int) {
        val author = authors[index]
        neighbor.visibility = VISIBLE
        neighbor.setFrame(Rect(0.0, 0.0, px(width), px(height)))
        neighbor.show(author, frames(author), startPosition(author), session.media, now)
        neighbor.canvas.pause()
        updateSound()
        drag = Drag.Sideways(index)
    }

    /** [offset] is how far the current author has turned, -1…1 (negative: towards the next). */
    private fun placeCube(offset: Double, target: Int) {
        val side = if (target > authorIndex) 1.0 else -1.0
        cube(page, offset)
        cube(neighbor, offset + side)
    }

    /** A side of the cube turned [s] of the way, about the edge it shares with the next side. */
    private fun cube(view: StoryViewerPageView, fraction: Double) {
        val s = max(-1.0, min(1.0, fraction))
        val w = view.width.toFloat()
        // Turned about its right edge going left, its left edge going right.
        view.pivotX = if (s <= 0) w else 0f
        view.pivotY = view.height / 2f
        view.cameraDistance = (StoryCube.perspective * density * resources.displayMetrics.densityDpi / 72.0).toFloat()
        view.translationX = (s * w).toFloat()
        view.rotationY = Math.toDegrees(StoryCube.angle(s)).toFloat()
        view.shade.alpha = (abs(s) * 0.5).toFloat()
    }

    private fun resetCube(view: StoryViewerPageView) {
        view.rotationY = 0f
        view.translationX = 0f
        view.shade.alpha = 0f
    }

    private fun stepCube(animation: CubeAnimation, time: Long) {
        val t = min(1.0, (time - animation.start) / 1000.0 / animation.duration)
        val eased = 1 - (1 - t).pow(3)
        val offset = animation.from + (animation.to - animation.from) * eased
        placeCube(offset, animation.target)
        if (t < 1) return
        cube = null
        if (animation.to == 0.0) {
            settleCube()
        } else {
            authorIndex = animation.target
            val turned = neighbor
            neighbor = page
            page = turned
            page.bringToFront()
            picker.bringToFront()
            // The page turned to already shows the author's first frame.
            position = startPosition(authors[authorIndex])
            settleCube()
            if (animation.acted) StoriesHaptics.play(StoriesHaptics.Moment.Advance, this)
            began()
        }
    }

    private fun settleCube() {
        for (page in listOf(page, neighbor)) {
            resetCube(page)
            page.setFrame(Rect(0.0, 0.0, px(width), px(height)))
        }
        neighbor.canvas.stop()
        neighbor.visibility = GONE
        drag = Drag.None
        pause(Pause.Drag, false)
    }

    // Gestures

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var pressMoved = false
    private var pressHolding = false
    private var holdRunnable: Runnable? = null
    /** The touch began where the press logic doesn't run: a button, the picker, the open caption. */
    private var onControl = false
    /** On the open caption's panel, where a drag still closes the viewer. */
    private var onCaption = false
    private var panning = false
    private var tracking = false

    private fun descendantAt(group: ViewGroup, x: Float, y: Float): View? {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != VISIBLE) continue
            val local = floatArrayOf(x - child.left, y - child.top)
            val matrix = android.graphics.Matrix()
            child.matrix.invert(matrix)
            matrix.mapPoints(local)
            if (local[0] < 0 || local[1] < 0 || local[0] >= child.width || local[1] >= child.height) continue
            if (child.isClickable) return child
            if (child is ViewGroup) descendantAt(child, local[0], local[1])?.let { return it }
        }
        return null
    }

    /** Whether a gesture may begin now. */
    private val gesturesOpen: Boolean get() = cube == null && !isClosing && sheet == null && presented

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                downX = ev.x
                downY = ev.y
                panning = false
                tracking = gesturesOpen
                // A tooltip takes the touch that puts it away.
                if (tooltip != null) {
                    tracking = false
                    return false
                }
                val target = descendantAt(this, ev.x, ev.y)
                onControl = target != null
                onCaption = target != null && page.canvas.captionExpanded && page.canvas.ownsCaptionPanel(target)
                if (!onControl && tracking) pressBegan()
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (!tracking || panning) return panning
                if (shouldPan(ev)) {
                    panBegan(ev)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!panning && tracking && !onControl) pressEnded(ev, ev.actionMasked == MotionEvent.ACTION_UP)
                tracking = false
            }
        }
        return false
    }

    /** A drag past the slop begins a pan, as UIPanGestureRecognizer's `shouldBegin` decides. */
    private fun shouldPan(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dx) <= slop && abs(dy) <= slop) return false
        // On the open caption's text, only where its text doesn't scroll.
        if (onCaption) {
            val local = floatArrayOf(downX - page.canvas.left, downY - page.canvas.top)
            if (!page.canvas.passesDrag(local[0], local[1])) return false
        } else if (onControl) {
            return false
        }
        if (abs(dx) > abs(dy)) return authors.size > 1
        // Down closes; up, on one's own story, shows who has seen it.
        return dy > 0 || authors.getOrNull(authorIndex)?.isSelf == true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Nothing under the touch took it: the press is the viewer's.
                if (!tracking) {
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    downX = event.x
                    downY = event.y
                    tracking = gesturesOpen && tooltip == null
                    onControl = false
                    onCaption = false
                    panning = false
                    if (tracking) pressBegan()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                if (!panning) {
                    if (shouldPan(event)) panBegan(event) else return true
                }
                panChanged(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val up = event.actionMasked == MotionEvent.ACTION_UP
                if (panning) panEnded(event, up) else if (tracking) pressEnded(event, up)
                tracking = false
                panning = false
                tracker?.recycle()
                tracker = null
            }
        }
        return true
    }

    /** Down: a hold past a moment pauses with the chrome hidden. */
    private fun pressBegan() {
        if (picker.visibility == VISIBLE) {
            hidePicker()
            pressMoved = true
            return
        }
        pressMoved = false
        pressHolding = false
        pause(Pause.Hold, true)
        holdRunnable?.let { removeCallbacks(it) }
        val hold = Runnable {
            if (!pressMoved) {
                pressHolding = true
                page.chromeHidden = true
            }
        }
        holdRunnable = hold
        postDelayed(hold, 200)
    }

    /** Up: a tap by thirds or on an overlay, unless it moved or held. */
    private fun pressEnded(event: MotionEvent, up: Boolean) {
        holdRunnable?.let { removeCallbacks(it) }
        page.chromeHidden = false
        pause(Pause.Hold, false)
        if (!up || pressMoved || pressHolding) return
        tap(Point(px(event.x - page.canvas.left), px(event.y - page.canvas.top)), Point(px(event.x), px(event.y)))
    }

    private fun tap(canvasPoint: Point, viewPoint: Point) {
        val canvas = page.canvas
        val inside = canvasPoint.x >= 0 && canvasPoint.y >= 0 && canvasPoint.x < px(canvas.width) && canvasPoint.y < px(canvas.height)
        if (inside) {
            when (val overlay = canvas.tappable(canvasPoint)) {
                is StoryOverlay.Link -> return openLink(overlay.url)
                is StoryOverlay.Reaction -> if (authors.getOrNull(authorIndex)?.isSelf == false) return react(overlay.emoji, canvas.overlayView(overlay.id))
                else -> {}
            }
        }
        when (page.layout.tap(viewPoint.x)) {
            StoryViewerLayout.Tap.Back -> back(acted = true)
            StoryViewerLayout.Tap.Forward -> forward(acted = true)
        }
    }

    private fun panBegan(event: MotionEvent) {
        panning = true
        pressMoved = true
        holdRunnable?.let { removeCallbacks(it) }
        if (!onControl) {
            page.chromeHidden = false
            pause(Pause.Hold, false)
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        pause(Pause.Drag, true)
        // Any drag folds the caption, so no fold is left holding the next author still.
        page.canvas.setCaptionExpanded(false)
        val dx = event.x - downX
        val dy = event.y - downY
        if (abs(dx) > abs(dy)) {
            drag = Drag.Sideways(-1)
        } else {
            drag = Drag.Down
            // Only the canvas shrinks away; the page's own black goes with the ground behind it.
            page.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun panChanged(event: MotionEvent) {
        val tx = px(event.x - downX)
        val ty = px(event.y - downY)
        when (val drag = drag) {
            Drag.Down -> {
                // With the finger, shrinking towards 0.6 by a screen, as Telegram's.
                val state = StoryViewerDrag.state(ty, px(height))
                page.translationY = dp(state.offset)
                page.pivotX = page.width / 2f
                page.pivotY = page.height / 2f
                page.scaleX = state.scale.toFloat()
                page.scaleY = state.scale.toFloat()
                setGround(state.groundAlpha)
            }
            is Drag.Sideways -> {
                val offset = tx / max(1.0, px(width))
                val wanted = if (offset < 0) authorIndex + 1 else authorIndex - 1
                if (wanted !in authors.indices) {
                    // Turned back past the start towards nothing: the neighbor goes, and the page gives a little.
                    if (drag.next in authors.indices) {
                        neighbor.visibility = GONE
                        resetCube(neighbor)
                        neighbor.canvas.stop()
                        this.drag = Drag.Sideways(wanted)
                    }
                    cube(page, offset * 0.15)
                    return
                }
                if (drag.next != wanted) prepareNeighbor(wanted)
                placeCube(offset, wanted)
            }
            Drag.None -> {}
        }
    }

    private fun panEnded(event: MotionEvent, up: Boolean) {
        tracker?.computeCurrentVelocity(1000)
        val vx = px(tracker?.xVelocity ?: 0f)
        val vy = px(tracker?.yVelocity ?: 0f)
        val tx = px(event.x - downX)
        val ty = px(event.y - downY)
        when (val drag = drag) {
            Drag.Down -> {
                val own = authors.getOrNull(authorIndex)?.isSelf == true
                val release = if (up) StoryViewerDrag.release(ty, vy, own) else StoryViewerDrag.Release.Stay
                if (release == StoryViewerDrag.Release.Close) {
                    note("drag-close")
                    dismissViewer()
                } else if (page.translationY == 0f && page.scaleX == 1f) {
                    // Up only: nothing moved, nothing to spring back.
                    page.setBackgroundColor(Theme.color(Palette.storiesViewerGround))
                    setGround(1.0)
                    this.drag = Drag.None
                    pause(Pause.Drag, false)
                    if (release == StoryViewerDrag.Release.Viewers) viewsTapped()
                } else {
                    val fromY = page.translationY
                    val fromScale = page.scaleX
                    val fromGround = ground
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 300
                        interpolator = android.view.animation.DecelerateInterpolator(1.6f)
                        addUpdateListener {
                            val t = it.animatedValue as Float
                            page.translationY = fromY * (1 - t)
                            val s = fromScale + (1 - fromScale) * t
                            page.scaleX = s
                            page.scaleY = s
                            setGround(fromGround + (1 - fromGround) * t)
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                page.setBackgroundColor(Theme.color(Palette.storiesViewerGround))
                                this@StoryViewerController.drag = Drag.None
                                pause(Pause.Drag, false)
                            }
                        })
                        start()
                    }
                    if (release == StoryViewerDrag.Release.Viewers) viewsTapped()
                }
            }
            is Drag.Sideways -> {
                val width = max(1.0, px(this.width))
                val offset = max(-1.0, min(1.0, tx / width))
                if (drag.next !in authors.indices) {
                    page.animate().rotationY(0f).translationX(0f).setDuration(250).start()
                    page.shade.animate().alpha(0f).setDuration(250).start()
                    this.drag = Drag.None
                    pause(Pause.Drag, false)
                    return
                }
                val goes = up && (abs(offset) > 0.3 || abs(vx) > 600) && (offset < 0) == (drag.next > authorIndex)
                val to = if (goes) (if (drag.next > authorIndex) -1.0 else 1.0) else 0.0
                cube = CubeAnimation(offset, to, SystemClock.uptimeMillis(), 0.3 * max(0.3, abs(to - offset)), drag.next, true)
            }
            Drag.None -> pause(Pause.Drag, false)
        }
    }

    private var ground = 1.0

    /** The black behind the story now, 0…1. */
    val groundAlpha: Float get() = ground.toFloat()

    private fun setGround(alpha: Double) {
        ground = alpha
        setBackgroundColor((Theme.color(Palette.storiesViewerGround) and 0x00FFFFFF) or ((alpha.coerceIn(0.0, 1.0) * 255).toInt() shl 24))
    }

    // Acts

    fun dismissViewer() {
        if (isClosing || !presented) return
        isClosing = true
        ticking = false
        burst?.let { removeView(it) }
        tooltip?.dismiss()
        sheet?.dismiss(animated = false)
        page.canvas.pause()
        neighbor.canvas.pause()
        onClosing?.invoke()
        onClosing = null
        StoriesViewerPresenter.dismiss(this) {
            finish()
            onClose?.invoke()
            onClose = null
        }
    }

    /** The presentation finished opening: touches go to the viewer from now. */
    fun opened() {
        presented = true
    }

    /**
     * Reacts with [emoji], or takes it back when it's the reaction already.
     * Reacting throws the emoji out around [source] (the bubble tapped, or
     * the heart), as Telegram's reaction effect does.
     */
    private fun react(emoji: String, source: View? = null) {
        note("react")
        val (story, _) = current ?: return
        val next = if (story.myReaction == emoji) null else emoji
        page.setHeart(next)
        StoriesHaptics.play(StoriesHaptics.Moment.React, this)
        if (next != null) page.canvas.overlayViewMatching(next)?.let { bounce(it) }
        bounce(page.heartButton)
        burst?.let { removeView(it) }
        // On the page, so it goes with it as it turns, shrinks or closes.
        if (next != null && !isClosing) {
            val around = source ?: page.heartButton
            val frame = frameIn(around, page)
            val shown = StoryReactionBurstView(context, next, StoryReactionBurst(frame))
            page.addView(shown)
            shown.setFrame(Rect(0.0, 0.0, px(page.width), px(page.height)))
            shown.play()
            burst = shown
            note("burst")
        }
        session.store.react(story.id, next)
    }

    /** [view]'s frame in [ancestor]'s dp, through the overlays' turns and scales. */
    private fun frameIn(view: View, ancestor: View): Rect {
        val box = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        var current: View = view
        while (current !== ancestor) {
            current.matrix.mapRect(box)
            box.offset(current.left.toFloat(), current.top.toFloat())
            current = current.parent as? View ?: break
        }
        return Rect(px(box.left), px(box.top), px(box.width()), px(box.height()))
    }

    private fun bounce(view: View) {
        val base = view.scaleX
        view.animate().scaleX(base * 1.25f).scaleY(base * 1.25f).setDuration(120).withEndAction {
            view.animate().scaleX(base).scaleY(base).setDuration(300).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }.start()
    }

    private fun heartTapped() {
        if (picker.visibility == VISIBLE) return hidePicker()
        val (story, _) = current ?: return
        react(story.myReaction ?: StoriesStrings.HEART)
    }

    private fun heartHeld() {
        StoriesHaptics.play(StoriesHaptics.Moment.React, this)
        showPicker()
    }

    private fun showPicker() {
        picker.chosen = current?.first?.myReaction
        picker.visibility = VISIBLE
        placePicker()
        picker.alpha = 0f
        picker.scaleX = 0.8f
        picker.scaleY = 0.8f
        pause(Pause.Picker, true)
        picker.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
    }

    private fun placePicker() {
        val heart = page.heartButton
        val heartFrame = Rect(px(heart.left), px(heart.top), px(heart.width), px(heart.height))
        val size = picker.sizeThatFits(px(width))
        val x = min(px(width) - size.width - 8, max(8.0, heartFrame.maxX - size.width))
        picker.setFrame(Rect(x, heartFrame.minY - size.height - 8, size.width, size.height))
        picker.pivotX = picker.width / 2f
        picker.pivotY = picker.height / 2f
    }

    private fun hidePicker() {
        pause(Pause.Picker, false)
        picker.animate().alpha(0f).setDuration(150).withEndAction { picker.visibility = GONE }.start()
    }

    private fun pickReaction(emoji: String) {
        hidePicker()
        current ?: return
        react(emoji)
    }

    private fun openLink(url: String) {
        note("link")
        pause(Pause.Sheet, true)
        scope.launch {
            val choice = Alerts.choose(context, url, options = listOf(context.getString(R.string.stories_open), context.getString(R.string.stories_copy_link)))
            pause(Pause.Sheet, false)
            when (choice) {
                0 -> Alerts.open(context, url)
                1 -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText(url, url))
            }
        }
    }

    private fun moreTapped() {
        val (story, _) = current ?: return
        pause(Pause.Sheet, true)
        val options = mutableListOf<String>()
        val acts = mutableListOf<() -> Unit>()
        val destructive = mutableSetOf<Int>()
        val source = story.source
        if (source != null && onOpenConversation != null) {
            options += context.getString(R.string.stories_open_conversation)
            acts += {
                val open = onOpenConversation
                pause(Pause.Sheet, false)
                dismissViewer()
                open?.invoke(source)
            }
        }
        if (authors.getOrNull(authorIndex)?.isSelf == true) {
            destructive += options.size
            options += context.getString(R.string.stories_delete)
            acts += { confirmDelete(story) }
        }
        scope.launch {
            val choice = Alerts.choose(context, null, options = options, destructive = destructive)
            if (choice == null) pause(Pause.Sheet, false) else acts[choice]()
        }
    }

    private fun confirmDelete(story: Story) {
        scope.launch {
            val delete = Alerts.confirm(
                context, context.getString(R.string.stories_delete_story), context.getString(R.string.stories_delete_detail),
                confirmTitle = context.getString(R.string.stories_delete), cancelTitle = context.getString(R.string.stories_cancel), destructive = true,
            )
            if (!delete) return@launch pause(Pause.Sheet, false)
            // Still while the deletion is under way; the viewer then moves on.
            try {
                session.store.delete(story.id)
                StoriesHaptics.play(StoriesHaptics.Moment.Delete, this@StoryViewerController)
                pause(Pause.Sheet, false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                StoriesHaptics.play(StoriesHaptics.Moment.Refused, this@StoryViewerController)
                Alerts.show(context, context.getString(R.string.stories_delete_failed)) { pause(Pause.Sheet, false) }
            }
        }
    }

    private var sheet: StoriesViewersSheet? = null

    private fun viewsTapped() {
        if (sheet != null) return
        val (story, _) = current ?: return
        note("viewers")
        pause(Pause.Sheet, true)
        val list = StoriesViewersSheet(context, session, story)
        list.onClose = {
            sheet = null
            pause(Pause.Sheet, false)
        }
        sheet = list
        addView(list)
        list.setFrame(Rect(0.0, 0.0, px(width), px(height)))
        list.present()
    }

    /** Back closes what is over the story first, then the viewer. */
    fun back(): Boolean {
        sheet?.let { it.dismiss(); return true }
        if (picker.visibility == VISIBLE) { hidePicker(); return true }
        if (page.canvas.captionExpanded) { page.canvas.setCaptionExpanded(false); return true }
        dismissViewer()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        finish()
    }

    companion object {
        /** How long frames show, times their own length (the harness slows them). */
        var showScale = 1.0

        /** The opening's curve (`StoryOpeningLayout.Metrics.curve`). */
        val openingCurve = PathInterpolator(0.38f, 0.7f, 0.125f, 1f)
    }
}

/**
 * Telegram's reaction effect drawn from [StoryReactionBurst] (happy-ios
 * `StoryReactionBurstView`): copies of the emoji thrown out from what was
 * tapped, rising and fading, once. Touches go through it.
 */
class StoryReactionBurstView(context: Context, emoji: String, private val burst: StoryReactionBurst) : View(context) {
    private val picture: Bitmap = StoryOverlayView.emojiBitmap(emoji, StoryReactionBurst.Metrics.particle)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var start = 0L
    private val move = PathInterpolator(0.23f, 1f, 0.32f, 1f)

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        tag = "story-reaction-burst"
    }

    override fun onTouchEvent(event: MotionEvent?) = false

    fun play() {
        start = SystemClock.uptimeMillis()
        val longest = burst.particles.maxOf { it.delay + it.duration }
        postInvalidateOnAnimation()
        postDelayed({ (parent as? ViewGroup)?.removeView(this) }, (longest * 1000).toLong())
    }

    override fun onDraw(canvas: Canvas) {
        val elapsed = (SystemClock.uptimeMillis() - start) / 1000.0
        val side = dp(StoryReactionBurst.Metrics.particle)
        var running = false
        for (particle in burst.particles) {
            val t = ((elapsed - particle.delay) / particle.duration)
            if (t < 0) { running = true; continue }
            if (t > 1) continue
            running = true
            val p = move.getInterpolation(t.toFloat())
            val x = particle.start.x + (particle.end.x - particle.start.x) * p
            val y = particle.start.y + (particle.end.y - particle.start.y) * p
            // Scale through 0.2, peak, 0.55 of the peak at 0, 0.3, 1; turning a step each key.
            val scale = if (t < 0.3) 0.2 + (particle.peakScale - 0.2) * (t / 0.3) else particle.peakScale + (particle.peakScale * 0.55 - particle.peakScale) * ((t - 0.3) / 0.7)
            val rotation = particle.rotation * (if (t < 0.3) t / 0.3 else 1 + (t - 0.3) / 0.7)
            // Opacity through 0, 1, 1, 0 at 0, 0.12, 0.6, 1.
            val opacity = when {
                t < 0.12 -> t / 0.12
                t < 0.6 -> 1.0
                else -> 1 - (t - 0.6) / 0.4
            }
            paint.alpha = (opacity * 255).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(dp(x), dp(y))
            canvas.rotate(Math.toDegrees(rotation).toFloat())
            canvas.scale(scale.toFloat(), scale.toFloat())
            canvas.drawBitmap(picture, null, RectF(-side / 2, -side / 2, side / 2, side / 2), paint)
            canvas.restore()
        }
        if (running) postInvalidateOnAnimation()
    }
}

/** The heart's other emoji, in a capsule over the bar (happy-ios `StoryReactionPicker`). */
class StoryReactionPicker(context: Context) : ManualLayout(context) {
    var onPick: ((String) -> Unit)? = null
    var chosen: String? = null
        set(value) {
            field = value
            for ((button, emoji) in buttons.zip(StoriesStrings.reactions)) button.chosen = emoji == value
        }
    private val buttons = StoriesStrings.reactions.map { emoji ->
        EmojiButton(context, emoji).apply {
            tag = "story-reaction:$emoji"
            contentDescription = emoji
            setOnClickListener { onPick?.invoke(emoji) }
        }
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        for (button in buttons) addView(button)
        elevation = dp(12.0)
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        tag = "story-reaction-picker"
    }

    fun sizeThatFits(width: Double): com.slopus.happy.layout.Size =
        com.slopus.happy.layout.Size(min(width - 16, buttons.size * ITEM + 2 * INSET), ITEM + 2 * INSET)

    override fun onDraw(canvas: Canvas) {
        // UIBlurEffect `systemThickMaterialDark`'s tint: Android draws the material without the blur.
        fill.color = 0xF22C2C2E.toInt()
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, fill)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val item = min(ITEM, (width - 2 * INSET) / max(1, buttons.size))
        for ((index, button) in buttons.withIndex()) button.setFrame(Rect(INSET + index * item, INSET, item, ITEM))
    }

    /** One emoji, ringed when it's the reaction already. */
    class EmojiButton(context: Context, emoji: String) : View(context) {
        private val image = StoryOverlayView.emojiBitmap(emoji, 30.0)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        var chosen = false
            set(value) { field = value; invalidate() }

        init {
            isClickable = true
            isFocusable = true
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            alpha = if (pressed) 0.6f else 1f
        }

        override fun onDraw(canvas: Canvas) {
            if (chosen) {
                paint.color = 0x33FFFFFF
                val r = dp(ITEM / 2)
                canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            }
            val w = image.width.toFloat()
            canvas.drawBitmap(image, (width - w) / 2, (height - image.height) / 2f, paint)
        }
    }

    companion object {
        const val ITEM = 44.0
        const val INSET = 6.0
    }
}
