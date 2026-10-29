package com.slopus.happy.ui.stories

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.slopus.happy.R
import com.slopus.happy.direct.ClientId
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Point
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.stories.StoryEditorLayout
import com.slopus.happy.layout.stories.StorySnapper
import com.slopus.happy.layout.stories.StoryTextEntryLayout
import com.slopus.happy.layout.storiesEditorAngle
import com.slopus.happy.layout.storiesEditorGuide
import com.slopus.happy.layout.storiesEditorHandle
import com.slopus.happy.layout.storiesEditorPost
import com.slopus.happy.stories.CaptionEdit
import com.slopus.happy.stories.Story
import com.slopus.happy.stories.StoryCanvas
import com.slopus.happy.stories.StoryDraft
import com.slopus.happy.stories.StoryFrame
import com.slopus.happy.stories.StoryLinkStyle
import com.slopus.happy.stories.StoryOverlay
import com.slopus.happy.stories.StoryRefusal
import com.slopus.happy.stories.StoryStatus
import com.slopus.happy.stories.StoryTextFill
import com.slopus.happy.stories.StoryTextStyle
import com.slopus.happy.stories.StoryTransform
import com.slopus.happy.stories.story
import com.slopus.happy.stories.upload
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.density
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The story editor (happy-ios `StoryEditorViewController`), laid out and
 * handled as Telegram's story editor: the frame full width on a 9:16 canvas
 * with round corners, the caption over its foot, and under it back, the text
 * and sticker tools in one pill, and Post. Overlays are placed by finger
 * (drag, pinch, turn, the handles of the selected one), snap to the centre
 * lines, the edges and 45° with the line shown, and go when dropped on the
 * bin that replaces the controls while one is dragged. The frames button
 * opens the frames. Device media is uploaded once, on save; nothing is
 * posted until Post. iOS's glass is a dark fill with a rim here.
 */
