package com.slopus.happy.ui.stemcells

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.core.view.ViewCompat
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.icons.IconCatalog
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.FontFace
import com.slopus.happy.layout.HeaderScrim
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.RGBA
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellsMetrics
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.stemcells.MakeStuffIntroLayout
import com.slopus.happy.layout.stemcells.StemCellBraille
import com.slopus.happy.layout.stemcells.StemCellChatFootLayout
import com.slopus.happy.layout.stemcells.StemCellChatHeaderLayout
import com.slopus.happy.layout.stemcells.StemCellElapsed
import com.slopus.happy.layout.stemcells.StemCellWorkingLayout
import com.slopus.happy.layout.shadow
import com.slopus.happy.stemcells.StemCellActivity
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.home.HeaderControlView
import com.slopus.happy.ui.home.HeaderScrimView
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.setFrameOrHide
import com.slopus.happy.ui.view.toAndroidPath
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MakeStuffIntro (happy-ios `StemCellIntroView`): the work's 64px mark
 * centred over its line. Once the conversation has begun the line keeps
 * its place, hidden.
 */
class StemCellIntroView(context: Context) : ManualLayout(context), ThemeAware {
    sealed interface Mark {
        data class StemCell(val seed: String) : Mark
        data class Clay(val slug: String) : Mark
    }

    private val stemCell = StemCellMarkView(context)
    private val clay = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    /** `.happy-make-stuff__line`: 14/20, centred. */
    private val line = Label(context, TextStyle(FontFace.Figtree, 14, 20), Palette.text, alignment = Layout.Alignment.ALIGN_CENTER).apply {
        text = context.getString(R.string.stem_intro_line)
    }
    private var settled = false
    private var placed: MakeStuffIntroLayout? = null

    init {
        tag = "stem-intro"
        addView(stemCell)
        addView(clay)
        addView(line)
    }

    fun configure(mark: Mark, settled: Boolean) {
        when (mark) {
            is Mark.StemCell -> {
                stemCell.seed = mark.seed
                stemCell.visibility = VISIBLE
                clay.visibility = GONE
            }
            is Mark.Clay -> {
                clay.setImageBitmap(ClayIconImages.image(context, mark.slug))
                clay.visibility = VISIBLE
                stemCell.visibility = GONE
            }
        }
        this.settled = settled
        line.visibility = if (settled) INVISIBLE else VISIBLE
    }

    fun layout(width: Double) = MakeStuffIntroLayout(width, line.measurable, settled)

    fun place(layout: MakeStuffIntroLayout) {
        placed = layout
        requestLayout()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = placed ?: return
        stemCell.setFrame(layout.mark)
        clay.setFrame(layout.mark)
        line.setFrame(layout.line)
    }

    override fun applyTheme() {
        stemCell.applyTheme()
        line.applyTheme()
    }
}

/**
 * Desktop's working line (`AgentWorkingStatus`; happy-ios
 * `StemCellWorkingView`) at the transcript's foot: the braille loader, the
 * turn's clock, a "·", and the phase under the calm sheen shimmer,
 * changing in place as the agent's status moves. A scheduled wait takes
 * the loader's column with its ring and counts down; a question waiting on
 * the person shows the red question mark, still. The clock ticks as each
 * of the turn's seconds turns over.
 */
class StemCellWorkingView(context: Context) : ManualLayout(context), ThemeAware {
    private val font = TextStyle(FontFace.Figtree, StemCellWorkingLayout.Metrics.fontSize, StemCellWorkingLayout.Metrics.line)
    private val loader = StemCellBrailleView(context)
    private val ring = StemCellWaitRingView(context)
    private val icon = IconView(context, StemCellsGlyphs.icon("question"), StemCellWorkingLayout.Metrics.column, StemCellsPalette.stemCellsQuestion)
    private val timer = Label(context, font, StemCellsPalette.stemCellsWorkingText, maxLines = 1, alignment = Layout.Alignment.ALIGN_OPPOSITE).apply { followsTextSize = false }
    private val separator = Label(context, font, StemCellsPalette.stemCellsWorkingSeparator, maxLines = 1).apply {
        followsTextSize = false
        text = context.getString(R.string.stem_working_separator)
    }
    private val phase = StemCellsShimmerLabel(context).apply {
        style = font
        color = StemCellsPalette.stemCellsWorkingText
    }
    var activity: StemCellActivity? = null
        private set
    private val clock = Runnable { tick(); scheduleTick() }

