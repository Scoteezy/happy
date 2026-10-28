package com.slopus.happy.layout

import kotlin.math.max
import kotlin.math.min

/**
 * The rows of the home list grouped by project (components/
 * ProjectHomeList.tsx; happy-ios `HomeProjectListLayout`). Every row
 * shares two leading columns:
 *
 *     |16| avatar lane 28 |12| name ………………… |8| trailing |16|
 *
 * The trailing column is a row of items 8 apart, flush right. A tree line
 * twice the hairline wide runs down the avatar lane from a project's avatar
 * through its worktrees; each worktree branches off it with an elbow.
 */
object ProjectListMetrics {
    const val paddingX = 16.0
    const val avatarSize = 28.0
    const val avatarGap = 12.0
    const val projectRowHeight = 52.0
    const val projectMarginTop = 8.0
    const val worktreeRowHeight = 48.0
    const val botRowHeight = 48.0
    const val trailingGap = 8.0
    const val trailingMarginLeft = 8.0
    /** The `+` that creates a workspace. */
    val addButton = Size(28, 32)
    const val dotSize = 7.0
    /** A bot's or an archived chat's corner dot. */
    const val chatDotSize = 10.0
    const val toggleIconGap = 5.0
    const val changesGap = 4.0

    /** Where every row's name starts. */
    val nameX get() = paddingX + avatarSize + avatarGap

    /** `TREE_LINE_WIDTH`: twice the hairline. */
    fun treeLineWidth(hairline: Double) = hairline * 2
}

/**
 * Lays out the trailing items of a row, right to left from [right], each
 * centred on [midY]; returns their frames in the order given and the
 * column's left edge.
 */
internal fun trailingFrames(sizes: List<Size>, right: Double, midY: Double, gap: Double): Pair<List<Rect>, Double> {
    var x = right
    val frames = MutableList(sizes.size) { Rect.Zero }
    for (index in sizes.indices.reversed()) {
        val size = sizes[index]
        x -= size.width
        frames[index] = Rect(x, midY - size.height / 2, size.width, size.height)
        if (index > 0) x -= gap
    }
    return frames to x
}

/** Content measured at an unbounded width, as `size(fitting: .greatestFiniteMagnitude)`. */
private fun Measurable.unbounded() = size(Double.MAX_VALUE)

/**
 * A project's card: avatar, name, then an activity dot, its line changes and
 * the `+`. The card sits 8 below the row above it, and the tree stem drops
 * from under the avatar to the cell's foot.
 */
data class ProjectCardLayout(
    val size: Size,
    val avatar: Rect,
    val stem: Rect?,
    val name: Rect,
    val dot: Rect?,
    val changes: Rect?,
    val add: Rect?,
) {
    class Input(
        val width: Double,
        val hairline: Double,
        val name: Measurable,
        val showsDot: Boolean,
        val changes: Measurable?,
        val showsAdd: Boolean,
        val showsTree: Boolean,
    )

    companion object {
        operator fun invoke(input: Input): ProjectCardLayout {
            val m = ProjectListMetrics
            val width = max(0.0, input.width)
            val top = m.projectMarginTop
            val height = top + m.projectRowHeight
            val midY = top + m.projectRowHeight / 2
            val avatar = Rect(m.paddingX, midY - m.avatarSize / 2, m.avatarSize, m.avatarSize)
            val line = m.treeLineWidth(input.hairline)
            val stemTop = top + (m.projectRowHeight + m.avatarSize) / 2
            val stem = if (input.showsTree) Rect(m.paddingX + (m.avatarSize - line) / 2, stemTop, line, height - stemTop) else null

            val sizes = mutableListOf<Size>()
            if (input.showsDot) sizes.add(Size(m.dotSize, m.dotSize))
            val changesSize = input.changes?.unbounded()
            if (changesSize != null) sizes.add(changesSize)
            if (input.showsAdd) sizes.add(m.addButton)
            val (frames, minX) = trailingFrames(sizes, width - m.paddingX, midY, m.trailingGap)
            val queue = ArrayDeque(frames)
            val dot = if (input.showsDot) queue.removeFirst() else null
            val changes = if (changesSize != null) queue.removeFirst() else null
            val add = if (input.showsAdd) queue.removeFirst() else null

            val nameRight = if (sizes.isEmpty()) width - m.paddingX else minX - m.trailingMarginLeft
            val nameWidth = max(0.0, nameRight - m.nameX)
            val nameHeight = input.name.height(nameWidth)
            val name = Rect(m.nameX, midY - nameHeight / 2, nameWidth, nameHeight)
            return ProjectCardLayout(Size(width, height), avatar, stem, name, dot, changes, add)
        }
    }
}