@SuppressLint("ViewConstructor")
class StoryEditorView(
    context: Context,
    private val session: StoriesSession,
    draft: StoryDraft,
    local: Map<String, StoryLocalMedia> = emptyMap(),
) : ManualLayout(context) {
    var draft: StoryDraft = draft
        private set
    private var local: Map<String, StoryLocalMedia> = local
    val canvas = StoryCanvasView(context)
    private val closeButton = StoryGlassButton(context, StoryEditorIcons.back)
    private val tools = StoryGlassButton(context, null)
    private val textTool = StoryGlyphButton(context, StoryEditorIcons.addText)
    private val stickerTool = StoryGlyphButton(context, StoryEditorIcons.addSticker)
    private val postButton = StoryPostButton(context)
    private val captionPanel = ManualFrame(context)
    private val captionField = StoryGlassButton(context, null, fill = 0x33000000)
    private val caption = EditText(context)
    private val captionDim = View(context)
    private val framesButton = StoryGlassButton(context, null)
    private val framesPanel = ManualFrame(context)
    private val framesScroll = HorizontalScrollView(context)
    private val framesStrip = ManualFrame(context)
    private val cards = ArrayList<FrameCard>()
    private val addCard = FrameCard(context)
    private var framesShown = false
    private val guides = StoryGuidesView(context)
    private val selection = StorySelectionView(context)
    private val bin = StoryBinView(context)
    private val busy = ManualFrame(context)
    private val busyLabel = TextView(context)
    private val busySpinner = ProgressBar(context)
    private var textEntry: StoryTextEntryView? = null
    private var picker: StickerPickerSheet? = null
    /** The keyboard's height over the editor's foot, dp. */
    private var keyboard = 0.0
    private var layout: StoryEditorLayout? = null
    /** The status bar and the navigation bar, dp. */
    var statusBar = 0.0
        set(value) { if (field != value) { field = value; requestLayout() } }
    var homeIndicator = 0.0
        set(value) { if (field != value) { field = value; requestLayout() } }

    private sealed class Target {
        data class Overlay(val id: String) : Target()
        data object Media : Target()
    }
    private var target: Target? = null
    /** The overlay last touched, shown in its dashed frame. */
    private var selected: String? = null
    /** The touched thing's place as the fingers have it, before snapping. */
    private var rawCenter = Point(0.0, 0.0)
    private var rawScale = 1.0
    private var rawRotation = 0.0
    private val snapper = StorySnapper()
    private var overBin = false

    private var saving = false
    /** Device files sent, by frame, until a save places them. */
    private var uploaded = HashMap<String, String>()
    /** Every device file this editor was given, removed when it closes. */
    private val files = ArrayList<java.io.File>()
    private val thumbLoads = HashMap<String, Job>()
    private val thumbImages = HashMap<String, Bitmap>()
    private val scope = MainScope()

    /** Closed, with the story saved (or posted), or null. */
    var onFinish: ((Story?) -> Unit)? = null
    /** Asks the presenter to take the editor away; it calls [onFinish] after. */
    var onDismiss: (() -> Unit)? = null
    /** What happened, for the harness's probe. */
    val trail = ArrayList<String>()

    init {
        setBackgroundColor(Color.BLACK)
        tag = "story-editor"
        clipChildren = false
        files += draft.uploads.map { it.file }
        canvas.tag = "story-editor-canvas"
        canvas.clipToOutline = true
        canvas.outlineProvider = rounded(StoryEditorLayout.Metrics.cornerRadius)
        canvas.onVideoEnd = {
            canvas.restart()
            canvas.play()
        }
        canvas.setOnTouchListener { _, event -> canvasTouched(event) }
        addView(canvas)
        // The snap lines and the angle line, over the canvas and clipped to it.
        guides.clipToOutline = true
        guides.outlineProvider = rounded(StoryEditorLayout.Metrics.cornerRadius)
        addView(guides)
        selection.onHandle = { phase, scale, turn -> handled(phase, scale, turn) }
        addView(selection)
        bin.alpha = 0f
        addView(bin)

        captionDim.setBackgroundColor(0x66000000)
        captionDim.alpha = 0f
        captionDim.visibility = GONE
        captionDim.clipToOutline = true
        captionDim.outlineProvider = rounded(StoryEditorLayout.Metrics.cornerRadius)
        captionDim.setOnClickListener { caption.clearFocus() }
        addView(captionDim)

        closeButton.tag = "story-editor-close"
        closeButton.contentDescription = context.getString(R.string.stories_close)
        closeButton.setOnClickListener { close() }
        addView(closeButton)

        for ((button, id, label) in listOf(Triple(textTool, "story-tool-text", R.string.stories_text), Triple(stickerTool, "story-tool-sticker", R.string.stories_stickers))) {
            button.tag = id
            button.contentDescription = context.getString(label)
            tools.addView(button)
        }
        tools.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        textTool.setOnClickListener { beginText(null) }
        stickerTool.setOnClickListener { pick(StickerPickerSheet.Mode.All) }
        addView(tools)

        postButton.title = context.getString(R.string.stories_post).uppercase()
        postButton.tag = "story-editor-post"
        postButton.contentDescription = context.getString(R.string.stories_post)
        postButton.setOnClickListener { commit(post = true) }
        addView(postButton)

        captionField.isClickable = false
        captionPanel.addView(captionField)
        caption.setText(draft.caption)
        caption.hint = context.getString(R.string.stories_caption)
        caption.setHintTextColor(0xCCFFFFFF.toInt())
        caption.setTextColor(Color.WHITE)
        caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
        caption.background = null
        caption.setPadding(0, 0, 0, 0)
        caption.isSingleLine = true
        caption.imeOptions = EditorInfo.IME_ACTION_DONE
        caption.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        caption.filters = arrayOf(InputFilter.LengthFilter(StoryCanvas.MAX_CAPTION))
        caption.tag = "story-editor-caption"
        if (android.os.Build.VERSION.SDK_INT >= 29) caption.textCursorDrawable?.setTint(Color.WHITE)
        caption.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) caption.clearFocus()
            action == EditorInfo.IME_ACTION_DONE
        }
        caption.doAfterTextChanged { this.draft.caption = it?.toString() ?: "" }
        caption.setOnFocusChangeListener { _, focused -> captionFocused(focused) }
        captionPanel.addView(caption)
        addView(captionPanel)

        framesButton.textLabel = "${draft.frames.size}"
        framesButton.tag = "story-editor-frames"
        framesButton.contentDescription = context.getString(R.string.stories_frames)
        framesButton.setOnClickListener { toggleFrames() }
        addView(framesButton)
        framesPanel.background = android.graphics.drawable.GradientDrawable().apply {
            // systemThinMaterialDark's tint, without the blur.
            setColor(0xE6252527.toInt())
            cornerRadius = dp(10.0)
        }
        framesPanel.clipToOutline = true
        framesPanel.alpha = 0f
        framesPanel.visibility = GONE
        framesScroll.isHorizontalScrollBarEnabled = false
        framesScroll.addView(framesStrip)
        framesPanel.addView(framesScroll)
        addCard.glyph = StoryEditorIcons.plus
        addCard.tag = "story-editor-add"
        addCard.contentDescription = context.getString(R.string.stories_add_frame)
        addCard.setOnClickListener { addFrames() }
        framesStrip.addView(addCard)
        addView(framesPanel)

        busy.setBackgroundColor(0x99000000.toInt())
        busy.visibility = GONE
        busy.isClickable = true
        busyLabel.setTextColor(Color.WHITE)
        busyLabel.gravity = Gravity.CENTER
        busyLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        busyLabel.typeface = StoryViewerPageView.medium
        busySpinner.isIndeterminate = true
        busySpinner.indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        busy.addView(busySpinner)
        busy.addView(busyLabel)
        addView(busy)

        // The keyboard as it moves, for the caption and the text tool.
        ViewCompat.setWindowInsetsAnimationCallback(this, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(insets: WindowInsetsCompat, running: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                keyboardMoved(insets)
                return insets
            }
        })
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            keyboardMoved(insets)
            insets
        }
        isFocusableInTouchMode = true
        render()
    }

    private fun keyboardMoved(insets: WindowInsetsCompat) {
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val next = px(ime)
        if (next == keyboard) return
        keyboard = next
        textEntry?.keyboard = next
        picker?.keyboard = next
        requestLayout()
    }

    private fun note(step: String) {
        trail += step
        if (trail.size > 24) trail.removeAt(0)
    }

    // Layout

    override fun layoutSubviews(width: Double, height: Double) {
        val title = postButton.titleWidth
        val bounds = Rect(0.0, 0.0, width, height)
        val layout = StoryEditorLayout(bounds, statusBar, homeIndicator, title + 5 + StoryEditorIcons.next.width, tablet = resources.configuration.smallestScreenWidthDp >= 600)
        val resized = this.layout?.canvas?.size != layout.canvas.size
        this.layout = layout
        canvas.setFrame(layout.canvas)
        guides.setFrame(layout.canvas)
        captionDim.setFrame(layout.canvas)
        closeButton.setFrame(layout.close)
        tools.setFrame(layout.tools)
        for ((button, frame) in listOf(textTool, stickerTool).zip(layout.toolButtons)) button.setFrame(frame)
        postButton.setFrame(layout.post)
        var panel = layout.captionPanel
        if (caption.hasFocus() && keyboard > 0) panel = Rect(panel.minX, height - keyboard - panel.height, panel.width, panel.height)
        captionPanel.setFrame(panel)
        val field = Rect(layout.caption.minX - layout.captionPanel.minX, layout.caption.minY - layout.captionPanel.minY, layout.caption.width, layout.caption.height)
        captionField.setFrame(field)
        caption.setFrame(Rect(field.minX + 16, field.minY, max(0.0, field.width - 32), field.height))
        framesButton.setFrame(layout.framesButton)
        layoutFrames(layout)
        bin.setFrame(layout.bin)
        bin.circle = Rect(layout.binCircle.minX - layout.bin.minX, layout.binCircle.minY - layout.bin.minY, layout.binCircle.width, layout.binCircle.height)
        guides.canvasSize = layout.canvas.size
        busy.setFrame(bounds)
        busySpinner.setFrame(Rect(width / 2 - 18, height / 2 - 16 - 18, 36.0, 36.0))
        busyLabel.setFrame(Rect(16.0, height / 2 + 2 + 12, width - 32, 22.0))
        textEntry?.setFrame(bounds)
        picker?.setFrame(bounds)
        if (resized) post { render() } else placeSelection()
    }

    // Drawing

    private fun render() {
        val frame = draft.current
        if (frame != null) {
            val (story, shown) = draft.preview(frame)
            // The editor's own field shows the caption.
            canvas.local = local
            canvas.show(story.copy(caption = null), shown, session.media, playsVideo = true)
            if (shown.kind == StoryFrame.Kind.Video) canvas.play()
        }
        selected?.let { if (draft.overlay(it) == null) selected = null }
        renderFrames()
        val idle = !saving && draft.frames.isNotEmpty()
        postButton.isEnabled = idle
        // Disabled buttons dim what they draw, as UIButton does; their alpha is the chrome's.
        for (button in listOf(textTool, stickerTool)) button.isEnabled = draft.current != null
        canvas.requestLayout()
        // The overlays are placed with the canvas; the frame follows them.
        canvas.post { placeSelection() }
        requestLayout()
    }

    /** The dashed frame around the selected overlay, unless it is being moved. */
    private fun placeSelection() {
        val selected = selected
        val view = selected?.let { canvas.overlayView(it) }
        val overlay = selected?.let { draft.overlay(it) }
        val t = overlay?.transform
        if (selected == null || target != null || textEntry != null || view == null || t == null || view.width == 0) {
            selection.visibility = GONE
            return
        }
        val scale = if (view.scalesWithTransform) t.scale else 1.0
        val unit = px(canvas.width) / 1080
        val oval = overlay is StoryOverlay.Sticker || overlay is StoryOverlay.Emoji || overlay is StoryOverlay.Reaction
        val base = view.baseBounds
        selection.show(
            size = Size(base.width * scale, base.height * scale),
            center = Point(px(canvas.left + view.left) + base.width / 2, px(canvas.top + view.top) + base.height / 2),
            rotation = t.rotation, outset = 10 * unit, oval = oval,
        )
        selection.contentDescription = selected
        selection.visibility = VISIBLE
    }

    private fun select(id: String?) {
        selected = id
        placeSelection()
    }

    // Frames

    private fun renderFrames() {
        framesButton.textLabel = "${draft.frames.size}"
        framesButton.stateDescription = "${draft.frames.size}"
        while (cards.size < draft.frames.size) {
            val card = FrameCard(context)
            val index = cards.size
            card.setOnClickListener { selectFrame(index) }
            card.setOnLongClickListener { frameMenu(card, index); true }
            framesStrip.addView(card, framesStrip.indexOfChild(addCard))
            cards += card
        }
        while (cards.size > draft.frames.size) framesStrip.removeView(cards.removeAt(cards.size - 1))
        for ((index, frame) in draft.frames.withIndex()) {
            val card = cards[index]
            card.tag = "story-editor-frame:$index"
            card.chosen = index == draft.selected
            card.stateDescription = if (index == draft.selected) "selected" else null
            card.picture = thumbnail(frame)
        }
        addCard.visibility = if (draft.canAddFrame) VISIBLE else GONE
        layout?.let { layoutFrames(it) }
    }

    private fun frameMenu(card: View, index: Int) {
        if (draft.frames.size <= 1) return
        val id = draft.frames.getOrNull(index)?.id ?: return
        val menu = PopupMenu(ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault), card)
        menu.menu.add(context.getString(R.string.stories_delete_frame)).setOnMenuItemClickListener { removeFrame(id); true }
        menu.show()
    }

    private fun layoutFrames(layout: StoryEditorLayout) {
        val panel = layout.panel(px(width), draft.frames.size, draft.canAddFrame)
        framesPanel.setFrame(panel.frame)
        framesScroll.setFrame(Rect(0.0, 0.0, panel.frame.width, panel.frame.height))
        framesStrip.contentSize = panel.content
        framesStrip.measure(MeasureSpec.makeMeasureSpec(dp(panel.content.width).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(panel.content.height).toInt(), MeasureSpec.EXACTLY))
        framesStrip.layout(0, 0, dp(panel.content.width).toInt(), dp(panel.content.height).toInt())
        for ((card, frame) in cards.zip(panel.items)) card.setFrame(frame)
        panel.add?.let { addCard.setFrame(it) }
    }

    private fun toggleFrames() {
        framesShown = !framesShown
        if (framesShown) framesPanel.visibility = VISIBLE
        val shown = framesShown
        framesPanel.animate().alpha(if (shown) 1f else 0f).setDuration(200).withEndAction {
            if (!framesShown) framesPanel.visibility = GONE
        }.start()
    }

    private fun thumbnail(frame: StoryDraft.Frame): Bitmap? {
        local[frame.id]?.poster?.let { return it }
        thumbImages[frame.id]?.let { return it }
        if (thumbLoads[frame.id] != null || frame.media != StoryDraft.Media.Kept) return null
        val (_, shown) = draft.preview(frame)
        val media = session.media
        val storyId = draft.storyId
        thumbLoads[frame.id] = scope.launch {
            val image = media.frame(storyId, shown, 240) ?: return@launch
            thumbImages[frame.id] = image
            renderFrames()
        }
        return null
    }

    private fun selectFrame(index: Int) {
        draft.select(index)
        select(null)
        render()
    }

    private fun removeFrame(id: String) {
        draft.removeFrame(id)
        // Its device file stays for the editor to remove on close.
        local = local - id
        uploaded.remove(id)
        thumbImages.remove(id)
        render()
    }

    private fun addFrames() {
        StoriesMediaChooser.choose(context, StoryCanvas.MAX_FRAMES - draft.frames.size) { picked ->
            if (!isAttachedToWindow) {
                // The editor closed while they were readied: nobody owns the files.
                for (one in picked) (one.frame.media as? StoryDraft.Media.Local)?.file?.delete()
                return@choose
            }
            for (one in picked) {
                (one.frame.media as? StoryDraft.Media.Local)?.let { files += it.file }
                if (!draft.add(one.frame)) continue
                one.local?.let { local = local + (one.frame.id to it) }
            }
            note("frames:${draft.frames.size}")
            select(null)
            render()
        }
    }

    // Fingers

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var tracking = false
    private var lastCenter = Point(0.0, 0.0)
    private var lastSpan = 0.0
    private var lastAngle = 0.0
    private var pointers = 0
    /** The finger's place in the editor, for the bin. */
    private var finger = Point(0.0, 0.0)

    private val gesturesOpen: Boolean get() = textEntry == null && !saving && !caption.hasFocus() && picker == null

    /** The fingers on the canvas: one drags, two drag, pinch and turn together, as UIKit's recognizers do at once. */
    private fun canvasTouched(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = event.eventTime
                moved = false
                tracking = gesturesOpen
                pointers = 1
                lastCenter = centroid(event)
                finger = Point(px(event.x + canvas.left), px(event.y + canvas.top))
                if (caption.hasFocus()) {
                    caption.clearFocus()
                    tracking = false
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!tracking) return true
                pointers = event.pointerCount
                lastCenter = centroid(event)
                lastSpan = span(event)
                lastAngle = angle(event)
                // A pinch or turn joining a drag that began on the media takes the selected overlay.
                val joins = target == Target.Media && selected != null
                if (target == null || joins) {
                    moved = true
                    begin(lastCenter, twoFingers = true)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                finger = Point(px(event.getX(0) + canvas.left), px(event.getY(0) + canvas.top))
                if (!moved) {
                    if (hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) <= slop) return true
                    moved = true
                    lastCenter = centroid(event)
                    begin(Point(px(downX), px(downY)), twoFingers = false)
                }
                if (target == null) return true
                val center = centroid(event)
                rawCenter = Point(rawCenter.x + center.x - lastCenter.x, rawCenter.y + center.y - lastCenter.y)
                lastCenter = center
                if (event.pointerCount > 1) {
                    val span = span(event)
                    val angle = angle(event)
                    if (lastSpan > 1) rawScale *= span / lastSpan
                    rawRotation += turn(lastAngle, angle)
                    lastSpan = span
                    lastAngle = angle
                }
                follow()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (!tracking) return true
                pointers = event.pointerCount - 1
                // What the remaining finger has is where it starts from.
                lastCenter = centroid(event, without = event.actionIndex)
                lastSpan = 0.0
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return true
                tracking = false
                pointers = 0
                if (!moved && event.eventTime - downAt < ViewConfiguration.getLongPressTimeout()) tapped(Point(px(event.x), px(event.y)))
                else if (target != null) finishGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                pointers = 0
                if (target != null) finishGesture()
            }
        }
        return true
    }

    private fun centroid(event: MotionEvent, without: Int = -1): Point {
        var x = 0f
        var y = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == without) continue
            x += event.getX(i)
            y += event.getY(i)
            n += 1
        }
        if (n == 0) return lastCenter
        return Point(px(x / n), px(y / n))
    }

    private fun span(event: MotionEvent): Double =
        if (event.pointerCount < 2) 0.0 else hypot((event.getX(1) - event.getX(0)).toDouble(), (event.getY(1) - event.getY(0)).toDouble())

    private fun angle(event: MotionEvent): Double =
        if (event.pointerCount < 2) 0.0 else atan2((event.getY(1) - event.getY(0)).toDouble(), (event.getX(1) - event.getX(0)).toDouble())

    /** From [from] to [to], never across atan2's ±π seam. */
    private fun turn(from: Double, to: Double): Double {
        var d = to - from
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        return d
    }

    /**
     * What the fingers took: the overlay under them, or for two fingers
     * the selected one, or else the frame's media.
     */
    private fun begin(point: Point, twoFingers: Boolean) {
        val size = Size(px(canvas.width), px(canvas.height))
        var id = canvas.overlayView(point)?.overlayId
        // Two fingers anywhere take the selected overlay, as Telegram's do.
        val chosen = selected
        if (id == null && twoFingers && chosen != null && draft.overlay(chosen) != null) id = chosen
        val overlay = id?.let { draft.overlay(it) }
        val t = overlay?.transform
        if (id != null && overlay != null && t != null) {
            target = Target.Overlay(id)
            selected = id
            draft.bringToFront(id)
            rawCenter = Point(t.x * size.width, t.y * size.height)
            rawScale = t.scale
            rawRotation = t.rotation
            canvas.overlayView(id)?.let { view ->
                snapper.begin(rawCenter, StorySnapper.half(box(view, rawScale), rawRotation), rawRotation, size, edges(overlay))
            }
            render()
        } else {
            val frame = draft.current ?: return
            // The selection stays; only a tap lets go of it.
            target = Target.Media
            val t = frame.placement
            rawCenter = Point(t.x * size.width, t.y * size.height)
            rawScale = t.scale
            rawRotation = t.rotation
        }
    }

    private fun edges(overlay: StoryOverlay): Boolean = overlay is StoryOverlay.Sticker || overlay is StoryOverlay.Emoji

    /** An overlay's box on the canvas at [scale]. */
    private fun box(view: StoryOverlayView, scale: Double): Size {
        val t = max(0.05, view.overlay.transform?.scale ?: 1.0)
        val grows = if (view.scalesWithTransform) scale else scale / t
        val base = view.baseBounds
        return Size(base.width * grows, base.height * grows)
    }

    /** Puts the touched thing where the fingers have it, snapped. */
    private fun follow() {
        val size = Size(px(canvas.width), px(canvas.height))
        if (size.width <= 0 || size.height <= 0) return
        val scale = min(10.0, max(0.05, rawScale))
        val turned = atan2(sin(rawRotation), cos(rawRotation))
        when (val target = target) {
            is Target.Overlay -> {
                val overlay = draft.overlay(target.id) ?: return
                val view = canvas.overlayView(target.id) ?: return
                var result = snapper.snap(rawCenter, StorySnapper.half(box(view, scale), rawRotation), rawRotation, size, edges(overlay) && !handling)
                // A handle grows and turns it about its centre; only the angle snaps.
                if (handling) result = result.copy(center = rawCenter, guides = emptySet())
                if (result.snapped) {
                    StoriesHaptics.play(StoriesHaptics.Moment.Snap, this)
                    note("snap")
                }
                guides.show(result.guides, if (result.turned) result.rotation else null, result.center)
                val rotation = atan2(sin(result.rotation), cos(result.rotation))
                draft.move(target.id, StoryTransform(result.center.x / size.width, result.center.y / size.height, scale, rotation))
                val dragging = !handling && pointers == 1
                binShown(dragging)
                val reach = layout?.bin?.let { Rect(it.minX - 20, it.minY - 20, it.width + 40, it.height + 40) }
                val over = dragging && reach != null && finger.x >= reach.minX && finger.x <= reach.maxX && finger.y >= reach.minY && finger.y <= reach.maxY
                if (over != overBin) {
                    overBin = over
                    bin.open = over
                    if (over) {
                        StoriesHaptics.play(StoriesHaptics.Moment.Trash, this)
                        note("trash-over")
                    } else {
                        // Pulled back out: it comes back to the finger.
                        view.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(150).start()
                    }
                }
                render()
                if (over) suck(view)
            }
            Target.Media -> {
                draft.placeMedia(StoryTransform(rawCenter.x / size.width, rawCenter.y / size.height, scale, turned))
                render()
            }
            null -> {}
        }
    }

    /** Over the bin, the overlay shrinks into it. */
    private fun suck(view: StoryOverlayView) {
        val layout = layout ?: return
        val center = Point(layout.binCircle.midX - px(canvas.left), layout.binCircle.midY - px(canvas.top))
        val width = max(1.0, view.baseBounds.width)
        val shrink = (layout.binCircle.width * 0.5 / width).toFloat()
        view.animate()
            .translationX(dp(center.x) - (view.left + view.width / 2f))
            .translationY(dp(center.y) - (view.top + view.height / 2f))
            .scaleX(shrink).scaleY(shrink).alpha(0.6f).setDuration(150).start()
    }

    private fun finishGesture() {
        val wasOverBin = overBin
        (target as? Target.Overlay)?.let { overlay ->
            if (wasOverBin) {
                trash(overlay.id)
            } else {
                draft.overlay(overlay.id)?.transform?.let { t ->
                    note("moved:" + String.format(java.util.Locale.US, "%.3f,%.3f,%.2f,%.3f", t.x, t.y, t.scale, t.rotation))
                }
            }
        }
        target = null
        overBin = false
        bin.open = false
        snapper.reset()
        guides.show(emptySet(), null, Point(0.0, 0.0))
        binShown(false)
        if (!wasOverBin) render()
    }

    /** Dropped on the bin: it falls in and goes. */
    private fun trash(id: String) {
        selected = null
        draft.remove(id)
        note("trashed")
        val view = canvas.overlayView(id) ?: return render()
        view.animate().alpha(0f).scaleX(view.scaleX * 0.01f).scaleY(view.scaleY * 0.01f).setDuration(200).withEndAction {
            view.translationX = 0f
            view.translationY = 0f
            render()
        }.start()
    }

    /** While an overlay is dragged the controls step aside for the bin. */
    private var binIsShown = false

    private fun binShown(shown: Boolean) {
        if (binIsShown == shown) return
        binIsShown = shown
        bin.animate().alpha(if (shown) 1f else 0f).setDuration(200).start()
        for (view in controls) view.animate().alpha(if (shown) 0f else 1f).setDuration(200).start()
        if (shown) framesPanel.animate().alpha(0f).setDuration(200).start()
        else if (framesShown) framesPanel.animate().alpha(1f).setDuration(200).start()
    }

    private val controls: List<View> get() = listOf(closeButton, tools, postButton, captionPanel, framesButton)

    /** A handle, not the canvas, is moving the selected overlay. */
    private var handling = false
    private var handleStartScale = 1.0
    private var handleStartRotation = 0.0

    /** The selected overlay's handles, dragged: it grows and turns about its centre. */
    private fun handled(phase: StorySelectionView.Phase, scale: Double, turn: Double) {
        val selected = selected ?: return
        val t = draft.overlay(selected)?.transform ?: return
        val size = Size(px(canvas.width), px(canvas.height))
        when (phase) {
            StorySelectionView.Phase.Began -> {
                handling = true
                rawCenter = Point(t.x * size.width, t.y * size.height)
                rawScale = t.scale
                rawRotation = t.rotation
                handleStartScale = rawScale
                handleStartRotation = rawRotation
                val view = canvas.overlayView(selected)
                val overlay = draft.overlay(selected)
                if (view != null && overlay != null) {
                    snapper.begin(rawCenter, StorySnapper.half(box(view, rawScale), rawRotation), rawRotation, size, edges(overlay))
                }
                target = Target.Overlay(selected)
            }
            StorySelectionView.Phase.Changed -> {
                rawScale = handleStartScale * scale
                rawRotation = handleStartRotation + turn
                follow()
            }
            StorySelectionView.Phase.Ended -> {
                handling = false
                finishGesture()
            }
        }
    }

    private fun tapped(point: Point) {
        if (textEntry != null || saving) return
        if (caption.hasFocus()) {
            caption.clearFocus()
            return
        }
        if (framesShown) return toggleFrames()
        val view = canvas.overlayView(point)
        if (view == null) {
            // Telegram's canvas: a tap lets go of the selection, or starts text.
            if (selected != null) select(null) else beginText(null)
            return
        }
        // The first tap selects; another on the selected one changes it.
        if (view.overlayId != selected) return select(view.overlayId)
        when (val overlay = view.overlay) {
            is StoryOverlay.Text -> return beginText(overlay.id)
            is StoryOverlay.Link -> {
                // Tapping a link turns it through its looks; only `custom` has a colour.
                val looks = listOf(StoryLinkStyle.White, StoryLinkStyle.Black, StoryLinkStyle.Transparent, StoryLinkStyle.Blur)
                val next = looks[(looks.indexOf(overlay.style) + 1) % looks.size]
                draft.replace(overlay.copy(style = next, color = null))
                note("link:${next.wire}")
            }
            is StoryOverlay.Reaction -> {
                // Light, dark, then each with its tail on the right.
                val isDark = overlay.dark == true
                val isFlipped = overlay.flipped == true
                val dark = !isDark
                val flipped = if (isDark) !isFlipped else isFlipped
                draft.replace(overlay.copy(dark = if (dark) true else null, flipped = if (flipped) true else null))
                note("reaction:${if (dark) "dark" else "light"},${if (flipped) "right" else "left"}")
            }
            else -> {}
        }
        render()
    }

    // Adding

    private fun add(overlay: StoryOverlay, kind: String) {
        if (!draft.add(overlay)) {
            StoriesHaptics.play(StoriesHaptics.Moment.Refused, this)
            return
        }
        note("added:$kind")
        selected = overlay.id
        render()
    }

    private fun pick(mode: StickerPickerSheet.Mode) {
        if (picker != null) return
        val sheet = StickerPickerSheet(context, session, mode)
        sheet.keyboard = keyboard
        sheet.statusBar = statusBar
        sheet.homeIndicator = homeIndicator
        sheet.onPick = { pick ->
            sheet.dismiss {
                // The link and the reaction ask for more first.
                when (pick) {
                    StickerPick.Link -> askLink()
                    StickerPick.Reaction -> pick(StickerPickerSheet.Mode.EmojiOnly)
                    else -> {}
                }
            }
            place(pick, reaction = mode == StickerPickerSheet.Mode.EmojiOnly)
        }
        sheet.onClose = { if (picker === sheet) picker = null }
        picker = sheet
        addView(sheet, indexOfChild(busy))
        sheet.setFrame(Rect(0.0, 0.0, px(width), px(height)))
        sheet.present()
    }

    private fun place(pick: StickerPick, reaction: Boolean) {
        val id = ClientId.make()
        fun middle(scale: Double) = StoryTransform(0.5, 0.5, scale, 0.0)
        when (pick) {
            is StickerPick.Emoji -> if (reaction) {
                add(StoryOverlay.Reaction(id, pick.emoji, null, null, middle(0.3)), "reaction")
            } else {
                add(StoryOverlay.Emoji(id, pick.emoji, middle(0.25)), "emoji")
            }
            is StickerPick.Sticker -> add(StoryOverlay.Sticker(id, pick.sticker.packId, pick.sticker.id, middle(0.4)), "sticker")
            StickerPick.Link, StickerPick.Reaction -> return
        }
        if (draft.overlay(id) != null) StoriesHaptics.play(StoriesHaptics.Moment.StickerDrop, this)
    }

    /** Text typed on the canvas: new, or the overlay [id] again. */
    private fun beginText(id: String?) {
        val layout = layout
        if (textEntry != null || draft.current == null || layout == null) return
        if (framesShown) toggleFrames()
        var initial = ""
        val newScale = StoryTextEntryLayout.scale(StoryTextEntryLayout.Metrics.newSize)
        var style = StoryTextStyle(fill = StoryTextFill.Filled, width = StoryTextEntryView.width(newScale))
        var scale = newScale
        val was = id?.let { draft.overlay(it) } as? StoryOverlay.Text
        if (was != null) {
            initial = was.text
            style = was.style
            scale = was.transform.scale
        }
        val entry = StoryTextEntryView(context, initial, style, scale, layout.canvas, StoryEditorLayout.Metrics.cornerRadius)
        entry.statusBar = statusBar
        entry.safeBottom = homeIndicator
        entry.keyboard = keyboard
        addView(entry, indexOfChild(busy))
        entry.setFrame(Rect(0.0, 0.0, px(width), px(height)))
        textEntry = entry
        chromeShown(false)
        if (id != null) canvas.overlayView(id)?.visibility = INVISIBLE
        val end = {
            removeView(entry)
            textEntry = null
            chromeShown(true)
            if (id != null) canvas.overlayView(id)?.visibility = VISIBLE
        }
        entry.onCancel = {
            end()
            render()
        }
        entry.onDone = done@{ text, chosen, size ->
            end()
            val typed = text.isNotBlank()
            val current = id?.let { draft.overlay(it) } as? StoryOverlay.Text
            if (current != null) {
                val t = current.transform.copy(scale = size)
                if (typed) draft.replace(StoryOverlay.Text(current.id, text, chosen, t)) else draft.remove(current.id)
                note("text:${chosen.fill.wire},${chosen.font.wire},${chosen.color}")
                render()
            } else if (typed) {
                add(StoryOverlay.Text(ClientId.make(), text, chosen, StoryTransform(0.5, 0.5, size, 0.0)), "text")
                note("text:${chosen.fill.wire},${chosen.font.wire},${chosen.color}")
            } else {
                render()
            }
        }
        entry.begin()
        placeSelection()
    }

    /** The tools, caption and frames step aside while text is typed. */
    private fun chromeShown(shown: Boolean) {
        for (view in controls) view.animate().alpha(if (shown) 1f else 0f).setDuration(200).start()
    }

    private fun askLink() {
        val dark = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        fun field(hint: Int, id: String, url: Boolean) = EditText(dark).apply {
            this.hint = context.getString(hint)
            tag = id
            isSingleLine = true
            inputType = if (url) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else InputType.TYPE_CLASS_TEXT
        }
        val url = field(R.string.stories_link_url, "story-link-url", url = true)
        val label = field(R.string.stories_link_label, "story-link-label", url = false)
        val fields = LinearLayout(dark).apply {
            orientation = LinearLayout.VERTICAL
            val side = dp(20.0).toInt()
            setPadding(side, dp(8.0).toInt(), side, 0)
            addView(url)
            addView(label)
        }
        val dialog = AlertDialog.Builder(dark)
            .setTitle(context.getString(R.string.stories_link))
            .setView(fields)
            .setNegativeButton(context.getString(R.string.stories_cancel), null)
            .setPositiveButton(context.getString(R.string.stories_done)) { _, _ ->
                val link = link(url.text.toString())
                if (link == null) {
                    Alerts.show(Alerts.dark(context), context.getString(R.string.stories_invalid_link))
                    return@setPositiveButton
                }
                val named = label.text.toString().trim()
                add(StoryOverlay.Link(ClientId.make(), link, if (named.isEmpty()) null else named.take(64), StoryLinkStyle.White, null, StoryTransform(0.5, 0.5, 1.0, 0.0)), "link")
                StoriesHaptics.play(StoriesHaptics.Moment.StickerDrop, this)
            }
            .create()
        dialog.show()
        url.requestFocus()
    }

    // Caption

    /** Typing a caption dims the canvas and lifts the field over the keyboard. */
    private fun captionFocused(focused: Boolean) {
        val input = context.getSystemService(InputMethodManager::class.java)
        if (focused) {
            if (framesShown) toggleFrames()
            select(null)
            captionDim.visibility = VISIBLE
            input?.showSoftInput(caption, 0)
        } else {
            input?.hideSoftInputFromWindow(caption.windowToken, 0)
        }
        captionDim.animate().alpha(if (focused) 1f else 0f).setDuration(250).withEndAction {
            if (!caption.hasFocus()) captionDim.visibility = GONE
        }.start()
        for (view in listOf(closeButton, tools, postButton, framesButton)) view.animate().alpha(if (focused) 0f else 1f).setDuration(250).start()
        requestLayout()
    }

    // Saving

    /**
     * Sends the device's files once, makes or edits the draft, and with
     * [post] posts the version just saved.
     */
    private fun commit(post: Boolean, closes: Boolean = false) {
        if (saving || draft.frames.isEmpty()) return
        caption.clearFocus()
        select(null)
        saving = true
        busyShown(true, context.getString(if (draft.uploads.any { uploaded[it.frameId] == null }) R.string.stories_uploading else R.string.stories_saving))
        render()
        val store = session.store
        val client = session.client
        scope.launch {
            var stage = "save"
            try {
                for (upload in draft.uploads) {
                    if (uploaded[upload.frameId] != null) continue
                    val data = withContext(Dispatchers.IO) { upload.file.readBytes() }
                    uploaded[upload.frameId] = client.upload(data)
                }
                busyShown(true, context.getString(R.string.stories_saving))
                val inputs = draft.inputs(uploaded)
                var story = when {
                    draft.isNew -> store.create(draft.storyId, draft.sentCaption, inputs)
                    // The version this editor began from: a newer one is a conflict, not overwritten.
                    draft.hasChanges -> store.update(draft.storyId, draft.original?.version, CaptionEdit.Set(draft.sentCaption), inputs)
                    else -> draft.original!!
                }
                note("saved:${story.artifactVersion}")
                // From here it is an edit of what was saved; the files are its own.
                adopt(story, ours = true)
                if (post && story.status == StoryStatus.Draft) {
                    stage = "post"
                    story = store.post(story.id, story.artifactVersion)
                    StoriesHaptics.play(StoriesHaptics.Moment.Post, this@StoryEditorView)
                    note("posted")
                }
                saving = false
                busyShown(false, null)
                if (post || closes) finish(story) else render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saving = false
                busyShown(false, null)
                render()
                StoriesHaptics.play(StoriesHaptics.Moment.Refused, this@StoryEditorView)
                note("refused:$stage")
                val refusal = StoryRefusal.of(e)
                if (refusal?.isConflict == true && !draft.isNew) {
                    conflicted(post, closes, stage, refusal.message)
                    return@launch
                }
                Alerts.show(Alerts.dark(context), context.getString(if (stage == "post") R.string.stories_post_failed else R.string.stories_save_failed), refusal?.message)
            }
        }
    }

    /**
     * A 409. When the story was saved elsewhere since this editor read it,
     * a save offers the newer version or these edits kept over it; a post
     * shows the newer version, to be posted only once seen. A deleted story
     * or one with nothing to show is refused as the daemon says.
     */
    private fun conflicted(post: Boolean, closes: Boolean, stage: String, message: String?) {
        val client = session.client
        val storyId = draft.storyId
        val base = draft.original
        scope.launch {
            val latest = runCatching { client.story(storyId) }.getOrNull()
            val dark = Alerts.dark(context)
            if (latest == null || latest.status == StoryStatus.Deleted || (latest.version == base?.version && latest.artifactVersion == base.artifactVersion)) {
                Alerts.show(dark, context.getString(if (stage == "post") R.string.stories_post_failed else R.string.stories_save_failed), message)
                return@launch
            }
            if (stage == "post") {
                adopt(latest, ours = false)
                note("conflict:latest")
                render()
                Alerts.show(dark, context.getString(R.string.stories_newer_version), context.getString(R.string.stories_newer_version_detail))
                return@launch
            }
            AlertDialog.Builder(ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog_Alert))
                .setTitle(context.getString(R.string.stories_newer_version))
                .setMessage(context.getString(R.string.stories_newer_version_detail))
                .setPositiveButton(context.getString(R.string.stories_use_latest)) { _, _ ->
                    adopt(latest, ours = false)
                    note("conflict:latest")
                    render()
                }
                .setNeutralButton(context.getString(R.string.stories_overwrite)) { _, _ ->
                    draft.rebase(latest)
                    note("conflict:mine")
                    render()
                    commit(post, closes)
                }
                .setNegativeButton(context.getString(R.string.stories_cancel), null)
                .show()
        }
    }

    /**
     * Edits [story] from here on: the frames are the story's own now.
     * [ours] (this editor's own save) keeps drawing its frames from the
     * device; another's version is read afresh.
     */
    private fun adopt(story: Story, ours: Boolean) {
        val selectedId = draft.current?.id
        draft = StoryDraft(editing = story)
        val index = draft.frames.indexOfFirst { it.id == selectedId }
        if (index >= 0) draft.select(index)
        caption.setText(draft.caption)
        uploaded = HashMap()
        if (ours) {
            val kept = draft.frames.map { it.id }.toSet()
            local = local.filterKeys { it in kept }
        } else {
            local = emptyMap()
            thumbImages.clear()
        }
        selected = null
    }

    private fun busyShown(shown: Boolean, text: String?) {
        busy.visibility = if (shown) VISIBLE else GONE
        busyLabel.text = text
        busy.bringToFront()
    }

    // Closing

    /** Back closes what is over the frame first, then the editor. */
    fun back() {
        val picker = picker
        when {
            picker != null -> picker.dismiss()
            textEntry != null -> textEntry?.cancel()
            caption.hasFocus() -> caption.clearFocus()
            framesShown -> toggleFrames()
            selected != null -> select(null)
            else -> close()
        }
    }

    private fun close() {
        if (saving) return
        if (!draft.hasChanges) return finish(draft.original)
        scope.launch {
            val title = context.getString(R.string.stories_discard_changes) + "\n" + context.getString(R.string.stories_discard_detail)
            val choice = Alerts.choose(
                Alerts.dark(context), title,
                options = listOf(context.getString(R.string.stories_discard), context.getString(R.string.stories_save_draft)),
                destructive = setOf(0), cancelTitle = context.getString(R.string.stories_keep_editing),
            )
            when (choice) {
                0 -> finish(null)
                1 -> commit(post = false, closes = true)
            }
        }
    }

    private var finished = false

    private fun finish(story: Story?) {
        if (finished) return
        finished = true
        canvas.stop()
        result = story
        onDismiss?.invoke() ?: done()
    }

    private var result: Story? = null
    private var disposed = false

    /** The presenter has taken it away. */
    fun done() {
        if (disposed) return
        disposed = true
        finished = true
        canvas.release()
        scope.cancel()
        val files = files.toList()
        this.files.clear()
        MainScope().launch(Dispatchers.IO) { for (file in files) file.delete() }
        onFinish?.invoke(result)
        onFinish = null
    }

    private class ManualFrame(context: Context) : ManualLayout(context) {
        var contentSize: Size? = null

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = contentSize ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            setMeasuredDimension(dp(size.width).toInt(), dp(size.height).toInt())
        }

        // Children are placed by whoever owns them.
        override fun layoutSubviews(width: Double, height: Double) {}
    }

    /** One frame in the frames panel: its picture, filled and rounded, ringed when selected; or the add button. */
    private class FrameCard(context: Context) : View(context) {
        var picture: Bitmap? = null
            set(value) { if (field !== value) { field = value; invalidate() } }
        var chosen = false
            set(value) { if (field != value) { field = value; invalidate() } }
        var glyph: StoryGlyph? = null
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
        private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
        private val clip = Path()

        init {
            isClickable = true
            isFocusable = true
        }

        override fun onDraw(canvas: Canvas) {
            val r = dp(8.0)
            val box = RectF(0f, 0f, width.toFloat(), height.toFloat())
            clip.reset()
            clip.addRoundRect(box, r, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(box, fill)
            picture?.let { bitmap ->
                // Aspect fill.
                val scale = max(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
                val w = bitmap.width * scale
                val h = bitmap.height * scale
                canvas.drawBitmap(bitmap, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), image)
            }
            canvas.restore()
            glyph?.let { g ->
                val w = (g.width * density).toFloat()
                val h = (g.height * density).toFloat()
                g.draw(canvas, (width - w) / 2, (height - h) / 2, Color.WHITE)
            }
            if (chosen) {
                ring.strokeWidth = dp(2.0)
                val inset = dp(1.0)
                canvas.drawRoundRect(RectF(inset, inset, width - inset, height - inset), r - inset, r - inset, ring)
            }
        }
    }

    companion object {
        /**
         * An absolute http or https URL without a user or password, as the
         * API keeps links; a bare host is taken as https.
         */
        fun link(typed: String): String? {
            var text = typed.trim()
            if (!text.contains("://")) text = "https://$text"
            if (text.length > 2_048) return null
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            if (host.isEmpty() || !host.contains(".") || uri.userInfo != null) return null
            return text
        }

        fun rounded(radius: Double) = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(radius))
            }
        }
    }
}

