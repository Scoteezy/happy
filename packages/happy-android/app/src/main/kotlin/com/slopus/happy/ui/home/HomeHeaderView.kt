package com.slopus.happy.ui.home

import androidx.core.view.ViewCompat
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.HeaderGlassShadow
import com.slopus.happy.layout.HeaderScrim
import com.slopus.happy.layout.HomeHeaderLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.headerControlFill
import com.slopus.happy.layout.headerGlassBorder
import com.slopus.happy.layout.headerScrimTint
import com.slopus.happy.layout.homeHeaderStatus
import com.slopus.happy.net.ConnectionStatus
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame

/**
 * A header control's surface (happy-ios `HeaderGlassView`, MobileGlass.tsx's
 * static material). Android has no liquid glass, so it is a solid pill in
 * the material's resting colour, with the same 1dp rim and the platform's
 * elevation shadow in place of the 20dp blur shadow.
 */
class HeaderControlView(context: Context) : ManualLayout(context), ThemeAware {
    private val shape = GradientDrawable()

    init {
        background = shape
        elevation = dp(HeaderGlassShadow.offsetY)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                outline.alpha = 1f
            }
        }
        applyTheme()
    }

    override fun layoutSubviews(width: Double, height: Double) {
        shape.cornerRadius = dp(height / 2)
    }

    override fun applyTheme() {
        shape.setColor(Theme.color(Palette.headerControlFill))
        shape.setStroke(dp(1), Theme.color(Palette.headerGlassBorder))
        outlineAmbientShadowColor = 0xFF000000.toInt()
        outlineSpotShadowColor = 0xFF000000.toInt()
        alpha = 1f
        invalidate()
    }
}

/**
 * MobileHeaderScrim.tsx, home variant (happy-ios `HeaderScrimView`): a dim
 * wash at full strength at the screen's edge, feathered out over the last
 * 36dp towards the content. The header's runs down from the top; the dock's
 * runs up from the foot. iOS adds a light blur under the wash; Android draws
 * the wash alone.
 */
class HeaderScrimView(context: Context, private val fromTop: Boolean = true) : View(context), ThemeAware {
    private val paint = Paint()
    private var shaderHeight = -1
    private var shaderDark: Boolean? = null

