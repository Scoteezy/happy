package com.slopus.happy.ui.chat

import android.content.Context
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import com.slopus.happy.R
import com.slopus.happy.layout.ChatTextStyles
import com.slopus.happy.layout.HomeHeaderLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.chat.ChatHeaderScrim
import com.slopus.happy.layout.chat.ChatHeaderTitleLayout
import com.slopus.happy.layout.chat.ChatPlainHeaderLayout
import com.slopus.happy.layout.gitAddedText
import com.slopus.happy.layout.gitRemovedText
import com.slopus.happy.layout.headerGlassSubtitle
import com.slopus.happy.model.VisibleGitChanges
import com.slopus.happy.ui.components.AvatarView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.home.HeaderControlView
import com.slopus.happy.ui.home.HeaderIconButton
import com.slopus.happy.ui.home.HeaderScrimView
import com.slopus.happy.ui.nav.NavigationBar
import com.slopus.happy.ui.tablet.TabletShell
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.setFrame

/**
 * ChatHeaderView.tsx (happy-ios `ChatHeaderTitleView` and
 * `ChatHeaderAvatarControl` in a transparent navigation bar): back in a
 * circle, the session's name over its workspace or branch and the line
 * changes in a capsule as tall as the controls, and the session's avatar in
 * a circle, over the strong scrim. Android has no liquid glass: the
 * controls are the home header's solid pills, and back is Material's arrow.
 * The row is the home header's (52dp under the status bar), so the two
 * headers line up.
 *
 * On a tablet ([tablet], `TabletBranches.chatHeaderGlass` false) there is
 * no glass, back or avatar: an opaque bar (`header.background`) as tall as
 * the other bars beside the sidebar, with the title over its workspace or
 * branch and the line changes at the start of the row
 * ([ChatPlainHeaderLayout]). Tapping it opens the session's info.
 */
class ChatHeaderView(context: Context) : ManualLayout(context), ThemeAware {
    val scrim = HeaderScrimView(context)
    private val backControl = HeaderControlView(context)
    val backButton = HeaderIconButton(context, NavigationBar.BACK_ICON, 24.0, context.getString(R.string.common_back))
    val titleControl = HeaderControlView(context)
    private val titleLabel = Label(context, ChatTextStyles.headerTitle, Palette.headerTint, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER)
    private val subtitleLabel = Label(context, ChatTextStyles.headerSubtitle, Palette.headerGlassSubtitle, maxLines = 1, alignment = Layout.Alignment.ALIGN_CENTER)
    private val changesLabel = Label(context, ChatTextStyles.headerSubtitle, Palette.headerGlassSubtitle, maxLines = 1)
    val avatarControl = HeaderControlView(context)
    private val avatar = AvatarView(context)
    /** The tablet's title, at the start of the bar. */
    val plainControl = PlainTitleControl(context)
    private val plainTitle = Label(context, ChatTextStyles.headerTitle, Palette.headerTint, maxLines = 1)
    private val plainSubtitle = Label(context, ChatTextStyles.headerPlainSubtitle, Palette.textSecondary, maxLines = 1)
    private val plainChanges = Label(context, ChatTextStyles.headerPlainSubtitle, Palette.textSecondary, maxLines = 1)

    /** `TabletBranches.chatHeaderGlass` off: the opaque bar with the plain title. */
    var tablet = false
        set(value) {
            if (field == value) return
            field = value
            applyBranch()
        }

    /** `layout.headerMaxWidth`: the plain title's row is held to it. */
    var headerMaxWidth = Double.MAX_VALUE
        set(value) { if (field != value) { field = value; requestLayout() } }

    var safeTop: Double = 0.0
        set(value) { if (field != value) { field = value; requestLayout() } }

    /** Where the header's controls end: the list's oldest edge sits 12 under it. */
    val headerBottom: Double get() = safeTop + if (tablet) TabletShell.HEADER_HEIGHT else HomeHeaderLayout.Metrics.contentHeight

