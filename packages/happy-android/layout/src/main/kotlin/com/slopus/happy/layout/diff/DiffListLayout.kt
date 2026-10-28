package com.slopus.happy.layout.diff

import com.slopus.happy.layout.Measurable
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import kotlin.math.max
import kotlin.math.min

// Ported from happy-ios HappyLayout/Diff/DiffListLayout.swift.

/** One line of measured text: its box and where its baseline sits in it. */
data class DiffTextBox(
    val size: Size,
    /** Distance from the box's top to the baseline (the font's ascender). */
    val baseline: Double,
)

/**
 * components/diff/DiffFileHeader.tsx: a `hunkBg` row, 12 in and 9 down,
 * its parts 8 apart and centred vertically:
 *
 *     12 [chevron] 8 [icon 16] 8 [dir…fileName] 8 [BADGE] 8 [+N] 8 [−N] 12
 *
 * The path takes what the rest leave (`flex: 1`): the file name keeps its
 * width and the directory gives way, truncated at its head. The two sit on
 * one baseline (`alignItems: 'baseline'`).
 */
class DiffFileHeaderLayout(input: Input) {
    object Metrics {
        const val paddingHorizontal = 12.0
        const val paddingVertical = 9.0
        const val gap = 8.0
        const val iconSize = 16.0
        /** The chevron's Ionicons size. */
        const val chevronSize = 14.0
    }

    data class Input(
        val width: Double,
        /** The chevron's box, when the header toggles. */
        val chevron: Size?,
        /** The directory part, `""` at the root (still a line tall). */
        val dir: DiffTextBox,
        val fileName: DiffTextBox,
        val badge: Size? = null,
        val additions: Size? = null,
        val deletions: Size? = null,
    )

    val size: Size
    val chevron: Rect?
    val icon: Rect
    val dir: Rect
    val fileName: Rect
    val badge: Rect?
    val additions: Rect?
    val deletions: Rect?

    init {
        val m = Metrics
        val width = max(0.0, input.width)
        val ascent = max(input.dir.baseline, input.fileName.baseline)
        val descent = max(input.dir.size.height - input.dir.baseline, input.fileName.size.height - input.fileName.baseline)
        val pathHeight = ascent + descent
        val trailing = listOf(input.badge, input.additions, input.deletions)
        val rowHeight = (listOf(input.chevron?.height, m.iconSize, pathHeight) + trailing.map { it?.height }).filterNotNull().max()
        val top = m.paddingVertical
        fun centred(x: Double, size: Size) = Rect(x, top + (rowHeight - size.height) / 2, size.width, size.height)

        var x = m.paddingHorizontal
        chevron = input.chevron?.let { size -> centred(x, size).also { x += size.width + m.gap } }
        icon = centred(x, Size(m.iconSize, m.iconSize))
        x += m.iconSize + m.gap

        val trailingWidth = trailing.filterNotNull().sumOf { m.gap + it.width }
        val pathWidth = max(0.0, width - m.paddingHorizontal - x - trailingWidth)
        val nameWidth = min(input.fileName.size.width, pathWidth)
        val dirWidth = min(input.dir.size.width, max(0.0, pathWidth - nameWidth))
        val pathTop = top + (rowHeight - pathHeight) / 2
        dir = Rect(x, pathTop + ascent - input.dir.baseline, dirWidth, input.dir.size.height)
        fileName = Rect(x + dirWidth, pathTop + ascent - input.fileName.baseline, nameWidth, input.fileName.size.height)

        x += pathWidth
        fun next(size: Size?): Rect? {
            size ?: return null
            x += m.gap
            return centred(x, size).also { x += size.width }
        }
        badge = next(input.badge)
        additions = next(input.additions)
        deletions = next(input.deletions)
        size = Size(width, rowHeight + m.paddingVertical * 2)
    }
}

/**
 * components/diff/DiffImageView.tsx: the sides there are, side by side
 * with 12 around and between, each a label 6 over a 160-tall box.
 *
 * [labelHeight] is one line of the 10.5 mono label; [count] is how many
 * sides there are (1 or 2).
 */
class DiffImageLayout(width: Double, count: Int, labelHeight: Double) {
    object Metrics {
        const val padding = 12.0
        const val gap = 12.0
        const val labelGap = 6.0
        const val boxHeight = 160.0
        const val boxBorder = 1.0
        const val boxRadius = 6.0
    }

    data class Pane(
        val label: Rect,
        val box: Rect,
        /** Inside the border, where the picture is fitted. */
        val image: Rect,
    )

    val size: Size
    val panes: List<Pane>

    init {
        val m = Metrics
        val w = max(0.0, width)
        val n = max(1, count)
        val paneWidth = max(0.0, (w - m.padding * 2 - m.gap * (n - 1)) / n)
        panes = (0 until n).map { index ->
            val x = m.padding + index * (paneWidth + m.gap)
            val label = Rect(x, m.padding, paneWidth, labelHeight)
            val box = Rect(x, label.maxY + m.labelGap, paneWidth, m.boxHeight)
            Pane(label, box, box.insetBy(m.boxBorder, m.boxBorder))
        }
        size = Size(w, m.padding * 2 + labelHeight + m.labelGap + m.boxHeight)
    }
}

