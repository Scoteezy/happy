package com.slopus.happy.ui.stemcells

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.FontFace
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.RGBA
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellsPalette
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.stemcells.StemCellAttachSheetLayout
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.home.HeaderControlView
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.BounceScrollView
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import kotlin.math.abs
import kotlin.math.max

/**
 * The composer's "+" sheet (happy-ios `StemCellAttachSheetViewController`),
 * after Claude's "Add to session": Photos at the left, the title, a close
 * circle at the right; a strip with the camera; then Paste from clipboard,
 * Add files, and the permission the next message runs with. iOS's strip
 * also lists the newest photos, read with the library permission; Android
 * keeps to the system photo picker, which needs none, so Photos opens it.
 *
 * It rises over its host as a sheet does, over a dim, and goes on a close, a
 * touch on the dim, back, or a drag down. A choice goes first, then
 * [onAction] hears it, except the permission, a menu over the sheet, which
 * stays up with its value updated by whoever shows it.
 */
class StemCellAttachSheet(context: Context, canTakePhoto: Boolean, permission: String) : ManualLayout(context), ThemeAware {
    enum class Action { Photos, Camera, Paste, Files, Permission }

    var onAction: ((Action) -> Unit)? = null
    /** Closed or dragged away with nothing chosen. */
    var onGone: (() -> Unit)? = null
    /** Where the sheet stops above the screen's foot (the bottom safe area, dp). */
    var safeBottom = 0.0
        set(value) { if (field != value) { field = value; requestLayout() } }

    /** The permission row's value: the mode's name. */
    var permission: String = permission
        set(value) {
            if (field == value) return
            field = value
            rows[2].value.text = value
            requestLayout()
        }

    /** Whether the permission row opens its menu; dimmed until the models are read. */
    var permissionReady = true
        set(value) {
            field = value
            rows[2].alpha = if (value) 1f else 0.5f
        }

    private val dim = View(context)
    private val sheet = Sheet(context)
    private val grabber = View(context)
    private val closeGlass = HeaderControlView(context)
    private val closeIcon = IconView(context, Icon.ionicons("close"), 24.0, Palette.headerTint)
    private val title = Label(context, TITLE, Palette.text, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER).apply { followsTextSize = false }
    private val photos = Label(context, PHOTOS, Palette.headerTint, maxLines = 1).apply { followsTextSize = false }
    private val strip = BounceScrollView(context)
    private val camera = Tile(context)
    private val group = object : ManualLayout(context) {
        override fun layoutSubviews(width: Double, height: Double) = Unit
    }
    private val groupShape = GradientDrawable().apply { cornerRadius = dp(StemCellAttachSheetLayout.Metrics.groupRadius) }
    /** Paste, files, permission. */
    val rows: List<Row> = listOf(
        Row(context, Icon.ionicons("copy-outline"), context.getString(R.string.stem_paste_from_clipboard), "attach-paste", null),
        Row(context, Icon.ionicons("document-outline"), context.getString(R.string.stem_add_files), "attach-files", null),
        Row(context, Icon.ionicons("shield-checkmark-outline"), context.getString(R.string.stem_permission), "attach-permission", permission),
    )
    private var layout: StemCellAttachSheetLayout? = null
    /** How far the sheet is down from where it rests (dp): its height when gone. */
    private var offset = 0.0
    private var animator: ValueAnimator? = null
    private var closing = false
    private var chose = false

    private companion object {
        val TITLE = TextStyle(FontFace.SystemSemiBold, 17)
        val PHOTOS = TextStyle(FontFace.System, 17)
        val ROW = TextStyle(FontFace.System, 17)
        val TILE = TextStyle(FontFace.SystemMedium, 15)
        const val RADIUS = 28.0
        const val DIM = 0.25
    }

    init {
        tag = "attach-sheet"
        dim.setOnClickListener { dismiss() }
        dim.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(dim)
        addView(sheet)
        sheet.isClickable = true
        grabber.background = GradientDrawable().apply { cornerRadius = dp(2.5) }
        grabber.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        sheet.addView(grabber)

        closeGlass.tag = "attach-close"
        closeGlass.contentDescription = context.getString(R.string.stem_close)
        closeGlass.isClickable = true
        closeGlass.setOnClickListener { dismiss() }
        closeGlass.addView(closeIcon)
        sheet.addView(closeGlass)

        title.text = context.getString(R.string.stem_add_to_message)
        if (android.os.Build.VERSION.SDK_INT >= 28) title.isAccessibilityHeading = true
        sheet.addView(title)

        photos.text = context.getString(R.string.stem_photos)
        photos.tag = "attach-photos"
        photos.isClickable = true
        photos.isFocusable = true
        photos.contentDescription = photos.text
        photos.setOnClickListener { finish(Action.Photos) }
        sheet.addView(photos)

        strip.tag = "attach-strip"
        strip.isScrollEnabled = false
        sheet.addView(strip)
        camera.showCamera(canTakePhoto)
        camera.setOnClickListener { finish(Action.Camera) }
        strip.content.addView(camera)

        group.background = groupShape
        group.clipToOutline = true
        group.outlineProvider = ViewOutlineProvider.BACKGROUND
        sheet.addView(group)
        for ((index, row) in rows.withIndex()) {
            group.addView(row)
            row.setOnClickListener {
                when (index) {
                    0 -> finish(Action.Paste)
                    1 -> finish(Action.Files)
                    else -> if (permissionReady) onAction?.invoke(Action.Permission)
                }
            }
        }
        applyTheme()
    }

