package com.slopus.happy.ui.home

import androidx.core.view.ViewCompat
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Layout
import android.view.View
import android.view.animation.LinearInterpolator
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.ArchiveToggleLayout
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.FlatSessionRowLayout
import com.slopus.happy.layout.GitChangesLayout
import com.slopus.happy.layout.MachineHeaderLayout
import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.ProjectCardLayout
import com.slopus.happy.layout.ProjectChatRowLayout
import com.slopus.happy.layout.ProjectListMetrics
import com.slopus.happy.layout.ProjectSectionLayout
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.SessionDayHeaderLayout
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyle
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.WorktreeRowLayout
import com.slopus.happy.layout.gitAddedText
import com.slopus.happy.layout.gitApproximate
import com.slopus.happy.layout.gitLineCount
import com.slopus.happy.layout.gitRemovedText
import com.slopus.happy.layout.projectMachine
import com.slopus.happy.layout.projectName
import com.slopus.happy.layout.projectSection
import com.slopus.happy.layout.projectTimestamp
import com.slopus.happy.layout.surfacePressed
import com.slopus.happy.layout.worktreeName
import com.slopus.happy.layout.worktreeToggle
import com.slopus.happy.model.DayTitle
import com.slopus.happy.model.FlatRowTopRight
import com.slopus.happy.model.ProjectHomeEntry
import com.slopus.happy.model.ProjectWorktree
import com.slopus.happy.model.SessionRowData
import com.slopus.happy.model.SessionState
import com.slopus.happy.model.VisibleGitChanges
import com.slopus.happy.model.WorktreeToggle
import com.slopus.happy.model.compactCount
import com.slopus.happy.ui.components.AvatarView
import com.slopus.happy.ui.components.IconView
import com.slopus.happy.ui.components.Label
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame

/** The blocked (orange) or ready (blue) dot colours a row's corner uses. */
fun cornerDotColor(blocked: Boolean): ColorToken = if (blocked) Palette.sessionBlockedDot else Palette.sessionReadyDot

/** "Today", "Yesterday", "3 days ago". */
fun Context.dayTitle(title: DayTitle): String = when (title) {
    DayTitle.Today -> getString(R.string.session_history_today)
    DayTitle.Yesterday -> getString(R.string.session_history_yesterday)
    is DayTitle.DaysAgo -> resources.getQuantityString(R.plurals.session_history_days_ago, title.days.toInt(), title.days.toInt())
}

/** A press highlight on a row, as the platform draws it: a ripple over the row's own colour. */
fun View.rowRipple(background: ColorToken?, pressed: ColorToken = Palette.surfacePressedOverlay) {
    val content = background?.let { ColorDrawable(Theme.color(it)) }
    this.background = RippleDrawable(ColorStateList.valueOf(Theme.color(pressed)), content, ColorDrawable(0xFFFFFFFF.toInt()))
}

/**
 * A title that shimmers while the agent works (ShimmerText; happy-ios
 * `ShimmerLabel`): the text in the secondary colour with a band of the full
 * text colour sweeping across it every 1.6s.
 */
class ShimmerLabel(context: Context, style: TextStyle) : ManualLayout(context), ThemeAware {
    private val base = Label(context, style, Palette.text, maxLines = 1)
    private val highlight = Label(context, style, Palette.text, maxLines = 1)
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var animator: ValueAnimator? = null
    private var phase = 0f

    var text: CharSequence
        get() = base.text
        set(value) { base.text = value; highlight.text = value; contentDescription = value }

    var color: ColorToken = Palette.text
        set(value) { field = value; applyColors() }

    var isShimmering = false
        set(value) { if (field != value) { field = value; applyColors(); updateAnimation() } }

    val measurable: Measurable get() = base.measurable

    init {
        addView(base)
        addView(highlight)
        setWillNotDraw(false)
        applyColors()
    }

    override fun applyTheme() = applyColors()

    private fun applyColors() {
        base.color = if (isShimmering) Palette.textSecondary else color
        highlight.visibility = if (isShimmering) VISIBLE else GONE
        invalidate()
    }

