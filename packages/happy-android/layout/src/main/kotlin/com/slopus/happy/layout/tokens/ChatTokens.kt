package com.slopus.happy.layout

// The chat's tokens (happy-ios Tokens/ChatTokens.swift and the composer's
// status colours from Tokens/ComposerTokens.swift): theme.ts colours and the
// stylesheets of MessageView, MarkdownView, SimpleSyntaxHighlighter, the tool
// views and ChatHeaderView (iOS values). Text happy-app draws in the system
// font or Menlo is in the platform's sans-serif and monospace here.

val Palette.userMessageText get() = ColorToken("#000000", "#FFFFFF")
val Palette.agentEventText get() = ColorToken("#666666", "#8E8E93")
/** Marks on an accent fill (a checked checkbox). */
val Palette.onAccent get() = ColorToken("#FFFFFF")
/** AgentQuestionModal's dot beside a question still to answer. */
val Palette.requiredDot get() = ColorToken("#FF9500")
/** theme.colors.warning: the tool error glyph. */
val Palette.warning get() = ColorToken("#8E8E93")
val Palette.boxWarningBackground get() = ColorToken(RGBA.hex("#FFF8F0")!!, RGBA(1.0, 159 / 255.0, 10 / 255.0, 0.15))
val Palette.boxErrorBackground get() = ColorToken(RGBA.hex("#FFF0F0")!!, RGBA(1.0, 69 / 255.0, 58 / 255.0, 0.15))
val Palette.boxErrorBorder get() = ColorToken("#FF3B30", "#FF453A")
val Palette.boxErrorText get() = ColorToken("#FF3B30", "#FF6B6B")
val Palette.boxWarningText get() = ColorToken("#FF9500", "#FFAB00")

val Palette.syntaxKeyword get() = ColorToken("#1d4ed8", "#569CD6")
val Palette.syntaxString get() = ColorToken("#059669", "#CE9178")
val Palette.syntaxComment get() = ColorToken("#6b7280", "#6A9955")
val Palette.syntaxNumber get() = ColorToken("#0891b2", "#B5CEA8")
val Palette.syntaxFunction get() = ColorToken("#9333ea", "#DCDCAA")
val Palette.syntaxBracket1 get() = ColorToken("#ff6b6b", "#FFD700")
val Palette.syntaxBracket2 get() = ColorToken("#4ecdc4", "#DA70D6")
val Palette.syntaxBracket3 get() = ColorToken("#45b7d1", "#179FFF")
val Palette.syntaxBracket4 get() = ColorToken("#f7b731", "#FF8C00")
val Palette.syntaxBracket5 get() = ColorToken("#5f27cd", "#00FF00")
val Palette.syntaxDefault get() = ColorToken("#374151", "#D4D4D4")

val Palette.gitBranchText get() = ColorToken("#6b7280", "#8E8E93")

val Palette.terminalPanel get() = ColorToken("#1E1E1E")
val Palette.terminalPromptGreen get() = ColorToken("#34C759", "#32D74B")
val Palette.terminalCommand get() = ColorToken("#E0E0E0")
val Palette.terminalStdout get() = ColorToken("#E0E0E0")
val Palette.terminalStderr get() = ColorToken("#FFB86C")
val Palette.terminalError get() = ColorToken("#FF5555", "#FF6B6B")

/** theme.colors.button.secondary.tint. */
val Palette.buttonSecondaryTint get() = ColorToken("#666666", "#8E8E93")
/** theme.colors.shadow: the scroll-to-bottom button. */
val Palette.shadow get() = ColorToken("#000000")
/** ChatHeaderView's subtitle in the capsule. */
val Palette.headerGlassSubtitle get() = ColorToken(RGBA(24 / 255.0, 23 / 255.0, 28 / 255.0, 0.72), RGBA(1.0, 1.0, 1.0, 0.78))

val Palette.todoCompleted get() = ColorToken("#34C759")
val Palette.todoInProgress get() = ColorToken("#007AFF")
val Palette.todoPending get() = ColorToken("#666666")
val Palette.todoOther get() = ColorToken("#000000")

// ToolFullView.tsx's section glyphs and MultiEditViewFull.
val Palette.fullViewPurple get() = ColorToken("#5856D6")
val Palette.fullViewGreen get() = ColorToken("#34C759")
val Palette.fullViewRed get() = ColorToken("#FF3B30")
val Palette.fullViewOrange get() = ColorToken("#FF9500")
val Palette.multiEditSeparator get() = ColorToken("#E5E5EA")
val Palette.terminalEmptyOutput get() = ColorToken("#6272A4", "#7B7B93")

