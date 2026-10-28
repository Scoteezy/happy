package com.slopus.happy.layout

/**
 * Home list rows' heights without a view (happy-ios `HomeRowHeights`). A
 * flat session row at the designed text size, a project card, a worktree row
 * and a bot or archived chat row are one fixed height at any width; the
 * headings are one line of their style. The list takes these numbers
 * instead of measuring a cell.
 */
object HomeRowHeights {
    private val empty get() = Measurable.fixed(Size.Zero)

    fun flatSession(width: Double): Double =
        FlatSessionRowLayout(FlatSessionRowLayout.Input(width, empty, empty, empty, null, showsDivider = false)).size.height

    fun projectCard(width: Double): Double =
        ProjectCardLayout(ProjectCardLayout.Input(width, 0.0, empty, showsDot = false, changes = null, showsAdd = false, showsTree = false)).size.height

    fun worktree(width: Double): Double =
        WorktreeRowLayout(WorktreeRowLayout.Input(width, 0.0, empty, showsDot = false, changes = null, last = false)).size.height

    fun projectChat(width: Double): Double = ProjectChatRowLayout(width, empty, empty).size.height

    /** A row's height from its own layout. Each heading is one line, or nothing when its text is empty. */
    fun height(kind: HomeRowKind, width: Double, metrics: HomeRowMetrics): Double {
        fun line(text: String, height: Double) = Measurable.fixed(0.0, if (text.isEmpty()) 0.0 else height)
        return when (kind) {
            is HomeRowKind.FlatSession -> FlatSessionRowLayout.height(metrics.sessionLines, kind.workspace, kind.changes, metrics.branchIcon.height)
            is HomeRowKind.DayHeader -> SessionDayHeaderLayout(width, line(kind.label, metrics.dayHeaderLine)).size.height
            is HomeRowKind.ArchiveToggle -> {
                val text = line(kind.label, metrics.archiveToggleLine)
                if (kind.banded) {
                    ArchiveToggleLayout(width, text).size.height
                } else {
                    ArchiveToggleLayout(width, text, ArchiveToggleLayout.unbanded.top, ArchiveToggleLayout.unbanded.bottom).size.height
                }
            }
            is HomeRowKind.MachineHeader -> MachineHeaderLayout(width, metrics.hairline, metrics.machineIcon, line(kind.name, metrics.machineLine)).size.height
            is HomeRowKind.Section -> ProjectSectionLayout(width, line(kind.label, metrics.sectionLine)).size.height
            HomeRowKind.ProjectCard -> projectCard(width)
            HomeRowKind.Worktree -> worktree(width)
            HomeRowKind.Chat -> projectChat(width)
        }
    }
}

/** A home list row, as much as its height needs. */
sealed interface HomeRowKind {
    data class FlatSession(val workspace: Boolean, val changes: Boolean) : HomeRowKind
    data class DayHeader(val label: String) : HomeRowKind
    data class ArchiveToggle(val label: String, val banded: Boolean) : HomeRowKind
    data class MachineHeader(val name: String) : HomeRowKind
    data class Section(val label: String) : HomeRowKind
    data object ProjectCard : HomeRowKind
    data object Worktree : HomeRowKind
    data object Chat : HomeRowKind
}

/** What the rows' heights are made of, as the screen renders them. */
data class HomeRowMetrics(
    var dayHeaderLine: Double,
    var archiveToggleLine: Double,
    var machineLine: Double,
    var sectionLine: Double,
    var machineIcon: Size,
    var hairline: Double,
    var sessionLines: FlatSessionRowLayout.Lines = FlatSessionRowLayout.Lines.designed,
    var branchIcon: Size = Size(FlatSessionRowLayout.Metrics.branchIconSize, FlatSessionRowLayout.Metrics.branchIconSize),
)

/**
 * HomeListHeader.tsx's notices, sized without views: each is an ItemGroup (4
 * above, 16 below) around one Item with an icon, a one-line title, a
 * subtitle and the chevron.
 */
object HomeBannerHeights {
    object Metrics {
        const val groupPaddingTop = 4.0
        const val groupPaddingBottom = 16.0
    }

    class Banner(val title: Measurable, val subtitle: Measurable)

    /** One notice, group padding included. */
    fun banner(banner: Banner, width: Double, maxWidth: Double?, chevron: Size): Double {
        val row = Measurable { rowWidth ->
            ItemRowLayout(ItemRowLayout.Input(width = rowWidth, hasIcon = true, title = banner.title, subtitle = banner.subtitle, accessory = chevron)).size
        }
        return ItemGroupLayout(
            ItemGroupLayout.Input(
                width = width,
                rows = listOf(row),
                maxWidth = maxWidth,
                untitledPaddingTop = Metrics.groupPaddingTop,
                paddingBottom = Metrics.groupPaddingBottom,
            ),
        ).size.height
    }

    /** The notices stacked; 0 for none. */
    fun header(banners: List<Banner>, width: Double, maxWidth: Double?, chevron: Size): Double =
        HomeListHeaderLayout(
            width,
            banners.map { b -> Measurable { w -> Size(w, banner(b, w, maxWidth, chevron)) } },
        ).size.height
}