    /** MobileHeaderScrim: 0.80 while the conversation starts below the header, 0.96 once content runs under it. */
    var underlap = false
        set(value) {
            if (field == value) return
            field = value
            val opacity = (if (value) ChatHeaderScrim.underlapOpacity else ChatHeaderScrim.restingOpacity).toFloat()
            scrim.animate().alpha(opacity).setDuration((ChatHeaderScrim.fadeDuration * 1000).toLong()).start()
        }

    init {
        clipChildren = false
        scrim.alpha = ChatHeaderScrim.restingOpacity.toFloat()
        addView(scrim)
        backControl.addView(backButton)
        addView(backControl)
        titleControl.addView(titleLabel)
        titleControl.addView(subtitleLabel)
        titleControl.addView(changesLabel)
        titleControl.isClickable = true
        titleControl.isFocusable = true
        titleControl.visibility = GONE
        addView(titleControl)
        avatarControl.addView(avatar)
        avatarControl.isClickable = true
        avatarControl.isFocusable = true
        avatarControl.visibility = GONE
        addView(avatarControl)
        listOf(plainTitle, plainSubtitle, plainChanges).forEach {
            it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            plainControl.column.addView(it)
        }
        plainControl.visibility = GONE
        addView(plainControl)
    }

    override fun applyTheme() {
        changesLabel.text = changesLabel.text
        plainChanges.text = plainChanges.text
        setBackgroundColor(if (tablet) Theme.color(Palette.headerBackground) else 0)
    }

    private fun applyBranch() {
        val phone = if (tablet) GONE else VISIBLE
        scrim.visibility = phone
        backControl.visibility = phone
        if (tablet) {
            titleControl.visibility = GONE
            avatarControl.visibility = GONE
        } else {
            plainControl.visibility = GONE
        }
        applyTheme()
        update(lastTitle, lastSubtitle, lastChanges, lastAvatar)
    }

    private var lastTitle = ""
    private var lastSubtitle: String? = null
    private var lastChanges: VisibleGitChanges? = null
    private var lastAvatar: AvatarView.Content? = null

    /**
     * `title || subtitle` heads the capsule; with neither (the session isn't
     * loaded yet) there is no capsule rather than an empty one that grows.
     */
    fun update(title: String, subtitle: String?, changes: VisibleGitChanges?, avatar: AvatarView.Content?) {
        lastTitle = title
        lastSubtitle = subtitle
        lastChanges = changes
        lastAvatar = avatar
        val heading = title.ifEmpty { subtitle.orEmpty() }
        if (tablet) {
            plainControl.visibility = if (heading.isEmpty()) GONE else VISIBLE
            plainTitle.text = heading
            val sub = if (title.isEmpty()) null else subtitle
            plainSubtitle.text = sub.orEmpty()
            plainSubtitle.visibility = if (sub == null) GONE else VISIBLE
            plainChanges.text = changes?.let { changesText(it) } ?: ""
            plainChanges.visibility = if (changes == null) GONE else VISIBLE
            plainControl.contentDescription = listOfNotNull(heading, sub).joinToString(", ")
            requestLayout()
            return
        }
        titleControl.visibility = if (heading.isEmpty()) GONE else VISIBLE
        titleLabel.text = heading
        val sub = if (title.isEmpty()) null else subtitle
        subtitleLabel.text = sub.orEmpty()
        subtitleLabel.visibility = if (sub == null) GONE else VISIBLE
        changesLabel.text = changes?.let { changesText(it) } ?: ""
        changesLabel.visibility = if (changes == null) GONE else VISIBLE
        titleControl.contentDescription = listOfNotNull(heading, sub).joinToString(", ")
        avatarControl.visibility = if (avatar == null) GONE else VISIBLE
        this.avatar.content = avatar
        avatarControl.contentDescription = heading
        requestLayout()
    }

    /** GitLineChanges: "≈" when approximate, the lines added in green, removed in red. */
    private fun changesText(changes: VisibleGitChanges): CharSequence {
        val builder = android.text.SpannableStringBuilder()
        if (changes.approximate) builder.append("≈ ")
        // Each side only when it has lines, counted compactly (999, 1.2k, 12k), as GitChangesView draws them.
        if (changes.insertions > 0) builder.append(ChatStrings.string("+" + com.slopus.happy.model.compactCount(changes.insertions), Palette.gitAddedText))
        if (changes.insertions > 0 && changes.deletions > 0) builder.append(" ")
        if (changes.deletions > 0) builder.append(ChatStrings.string("-" + com.slopus.happy.model.compactCount(changes.deletions), Palette.gitRemovedText))
        return builder
    }

