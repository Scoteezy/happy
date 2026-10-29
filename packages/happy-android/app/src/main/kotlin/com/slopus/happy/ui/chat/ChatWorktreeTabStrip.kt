package com.slopus.happy.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.HeaderGlassShadow
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.chat.WorktreeTabStripLayout
import com.slopus.happy.layout.headerControlFill
import com.slopus.happy.layout.headerGlassBorder
import com.slopus.happy.layout.worktreeTabSelected
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.home.ShimmerLabel
import com.slopus.happy.ui.home.StatusDotView
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.FrameContainer
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlin.math.roundToInt

/**
 * WorktreeTabStrip.tsx (happy-ios `ChatWorktreeTabStrip`): grouped by
 * project, a chat is one tab of its checkout, and this is where the
 * checkout's other chats are reached. It hangs under the header in a
 * capsule of the header controls' own fill (a solid surface on a tablet),
 * scrolls sideways, and ends in a hairline and `+`.
 *
 * The screen owns what the tabs mean: a tap asks it to switch in place, a
 * long press to show the chat's actions. [WorktreeTabStripLayout] places
 * everything. The names are a handful of one-line labels, measured here as
 * the strip lays out.
 */
@SuppressLint("ViewConstructor")
class ChatWorktreeTabStrip(context: Context) : ManualLayout(context), ThemeAware {
    data class Item(
        val id: String,
        val title: String,
        /** The name shimmers: the agent works, or the chat is still arriving. */
        val shimmers: Boolean,
        val blocked: Boolean,
        val unread: Boolean,
        /** A stand-in for a chat that hasn't started yet: no actions. */
        val pending: Boolean,
    )

    var onSelect: ((String) -> Unit)? = null
    var onLongPress: ((String) -> Unit)? = null
    var onAdd: (() -> Unit)? = null

    /** `layout.headerMaxWidth`. */
    var maxWidth = Double.MAX_VALUE
        set(value) { if (field != value) { field = value; requestLayout() } }

    /** TabletBranches' `worktreeStripGlass`: the header's capsule on a phone only. */
    var glass = true
        set(value) { if (field != value) { field = value; applyTheme() } }

    var showsAdd = false
        set(value) {
            if (field == value) return
            field = value
            divider.visibility = if (value) VISIBLE else GONE
            addButton.visibility = if (value) VISIBLE else GONE
            requestLayout()
        }

    /** Where the tabs are scrolled to, in dp, for the screen that takes over on a switch to pick up from. */
    val offset: Double get() = px(scroller.scrollX)

    var items: List<Item> = emptyList()
        private set
    private var selectedId: String? = null
    private val pill = View(context)
    private val pillShape = GradientDrawable()
    private val clip = Clip(context)
    private val scroller = Scroller(context)
    private val divider = View(context)
    private val addButton = AddControl(context)
    private val tabs = LinkedHashMap<String, TabControl>()
    /** Placed on the first layout, unanimated: where the last screen on this checkout left the strip. */
    private var restoredOffset: Double? = null
    /** The open tab, to bring into view once it has been placed. */
    private var revealId: String? = null
    private var placedOnce = false

