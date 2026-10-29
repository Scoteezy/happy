package com.slopus.happy.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.slopus.happy.R
import com.slopus.happy.home.HomeRow
import com.slopus.happy.home.formatSessionListTimestamp
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.HomeContentInsets
import com.slopus.happy.layout.HomeListHeaderLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.model.ProjectHomeEntry
import com.slopus.happy.model.ProjectHomeRow
import com.slopus.happy.model.ProjectHomeSection
import com.slopus.happy.model.SessionListGrouping
import com.slopus.happy.model.SessionRowData
import com.slopus.happy.model.projectGitChanges
import com.slopus.happy.model.resolveAvatarHarness
import com.slopus.happy.model.resolveFlatRowPresentation
import com.slopus.happy.model.visibleGitChanges
import com.slopus.happy.model.workspaceOrigin
import com.slopus.happy.ui.components.AvatarStyle
import com.slopus.happy.ui.components.AvatarView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.components.ItemIcon
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.components.SwipeActions
import com.slopus.happy.ui.components.measurable
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame
import kotlin.math.roundToInt

/** What the list asks of home. */
interface HomeListActions {
    fun openSession(id: String)
    fun toggleArchive(hidden: Boolean)
    fun toggleExpanded(projectId: String)
    fun holdSession(id: String)
    fun archiveSession(id: String)
    fun holdProject(entry: ProjectHomeEntry)
    fun createWorkspace(entry: ProjectHomeEntry)
    fun openTroubleshoot()
    fun openWhatsNew()
    fun openStore()
}

/** HomeListHeader.tsx's notices: the offline plaque, then the store update or What's new. */
data class HomeBanners(val offlineTitle: String? = null, val nativeUpdate: Boolean = false, val whatsNew: Boolean = false) {
    val isEmpty get() = offlineTitle == null && !nativeUpdate && !whatsNew
}

/** The settings every row's avatar reads. */
data class AvatarSettings(val style: AvatarStyle = AvatarStyle.Brutalist, val monochrome: Boolean = false, val showFlavorIcons: Boolean = false)

/**
 * The home session list in a RecyclerView, in either of happy-app's layouts
 * (SessionsListWrapper.tsx; happy-ios `SessionListViewController`): flat
 * (live sessions by activity, the archive toggle, the archive by day) or
 * grouped by project (each computer, its bots and projects with their
 * worktrees, then the archive). The notices sit above the rows. A tap opens
 * the chat; a long press opens the session's actions. The list runs under
 * the floating home header, padded by [contentInsets]. Rows are diffed off
 * the main thread (AsyncListDiffer), and only the ones that changed are
 * bound again, as iOS applies its changesets.
 */
@SuppressLint("ViewConstructor")
class SessionListView(context: Context, private val actions: HomeListActions) : RecyclerView(context), ThemeAware {
    private val adapter = Adapter()
    private var grouping = SessionListGrouping.Flat
    private var didScrollToTop = false

    var avatarSettings = AvatarSettings()
        set(value) {
            if (field == value) return
            field = value
            @Suppress("NotifyDataSetChanged")
            adapter.notifyDataSetChanged()
        }

    var banners = HomeBanners()
        set(value) {
            if (field == value) return
            field = value
            submit(rows, grouping)
        }

    var contentInsets: HomeContentInsets? = null
        set(value) {
            if (field == value) return
            field = value
            val insets = value ?: return
            setPadding(0, dp(insets.contentTop.roundToInt()), 0, dp(insets.listBottom.roundToInt()))
            if (!didScrollToTop) {
                didScrollToTop = true
                scrollToPosition(0)
            }
        }

    private var rows: List<HomeRow> = emptyList()

