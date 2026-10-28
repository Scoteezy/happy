package com.slopus.happy.layout

import kotlin.math.max

/**
 * The phone home header: MainView.tsx's phone header, drawn by
 * navigation/Header.tsx with headerMetrics.ts (happy-ios
 * `HomeHeaderLayout`).
 *
 *     |16| (logo) ……… Sessions ……… ( ⏷ │ ⚙ ) |16|
 *                     ● connecting
 *
 * It floats over the content, which scrolls under it, inside a 52dp row
 * below the status bar:
 *   - the logo sits in a 44dp circle on the left (a 19dp mark in a 32dp box);
 *   - the filter menu and settings share one pill on the right, split by a
 *     22dp hairline. The pill's 1dp rim is drawn outside the 44dp buttons, so
 *     it is 46 tall where the circle is 44;
 *   - the title is centred between 64dp insets and the status line hangs 2dp
 *     into the title's bottom, outside the centring.
 * A scrim reaches 8dp below the row.
 */
data class HomeHeaderLayout(
    /** The header's own box: the status bar and the 52dp row. */
    val size: Size,
    /** The scrim behind the controls, 8dp deeper than the header. */
    val backdrop: Rect,
    val logoControl: Rect,
    val logo: Rect,
    /** The right-hand pill, rim included. */
    val actions: Rect,
    val filterButton: Rect,
    val actionDivider: Rect,
    val settingsButton: Rect,
    val title: Rect,
    val statusDot: Rect?,
    val status: Rect?,
) {
    object Metrics {
        const val contentHeight = 52.0
        const val paddingHorizontal = 16.0
        const val controlSize = 44.0
        const val controlBorder = 1.0
        const val logoBox = 32.0
        const val logoSize = 19.0
        const val actionDividerHeight = 22.0
        const val titleInset = 64.0
        const val statusMarginTop = -2.0
        const val statusMinHeight = 16.0
        const val statusDotSize = 6.0
        const val statusDotGap = 4.0
        const val backdropOverhang = 8.0
        /** MainView.tsx: content starts 12 below the chrome. */
        const val contentGap = 12.0
    }

    class Input(
        val width: Double,
        val safeTop: Double,
        /** One device pixel. */
        val hairline: Double,
        val title: Measurable,
        /** The status text, when the socket is not connected. */
        val status: Measurable?,
        var showsAccountAvatar: Boolean = false,
        /** Where the title starts when something beside it places it; null centres it. */
        var titleX: Double? = null,
        var backdropExtension: Double = 0.0,
        /** The pill holds one button, in the first slot. */
        var singleAction: Boolean = false,
    )

    companion object {
        operator fun invoke(input: Input): HomeHeaderLayout {
            val m = Metrics
            val width = max(0.0, input.width)
            val top = input.safeTop
            val rowHeight = m.contentHeight

            val size = Size(width, top + rowHeight)
            val backdrop = Rect(0.0, 0.0, width, top + rowHeight + m.backdropOverhang + max(0.0, input.backdropExtension))

            val logoControl = Rect(m.paddingHorizontal, top + (rowHeight - m.controlSize) / 2, m.controlSize, m.controlSize)
            val logoSize = if (input.showsAccountAvatar) m.logoBox else m.logoSize
            val logoInset = (m.controlSize - logoSize) / 2
            val logo = Rect(logoControl.minX + logoInset, logoControl.minY + logoInset, logoSize, logoSize)

            val pillWidth = if (input.singleAction) 2 * m.controlBorder + m.controlSize else 2 * m.controlBorder + 2 * m.controlSize + input.hairline
            val pillHeight = 2 * m.controlBorder + m.controlSize
            val actions = Rect(width - m.paddingHorizontal - pillWidth, top + (rowHeight - pillHeight) / 2, pillWidth, pillHeight)
            val buttonY = actions.minY + m.controlBorder
            val filterButton = Rect(actions.minX + m.controlBorder, buttonY, m.controlSize, m.controlSize)
            val actionDivider = Rect(
                filterButton.maxX,
                buttonY + (m.controlSize - m.actionDividerHeight) / 2,
                if (input.singleAction) 0.0 else input.hairline,
                m.actionDividerHeight,
            )
            val settingsButton = Rect(actionDivider.maxX, buttonY, if (input.singleAction) 0.0 else m.controlSize, m.controlSize)

            val slotX = m.titleInset
            val slotWidth = max(0.0, width - 2 * m.titleInset)
            val titleSize = input.title.size(slotWidth)
            val titleY = top + (rowHeight - titleSize.height) / 2
            val titleX = input.titleX ?: (slotX + (slotWidth - titleSize.width) / 2)
            val title = Rect(titleX, titleY, titleSize.width, titleSize.height)

            var statusDot: Rect? = null
            var status: Rect? = null
            if (input.status != null) {
                val text = input.status.size(max(0.0, slotWidth - m.statusDotSize - m.statusDotGap))
                val statusRow = max(m.statusDotSize, text.height)
                val slotHeight = max(m.statusMinHeight, statusRow)
                val slotY = titleY + titleSize.height + m.statusMarginTop
                val rowWidth = m.statusDotSize + m.statusDotGap + text.width
                val rowX = title.midX - rowWidth / 2
                val rowY = slotY + (slotHeight - statusRow) / 2
                statusDot = Rect(rowX, rowY + (statusRow - m.statusDotSize) / 2, m.statusDotSize, m.statusDotSize)
                status = Rect(rowX + m.statusDotSize + m.statusDotGap, rowY + (statusRow - text.height) / 2, text.width, text.height)
            }
            return HomeHeaderLayout(size, backdrop, logoControl, logo, actions, filterButton, actionDivider, settingsButton, title, statusDot, status)
        }
    }
}

