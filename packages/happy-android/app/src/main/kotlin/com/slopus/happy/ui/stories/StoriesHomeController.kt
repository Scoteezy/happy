package com.slopus.happy.ui.stories

import android.content.Context
import android.os.SystemClock
import android.view.ViewGroup
import com.slopus.happy.R
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.stories.StoriesHeaderLayout
import com.slopus.happy.layout.stories.StoriesHeaderMotion
import com.slopus.happy.layout.stories.StoryViewerLayout
import com.slopus.happy.stories.StoriesRow
import com.slopus.happy.stories.StoriesRowItem
import com.slopus.happy.stories.StoriesSnapshot
import com.slopus.happy.stories.StoryFrame
import com.slopus.happy.ui.home.HomeHeaderView
import com.slopus.happy.ui.view.BounceScrollView
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Stories on Stem Cells home (happy-ios `StoriesHomeController`), in the
 * header as Telegram's chat list has them: open above the list, or closed
 * into the title as small avatars; opened by pulling the list down past 65
 * or tapping the avatars, and put away by scrolling the list up past 61 or
 * by seeing everything.
 *
 * Home starts with them open when there is something to look at, until
 * the person puts them away; that is remembered, and only a launch with a
 * story posted since opens them again. While home is up nothing else opens
 * them. Nothing here makes a story: that is done in a conversation.
 *
 * The open row takes 96 above the list ([inset]). The list's pull below its
 * closed top drives everything; the host hands its list over as [list] and
 * forwards its listener calls. The room above the list only changes when
 * the list is let go of or at rest, never under a finger or while it
 * glides, so the list never jumps.
 */
class StoriesHomeController(private val host: ViewGroup) {
    /**
     * How the room above the list changed: as the list was let go of or
     * came to rest (the list stays where it is), on its own as the stories
     * were seen or new ones came (the list follows, animated), or as home
     * first shows them (the list follows at once).
     */
    enum class Change { Held, Animated, Immediate }

    private val context: Context get() = host.context
    val view = StoriesHeaderView(host.context)
    var onInsetChange: ((Change) -> Unit)? = null
    /** The list the stories open above. */
    var list: BounceScrollView? = null

    /** A row is being carried: the list's own scrolling then is the carry's. */
    var carrying = false
        set(value) {
            val was = field
            field = value
            if (!value && was) listCameToRest()
        }

    /** Home shows something else (no computer, one too old): no stories. */
    var isSuppressed = false
        set(value) {
            if (field == value) return
            field = value
            // Home changed under the viewer: what it held is drawn now.
            val latest = held ?: snapshot
            held = null
            draw(latest)
        }

    private var header: HomeHeaderView? = null
    var session: StoriesSession? = null
        private set
    private var watch: Job? = null
    private var snapshot = StoriesSnapshot()
    private var items: List<StoriesRowItem> = emptyList()
    var unlocked = false
        private set
    /** The room above the list as the host last laid it out. */
    var inset = 0.0
        private set
    /** The drag began, and has stayed, with the list at its top, so pulling it opens the row. */
    private var expansionAllowed = false
    private var dragging = false
    private var motion: StoriesHeaderMotion? = null
    private var drawn = StoriesHeaderLayout.State(0.0, 0.0, 0.0)
    /** Something changed what should be open while the list was moving or the viewer was up. */
    private var settleWhenBack = false
    /** What came while the viewer was up, drawn as it closes back into the row. */
    private val viewerUp: Boolean get() = viewer != null
    private var viewer: StoryViewerController? = null
    private var held: StoriesSnapshot? = null
    /** A circle tapped whose story is still loading: the viewer opens once it's here, or after a few seconds regardless. */
    private var opening: Job? = null
    private var loadingCircle: StoriesCircleView? = null
    private val scope = MainScope()
    /** Whether the row starts open is still to be decided. */
    private var launching = false
    private var tooltip: StoriesTooltipView? = null
    private var tooltipCheck: Job? = null
    private var tooltipTip: Point? = null
    private val preferences = host.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    init {
        view.visibility = ViewGroup.GONE
        view.onOpen = { open(it) }
        view.onExpand = { expand() }
        view.onScroll = { layout() }
        view.onLayout = { layout() }
        view.onTick = { layout() }
    }