    override fun applyTheme() {
        dim.setBackgroundColor(Theme.color(com.slopus.happy.layout.ColorToken(RGBA(0.0, 0.0, 0.0, DIM), RGBA(0.0, 0.0, 0.0, DIM * 2))))
        sheet.shape.setColor(Theme.color(StemCellsPalette.stemCellsAttachGround))
        (grabber.background as GradientDrawable).setColor(Theme.color(Palette.textSecondary) and 0x66ffffff)
        groupShape.setColor(Theme.color(StemCellsPalette.stemCellsAttachFill))
        camera.applyTheme()
        rows.forEach { it.applyTheme() }
    }

    /** The anchor for the permission's menu. */
    val permissionRow: View get() = rows[2]

    // Showing

    /** Rises from the foot of its host into place. */
    fun present() {
        offset = Double.MAX_VALUE
        dim.alpha = 0f
        requestLayout()
        post {
            val height = sheetHeight()
            offset = height
            place()
            animate(to = 0.0)
        }
    }

    /** Goes down out of view; nothing was chosen. */
    fun dismiss(animated: Boolean = true) = close(animated) { if (!chose) onGone?.invoke() }

    /** A choice: the sheet goes, then it is heard over what it covered. */
    private fun finish(action: Action) {
        if (closing) return
        chose = true
        close(true) { onAction?.invoke(action) }
    }

    private fun close(animated: Boolean, then: () -> Unit) {
        if (closing) return
        closing = true
        val done = {
            (parent as? android.view.ViewGroup)?.removeView(this)
            then()
        }
        if (!animated) return done()
        animate(to = sheetHeight(), duration = 250, end = done)
    }

