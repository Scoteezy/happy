package com.slopus.happy.ui.stories

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.slopus.happy.R
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.stories.StoryCanvasLayout
import com.slopus.happy.layout.stories.StoryTextEntryLayout
import com.slopus.happy.stories.StoryOverlay
import com.slopus.happy.stories.StoryTextAlign
import com.slopus.happy.stories.StoryTextFill
import com.slopus.happy.stories.StoryTextFont
import com.slopus.happy.stories.StoryTextStyle
import com.slopus.happy.stories.StoryTransform
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.density
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Typing a text overlay (happy-ios `StoryTextEntryView`), as Telegram's text
 * tool: the canvas dims and the text is typed on it, drawn as it will be
 * posted; Cancel and Done over the top; the size slider at the left edge;
 * and over the keyboard the colour, the fill, the alignment and the font.
 * Done hands back the text, its style and its size. The caret is an
 * EditText in clear ink over the overlay that draws the letters, scaled as
 * the overlay is.
 */
@SuppressLint("ViewConstructor")
class StoryTextEntryView(
    context: Context,
    initial: String,
    style: StoryTextStyle,
    scale: Double,
    /** The canvas, in this view, and its corners. */
    private val canvas: Rect,
    cornerRadius: Double,
) : ManualLayout(context) {
    var onDone: ((String, StoryTextStyle, Double) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    var style: StoryTextStyle = style
        private set
    var scale: Double = scale
        private set
    var statusBar = 0.0
    var safeBottom = 0.0
    /** The keyboard's height over this view's foot, dp. */
    var keyboard = 0.0
        set(value) { if (field != value) { field = value; requestLayout() } }

    private val fade = View(context)
    private val stage = FrameLayout(context)
    private val preview = StoryTextOverlayView(context, overlay(initial))
    /** Scaled and placed as the preview, holding the caret. */
    private val caretBox = FrameLayout(context)
    private val text = EditText(context)
    private val cancel = TextView(context)
    private val done = TextView(context)
    private val slider = StoryTextSizeSlider(context)
    private val bar = StoryTextBar(context)
    private var applying = false

    init {
        tag = "story-text-entry"
        clipChildren = false
        fade.setBackgroundColor(0x66000000)
        fade.clipToOutline = true
        fade.outlineProvider = StoryEditorView.rounded(cornerRadius)
        fade.setOnClickListener { finish() }
        addView(fade)
        stage.clipToOutline = true
        stage.outlineProvider = StoryEditorView.rounded(cornerRadius)
        stage.clipChildren = true
        stage.isClickable = true
        stage.setOnClickListener { finish() }
        addView(stage)
        stage.addView(preview)
        caretBox.clipChildren = false
        stage.addView(caretBox)
        text.background = null
        text.setPadding(0, 0, 0, 0)
        text.includeFontPadding = false
        text.setText(initial)
        text.setSelection(initial.length)
        text.setTextColor(Color.TRANSPARENT)
        text.highlightColor = 0x55FFFFFF
        text.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        text.isSingleLine = false
        text.isHorizontalScrollBarEnabled = false
        text.isVerticalScrollBarEnabled = false
        text.filters = arrayOf(InputFilter.LengthFilter(2_048))
        text.tag = "story-text-field"
        if (android.os.Build.VERSION.SDK_INT >= 29) text.textCursorDrawable?.setTint(Color.WHITE)
        text.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!applying) redraw() }
        })
        caretBox.addView(text)
        for ((button, title, id) in listOf(Triple(cancel, R.string.stories_cancel, "story-text-cancel"), Triple(done, R.string.stories_done, "story-text-done"))) {
            button.text = context.getString(title)
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
            button.setTextColor(Color.WHITE)
            button.gravity = Gravity.CENTER
            button.tag = id
            button.isClickable = true
            addView(button)
        }
        cancel.setOnClickListener { cancel() }
        done.setOnClickListener { finish() }
        slider.value = StoryTextEntryLayout.slider(scale)
        slider.onChange = { resize(it) }
        addView(slider)
        bar.onColor = { chooseColor() }
        bar.onStyle = { nextFill() }
        bar.onAlign = { nextAlign() }
        bar.onFont = { choose(it) }
        addView(bar)
        apply()
    }

    private fun overlay(string: String) = StoryOverlay.Text("entry", string, style, StoryTransform(0.5, 0.5, scale, 0.0))

    fun begin() {
        text.requestFocus()
        post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(text, 0) }
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(text.windowToken, 0)
        text.clearFocus()
    }

    // Style

    /** Telegram's style button: plain, filled, half, and round again. */
    private fun nextFill() {
        style = style.copy(
            fill = when (style.fill) {
                StoryTextFill.Regular -> StoryTextFill.Filled
                StoryTextFill.Filled -> StoryTextFill.Semi
                StoryTextFill.Semi, StoryTextFill.Stroke, StoryTextFill.Blur -> StoryTextFill.Regular
            },
        )
        apply()
    }

    private fun nextAlign() {
        val all = StoryTextAlign.entries
        style = style.copy(align = all[(all.indexOf(style.align) + 1) % all.size])
        apply()
    }

    private fun choose(font: StoryTextFont) {
        style = style.copy(font = font)
        apply()
    }

    /** The system has no colour picker: a spectrum to touch, as iOS's picker's Spectrum tab. */
    private fun chooseColor() {
        val spectrum = StoryColorSpectrum(context)
        spectrum.chosen = StoryOverlayView.color(style.color) ?: Color.WHITE
        spectrum.onChange = { color ->
            style = style.copy(color = StoryOverlayView.hex(color))
            apply()
        }
        val holder = FrameLayout(context).apply {
            val side = dp(20.0).toInt()
            setPadding(side, dp(12.0).toInt(), side, 0)
            addView(spectrum, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(280.0).toInt()))
        }
        AlertDialog.Builder(ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog_Alert))
            .setTitle(context.getString(R.string.stories_color))
            .setView(holder)
            .setPositiveButton(context.getString(R.string.stories_done), null)
            .setOnDismissListener { begin() }
            .show()
    }

    /**
     * The slider sets the font; the text keeps wrapping at 90% of the
     * canvas, as Telegram's does, so a larger size wraps sooner.
     */
    private fun resize(value: Double) {
        scale = rounded(StoryTextEntryLayout.scale(value))
        style = style.copy(width = width(scale))
        apply()
    }

    /** A new look: the caret's field takes the font and alignment, and the text is drawn again. */
    private fun apply() {
        val paint = StoryTextOverlayView.paint(context, style, canvas.size, inked = false)
        applying = true
        text.typeface = paint.typeface
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, paint.textSize)
        text.gravity = Gravity.TOP or when (style.align) {
            StoryTextAlign.Left -> Gravity.START
            StoryTextAlign.Center -> Gravity.CENTER_HORIZONTAL
            StoryTextAlign.Right -> Gravity.END
        }
        applying = false
        bar.show(style)
        slider.stateDescription = String.format(java.util.Locale.US, "%.2f", scale)
        redraw()
    }

    private fun redraw() {
        preview.update(overlay(text.text.toString()))
        requestLayout()
    }

    private fun finish() {
        hideKeyboard()
        onDone?.invoke(text.text.toString(), style, scale)
    }

    fun cancel() {
        hideKeyboard()
        onCancel?.invoke()
    }

    // Layout

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = StoryTextEntryLayout(
            Rect(0.0, 0.0, width, height), statusBar, statusBar, safeBottom, keyboard,
            px(cancel.paint.measureText(cancel.text.toString())), px(done.paint.measureText(done.text.toString())),
        )
        fade.setFrame(canvas)
        stage.setFrame(canvas)
        cancel.setFrame(layout.cancel)
        done.setFrame(layout.done)
        slider.setFrame(layout.slider)
        // Over the keyboard, or the foot of the screen while it is coming.
        val barHeight = StoryTextEntryLayout.Metrics.bar
        val floor = if (keyboard > 0) height - keyboard else height - safeBottom
        bar.setFrame(Rect(0.0, floor - barHeight, width, barHeight))
        bar.visibility = if (keyboard > 0) VISIBLE else INVISIBLE
        preview.place(canvas.size)
        // The field turns and grows with the preview.
        caretBox.setFrame(Rect(px(preview.left), px(preview.top), px(preview.width), px(preview.height)))
        caretBox.pivotX = preview.pivotX
        caretBox.pivotY = preview.pivotY
        caretBox.scaleX = preview.scaleX
        caretBox.scaleY = preview.scaleY
        caretBox.rotation = preview.rotation
        val frame = preview.textFrame(canvas.size) ?: return
        // Tall enough for the caret on an empty line.
        val line = px(text.paint.fontSpacing)
        text.setFrame(Rect(frame.minX, frame.minY, frame.width, max(frame.height, ceil(line))))
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Over the typed text the caret's field takes the touch, wherever the preview drew it.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val box = RectF()
            preview.matrix.mapRect(box.apply { set(0f, 0f, preview.width.toFloat(), preview.height.toFloat()) })
            box.offset((stage.left + preview.left).toFloat(), (stage.top + preview.top).toFloat())
            if (box.contains(ev.x, ev.y) && text.hasFocus()) return super.dispatchTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        fun width(scale: Double): Double = rounded(min(1.0, max(0.1, StoryTextEntryLayout.Metrics.newWidth / max(0.05, scale))))

        private fun rounded(value: Double): Double = (value * 1000).roundToInt() / 1000.0
    }
}

