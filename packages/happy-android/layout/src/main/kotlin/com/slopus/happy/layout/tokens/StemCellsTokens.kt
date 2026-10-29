package com.slopus.happy.layout

// Stem Cells' measures (happy-ios Tokens/StemCellsTokens.swift):
// happy-desktop-ui's spaces sidebar (`Sidebar.tsx`, `styles/sidebar.css`,
// `theme.css`), scaled from its 32px pointer rows to touch. The colours are
// generated into StemCellsPalette.kt.

/**
 * The model control panels as iOS's own menus draw theirs: liquid glass,
 * about 250 wide, 17pt lines 44 tall with the check in a column before the
 * names; desktop's headings, gauges and account windows inside.
 */
object StemCellPickerMetrics {
    /** The panel's padding above and below its lines, and at its sides. */
    const val inset = 8.0
    /** Between a panel's blocks: services, hairlines, the footer. */
    const val gap = 5.0
    /** A menu's corners. */
    const val radius = 24.0
    /** Its shadow: soft and wide under the glass. */
    const val shadowRadius = 24.0
    const val shadowY = 8.0
    /** A model, effort or account line, and a service's heading. */
    const val row = 44.0
    const val heading = 32.0
    /** A line's text inset, and its highlight 2 inside it. */
    const val padding = 12.0
    const val highlightInset = 2.0
    const val highlightRadius = 14.0
    const val labelSize = 17.0
    const val headingSize = 13.0
    const val linkSize = 15.0
    /** The check, in the column before every name: names start `checkColumn` past the line's padding, picked or not. */
    const val check = 15.0
    const val checkColumn = 23.0
    /** Between a name and the gauge after it. */
    const val checkGap = 8.0
    /** A heading: its service, 5, its account (at most 110 wide), its usage 8 apart, and its chevron. */
    const val headingGap = 5.0
    const val accountMax = 110.0
    const val usageGap = 8.0
    const val chevron = 13.0
    /** A usage gauge: its number, 4, a ring 13 across. */
    const val gaugeSize = 13.0
    const val ring = 13.0
    const val ringGap = 4.0
    /**
     * The account's windows under its list: 7 in at top and bottom, lines
     * 5 apart, the week's 22 high, the rest 18; a window's name at least
     * 48 wide, so the times start together.
     */
    const val detailsInsetY = 7.0
    const val detailsLine = 18.0
    const val detailsPrimaryLine = 22.0
    const val detailsLabelMin = 48.0
    const val detailsGap = 8.0
    /** The panel stands 10 over its chip, at least 8 from the screen's edges. */
    const val anchorGap = 10.0
    const val margin = 8.0
    const val maxHeight = 560.0
    /** Widths: the model list 200 to 250, effort 160 to 250, accounts 250 to 280; a longer name ends early. */
    const val modelsMinWidth = 200.0
    const val modelsMaxWidth = 250.0
    const val effortsMinWidth = 160.0
    const val accountsMinWidth = 250.0
    const val accountsMaxWidth = 280.0
}