    /** How present the scrim is, 0 to 1. */
    var presence: Float = 1f
        set(value) { if (field != value) { field = value; invalidate() } }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun applyTheme() {
        shaderDark = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (height <= 0) return
        if (shaderHeight != height || shaderDark != Theme.isDark) {
            val stops = HeaderScrim.stops(px(height), if (Theme.isDark) HeaderScrim.strongPeakDark else HeaderScrim.strongPeakLight)
            val base = Theme.rgba(Palette.headerScrimTint)
            val colors = stops.map { base.withAlpha(it.alpha).argb }.toIntArray()
            val positions = stops.map { it.location.toFloat() }.toFloatArray()
            paint.shader = if (fromTop) {
                LinearGradient(0f, 0f, 0f, height.toFloat(), colors, positions, Shader.TileMode.CLAMP)
            } else {
                LinearGradient(0f, height.toFloat(), 0f, 0f, colors, positions, Shader.TileMode.CLAMP)
            }
            shaderHeight = height
            shaderDark = Theme.isDark
        }
        paint.alpha = (presence * 255).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

/** StatusDot.tsx: a dot that breathes between full and 0.3 opacity while pulsing. */
class StatusDotView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null

    var color: Int = 0
        set(value) { field = value; invalidate() }

    var isPulsing = false
        set(value) { if (field != value) { field = value; updateAnimation() } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    private fun updateAnimation() {
        animator?.cancel()
        animator = null
        alpha = 1f
        if (!isPulsing || !isAttachedToWindow) return
        animator = ValueAnimator.ofFloat(1f, 0.3f).apply {
            duration = 1000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        canvas.drawOval(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

/** A 44dp header button holding one icon (Header.tsx `headerActionButton`), with the platform ripple. */
class HeaderIconButton(context: Context, icon: Icon, size: Double, label: String) : ManualLayout(context) {
    val iconView = IconView(context, icon, size, Palette.headerTint)

    init {
        addView(iconView)
        contentDescription = label
        isClickable = true
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(0x22000000), null, null).apply { radius = dp(22) }
    }

    override fun layoutSubviews(width: Double, height: Double) {
        iconView.setFrame(Rect(0.0, 0.0, width, height))
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        iconView.alpha = if (pressed) 0.5f else 1f
    }
}

/**
 * The phone home header (MainView.tsx's phone header; happy-ios
 * `HomeHeaderView`): the logo, or the account's picture, in a circle,
 * "Sessions" with the socket status under it while it isn't healthy, and the
 * filter menu and settings in a pill. It floats over home's content on the
 * scrim; [HomeHeaderLayout] places everything.
 */
class HomeHeaderView(context: Context) : ManualLayout(context), ThemeAware {
    val scrim = HeaderScrimView(context)
    val logoControl = HeaderControlView(context)
    private val logo = LogoView(context)
    val actions = HeaderControlView(context)
    val filterButton = HeaderIconButton(context, Icon.ionicons("filter"), 22.0, context.getString(R.string.sessions_filter_title))
    private val actionDivider = View(context)
    val settingsButton = HeaderIconButton(context, Icon.ionicons("settings-outline"), 22.0, context.getString(R.string.settings_title))
    private val titleLabel = Label(context, TextStyles.headerTitle, Palette.headerTint, maxLines = 1, alignment = android.text.Layout.Alignment.ALIGN_CENTER)
    private val statusDot = StatusDotView(context)
    private val statusLabel = Label(context, TextStyles.homeHeaderStatus, Palette.textSecondary, maxLines = 1, alignment = android.text.Layout.Alignment.ALIGN_CENTER)

    /** The safe area's top: the status bar the header sits under. */
    var safeTop: Double = 0.0
        set(value) { if (field != value) { field = value; requestLayout() } }

    /** The account's picture stands in for the logo (several accounts, or Stem Cells' settings). */
    var showsAccountAvatar = false
        set(value) {
            field = value
            logo.mode = if (value) LogoView.Mode.Account else LogoView.Mode.Logo
            requestLayout()
        }

    var status: ConnectionStatus = ConnectionStatus.DISCONNECTED
        set(value) { if (field != value) { field = value; applyStatus() } }

    init {
        clipChildren = false
        clipToPadding = false
        logoControl.isClickable = false
        logoControl.addView(logo)
        actionDivider.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        actions.addView(filterButton)
        actions.addView(actionDivider)
        actions.addView(settingsButton)
        titleLabel.text = context.getString(R.string.tabs_sessions)
        titleLabel.isFocusable = true
        ViewCompat.setAccessibilityHeading(titleLabel, true)
        addView(scrim)
        addView(logoControl)
        addView(actions)
        addView(titleLabel)
        addView(statusDot)
        addView(statusLabel)
        applyTheme()
        applyStatus()
    }

    override fun applyTheme() {
        actionDivider.setBackgroundColor(Theme.color(Palette.divider))
        applyStatus()
    }

    private val showsStatus get() = status != ConnectionStatus.CONNECTED

    private fun applyStatus() {
        val color: ColorToken = when (status) {
            ConnectionStatus.CONNECTED -> Palette.statusConnected
            ConnectionStatus.CONNECTING -> Palette.statusConnecting
            ConnectionStatus.DISCONNECTED -> Palette.statusDisconnected
            ConnectionStatus.ERROR -> Palette.statusError
        }
        statusLabel.text = context.getString(
            when (status) {
                ConnectionStatus.CONNECTED -> R.string.status_connected
                ConnectionStatus.CONNECTING -> R.string.status_connecting
                ConnectionStatus.DISCONNECTED -> R.string.status_disconnected
                ConnectionStatus.ERROR -> R.string.status_error
            },
        )
        statusLabel.color = color
        statusDot.color = Theme.color(color)
        statusDot.isPulsing = status == ConnectionStatus.CONNECTING
        statusLabel.visibility = if (showsStatus) VISIBLE else GONE
        statusDot.visibility = if (showsStatus) VISIBLE else GONE
        titleLabel.stateDescription = if (showsStatus) statusLabel.text else null
        requestLayout()
    }

    fun layout(width: Double): HomeHeaderLayout = HomeHeaderLayout(
        HomeHeaderLayout.Input(
            width = width,
            safeTop = safeTop,
            hairline = 1.0 / Theme.density,
            title = titleLabel.measurable,
            status = if (showsStatus) statusLabel.measurable else null,
            showsAccountAvatar = showsAccountAvatar,
        ),
    )

    override fun sizeThatFits(width: Double, height: Double): Size = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val l = layout(width)
        scrim.setFrame(l.backdrop)
        logoControl.setFrame(l.logoControl)
        logo.setFrame(l.logo.offsetBy(-l.logoControl.minX, -l.logoControl.minY))
        actions.setFrame(l.actions)
        val o = l.actions
        filterButton.setFrame(l.filterButton.offsetBy(-o.minX, -o.minY))
        actionDivider.setFrame(l.actionDivider.offsetBy(-o.minX, -o.minY))
        settingsButton.setFrame(l.settingsButton.offsetBy(-o.minX, -o.minY))
        titleLabel.setFrame(l.title)
        l.statusDot?.let { statusDot.setFrame(it) }
        l.status?.let { statusLabel.setFrame(it) }
    }

    /** Only the controls take touches; the rest lets the list scroll under it. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = ev.x.toInt()
            val y = ev.y.toInt()
            val hit = listOf(logoControl, actions).any { x in it.left until it.right && y in it.top until it.bottom }
            if (!hit) return false
        }
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * The Happy mark tinted in the header colour, or the account's picture: until
 * pictures are ported, the platform's account glyph, as iOS draws
 * person.crop.circle.fill for an account without one.
 */
private class LogoView(context: Context) : View(context), ThemeAware {
    enum class Mode { Logo, Account }

    var mode = Mode.Logo
        set(value) { field = value; invalidate() }

    private val mark = BitmapFactory.decodeResource(resources, R.drawable.happy_logo)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val person = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_account_circle)!!.mutate()

    override fun applyTheme() = invalidate()

    override fun onDraw(canvas: Canvas) {
        when (mode) {
            Mode.Logo -> {
                paint.colorFilter = PorterDuffColorFilter(Theme.color(Palette.headerTint), PorterDuff.Mode.SRC_IN)
                val scale = minOf(width / mark.width.toFloat(), height / mark.height.toFloat())
                val w = mark.width * scale
                val h = mark.height * scale
                canvas.drawBitmap(mark, null, android.graphics.RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), paint)
            }
            Mode.Account -> {
                // The glyph's circle is 20 of its 24: grown so the circle fills the box, as the SF Symbol does.
                val side = (minOf(width, height) * 24f / 20f).toInt()
                val left = (width - side) / 2
                val top = (height - side) / 2
                person.setBounds(left, top, left + side, top + side)
                person.setTint(Theme.color(Palette.headerTint))
                person.draw(canvas)
            }
        }
    }
}