/** The text size slider at the canvas's left edge: a tapering track half out of view until held, and a knob riding it. */
class StoryTextSizeSlider(context: Context) : View(context) {
    var onChange: ((Double) -> Unit)? = null
    var value = 0.5
        set(v) { field = v; invalidate() }
    private var tracking = false
    private var shift = 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4D878787 }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shape = Path()

    init {
        tag = "story-text-size"
        contentDescription = context.getString(R.string.stories_text_size)
        isClickable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        knob.setShadowLayer(dp(4.5), 0f, 0f, 0x4D000000)
    }

    /** Half out of view at rest; out beside the edge while held. */
    private fun slide() {
        val to = if (tracking) dp(8.0) else -width / 2f
        android.animation.ValueAnimator.ofFloat(shift, to).apply {
            duration = 200
            addUpdateListener { shift = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!tracking) shift = -w / 2f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (!tracking) {
                    tracking = true
                    parent.requestDisallowInterceptTouchEvent(true)
                    slide()
                }
                value = StoryTextEntryLayout.value(px(event.y), px(height))
                onChange?.invoke(value)
            }
            else -> {
                tracking = false
                slide()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val top = dp(15.0)
        val bottom = dp(3.0)
        shape.reset()
        shape.addArc(RectF(w / 2 - top, 0f, w / 2 + top, 2 * top), 180f, 180f)
        shape.lineTo(w / 2 + bottom, h - bottom)
        shape.addArc(RectF(w / 2 - bottom, h - 2 * bottom, w / 2 + bottom, h), 0f, 180f)
        shape.lineTo(w / 2 - top, top)
        shape.close()
        canvas.save()
        canvas.translate(shift, 0f)
        canvas.drawPath(shape, track)
        val y = dp(StoryTextEntryLayout.knob(value, px(height)))
        canvas.drawCircle(w / 2 + if (tracking) 0f else dp(4.0), y, dp(16.0), knob)
        canvas.restore()
    }
}