/**
 * A button on iOS's glass (`UIButton.Configuration.glass()`), as a dark
 * fill with a faint rim: a circle, or a capsule when wider. Holds a glyph,
 * a number, or other buttons.
 */
class StoryGlassButton(context: Context, private val glyph: StoryGlyph?, private val fill: Int = 0xFF242426.toInt()) : ManualLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x26FFFFFF }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = StoryViewerPageView.semibold
    }
    var textLabel: String? = null
        set(value) { if (field != value) { field = value; invalidate() } }

    init {
        setWillNotDraw(false)
        clipChildren = false
        isClickable = true
        isFocusable = true
    }

    override fun layoutSubviews(width: Double, height: Double) {}

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        paint.color = fill
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        rim.strokeWidth = max(1f, density * 0.5f)
        val inset = rim.strokeWidth / 2
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, r - inset, r - inset, rim)
        glyph?.let { g ->
            val w = (g.width * density).toFloat()
            val h = (g.height * density).toFloat()
            g.draw(canvas, (width - w) / 2, (height - h) / 2, if (isPressed) 0x99FFFFFF.toInt() else Color.WHITE)
        }
        textLabel?.let { label ->
            text.textSize = 14 * density
            val fm = text.fontMetrics
            canvas.drawText(label, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2, text)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

/** A glyph alone that takes taps, inside the tools pill. */
class StoryGlyphButton(context: Context, private val glyph: StoryGlyph) : View(context) {
    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = (glyph.width * density).toFloat()
        val h = (glyph.height * density).toFloat()
        val tint = if (!isEnabled) 0x66FFFFFF else if (isPressed) 0x99FFFFFF.toInt() else Color.WHITE
        glyph.draw(canvas, (width - w) / 2, (height - h) / 2, tint)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

/** Post (`prominentGlass` in the story blue): the word in rounded semibold and the chevron after it. */
class StoryPostButton(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        // SF Rounded has no Android counterpart; the system's semibold stands in.
        typeface = StoryViewerPageView.semibold
        textSize = 16 * density
    }
    var title: String = ""
        set(value) { field = value; requestLayout(); invalidate() }

    /** The title's width, dp. */
    val titleWidth: Double get() = px(text.measureText(title))

    init {
        isClickable = true
        isFocusable = true
    }

    override fun draw(canvas: Canvas) {
        if (isEnabled) return super.draw(canvas)
        canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 128)
        super.draw(canvas)
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = Palette.storiesEditorPost.resolve(true).argb.let { if (isPressed) (it and 0x00FFFFFF) or 0xCC000000.toInt() else it }
        val r = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, fill)
        val glyph = StoryEditorIcons.next
        val gw = (glyph.width * density).toFloat()
        val gh = (glyph.height * density).toFloat()
        val tw = text.measureText(title)
        val total = tw + 5 * density + gw
        val x = (width - total) / 2
        val fm = text.fontMetrics
        canvas.drawText(title, x, height / 2f - (fm.ascent + fm.descent) / 2, text)
        glyph.draw(canvas, x + tw + 5 * density, (height - gh) / 2, Color.WHITE)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

/** The snap lines and the dashed angle line, over the canvas. */
class StoryGuidesView(context: Context) : View(context) {
    var canvasSize = Size.Zero
    private val alphas = HashMap<StorySnapper.Guide, Float>()
    private val animators = HashMap<StorySnapper.Guide, ValueAnimator>()
    private val goals = HashMap<StorySnapper.Guide, Float>()
    private var angle: Double? = null
    private var through = Point(0.0, 0.0)
    private val line = Paint().apply { color = Palette.storiesEditorGuide.resolve(true).argb }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Palette.storiesEditorAngle.resolve(true).argb
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(shown: Set<StorySnapper.Guide>, angle: Double?, through: Point) {
        for (guide in StorySnapper.Guide.entries) {
            val target = if (guide in shown) 1f else 0f
            val current = alphas[guide] ?: 0f
            if (current == target || goals[guide] == target) continue
            goals[guide] = target
            animators[guide]?.cancel()
            animators[guide] = ValueAnimator.ofFloat(current, target).apply {
                duration = 100
                addUpdateListener {
                    alphas[guide] = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        this.angle = angle
        this.through = through
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val c = canvasSize
        val inset = c.width * StorySnapper.INSET
        // One device pixel, at least one dp's worth of a point on iOS's 3x.
        val w = max(1f, 1f / density)
        fun alpha(guide: StorySnapper.Guide) = alphas[guide] ?: 0f
        fun vertical(x: Double, guide: StorySnapper.Guide) {
            val a = alpha(guide)
            if (a <= 0f) return
            line.alpha = (a * 255).toInt()
            canvas.drawRect(dp(x), 0f, dp(x) + w * density, height.toFloat(), line)
        }
        fun horizontal(y: Double, guide: StorySnapper.Guide) {
            val a = alpha(guide)
            if (a <= 0f) return
            line.alpha = (a * 255).toInt()
            canvas.drawRect(0f, dp(y), width.toFloat(), dp(y) + w * density, line)
        }
        vertical(c.width / 2 - 0.5, StorySnapper.Guide.CenterX)
        horizontal(c.height / 2 - 0.5, StorySnapper.Guide.CenterY)
        vertical(inset, StorySnapper.Guide.Left)
        vertical(c.width - inset - 1, StorySnapper.Guide.Right)
        horizontal(inset, StorySnapper.Guide.Top)
        horizontal(c.height - inset - 1, StorySnapper.Guide.Bottom)
        val angle = angle ?: return
        dashed.strokeWidth = density
        dashed.pathEffect = DashPathEffect(floatArrayOf(4 * density, 4 * density), 0f)
        val cx = dp(through.x)
        val cy = dp(through.y)
        val dx = (3000 * density * cos(angle)).toFloat()
        val dy = (3000 * density * sin(angle)).toFloat()
        canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, dashed)
    }
}

/**
 * The selected overlay's dashed frame, as Telegram draws it (happy-ios
 * `StorySelectionView`): white dashes around it (a ring around a sticker),
 * and a handle at either side that grows and turns it.
 */
class StorySelectionView(context: Context) : View(context) {
    enum class Phase { Began, Changed, Ended }

    /** A handle dragged: how much larger, and how far turned, since it began. */
    var onHandle: ((Phase, Double, Double) -> Unit)? = null
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xBFFFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.storiesEditorHandle.resolve(true).argb }
    private val handleRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val path = Path()
    private var handles = listOf(Point(0.0, 0.0), Point(0.0, 0.0))
    private var startX = 0.0
    private var startY = 0.0
    private var dragging = false

    init {
        visibility = GONE
        tag = "story-editor-selection"
    }

    /** Frames a box of [size] at [center] (dp in the parent), turned by [rotation]. */
    fun show(size: Size, center: Point, rotation: Double, outset: Double, oval: Boolean) {
        val box = Size(size.width + 2 * outset, size.height + 2 * outset)
        setFrame(Rect(center.x - box.width / 2, center.y - box.height / 2, box.width, box.height))
        pivotX = width / 2f
        pivotY = height / 2f
        this.rotation = Math.toDegrees(rotation).toFloat()
        path.reset()
        val w = dp(box.width)
        val h = dp(box.height)
        val perimeter: Float
        val count: Float
        if (oval) {
            val side = max(w, h)
            val ring = RectF(w / 2 - side / 2, h / 2 - side / 2, w / 2 + side / 2, h / 2 + side / 2)
            path.addOval(ring, Path.Direction.CW)
            perimeter = (PI * side).toFloat()
            count = 10f
            handles = listOf(Point(px(ring.left), box.height / 2), Point(px(ring.right), box.height / 2))
        } else {
            val radius = min(dp(11.0), h / 2)
            path.addRoundRect(RectF(0f, 0f, w, h), radius, radius, Path.Direction.CW)
            perimeter = (2 * (w + h - radius * (4 - PI))).toFloat()
            count = 12f
            handles = listOf(Point(0.0, box.height / 2), Point(box.width, box.height / 2))
        }
        val dash = perimeter / count * 0.25f
        border.pathEffect = DashPathEffect(floatArrayOf(dash, dash), 0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        border.strokeWidth = dp(2.0)
        canvas.drawPath(path, border)
        handleRim.strokeWidth = dp(1.33)
        for (handle in handles) {
            canvas.drawCircle(dp(handle.x), dp(handle.y), dp(4.5), handleFill)
            canvas.drawCircle(dp(handle.x), dp(handle.y), dp(4.5), handleRim)
        }
    }

    /** Only the handles take touches, and only their outer halves on a small overlay: the rest falls through to the canvas. */
    private fun onHandle(x: Double, y: Double): Boolean {
        val w = px(width)
        val reach = min(22.0, max(10.0, w / 4))
        return handles.any { handle ->
            val outward = if (handle.x < w / 2) x <= handle.x + reach / 2 else x >= handle.x - reach / 2
            outward && hypot(x - handle.x, y - handle.y) <= reach
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !onHandle(px(event.x), px(event.y))) return false
        return super.dispatchTouchEvent(event)
    }

    // The handle's reach goes past the view's edge.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val parent = parent as? View ?: return false
        val origin = IntArray(2)
        parent.getLocationOnScreen(origin)
        val fx = px(event.rawX - origin[0])
        val fy = px(event.rawY - origin[1])
        val cx = px(left + width / 2f)
        val cy = px(top + height / 2f)
        val vx = fx - cx
        val vy = fy - cy
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = vx
                startY = vy
                dragging = false
                this.parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val length = hypot(startX, startY)
                if (length <= 1) return true
                if (!dragging) {
                    dragging = true
                    onHandle?.invoke(Phase.Began, 1.0, 0.0)
                }
                val scale = hypot(vx, vy) / length
                // The angle between the two, never across atan2's ±π seam.
                val turn = atan2(startX * vy - startY * vx, startX * vx + startY * vy)
                onHandle?.invoke(Phase.Changed, scale, turn)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onHandle?.invoke(Phase.Ended, 1.0, 0.0)
                dragging = false
            }
        }
        return true
    }
}