/**
 * DiffFilesList.tsx's FileSection: the header, then whatever the file has
 * to show, over a 1dp divider. Centred at `layout.maxWidth`.
 */
class DiffSectionLayout(width: Double, maxWidth: Double? = null, headerHeight: Double, body: Body) {
    object Metrics {
        const val border = 1.0
        /** "N changed lines — tap to expand". */
        const val hintPaddingVertical = 12.0
        /** Error, message and loading rows. */
        const val messagePaddingVertical = 16.0
    }

    sealed interface Body {
        /** Collapsed with nothing to explain. */
        data object None : Body
        /** The size hint's text (13, centred). */
        data class Hint(val size: Size) : Body
        /** A message's text, measured at the section's width. */
        data class Message(val size: Size) : Body
        /** The small activity indicator. */
        data class Spinner(val size: Size) : Body
        /** The image view's height. */
        data class Image(val height: Double) : Body
        /** Each built file's view height, one after another. */
        data class Files(val heights: List<Double>) : Body
    }

    val frame: Rect
    /** The rest are in the section's coordinates. */
    val header: Rect
    /** The hint's tap target (the whole row). */
    val hintRow: Rect?
    /** The hint, message or spinner, centred. */
    val content: Rect?
    val image: Rect?
    val files: List<Rect>
    val border: Rect
    val height: Double get() = frame.height

    init {
        val m = Metrics
        val outer = max(0.0, width)
        val inner = min(outer, maxWidth ?: outer)
        header = Rect(0.0, 0.0, inner, headerHeight)
        var y = header.maxY
        fun centred(size: Size, padding: Double): Rect {
            val w = min(size.width, inner)
            return Rect((inner - w) / 2, y + padding, w, size.height).also { y += padding * 2 + size.height }
        }
        var hintRow: Rect? = null
        var content: Rect? = null
        var image: Rect? = null
        val files = mutableListOf<Rect>()
        when (body) {
            Body.None -> Unit
            is Body.Hint -> {
                hintRow = Rect(0.0, y, inner, body.size.height + m.hintPaddingVertical * 2)
                content = centred(body.size, m.hintPaddingVertical)
            }
            is Body.Message -> content = centred(body.size, m.messagePaddingVertical)
            is Body.Spinner -> content = centred(body.size, m.messagePaddingVertical)
            is Body.Image -> {
                image = Rect(0.0, y, inner, body.height)
                y += body.height
            }
            is Body.Files -> for (h in body.heights) {
                files += Rect(0.0, y, inner, h)
                y += h
            }
        }
        this.hintRow = hintRow
        this.content = content
        this.image = image
        this.files = files
        border = Rect(0.0, y, inner, m.border)
        frame = Rect((outer - inner) / 2, 0.0, inner, border.maxY)
    }
}

/**
 * DiffFilesList.tsx's FlashList: an optional header, the sections one
 * after another, 32 of room at the end. With no sections, the empty text
 * centred 32 in.
 *
 * [sections] are each section's height; [empty] is the empty text's size,
 * used only when there are none.
 */
class DiffFilesListLayout(width: Double, headerHeight: Double?, sections: List<Double>, empty: Size) {
    object Metrics {
        const val paddingBottom = 32.0
        const val emptyPadding = 32.0
        /** HappyAgentDiffView's notices: padding 16, 8 apart. */
        const val noticesPadding = 16.0
        const val noticesGap = 8.0
    }

    val header: Rect?
    val sections: List<Rect>
    val empty: Rect?
    val contentHeight: Double

    init {
        val m = Metrics
        val w = max(0.0, width)
        var y = 0.0
        header = headerHeight?.let { Rect(0.0, 0.0, w, it).also { y = headerHeight } }
        this.sections = sections.map { h -> Rect(0.0, y, w, h).also { y += h } }
        this.empty = if (sections.isEmpty()) {
            val size = Size(min(empty.width, max(0.0, w - m.emptyPadding * 2)), empty.height)
            Rect((w - size.width) / 2, y + m.emptyPadding, size.width, size.height).also { y += m.emptyPadding * 2 + size.height }
        } else {
            null
        }
        contentHeight = y + m.paddingBottom
    }

    companion object {
        /** The notices' text frames and height, each measured at the inner width. */
        fun notices(width: Double, texts: List<Measurable>): Pair<List<Rect>, Double> {
            val m = Metrics
            val inner = max(0.0, width - m.noticesPadding * 2)
            var y = m.noticesPadding
            val frames = texts.mapIndexed { index, text ->
                if (index > 0) y += m.noticesGap
                val size = text.size(inner)
                Rect(m.noticesPadding, y, size.width, size.height).also { y += size.height }
            }
            return frames to y + m.noticesPadding
        }
    }
}
