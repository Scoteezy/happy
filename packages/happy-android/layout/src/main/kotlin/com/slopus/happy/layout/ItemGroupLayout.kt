package com.slopus.happy.layout

import kotlin.math.max
import kotlin.math.min

/**
 * An inset grouped section: an uppercase header, a rounded card holding the
 * rows, and a footer. Ported from components/ItemGroup.tsx (iOS values), as
 * happy-ios `ItemGroupLayout`.
 */
data class ItemGroupLayout(
    val size: Size,
    val header: Rect?,
    val card: Rect,
    /** Row frames in the card's coordinates. */
    val rows: List<Rect>,
    val footer: Rect?,
) {
    object Metrics {
        const val headerPaddingTop = 35.0
        const val headerPaddingBottom = 6.0
        const val headerPaddingHorizontal = 32.0
        /** Space above the card when there is no header. */
        const val untitledPaddingTop = 20.0
        const val cardMarginHorizontal = 16.0
        const val cardCornerRadius = Radius.group
        const val footerPaddingTop = 6.0
        const val footerPaddingBottom = 8.0
        const val footerPaddingHorizontal = 32.0
    }

    class Input(
        val width: Double,
        val title: Measurable? = null,
        val footer: Measurable? = null,
        /** Each row's height at the card's width. */
        val rows: List<Measurable>,
        /** The group is centred at this width on wide screens. */
        val maxWidth: Double? = null,
        /** Overrides the space above an untitled card (`headerStyle`). */
        val untitledPaddingTop: Double? = null,
        /** Space below the group (`style.paddingBottom`). */
        val paddingBottom: Double = 0.0,
    )

    companion object {
        /** The width rows are laid out at, for measuring them before layout. */
        fun cardWidth(width: Double, maxWidth: Double? = null): Double =
            max(0.0, groupWidth(width, maxWidth) - Metrics.cardMarginHorizontal * 2)

        private fun groupWidth(width: Double, maxWidth: Double?): Double {
            val w = max(0.0, width)
            return maxWidth?.let { min(w, it) } ?: w
        }

        operator fun invoke(input: Input): ItemGroupLayout {
            val m = Metrics
            val width = max(0.0, input.width)
            val groupWidth = groupWidth(width, input.maxWidth)
            val originX = (width - groupWidth) / 2
            var y: Double
            val header: Rect?
            val title = input.title
            if (title != null) {
                val textWidth = max(0.0, groupWidth - m.headerPaddingHorizontal * 2)
                val height = title.height(textWidth)
                header = Rect(originX + m.headerPaddingHorizontal, m.headerPaddingTop, textWidth, height)
                y = m.headerPaddingTop + height + m.headerPaddingBottom
            } else {
                header = null
                y = input.untitledPaddingTop ?: m.untitledPaddingTop
            }

            val cardWidth = cardWidth(width, input.maxWidth)
            var rowY = 0.0
            val rows = input.rows.map { row ->
                val height = row.height(cardWidth)
                Rect(0.0, rowY, cardWidth, height).also { rowY += height }
            }
            val card = Rect(originX + m.cardMarginHorizontal, y, cardWidth, rowY)
            y = card.maxY

            val footer = input.footer?.let { f ->
                val textWidth = max(0.0, groupWidth - m.footerPaddingHorizontal * 2)
                val height = f.height(textWidth)
                Rect(originX + m.footerPaddingHorizontal, y + m.footerPaddingTop, textWidth, height).also {
                    y += m.footerPaddingTop + height + m.footerPaddingBottom
                }
            }
            return ItemGroupLayout(Size(width, y + input.paddingBottom), header, card, rows, footer)
        }
    }
}

/**
 * A scrolling column of sections on the grouped background, like
 * components/ItemList.tsx. The safe area is applied here so the frames are
 * fully determined.
 */