    init {
        tag = "stem-working"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        for (view in listOf(loader, ring, icon, timer, separator, phase)) addView(view)
    }

    fun show(activity: StemCellActivity?) {
        val previous = this.activity
        this.activity = activity
        visibility = if (activity == null) GONE else VISIBLE
        if (activity == null) {
            phase.shimmering = false
            loader.running = false
            removeCallbacks(clock)
            return
        }
        val question = activity.phase == StemCellActivity.Phase.AwaitingAnswer
        val waiting = activity.phase is StemCellActivity.Phase.Waiting
        icon.visibility = if (question) VISIBLE else GONE
        ring.visibility = if (waiting) VISIBLE else GONE
        loader.visibility = if (question || waiting) GONE else VISIBLE
        loader.running = loader.visibility == VISIBLE
        // Only work in progress shimmers; a question or a wait is still.
        phase.shimmering = !question && !waiting
        if (previous == null || previous.startedAt != activity.startedAt || waitDue(previous) != waitDue(activity)) scheduleTick()
        tick()
    }

    /** The parts that change with time: the clock, a wait's ring and countdown. */
    private fun tick() {
        val activity = activity ?: return
        val now = System.currentTimeMillis().toDouble()
        val started = activity.startedAt
        timer.text = started?.let { StemCellElapsed.text(now - it) } ?: ""
        val text = when (val phase = activity.phase) {
            StemCellActivity.Phase.WaitingForModel -> context.getString(R.string.stem_phase_waiting_for_model)
            StemCellActivity.Phase.Thinking -> context.getString(R.string.stem_phase_thinking)
            StemCellActivity.Phase.PreparingTools -> context.getString(R.string.stem_phase_preparing_tools)
            StemCellActivity.Phase.RunningTools -> context.getString(R.string.stem_phase_running_tools)
            StemCellActivity.Phase.Working -> context.getString(R.string.stem_phase_working)
            StemCellActivity.Phase.Delegating -> context.getString(R.string.stem_phase_delegating)
            StemCellActivity.Phase.AwaitingAnswer -> context.getString(R.string.stem_phase_awaiting_answer)
            is StemCellActivity.Phase.Retrying -> retrying(phase)
            is StemCellActivity.Phase.Waiting -> {
                val wait = phase.wait
                val span = wait.dueAt - wait.startedAt
                ring.progress = if (span > 0) min(1.0, max(0.0, (now - wait.startedAt) / span)) else 1.0
                if (wait.dueAt > now) context.getString(R.string.stem_phase_wait_for, StemCellElapsed.remaining(wait.dueAt - now)) else context.getString(R.string.stem_phase_wait_ending)
            }
        }
        phase.text = text
        val shownTimer = if (started != null) "${timer.text} ${context.getString(R.string.stem_working_separator)} " else ""
        contentDescription = shownTimer + text
        requestLayout()
    }

    private fun retrying(phase: StemCellActivity.Phase.Retrying): String {
        val retry = phase.retrying
        val id = when (retry.kind) {
            "context_too_long" -> return context.getString(R.string.stem_retry_context)
            "provider_unavailable" -> R.string.stem_retry_overloaded
            "rate_limited" -> R.string.stem_retry_rate_limited
            "network" -> R.string.stem_retry_network
            else -> R.string.stem_retry_other
        }
        return context.getString(id, retry.vendorName, retry.attempt, retry.maxAttempts)
    }

    private fun waitDue(activity: StemCellActivity?): Double? = (activity?.phase as? StemCellActivity.Phase.Waiting)?.wait?.dueAt

