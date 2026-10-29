package com.slopus.happy.ui.stories

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewOutlineProvider
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.stories.StoryOpeningLayout
import com.slopus.happy.layout.stories.StoryViewerLayout
import com.slopus.happy.layout.storiesAvatarFill
import com.slopus.happy.layout.storiesAvatarText
import com.slopus.happy.layout.storiesProgressFill
import com.slopus.happy.layout.storiesProgressTrack
import com.slopus.happy.layout.storiesViewerGround
import com.slopus.happy.layout.storiesViewerText
import com.slopus.happy.stories.StoriesRow
import com.slopus.happy.stories.Story
import com.slopus.happy.stories.StoryFeedAuthor
import com.slopus.happy.stories.StoryFrame
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.density
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame

/**
 * One author in the viewer (happy-ios `StoryViewerPageView`), as Telegram's
 * story item set lays it out: the canvas with its corners rounded, the
 * progress strip and the author over its top, close and more beside them,
 * and the bar under it with the heart, or on one's own story how many have
 * seen it.
 */
class StoryViewerPageView(context: Context) : ManualLayout(context) {
    val canvas = StoryCanvasView(context)
    val closeButton = StoryIconButton(context).apply { icon = Icon.ionicons("close"); iconSize = 26.0 }
    val moreButton = StoryIconButton(context).apply { icon = Icon.materialIcons("more-horiz"); iconSize = 26.0 }
    val heartButton = StoryIconButton(context).apply { iconSize = 28.0 }
    val viewsButton = StoryViewsButton(context)
    /** A recording's sound, on or off, at the top beside the more button. */
    val soundButton = StoryIconButton(context)
    private val topFade = GradientView(context, intArrayOf(0x66000000, 0x00000000), null)
    private val strip = SegmentStrip(context)
    private val avatar = AvatarCircle(context, 13.0)
    private val nameLabel = StoryLineLabel(context, 14.0, semibold, Theme.color(Palette.storiesViewerText))
    private val timeLabel = StoryLineLabel(context, 13.0, null, (Theme.color(Palette.storiesViewerText) and 0x00FFFFFF) or 0xB3000000.toInt())
    /** Shades the side of the cube turned away. */
    val shade = View(context)
    private val chrome: List<View> get() = listOf(strip, avatar, nameLabel, timeLabel, closeButton, moreButton, soundButton, heartButton, viewsButton)

    var author: StoryFeedAuthor? = null
        private set
    var layout = StoryViewerLayout(Size.Zero, LayoutInsets.Zero)
        private set

    /** The safe area the page keeps clear (the status bar is hidden, so mostly the cutout and navigation). */
    var safeArea = LayoutInsets.Zero
        set(value) { if (field != value) { field = value; requestLayout() } }

