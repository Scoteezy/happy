package com.slopus.happy.ui.stemcells

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ProgressBar
import com.slopus.happy.R
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellsPalette as P
import com.slopus.happy.layout.TextScale
import com.slopus.happy.layout.stemcells.StemCellSidebarProposalLayout
import com.slopus.happy.layout.stemcells.StemCellSidebarProposalLayout.Metrics as M
import com.slopus.happy.stemcells.StemCellProposalShown
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame

/** The proposal uses the actual sidebar rows, full-height inside the chat. */
class StemCellSidebarProposalView(context: Context, images: StemCellsImages?) : ManualLayout(context), ThemeAware {
    var onAccept: (() -> Unit)? = null
    var onDecline: (() -> Unit)? = null
    var onTurn: ((String) -> Unit)? = null
    private val title = Label(context, headerStyle, P.stemCellsCardTitle, 1)
    private val version = Label(context, versionStyle, P.stemCellsCardDetail, 1)
    val sidebar = StemCellsSidebarView(context)
    private val keep = ProposalButton(context, false)
    private val use = ProposalButton(context, true)
    private val notice = Label(context, noteStyle, P.stemCellsNotice, 1)
    private val status = Label(context, noteStyle, P.stemCellsCardDetail, 1, Layout.Alignment.ALIGN_OPPOSITE)
    private val fill = GradientDrawable()
    private val ground = GradientDrawable()
    private var frames: StemCellSidebarProposalLayout? = null

    init {
        tag = "stem-proposal"
        background = fill
        title.text = context.getString(R.string.stem_proposed_sidebar)
        if (android.os.Build.VERSION.SDK_INT >= 28) title.isAccessibilityHeading = true
        version.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        for (label in listOf(title, version, notice, status)) label.followsTextSize = false
        sidebar.images = images
        sidebar.list.isScrollEnabled = false
        sidebar.background = ground
        sidebar.clipToOutline = true
        sidebar.onFold = { onTurn?.invoke(it.id) }
        keep.setTitle(context.getString(R.string.stem_proposal_keep))
        keep.tag = "stem-proposal-keep"
        keep.setOnClickListener { onDecline?.invoke() }
        use.setTitle(context.getString(R.string.stem_proposal_use))
        use.tag = "stem-proposal-use"
        use.setOnClickListener { onAccept?.invoke() }
        status.tag = "stem-proposal-status"
        for (view in listOf(title, version, sidebar, keep, use, notice, status)) addView(view)
        applyTheme()
    }

    fun apply(shown: StemCellProposalShown, layout: StemCellSidebarProposalLayout) {
        frames = layout
        version.text = context.getString(R.string.stem_proposal_version, shown.revision)
        title.contentDescription = context.getString(R.string.stem_proposal_label, shown.revision)
        sidebar.apply(shown.rows)
        sidebar.alpha = if (shown.status == StemCellProposalShown.Status.Declined) M.declinedAlpha.toFloat() else 1f
        val open = shown.status == StemCellProposalShown.Status.Open
        keep.visibility = if (open) VISIBLE else GONE
        use.visibility = if (open) VISIBLE else GONE
        keep.show(shown.answering == null, shown.answering == StemCellProposalShown.Answer.Decline)
        use.show(shown.answering == null, shown.answering == StemCellProposalShown.Answer.Accept)
        notice.text = shown.notice.orEmpty()
        notice.visibility = if (open && shown.notice != null) VISIBLE else GONE
        status.text = when (shown.status) {
            StemCellProposalShown.Status.Accepted -> context.getString(R.string.stem_proposal_accepted)
            StemCellProposalShown.Status.Declined -> context.getString(R.string.stem_proposal_declined)
            else -> ""
        }
        status.visibility = if (open) GONE else VISIBLE
        requestLayout()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        val layout = frames ?: return
        title.setFrame(layout.title)
        version.setFrame(layout.version)
        sidebar.setFrame(layout.frame)
        // Native's 28-point pills have +4/+8 hit padding; the Views own those
        // larger bounds and draw the original pill inside them.
        layout.keep?.let { keep.setFrame(it.expandedButton()) }
        layout.use?.let { use.setFrame(it.expandedButton()) }
        notice.setFrame(layout.notice ?: Rect.Zero)
        status.setFrame(layout.status ?: Rect.Zero)
    }

