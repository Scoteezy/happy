package com.slopus.happy.ui.components

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.PopupWindow
import android.widget.ScrollView
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.FontFace
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlin.math.max
import kotlin.math.min

/**
 * A pick-one menu hanging from a control: where happy-ios opens a UIMenu of
 * inline sections (a grey title, then checkable rows, the current one
 * ticked), Android opens a popup window drawn as the platform's menus are
 * (a raised surface, a ripple per row) holding the same sections. Unlike
 * [android.widget.PopupMenu] it wraps a long choice (a deep path) over two
 * lines instead of cutting it, and keeps the section titles iOS shows.
 */
object ChoiceMenu {
    /** [subtitle] is a grey line under the label, as a UIMenu action's. */
    data class Option(val key: String, val label: String, val isDisabled: Boolean = false, val isAction: Boolean = false, val subtitle: String? = null)

    /** [selectedKey], when set, is the section's own checked row, over the menu's. */
    data class Section(val title: String?, val options: List<Option>, val selectedKey: String? = null)

    private val TITLE = TextStyle(FontFace.System, 13)
    private val ROW = TextStyle(FontFace.System, 17)
    private val SUBTITLE = TextStyle(FontFace.System, 13)
    private const val SUBTITLE_GAP = 2.0
    private const val MIN_WIDTH = 180.0
    private const val MAX_WIDTH = 300.0
    private const val PADDING_H = 16.0
    private const val CHECK_SLOT = 28.0
    private const val ROW_MIN_HEIGHT = 48.0
    private const val ROW_PADDING_V = 12.0
    private const val TITLE_HEIGHT = 36.0
    private const val RADIUS = 14.0
    private const val MARGIN = 8.0

