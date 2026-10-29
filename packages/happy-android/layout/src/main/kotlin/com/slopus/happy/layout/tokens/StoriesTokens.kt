package com.slopus.happy.layout

// Stories (happy-ios Tokens/StoriesTokens.swift): Telegram's stories row and
// viewer (`DefaultDayPresentationTheme`, `DefaultDarkPresentationTheme`
// `storyUnseenColors`, `storySeenColors`, `itemCheckColors`), on Stem Cells'
// own ground.

/** An unseen ring's gradient, top to bottom. */
val Palette.storiesUnseenTop get() = ColorToken("#34C76F")
val Palette.storiesUnseenBottom get() = ColorToken("#3DA1FD")

/** A seen ring. */
val Palette.storiesSeen get() = ColorToken("#D8D8E1", "#48484A")

/** An avatar without a photo, under its initials. */
val Palette.storiesAvatarFill get() = ColorToken("#B8BBC4", "#5A5A5F")
val Palette.storiesAvatarText get() = ColorToken("#FFFFFF")

/**
 * The one-time tooltip under the closed avatars (`TooltipScreen`'s default
 * style): black at 0.6 over the blur by day, the dark navigation bar's
 * #1D1D1D at 0.9 by night, white text on both.
 */
val Palette.storiesTooltipGround get() = ColorToken(RGBA(0, 0, 0, 0.6), RGBA(29 / 255.0, 29 / 255.0, 29 / 255.0, 0.9))
val Palette.storiesTooltipText get() = ColorToken("#FFFFFF")

/** The viewer's ground and chrome. */
val Palette.storiesViewerGround get() = ColorToken("#000000")
val Palette.storiesViewerText get() = ColorToken("#FFFFFF")
val Palette.storiesProgressTrack get() = ColorToken(RGBA(1, 1, 1, 0.3), RGBA(1, 1, 1, 0.3))
val Palette.storiesProgressFill get() = ColorToken("#FFFFFF")

/**
 * The editor (`MediaEditorScreen`, `DrawingEntitiesView`): Post's tinted
 * glass, the snap lines, the angle line, a selection's handles.
 */
val Palette.storiesEditorPost get() = ColorToken("#0088FF")
val Palette.storiesEditorGuide get() = ColorToken("#5FC1F0")
val Palette.storiesEditorAngle get() = ColorToken("#FFD70A")
val Palette.storiesEditorHandle get() = ColorToken("#0A60FF")

/** The sticker sheet's ground and its interactive stickers' pills. */
val Palette.storiesStickerSheet get() = ColorToken("#1C1C1E")
val Palette.storiesStickerPill get() = ColorToken(RGBA(1, 1, 1, 0.11), RGBA(1, 1, 1, 0.11))

/** The inline story's Post and Edit: UIButton's system tint, iOS's systemBlue. */
val Palette.storiesInlineTint get() = ColorToken("#007AFF", "#0A84FF")

/**
 * The views sheet, as the dark theme draws it: its ground (the navigation
 * bar's #1D1D1D at 0.9 over the black Telegram keeps under the list, so the
 * story never shows through), the time under a name, and the lines under
 * the header and between rows.
 */
val Palette.storiesViewersGround get() = ColorToken("#1A1A1A")
val Palette.storiesViewersSecondary get() = ColorToken("#98989E")
val Palette.storiesViewersSeparator get() = ColorToken(RGBA(84 / 255.0, 84 / 255.0, 88 / 255.0, 0.55), RGBA(84 / 255.0, 84 / 255.0, 88 / 255.0, 0.55))