    /** Ticks as each second turns over: a wait's own, else the turn's. Nothing ticks where nothing moves. */
    private fun scheduleTick() {
        removeCallbacks(clock)
        val activity = activity ?: return
        if (!isAttachedToWindow) return
        val origin = waitDue(activity) ?: activity.startedAt ?: return
        val now = System.currentTimeMillis().toDouble()
        val into = ((now - origin) % 1000 + 1000) % 1000
        postDelayed(clock, (1000 - into).toLong().coerceAtLeast(1))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (activity != null) {
            scheduleTick()
            tick()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(clock)
        super.onDetachedFromWindow()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val activity = activity ?: return
        val timed = activity.startedAt != null
        val layout = StemCellWorkingLayout(
            width, if (loader.visibility == VISIBLE) StemCellWorkingLayout.Glyph.Loader else StemCellWorkingLayout.Glyph.Mark,
            timer = if (timed) timer.measure(100_000.0).width else null,
            separator = if (timed) separator.measure(100_000.0).width else null,
            // All the room left: the label ends its own line.
            phase = Double.MAX_VALUE,
        )
        loader.setFrame(layout.glyph)
        ring.setFrame(layout.glyph)
        icon.setFrame(layout.glyph)
        timer.setFrameOrHide(layout.timer)
        separator.setFrameOrHide(layout.separator)
        phase.setFrame(layout.phase)
    }

    override fun applyTheme() {
        for (view in listOf(loader, ring, icon, timer, separator, phase)) (view as? ThemeAware)?.applyTheme()
    }
}

/**
 * Desktop's `braille-2` loader (`spinnerBrailleCanvas.ts`; happy-ios
 * `StemCellBrailleView`) on the screen's own pixels: one hard round stamp
 * sized for the density, at eight whole-pixel origins, so no dot looks
 * larger than another. Every dot runs the same opacity wave, a tenth of a
 * second apart in `dots2`'s order, redrawn each frame while it runs.
 */
class StemCellBrailleView(context: Context) : View(context), ThemeAware {
    private val paint = Paint()
    private var stamp: List<Pair<Int, Int>> = emptyList()
    private var origins: StemCellBraille.Origins? = null
    private var builtFor = -1 to -1

    var running = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun applyTheme() = invalidate()

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        if (builtFor != width to height) {
            val built = StemCellBraille.origins(width, height)
            origins = built
            stamp = StemCellBraille.stamp(built.diameter)
            builtFor = width to height
        }
        val origins = origins ?: return
        val ink = Theme.color(StemCellsPalette.stemCellsWorkingText)
        val animating = running && ValueAnimator.areAnimatorsEnabled()
        val time = SystemClock.uptimeMillis().toDouble()
        for ((index, origin) in origins.origins.withIndex()) {
            val phase = StemCellBraille.phases.getOrElse(index) { 0 }
            // opacity(t - phase): the dot's own time runs a phase behind.
            val opacity = if (animating) StemCellBraille.opacity(time - phase * StemCellBraille.FRAME) else 1.0
            paint.color = ink
            paint.alpha = (((ink ushr 24) and 0xFF) * opacity).roundToInt()
            for ((y, span) in stamp.withIndex()) {
                if (span.second <= 0) continue
                val left = (origin.first + span.first).toFloat()
                val top = (origin.second + y).toFloat()
                canvas.drawRect(left, top, left + span.second, top + 1, paint)
            }
        }
        if (animating && isAttachedToWindow) postInvalidateOnAnimation()
    }
}

/** Desktop's `WaitRing` (happy-ios `StemCellWaitRingView`): the share of a wait spent, an arc filling clockwise from the top over a quiet track. */
class StemCellWaitRingView(context: Context) : View(context), ThemeAware {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    var progress = 0.0
        set(value) { if (field != value) { field = value; invalidate() } }

    override fun applyTheme() = invalidate()

    override fun onDraw(canvas: Canvas) {
        val stroke = StemCellWorkingLayout.Metrics.ringStroke
        val radius = dp(StemCellWorkingLayout.ringRadius(com.slopus.happy.layout.Size(px(width), px(height))))
        oval.set(width / 2f - radius, height / 2f - radius, width / 2f + radius, height / 2f + radius)
        paint.strokeWidth = dp(stroke)
        paint.color = Theme.color(StemCellsPalette.stemCellsDivider)
        canvas.drawArc(oval, 0f, 360f, false, paint)
        paint.color = Theme.color(StemCellsPalette.stemCellsWorkingText)
        canvas.drawArc(oval, -90f, (360 * progress).toFloat(), false, paint)
    }
}