    init {
        setBackgroundColor(Theme.color(Palette.storiesViewerGround))
        clipChildren = false
        canvas.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(StoryViewerLayout.Metrics.cornerRadius))
            }
        }
        canvas.clipToOutline = true
        addView(canvas)
        canvas.addView(topFade)
        addView(strip)
        addView(avatar)
        for (label in listOf(nameLabel, timeLabel)) {
            label.paint.setShadowLayer(2 * density, 0f, 0f, 0x40000000)
            addView(label)
        }
        closeButton.contentDescription = context.getString(R.string.stories_close)
        closeButton.tag = "story-close"
        moreButton.tag = "story-more"
        addView(closeButton)
        addView(moreButton)
        addView(viewsButton)
        soundButton.tag = "story-sound"
        soundButton.visibility = GONE
        addView(soundButton)
        heartButton.tag = "story-heart"
        heartButton.contentDescription = context.getString(R.string.stories_react)
        addView(heartButton)
        viewsButton.tag = "story-views"
        shade.setBackgroundColor(Color.BLACK)
        shade.alpha = 0f
        shade.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(shade)
        tag = "story-viewer-page"
    }

    // Showing

    /** Draws [author] at frame [position] of [frames] (their stories' frames in order). */
    fun show(author: StoryFeedAuthor, frames: List<Pair<Story, StoryFrame>>, position: Int, media: StoriesMedia?, now: Long) {
        val changedAuthor = this.author?.key != author.key
        this.author = author
        nameLabel.text = if (author.isSelf) context.getString(R.string.stories_my_story) else author.name
        avatar.initials = StoriesRow.initials(author.name)
        if (strip.count != frames.size || changedAuthor) strip.count = frames.size
        for (index in 0 until strip.count) strip.setProgress(index, if (index < position) 1.0 else 0.0)
        if (position !in frames.indices) return
        val (story, frame) = frames[position]
        canvas.show(story, frame, media)
        timeLabel.text = story.postedAt?.let { StoriesStrings.ago(context, now - it) } ?: ""
        heartButton.visibility = if (author.isSelf) GONE else VISIBLE
        viewsButton.visibility = if (author.isSelf) VISIBLE else GONE
        // Others' stories offer only their conversation, when there is one.
        moreButton.visibility = if (!author.isSelf && story.source == null) GONE else VISIBLE
        setHeart(story.myReaction)
        val views = story.viewCount ?: 0
        viewsButton.title = if (views == 0) context.getString(R.string.stories_no_viewers) else StoriesStrings.views(context, views)
        stateDescription = "${story.id}#$position"
        requestLayout()
    }

    /** The current segment's fill, 0…1. */
    fun setProgress(progress: Double, position: Int) = strip.setProgress(position, progress)

    /**
     * What the sound button shows: nothing on a picture; on a recording its
     * sound on or off, or, with no sound at all, off and dimmed.
     */
    enum class Sound { None, On, Off, Silent }

    fun setSound(sound: Sound) {
        val hidden = sound == Sound.None
        if ((soundButton.visibility == GONE) != hidden) {
            soundButton.visibility = if (hidden) GONE else VISIBLE
            requestLayout()
        }
        soundButton.glyph = StoryEditorIcons.sound(sound == Sound.On)
        // Telegram's dims a recording without sound to half.
        soundButton.tint = if (sound == Sound.Silent) 0x80FFFFFF.toInt() else Color.WHITE
        soundButton.contentDescription = context.getString(if (sound == Sound.On) R.string.stories_mute else R.string.stories_unmute)
        soundButton.stateDescription = when (sound) {
            Sound.None -> null
            Sound.On -> "on"
            Sound.Off -> "off"
            Sound.Silent -> "silent"
        }
    }

    fun setHeart(reaction: String?) {
        if (reaction != null && reaction != StoriesStrings.HEART) {
            heartButton.icon = null
            heartButton.image = StoryOverlayView.emojiBitmap(reaction, 28.0)
        } else {
            heartButton.image = null
            heartButton.icon = Icon.ionicons(if (reaction == null) "heart-outline" else "heart")
        }
        heartButton.tint = if (reaction == null) Color.WHITE else HEART_RED
        heartButton.stateDescription = reaction
    }

    /** Holding the frame hides everything over it but the frame. */
    var chromeHidden = false
        set(value) {
            if (field == value) return
            field = value
            canvas.chromeHidden = value
            for (view in chrome) view.animate().alpha(if (value) 0f else 1f).setDuration(200).start()
            topFade.alpha = if (value) 0f else 1f
        }

    // Layout

    override fun layoutSubviews(width: Double, height: Double) {
        layout = StoryViewerLayout(Size(width, height), LayoutInsets(safeArea.top, 0.0, safeArea.bottom, 0.0))
        // Placed by bounds and centre: opening and closing move them by their transforms.
        canvas.setFrame(layout.canvas)
        canvas.pivotX = canvas.width / 2f
        canvas.pivotY = canvas.height / 2f
        topFade.setFrame(Rect(0.0, 0.0, layout.canvas.width, 96.0))
        strip.setFrame(Rect(0.0, 0.0, width, height))
        strip.segments = StoryViewerLayout.segments(strip.count, layout.strip)
        avatar.setFrame(layout.avatar)
        val lineHeight = 17.0
        // Without a more button the sound button takes its place.
        val titleWidth = layout.titleWidth(withSound = soundButton.visibility == VISIBLE && moreButton.visibility == VISIBLE)
        nameLabel.setFrame(Rect(layout.titleOrigin.x, layout.titleOrigin.y, titleWidth, lineHeight))
        timeLabel.setFrame(Rect(layout.titleOrigin.x, layout.titleOrigin.y + lineHeight, titleWidth, lineHeight))
        closeButton.setFrame(layout.close)
        moreButton.setFrame(layout.more)
        soundButton.setFrame(if (moreButton.visibility == GONE) layout.more else layout.sound)
        heartButton.setFrame(layout.heart)
        viewsButton.setFrame(layout.views)
        shade.setFrame(Rect(0.0, 0.0, width, height))
        for (view in listOf(avatar, heartButton, viewsButton)) {
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
        }
    }

    // Opening and closing

    private var opening = false
    private val windowRect = RectF()
    private var windowRadius = 0f
    private val clip = Path()

    /**
     * From here until [endOpening], the story shows only through a window
     * (and the bar under it), as it opens out of what was tapped or closes
     * back into it ([StoryOpeningLayout]).
     */
    fun beginOpening() {
        if (opening) return
        opening = true
        setWindow(layout.canvas, StoryViewerLayout.Metrics.cornerRadius)
    }

    fun endOpening() {
        opening = false
        canvas.scaleX = 1f; canvas.scaleY = 1f; canvas.translationX = 0f; canvas.translationY = 0f
        avatar.scaleX = 1f; avatar.scaleY = 1f; avatar.translationX = 0f; avatar.translationY = 0f
        invalidate()
    }

    private fun setWindow(rect: Rect, radius: Double) {
        windowRect.set(dp(rect.minX), dp(rect.minY), dp(rect.maxX), dp(rect.maxY))
        windowRadius = dp(radius)
        invalidate()
    }

    /**
     * The window, the story in it and the author's avatar at [t] of the way
     * from the source (0) to their places (1).
     */
    fun placeOpening(opening: StoryOpeningLayout, t: Float) {
        val from = opening.window
        val to = layout.canvas
        fun mix(a: Double, b: Double) = a + (b - a) * t
        setWindow(Rect(mix(from.minX, to.minX), mix(from.minY, to.minY), mix(from.width, to.width), mix(from.height, to.height)), mix(opening.windowRadius, StoryViewerLayout.Metrics.cornerRadius))
        apply(canvas, opening.canvas, t)
        opening.avatar?.let { apply(avatar, it, t) }
    }

    private fun apply(view: View, placement: StoryOpeningLayout.Placement, t: Float) {
        val scale = (placement.scale + (1 - placement.scale) * t).toFloat()
        view.scaleX = scale
        view.scaleY = scale
        view.translationX = dp(placement.offset.x * (1 - t))
        view.translationY = dp(placement.offset.y * (1 - t))
    }

    /**
     * The controls over the story, and the shade behind them, at [shown]
     * presence; the author's avatar too when it doesn't fly to the source.
     */
    fun showOpeningControls(shown: Float, opening: StoryOpeningLayout) {
        val views = mutableListOf(strip, closeButton, moreButton, soundButton, nameLabel, timeLabel)
        if (opening.avatar == null) views += avatar
        for (view in views) view.alpha = shown
        topFade.alpha = shown
    }

    /** The bar under the story: [risen] of the way from below the screen to its place. */
    fun placeOpeningBar(opening: StoryOpeningLayout, risen: Float) {
        for (view in listOf(heartButton, viewsButton)) view.translationY = dp(opening.barDrop * (1 - risen))
    }

    fun showOpeningBar(shown: Float) {
        for (view in listOf(heartButton, viewsButton)) view.alpha = shown
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!opening) return super.dispatchDraw(canvas)
        clip.reset()
        clip.addRoundRect(windowRect, windowRadius, windowRadius, Path.Direction.CW)
        clip.addRect(0f, dp(layout.canvas.maxY), width.toFloat(), height.toFloat(), Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    override fun draw(canvas: Canvas) {
        if (!opening) return super.draw(canvas)
        // The page's own ground shows only through the window too.
        clip.reset()
        clip.addRoundRect(windowRect, windowRadius, windowRadius, Path.Direction.CW)
        clip.addRect(0f, dp(layout.canvas.maxY), width.toFloat(), height.toFloat(), Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        super.draw(canvas)
        canvas.restore()
    }

    companion object {
        /** The filled heart's red. */
        val HEART_RED = Color.rgb(255, (0.23 * 255).toInt(), (0.33 * 255).toInt())
        val semibold: Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, 600, false) else Typeface.DEFAULT_BOLD
        val medium: Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, 500, false) else Typeface.DEFAULT
    }
}