    /** Stories are on, somebody has one, and home is showing its list. */
    val isShown: Boolean get() = snapshot.isOn && items.isNotEmpty() && !isSuppressed

    /** The room the row wants: 96 open, 0 closed or not drawn. */
    private val wanted: Double get() = if (isShown && unlocked) StoriesHeaderLayout.Metrics.scrollHeight else 0.0

    /** How far the list sits below its top with the stories closed. */
    private val pull: Double
        get() {
            val list = list ?: return 0.0
            return -(list.contentOffset + list.insetTop - inset)
        }

    private val listAtRest: Boolean
        get() {
            val list = list ?: return true
            return !list.isDragging && !list.isDecelerating && !carrying
        }

    /** Puts the stories in [header], or takes them out with null. */
    fun install(header: HomeHeaderView?) {
        if (header === this.header) return
        this.header?.let { previous ->
            if (previous.accessory === view) {
                previous.accessory = null
                previous.accessoryTakes = null
                previous.accessoryOverhang = 0.0
                previous.titleX = null
                previous.backdropExtension = 0.0
            }
        }
        hideTooltip()
        this.header = header
        header?.accessory = view
        header?.accessoryTakes = { x, y -> view.takes(x, y) }
        header?.accessoryOverhang = StoriesHeaderLayout.Metrics.scrollHeight
        layout()
    }

    fun attach(stories: StoriesSession?) {
        watch?.cancel()
        watch = null
        stopOpening()
        hideTooltip()
        viewer = null
        held = null
        session = stories
        view.photos = null
        snapshot = StoriesSnapshot()
        // Another computer's stories start closed, and decide again.
        unlocked = false
        launching = stories != null
        motion = null
        settleWhenBack = false
        applyInset(Change.Immediate)
        if (stories != null) watch = stories.watch { apply(it) } else draw(StoriesSnapshot())
    }

    /** A snapshot from the session: held while the viewer is up. */
    private fun apply(next: StoriesSnapshot) {
        if (viewerUp) {
            held = next
            return
        }
        draw(next)
    }

    private fun draw(next: StoriesSnapshot) {
        val hadUnseen = StoriesRow.hasUnseen(items)
        snapshot = next
        items = if (next.isOn) StoriesRow.items(next.feed, context.getString(R.string.stories_my_story)) else emptyList()
        view.apply(items)
        view.visibility = if (isShown) ViewGroup.VISIBLE else ViewGroup.GONE
        val hasUnseen = StoriesRow.hasUnseen(items)
        if (!isShown) {
            stopOpening()
            hideTooltip()
            unlocked = false
            motion = null
            applyInset(Change.Immediate)
        } else if (hasUnseen != hadUnseen || settleWhenBack) {
            settle()
        }
        if (launching) openAtLaunch()
        if (isShown) lookForTooltip()
        layout()
    }

    /**
     * Opens the row as home first shows it, if it should, with the list at
     * rest at its top: at once before home is on screen, else as a tap on
     * the avatars would.
     */
    private fun openAtLaunch() {
        if (isShown && !unlocked && listAtRest && !dragging && !viewerUp && abs(pull) < 1 &&
            StoriesRow.opensAtLaunch(snapshot.feed, snapshot.stories, putAwayThrough)
        ) {
            launching = false
            change(true, haptic = false)
            val offScreen = !host.isAttachedToWindow || host.width == 0
            if (offScreen) {
                motion = null
                view.ticking = false
            }
            applyInset(if (offScreen) Change.Immediate else Change.Animated)
            return
        }
        if (snapshot.live) launching = false
    }