    /** Shows [sections] from [anchor]; [onSelect] gets the picked option's key. */
    fun show(anchor: View, sections: List<Section>, selectedKey: String?, onSelect: (String) -> Unit) {
        val context = anchor.context
        val content = Content(context, sections, selectedKey)
        val root = anchor.rootView
        val screenWidth = px(root.width)
        val width = content.preferredWidth(min(MAX_WIDTH, screenWidth - 2 * MARGIN))
        val height = content.heightFor(width)

        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val anchorTop = px(location[1])
        val anchorBottom = anchorTop + px(anchor.height)
        // The keyboard stays up under a menu, as iOS keeps it; the menu keeps clear of it.
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(anchor)
        val ime = insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val safeTop = px(insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())?.top ?: 0)
        val windowHeight = px(root.height - ime)
        val below = windowHeight - anchorBottom - MARGIN
        val above = anchorTop - MARGIN - safeTop
        // Below the control when it fits, else above it, else wherever there is more room.
        val placeBelow = height <= below || below >= above
        val shown = min(height, if (placeBelow) below else above)
        val y = if (placeBelow) anchorBottom else anchorTop - shown
        val x = min(max(MARGIN, px(location[0]) - PADDING_H), screenWidth - MARGIN - width)

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content)
        }
        val surface = GradientDrawable().apply {
            setColor(Theme.color(Palette.surface))
            cornerRadius = dp(RADIUS)
        }
        scroll.background = surface
        scroll.clipToOutline = true
        scroll.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(RADIUS))
        }
        val popup = PopupWindow(scroll, dp(width.toInt()), dp(shown.toInt()), true)
        popup.elevation = dp(8.0)
        popup.setBackgroundDrawable(ColorDrawable(0))
        popup.isOutsideTouchable = true
        // Not the IME's target: the field under the menu keeps the keyboard.
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        popup.animationStyle = android.R.style.Animation_Dialog
        content.width = width
        content.onPick = { key ->
            popup.dismiss()
            onSelect(key)
        }
        anchor.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, dp(x.toInt()), dp(y.toInt()))
        // Opened on the current pick, as iOS scrolls its menu to it.
        content.selectedTop?.let { top -> scroll.post { scroll.scrollTo(0, dp(max(0.0, top - shown / 2).toInt())) } }
    }

    private class Content(context: Context, private val sections: List<Section>, private val selectedKey: String?) : ManualLayout(context) {
        var onPick: ((String) -> Unit)? = null
        var width = 0.0
        var selectedTop: Double? = null
        private val pieces = ArrayList<Pair<View, Any?>>()

        init {
            sections.forEachIndexed { index, section ->
                if (index > 0) pieces += View(context).apply { setBackgroundColor(Theme.color(Palette.divider)) } to Divider
                section.title?.let { title ->
                    pieces += Label(context, TITLE, Palette.textSecondary, maxLines = 1).apply { text = title } to null
                }
                for (option in section.options) pieces += Row(context, option, !option.isAction && option.key == (section.selectedKey ?: selectedKey)).also { row ->
                    row.setOnClickListener { if (!option.isDisabled) onPick?.invoke(option.key) }
                } to option
            }
            pieces.forEach { addView(it.first) }
        }

        fun preferredWidth(maxWidth: Double): Double {
            var widest = 0.0
            for ((view, _) in pieces) {
                widest = max(
                    widest,
                    when (view) {
                        is Row -> view.naturalWidth() + 2 * PADDING_H + CHECK_SLOT
                        is Label -> view.measure(Double.MAX_VALUE).width + 2 * PADDING_H + CHECK_SLOT
                        else -> 0.0
                    },
                )
            }
            return min(maxWidth, max(MIN_WIDTH, widest))
        }

        fun heightFor(width: Double): Double = frames(width).lastOrNull()?.maxY ?: 0.0

        private fun frames(width: Double): List<Rect> {
            var y = 4.0
            selectedTop = null
            return pieces.map { (view, tag) ->
                val frame = when {
                    tag === Divider -> Rect(0.0, y + 4, width, 1.0 / Theme.density).also { y += 9 }
                    view is Row -> {
                        val text = view.textHeight(width - 2 * PADDING_H - CHECK_SLOT)
                        Rect(0.0, y, width, max(ROW_MIN_HEIGHT, text + 2 * ROW_PADDING_V)).also {
                            if (view.checked) selectedTop = y
                            y = it.maxY
                        }
                    }
                    else -> Rect(0.0, y, width, TITLE_HEIGHT).also { y = it.maxY }
                }
                frame
            }.also { y += 4 }
        }

        override fun sizeThatFits(width: Double, height: Double) = com.slopus.happy.layout.Size(this.width, heightFor(this.width) + 4)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(dp(width.toInt()), dp((heightFor(width) + 4).toInt()))
        }

        override fun layoutSubviews(width: Double, height: Double) {
            frames(width).forEachIndexed { index, frame ->
                val view = pieces[index].first
                if (view is Label) {
                    // The title sits at the foot of its strip, over its options.
                    view.setFrame(Rect(PADDING_H + CHECK_SLOT, frame.maxY - 26, frame.width - 2 * PADDING_H - CHECK_SLOT, 20.0))
                } else {
                    view.setFrame(frame)
                }
            }
        }

        private object Divider
    }

    /** One choice: the tick in its slot, then the label, wrapped over two lines at most. */
    private class Row(context: Context, option: Option, val checked: Boolean) : ManualLayout(context) {
        val label = Label(context, ROW, if (option.isDisabled) Palette.textSecondary else Palette.text, maxLines = 2).apply { text = option.label }
        private val subtitle = option.subtitle?.let { line -> Label(context, SUBTITLE, Palette.textSecondary, maxLines = 2).apply { text = line } }
        private val check = IconView(context, Icon.ionicons("checkmark"), 20.0, Palette.text)

        /** The wider of the label and its subtitle on one line. */
        fun naturalWidth(): Double = max(label.measure(Double.MAX_VALUE).width, subtitle?.measure(Double.MAX_VALUE)?.width ?: 0.0)

        /** The label and its subtitle, offered [width]. */
        fun textHeight(width: Double): Double =
            label.measure(width).height + (subtitle?.let { SUBTITLE_GAP + it.measure(width).height } ?: 0.0)

        init {
            addView(label)
            subtitle?.let(::addView)
            check.visibility = if (checked) VISIBLE else GONE
            addView(check)
            isClickable = !option.isDisabled
            isFocusable = !option.isDisabled
            background = RippleDrawable(ColorStateList.valueOf(Theme.color(Palette.divider)), null, ColorDrawable(0xFFFFFFFF.toInt()))
            contentDescription = listOfNotNull(option.label, option.subtitle).joinToString(", ")
            isSelected = checked
        }

        override fun layoutSubviews(width: Double, height: Double) {
            check.setFrame(Rect(PADDING_H, (height - 20) / 2, 20.0, 20.0))
            val textWidth = width - 2 * PADDING_H - CHECK_SLOT
            val size = label.measure(textWidth)
            val top = (height - textHeight(textWidth)) / 2
            label.setFrame(Rect(PADDING_H + CHECK_SLOT, top, textWidth, size.height))
            subtitle?.let { it.setFrame(Rect(PADDING_H + CHECK_SLOT, top + size.height + SUBTITLE_GAP, textWidth, it.measure(textWidth).height)) }
        }
    }
}