/** The bar over the keyboard while typing: the colour, the fill, the alignment, and the font in its pill at the right. */
class StoryTextBar(context: Context) : ManualLayout(context) {
    var onColor: (() -> Unit)? = null
    var onStyle: (() -> Unit)? = null
    var onAlign: (() -> Unit)? = null
    var onFont: ((StoryTextFont) -> Unit)? = null
    private val swatch = GlyphView(context)
    private val style = GlyphView(context)
    private val align = GlyphView(context)
    private val font = FontPill(context)
    private var shown: StoryTextStyle? = null

    init {
        for ((view, id, label) in listOf(Triple(swatch, "story-text-color", R.string.stories_color), Triple(style, "story-text-fill", R.string.stories_fill), Triple(align, "story-text-align", R.string.stories_align))) {
            view.tag = id
            view.contentDescription = context.getString(label)
            view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            view.isClickable = true
            addView(view)
        }
        swatch.setOnClickListener { onColor?.invoke() }
        style.setOnClickListener { onStyle?.invoke() }
        align.setOnClickListener { onAlign?.invoke() }
        font.tag = "story-text-font"
        font.contentDescription = context.getString(R.string.stories_font)
        font.setOnClickListener { fontMenu() }
        addView(font)
    }

    private fun fontMenu() {
        val menu = PopupMenu(ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault), font)
        for (choice in StoryTextFont.entries) {
            menu.menu.add(StoriesStrings.fontName(context, choice)).setOnMenuItemClickListener { onFont?.invoke(choice); true }
        }
        menu.show()
    }

    fun show(next: StoryTextStyle) {
        if (next == shown) return
        shown = next
        swatch.glyph = StoryEditorIcons.swatch(StoryOverlayView.color(next.color) ?: Color.WHITE)
        swatch.stateDescription = next.color
        style.glyph = StoryEditorIcons.style(next.fill)
        style.stateDescription = next.fill.wire
        align.glyph = StoryEditorIcons.align(next.align)
        align.stateDescription = next.align.wire
        font.title = StoriesStrings.fontName(context, next.font)
        font.typeface = StoryTextOverlayView.typeface(next.font)
        font.stateDescription = next.font.wire
        requestLayout()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val bar = StoryTextEntryLayout.bar(width, font.titleWidth)
        swatch.setFrame(bar.swatch)
        style.setFrame(bar.style)
        align.setFrame(bar.align)
        font.setFrame(bar.font)
    }

    private class FontPill(context: Context) : View(context) {
        var title = ""
            set(value) { field = value; invalidate() }
        var typeface: android.graphics.Typeface? = null
            set(value) { field = value; text.typeface = value; invalidate() }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13 * density }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }

        val titleWidth: Double get() = px(text.measureText(title))

        init {
            isClickable = true
            isFocusable = true
        }

        override fun onDraw(canvas: Canvas) {
            rim.strokeWidth = dp(1.0)
            val r = dp(11.0)
            val inset = rim.strokeWidth / 2
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, r, r, rim)
            val fm = text.fontMetrics
            canvas.drawText(title, dp(StoryTextEntryLayout.Metrics.fontLeft), height / 2f - (fm.ascent + fm.descent) / 2, text)
            val arrow = StoryEditorIcons.fontArrow
            arrow.draw(canvas, width - dp(24.0), (height - dp(16.0)) / 2, Color.WHITE)
        }
    }
}