data class GroupedListLayout(
    /** Section frames in the scroll view's content coordinates. */
    val sections: List<Rect>,
    val contentSize: Size,
) {
    object Metrics {
        const val bottomPadding = 34.0
    }

    class Input(
        val size: Size,
        val safeArea: LayoutInsets,
        /** Each section's height at the list's content width. */
        val sections: List<Measurable>,
    )

    companion object {
        fun contentWidth(size: Size, safeArea: LayoutInsets) = max(0.0, size.width - safeArea.horizontal)

        operator fun invoke(input: Input): GroupedListLayout {
            val width = contentWidth(input.size, input.safeArea)
            var y = input.safeArea.top
            val sections = input.sections.map { section ->
                val height = section.height(width)
                Rect(input.safeArea.left, y, width, height).also { y += height }
            }
            // Never shorter than the viewport, so the background fills it.
            val height = max(input.size.height, y + Metrics.bottomPadding + input.safeArea.bottom)
            return GroupedListLayout(sections, Size(max(0.0, input.size.width), height))
        }
    }
}

/** The card at the top of Settings: the logotype on the surface colour (components/SettingsView.tsx). */
data class SettingsHeaderLayout(
    val size: Size,
    val card: Rect,
    /** In the card's coordinates. */
    val logo: Rect,
) {
    object Metrics {
        const val marginTop = 16.0
        const val marginHorizontal = 16.0
        const val paddingVertical = 24.0
        val logoSize = Size(300.0, 90.0)
        const val logoMarginBottom = 12.0
        /** Keeps the logo off the card's edges on narrow screens. */
        const val logoMinimumInset = 16.0
        const val cornerRadius = 16.0
    }

    companion object {
        operator fun invoke(width: Double): SettingsHeaderLayout {
            val m = Metrics
            val w = max(0.0, width)
            val cardWidth = max(0.0, w - m.marginHorizontal * 2)
            val logoWidth = min(m.logoSize.width, max(0.0, cardWidth - m.logoMinimumInset * 2))
            val logoHeight = logoWidth * m.logoSize.height / m.logoSize.width
            val logo = Rect((cardWidth - logoWidth) / 2, m.paddingVertical, logoWidth, logoHeight)
            val cardHeight = m.paddingVertical + logoHeight + m.logoMarginBottom + m.paddingVertical
            val card = Rect(m.marginHorizontal, m.marginTop, cardWidth, cardHeight)
            return SettingsHeaderLayout(Size(w, card.maxY), card, logo)
        }
    }
}

/** The revealed secret key under Settings › Backup: a small label with a copy icon, then the key (settings/account.tsx). */
data class SecretKeyCardLayout(val size: Size, val label: Rect, val icon: Rect, val value: Rect) {
    object Metrics {
        const val paddingHorizontal = 16.0
        const val paddingVertical = 14.0
        const val headerMarginBottom = 10.0
        const val iconSize = 18.0
        const val labelIconSpacing = 8.0
    }

    companion object {
        operator fun invoke(width: Double, label: Measurable, value: Measurable): SecretKeyCardLayout {
            val m = Metrics
            val w = max(0.0, width)
            val innerWidth = max(0.0, w - m.paddingHorizontal * 2)
            val labelWidth = max(0.0, innerWidth - m.iconSize - m.labelIconSpacing)
            val labelHeight = label.height(labelWidth)
            val headerHeight = max(labelHeight, m.iconSize)
            val top = m.paddingVertical
            val labelFrame = Rect(m.paddingHorizontal, top + (headerHeight - labelHeight) / 2, labelWidth, labelHeight)
            val icon = Rect(w - m.paddingHorizontal - m.iconSize, top + (headerHeight - m.iconSize) / 2, m.iconSize, m.iconSize)
            val valueFrame = Rect(m.paddingHorizontal, top + headerHeight + m.headerMarginBottom, innerWidth, value.height(innerWidth))
            return SecretKeyCardLayout(Size(w, valueFrame.maxY + m.paddingVertical), labelFrame, icon, valueFrame)
        }
    }
}