    /** In the tablet sidebar, the open chat's row stays selected (SessionsList.tsx `selectedSessionId`); flat rows only. */
    var selectedSessionId: String? = null
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            for (id in listOfNotNull(previous, value)) {
                val position = adapter.currentList.indexOfFirst { ((it as? Item.Row)?.row as? HomeRow.Chat)?.row?.session?.id == id }
                if (position >= 0) adapter.notifyItemChanged(position)
            }
        }

    /** FlatSessionRow.tsx's swipe: "Archive Session", on a live flat row only (happy-ios `trailingSwipeActionsConfigurationForRowAt`). */
    private val swipe = SwipeActions(this) { holder ->
        val position = holder.bindingAdapterPosition
        val row = (adapter.itemAt(position) as? Item.Row)?.row as? HomeRow.Chat
        row?.takeIf { !it.archived }?.let { chat ->
            SwipeActions.Action(context.getString(R.string.session_info_archive_session), Icon.ionicons("archive-outline")) {
                actions.archiveSession(chat.row.session.id)
            }
        }
    }

    init {
        layoutManager = LinearLayoutManager(context)
        setAdapter(adapter)
        clipToPadding = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        isVerticalScrollBarEnabled = true
        // iOS fades inserts and deletes; moves slide.
        itemAnimator = DefaultItemAnimator().apply { supportsChangeAnimations = false }
        applyTheme()
    }

    override fun applyTheme() {
        setBackgroundColor(Theme.color(if (grouping == SessionListGrouping.Project) Palette.groupedBackground else Palette.flatListBackground))
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
    }

    fun submit(rows: List<HomeRow>, grouping: SessionListGrouping) {
        this.rows = rows
        if (this.grouping != grouping) {
            this.grouping = grouping
            applyTheme()
        }
        val items = ArrayList<Item>(rows.size + 1)
        if (!banners.isEmpty) items.add(Item.Banners(banners))
        rows.mapTo(items) { Item.Row(it) }
        adapter.submitList(items)
    }

    private sealed interface Item {
        val key: String

        data class Banners(val banners: HomeBanners) : Item {
            override val key get() = "banners"
        }

        data class Row(val row: HomeRow) : Item {
            override val key get() = row.key
        }
    }

    private class Holder(view: View) : ViewHolder(view)

    private inner class Adapter : ListAdapter<Item, Holder>(object : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(oldItem: Item, newItem: Item) = oldItem.key == newItem.key && oldItem::class == newItem::class
        override fun areContentsTheSame(oldItem: Item, newItem: Item) = oldItem == newItem
    }) {
        fun itemAt(position: Int): Item? = if (position in 0 until itemCount) getItem(position) else null

        override fun getItemViewType(position: Int): Int = when (val item = getItem(position)) {
            is Item.Banners -> TYPE_BANNERS
            is Item.Row -> when (val row = item.row) {
                is HomeRow.Chat -> TYPE_SESSION
                is HomeRow.ArchiveToggle -> TYPE_ARCHIVE
                is HomeRow.DayHeader -> TYPE_DAY
                is HomeRow.Project -> when (row.row) {
                    is ProjectHomeRow.Section, is ProjectHomeRow.ArchiveHeader -> TYPE_SECTION
                    is ProjectHomeRow.MachineHeader -> TYPE_MACHINE
                    is ProjectHomeRow.Bot, is ProjectHomeRow.Task, is ProjectHomeRow.Archived -> TYPE_CHAT
                    is ProjectHomeRow.ProjectCard -> TYPE_PROJECT
                    is ProjectHomeRow.Worktree, is ProjectHomeRow.Toggle -> TYPE_WORKTREE
                    is ProjectHomeRow.ArchiveToggle -> TYPE_ARCHIVE
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view: View = when (viewType) {
                TYPE_BANNERS -> HomeListHeaderView(context, actions)
                TYPE_SESSION -> SessionRowCell(context)
                TYPE_ARCHIVE -> ArchiveToggleCell(context)
                TYPE_DAY -> SessionDayHeaderCell(context)
                TYPE_SECTION -> ProjectSectionCell(context)
                TYPE_MACHINE -> ProjectMachineHeaderCell(context)
                TYPE_CHAT -> ProjectChatCell(context)
                TYPE_PROJECT -> ProjectCardCell(context)
                else -> WorktreeCell(context)
            }
            view.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val view = holder.itemView
            if (view is ThemeAware) view.applyTheme()
            when (val item = getItem(position)) {
                is Item.Banners -> (view as HomeListHeaderView).content = item.banners
                is Item.Row -> bind(view, item.row)
            }
        }

        private fun bind(view: View, row: HomeRow) {
            when (row) {
                is HomeRow.Chat -> {
                    val cell = view as SessionRowCell
                    val faded = row.archived || row.row.session.machineOffline
                    cell.configure(sessionModel(row.row.session, faded, row.row.projectName, row.row.workspaceName, row.showBorder))
                    cell.isSelected = row.row.session.id == selectedSessionId
                    cell.setOnClickListener { actions.openSession(row.row.session.id) }
                    cell.setOnLongClickListener { actions.holdSession(row.row.session.id); true }
                    // The swipe, for TalkBack (VoiceOver reads a row's swipe actions as its custom actions).
                    ViewCompat.removeAccessibilityAction(cell, cell.archiveActionId)
                    cell.archiveActionId = View.NO_ID
                    if (!row.archived) {
                        cell.archiveActionId = ViewCompat.addAccessibilityAction(cell, context.getString(R.string.session_info_archive_session)) { _, _ ->
                            actions.archiveSession(row.row.session.id)
                            true
                        }
                    }
                }
                is HomeRow.ArchiveToggle -> archive(view as ArchiveToggleCell, row.hidden, banded = true)
                is HomeRow.DayHeader -> (view as SessionDayHeaderCell).configure(context.dayTitle(row.title))
                is HomeRow.Project -> bindProject(view, row.row)
            }
        }

        private fun archive(cell: ArchiveToggleCell, hidden: Boolean, banded: Boolean) {
            cell.banded = banded
            cell.configure(hidden)
            cell.setOnClickListener { actions.toggleArchive(hidden) }
        }

        private fun bindProject(view: View, row: ProjectHomeRow) {
            when (row) {
                is ProjectHomeRow.Section -> (view as ProjectSectionCell).configure(
                    context.getString(when (row.section) {
                        ProjectHomeSection.Bots -> R.string.sidebar_bots
                        ProjectHomeSection.Tasks -> R.string.home_info_tasks
                        ProjectHomeSection.Projects -> R.string.sidebar_projects
                    }),
                )
                is ProjectHomeRow.ArchiveHeader -> (view as ProjectSectionCell).configure(context.dayTitle(row.title))
                is ProjectHomeRow.MachineHeader -> (view as ProjectMachineHeaderCell).configure(
                    if (row.machineId == null) "<${context.getString(R.string.status_unknown)}>" else row.machineName,
                )
                is ProjectHomeRow.Bot -> chat(view as ProjectChatCell, row.session, archived = false)
                is ProjectHomeRow.Task -> chat(view as ProjectChatCell, row.session, archived = false)
                is ProjectHomeRow.Archived -> chat(view as ProjectChatCell, row.session, archived = true)
                is ProjectHomeRow.ProjectCard -> {
                    val cell = view as ProjectCardCell
                    val entry = row.project
                    val origin = (entry.session ?: entry.avatarSession)?.let(::workspaceOrigin)
                    val avatar = entry.avatarSession?.let { AvatarView.Content(it.avatarId, avatarSettings.style, avatarSettings.monochrome, image = it.projectAvatarUri) }
                    cell.configure(entry, avatar, (entry.session ?: entry.avatarSession)?.let(::projectGitChanges), canCreate = origin != null)
                    cell.setOnClickListener { entry.session?.let { actions.openSession(it.id) } }
                    if (origin != null) {
                        cell.setOnLongClickListener { actions.holdProject(entry); true }
                    } else {
                        cell.setOnLongClickListener(null)
                        cell.isLongClickable = false
                    }
                    cell.addButton.setOnClickListener { actions.createWorkspace(entry) }
                }
                is ProjectHomeRow.Worktree -> {
                    val cell = view as WorktreeCell
                    cell.configure(row.worktree, row.last, projectGitChanges(row.worktree.session))
                    cell.setOnClickListener { actions.openSession(row.worktree.session.id) }
                }
                is ProjectHomeRow.Toggle -> {
                    val cell = view as WorktreeCell
                    cell.configure(row.toggle)
                    cell.setOnClickListener { actions.toggleExpanded(row.toggle.projectId) }
                }
                is ProjectHomeRow.ArchiveToggle -> archive(view as ArchiveToggleCell, row.hidden, banded = false)
            }
        }

        private fun chat(cell: ProjectChatCell, session: SessionRowData, archived: Boolean) {
            val model = sessionModel(session, archived || session.machineOffline, "", null, false)
            // The cell's 0.6 fades an archived chat; its face keeps its colour unless the settings say otherwise.
            cell.configure(model.copy(avatar = model.avatar.copy(monochrome = avatarSettings.monochrome)), archived)
            cell.setOnClickListener { actions.openSession(session.id) }
        }
    }

    private fun sessionModel(session: SessionRowData, faded: Boolean, project: String, workspace: String?, divider: Boolean): SessionRowModel {
        val presentation = resolveFlatRowPresentation(session.state, session.hasUnread, faded)
        val harness = if (avatarSettings.showFlavorIcons) resolveAvatarHarness(session.flavor, session.clientId) else null
        return SessionRowModel(
            id = session.id,
            title = session.name ?: context.getString(R.string.session_new_chat),
            timestamp = formatSessionListTimestamp(session.lastActivityAt),
            dot = presentation.topRight.dot(),
            shimmers = presentation.shimmerTitle,
            faded = faded,
            project = project,
            workspace = workspace,
            changes = visibleGitChanges(session),
            hasDraft = session.hasDraft,
            showsDivider = divider,
            avatar = AvatarView.Content(
                seed = session.avatarId,
                style = avatarSettings.style,
                monochrome = avatarSettings.monochrome || faded,
                isBot = session.botId != null,
                isTask = session.isTask,
                image = session.avatarUri,
                harness = harness,
            ),
            state = session.state,
        )
    }

    private companion object {
        const val TYPE_BANNERS = 0
        const val TYPE_SESSION = 1
        const val TYPE_ARCHIVE = 2
        const val TYPE_DAY = 3
        const val TYPE_SECTION = 4
        const val TYPE_MACHINE = 5
        const val TYPE_CHAT = 6
        const val TYPE_PROJECT = 7
        const val TYPE_WORKTREE = 8
    }
}

/**
 * HomeListHeader.tsx on a phone (happy-ios `HomeListHeaderView`): the
 * offline plaque while every computer is offline, then the update banner.
 * Each is an ItemGroup with 4 above and 16 below.
 */
@SuppressLint("ViewConstructor")
class HomeListHeaderView(context: Context, private val actions: HomeListActions) : ManualLayout(context) {
    private var groups: List<ItemGroupView> = emptyList()

    var content = HomeBanners()
        set(value) { if (field != value) { field = value; rebuild() } }

    private fun rebuild() {
        groups.forEach(::removeView)
        val rows = buildList {
            content.offlineTitle?.let { title ->
                add(ItemRowView(context, title, context.getString(R.string.troubleshoot_banner_subtitle), icon = ItemIcon.Glyph(Icon.ionicons("warning-outline"), Palette.warningText, 28.0)) { actions.openTroubleshoot() })
            }
            if (content.nativeUpdate) {
                add(ItemRowView(context, context.getString(R.string.update_banner_native_update_available), context.getString(R.string.update_banner_tap_to_update_play_store), icon = ItemIcon.Glyph(Icon.ionicons("download-outline"), Palette.success, 28.0)) { actions.openStore() })
            } else if (content.whatsNew) {
                add(ItemRowView(context, context.getString(R.string.update_banner_whats_new), context.getString(R.string.update_banner_see_latest), icon = ItemIcon.Glyph(Icon.ionicons("sparkles-outline"), Palette.text, 28.0)) { actions.openWhatsNew() })
            }
        }
        groups = rows.map { row ->
            ItemGroupView(context, rows = listOf(row)).apply {
                untitledPaddingTop = 4.0
                paddingBottom = 16.0
            }
        }
        groups.forEach(::addView)
        requestLayout()
    }

    private fun layout(width: Double) = HomeListHeaderLayout(width, groups.map { it.measurable })

    override fun sizeThatFits(width: Double, height: Double) = layout(width).size

    override fun layoutSubviews(width: Double, height: Double) {
        groups.zip(layout(width).banners).forEach { (group, frame) -> group.setFrame(frame) }
    }
}
