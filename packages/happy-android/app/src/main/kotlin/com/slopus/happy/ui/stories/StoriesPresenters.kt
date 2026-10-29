package com.slopus.happy.ui.stories

import android.animation.ValueAnimator
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.stories.StoriesRowLayout
import com.slopus.happy.layout.stories.StoryOpeningLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.storiesViewerGround
import com.slopus.happy.stories.Story
import com.slopus.happy.stories.StoryFeed
import com.slopus.happy.stories.StoryFeedAuthor
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.toLayoutInsets
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Opens the full-screen viewer on an author of the row (happy-ios
 * `StoriesViewerPresenter`), grown out of their circle, and closes it back
 * into the circle. The viewer lies over the activity's whole window, the
 * status bar hidden under it, as Telegram for Android shows stories; back
 * closes it.
 */
object StoriesViewerPresenter {
    private class Presented(val viewer: StoryViewerController, val root: ViewGroup, val back: OnBackPressedCallback?, val bars: WindowInsetsControllerCompat?, val lifecycle: DefaultLifecycleObserver?)

    private val shown = HashMap<StoryViewerController, Presented>()

    /** The viewer up over [root], if any. */
    fun presented(root: View): StoryViewerController? = shown.keys.firstOrNull { it.rootView === root.rootView }

    fun present(session: StoriesSession, feed: StoryFeed, authorIndex: Int, storyId: String? = null, host: View, origin: View?): StoryViewerController? {
        val root = host.rootView as? ViewGroup ?: return null
        if (shown.keys.any { it.rootView === root }) return null
        val viewer = StoryViewerController(host.context, session, feed, authorIndex, storyId)
        viewer.origin = origin
        val activity = host.context as? ComponentActivity
        val back = activity?.let {
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { viewer.back() }
            }.also { callback -> it.onBackPressedDispatcher.addCallback(callback) }
        }
        val bars = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val lifecycle = activity?.let {
            object : DefaultLifecycleObserver {
                override fun onPause(owner: LifecycleOwner) = viewer.setBackgrounded(true)
                override fun onResume(owner: LifecycleOwner) = viewer.setBackgrounded(false)
            }.also { observer -> it.lifecycle.addObserver(observer) }
        }
        shown[viewer] = Presented(viewer, root, back, bars, lifecycle)
        StoriesHaptics.play(StoriesHaptics.Moment.Open, host)
        root.addView(viewer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The pages keep clear of the cutout and the navigation, under a hidden status bar.
        val insets = ViewCompat.getRootWindowInsets(root)
        val safe = insets?.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.navigationBars())?.toLayoutInsets() ?: LayoutInsets.Zero
        viewer.safeArea = LayoutInsets(safe.top, 0.0, safe.bottom, 0.0)
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.statusBars())
        viewer.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        viewer.layout(0, 0, root.width, root.height)
        viewer.start()
        StoriesViewerTransition.open(viewer, root)
        return viewer
    }

    /**
     * Opens one posted story, from where it is drawn inline: among its
     * author's stories when the row has them, or on its own.
     */
    fun present(session: StoriesSession, story: Story, host: View, origin: View?): StoryViewerController? {
        val snapshot = session.snapshot
        val feed = snapshot.feed
        if (feed != null) {
            val index = feed.authors.indexOfFirst { story.id in it.storyIds }
            if (index >= 0) return present(session, feed.copy(stories = snapshot.stories), index, story.id, host, origin)
        }
        val mine = feed?.authors?.firstOrNull { it.isSelf }
        val isSelf = story.authorUserId == null || story.authorUserId == mine?.userId
        val known = if (isSelf) mine else feed?.authors?.firstOrNull { it.userId == story.authorUserId }
        val author = StoryFeedAuthor(
            userId = story.authorUserId, name = known?.name ?: (if (isSelf) host.context.getString(com.slopus.happy.R.string.stories_my_story) else ""),
            photo = known?.photo, thumbhash = known?.thumbhash, isSelf = isSelf,
            storyIds = listOf(story.id), unseenCount = 0, draftCount = null, latestPostedAt = story.postedAt,
        )
        return present(session, StoryFeed(listOf(author), mapOf(story.id to story), ""), 0, story.id, host, origin)
    }

    /** Closes [viewer] back into what it opened from; [done] once it's gone. */
    fun dismiss(viewer: StoryViewerController, done: () -> Unit) {
        val presented = shown[viewer] ?: return done()
        presented.back?.isEnabled = false
        StoriesViewerTransition.close(viewer, presented.root) {
            presented.back?.remove()
            presented.lifecycle?.let { (viewer.context as? ComponentActivity)?.lifecycle?.removeObserver(it) }
            presented.bars?.show(WindowInsetsCompat.Type.statusBars())
            presented.root.removeView(viewer)
            shown.remove(viewer)
            done()
        }
    }
}

/**
 * The viewer grows from what it opened from (happy-ios
 * `StoriesViewerTransition`), as Telegram's does ([StoryOpeningLayout]): the
 * story shows through the row's circle, or the story drawn inline with its
 * corners, which grows into the canvas. Closing goes back into it, or, with
 * that out of sight, lets it fall away.
 */
object StoriesViewerTransition {
    private val M = StoryOpeningLayout.Metrics

