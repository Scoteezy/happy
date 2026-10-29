package com.slopus.happy.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ScrollView
import android.widget.TextView
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.chat.TextSelectionLayout
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.nav.NavigationBar
import com.slopus.happy.ui.theme.Fonts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame
import kotlin.math.roundToInt

/**
 * text-selection.tsx (happy-ios `TextSelectionViewController`): a text to
 * select any part of, read-only inside a card with a hairline rim, with
 * Copy (all of it) in the bar. Markdown Copy v2 opens it from a long press
 * on an agent's message. With no text, an error alert leads back.
 */
class TextSelectionScreen(context: Context, private val text: String) : BarScreen(context) {
    private var warned = false

    override fun createContent(): View {
        bar.title = context.getString(R.string.text_selection_title)
        bar.actions = if (text.isEmpty()) {
            emptyList()
        } else {
            listOf(NavigationBar.Action(Icon.ionicons("copy-outline"), context.getString(R.string.common_copy)) { copyAll() })
        }
        return Content(context)
    }

    override fun onAppear() {
        super.onAppear()
        if (text.isNotEmpty() || warned) return
        warned = true
        Alerts.show(context, context.getString(R.string.common_error), context.getString(R.string.text_selection_no_text_provided)) { pop() }
    }

    private fun copyAll() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
        Alerts.show(context, context.getString(R.string.text_selection_text_copied))
    }

    private inner class Content(context: Context) : ManualLayout(context), ThemeAware {
        // MobileGlassSurface without a native effect: no fill of its own, a hairline rim.
        private val rim = GradientDrawable().apply { cornerRadius = dp(TextSelectionLayout.Metrics.radius) }
        private val card = ScrollView(context)
        private val textView = TextView(context)

        init {
            card.background = rim
            card.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(TextSelectionLayout.Metrics.radius))
                }
            }
            card.clipToOutline = true
            card.isVerticalScrollBarEnabled = true
            card.clipToPadding = false
            textView.setTextIsSelectable(true)
            textView.includeFontPadding = false
            textView.text = text
            card.addView(textView)
            addView(card)
            applyTheme()
        }

        override fun applyTheme() {
            setBackgroundColor(Theme.color(Palette.groupedBackground))
            rim.setStroke(1, Theme.color(Palette.glassBorder))
            val style = ChatTextStyles.textSelection.scaled(Theme.textScale)
            textView.typeface = Fonts.typeface(style.face)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, (style.size * Theme.density).toFloat())
            style.lineHeight?.let { if (android.os.Build.VERSION.SDK_INT >= 28) textView.lineHeight = (it * Theme.density).roundToInt() }
            textView.setTextColor(Theme.color(Palette.text))
            textView.highlightColor = Theme.color(Palette.iconBlue) and 0x40FFFFFF
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val layout = TextSelectionLayout(Size(width, height), contentSafeArea)
            card.setFrame(layout.card)
            val insets = layout.textInsets
            textView.setPadding(dp(insets.left.toInt()), dp(insets.top.toInt()), dp(insets.right.toInt()), dp(insets.bottom.toInt()))
        }
    }
}