    override fun sizeThatFits(width: Double, height: Double): Size = base.measure(width)

    override fun layoutSubviews(width: Double, height: Double) {
        base.setFrame(Rect(0.0, 0.0, width, height))
        highlight.setFrame(Rect(0.0, 0.0, width, height))
    }

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
        if (!isShimmering || !isAttachedToWindow) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child !== highlight) return super.drawChild(canvas, child, drawingTime)
        val w = width.toFloat()
        val save = canvas.saveLayer(0f, 0f, w, height.toFloat(), null)
        val drawn = super.drawChild(canvas, child, drawingTime)
        // The band, a row wide, travels from one width before to one after.
        val center = -w / 2 + phase * 2 * w
        maskPaint.shader = LinearGradient(center - w / 2, 0f, center + w / 2, 0f, intArrayOf(0, 0xFF000000.toInt(), 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, height.toFloat(), maskPaint)
        canvas.restoreToCount(save)
        return drawn
    }
}

/** "≈ +12 -3" on a checkout (GitLineChanges.tsx). */
class GitChangesView(
    context: Context,
    approximateStyle: TextStyle = TextStyles.gitApproximate,
    countStyle: TextStyle = TextStyles.gitLineCount,
) : ManualLayout(context) {
    private val approximate = Label(context, approximateStyle, Palette.textSecondary, maxLines = 1).apply { text = "≈" }
    private val added = Label(context, countStyle, Palette.gitAddedText, maxLines = 1)
    private val removed = Label(context, countStyle, Palette.gitRemovedText, maxLines = 1)

    var changes: VisibleGitChanges? = null
        set(value) {
            if (field == value) return
            field = value
            approximate.visibility = if (value?.approximate == true) VISIBLE else GONE
            added.text = "+" + compactCount(value?.insertions ?: 0)
            added.visibility = if ((value?.insertions ?: 0) > 0) VISIBLE else GONE
            removed.text = "-" + compactCount(value?.deletions ?: 0)
            removed.visibility = if ((value?.deletions ?: 0) > 0) VISIBLE else GONE
            contentDescription = listOf(approximate, added, removed).filter { it.visibility == VISIBLE }.joinToString(" ") { it.text }
            requestLayout()
        }

    init {
        addView(approximate)
        addView(added)
        addView(removed)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun layout(): GitChangesLayout {
        fun size(label: Label) = if (label.visibility == VISIBLE) label.measure(Double.MAX_VALUE) else null
        return GitChangesLayout(size(approximate), size(added), size(removed))
    }

    override fun sizeThatFits(width: Double, height: Double): Size = layout().size

    override fun layoutSubviews(width: Double, height: Double) {
        val l = layout()
        l.approximate?.let { approximate.setFrame(it) }
        l.added?.let { added.setFrame(it) }
        l.removed?.let { removed.setFrame(it) }
    }
}

/** The presentation a session row is drawn from, worked out once from its data. */
data class SessionRowModel(
    val id: String,
    val title: String,
    val timestamp: String,
    /** null: the timestamp shows; else the dot, blocked or ready. */
    val dot: Boolean?,
    val shimmers: Boolean,
    val faded: Boolean,
    val project: String,
    val workspace: String?,
    val changes: VisibleGitChanges?,
    val hasDraft: Boolean,
    val showsDivider: Boolean,
    val avatar: AvatarView.Content,
    val state: SessionState,
)

/**
 * One session in the flat home list (components/FlatSessionRow.tsx;
 * happy-ios `SessionRowCell`): it measures its labels and applies
 * [FlatSessionRowLayout].
 */
class SessionRowCell(context: Context) : ManualLayout(context), ThemeAware {
    /** The swipe's archive as a TalkBack action, while it has one. */
    var archiveActionId = View.NO_ID
    private val avatar = AvatarView(context)
    private val titleLabel = ShimmerLabel(context, TextStyles.sessionTitle)
    private val timestampLabel = Label(context, TextStyles.sessionTimestamp, Palette.textSecondary, maxLines = 1, alignment = Layout.Alignment.ALIGN_OPPOSITE)
    private val dot = View(context)
    private val projectLabel = Label(context, TextStyles.sessionProject, Palette.textSecondary, maxLines = 1)
    private val workspaceLabel = Label(context, TextStyles.sessionWorkspace, Palette.textSecondary, maxLines = 1)
    private val branchIcon = IconView(context, Icon.ionicons("git-branch-outline"), FlatSessionRowLayout.Metrics.branchIconSize, Palette.textSecondary)
    private val changes = GitChangesView(context, TextStyles.sessionLineChanges, TextStyles.sessionLineChanges)
    private val draftIcon = IconView(context, Icon.ionicons("create-outline"), FlatSessionRowLayout.Metrics.draftIconSize, Palette.textSecondary)
    private val divider = View(context)
    var model: SessionRowModel? = null
        private set

    init {
        listOf(avatar, titleLabel, timestampLabel, dot, projectLabel, workspaceLabel, branchIcon, draftIcon, changes, divider).forEach(::addView)
        listOf(titleLabel, timestampLabel, projectLabel, workspaceLabel, branchIcon, draftIcon, changes).forEach {
            it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        dot.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
        isClickable = true
        isLongClickable = true
        isFocusable = true
        applyTheme()
    }

    override fun applyTheme() {
        rowRipple(if (isSelected) Palette.surfaceSelected else Palette.flatListBackground)
        divider.setBackgroundColor(Theme.color(Palette.divider))
        model?.let { applyDot(it) }
    }

    /** The tablet sidebar's open chat: the selected surface (UITableViewCell's `selectedBackgroundView`). */
    override fun setSelected(selected: Boolean) {
        val changed = selected != isSelected
        super.setSelected(selected)
        if (changed) applyTheme()
    }

    fun configure(model: SessionRowModel) {
        this.model = model
        avatar.content = model.avatar
        avatar.alpha = if (model.faded) 0.5f else 1f
        listOf(titleLabel, timestampLabel, dot, projectLabel, workspaceLabel, branchIcon, draftIcon, changes).forEach { it.alpha = if (model.faded) 0.6f else 1f }
        titleLabel.text = model.title
        titleLabel.color = if (model.faded) Palette.textSecondary else Palette.text
        titleLabel.isShimmering = model.shimmers
        timestampLabel.text = model.timestamp
        // The stamp keeps its width under a dot so the title never jumps.
        timestampLabel.visibility = if (model.dot == null) VISIBLE else INVISIBLE
        dot.visibility = if (model.dot == null) GONE else VISIBLE
        applyDot(model)
        projectLabel.text = model.project
        workspaceLabel.text = model.workspace.orEmpty()
        workspaceLabel.visibility = if (model.workspace == null) GONE else VISIBLE
        branchIcon.visibility = if (model.workspace == null) GONE else VISIBLE
        changes.changes = model.changes
        changes.visibility = if (model.changes == null) GONE else VISIBLE
        draftIcon.visibility = if (model.hasDraft) VISIBLE else GONE
        divider.visibility = if (model.showsDivider) VISIBLE else GONE
        contentDescription = listOfNotNull(
            model.title,
            when {
                model.dot == null -> null
                model.state == SessionState.InputRequired -> context.getString(R.string.status_input_required)
                model.state == SessionState.PermissionRequired -> context.getString(R.string.status_permission_required)
                else -> context.getString(R.string.status_unread)
            },
            model.project,
            model.workspace,
            model.changes?.let { changes.contentDescription?.toString() },
            model.timestamp,
        ).joinToString(", ")
        requestLayout()
    }

    private fun applyDot(model: SessionRowModel) {
        (dot.background as android.graphics.drawable.GradientDrawable).setColor(Theme.color(cornerDotColor(model.dot == true)))
    }

    private fun layout(width: Double): FlatSessionRowLayout {
        val m = model
        return FlatSessionRowLayout(
            FlatSessionRowLayout.Input(
                width = width,
                title = titleLabel.measurable,
                timestamp = timestampLabel.measurable,
                project = projectLabel.measurable,
                workspace = if (m?.workspace == null) null else workspaceLabel.measurable,
                branchIcon = branchIcon.intrinsicSize(),
                changes = if (m?.changes == null) null else changes.sizeThatFits(0.0, 0.0),
                draftIcon = if (m?.hasDraft == true) draftIcon.intrinsicSize() else null,
                showsDivider = m?.showsDivider ?: false,
                lines = lines,
            ),
        )
    }

    /** The lines of each text at the screen's text size, so the row grows with it as RN's does. */
    private val lines: FlatSessionRowLayout.Lines
        get() = FlatSessionRowLayout.Lines(
            title = titleLabelLine(TextStyles.sessionTitle),
            project = titleLabelLine(TextStyles.sessionProject),
            workspace = titleLabelLine(TextStyles.sessionWorkspace),
        )

    private fun titleLabelLine(style: TextStyle) = style.scaled(Theme.textScale).lineHeight ?: 0.0

    override fun sizeThatFits(width: Double, height: Double): Size = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val l = layout(width)
        avatar.setFrame(l.avatar)
        titleLabel.setFrame(l.title)
        timestampLabel.setFrame(l.timestamp)
        dot.setFrame(l.dot)
        projectLabel.setFrame(l.project)
        l.workspace?.let { workspaceLabel.setFrame(it) }
        l.branchIcon?.let { branchIcon.setFrame(it) }
        l.changes?.let { changes.setFrame(it) }
        l.draftIcon?.let { draftIcon.setFrame(it) }
        l.divider?.let { divider.setFrame(it) }
    }
}

/** "Today", "Yesterday", "3 days ago" over archived rows. */
class SessionDayHeaderCell(context: Context) : ManualLayout(context), ThemeAware {
    private val label = Label(context, TextStyles.sessionDayHeader, Palette.groupedSectionTitle, maxLines = 1).apply { ViewCompat.setAccessibilityHeading(this, true) }

    init {
        addView(label)
        applyTheme()
    }

    override fun applyTheme() = setBackgroundColor(Theme.color(Palette.flatListBackground))

    fun configure(title: String) {
        label.text = title
        requestLayout()
    }

    override fun sizeThatFits(width: Double, height: Double) = SessionDayHeaderLayout(width, label.measurable).size

    override fun layoutSubviews(width: Double, height: Double) = label.setFrame(SessionDayHeaderLayout(width, label.measurable).label)
}

/** "── Show archived ──": banded in the flat list, dividing the page in the project list. */
class ArchiveToggleCell(context: Context) : ManualLayout(context), ThemeAware {
    private val label = Label(context, TextStyles.archiveToggle, Palette.textSecondary, maxLines = 1)
    private val leading = View(context)
    private val trailing = View(context)

    var banded = true
        set(value) { if (field != value) { field = value; applyTheme(); requestLayout() } }

    init {
        addView(leading)
        addView(trailing)
        addView(label)
        label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = true
        isFocusable = true
        applyTheme()
    }

    override fun applyTheme() {
        setBackgroundColor(Theme.color(if (banded) Palette.flatListBackground else Palette.groupedBackground))
        leading.setBackgroundColor(Theme.color(Palette.divider))
        trailing.setBackgroundColor(Theme.color(Palette.divider))
    }

    fun configure(hidden: Boolean) {
        label.text = context.getString(if (hidden) R.string.sidebar_show_archived else R.string.sidebar_hide_archived)
        contentDescription = label.text
        requestLayout()
    }

    private fun layout(width: Double) = if (banded) {
        ArchiveToggleLayout(width, label.measurable)
    } else {
        ArchiveToggleLayout(width, label.measurable, ArchiveToggleLayout.unbanded.top, ArchiveToggleLayout.unbanded.bottom)
    }

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val l = layout(width)
        label.setFrame(l.label)
        leading.setFrame(l.leadingLine)
        trailing.setFrame(l.trailingLine)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alpha = if (pressed) 0.5f else 1f
    }
}