/**
 * A worktree hanging off its project's tree line, or the toggle row that
 * ends the line. The last one stops the trunk at its elbow.
 */
data class WorktreeRowLayout(
    val size: Size,
    val trunk: Rect,
    val elbow: Rect,
    val chevron: Rect?,
    val label: Rect,
    val dot: Rect?,
    val changes: Rect?,
) {
    class Input(
        val width: Double,
        val hairline: Double,
        /** The name, or the toggle's label. */
        val label: Measurable,
        /** The toggle's chevron box, ahead of its label. */
        val chevron: Size? = null,
        val showsDot: Boolean,
        val changes: Measurable?,
        val last: Boolean,
    )

    companion object {
        operator fun invoke(input: Input): WorktreeRowLayout {
            val m = ProjectListMetrics
            val width = max(0.0, input.width)
            val height = m.worktreeRowHeight
            val midY = height / 2
            val line = m.treeLineWidth(input.hairline)
            val trunk = Rect(m.paddingX + (m.avatarSize - line) / 2, 0.0, line, if (input.last) height / 2 else height)
            val elbow = Rect(m.paddingX + m.avatarSize / 2, midY - line / 2, m.avatarSize / 2, line)

            val sizes = mutableListOf<Size>()
            if (input.showsDot) sizes.add(Size(m.dotSize, m.dotSize))
            val changesSize = input.changes?.unbounded()
            if (changesSize != null) sizes.add(changesSize)
            val (frames, minX) = trailingFrames(sizes, width - m.paddingX, midY, m.trailingGap)
            val queue = ArrayDeque(frames)
            val dot = if (input.showsDot) queue.removeFirst() else null
            val changes = if (changesSize != null) queue.removeFirst() else null
            // An empty trailing column still takes its margin.
            val laneRight = minX - m.trailingMarginLeft
            var x = m.nameX
            val chevron = input.chevron?.let { box ->
                Rect(x, midY - box.height / 2, box.width, box.height).also { x += box.width + m.toggleIconGap }
            }
            val lane = max(0.0, laneRight - x)
            val text = input.label.size(lane)
            val label = Rect(x, midY - text.height / 2, if (input.chevron == null) lane else min(text.width, lane), text.height)
            return WorktreeRowLayout(Size(width, height), trunk, elbow, chevron, label, dot, changes)
        }
    }
}

/** A bot's standing chat, or a chat in the archive: avatar, name, and the time or a status dot. */
data class ProjectChatRowLayout(
    val size: Size,
    val avatar: Rect,
    val name: Rect,
    /** The timestamp, or the dot's box when it shows one. */
    val corner: Rect,
) {
    companion object {
        operator fun invoke(width: Double, name: Measurable, corner: Measurable): ProjectChatRowLayout {
            val m = ProjectListMetrics
            val w = max(0.0, width)
            val height = m.botRowHeight
            val midY = height / 2
            val avatar = Rect(m.paddingX, midY - m.avatarSize / 2, m.avatarSize, m.avatarSize)
            val cornerMax = max(0.0, w - m.nameX - m.paddingX - m.trailingMarginLeft)
            val cornerSize = corner.size(cornerMax)
            val cornerWidth = min(cornerSize.width, cornerMax)
            val cornerFrame = Rect(w - m.paddingX - cornerWidth, midY - cornerSize.height / 2, cornerWidth, cornerSize.height)
            val nameWidth = max(0.0, cornerFrame.minX - m.trailingMarginLeft - m.nameX)
            val nameHeight = name.height(nameWidth)
            return ProjectChatRowLayout(Size(w, height), avatar, Rect(m.nameX, midY - nameHeight / 2, nameWidth, nameHeight), cornerFrame)
        }
    }
}

