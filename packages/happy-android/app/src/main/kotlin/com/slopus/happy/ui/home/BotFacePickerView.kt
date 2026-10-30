package com.slopus.happy.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.slopus.happy.R
import com.slopus.happy.home.BotFacePainter
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.BotFacePickerLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.botFaceCredit
import com.slopus.happy.layout.botFaceHeading
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** HappyUI BotFacePickerView: selection never repaints or moves the image. */
class BotFacePickerView(context: Context) : ManualLayout(context), ThemeAware {
    var onPick: ((Int) -> Unit)? = null
    var onRoll: (() -> Unit)? = null
    var onRefuse: (() -> Unit)? = null
    var refusing = false
    private val heading = Label(context, TextStyles.botFaceHeading, Palette.textSecondary).apply {
        text = context.getString(R.string.home_dock_bot_avatar)
    }
    private val credit = Label(context, TextStyles.botFaceCredit, Palette.textSecondary, maxLines = 1)
    private val lead = Label(context, TextStyles.botFaceCredit, Palette.textSecondary, maxLines = 1)
    private val link = View(context)
    private val faces = List(BotFacePickerLayout.Metrics.faceCount) { FaceButton(context, it) }
    private val die = IconView(context, Icon.ionicons("dice-outline"), BotFacePickerLayout.Metrics.dieIconSize)
    val text get() = BotFacePickerLayout.Text(heading.measurable, credit.measurable, lead.measurable)

    init {
        addView(heading)
        faces.forEachIndexed { index, face ->
            addView(face)
            face.setOnClickListener { action { onPick?.invoke(index) } }
        }
        addView(die)
        die.contentDescription = context.getString(R.string.home_dock_bot_roll)
        die.accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        }
        die.setOnClickListener { action { onRoll?.invoke() } }
        val prefix = context.getString(R.string.home_dock_bot_art_by) + " "
        lead.text = prefix
        credit.text = SpannableString(prefix + ARTIST).apply { setSpan(UnderlineSpan(), prefix.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        credit.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(credit)
        link.contentDescription = ARTIST
        link.setOnClickListener { action { Alerts.open(context, ARTIST_URL) } }
        addView(link)
        applyTheme()
    }

    override fun applyTheme() {
        die.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Theme.color(Palette.optionSelected))
            })
            addState(intArrayOf(), ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
    }

    private fun action(block: () -> Unit) { if (refusing) onRefuse?.invoke() else block() }

    fun configure(seeds: List<String>, slot: Int) {
        faces.forEachIndexed { index, face ->
            face.isSelected = index == slot
            face.show(seeds.getOrNull(index))
        }
    }

    override fun sizeThatFits(width: Double, height: Double): Size = BotFacePickerLayout(width, text).size

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = BotFacePickerLayout(width, text)
        heading.setFrame(layout.heading)
        faces.zip(layout.faces).forEach { (face, frame) -> face.setFrame(frame) }
        die.setFrame(layout.die)
        credit.setFrame(layout.credit)
        link.setFrame(layout.creditLink)
    }

    private class FaceButton(context: Context, index: Int) : View(context), ThemeAware {
        private var seed: String? = null
        private var bitmap: Bitmap? = null
        private var rendered: Pair<String, Int>? = null
        private var scope: CoroutineScope? = null
        private var job: Job? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val clip = Path()

        init { contentDescription = context.getString(R.string.home_dock_bot_face, index + 1) }

        fun show(next: String?) {
            if (seed == next) return
            seed = next
            if (next == null) { bitmap = null; rendered = null; job?.cancel(); invalidate() }
            render()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            render()
        }

        override fun onDetachedFromWindow() {
            scope?.cancel()
            scope = null
            rendered = null
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { render() }

        private fun render() {
            val scope = scope ?: return
            val seed = seed ?: return
            val pixels = ceil(width - 2 * dp(BotFacePickerLayout.Metrics.ringWidth).toDouble()).toInt()
            if (pixels <= 0 || rendered == (seed to pixels)) return
            val request = seed to pixels
            rendered = request
            job?.cancel()
            job = scope.launch {
                try {
                    val image = BotFacePainter.of(context).image(seed, pixels)
                    if (rendered == request) { bitmap = image; invalidate() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed preview is retried on the next sizing/attachment, not a crashed dock.
                    if (rendered == request) rendered = null
                }
            }
        }

        override fun setSelected(selected: Boolean) { super.setSelected(selected); invalidate() }
        override fun applyTheme() = invalidate()

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
        }

        override fun onDraw(canvas: Canvas) {
            val m = BotFacePickerLayout.Metrics
            val ring = dp(m.ringWidth)
            val inside = RectF(ring, ring, width - ring, height - ring)
            bitmap?.let { image ->
                clip.reset()
                clip.addRoundRect(inside, dp(m.imageRadius), dp(m.imageRadius), Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clip)
                paint.style = Paint.Style.FILL
                canvas.drawBitmap(image, null, inside, paint)
                canvas.restore()
            }
            if (isSelected) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = ring
                paint.color = Theme.color(Palette.text)
                val inset = ring / 2
                canvas.drawRoundRect(RectF(inset, inset, width - inset, height - inset), dp(m.faceRadius) - inset, dp(m.faceRadius) - inset, paint)
            }
        }
    }

    companion object {
        // Bundled Adventurer Neutral's credit, kept independent of parsing the artwork on main.
        const val ARTIST = "Lisa Wischofsky"
        const val ARTIST_URL = "https://www.instagram.com/lischi_art/"
    }
}