    override fun applyTheme() {
        fill.cornerRadius = dp(M.radius)
        fill.setColor(Theme.color(P.stemCellsCardFill))
        fill.setStroke(dp(M.border).toInt().coerceAtLeast(1), Theme.color(P.stemCellsProposalBorder))
        ground.cornerRadius = dp(M.frameRadius)
        ground.setColor(Theme.color(P.stemCellsBackground))
        for (label in listOf(title, version, notice, status)) label.applyTheme()
        sidebar.applyTheme()
        keep.applyTheme()
        use.applyTheme()
    }

    companion object {
        private val headerStyle = StemCellsFonts.ui(StemCellsFonts.Weight.SemiBold, M.headerSize, M.headerHeight)
        private val versionStyle = StemCellsFonts.ui(StemCellsFonts.Weight.Regular, M.headerSize, M.headerHeight)
        private val buttonStyle = StemCellsFonts.ui(StemCellsFonts.Weight.Bold, M.buttonSize, M.noteHeight)
        private val noteStyle = StemCellsFonts.ui(StemCellsFonts.Weight.Regular, M.noteSize, M.noteHeight)
        private fun Rect.expandedButton() = Rect(x - 4, y - 8, width + 8, height + 16)

        /** Pure planning: explicit density and fixed-size faces, no View reads. */
        fun input(shown: StemCellProposalShown, resources: Resources, env: TextLayouts.Env): StemCellSidebarProposalLayout.Input {
            val fixed = env.copy(scale = TextScale.Standard)
            fun width(id: Int, style: com.slopus.happy.layout.TextStyle) = TextLayouts.measure(resources.getString(id), style, 100_000.0, fixed, 1).width
            val footer = if (shown.status == StemCellProposalShown.Status.Open) StemCellSidebarProposalLayout.Footer.Answers(
                width(R.string.stem_proposal_keep, buttonStyle), width(R.string.stem_proposal_use, buttonStyle), shown.notice != null,
            ) else StemCellSidebarProposalLayout.Footer.Status
            return StemCellSidebarProposalLayout.Input(StemCellsSidebarView.layoutRows(shown.rows), width(R.string.stem_proposed_sidebar, headerStyle), footer)
        }
    }

    private class ProposalButton(context: Context, private val primary: Boolean) : ManualLayout(context), ThemeAware {
        private val ink = if (primary) P.stemCellsPrimaryButtonInk else P.stemCellsCardTitle
        private val label = Label(context, buttonStyle, ink, 1, Layout.Alignment.ALIGN_CENTER).apply {
            followsTextSize = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val spinner = ProgressBar(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            setWillNotDraw(false)
            isFocusable = true
            addView(label)
            addView(spinner)
        }

        fun setTitle(value: String) { label.text = value; contentDescription = value }
        fun show(enabled: Boolean, loading: Boolean) {
            isEnabled = enabled
            alpha = if (enabled) 1f else M.disabledAlpha.toFloat()
            label.visibility = if (loading) INVISIBLE else VISIBLE
            spinner.visibility = if (loading) VISIBLE else GONE
            invalidate()
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
        }

        override fun layoutSubviews(width: Double, height: Double) {
            label.setFrame(Rect(4.0, (height - M.noteHeight) / 2, width - 8, M.noteHeight))
            val side = M.buttonHeight * 0.7
            spinner.setFrame(Rect((width - side) / 2, (height - side) / 2, side, side))
        }

        override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

        override fun onDraw(canvas: Canvas) {
            val fill = if (primary) (if (isEnabled) P.stemCellsPrimaryButton else P.stemCellsPrimaryButtonDisabled) else P.stemCellsButton
            paint.style = Paint.Style.FILL
            paint.color = Theme.color(fill)
            paint.alpha = if (isPressed && isEnabled) 204 else 255
            val radius = dp(M.buttonHeight / 2)
            canvas.drawRoundRect(dp(4.0), dp(8.0), width - dp(4.0), height - dp(8.0), radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(M.border)
            paint.color = Theme.color(if (primary) (if (isEnabled) P.stemCellsPrimaryButton else fill) else P.stemCellsDivider)
            val half = paint.strokeWidth / 2
            canvas.drawRoundRect(dp(4.0) + half, dp(8.0) + half, width - dp(4.0) - half, height - dp(8.0) - half, radius, radius, paint)
        }

        override fun applyTheme() {
            label.applyTheme()
            spinner.indeterminateTintList = ColorStateList.valueOf(Theme.color(ink))
            invalidate()
        }
    }
}