package com.slopus.happy.layout

import kotlin.math.max
import kotlin.math.min

/**
 * One session in the flat home list (components/FlatSessionRow.tsx;
 * happy-ios `FlatSessionRowLayout`):
 *
 *     |16| avatar 60 |12| title ……………………………… |8| 12:55 PM |16|
 *     |          (centred) | project
 *     |                    | workspace ⑂ ……………………… ✎ |8| ≈ +12 -3 |16|
 *     ──────────── hairline from x 88 to the right edge ────────────
 *
 * At the designed text size the row is 10 + 22 + 20 + 1 + 18 + 10 = 81
 * tall. The title's row is its line but never less than the corner's 22;
 * the project is its line; the workspace's row is its line but never less
 * than 18. Line counts end the workspace's row, a draft glyph ahead of
 * them. The avatar is centred in the row, and the text column too. The
 * corner slot is at least 56 wide and sized by the timestamp, which stays
 * measured while a dot covers it, so the title never jumps.
 */
data class FlatSessionRowLayout(
    val size: Size,
    val avatar: Rect,
    val title: Rect,
    val timestamp: Rect,
    /** The dot shares the timestamp's right edge and is centred on its line. */
    val dot: Rect,
    val project: Rect,
    val workspace: Rect?,
    val branchIcon: Rect?,
    val changes: Rect?,
    val draftIcon: Rect?,
    val divider: Rect?,
) {
    object Metrics {
        const val paddingLeft = 16.0
        const val paddingRight = 16.0
        const val paddingVertical = 10.0
        const val avatarSize = 60.0
        const val avatarGap = 12.0
        const val titleRowHeight = 22.0
        const val cornerMinWidth = 56.0
        const val cornerMarginLeft = 8.0
        const val dotSize = 20.0
        const val projectHeight = 20.0
        const val workspaceMarginTop = 1.0
        const val workspaceMinHeight = 18.0
        const val workspaceIconGap = 4.0
        const val branchIconSize = 13.0
        const val draftIconSize = 13.0
        const val changesPaddingLeft = 8.0
        /** One line of GitLineChanges' 11s, which set no line height: Plex's own 1.3 em. */
        const val changesLine = 14.3
        const val dividerHeight = 0.33

        /** Where the text column and the hairline start. */
        val contentX get() = paddingLeft + avatarSize + avatarGap
    }

    /** One line of each of the texts at the screen's text size. */
    data class Lines(
        val title: Double,
        val project: Double,
        val workspace: Double,
        val changes: Double = Metrics.changesLine,
    ) {
        constructor(title: Number, project: Number, workspace: Number) :
            this(title.toDouble(), project.toDouble(), workspace.toDouble())

        companion object {
            val designed = Lines(Metrics.titleRowHeight, Metrics.projectHeight, Metrics.workspaceMinHeight, Metrics.changesLine)
        }
    }

    class Input(
        val width: Double,
        val title: Measurable,
        val timestamp: Measurable,
        val project: Measurable,
        /** null in a project's primary checkout. */
        val workspace: Measurable?,
        val branchIcon: Size = Size(Metrics.branchIconSize, Metrics.branchIconSize),
        /** The line counts as drawn; null without any. */
        val changes: Size? = null,
        /** The draft glyph's box; null without a draft. */
        val draftIcon: Size? = null,
        val showsDivider: Boolean,
        val lines: Lines = Lines.designed,
    )

    companion object {
        fun height(
            lines: Lines,
            workspace: Boolean,
            changes: Boolean = false,
            branchIcon: Double = Metrics.branchIconSize,
            draftIcon: Double = 0.0,
        ): Double = Metrics.paddingVertical * 2 + max(Metrics.avatarSize, contentHeight(lines, workspace, changes, branchIcon, draftIcon))

        private fun contentHeight(lines: Lines, workspace: Boolean, changes: Boolean, branchIcon: Double, draftIcon: Double): Double =
            max(Metrics.titleRowHeight, lines.title) + lines.project + Metrics.workspaceMarginTop +
                workspaceRowHeight(lines, workspace, changes, branchIcon, draftIcon)

        private fun workspaceRowHeight(lines: Lines, workspace: Boolean, changes: Boolean, branchIcon: Double, draftIcon: Double): Double =
            maxOf(
                Metrics.workspaceMinHeight,
                if (workspace) max(lines.workspace, branchIcon) else 0.0,
                if (changes) lines.changes else 0.0,
                draftIcon,
            )

        operator fun invoke(input: Input): FlatSessionRowLayout {
            val m = Metrics
            val width = max(0.0, input.width)
            val x = m.contentX
            val right = max(x, width - m.paddingRight)
            val contentWidth = right - x
            val lines = input.lines
            val hasWorkspace = input.workspace != null
            val hasChanges = input.changes != null
            val draftHeight = input.draftIcon?.height ?: 0.0
            val height = height(lines, hasWorkspace, hasChanges, input.branchIcon.height, draftHeight)
            val content = contentHeight(lines, hasWorkspace, hasChanges, input.branchIcon.height, draftHeight)
            val titleRowHeight = max(m.titleRowHeight, lines.title)

            val stamp = input.timestamp.size(max(0.0, contentWidth - m.cornerMarginLeft))
            val cornerWidth = max(m.cornerMinWidth, stamp.width)
            val top = (height - content) / 2
            val timestamp = Rect(right - stamp.width, top + (titleRowHeight - stamp.height) / 2, stamp.width, stamp.height)
            val dot = Rect(right - m.dotSize, top + (titleRowHeight - m.dotSize) / 2, m.dotSize, m.dotSize)
            val titleWidth = max(0.0, contentWidth - cornerWidth - m.cornerMarginLeft)
            val titleHeight = input.title.height(titleWidth)
            val title = Rect(x, top + (titleRowHeight - titleHeight) / 2, titleWidth, titleHeight)

            val projectY = top + titleRowHeight
            val projectHeight = input.project.height(contentWidth)
            val project = Rect(x, projectY + (lines.project - projectHeight) / 2, contentWidth, projectHeight)

            val workspaceY = projectY + lines.project + m.workspaceMarginTop
            val workspaceRow = workspaceRowHeight(lines, hasWorkspace, hasChanges, input.branchIcon.height, draftHeight)
            val draftWidth = input.draftIcon?.width ?: 0.0
            val metaWidth = draftWidth + (input.changes?.let { m.changesPaddingLeft + it.width } ?: 0.0)
            val metaX = max(x, right - metaWidth)
            val draftIcon = input.draftIcon?.let { Rect(metaX, workspaceY + (workspaceRow - it.height) / 2, it.width, it.height) }
            val changes = input.changes?.let {
                Rect(metaX + draftWidth + m.changesPaddingLeft, workspaceY + (workspaceRow - it.height) / 2, it.width, it.height)
            }
            val locationWidth = max(0.0, contentWidth - metaWidth)
            var workspace: Rect? = null
            var branchIcon: Rect? = null
            if (input.workspace != null) {
                val icon = input.branchIcon
                val textSize = input.workspace.size(max(0.0, locationWidth - m.workspaceIconGap - icon.width))
                workspace = Rect(x, workspaceY + (workspaceRow - textSize.height) / 2, textSize.width, textSize.height)
                branchIcon = Rect(x + textSize.width + m.workspaceIconGap, workspaceY + (workspaceRow - icon.height) / 2, icon.width, icon.height)
            }

            val avatar = Rect(m.paddingLeft, (height - m.avatarSize) / 2, m.avatarSize, m.avatarSize)
            val divider = if (input.showsDivider) Rect(x, height - m.dividerHeight, max(0.0, width - x), m.dividerHeight) else null
            return FlatSessionRowLayout(Size(width, height), avatar, title, timestamp, dot, project, workspace, branchIcon, changes, draftIcon, divider)
        }
    }
}