    override fun sizeThatFits(width: Double, height: Double): Size = Size(width, headerBottom)

    override fun layoutSubviews(width: Double, height: Double) {
        if (tablet) {
            layoutPlain(width)
            return
        }
        val m = ChatHeaderTitleLayout.Metrics
        val row = HomeHeaderLayout.Metrics.contentHeight
        val controlY = safeTop + (row - m.controlSize) / 2
        scrim.setFrame(Rect(0.0, 0.0, width, headerBottom + com.slopus.happy.layout.chat.ChatScreenLayout.Metrics.headerScrimOverhang))
        val back = Rect(m.headerPaddingH, controlY, m.controlSize, m.controlSize)
        backControl.setFrame(back)
        backButton.setFrame(Rect(0.0, 0.0, m.controlSize, m.controlSize))
        val avatarFrame = Rect(width - m.headerPaddingH - m.controlSize, controlY, m.controlSize, m.controlSize)
        avatarControl.setFrame(avatarFrame)
        avatar.setFrame(Rect((m.controlSize - m.avatarSize) / 2, (m.controlSize - m.avatarSize) / 2, m.avatarSize, m.avatarSize))
        if (titleControl.visibility != GONE) {
            val layout = ChatHeaderTitleLayout(
                ChatHeaderTitleLayout.maxWidth(width),
                titleLabel.measurable,
                if (subtitleLabel.visibility == GONE) null else subtitleLabel.measurable,
                if (changesLabel.visibility == GONE) null else changesLabel.measure(Double.MAX_VALUE),
            )
            titleControl.setFrame(Rect((width - layout.size.width) / 2, controlY, layout.size.width, layout.size.height))
            titleLabel.setFrame(layout.title)
            layout.subtitle?.let { subtitleLabel.setFrame(it) }
            layout.changes?.let { changesLabel.setFrame(it) }
        }
    }

    /** The bar's row under the status bar; the column is centred in it, at the start of the row. */
    private fun layoutPlain(width: Double) {
        val row = Rect(0.0, safeTop, width, TabletShell.HEADER_HEIGHT)
        if (plainControl.visibility == GONE) return
        val layout = ChatPlainHeaderLayout(
            bar = Rect(0.0, 0.0, width, row.height),
            size = row.size,
            maxWidth = headerMaxWidth,
            title = plainTitle.measurable,
            subtitle = if (plainSubtitle.visibility == GONE) null else plainSubtitle.measurable,
            changes = if (plainChanges.visibility == GONE) null else plainChanges.measure(Double.MAX_VALUE),
        )
        plainControl.setFrame(row)
        plainControl.column.setFrame(layout.column)
        plainTitle.setFrame(layout.title)
        layout.subtitle?.let { plainSubtitle.setFrame(it) }
        layout.changes?.let { plainChanges.setFrame(it) }
    }

    /** Only the controls take touches; the list scrolls under the rest. On a tablet the bar is opaque and takes them all. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (tablet) return super.dispatchTouchEvent(ev) || true
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = ev.x.toInt()
            val y = ev.y.toInt()
            val hit = listOf<View>(backControl, titleControl, avatarControl).any {
                it.visibility == VISIBLE && x in it.left until it.right && y in it.top until it.bottom
            }
            if (!hit) return false
        }
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * The plain title's row: a tap opens the session's info, and the column
 * grows a little while pressed (BubblePressable's `bubbleScale={1.012}`).
 */
class PlainTitleControl(context: Context) : ManualLayout(context) {
    val column = object : ManualLayout(context) {
        override fun layoutSubviews(width: Double, height: Double) = Unit
    }

    init {
        addView(column)
        isClickable = true
        isFocusable = true
        accessibilityHeading()
    }

    private fun accessibilityHeading() = androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val scale = if (pressed) 1.012f else 1f
        column.scaleX = scale
        column.scaleY = scale
    }

    override fun layoutSubviews(width: Double, height: Double) = Unit
}