/**
 * A project-list cell whose press highlight fades rather than flips
 * (ProjectHomeList.tsx `RowPressable`): up in 70ms, gone over 240ms.
 */
abstract class ProjectListCell(context: Context) : ManualLayout(context), ThemeAware {
    private val highlight = View(context)

    init {
        addView(highlight)
        highlight.alpha = 0f
        applyTheme()
    }

    /** The pressable area: the whole cell, or the card under its margin. */
    open fun highlightFrame(width: Double, height: Double) = Rect(0.0, 0.0, width, height)

    override fun applyTheme() {
        setBackgroundColor(Theme.color(Palette.groupedBackground))
        highlight.setBackgroundColor(Theme.color(Palette.surfacePressed))
    }

    override fun layoutSubviews(width: Double, height: Double) {
        highlight.setFrame(highlightFrame(width, height))
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        highlight.animate().alpha(if (pressed) 1f else 0f).setDuration(if (pressed) 70 else 240).start()
    }
}

/** The blocked (orange, pulsing) or unread (blue) dot on a project row. */
class ActivityDotView(context: Context) : ManualLayout(context) {
    private val dot = StatusDotView(context)

    var activity: Pair<Boolean, Boolean> = false to false
        set(value) {
            field = value
            val (blocked, unread) = value
            dot.color = Theme.color(cornerDotColor(blocked))
            dot.isPulsing = blocked
            visibility = if (blocked || unread) VISIBLE else GONE
        }