/** A day heading over archived rows ("Today", "3 days ago"): SessionsList.tsx `headerSectionFlat`. */
data class SessionDayHeaderLayout(val size: Size, val label: Rect) {
    object Metrics {
        const val paddingHorizontal = 16.0
        const val paddingTop = 20.0
        const val paddingBottom = 8.0
    }

    companion object {
        operator fun invoke(width: Double, label: Measurable): SessionDayHeaderLayout {
            val m = Metrics
            val w = max(0.0, width)
            val labelWidth = max(0.0, w - m.paddingHorizontal * 2)
            val labelHeight = label.height(labelWidth)
            return SessionDayHeaderLayout(Size(w, m.paddingTop + labelHeight + m.paddingBottom), Rect(m.paddingHorizontal, m.paddingTop, labelWidth, labelHeight))
        }
    }
}

data class VerticalPadding(val top: Double, val bottom: Double)

/** "── Show archived ──": a centred label between two hairlines (SessionsList.tsx `archiveToggle`). */
data class ArchiveToggleLayout(val size: Size, val label: Rect, val leadingLine: Rect, val trailingLine: Rect) {
    object Metrics {
        const val paddingHorizontal = 24.0
        const val paddingTop = 24.0
        const val paddingBottom = 8.0
        const val gap = 12.0
        const val lineHeight = 0.33
    }