/**
 * Where home's content sits under the floating header and above the dock
 * (MainView.tsx `topChromeInset`, `topContentInset`, `bottomContentInset`).
 */
data class HomeContentInsets(
    /** What the header covers: the status bar and the 52dp row. */
    val chromeTop: Double,
    /** Where content starts: 12 below the chrome. */
    val contentTop: Double,
    /** What the dock covers, without the navigation bar. */
    val dock: Double,
    /** The list's bottom padding: `safeArea.bottom + bottomContentInset`. */
    val listBottom: Double,
) {
    /** The safe area the empty states lay out in. */
    val emptyStateSafeArea get() = LayoutInsets(top = contentTop, bottom = dock)

    /** What a child's safe area must grow by to see [emptyStateSafeArea] inside [safeArea]; never negative. */
    fun additionalSafeArea(over: LayoutInsets): LayoutInsets {
        val target = emptyStateSafeArea
        return LayoutInsets(top = max(0.0, target.top - over.top), left = 0.0, bottom = max(0.0, target.bottom - over.bottom), right = 0.0)
    }

    companion object {
        operator fun invoke(safeArea: LayoutInsets, dock: Double): HomeContentInsets {
            val chromeTop = safeArea.top + HomeHeaderLayout.Metrics.contentHeight
            return HomeContentInsets(chromeTop, chromeTop + HomeHeaderLayout.Metrics.contentGap, dock, safeArea.bottom + dock)
        }
    }
}

/** Home as a whole: the content fills the screen and the header floats over its top, scrim included. */
data class HomeScreenLayout(val content: Rect, val header: Rect, val insets: HomeContentInsets) {
    companion object {
        operator fun invoke(size: Size, safeArea: LayoutInsets, dock: Double): HomeScreenLayout {
            val width = max(0.0, size.width)
            return HomeScreenLayout(
                content = Rect(0.0, 0.0, width, max(0.0, size.height)),
                header = Rect(0.0, 0.0, width, safeArea.top + HomeHeaderLayout.Metrics.contentHeight + HomeHeaderLayout.Metrics.backdropOverhang),
                insets = HomeContentInsets(safeArea, dock),
            )
        }
    }
}