/** "BOTS", "PROJECTS", or a day over the archive (ProjectHomeList.tsx `section`). */
data class ProjectSectionLayout(val size: Size, val label: Rect) {
    object Metrics {
        const val paddingLeft = 16.0
        const val paddingRight = 12.0
        const val paddingTop = 18.0
        const val paddingBottom = 6.0
    }

    companion object {
        operator fun invoke(width: Double, label: Measurable): ProjectSectionLayout {
            val m = Metrics
            val w = max(0.0, width)
            val labelWidth = max(0.0, w - m.paddingLeft - m.paddingRight)
            val height = label.height(labelWidth)
            return ProjectSectionLayout(Size(w, m.paddingTop + height + m.paddingBottom), Rect(m.paddingLeft, m.paddingTop, labelWidth, height))
        }
    }
}

/** The computer heading everything under it: a desktop glyph, its name (at most 60% of the row) and a rule. */
data class MachineHeaderLayout(val size: Size, val icon: Rect, val name: Rect, val rule: Rect) {
    object Metrics {
        const val paddingX = 16.0
        const val paddingTop = 14.0
        const val paddingBottom = 2.0
        const val gap = 6.0
        const val nameMaxFraction = 0.6
    }

    companion object {
        operator fun invoke(width: Double, hairline: Double, icon: Size, name: Measurable): MachineHeaderLayout {
            val m = Metrics
            val w = max(0.0, width)
            val inner = max(0.0, w - 2 * m.paddingX)
            val nameMax = inner * m.nameMaxFraction
            val fitted = name.size(nameMax)
            val text = Size(min(fitted.width, nameMax), fitted.height)
            val line = ProjectListMetrics.treeLineWidth(hairline)
            val rowHeight = maxOf(icon.height, text.height, line)
            val midY = m.paddingTop + rowHeight / 2
            val iconFrame = Rect(m.paddingX, midY - icon.height / 2, icon.width, icon.height)
            val nameFrame = Rect(iconFrame.maxX + m.gap, midY - text.height / 2, text.width, text.height)
            val ruleX = nameFrame.maxX + m.gap
            val rule = Rect(ruleX, midY - line / 2, max(0.0, w - m.paddingX - ruleX), line)
            return MachineHeaderLayout(Size(w, m.paddingTop + rowHeight + m.paddingBottom), iconFrame, nameFrame, rule)
        }
    }
}

/** "≈ +12 -3": a checkout's line changes, 4 apart, on one centre line. */
data class GitChangesLayout(val size: Size, val approximate: Rect?, val added: Rect?, val removed: Rect?) {
    companion object {
        operator fun invoke(approximate: Size?, added: Size?, removed: Size?): GitChangesLayout {
            val parts = listOf(approximate, added, removed)
            val height = parts.mapNotNull { it?.height }.maxOrNull() ?: 0.0
            var x = 0.0
            val frames = parts.map { part ->
                if (part == null) return@map null
                if (x > 0) x += ProjectListMetrics.changesGap
                Rect(x, (height - part.height) / 2, part.width, part.height).also { x += part.width }
            }
            return GitChangesLayout(Size(x, height), frames[0], frames[1], frames[2])
        }
    }
}

/** HomeListHeader.tsx: the notices above the rows, stacked full width. */
data class HomeListHeaderLayout(val size: Size, val banners: List<Rect>) {
    companion object {
        operator fun invoke(width: Double, banners: List<Measurable>): HomeListHeaderLayout {
            val w = max(0.0, width)
            var y = 0.0
            val frames = banners.map { banner ->
                val height = banner.height(w)
                Rect(0.0, y, w, height).also { y += height }
            }
            return HomeListHeaderLayout(Size(w, y), frames)
        }
    }
}