    val shows get() = activity.first || activity.second

    init {
        addView(dot)
    }

    override fun layoutSubviews(width: Double, height: Double) = dot.setFrame(Rect(0.0, 0.0, width, height))
}

/** The computer heading its bots and projects. */
class ProjectMachineHeaderCell(context: Context) : ManualLayout(context), ThemeAware {
    private val icon = IconView(context, Icon.ionicons("desktop-outline"), 12.0, Palette.textSecondary)
    private val nameLabel = Label(context, TextStyles.projectMachine, Palette.textSecondary, maxLines = 1)
    private val rule = View(context)

    init {
        addView(icon)
        addView(nameLabel)
        addView(rule)
        ViewCompat.setAccessibilityHeading(this, true)
        applyTheme()
    }

    override fun applyTheme() {
        setBackgroundColor(Theme.color(Palette.groupedBackground))
        rule.setBackgroundColor(Theme.color(Palette.textSecondary))
    }

    fun configure(name: String) {
        nameLabel.text = name
        contentDescription = name
        requestLayout()
    }

    private fun layout(width: Double) = MachineHeaderLayout(width, 1.0 / Theme.density, icon.intrinsicSize(), nameLabel.measurable)

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        val l = layout(width)
        icon.setFrame(l.icon)
        nameLabel.setFrame(l.name)
        rule.setFrame(l.rule)
    }
}