// AgentInput.tsx's status dot.
val Palette.sessionStatusDisconnected get() = ColorToken("#999999")
val Palette.sessionStatusAttention get() = ColorToken("#FF9500")
val Palette.sessionStatusThinking get() = ColorToken("#007AFF")
val Palette.sessionStatusOnline get() = ColorToken("#34C759")

// SessionView's CLI-outdated pill under the header.
val Palette.cliWarningFill get() = ColorToken("#FFF3CD")
val Palette.cliWarningText get() = ColorToken("#856404")
val Palette.cliWarningIcon get() = ColorToken("#FF9500")

/**
 * WorktreeTabStrip's open tab: a translucent chip, so it doesn't punch a
 * hole in the glass, rgba(0,0,0,0.07) in light and rgba(255,255,255,0.14)
 * in dark.
 */
val Palette.worktreeTabSelected get() = ColorToken(RGBA(0.0, 0.0, 0.0, 0.07), RGBA(1.0, 1.0, 1.0, 0.14))

/** The chat's text styles, by the component they come from. */
object ChatTextStyles {
    // MarkdownView.tsx
    val markdownText = TextStyle(FontFace.Regular, 16, 25)
    /** text-selection.tsx: the text to select, in mono. */
    val textSelection = TextStyle(FontFace.Mono, 14, 20)
    val markdownCode = TextStyle(FontFace.Mono, 16, 24)
    /** header1 is weight 900, header2–6 600; IBM Plex SemiBold draws both. */
    fun markdownHeader(level: Int) = when (level) {
        2 -> TextStyle(FontFace.SemiBold, 20, 24)
        3 -> TextStyle(FontFace.SemiBold, 16, 28)
        else -> TextStyle(FontFace.SemiBold, 16, 24)
    }
    val codeBlock = TextStyle(FontFace.Mono, 14, 20)
    val codeLanguage = TextStyle(FontFace.Mono, 12)
    val tableHeader = TextStyle(FontFace.SemiBold, 16, 24)
    val tableCell = TextStyle(FontFace.Regular, 16, 24)
    val option = TextStyle(FontFace.Regular, 16, 24)
    val imageCaption = TextStyle(FontFace.Regular, 14, 20)

    // MessageView.tsx
    val userAuthor = TextStyle(FontFace.SemiBold, 14, 20)
    val pendingStatus = TextStyle(FontFace.Regular, 11, 14)
    val agentEvent = TextStyle(FontFace.System, 14)
    val goalSent = TextStyle(FontFace.System, 14)
    val commandChip = TextStyle(FontFace.Menlo, 13)
    val unsupported = TextStyle(FontFace.System, 13)

    // ToolView.tsx
    val toolName = TextStyle(FontFace.SystemMedium, 14)
    val toolStatus = TextStyle(FontFace.System, 15)
    val toolDescription = TextStyle(FontFace.System, 13)
    val toolElapsed = TextStyle(FontFace.Menlo, 13)
    val compactActivity = TextStyle(FontFace.System, 15, 20)
    val todo = TextStyle(FontFace.System, 14)
    val taskItem = TextStyle(FontFace.SystemMedium, 14)
    val taskMore = TextStyle(FontFace.SystemItalic, 14)
    val toolOperation = TextStyle(FontFace.SystemMedium, 14)
    val toolOperationCommand = TextStyle(FontFace.System, 12)
    val executeCwd = TextStyle(FontFace.System, 12)
    val executeDescription = TextStyle(FontFace.SystemItalic, 13)
    val toolSectionTitle = TextStyle(FontFace.SystemSemiBold, 13, uppercase = true)
    val toolError = TextStyle(FontFace.System, 13)
    val codeView = TextStyle(FontFace.Menlo, 12, 18)
    // CommandView.tsx
    val terminal = TextStyle(FontFace.Menlo, 13, 18)
    val terminalPrompt = TextStyle(FontFace.MenloBold, 14, 20)
    val terminalCommand = TextStyle(FontFace.Menlo, 14, 20)

    // PermissionFooter.tsx
    // FileView
    val attachmentName = TextStyle(FontFace.SystemMedium, 13)

    val permissionButton = TextStyle(FontFace.SystemMedium, 14, 18)
    val permissionButtonSettled = TextStyle(FontFace.System, 14, 18)

    // InlineQuestionForm.tsx
    val inlineQuestionHeader = TextStyle(FontFace.SystemSemiBold, 12, uppercase = true)
    val inlineQuestionText = TextStyle(FontFace.SystemMedium, 15)
    val inlineOptionLabel = TextStyle(FontFace.SystemMedium, 14)
    val inlineOptionDescription = TextStyle(FontFace.System, 13)
    val inlineSubmit = TextStyle(FontFace.SystemSemiBold, 14)
    val inlineSubmittedHeader = TextStyle(FontFace.SystemSemiBold, 13)
    val inlineSubmittedValue = TextStyle(FontFace.System, 13)