/** A hue across and brightness down, with white along the top: touched or dragged, it gives the colour under the finger. */
class StoryColorSpectrum(context: Context) : View(context) {
    var onChange: ((Int) -> Unit)? = null
    var chosen: Int = Color.WHITE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bitmap: android.graphics.Bitmap? = null
    private var touch: Pair<Float, Float>? = null
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val drawn = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(drawn)
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        val across = LinearGradient(0f, 0f, w.toFloat(), 0f, hues, null, Shader.TileMode.CLAMP)
        // White at the top, the pure hue at a third, black at the foot.
        val down = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(Color.WHITE, 0x00FFFFFF, 0x00000000, Color.BLACK), floatArrayOf(0f, 0.33f, 0.34f, 1f), Shader.TileMode.CLAMP)
        val p = Paint()
        p.shader = across
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = down
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        bitmap = drawn
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bitmap = bitmap ?: return true
        parent.requestDisallowInterceptTouchEvent(true)
        val x = event.x.coerceIn(0f, width - 1f)
        val y = event.y.coerceIn(0f, height - 1f)
        touch = x to y
        chosen = bitmap.getPixel(x.toInt(), y.toInt()) or 0xFF000000.toInt()
        onChange?.invoke(chosen)
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = bitmap ?: return
        val r = dp(10.0)
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        canvas.restore()
        val (x, y) = touch ?: return
        ring.strokeWidth = dp(3.0)
        canvas.drawCircle(x, y, dp(12.0), ring)
    }
}