/** "BOTS", "PROJECTS", or a day over the archive. */
class ProjectSectionCell(context: Context) : ManualLayout(context), ThemeAware {
    private val label = Label(context, TextStyles.projectSection, Palette.groupedSectionTitle, maxLines = 1).apply { ViewCompat.setAccessibilityHeading(this, true) }

    init {
        addView(label)
        applyTheme()
    }

    override fun applyTheme() = setBackgroundColor(Theme.color(Palette.groupedBackground))

    fun configure(text: String) {
        label.text = text
        requestLayout()
    }

    override fun sizeThatFits(width: Double, height: Double) = ProjectSectionLayout(width, label.measurable).size

    override fun layoutSubviews(width: Double, height: Double) = label.setFrame(ProjectSectionLayout(width, label.measurable).label)
}

/** A project's card: avatar, name, activity, line changes and the `+`. */
class ProjectCardCell(context: Context) : ProjectListCell(context) {
    private val avatar = AvatarView(context)
    private val stem = View(context)
    private val nameLabel = ShimmerLabel(context, TextStyles.projectName)
    private val dot = ActivityDotView(context)
    private val changes = GitChangesView(context)
    val addButton = HeaderIconButton(context, Icon.ionicons("add"), 18.0, "")
    var entry: ProjectHomeEntry? = null
        private set