/**
 * A glyph that can carry the sheen every working thing in Stem Cells does
 * (`StemCellsShimmerLabel`'s band, over an icon): the arrow of the way
 * back to the newest message while the agent works.
 */
class StemCellsShimmerGlyph(context: Context, private val icon: Icon, private val size: Double) : View(context), ThemeAware {
    var color: ColorToken = Palette.text
    var ground: ColorToken = Palette.surface
    var shimmering = false
        set(value) {
            if (field == value) return
            field = value
            sweepStart = if (value) SystemClock.uptimeMillis() else null
            invalidate()
        }
    private var sweepStart: Long? = null
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pale = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun applyTheme() = invalidate()

    override fun onDraw(canvas: Canvas) {
        val glyph = IconCatalog.glyph(icon) ?: return
        val d = Theme.density
        val scale = d.toDouble()
        val layout = glyph.layout(size, scale)
        val box = layout.frame(Rect((px(width) - size) / 2, (px(height) - size) / 2, size, size), scale)
        glyph.path.toAndroidPath(scale = (layout.glyphScale * scale).toFloat(), into = path)
        val ink = Theme.rgba(color)
        paint.color = ink.argb
        canvas.save()
        canvas.translate((box.x * d).toFloat(), (box.y * d).toFloat())
        canvas.drawPath(path, paint)
        val start = sweepStart
        if (shimmering && start != null && ValueAnimator.areAnimatorsEnabled()) {
            val glyphWidth = (size * d).toFloat()
            val period = (StemCellsMetrics.sheenDuration * 1000).toLong()
            val phase = ((SystemClock.uptimeMillis() - start) % period) / period.toFloat()
            val peak = -0.5f * glyphWidth + 2f * glyphWidth * phase
            val reach = 0.3f * glyphWidth
            val tone = mix(ink, Theme.rgba(ground), StemCellsMetrics.sheenInk).argb
            pale.shader = LinearGradient(peak - reach, 0f, peak + reach, 0f, intArrayOf(tone and 0x00FFFFFF, tone, tone and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            canvas.drawPath(path, pale)
            postInvalidateOnAnimation()
        }
        canvas.restore()
    }

    private fun mix(ink: RGBA, ground: RGBA, keep: Double): RGBA {
        val rest = 1 - keep
        return RGBA(ink.red * keep + ground.red * rest, ink.green * keep + ground.green * rest, ink.blue * keep + ground.blue * rest, ink.alpha * keep + ground.alpha * rest)
    }
}

/**
 * Desktop's way back to the newest message (`.happy-message-list__jump`;
 * happy-ios `StemCellChatJumpButton`): a small secondary circle with the
 * arrow down, its hairline and soft shadow, shown while the reader is away
 * from the end. While the agent works its arrow carries the sheen. Its
 * touch reaches past the drawn 32.
 */
class StemCellChatJumpButton(context: Context) : ManualLayout(context), ThemeAware {
    private val arrow = StemCellsShimmerGlyph(context, StemCellsGlyphs.icon("arrow-down-outline"), StemCellChatFootLayout.Metrics.jumpIcon)
    private val shape = GradientDrawable().apply { shape = GradientDrawable.OVAL }

    /** Desktop's rule: the arrow shimmers while the working line's loader runs, not on a question or a scheduled wait. */
    companion object {
        fun spins(activity: StemCellActivity?): Boolean = when (activity?.phase) {
            null, StemCellActivity.Phase.AwaitingAnswer, is StemCellActivity.Phase.Waiting -> false
            else -> true
        }
    }

    var working = false
        set(value) {
            field = value
            arrow.shimmering = value
            stateDescription = context.getString(if (value) R.string.stem_jump_working else R.string.stem_jump_idle)
        }

    init {
        tag = "stem-jump-latest"
        background = shape
        contentDescription = context.getString(R.string.stem_jump_to_latest)
        stateDescription = context.getString(R.string.stem_jump_idle)
        isClickable = true
        isFocusable = true
        elevation = dp(StemCellChatFootLayout.Metrics.jumpShadowRadius)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        addView(arrow)
        applyTheme()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        arrow.setFrame(Rect(0.0, 0.0, width, height))
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        arrow.alpha = if (pressed) 0.6f else 1f
    }

    override fun applyTheme() {
        shape.setColor(Theme.color(Palette.surfaceHigh))
        shape.setStroke(dp(1), Theme.color(Palette.divider))
        outlineSpotShadowColor = Theme.color(Palette.shadow)
        outlineAmbientShadowColor = Theme.color(Palette.shadow)
        arrow.color = Palette.text
        arrow.ground = Palette.surface
        arrow.applyTheme()
    }
}

/**
 * A conversation's header (happy-ios `StemCellChatHeaderView`), as
 * Telegram's on iOS 26: a glass back circle and the name in a glass capsule
 * to the right edge, with the agent's status in grey under it, floating
 * over the transcript, which scrolls under them and a scrim. The capsule
 * opens the conversation's detail. Frames are [StemCellChatHeaderLayout]'s.
 * Touches between the controls go through to the transcript.
 */
class StemCellChatHeaderView(context: Context) : ManualLayout(context), ThemeAware {
    var onBack: (() -> Unit)? = null
    var onOpen: (() -> Unit)? = null

    private val scrim = HeaderScrimView(context)
    private val backGlass = HeaderControlView(context)
    private val backIcon = IconView(context, Icon.ionicons("chevron-back"), 28.0, Palette.headerTint)
    private val pill = HeaderControlView(context)
    private val title = Label(context, TextStyles.nativeHeaderTitle, Palette.headerTint, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER).apply {
        tag = "stem-chat-title"
        followsTextSize = false
    }
    private val statusLabel = Label(context, TextStyle(FontFace.System, 13, 16), Palette.textSecondary, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER).apply {
        tag = "stem-chat-status"
        followsTextSize = false
    }

    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            title.text = value
            pill.contentDescription = value
            requestLayout()
        }

    /** What the agent is doing, under its name; null for nothing. */
    var status: String? = null
        set(value) {
            if (field == value) return
            field = value
            statusLabel.text = value ?: ""
            requestLayout()
        }

    /** How far the transcript has scrolled under the row's foot: the scrim comes in with it. */
    var scrolledUnder = 0.0
        set(value) {
            field = value
            scrim.presence = HeaderScrim.presence(value).toFloat()
        }

    /** The status bar's height: the row starts under it. */
    var safeTop = 0.0
        set(value) { if (field != value) { field = value; requestLayout() } }

    init {
        scrim.presence = 0f
        addView(scrim)
        backGlass.tag = "stem-chat-back"
        backGlass.contentDescription = context.getString(R.string.stem_back)
        backGlass.isClickable = true
        backGlass.setOnClickListener { onBack?.invoke() }
        backGlass.addView(backIcon)
        addView(backGlass)
        pill.tag = "stem-chat-capsule"
        pill.isClickable = true
        pill.setOnClickListener { onOpen?.invoke() }
        addView(pill)
        title.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Over the capsule, which stands at its shadow's elevation; casting nothing.
        for (label in listOf(title, statusLabel)) {
            label.outlineProvider = null
            label.elevation = pill.elevation + 1
            addView(label)
        }
    }

    fun layout(width: Double): StemCellChatHeaderLayout {
        val statusWidth = status?.let { statusLabel.measure(UNBOUNDED).width }
        return StemCellChatHeaderLayout(width, safeTop, title.measure(UNBOUNDED).width, statusWidth)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = layout(width)
        scrim.setFrame(layout.scrim)
        backGlass.setFrame(layout.back)
        backIcon.setFrame(Rect(0.0, 0.0, layout.back.width, layout.back.height))
        pill.setFrame(layout.pill)
        // The name's own line, centred on its place in the capsule.
        val line = title.lineHeight
        title.setFrame(Rect(layout.title.minX, layout.title.midY - line / 2, layout.title.width, line))
        statusLabel.setFrameOrHide(layout.status)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    private companion object {
        /** A single line's whole width. */
        const val UNBOUNDED = 100_000.0
    }

    override fun applyTheme() {
        scrim.applyTheme()
        backGlass.applyTheme()
        pill.applyTheme()
        backIcon.applyTheme()
        title.applyTheme()
        statusLabel.applyTheme()
    }
}