    private fun putAway(): JsonObject = runCatching {
        Json.parseToJsonElement(preferences.getString(PUT_AWAY_KEY, null) ?: "{}") as JsonObject
    }.getOrDefault(JsonObject(emptyMap()))

    /** The newest story's time when the person last put the row away here. */
    private val putAwayThrough: Long?
        get() {
            val account = session?.accountId ?: return null
            return (putAway()[account] as? JsonPrimitive)?.longOrNull
        }

    /** The person put the row away, or opened it: remembered for the next launch. */
    private fun remember(putAway: Boolean) {
        val account = session?.accountId ?: return
        val all = putAway().toMutableMap()
        if (putAway) all[account] = JsonPrimitive(StoriesRow.newest(snapshot.feed, snapshot.stories)) else all.remove(account)
        preferences.edit().putString(PUT_AWAY_KEY, JsonObject(all).toString()).apply()
    }

    /** Lays the list out for the room the row now wants. */
    private fun applyInset(change: Change) {
        if (inset == wanted) return
        inset = wanted
        onInsetChange?.invoke(change)
    }

    // The list

    fun listWillBeginDragging() {
        hideTooltip()
        launching = false
        dragging = true
        expansionAllowed = pull >= -1
    }

    fun listDidScroll() {
        if (!isShown || carrying) {
            layout()
            return
        }
        val pull = pull
        if (dragging && pull < -1) expansionAllowed = false
        val next = StoriesHeaderLayout.unlocked(unlocked, pull)
        // Opens only under a finger that began at the top; closes as the
        // list goes up, however it moves, a fling's glide too, with its tap
        // of feedback as Telegram's. The room follows on release.
        if (next != unlocked && (!next || (dragging && expansionAllowed))) {
            change(next, haptic = true)
            remember(putAway = !next)
        }
        layout()
    }

    /**
     * The finger lifts: the room above the list becomes what the row wants,
     * and a list let go of part way into the open row goes to it or past
     * it, never stopping half way. [velocity] is the list's, dp per ms,
     * positive scrolling on (the finger going up).
     */
    fun listWillEndDragging(velocity: Double, target: DoubleArray) {
        dragging = false
        val list = list ?: return
        if (!isShown) return
        val current = pull
        val closedTop = list.insetTop - inset
        var resting = -(target[0] + closedTop)
        // Let go above the top it had: it would have gone back to that top.
        val bouncing = current > inset && abs(resting - inset) < 1
        if (bouncing) resting = current
        // A list too short to scroll can't glide past its top: where a fling
        // up would have taken it, as a scroll view decelerates.
        if (unlocked && !bouncing && abs(resting - inset) < 1 && velocity > 0) {
            resting = min(resting, current - velocity * 499)
        }
        // A glide that will put the row away puts it away now, so the list
        // goes straight to its closed top rather than back to the row first.
        if (unlocked && resting <= StoriesHeaderLayout.Metrics.lockPull) {
            change(false, haptic = true)
            remember(putAway = true)
        }
        applyInset(Change.Held)
        resting = StoriesHeaderLayout.resting(unlocked, resting)
        // Never past the top the room now has.
        resting = min(resting, inset)
        target[0] = -(resting + (list.insetTop - inset))
    }

    fun listDidEndDragging(decelerate: Boolean) {
        dragging = false
        if (!decelerate) listCameToRest()
    }

    fun listDidEndDecelerating() = listCameToRest()

    /** The list stopped: what changed while it moved takes its room, and a settle that waited for it runs. */
    private fun listCameToRest() {
        if (!listAtRest) return
        if (pull < -1) expansionAllowed = false
        if (inset != wanted) {
            // Closing with the list in the open row's room: the list goes up.
            applyInset(if (wanted < inset && pull > 1) Change.Animated else Change.Held)
        }
        if (settleWhenBack) settle()
        layout()
    }

    // Acts