    /** [view]'s frame (its own [local] rect, dp) in [root]'s dp, through every transform between. */
    fun frame(view: View, root: View, local: Rect? = null): Rect? {
        if (!view.isAttachedToWindow || view.visibility != View.VISIBLE) return null
        val box = local?.let { RectF(dp(it.minX), dp(it.minY), dp(it.maxX), dp(it.maxY)) } ?: RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        var current: View = view
        while (current !== root) {
            current.matrix.mapRect(box)
            val parent = current.parent as? View ?: return null
            box.offset((current.left - parent.scrollX).toFloat(), (current.top - parent.scrollY).toFloat())
            current = parent
        }
        val frame = Rect(px(box.left), px(box.top), px(box.width()), px(box.height()))
        val bounds = Rect(0.0, 0.0, px(root.width), px(root.height))
        val visible = frame.maxX > bounds.minX && frame.minX < bounds.maxX && frame.maxY > bounds.minY && frame.minY < bounds.maxY
        return if (visible) frame else null
    }

    private fun opening(viewer: StoryViewerController, root: View, source: View?): StoryOpeningLayout? {
        source ?: return null
        val circle = source is StoriesCircleView
        val frame = frame(source, root, if (circle) StoriesRowLayout.item.avatar else null) ?: return null
        val radius = if (circle) frame.width / 2 else (source.getTag(com.slopus.happy.R.id.stories_corner_radius) as? Double ?: 0.0)
        return StoryOpeningLayout(frame, radius, circle, viewer.page.layout, px(root.height))
    }

    private fun run(duration: Double, interpolator: android.animation.TimeInterpolator = StoryViewerController.openingCurve, step: (Float) -> Unit, end: (() -> Unit)? = null) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = (duration * 1000).toLong()
            this.interpolator = interpolator
            addUpdateListener { step(it.animatedValue as Float) }
            if (end != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = end()
            })
            start()
        }
    }

    private fun ground(viewer: View, alpha: Float) {
        val base = Theme.color(Palette.storiesViewerGround) and 0x00FFFFFF
        viewer.setBackgroundColor(base or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24))
    }

    fun open(viewer: StoryViewerController, root: View) {
        val page = viewer.page
        val opening = opening(viewer, root, viewer.origin)
        ground(viewer, 0f)
        page.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val ease = AccelerateDecelerateInterpolator()
        if (opening != null) {
            // Out of the source, as Telegram's: the story through a window that
            // grows from it, the author's avatar leaving it for its corner, the
            // controls fading in, the bar rising from below.
            page.beginOpening()
            page.placeOpening(opening, 0f)
            page.showOpeningControls(0f, opening)
            page.placeOpeningBar(opening, 0f)
            page.showOpeningBar(0f)
            run(M.duration, step = { page.placeOpening(opening, it) }) {
                page.endOpening()
                page.setBackgroundColor(Theme.color(Palette.storiesViewerGround))
                viewer.opened()
            }
            run(M.controlsFade, ease, { page.showOpeningControls(it, opening) })
            run(M.barIn, step = { page.placeOpeningBar(opening, it) })
            run(M.groundIn, ease, {
                page.showOpeningBar(it)
                ground(viewer, it)
            })
        } else {
            page.pivotX = page.width / 2f
            page.pivotY = page.height / 2f
            run(M.duration, android.view.animation.DecelerateInterpolator(1.5f), {
                val s = 0.9f + 0.1f * it
                page.scaleX = s
                page.scaleY = s
                page.alpha = it
                ground(viewer, it)
            }) {
                page.setBackgroundColor(Theme.color(Palette.storiesViewerGround))
                viewer.opened()
            }
        }
    }

    fun close(viewer: StoryViewerController, root: View, done: () -> Unit) {
        val page = viewer.page
        page.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val opening = opening(viewer, root, viewer.closingOrigin)
        val ease = AccelerateDecelerateInterpolator()
        if (opening != null) {
            // Back into the source, from wherever a drag left it.
            page.beginOpening()
            val fromY = page.translationY
            val fromScale = page.scaleX
            run(M.duration, step = { t ->
                page.translationY = fromY * (1 - t)
                val s = fromScale + (1 - fromScale) * t
                page.scaleX = s
                page.scaleY = s
                page.placeOpening(opening, 1 - t)
            }) { done() }
            run(M.controlsFade, ease, { page.showOpeningControls(1 - it, opening) })
            run(M.barOut, step = {
                page.placeOpeningBar(opening, 1 - it)
                page.showOpeningBar(1 - it)
            })
            val from = viewer.groundAlpha
            run(M.groundOut, ease, { ground(viewer, from * (1 - it)) })
            return
        }
        // On from where a drag left it, never back up or larger.
        val height = px(root.height)
        val startY = px(page.translationY)
        val startScale = page.scaleX.toDouble()
        val y = max(height * 0.3, startY + height * 0.15)
        val s = min(0.85, hypot(startScale, 0.0) * 0.9)
        val from = viewer.groundAlpha
        page.pivotX = page.width / 2f
        page.pivotY = page.height / 2f
        run(M.duration, ease, { t ->
            page.translationY = dp(startY + (y - startY) * t)
            val scale = (startScale + (s - startScale) * t).toFloat()
            page.scaleX = scale
            page.scaleY = scale
            page.alpha = 1 - t
            ground(viewer, from * (1 - t))
        }) { done() }
    }
}