/** One frame's segments of the strip: a translucent track filled from the left. */
class SegmentStrip(context: Context) : View(context) {
    var count = 0
        set(value) { field = value; progress = DoubleArray(value); invalidate() }
    var segments: List<Rect> = emptyList()
        set(value) { field = value; invalidate() }
    private var progress = DoubleArray(0)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        track.color = Theme.color(Palette.storiesProgressTrack)
        fill.color = Theme.color(Palette.storiesProgressFill)
    }

    fun setProgress(index: Int, value: Double) {
        if (index !in progress.indices || progress[index] == value) return
        progress[index] = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for ((index, segment) in segments.withIndex()) {
            val r = dp(segment.height / 2)
            val box = RectF(dp(segment.minX), dp(segment.minY), dp(segment.maxX), dp(segment.maxY))
            canvas.drawRoundRect(box, r, r, track)
            val p = progress.getOrElse(index) { 0.0 }.coerceIn(0.0, 1.0)
            if (p <= 0) continue
            canvas.save()
            canvas.clipRect(box.left, box.top, box.left + box.width() * p.toFloat(), box.bottom)
            canvas.drawRoundRect(box, r, r, fill)
            canvas.restore()
        }
    }
}

/** An avatar without a photo: the fill and the initials, in a circle. */
class AvatarCircle(context: Context, private val initialsSize: Double) : View(context) {
    var initials: String = ""
        set(value) { field = value; invalidate() }
    var photo: Bitmap? = null
        set(value) { field = value; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = StoryViewerPageView.semibold }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f
        fill.color = Theme.color(Palette.storiesAvatarFill)
        canvas.drawCircle(width / 2f, height / 2f, r, fill)
        text.color = Theme.color(Palette.storiesAvatarText)
        text.textSize = (initialsSize * density).toFloat()
        val fm = text.fontMetrics
        canvas.drawText(initials, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2, text)
    }
}