    private fun change(unlocked: Boolean, haptic: Boolean) {
        if (unlocked == this.unlocked) return
        this.unlocked = unlocked
        if (!unlocked) stopOpening() else hideTooltip()
        if (haptic) StoriesHaptics.play(if (unlocked) StoriesHaptics.Moment.HeaderOpen else StoriesHaptics.Moment.HeaderClose, view)
        motion = StoriesHeaderMotion(drawn, now(), opening = unlocked)
        view.ticking = true
    }

    /**
     * Closed once everything is seen, when the list is at rest and the
     * viewer gone, wherever the list is. Never opens: only the person does.
     */
    private fun settle() {
        if (!isShown || !unlocked || StoriesRow.hasUnseen(items)) {
            settleWhenBack = false
            return
        }
        if (dragging || !listAtRest || viewerUp) {
            settleWhenBack = true
            return
        }
        settleWhenBack = false
        change(false, haptic = false)
        applyInset(if (pull > 1) Change.Animated else Change.Held)
    }

    /** The avatars by the title were tapped: the row opens, the list moving down to make room. */
    private fun expand() {
        if (!isShown || unlocked) return
        launching = false
        change(true, haptic = false)
        remember(putAway = false)
        applyInset(Change.Animated)
    }

    private fun open(item: StoriesRowItem) {
        val session = session ?: return
        val feed = snapshot.feed ?: return
        if (opening != null || viewerUp) return
        val index = feed.authors.indexOfFirst { it.key == item.id }.takeIf { it >= 0 } ?: return
        // As Telegram's row does: the picture it opens on first, the
        // circle's ring turning to dashes if that takes more than a moment.
        val author = feed.authors[index]
        val stories = feed.stories + session.snapshot.stories
        val start = StoriesRow.start(author, stories)
        val story = author.storyIds.getOrNull(start)?.let { stories[it] }
        val frame = story?.frames?.firstOrNull()
        val circle = view.circle(item.id)
        if (story != null && frame != null && frame.kind == StoryFrame.Kind.Image && frame.available && circle != null) {
            // The size the viewer reads it at: full screen.
            val root = host.rootView
            val canvas = StoryViewerLayout(Size(px(root.width), px(root.height)), LayoutInsets.Zero).canvas.size
            val pixels = StoryCanvasView.pixels(canvas)
            val media = session.media
            if (media.cachedFrame(story.id, frame.id, pixels) == null) {
                loadingCircle = circle
                opening = scope.launch {
                    val ring = launch {
                        delay(200)
                        circle.isLoadingStory = true
                    }
                    withTimeoutOrNull(5_000) { media.frame(story.id, frame, pixels) }
                    ring.cancel()
                    circle.isLoadingStory = false
                    opening = null
                    if (isShown && unlocked) present(item)
                }
                return
            }
        }
        present(item)
    }

    /** The row closed or went away while a story loaded: no viewer after all. */
    private fun stopOpening() {
        opening?.cancel()
        opening = null
        loadingCircle?.isLoadingStory = false
        loadingCircle = null
    }

    private fun present(item: StoriesRowItem) {
        val session = session ?: return
        val feed = snapshot.feed ?: return
        val index = feed.authors.indexOfFirst { it.key == item.id }.takeIf { it >= 0 } ?: return
        if (viewerUp) return
        val viewer = StoriesViewerPresenter.present(session, feed, index, null, host, view.circle(item.id)) ?: return
        hideTooltip()
        launching = false
        this.viewer = viewer
        viewer.originFor = { key -> view.circle(key) }
        viewer.onClosing = { if (viewer === this.viewer) release() }
        viewer.onClose = {
            if (viewer === this.viewer) release()
            // Once the presentation has let go of it.
            host.post { if (settleWhenBack) settle() }
        }
    }

    // The tooltip

