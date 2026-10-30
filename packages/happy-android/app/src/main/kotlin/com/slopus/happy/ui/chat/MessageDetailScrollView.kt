package com.slopus.happy.ui.chat

import android.content.Context
import android.widget.ScrollView
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.chat.MessageDetailReadingPlace
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.setFrame
import kotlin.math.roundToInt

/** MessageDetailViewController.show: keep the displayed text piece when its size changes. */
internal class MessageDetailScrollView(context: Context) : ScrollView(context) {
    val canvas = ChatRowView(context)
    private var shownText: TextLayouts.Env? = null
    /** The scale of the installed frames, which can change before a new text plan arrives. */
    private var canvasDensity = Theme.density

    init {
        isFillViewport = false
        clipToPadding = false
        addView(canvas)
    }

    fun show(plan: ChatRowPlan, text: TextLayouts.Env) {
        // Capture at application time, not when asynchronous measurement started:
        // the reader may have scrolled while the plan was being prepared.
        val place = if (text != shownText) {
            MessageDetailReadingPlace.at(scrollY / canvasDensity.toDouble(), canvas.plan.textFrames())
        } else null
        shownText = text
        canvas.bind(plan)
        // Install the new extent before scrollTo, which otherwise clamps against
        // the old child's height. Applying synchronously also makes successive
        // plans coherent without a stale, unowned post-layout restoration callback.
        canvas.setFrame(Rect(0.0, 0.0, plan.size.width, plan.size.height))
        canvasDensity = text.density
        val top = place?.top(plan.textFrames())?.let { (it * text.density).roundToInt() }
        // ScrollView supplies the viewport/padding clamp, including the safe bottom.
        // Width-only changes retain the pixel offset, as the native screen does.
        scrollTo(scrollX, top ?: scrollY)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        canvasDensity = Theme.density
    }

    private fun ChatRowPlan?.textFrames(): List<Rect> =
        this?.items?.filterIsInstance<ChatDrawItem.Text>()?.map { it.frame }.orEmpty()
}