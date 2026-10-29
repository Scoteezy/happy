package com.slopus.happy.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.slopus.happy.R
import com.slopus.happy.chat.ChatOlderPaging
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.RoundButtonDisplay
import com.slopus.happy.layout.RoundButtonSize
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.chat.ChatOlderEndLayout
import com.slopus.happy.layout.chat.ChatScrollAnchor
import com.slopus.happy.ui.components.RoundButton
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The chat's message list (happy-ios `ChatListView`; ChatList.tsx's inverted
 * FlatList; Telegram's history list): a RecyclerView laid out from the bottom
 * up (`reverseLayout`), newest row first, whose rows were all measured off
 * the main thread before they were handed over ([ChatRowPlan]), so every
 * height is exact. ChatFooter sits just past the newest row and the older
 * end's 32dp slot past the oldest.
 *
 * Being inverted, the newest edge is the scroll origin: a reader at the
 * newest message is carried along as rows arrive and grow, and a reader
 * further back keeps the row they look at where it is (the layout manager
 * anchors on it). The insets keep the newest row clear of the dock and the
 * oldest clear of the header; content runs under both.
 */
@SuppressLint("ViewConstructor")
class ChatListView(context: Context) : RecyclerView(context), ThemeAware {
    /** `distanceFromNewest`, how far the reader is back from the newest message; `distanceFromOldest`, how much is left before the oldest row. */
    var onScroll: ((distanceFromNewest: Double, distanceFromOldest: Double) -> Unit)? = null
    /** A tap or long press on a row's target; [frame] is the target's, in this view. */
    var onAction: ((ChatRowAction, Rect, Boolean) -> Unit)? = null

    private val adapter = Adapter()
    private val manager = LinearLayoutManager(context, VERTICAL, true)
    private var heights = DoubleArray(0)
    private var newestInset = 0.0
    private var oldestInset = 0.0

    /** Retry or Load more at the older end was pressed; which one is [olderStatus]. */
    var onOlderAction: (() -> Unit)? = null

    /** What the older end shows (`ChatOlderPaging.status`). */
    var olderStatus = ChatOlderPaging.Status.Idle
        set(value) {
            if (field == value) return
            field = value
            adapter.olderEnd?.status = value
        }

