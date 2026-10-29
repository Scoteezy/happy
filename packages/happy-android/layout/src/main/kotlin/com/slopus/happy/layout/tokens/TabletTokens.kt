package com.slopus.happy.layout

// Tablet (happy-ios Tokens/TabletTokens.swift): SidebarNavigator.tsx's
// persistent header, SidebarView.tsx and EmptySessionsTablet.tsx (iOS
// values). The colours they use are the shared ones: groupped.background,
// divider, surface, surfacePressed, surfaceSelected, header.tint, textLink,
// textSecondary and button.primary.

/** SidebarView.tsx `newSessionText`: 14, weight 500, semiBold. */
val TextStyles.sidebarNewSession get() = TextStyle(FontFace.SemiBold, 14)

/**
 * SidebarView.tsx `settingsText`: 14, weight 500 over the regular face,
 * which React Native resolves to SemiBold (see `homeHeaderStatus`).
 */
val TextStyles.sidebarSettings get() = TextStyle(FontFace.SemiBold, 14)

/** EmptySessionsTablet.tsx `titleText`: 20, regular. */
val TextStyles.tabletEmptyTitle get() = TextStyle(FontFace.Regular, 20)

/** EmptySessionsTablet.tsx `descriptionText`: 16, regular. */
val TextStyles.tabletEmptyDescription get() = TextStyle(FontFace.Regular, 16)

/** EmptySessionsTablet.tsx `buttonText`: 16, weight 600, semiBold. */
val TextStyles.tabletEmptyButton get() = TextStyle(FontFace.SemiBold, 16)

/** STATUS_CONFIG's colours, written as literals in SessionsList.tsx and ActiveSessionsGroupCompact.tsx, so the same in both themes. */
object TabletPalette {
    /** `disconnected`: #999. */
    val statusOffline = ColorToken("#999999")
    /** `thinking`, and an unread turn: #007AFF. */
    val statusWorking = ColorToken("#007AFF")
    /** `waiting`: #34C759. */
    val statusOnline = ColorToken("#34C759")
    /** `permission_required`, `input_required`: #FF9500. */
    val statusAttention = ColorToken("#FF9500")
}
