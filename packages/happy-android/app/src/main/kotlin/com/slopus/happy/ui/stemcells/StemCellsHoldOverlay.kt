package com.slopus.happy.ui.stemcells

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import com.slopus.happy.R
import com.slopus.happy.layout.*
import com.slopus.happy.layout.stemcells.StemCellsHoldLayout
import com.slopus.happy.layout.stemcells.StemCellsPreviewLayout
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.*

/** Telegram-style held row: read-only conversation, grouped actions, tap outside/Back to dismiss. */
class StemCellsHoldOverlay(
    context: Context,
    private val source: Rect,
    groups: List<List<Item>>,
    private val preview: Screen?,
    snapshot: Bitmap?,
) : ManualLayout(context) {
    data class Item(val title: String, val icon: String, val destructive: Boolean = false, val run: () -> Unit)
    var safeArea = LayoutInsets.Zero
    var onOpen: (() -> Unit)? = null
    var onGone: (() -> Unit)? = null
    private val dim = View(context)
    private val card = object : ManualLayout(context) {
        override fun layoutSubviews(width: Double, height: Double) {
            for (i in 0 until childCount) getChildAt(i).setFrame(Rect(0.0, 0.0, width, height))
        }
        override fun onInterceptTouchEvent(ev: MotionEvent) = true
    }
    private val menu = Menu(context, groups) { close(then = it.run) }
    private val springs = ArrayList<SpringAnimation>()
    private var closing = false
    private var finished = false
    private var after: (() -> Unit)? = null
    private var distance = 0f

    init {
        tag = "stem-hold"
        isClickable = true
        dim.setBackgroundColor(Theme.color(StemCellsPalette.stemCellsHoldDim))
        dim.contentDescription = context.getString(R.string.stem_close_menu)
        dim.setOnClickListener { close() }
        addView(dim)
        card.background = GradientDrawable().apply {
            setColor(Theme.color(Palette.surface))
            cornerRadius = dp(if (preview != null) StemCellsHoldLayout.Metrics.cardCorner else StemCellsMetrics.cornerRadius)
        }
        card.clipToOutline = true
        if (preview != null) {
            card.tag = "stem-preview-card"
            card.contentDescription = context.getString(R.string.stem_open_preview)
            card.setOnClickListener { close(then = onOpen) }
            card.addView(preview.view)
        } else if (snapshot != null) {
            card.addView(ImageView(context).apply { setImageBitmap(snapshot); scaleType = ImageView.ScaleType.FIT_XY })
        }
        addView(card)
        addView(menu)
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val size = Size(width, height)
        val previewSize = preview?.let { StemCellsPreviewLayout.size(size, safeArea.top, safeArea.bottom, width >= 768) }
        val layout = StemCellsHoldLayout(size, safeArea.top, safeArea.bottom, source, previewSize, menu.frames.size)
        dim.setFrame(Rect(0.0, 0.0, width, height))
        card.setFrame(layout.card)
        menu.setFrame(layout.menu)
        distance = if (preview != null) dp(layout.card.midY - source.midY) else 0f
    }

    fun present() {
        post {
            if (closing) return@post
            dim.alpha = 0f
            dim.animate().alpha(1f).setDuration(200).start()
            fun spring(view: View, property: DynamicAnimation.ViewProperty, start: Float, end: Float) {
                property.setValue(view, start)
                springs += SpringAnimation(view, property, end).apply {
                    // iOS/Telegram: mass 5, stiffness 900, damping 104.
                    spring.stiffness = 180f
                    spring.dampingRatio = 0.775f
                    start()
                }
            }
            if (preview != null) {
                card.alpha = 0f
                card.animate().alpha(1f).setDuration(150).start()
                spring(card, DynamicAnimation.TRANSLATION_Y, -distance, 0f)
                spring(card, DynamicAnimation.SCALE_X, 0.01f, 1f)
                spring(card, DynamicAnimation.SCALE_Y, 0.01f, 1f)
            }
            menu.alpha = 0f
            menu.animate().alpha(1f).setDuration(50).start()
            spring(menu, DynamicAnimation.SCALE_X, 0.01f, 1f)
            spring(menu, DynamicAnimation.SCALE_Y, 0.01f, 1f)
            spring(menu, DynamicAnimation.TRANSLATION_Y, -distance - menu.height / 2f, 0f)
            menu.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        }
    }

    fun close(animated: Boolean = true, then: (() -> Unit)? = null) {
        if (closing) { if (!animated) finish(); return }
        closing = true
        after = then
        springs.forEach { it.cancel() }
        if (!animated) { finish(); return }
        dim.animate().alpha(0f).setDuration(200).start()
        card.animate().alpha(0f).translationY(-distance).scaleX(if (preview != null) 0.01f else 1f).scaleY(if (preview != null) 0.01f else 1f).setDuration(200).start()
        menu.animate().alpha(0f).scaleX(0.01f).scaleY(0.01f).translationY(-distance - menu.height / 2f).setDuration(200).withEndAction { finish() }.start()
    }

    private fun finish() {
        if (finished) return
        finished = true
        for (view in listOf(dim, card, menu)) view.animate().cancel()
        preview?.destroy()
        (parent as? ViewGroup)?.removeView(this)
        onGone?.invoke()
        onGone = null
        after?.invoke()
        after = null
    }

    private class Menu(context: Context, groups: List<List<Item>>, picked: (Item) -> Unit) : ManualLayout(context) {
        val frames: StemCellsHoldLayout.Menu
        init {
            tag = "stem-hold-menu"
            val labels = groups.map { group -> group.map { item ->
                Label(context, TextStyle(FontFace.System, 17.0), if (item.destructive) StemCellsPalette.stemCellsHoldDestructive else StemCellsPalette.stemCellsHoldInk, maxLines = 1)
                    .apply { text = item.title; followsTextSize = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
            } }
            frames = StemCellsHoldLayout.Menu(labels.map { group -> group.map { it.measure(10000.0).width } }, labels.first().first().lineHeight)
            background = GradientDrawable().apply { setColor(Theme.color(StemCellsPalette.stemCellsHoldMenu)); cornerRadius = dp(frames.corner) }
            clipToOutline = true
            for ((groupIndex, group) in groups.withIndex()) for ((index, item) in group.withIndex()) {
                val frame = frames.items[groupIndex][index]
                val row = object : ManualLayout(context) {
                    override fun layoutSubviews(width: Double, height: Double) {}
                    override fun setPressed(pressed: Boolean) {
                        super.setPressed(pressed)
                        setBackgroundColor(if (pressed) Theme.color(StemCellsPalette.stemCellsHoldHighlight) else 0)
                    }
                }
                row.contentDescription = item.title
                row.tag = "stem-hold-action:${item.title}"
                row.isFocusable = true
                row.setOnClickListener { picked(item) }
                val label = labels[groupIndex][index]
                val icon = IconView(context, StemCellsGlyphs.icon(item.icon), 24.0, if (item.destructive) StemCellsPalette.stemCellsHoldDestructive else StemCellsPalette.stemCellsHoldInk)
                icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                row.addView(icon)
                row.addView(label)
                addView(row)
                row.setFrame(frame.frame)
                icon.setFrame(frame.icon.offsetBy(-frame.frame.minX, -frame.frame.minY))
                label.setFrame(frame.title.offsetBy(-frame.frame.minX, -frame.frame.minY))
            }
            for (frame in frames.separators) addView(View(context).apply { setBackgroundColor(Theme.color(StemCellsPalette.stemCellsHoldSeparator)); setFrame(frame) })
        }
        override fun layoutSubviews(width: Double, height: Double) {}
    }
}