    /**
     * As Telegram's chat list does each time its stories come: a second
     * later, if the tip was never shown, the row is closed and someone else
     * has a story, it shows under the avatars by the title.
     */
    private fun lookForTooltip() {
        if (tooltip != null || tooltipCheck != null || preferences.getBoolean(TOOLTIP_SHOWN_KEY, false)) return
        tooltipCheck = scope.launch {
            delay(1_000)
            tooltipCheck = null
            showTooltip()
        }
    }

    private fun showTooltip() {
        val names = items.filter { !it.isSelf }.take(3).map { it.title }
        val header = header ?: return
        val container = header.parent as? ViewGroup ?: return
        val tip = tooltipTip ?: return
        if (tooltip != null || !isShown || unlocked || viewerUp || opening != null || names.isEmpty() || !listAtRest || dragging) return
        if (preferences.getBoolean(TOOLTIP_SHOWN_KEY, false) || !host.isAttachedToWindow) return
        // Over the header and everything else on the screen, so it sees the
        // first touch anywhere, the header's buttons too.
        val tooltip = StoriesTooltipView(context, StoriesStrings.tooltip(context, names))
        tooltip.point(Point(tip.x + px(header.left), tip.y + px(header.top)))
        tooltip.onDismiss = { if (this.tooltip === tooltip) this.tooltip = null }
        container.addView(tooltip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // A hand-laid-out container only places what it knows.
        tooltip.setFrame(Rect(0.0, 0.0, px(container.width), px(container.height)))
        tooltip.show()
        this.tooltip = tooltip
        preferences.edit().putBoolean(TOOLTIP_SHOWN_KEY, true).apply()
    }

    private fun hideTooltip() {
        tooltipCheck?.cancel()
        tooltipCheck = null
        tooltip?.dismiss()
    }

    /** The viewer goes: what came meanwhile is drawn, as it closes. */
    private fun release() {
        viewer = null
        held?.let {
            held = null
            draw(it)
        }
    }

    // Drawing

    private fun now(): Double = SystemClock.uptimeMillis() / 1000.0

    private fun layout() {
        val header = header
        if (header == null || header.accessory !== view) {
            view.ticking = false
            return
        }
        if (!isShown) {
            header.titleX = null
            header.backdropExtension = 0.0
            view.ticking = false
            return
        }
        val now = now()
        // Closed, a pull leans the avatars out only when it may open them.
        val shownPull = if (unlocked || (dragging && expansionAllowed)) pull else min(0.0, pull)
        val target = StoriesHeaderLayout.State.target(unlocked, shownPull)
        var state = target
        val motion = motion
        if (motion != null) {
            if (motion.isFinished(now)) {
                this.motion = null
                view.ticking = false
            } else {
                state = motion.state(target, now)
            }
        } else {
            view.ticking = false
        }
        drawn = state
        val width = px(header.width)
        val base = header.layout(width)
        val layout = StoriesHeaderLayout(
            StoriesHeaderLayout.Input(
                width = width, title = base.title, bandMinX = base.logoControl.maxX, bandMaxX = base.actions.minX,
                count = items.size, collapseStart = StoriesRow.collapseStart(items),
                scroll = px(view.pager.scrollX), state = state,
            ),
        )
        view.place(layout, unlocked, state.side)
        tooltipTip = layout.tooltipTip
        tooltip?.let { shown ->
            val tip = layout.tooltipTip
            if (tip != null) shown.point(Point(tip.x + px(header.left), tip.y + px(header.top))) else hideTooltip()
        }
        header.titleX = layout.titleX
        header.backdropExtension = StoriesHeaderLayout.Metrics.scrollHeight * max(0.0, min(1.0, state.max))
    }

    companion object {
        const val PREFERENCES = "stories"
        /** Telegram's one-time "Tap above to view stories from …", shown once ever. */
        const val TOOLTIP_SHOWN_KEY = "stories.feedTooltipShown"
        /** Per computer, the newest story's posting time when the person last put the row away. */
        const val PUT_AWAY_KEY = "stories.putAway"
    }
}