    private fun animate(to: Double, duration: Long = 350, end: (() -> Unit)? = null) {
        animator?.cancel()
        val from = offset
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                offset = from + (to - from) * (it.animatedValue as Float)
                place()
            }
            if (end != null) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = end()
            })
            start()
        }
    }

    private fun sheetHeight() = (layout?.height ?: 0.0) + safeBottom

    // Dragging down

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (closing) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (ev.y >= sheet.top && ev.rawY - downY > slop) {
                    dragging = true
                    animator?.cancel()
                    downY = ev.rawY
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                offset = max(0.0, px(event.rawY - downY))
                place()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                tracker?.computeCurrentVelocity(1000)
                val velocity = px(tracker?.yVelocity ?: 0f)
                if (offset > sheetHeight() / 3 || velocity > 800) dismiss() else animate(to = 0.0, duration = 250)
            }
        }
        return true
    }

    // Layout

    override fun layoutSubviews(width: Double, height: Double) {
        dim.setFrame(Rect(0.0, 0.0, width, height))
        val values = rows.map { row -> row.value.text.takeIf { it.isNotEmpty() }?.let { row.value.measure(Double.MAX_VALUE).width } }
        val layout = StemCellAttachSheetLayout(
            width, title.measure(Double.MAX_VALUE).width, photos.measure(Double.MAX_VALUE).width, 1, values, Theme.density.toDouble(),
        )
        this.layout = layout
        val m = StemCellAttachSheetLayout.Metrics
        grabber.setFrame(Rect((width - 36) / 2, 5.0, 36.0, 5.0))
        closeGlass.setFrame(layout.close)
        closeIcon.setFrame(Rect(10.0, 10.0, 24.0, 24.0))
        title.setFrame(layout.title)
        photos.setFrame(Rect(layout.photos.minX, layout.photos.midY - title.lineHeight / 2, layout.photos.width, title.lineHeight))
        strip.setFrame(layout.strip)
        strip.contentHeight = m.tile
        strip.content.frames[camera] = layout.tiles[0]
        strip.content.requestLayout()
        group.setFrame(layout.group)
        for ((row, frame) in rows.zip(layout.rows)) row.place(frame)
        if (offset == Double.MAX_VALUE) offset = sheetHeight()
        place()
    }

    private fun place() {
        val w = px(width)
        val h = px(height)
        val total = sheetHeight()
        sheet.setFrame(Rect(0.0, h - total, w, total + RADIUS))
        sheet.translationY = dp(offset)
        dim.alpha = if (total <= 0) 0f else (1 - (offset / total).coerceIn(0.0, 1.0)).toFloat()
    }

    /** The sheet's ground, its top corners rounded, running past the screen's foot. */
    private class Sheet(context: Context) : ManualLayout(context) {
        val shape = GradientDrawable().apply { cornerRadius = dp(RADIUS) }

        init {
            background = shape
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(RADIUS))
            }
        }

        override fun layoutSubviews(width: Double, height: Double) = Unit
    }

    /** The strip's camera tile: its glyph over its name, dimmed where there is none. */
    private class Tile(context: Context) : ManualLayout(context), ThemeAware {
        private val shape = GradientDrawable().apply { cornerRadius = dp(StemCellAttachSheetLayout.Metrics.tileRadius) }
        private val icon = IconView(context, Icon.ionicons("camera"), 28.0, Palette.headerTint)
        private val label = Label(context, TILE, Palette.text, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER).apply { followsTextSize = false }

        init {
            background = shape
            addView(icon)
            addView(label)
            isClickable = true
            isFocusable = true
        }

        fun showCamera(available: Boolean) {
            label.text = context.getString(R.string.stem_camera)
            tag = "attach-camera"
            contentDescription = context.getString(R.string.stem_camera)
            isEnabled = available
            alpha = if (available) 1f else 0.45f
            if (!available) stateDescription = context.getString(R.string.stem_camera_unavailable)
        }

        override fun applyTheme() {
            shape.setColor(Theme.color(StemCellsPalette.stemCellsAttachFill))
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            icon.alpha = if (pressed) 0.7f else 1f
        }

        override fun layoutSubviews(width: Double, height: Double) {
            // The glyph and name, centred together.
            val labelHeight = 20.0
            val block = 28 + 6 + labelHeight
            val top = Math.round((height - block) / 2).toDouble()
            icon.setFrame(Rect((width - 28) / 2, top, 28.0, 28.0))
            label.setFrame(Rect(4.0, top + 28 + 6, width - 8, labelHeight))
        }
    }

    /** One row of the group: an icon, its name, and for a setting its value and a chevron. */
    class Row(context: Context, icon: Icon, text: String, id: String, value: String?) : ManualLayout(context), ThemeAware {
        private val icon = IconView(context, icon, StemCellAttachSheetLayout.Metrics.icon, Palette.headerTint)
        private val title = Label(context, ROW, Palette.text, maxLines = 1).apply { followsTextSize = false }
        val value = Label(context, ROW, Palette.textSecondary, maxLines = 1, alignment = Layout.Alignment.ALIGN_OPPOSITE).apply { followsTextSize = false }
        private val chevron = IconView(context, Icon.ionicons("chevron-forward"), StemCellAttachSheetLayout.Metrics.chevron, Palette.textSecondary)
        private val separator = View(context)
        private var placed: StemCellAttachSheetLayout.Row? = null

        init {
            title.text = text
            this.value.text = value ?: ""
            chevron.visibility = if (value == null) GONE else VISIBLE
            for (view in listOf(this.icon, title, this.value, chevron, separator)) {
                view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(view)
            }
            tag = id
            isClickable = true
            isFocusable = true
            contentDescription = text
            applyTheme()
        }

        override fun applyTheme() {
            separator.setBackgroundColor(Theme.color(StemCellsPalette.stemCellsDivider))
            setBackgroundColor(if (isPressed) Theme.color(StemCellsPalette.stemCellsRowPressed) else 0)
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            applyTheme()
        }

        /** Placed by the sheet's layout, in its group's space. */
        fun place(row: StemCellAttachSheetLayout.Row) {
            placed = row
            stateDescription = value.text.takeIf { it.isNotEmpty() }?.toString()
            setFrame(row.frame)
        }

        override fun layoutSubviews(width: Double, height: Double) {
            val row = placed ?: return
            fun local(rect: Rect) = Rect(rect.minX - row.frame.minX, rect.minY - row.frame.minY, rect.width, rect.height)
            icon.setFrame(local(row.icon))
            title.setFrame(local(row.title))
            row.value?.let { value.setFrame(local(it)) }
            row.chevron?.let { chevron.setFrame(local(it)) }
            separator.visibility = if (row.separator == null) GONE else VISIBLE
            row.separator?.let { separator.setFrame(local(it)) }
        }
    }
}