    // AgentWorkGroupHeader.tsx
    val workGroup = TextStyle(FontFace.System, 13, 20)

    // AgentQuestionBanner.tsx
    val bannerTitle = TextStyle(FontFace.SemiBold, 14)
    val bannerSubtitle = TextStyle(FontFace.Regular, 13)
    val bannerDismiss = TextStyle(FontFace.SemiBold, 14)

    // AgentGoalBar.tsx
    val goalLabel = TextStyle(FontFace.SystemSemiBold, 12, 16)
    val goalText = TextStyle(FontFace.System, 14, 19)

    // RigActivityBar.tsx
    val rigActivity = TextStyle(FontFace.Regular, 11)

    // AgentQuestionModal.tsx
    val sheetAction = TextStyle(FontFace.Regular, 16)
    val sheetTitle = TextStyle(FontFace.SemiBold, 16)
    val sheetChip = TextStyle(FontFace.SemiBold, 12, uppercase = true)
    val sheetQuestion = TextStyle(FontFace.SemiBold, 17)
    val sheetHint = TextStyle(FontFace.Regular, 13)
    val sheetOptionLabel = TextStyle(FontFace.SemiBold, 15)
    val sheetOptionDescription = TextStyle(FontFace.Regular, 13)
    val sheetCustomLabel = TextStyle(FontFace.SemiBold, 13)
    /** A TextInput with no font family: the system font. */
    val sheetInput = TextStyle(FontFace.System, 15)
    val sheetError = TextStyle(FontFace.Regular, 14)

    // ToolFullView.tsx and the message screen
    val fullSectionTitle = TextStyle(FontFace.SystemSemiBold, 17)
    val fullDescription = TextStyle(FontFace.System, 14, 20)
    val fullError = TextStyle(FontFace.System, 14, 20)
    val fullEmptyTitle = TextStyle(FontFace.SystemSemiBold, 16)
    val fullEmptySubtitle = TextStyle(FontFace.System, 14)
    val multiEditNumber = TextStyle(FontFace.SystemSemiBold, 14)
    val multiEditBadge = TextStyle(FontFace.SystemSemiBold, 12)
    val terminalEmptyOutput = TextStyle(FontFace.MenloItalic, 13, 18)
    val detailMessage = TextStyle(FontFace.Regular, 16, 24)
    val sheetSubmit = TextStyle(FontFace.SemiBold, 16)

    // ChatFooter.tsx
    val chatFooterWarning = TextStyle(FontFace.Regular, 12)

    // ChatHeaderView.tsx, in the capsule
    val headerTitle = TextStyle(FontFace.SemiBold, 16, 20)
    val headerSubtitle = TextStyle(FontFace.Regular, 12, 14)
    /** Without glass (a tablet): the subtitle on its own 16 line. */
    val headerPlainSubtitle = TextStyle(FontFace.Regular, 12, 16)

    // InactiveArchivedHint
    val inactiveHint = TextStyle(FontFace.System, 13, 18)
    val resumeButton = TextStyle(FontFace.SystemSemiBold, 15)
    val resumeCommand = TextStyle(FontFace.Menlo, 13, 18)

    // LongPressCopyable's menu
    val copyMenuLabel = TextStyle(FontFace.Regular, 15, 20)

    // The session deleted state (SessionView.tsx)
    val deletedTitle = TextStyle(FontFace.SystemSemiBold, 20)
    val deletedBody = TextStyle(FontFace.System, 15)

    // EmptyMessages.tsx
    val emptyTitle = TextStyle(FontFace.Regular, 20)
    val emptyPath = TextStyle(FontFace.Regular, 14)
    val emptyHost = TextStyle(FontFace.SemiBold, 18)
    val emptyCreated = TextStyle(FontFace.Regular, 16, 24)

    // PendingChatPlaceholder.tsx; its folder line is emptyPath's 14
    val pendingQueued = TextStyle(FontFace.Regular, 15, 21)
    val pendingChatTitle = TextStyle(FontFace.SemiBold, 18)

    // SessionView's CLI-outdated pill: the system face, semibold 12.
    val cliWarning = TextStyle(FontFace.SystemSemiBold, 12)

    // WorktreeTabStrip.tsx: the open tab's name is semibold.
    val worktreeTab = TextStyle(FontFace.Regular, 14, 18)
    val worktreeTabSelected = TextStyle(FontFace.SemiBold, 14, 18)
}

// AgentInput.tsx and MultiTextInput.tsx (happy-ios TextStyles extension).
val TextStyles.composerInput get() = TextStyle(FontFace.Regular, 16, 22)
val TextStyles.composerChip get() = TextStyle(FontFace.Regular, 14)
val TextStyles.composerStatus get() = TextStyle(FontFace.Regular, 11)