object StemCellsMetrics {
    /** A shimmer's pale band (`ShimmerText` `sheen`): the ink mixed 15% into its ground; one sweep takes 1.6s. */
    const val sheenInk = 0.15
    const val sheenDuration = 1.6
    // Desktop's sidebar at its own pixels (`Sidebar.tsx`: `SIDEBAR_ROW_HEIGHT`
    // and the rest, `styles/sidebar.css`, `theme.css`), half again as big on
    // the phone: rows, gaps, type and marks all `phone` times desktop's.
    // Nesting stays tight.
    /** How much bigger than desktop's the phone draws the sidebar. */
    const val phone = 1.5
    /** The row box's inset from the screen's edges. */
    const val inset = 8.0
    /** `SIDEBAR_ROW_HEIGHT`. */
    const val rowHeight = 32 * phone
    /**
     * Each end of a row holds its mark in a square the row's height: the
     * mark's optical centre half the row in from the row box's side, as far
     * as it is from the box's top (desktop's 16 in a 32 row).
     */
    const val endInset = rowHeight / 2
    /** A top-level mark's centre, from the screen's left edge. */
    const val markCenter = inset + endInset
    /** The fold's optical centre, and the trailing square's, from the right edge. */
    const val trailingCenter = inset + endInset
    /**
     * A row's title, lifted so its x-height's middle is the row's middle,
     * where the end marks' centres are (iOS measures 1.95 for Figtree 19.5;
     * 2 on whole pixels at 3x).
     */
    const val titleLift = 2.0
    /**
     * Where a fold chevron's ink weighs, from its image's centre, at
     * `chevronGlyph`: a "⌄" is heavier at its top, a "›" at its left.
     */
    val chevronDownInk = Point(0.0, -0.34)
    val chevronRightInk = Point(-0.21, -0.13)
    /** A section heading's title: desktop's row padding 10 in, which is where a top-level glyph's ink starts. */
    const val sectionTitleInset = 10 * phone
    /** `.happy-sidebar__section-item`. */
    const val sectionHeight = 28 * phone
    /** `.happy-sidebar__separator`: (17 × 1.5).rounded(). */
    const val separatorHeight = 26.0
    const val widgetMinHeight = 32 * phone
    /** Between one row and the next (`SIDEBAR_ROW_GAP`). */
    const val rowGap = 2 * phone
    /** The leading lane every mark stands in (`SIDEBAR_LEADING_SLOT`, the `xs` avatar). */
    const val mark = 20 * phone
    /** A glyph's size in that lane. */
    const val glyph = 16 * phone
    /** The row's flex gap: mark to label, label to trailing lane. */
    const val markGap = 8 * phone
    /** A nested row's mark stands this far right of its parent's: desktop's 28, not scaled, so nesting stays narrow. */
    const val indent = 28.0
    /** The parent's stem runs down its mark's centre, from under the mark, and an elbow runs from it to `elbowGap` short of the child's mark. */
    const val stemInset = mark / 2
    const val elbowGap = 4.0
    /** The trailing square a status mark, an unread dot or a fold stands in (`SIDEBAR_SLOT_CELL`). */
    const val trailing = 18 * phone
    const val trailingGap = 8 * phone
    /** `--happy-hover-radius`. */
    const val cornerRadius = 6 * phone
    /** A picture's rounding in its lane: a rounded square, never a circle. */
    const val pictureRadius = 6 * phone
    /** `.happy-sidebar__item-label`: 13, weight 500, line 16. */
    const val labelSize = 13 * phone
    /** `.happy-sidebar__section-item-label`: mono 11, bold. */
    const val sectionLabelSize = 11 * phone
    /** `letter-spacing: 0.08em`. */
    const val sectionTracking = 0.08
    /** The tree's lines and a separator's rule: desktop's, not scaled. */
    const val line = 1.0
    /** The fold's chevron, the question and the clock in the trailing square. */
    const val chevronGlyph = 12 * phone
    const val questionGlyph = 15 * phone
    const val clockGlyph = 14 * phone
    /** A section's "+". */
    const val addGlyph = 14 * phone
    /** The tree's lines, at 0.28 of the row ink. */
    const val connectorAlpha = 0.28
    /** Room above the first row and below the last (`.happy-sidebar__body-content`). */
    const val contentTop = 6 * phone
    const val contentBottom = 6 * phone
}

/** Stem Cells' agent text, as desktop's Message sets it: Figtree 16/24. */
val ChatTextStyles.stemCellsText get() = TextStyle(FontFace.Figtree, 16, 24)

/** Desktop's heading ramp: all 600 on 24 but h1's 900 (Figtree Bold here, its heaviest bundled), h2 at 20, h3 on 28. */
fun ChatTextStyles.stemCellsHeader(level: Int) = when (level) {
    1 -> TextStyle(FontFace.FigtreeBold, 16, 24)
    2 -> TextStyle(FontFace.FigtreeSemiBold, 20, 24)
    3 -> TextStyle(FontFace.FigtreeSemiBold, 16, 28)
    else -> TextStyle(FontFace.FigtreeSemiBold, 16, 24)
}

/** A message from another agent (`agent-activity-row.css`): its sender, what it said it is, and the message opened under them. */
val ChatTextStyles.stemCellsActivityVerb get() = TextStyle(FontFace.FigtreeMedium, 14, 20)
val ChatTextStyles.stemCellsActivityText get() = TextStyle(FontFace.Figtree, 14, 20)
val ChatTextStyles.stemCellsActivityBody get() = TextStyle(FontFace.Figtree, 12, 18)

@Suppress("UNUSED_PARAMETER")
fun ChatTextStyles.stemCellsActivityHeader(level: Int) = TextStyle(FontFace.FigtreeSemiBold, 12, 18)
