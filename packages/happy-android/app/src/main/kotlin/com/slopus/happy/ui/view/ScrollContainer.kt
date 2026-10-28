package com.slopus.happy.ui.view

import android.content.Context
import android.view.View
import android.widget.ScrollView
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size

/**
 * A vertical scroll view whose content is placed by frames computed
 * elsewhere (happy-ios sets a UIScrollView's `contentSize` and its
 * subviews' frames from one layout). Put views in [content] and hand it
 * their frames and the content size on every layout pass.
 */
class ScrollContainer(context: Context) : ScrollView(context) {
    val content = FrameContainer(context)

    init {
        isFillViewport = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        addView(content)
    }

    fun addContent(view: View) = content.addView(view)

    /** Sets the content's size and its children's frames, in the content's coordinates. */
    fun place(contentSize: Size, frames: Map<View, Rect?>) {
        content.frames.clear()
        content.frames.putAll(frames)
        content.contentSize = contentSize
        // Applied now, in the caller's layout pass, as UIScrollView takes a
        // new contentSize: asking for another pass from inside this one
        // would loop.
        content.setFrame(Rect(0.0, 0.0, contentSize.width, contentSize.height))
        // A layout() at the same size skips onLayout; the frames may still be new.
        content.layoutSubviews(contentSize.width, contentSize.height)
        val maxScroll = (dp(contentSize.height) - height).toInt().coerceAtLeast(0)
        if (scrollY > maxScroll) scrollTo(0, maxScroll)
    }
}

/** A view whose children sit at frames assigned from outside; its size is [contentSize]. */
class FrameContainer(context: Context) : ManualLayout(context) {
    var contentSize: Size = Size.Zero
    val frames = LinkedHashMap<View, Rect?>()

    init {
        clipChildren = false
    }

    override fun sizeThatFits(width: Double, height: Double) = contentSize

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(contentSize.width).toInt(), dp(contentSize.height).toInt())
    }

    override fun layoutSubviews(width: Double, height: Double) {
        for ((view, frame) in frames) view.setFrameOrHide(frame)
    }
}