    init {
        clipChildren = false
        pill.background = pillShape
        pill.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                outline.alpha = 1f
            }
        }
        pill.elevation = dp(HeaderGlassShadow.offsetY)
        addView(pill)
        clip.clipToOutline = true
        clip.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
        }
        clip.elevation = pill.elevation
        addView(clip)
        clip.addView(scroller)
        divider.visibility = GONE
        clip.addView(divider)
        addButton.visibility = GONE
        addButton.setOnClickListener { onAdd?.invoke() }
        clip.addView(addButton)
        visibility = GONE
        applyTheme()
    }

    fun restore(offset: Double) {
        restoredOffset = offset
        requestLayout()
    }

    fun update(items: List<Item>, selectedId: String) {
        val selectionMoved = selectedId != this.selectedId
        if (items == this.items && !selectionMoved) return
        this.items = items
        this.selectedId = selectedId
        val kept = LinkedHashMap<String, TabControl>()
        for (item in items) {
            val tab = tabs[item.id] ?: TabControl(context).also { makeTab(it) }
            tab.configure(item, selected = item.id == selectedId)
            kept[item.id] = tab
        }
        for ((id, tab) in tabs) if (kept[id] == null) scroller.content.removeView(tab)
        tabs.clear()
        tabs.putAll(kept)
        // Keep the open tab in view, including one that didn't exist a moment ago.
        if (selectionMoved || revealId != null) revealId = selectedId
        requestLayout()
    }

    private fun makeTab(tab: TabControl) {
        tab.setOnClickListener { tab.item?.let { onSelect?.invoke(it.id) } }
        tab.setOnLongClickListener {
            val item = tab.item ?: return@setOnLongClickListener false
            if (item.pending) return@setOnLongClickListener false
            onLongPress?.invoke(item.id)
            true
        }
        scroller.content.addView(tab)
    }

    override fun applyTheme() {
        if (glass) {
            pillShape.setColor(Theme.color(Palette.headerControlFill))
            pillShape.setStroke(dp(WorktreeTabStripLayout.Metrics.glassBorder).roundToInt(), Theme.color(Palette.headerGlassBorder))
        } else {
            pillShape.setColor(Theme.color(Palette.surfaceHigh))
            pillShape.setStroke(1, Theme.color(Palette.divider))
        }
        pill.outlineAmbientShadowColor = 0xFF000000.toInt()
        pill.outlineSpotShadowColor = 0xFF000000.toInt()
        divider.setBackgroundColor(Theme.color(Palette.divider))
        addButton.applyTheme()
        for (tab in tabs.values) tab.applyTheme()
    }

    override fun sizeThatFits(width: Double, height: Double) = Size(width, WorktreeTabStripLayout.Metrics.height)

    override fun layoutSubviews(width: Double, height: Double) {
        val hairline = px(1)
        val labels = items.map { item -> tabs.getValue(item.id).label.measurable }
        val layout = WorktreeTabStripLayout(
            width = width,
            maxWidth = maxWidth,
            border = if (glass) WorktreeTabStripLayout.Metrics.glassBorder else hairline,
            hairline = hairline,
            tabs = items.zip(labels).map { (item, label) -> WorktreeTabStripLayout.Tab(label, TabControl.hasDot(item, item.id == selectedId)) },
            showsAdd = showsAdd,
        )
        pill.setFrame(layout.pill)
        pillShape.cornerRadius = dp(layout.pill.height / 2)
        clip.setFrame(layout.inner.offsetBy(layout.pill.minX, layout.pill.minY))
        scroller.setFrame(layout.scroller)
        scroller.content.contentSize = Size(layout.contentWidth, layout.scroller.height)
        scroller.content.setFrame(Rect(0.0, 0.0, layout.contentWidth, layout.scroller.height))
        for ((item, frame) in items.zip(layout.tabs)) tabs[item.id]?.place(frame)
        layout.divider?.let(divider::setFrame)
        layout.add?.let(addButton::setFrame)

        if (items.isEmpty() || layout.scroller.width <= 0) return
        val maxOffset = maxOf(0.0, layout.contentWidth - layout.scroller.width)
        restoredOffset?.let { restored ->
            restoredOffset = null
            scroller.scrollTo(dp(restored.coerceIn(0.0, maxOffset)).roundToInt(), 0)
        }
        val reveal = revealId
        val index = items.indexOfFirst { it.id == reveal }
        if (reveal != null && index >= 0) {
            revealId = null
            WorktreeTabStripLayout.revealOffset(layout.tabs[index].frame, offset, layout.scroller.width, layout.contentWidth)?.let { target ->
                val x = dp(target).roundToInt()
                // The first placement is where the strip already was, not a move.
                if (placedOnce) scroller.post { scroller.smoothScrollTo(x, 0) } else scroller.post { scroller.scrollTo(x, 0) }
            }
        }
        placedOnce = true
    }

    /** Only the capsule takes touches (`pointerEvents="box-none"`). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val slop = dp(WorktreeTabStripLayout.Metrics.addHitSlop)
            val inside = ev.x >= clip.left - slop && ev.x < clip.right + slop && ev.y >= clip.top - slop && ev.y < clip.bottom + slop
            if (!inside) return false
        }
        return super.dispatchTouchEvent(ev)
    }

    /** The capsule's inside, which the scroller, the hairline and `+` are clipped to; its children are placed from outside. */
    private class Clip(context: Context) : ManualLayout(context) {
        override fun layoutSubviews(width: Double, height: Double) = Unit
    }

    /** The tabs, scrolled sideways without an indicator. */
    private class Scroller(context: Context) : HorizontalScrollView(context) {
        val content = FrameContainer(context)

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content)
        }
    }

    /**
     * One chat: its name and the blocked or unread dot. The open one is a
     * filled chip rather than an underline, which would run into the
     * capsule's rounded ends.
     */
    private class TabControl(context: Context) : ManualLayout(context), ThemeAware {
        var item: Item? = null
            private set
        private var isOpen = false
        var label = ShimmerLabel(context, ChatTextStyles.worktreeTab)
            private set
        private val dot = StatusDotView(context)
        private val chip = GradientDrawable()

        init {
            chip.cornerRadius = dp(WorktreeTabStripLayout.Metrics.tabHeight / 2)
            background = chip
            label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(label)
            addView(dot)
            isClickable = true
            isFocusable = true
        }

        fun configure(item: Item, selected: Boolean) {
            if (selected != isOpen || this.item == null) {
                // The open tab's name is semibold.
                removeView(label)
                label = ShimmerLabel(context, if (selected) ChatTextStyles.worktreeTabSelected else ChatTextStyles.worktreeTab)
                label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(label, 0)
            }
            this.item = item
            isOpen = selected
            label.text = item.title
            label.color = if (selected) Palette.headerTint else Palette.textSecondary
            label.isShimmering = item.shimmers
            dot.visibility = if (hasDot(item, selected)) VISIBLE else GONE
            dot.color = Theme.color(if (item.blocked) Palette.sessionBlockedDot else Palette.sessionReadyDot)
            dot.isPulsing = item.blocked
            contentDescription = item.title
            isSelected = selected
            applyTheme()
        }

        fun place(frame: WorktreeTabStripLayout.TabFrame) {
            setFrame(frame.frame)
            label.setFrame(frame.label)
            frame.dot?.let(dot::setFrame)
        }

        override fun applyTheme() {
            chip.setColor(if (isOpen) Theme.color(Palette.worktreeTabSelected) else 0)
            item?.let { dot.color = Theme.color(if (it.blocked) Palette.sessionBlockedDot else Palette.sessionReadyDot) }
            label.applyTheme()
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            alpha = if (pressed && !isOpen) WorktreeTabStripLayout.Metrics.pressedOpacity.toFloat() else 1f
        }

        override fun layoutSubviews(width: Double, height: Double) = Unit

        companion object {
            fun hasDot(item: Item, selected: Boolean) = item.blocked || item.unread && !selected
        }
    }

    /** `+`: always the plus; how far along a start is belongs to the tab it opened. */
    private class AddControl(context: Context) : ManualLayout(context) {
        private val icon = IconView(context, Icon.ionicons("add"), WorktreeTabStripLayout.Metrics.addIconSize, Palette.headerTint)

        init {
            addView(icon)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.sidebar_new_session)
        }

        fun applyTheme() = icon.applyTheme()

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            alpha = if (pressed) WorktreeTabStripLayout.Metrics.pressedOpacity.toFloat() else 1f
        }

        override fun layoutSubviews(width: Double, height: Double) = icon.setFrame(Rect(0.0, 0.0, width, height))
    }
}