    init {
        layoutManager = manager
        setAdapter(adapter)
        clipToPadding = false
        // No scroll indicator, as Telegram's chat list draws none.
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        itemAnimator = DefaultItemAnimator().apply {
            supportsChangeAnimations = false
            // ChatFollowMotion's 0.4s glide for rows that arrive at the newest edge.
            moveDuration = 250
            addDuration = 200
        }
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = reportScroll()
        })
    }

    override fun applyTheme() {
        for (i in 0 until childCount) (getChildAt(i) as? ThemeAware)?.applyTheme()
        recycledViewPool.clear()
    }

    /**
     * Shows [plans] (newest first) under [footer]. Only the rows whose plan
     * changed are bound again. [pinning] is a row the reader just opened or
     * closed: its top stays where it is on screen, as iOS pins it
     * (`ChatScrollAnchor.pinning`).
     */
    fun submit(footer: ChatRowPlan, plans: List<ChatRowPlan>, pinning: String? = null, done: (() -> Unit)? = null) {
        pinned = pinning?.let { id -> rowView(id)?.let { id to it.top } }
        val items = ArrayList<Item>(plans.size + 2)
        items += Item.Row(footer)
        plans.mapTo(items) { Item.Row(it) }
        items += Item.OlderEnd
        // ChatScrollAnchor.capture: within 200 of the newest edge, and not under the finger, the reader follows it.
        val distance = distanceFromNewest()
        val atNewest = pinning == null && distance > 0.5 && distance <= ChatScrollAnchor.followThreshold && scrollState != SCROLL_STATE_DRAGGING
        adapter.submitList(items) {
            heights = DoubleArray(items.size) { index ->
                when (val item = items[index]) {
                    is Item.Row -> item.plan.size.height
                    Item.OlderEnd -> ChatOlderEndLayout.height
                }
            }
            // The layout manager keeps a reader at the newest edge there; one just short of it is carried to it.
            if (atNewest) smoothScrollToPosition(0)
            reportScroll()
            done?.invoke()
        }
    }

    /**
     * Insets at both ends: the newest edge clears the dock, the oldest the
     * header. What the reader sees at the newest edge stays put, so a rising
     * keyboard carries the conversation up with the composer; under the
     * reader's finger the conversation stays with the finger
     * (`ChatScrollAnchor.visibleMinYAfterNewestInsetChange`).
     */
    fun setEdgeInsets(newest: Double, oldest: Double) {
        if (newest == newestInset && oldest == oldestInset) return
        newestInset = newest
        oldestInset = oldest
        val bottom = dp(newest).roundToInt()
        // The layout manager keeps each row's distance from the newest padded edge (its anchor takes in the
        // space change), which is iOS's rule away from the finger. Under the finger the rows stay where they are.
        if (childCount > 0 && scrollState == SCROLL_STATE_DRAGGING) pendingShift += bottom - paddingBottom
        setPadding(0, dp(oldest).roundToInt(), 0, bottom)
    }

    /** Pixels to scroll once the new insets are laid out, keeping the distance from the newest edge. */
    private var pendingShift = 0

    /** The row being opened or closed, and its top before. */
    private var pinned: Pair<String, Int>? = null

    private fun rowView(id: String): ChatRowView? {
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? ChatRowView ?: continue
            if (child.plan?.id == id) return child
        }
        return null
    }

    fun scrollToNewest(animated: Boolean) {
        if (adapter.itemCount == 0) return
        if (animated) smoothScrollToPosition(0) else scrollToPosition(0)
    }

    /**
     * How far the visible area's newest edge is from the newest content, in
     * dp: 0 at the newest message. Worked out from the rows' known heights,
     * not the layout manager's estimates.
     */
    fun distanceFromNewest(): Double {
        val child = lowestChild() ?: return 0.0
        val position = getChildAdapterPosition(child)
        if (position == NO_POSITION) return 0.0
        var below = 0.0
        for (i in 0 until minOf(position, heights.size)) below += heights[i]
        val edge = height - paddingBottom
        return max(0.0, below + px(child.bottom - edge))
    }

    /** How much content is left past the visible area's oldest edge, in dp. */
    fun distanceFromOldest(): Double {
        val child = highestChild() ?: return 0.0
        val position = getChildAdapterPosition(child)
        if (position == NO_POSITION) return 0.0
        var above = 0.0
        for (i in position + 1 until heights.size) above += heights[i]
        return max(0.0, above + px(paddingTop - child.top))
    }

    private fun lowestChild(): View? {
        var best: View? = null
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (best == null || child.bottom > best.bottom) best = child
        }
        return best
    }

    private fun highestChild(): View? {
        var best: View? = null
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (best == null || child.top < best.top) best = child
        }
        return best
    }

    private fun reportScroll() {
        onScroll?.invoke(distanceFromNewest(), distanceFromOldest())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (pendingShift != 0) {
            val shift = pendingShift
            pendingShift = 0
            scrollBy(0, shift)
        }
        pinned?.let { (id, top) ->
            val view = rowView(id) ?: return@let
            if (view.plan?.size?.height?.let { dp(it).roundToInt() } != view.height) return@let
            pinned = null
            if (view.top != top) scrollBy(0, view.top - top)
        }
        reportScroll()
    }

    private sealed interface Item {
        class Row(val plan: ChatRowPlan) : Item
        data object OlderEnd : Item
    }

    private inner class Adapter : ListAdapter<Item, ViewHolder>(Diff) {
        var olderEnd: OlderEndView? = null

        override fun getItemViewType(position: Int) = if (getItem(position) is Item.OlderEnd) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view: View = if (viewType == 1) {
                OlderEndView(parent.context).also {
                    olderEnd = it
                    it.status = olderStatus
                    it.onAction = { onOlderAction?.invoke() }
                }
            } else {
                ChatRowView(parent.context)
            }
            view.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            return object : ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            if (item !is Item.Row) return
            val row = holder.itemView as ChatRowView
            row.bind(item.plan)
            row.onAction = { action, frame, hold ->
                onAction?.invoke(action, frame.offsetBy(px(row.left), px(row.top)), hold)
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean = when {
            oldItem is Item.Row && newItem is Item.Row -> oldItem.plan.id == newItem.plan.id
            else -> oldItem === newItem
        }

        override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean = when {
            oldItem is Item.Row && newItem is Item.Row -> oldItem.plan === newItem.plan
            else -> true
        }
    }

    /**
     * ChatList's OlderEnd (happy-ios `ChatOlderEndView`): past the oldest
     * message, a spinner while the screen waits on an older page it asked
     * for, Retry after one failed, Load more after five that showed nothing
     * new. Always the 32dp slot, so a change of status never moves the
     * conversation.
     */
    private class OlderEndView(context: Context) : ManualLayout(context) {
        private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
        private val button = RoundButton(context, context.getString(R.string.common_retry), RoundButtonSize.Small, RoundButtonDisplay.Inverted)

        var onAction: (() -> Unit)? = null

        var status = ChatOlderPaging.Status.Idle
            set(value) {
                field = value
                spinner.visibility = if (value == ChatOlderPaging.Status.Loading) VISIBLE else INVISIBLE
                when (value) {
                    ChatOlderPaging.Status.Error -> button.title = context.getString(R.string.common_retry)
                    ChatOlderPaging.Status.LoadMore -> button.title = context.getString(R.string.common_load_more)
                    else -> Unit
                }
                button.visibility = if (value == ChatOlderPaging.Status.Error || value == ChatOlderPaging.Status.LoadMore) VISIBLE else GONE
                requestLayout()
            }

        init {
            spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(Palette.textSecondary))
            spinner.visibility = INVISIBLE
            addView(spinner)
            button.onTap = { onAction?.invoke() }
            button.visibility = GONE
            addView(button)
        }

        override fun sizeThatFits(width: Double, height: Double) = com.slopus.happy.layout.Size(width, ChatOlderEndLayout.height)

        override fun layoutSubviews(width: Double, height: Double) {
            spinner.setFrame(ChatOlderEndLayout(width, com.slopus.happy.layout.Size(20.0, 20.0)).control)
            button.setFrame(ChatOlderEndLayout(width, button.sizeThatFits(width, Double.MAX_VALUE)).control)
        }
    }
}