/** Telegram's bin (happy-ios `StoryBinView`): a ring at the canvas's foot with a can in it, whose lid lifts as an overlay comes over it. */
class StoryBinView(context: Context) : ManualLayout(context) {
    var circle: Rect = Rect(0.0, 0.0, 0.0, 0.0)
        set(value) { if (field != value) { field = value; requestLayout() } }
    var open = false
        set(value) {
            if (field == value) return
            field = value
            val spring = OvershootInterpolator(2f)
            ring.animate().scaleX(if (value) 1.15f else 1f).scaleY(if (value) 1.15f else 1f).setInterpolator(spring).setDuration(200).start()
            lid.animate().translationX(if (value) dp(-2.0) else 0f).translationY(if (value) dp(-3.0) else 0f)
                .rotation(if (value) Math.toDegrees(-0.45).toFloat() else 0f).setInterpolator(spring).setDuration(200).start()
            stateDescription = if (value) "open" else null
        }

    private val ring = object : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }

        override fun onDraw(canvas: Canvas) {
            // A stroke 5 of the ring's 160.
            stroke.strokeWidth = max(dp(1.5), width * 5f / 160)
            val r = width / 2f
            canvas.drawCircle(r, r, r, fill)
            canvas.drawCircle(r, r, r - stroke.strokeWidth / 2, stroke)
        }
    }
    private val can = GlyphView(context, StoryEditorIcons.binCan)
    private val lid = GlyphView(context, StoryEditorIcons.binLid)

    init {
        isClickable = false
        clipChildren = false
        addView(ring)
        addView(can)
        addView(lid)
        tag = "story-editor-trash"
        contentDescription = context.getString(R.string.stories_trash)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onTouchEvent(event: MotionEvent?) = false

    override fun layoutSubviews(width: Double, height: Double) {
        ring.setFrame(circle)
        ring.pivotX = ring.width / 2f
        ring.pivotY = ring.height / 2f
        val icon = Rect(circle.minX + (circle.width - 30) / 2, circle.minY + (circle.height - 30) / 2, 30.0, 30.0)
        can.setFrame(icon)
        lid.setFrame(icon)
        lid.pivotX = lid.width / 2f
        lid.pivotY = lid.height / 2f
    }
}