    init {
        listOf(avatar, stem, nameLabel, dot, changes, addButton).forEach(::addView)
        addButton.iconView.tint = Palette.textSecondary
        isClickable = true
        isLongClickable = true
    }

    override fun applyTheme() {
        super.applyTheme()
        stem?.setBackgroundColor(Theme.color(Palette.textSecondary))
    }

    fun configure(entry: ProjectHomeEntry, avatar: AvatarView.Content?, changes: VisibleGitChanges?, canCreate: Boolean) {
        this.entry = entry
        this.avatar.visibility = if (avatar == null) GONE else VISIBLE
        this.avatar.content = avatar
        stem.visibility = if (entry.worktreeCount == 0) GONE else VISIBLE
        stem.setBackgroundColor(Theme.color(Palette.textSecondary))
        nameLabel.text = entry.name
        nameLabel.isShimmering = entry.working
        contentDescription = entry.name
        dot.activity = entry.blocked to entry.unread
        this.changes.changes = changes
        this.changes.visibility = if (changes == null) GONE else VISIBLE
        addButton.visibility = if (canCreate) VISIBLE else GONE
        addButton.contentDescription = context.getString(R.string.home_create_workspace_in, entry.name)
        requestLayout()
    }

    private fun layout(width: Double) = ProjectCardLayout(
        ProjectCardLayout.Input(
            width = width,
            hairline = 1.0 / Theme.density,
            name = nameLabel.measurable,
            showsDot = dot.shows,
            changes = if (changes.visibility == VISIBLE) Measurable { changes.sizeThatFits(it, 0.0) } else null,
            showsAdd = addButton.visibility == VISIBLE,
            showsTree = stem.visibility == VISIBLE,
        ),
    )

    override fun highlightFrame(width: Double, height: Double) =
        Rect(0.0, ProjectListMetrics.projectMarginTop, width, ProjectListMetrics.projectRowHeight)

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        super.layoutSubviews(width, height)
        val l = layout(width)
        avatar.setFrame(l.avatar)
        l.stem?.let { stem.setFrame(it) }
        nameLabel.setFrame(l.name)
        l.dot?.let { dot.setFrame(it) }
        l.changes?.let { changes.setFrame(it) }
        l.add?.let { addButton.setFrame(it) }
    }
}

/** A worktree on the tree line, or the row that ends it. */
class WorktreeCell(context: Context) : ProjectListCell(context) {
    private val trunk = View(context)
    private val elbow = View(context)
    private val chevron = IconView(context, Icon.ionicons("chevron-down"), 13.0, Palette.textSecondary)
    private val nameLabel = ShimmerLabel(context, TextStyles.worktreeName)
    private val toggleLabel = Label(context, TextStyles.worktreeToggle, Palette.textSecondary, maxLines = 1)
    private val dot = ActivityDotView(context)
    private val changes = GitChangesView(context)
    private var isToggle = false
    private var last = false

    init {
        listOf(trunk, elbow, chevron, nameLabel, toggleLabel, dot, changes).forEach(::addView)
        isClickable = true
    }

    override fun applyTheme() {
        super.applyTheme()
        trunk?.setBackgroundColor(Theme.color(Palette.textSecondary))
        elbow?.setBackgroundColor(Theme.color(Palette.textSecondary))
    }

    fun configure(worktree: ProjectWorktree, last: Boolean, changes: VisibleGitChanges?) {
        isToggle = false
        this.last = last
        nameLabel.text = worktree.workspaceName ?: worktree.projectName
        nameLabel.isShimmering = worktree.working
        dot.activity = worktree.blocked to worktree.unread
        this.changes.changes = changes
        this.changes.visibility = if (changes == null) GONE else VISIBLE
        contentDescription = nameLabel.text
        stateDescription = null
        applyKind()
    }