/** One line of text at a fixed size, cut with an ellipsis (a UILabel of one line). */
class StoryLineLabel(context: Context, size: Double, typeface: Typeface?, color: Int, private val centered: Boolean = false) : View(context) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = (size * Theme.density).toFloat()
        this.typeface = typeface
        this.color = color
    }

    var text: String = ""
        set(value) { if (field != value) { field = value; contentDescription = value; invalidate() } }

    val textWidth: Float get() = paint.measureText(text)

    override fun onDraw(canvas: Canvas) {
        val shown = TextUtils.ellipsize(text, paint, width.toFloat(), TextUtils.TruncateAt.END)
        val fm = paint.fontMetrics
        val x = if (centered) (width - paint.measureText(shown, 0, shown.length)) / 2 else 0f
        canvas.drawText(shown, 0, shown.length, x, height / 2f - (fm.ascent + fm.descent) / 2, paint)
    }
}

/**
 * A borderless button holding one icon, glyph or picture, centred, that
 * dims as it's pressed (UIButton of type `.system` with a template image).
 */
open class StoryIconButton(context: Context) : View(context) {
    var icon: Icon? = null
        set(value) { field = value; invalidate() }
    var iconSize = 26.0
        set(value) { field = value; invalidate() }
    var glyph: StoryGlyph? = null
        set(value) { field = value; invalidate() }
    var image: Bitmap? = null
        set(value) { field = value; invalidate() }
    var tint: Int = Color.WHITE
        set(value) { field = value; invalidate() }
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        isClickable = true
        isFocusable = true
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alpha = if (pressed) 0.5f else 1f
    }

    override fun onDraw(canvas: Canvas) {
        val box = RectF(0f, 0f, width.toFloat(), height.toFloat())
        image?.let { bitmap ->
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            canvas.drawBitmap(bitmap, null, RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2), paint)
            return
        }
        glyph?.let { glyph ->
            val w = (glyph.width * density).toFloat()
            val h = (glyph.height * density).toFloat()
            glyph.draw(canvas, (width - w) / 2, (height - h) / 2, tint)
            return
        }
        val icon = icon ?: return
        val side = dp(iconSize)
        StoryOverlayView.drawIcon(canvas, icon, iconSize, RectF(box.centerX() - side / 2, box.centerY() - side / 2, box.centerX() + side / 2, box.centerY() + side / 2), tint, path)
    }
}

/** One's own story's "N views": the eye and the count from the bar's left. */
class StoryViewsButton(context: Context) : View(context) {
    var title: String = ""
        set(value) { field = value; contentDescription = value; invalidate() }
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15 * Theme.density
        typeface = StoryViewerPageView.medium
        color = Color.WHITE
    }
    private val path = Path()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alpha = if (pressed) 0.5f else 1f
    }

    override fun onDraw(canvas: Canvas) {
        val side = dp(20.0)
        val mid = height / 2f
        // The image's edge inset moves it 3 left, the title's 3 right.
        StoryOverlayView.drawIcon(canvas, Icon.ionicons("eye-outline"), 20.0, RectF(-dp(3.0), mid - side / 2, side - dp(3.0), mid + side / 2), Color.WHITE, path)
        val fm = paint.fontMetrics
        val x = side + dp(3.0)
        val shown = TextUtils.ellipsize(title, paint, width - x, TextUtils.TruncateAt.END)
        canvas.drawText(shown, 0, shown.length, x, mid - (fm.ascent + fm.descent) / 2, paint)
    }
}
