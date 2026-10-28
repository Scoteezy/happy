package com.slopus.happy.ui.details

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.core.view.WindowCompat
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.layout.DuplicateSheetLayout
import com.slopus.happy.layout.DuplicateSheetMaterial
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.SecondaryPalette
import com.slopus.happy.layout.SecondaryTextStyles
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.model.details.DuplicateSheetModel
import com.slopus.happy.model.details.SessionInfoText
import com.slopus.happy.model.parseJsonObject
import com.slopus.happy.sync.ForkOptions
import com.slopus.happy.sync.ForkSource
import com.slopus.happy.sync.MachineOps
import com.slopus.happy.ui.chat.ChatScreen
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * components/DuplicateSheet.tsx in BaseModal (happy-ios
 * `DuplicateSheetViewController`): the session's rewind points, newest
 * first; Duplicate forks the session cut after the chosen one and replaces
 * [presenter] with the new session.
 *
 * iOS presents it over the full screen with the backdrop blurred and dimmed
 * and the card in glass. Here it is a full-screen dialog whose window blurs
 * (Android 12 and later, where the device supports it) and dims what is
 * behind; the card is the glass overlay colour over the surface.
 */
class DuplicateSheet private constructor(
    private val presenter: Screen,
    private val graph: AppGraph,
    private val sessionId: String,
) {
    private val context = presenter.context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
    private val sheet = SheetView(context)
    private var source: ForkSource? = null
    private var points: DuplicateSheetModel.Points? = null
    private var selectedId: String? = null
    private var duplicating = false

    private val ops: DuplicateSheetModel.Ops? = graph.account.value?.machineOps?.let(::LiveOps)

    private class LiveOps(private val machines: MachineOps) : DuplicateSheetModel.Ops {
        override suspend fun claudeRewindPoints(machineId: String, directory: String, claudeSessionId: String) =
            machines.claudeRewindPoints(machineId, directory, claudeSessionId)

        override suspend fun codexRewindPoints(machineId: String, directory: String, codexThreadId: String) =
            machines.codexRewindPoints(machineId, directory, codexThreadId)

        override suspend fun forkAndSpawn(source: ForkSource, options: ForkOptions) = machines.forkAndSpawn(source, options)
    }

    private fun show() {
        dialog.setContentView(sheet, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setOnDismissListener { scope.cancel() }
        dialog.window?.let { window ->
            // Over the whole screen, bars included, as iOS's overFullScreen.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            }
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !Theme.isDark
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(BACKDROP_DIM)
            window.setWindowAnimations(android.R.style.Animation_Dialog)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = dp(BACKDROP_BLUR.toInt()) }
            }
        }
        render()
        dialog.show()
        sheet.card.scaleX = DuplicateSheetMaterial.appearScale.toFloat()
        sheet.card.scaleY = DuplicateSheetMaterial.appearScale.toFloat()
        sheet.card.animate().scaleX(1f).scaleY(1f).setDuration((DuplicateSheetMaterial.appearDuration * 1000).toLong()).start()
        load()
    }

    private fun close() = dialog.dismiss()

    // Loading

    /** Reads the session off the main thread, then lists its points. */
    private fun load() {
        val missing = context.getString(R.string.session_fork_error_missing_metadata)
        scope.launch {
            val record = withContext(Dispatchers.IO) { graph.store.session(sessionId) }
            source = record?.let { SessionInfoText.forkSource(it.id, parseJsonObject(it.metadataJson)) }
            val ops = ops
            if (source == null || ops == null) {
                points = DuplicateSheetModel.Points.Failed(missing)
                render()
                return@launch
            }
            val loaded = DuplicateSheetModel.loadPoints(source, ops, missing)
            points = loaded
            // A selection that isn't among the points is dropped.
            if (loaded is DuplicateSheetModel.Points.Loaded && loaded.points.none { it.id == selectedId }) selectedId = null
            render()
        }
    }

    private val loadedPoints get() = (points as? DuplicateSheetModel.Points.Loaded)?.points.orEmpty()
    private val selected get() = selectedId?.let { id -> loadedPoints.firstOrNull { it.id == id } }

    // Rendering

    private fun render() {
        sheet.show(points, loadedPoints, selectedId)
        sheet.confirm.title = context.getString(if (duplicating) R.string.common_loading else R.string.session_duplicate_sheet_confirm)
        sheet.confirm.isEnabled = !duplicating && selected != null && source != null
    }

    private fun ageText(age: DuplicateSheetModel.Age): String {
        val res = context.resources
        return when (age) {
            DuplicateSheetModel.Age.JustNow -> context.getString(R.string.time_just_now)
            is DuplicateSheetModel.Age.Minutes -> res.getQuantityString(R.plurals.time_minutes_ago, age.count, age.count)
            is DuplicateSheetModel.Age.Hours -> res.getQuantityString(R.plurals.time_hours_ago, age.count, age.count)
            is DuplicateSheetModel.Age.Days -> res.getQuantityString(R.plurals.time_days_ago, age.count, age.count)
        }
    }

    // Actions

    private fun select(id: String) {
        selectedId = id
        render()
    }

    private fun confirmDuplicate() {
        // Nothing to pick until the session has been read.
        if (duplicating || points == null) return
        val source = source
        val ops = ops
        if (source == null || ops == null) {
            Alerts.show(context, context.getString(R.string.common_error), context.getString(R.string.session_fork_error_missing_metadata))
            return
        }
        val point = selected ?: run {
            Alerts.show(context, context.getString(R.string.common_error), context.getString(R.string.session_duplicate_row_disabled))
            return
        }
        duplicating = true
        render()
        scope.launch {
            try {
                val id = DuplicateSheetModel.duplicate(
                    source, point, ops,
                    context.getString(R.string.session_fork_error_missing_metadata),
                    context.getString(R.string.session_fork_error_generic),
                )
                duplicating = false
                openSession(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                duplicating = false
                render()
                val message = (e as? DuplicateSheetModel.Failure)?.message ?: context.getString(R.string.session_info_unknown_error)
                Alerts.show(context, context.getString(R.string.common_error), message)
            }
        }
    }

    /** onClose, then replaceToSession: the presenter's screen gives way to the new session. */
    private fun openSession(id: String) {
        close()
        val stack = presenter.stack ?: return
        if (stack.top === presenter) stack.replaceTop(ChatScreen(context, graph, id))
    }

    // Views

    private inner class SheetView(context: Context) : ManualLayout(context) {
        val card = Card(context)
        private val title = label(SecondaryTextStyles.sheetTitle, Palette.text).apply { text = context.getString(R.string.session_duplicate_sheet_title) }
        private val subtitle = label(SecondaryTextStyles.sheetSubtitle, Palette.textSecondary).apply { text = context.getString(R.string.session_duplicate_sheet_subtitle) }
        private val headerDivider = Hairline(context)
        private val list = ScrollView(context).apply { isVerticalScrollBarEnabled = true; overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS }
        private val listContent = ListContent(context)
        private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyle).apply {
            // UIActivityIndicatorView's grey.
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Theme.color(Palette.textSecondary))
        }
        private val message = label(SecondaryTextStyles.sheetEmpty, Palette.textSecondary, alignment = Layout.Alignment.ALIGN_CENTER)
        private var rows: List<PointRow> = emptyList()
        private val actionsDivider = Hairline(context)
        private val cancel = SheetButton(context, primary = false).apply { title = context.getString(R.string.common_cancel); setOnClickListener { close() } }
        val confirm = SheetButton(context, primary = true).apply { setOnClickListener { confirmDuplicate() } }
        private var loading = true

        init {
            list.addView(listContent)
            listContent.addView(spinner)
            listContent.addView(message)
            for (view in listOf(title, subtitle, headerDivider, list, actionsDivider, cancel, confirm)) card.addView(view)
            addView(card)
            // Only taps on the backdrop close the sheet.
            setOnClickListener { close() }
            card.isClickable = true
        }

        private fun label(style: TextStyle, color: ColorToken, maxLines: Int = 0, alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
            Label(context, style, color, maxLines, alignment)

        fun show(points: DuplicateSheetModel.Points?, loaded: List<DuplicateSheetModel.Point>, selectedId: String?) {
            loading = points == null
            spinner.visibility = if (loading) VISIBLE else GONE
            when (points) {
                null -> message.visibility = GONE
                is DuplicateSheetModel.Points.Failed -> {
                    message.visibility = VISIBLE
                    message.text = points.message
                }
                is DuplicateSheetModel.Points.Loaded -> {
                    message.visibility = if (points.points.isEmpty()) VISIBLE else GONE
                    message.text = context.getString(R.string.session_duplicate_sheet_empty)
                }
            }
            while (rows.size > loaded.size) listContent.removeView(rows.last()).also { rows = rows.dropLast(1) }
            while (rows.size < loaded.size) rows = rows + PointRow(context).also { row -> row.setOnClickListener { select(row.pointId) }; listContent.addView(row) }
            val now = System.currentTimeMillis().toDouble()
            rows.zip(loaded).forEach { (row, point) ->
                row.pointId = point.id
                row.text.text = DuplicateSheetModel.preview(point.text)
                row.meta.text = ageText(DuplicateSheetModel.age(point.timestamp, now))
                row.isChosen = point.id == selectedId
                row.contentDescription = "${row.text.text}, ${row.meta.text}"
                row.isSelected = row.isChosen
            }
            requestLayout()
        }

        private fun sheetLayout(width: Double, height: Double): DuplicateSheetLayout {
            val body = when {
                loading -> DuplicateSheetLayout.Body.Loading(Size(SPINNER, SPINNER))
                rows.isEmpty() -> DuplicateSheetLayout.Body.Message(message.measurable)
                else -> DuplicateSheetLayout.Body.Rows(rows.map { it.text.measurable to it.meta.measurable })
            }
            return DuplicateSheetLayout(
                DuplicateSheetLayout.Input(Size(width, height), title.measurable, subtitle.measurable, body, confirm.textHeight, 1.0 / Theme.density),
            )
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val layout = sheetLayout(width, height)
            card.setFrame(layout.card)
            title.setFrame(layout.title)
            subtitle.setFrame(layout.subtitle)
            headerDivider.setHairline(layout.headerDivider)
            list.setFrame(layout.list)
            listContent.frames = layout
            actionsDivider.setHairline(layout.actionsDivider)
            cancel.setFrame(layout.cancel)
            confirm.setFrame(layout.confirm)
        }

        private inner class ListContent(context: Context) : ManualLayout(context) {
            var frames: DuplicateSheetLayout? = null
                set(value) { field = value; requestLayout() }

            override fun sizeThatFits(width: Double, height: Double) = frames?.listContentSize ?: Size(width, 0.0)

            override fun layoutSubviews(width: Double, height: Double) {
                val layout = frames ?: return
                layout.body?.let {
                    spinner.setFrame(it)
                    message.setFrame(it)
                }
                rows.zip(layout.rows).forEach { (row, frame) -> row.apply(frame) }
            }
        }
    }

    /** The card: 16dp corners, the glass overlay colour over the surface, inside a hairline glass rim. */
    private class Card(context: Context) : ManualLayout(context), ThemeAware {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = RectF()

        init {
            setWillNotDraw(false)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(DuplicateSheetLayout.Metrics.cornerRadius))
                }
            }
            elevation = dp(12.0)
        }

        override fun applyTheme() = invalidate()

        override fun layoutSubviews(width: Double, height: Double) = invalidateOutline()

        override fun onDraw(canvas: Canvas) {
            val radius = dp(DuplicateSheetLayout.Metrics.cornerRadius)
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            paint.style = Paint.Style.FILL
            paint.color = Theme.color(Palette.surface)
            canvas.drawRoundRect(bounds, radius, radius, paint)
            paint.color = Theme.color(SecondaryPalette.sheetOverlay)
            canvas.drawRoundRect(bounds, radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = Theme.color(Palette.glassBorder)
            bounds.inset(0.5f, 0.5f)
            canvas.drawRoundRect(bounds, radius, radius, paint)
        }
    }

    /** One device pixel tall wherever its frame falls, as UIKit's hairline is. */
    private class Hairline(context: Context) : View(context), ThemeAware {
        init { applyTheme() }
        override fun applyTheme() = setBackgroundColor(Theme.color(Palette.divider))

        fun setHairline(frame: Rect) {
            val d = Theme.density
            val left = Math.round(frame.minX * d).toInt()
            val top = Math.round(frame.minY * d).toInt()
            val right = Math.round(frame.maxX * d).toInt()
            measure(MeasureSpec.makeMeasureSpec(right - left, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(1, MeasureSpec.EXACTLY))
            layout(left, top, right, top + 1)
        }
    }

    /** A rewind point: its preview (three lines at most) over its age. Chosen or pressed, it takes the high surface. */
    private class PointRow(context: Context) : ManualLayout(context), ThemeAware {
        val text = Label(context, SecondaryTextStyles.sheetRow, Palette.text, maxLines = 3)
        val meta = Label(context, SecondaryTextStyles.sheetMeta, Palette.textSecondary)
        private val divider = Hairline(context)
        var pointId = ""
        var isChosen = false
            set(value) { field = value; applyTheme() }

        init {
            addView(text)
            addView(meta)
            addView(divider)
            isClickable = true
        }

        fun apply(row: DuplicateSheetLayout.Row) {
            setFrame(row.frame)
            val origin = row.frame
            text.setFrame(row.text.offsetBy(-origin.x, -origin.y))
            meta.setFrame(row.meta.offsetBy(-origin.x, -origin.y))
            // The row's last pixel, wherever rounding put its bottom.
            divider.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(1, MeasureSpec.EXACTLY))
            divider.layout(0, height - 1, width, height)
        }

        override fun layoutSubviews(width: Double, height: Double) {}

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            applyTheme()
        }

        override fun applyTheme() = setBackgroundColor(if (isChosen || isPressed) Theme.color(Palette.surfaceHigh) else 0)
    }

    /** Cancel (the high surface) and Duplicate (the primary button): 15pt text with 11 above and below, 10 corners; 0.7 pressed, 0.4 disabled. */
    private class SheetButton(context: Context, private val primary: Boolean) : ManualLayout(context), ThemeAware {
        private val label = Label(
            context,
            if (primary) SecondaryTextStyles.sheetPrimaryButton else SecondaryTextStyles.sheetSecondaryButton,
            if (primary) SecondaryPalette.buttonPrimaryTint else Palette.text,
            maxLines = 1,
            alignment = Layout.Alignment.ALIGN_CENTER,
        )
        private val shape = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(DuplicateSheetLayout.Metrics.buttonCornerRadius) }

        var title: String = ""
            set(value) { field = value; label.text = value; contentDescription = value; requestLayout() }

        init {
            addView(label)
            background = shape
            isClickable = true
            applyTheme()
        }

        /** One line of the button's text. */
        val textHeight: Double get() = label.lineHeight

        override fun applyTheme() = shape.setColor(Theme.color(if (primary) SecondaryPalette.buttonPrimary else Palette.surfaceHigh))

        override fun layoutSubviews(width: Double, height: Double) {
            val inset = DuplicateSheetLayout.Metrics.buttonPaddingVertical
            label.setFrame(Rect(0.0, inset, width, height - inset * 2))
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            applyOpacity()
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            applyOpacity()
        }

        private fun applyOpacity() {
            alpha = when {
                !isEnabled -> DuplicateSheetMaterial.disabledOpacity.toFloat()
                isPressed -> DuplicateSheetMaterial.pressedOpacity.toFloat()
                else -> 1f
            }
        }
    }

    companion object {
        /** UIActivityIndicatorView's medium spinner. */
        private const val SPINNER = 20.0
        /** expo-blur's intensity 44 of the regular material, in dp. */
        private const val BACKDROP_BLUR = 13.0
        /** BaseModal's dim over its blur (SecondaryPalette.backdropDim). */
        private const val BACKDROP_DIM = 0.42f

        /** Presents the duplicate sheet for [sessionId] over [presenter]. */
        fun present(presenter: Screen, graph: AppGraph, sessionId: String) {
            if (graph.account.value == null) return
            DuplicateSheet(presenter, graph, sessionId).show()
        }
    }
}