    companion object {
        /** The project list's toggle divides the page: 20 above and 12 below. */
        val unbanded = VerticalPadding(20.0, 12.0)

        operator fun invoke(
            width: Double,
            label: Measurable,
            paddingTop: Double = Metrics.paddingTop,
            paddingBottom: Double = Metrics.paddingBottom,
        ): ArchiveToggleLayout {
            val m = Metrics
            val w = max(0.0, width)
            val inner = max(0.0, w - m.paddingHorizontal * 2)
            val text = label.size(max(0.0, inner - m.gap * 2))
            val lineWidth = max(0.0, (inner - text.width - m.gap * 2) / 2)
            val midY = paddingTop + text.height / 2
            val leading = Rect(m.paddingHorizontal, midY - m.lineHeight / 2, lineWidth, m.lineHeight)
            val labelFrame = Rect(leading.maxX + m.gap, paddingTop, text.width, text.height)
            val trailing = Rect(labelFrame.maxX + m.gap, leading.minY, lineWidth, m.lineHeight)
            return ArchiveToggleLayout(Size(w, paddingTop + text.height + paddingBottom), labelFrame, leading, trailing)
        }
    }
}

/**
 * A computer is reachable but nothing has run on it yet
 * (components/EmptyMainScreen.tsx): an icon, a title and a line of text,
 * centred, with "Start New Session" and an optional text action. Taller
 * than the safe area, the content starts at the top of it and scrolls.
 */
data class NoSessionsLayout(
    val icon: Rect,
    val title: Rect,
    val description: Rect,
    val button: Rect?,
    val action: Rect?,
    /** How tall the scrolling content is: the screen's height while the content fits. */
    val contentHeight: Double,
) {
    object Metrics {
        const val iconSize = 56.0
        const val iconMarginBottom = 20.0
        const val paddingHorizontal = 24.0
        const val titleMarginBottom = 8.0
        const val descriptionMaxWidth = 360.0
        const val descriptionMarginBottom = 24.0
        const val actionMarginTop = 4.0
        const val marginBottom = 32.0
    }

    class Input(
        val size: Size,
        val safeArea: LayoutInsets,
        val icon: Size = Size(Metrics.iconSize, Metrics.iconSize),
        val title: Measurable,
        val description: Measurable,
        val buttonHeight: Double? = null,
        val action: Measurable?,
    )

    companion object {
        operator fun invoke(input: Input): NoSessionsLayout {
            val m = Metrics
            val insets = input.safeArea
            val width = max(0.0, input.size.width - insets.horizontal)
            val textWidth = max(0.0, width - m.paddingHorizontal * 2)
            val descriptionWidth = min(m.descriptionMaxWidth, width) - m.paddingHorizontal * 2
            val titleSize = input.title.size(textWidth)
            val descriptionSize = input.description.size(max(0.0, descriptionWidth))
            val actionSize = input.action?.size(textWidth)

            var height = input.icon.height + m.iconMarginBottom + titleSize.height + m.titleMarginBottom +
                descriptionSize.height + m.descriptionMarginBottom
            if (input.buttonHeight != null) height += input.buttonHeight
            if (actionSize != null) height += m.actionMarginTop + actionSize.height

            val available = max(0.0, input.size.height - insets.vertical - m.marginBottom)
            var y = insets.top + max(0.0, available - height) / 2
            fun centred(size: Size, y: Double) = Rect(insets.left + (width - size.width) / 2, y, size.width, size.height)
            val icon = centred(input.icon, y)
            y = icon.maxY + m.iconMarginBottom
            val title = centred(titleSize, y)
            y = title.maxY + m.titleMarginBottom
            val description = centred(descriptionSize, y)
            y = description.maxY + m.descriptionMarginBottom
            val button = input.buttonHeight?.let { Rect(insets.left, y, width, it) }
            y = button?.maxY ?: y
            val action = actionSize?.let { centred(it, y + m.actionMarginTop) }
            y = action?.maxY ?: y
            return NoSessionsLayout(icon, title, description, button, action, max(input.size.height, y + m.marginBottom + insets.bottom))
        }
    }
}

/** A text-only button padded to a 40dp target (EmptyMainScreen.tsx `secondaryAction`). */
data class TextActionLayout(val size: Size, val label: Rect) {
    object Metrics {
        const val minHeight = 40.0
        const val paddingHorizontal = 14.0
        const val cornerRadius = 20.0
    }

    companion object {
        operator fun invoke(width: Double, label: Measurable): TextActionLayout {
            val m = Metrics
            val text = label.size(max(0.0, width - m.paddingHorizontal * 2))
            val height = max(m.minHeight, text.height)
            return TextActionLayout(Size(text.width + m.paddingHorizontal * 2, height), Rect(m.paddingHorizontal, (height - text.height) / 2, text.width, text.height))
        }
    }
}