    fun configure(toggle: WorktreeToggle) {
        isToggle = true
        last = true
        chevron.icon = Icon.ionicons(if (toggle.expanded) "chevron-up" else "chevron-down")
        toggleLabel.text = if (toggle.expanded) {
            context.getString(R.string.sidebar_show_fewer_workspaces)
        } else {
            context.getString(R.string.sidebar_show_all_workspaces, toggle.worktreeCount)
        }
        dot.activity = toggle.blocked to toggle.unread
        changes.visibility = GONE
        contentDescription = toggleLabel.text
        stateDescription = if (toggle.expanded) "expanded" else "collapsed"
        applyKind()
    }

    private fun applyKind() {
        nameLabel.visibility = if (isToggle) GONE else VISIBLE
        chevron.visibility = if (isToggle) VISIBLE else GONE
        toggleLabel.visibility = if (isToggle) VISIBLE else GONE
        trunk.setBackgroundColor(Theme.color(Palette.textSecondary))
        elbow.setBackgroundColor(Theme.color(Palette.textSecondary))
        requestLayout()
    }

    private fun layout(width: Double) = WorktreeRowLayout(
        WorktreeRowLayout.Input(
            width = width,
            hairline = 1.0 / Theme.density,
            label = if (isToggle) toggleLabel.measurable else nameLabel.measurable,
            chevron = if (isToggle) chevron.intrinsicSize() else null,
            showsDot = dot.shows,
            changes = if (changes.visibility == VISIBLE) Measurable { changes.sizeThatFits(it, 0.0) } else null,
            last = last,
        ),
    )

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        super.layoutSubviews(width, height)
        val l = layout(width)
        trunk.setFrame(l.trunk)
        elbow.setFrame(l.elbow)
        l.chevron?.let { chevron.setFrame(it) }
        (if (isToggle) toggleLabel else nameLabel).setFrame(l.label)
        l.dot?.let { dot.setFrame(it) }
        l.changes?.let { changes.setFrame(it) }
    }
}

/** A bot's standing chat or an archived chat: avatar, name, and the time or a dot. */
class ProjectChatCell(context: Context) : ProjectListCell(context) {
    private val avatar = AvatarView(context)
    private val nameLabel = ShimmerLabel(context, TextStyles.projectName)
    private val timestampLabel = Label(context, TextStyles.projectTimestamp, Palette.textSecondary, maxLines = 1)
    private val dot = StatusDotView(context)
    private var showsDot = false

    init {
        listOf(avatar, nameLabel, timestampLabel, dot).forEach(::addView)
        isClickable = true
    }

    fun configure(model: SessionRowModel, archived: Boolean) {
        // A bot or retired chat gets its own face, without the harness badge.
        avatar.content = model.avatar.copy(harness = null)
        nameLabel.text = model.title
        nameLabel.isShimmering = model.shimmers
        timestampLabel.text = model.timestamp
        showsDot = model.dot != null
        timestampLabel.visibility = if (showsDot) GONE else VISIBLE
        dot.visibility = if (showsDot) VISIBLE else GONE
        dot.color = Theme.color(cornerDotColor(model.dot == true))
        // iOS fades the cell's content view. The cell's own alpha belongs to the
        // list's item animator, which sets it back to 1, so the content fades here.
        listOf(avatar, nameLabel, timestampLabel, dot).forEach { it.alpha = if (archived) 0.6f else 1f }
        contentDescription = model.title
        requestLayout()
    }

    private fun layout(width: Double): ProjectChatRowLayout {
        val size = ProjectListMetrics.chatDotSize
        return ProjectChatRowLayout(width, nameLabel.measurable, if (showsDot) Measurable.fixed(size, size) else timestampLabel.measurable)
    }

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        super.layoutSubviews(width, height)
        val l = layout(width)
        avatar.setFrame(l.avatar)
        nameLabel.setFrame(l.name)
        timestampLabel.setFrame(l.corner)
        dot.setFrame(l.corner)
    }
}

/** A row's corner as a [SessionRowModel] wants it. */
internal fun FlatRowTopRight.dot(): Boolean? = (this as? FlatRowTopRight.Dot)?.blocked
