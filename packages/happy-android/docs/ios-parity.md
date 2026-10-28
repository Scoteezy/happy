# iOS parity (Android Views port)

The Android app is being rebuilt to match `packages/happy-ios` (UIKit) 1:1,
with Android Views instead of Jetpack Compose. This file is the checklist:
every iOS screen, feature and flow, the Android plan for it, and its status.
The iOS app is the behavioural reference; its own checklist is
`packages/happy-ios/docs/FEATURES.md` (which maps to happy-app).

Status: `[ ]` not started, `[~]` partly done, `[x]` done and verified on the
emulator. Evidence (screenshots, screen recordings) is kept out of git under
`.context/evidence/<feature>/` at the repository root; the path is listed per
item once it exists.

## Architecture decision

| Layer | iOS | Android | Source |
| --- | --- | --- | --- |
| Crypto | `HappyCrypto` | `:crypto` (pure Kotlin, BouncyCastle low level) | kept from the old port |
| Wire, HTTP, socket | `HappyWire`, `HappyCore/Networking`, `Realtime` | `app/net`, `app/sync` (`SocketIoClient`, `SyncApi`) | kept |
| Store | `HappyStore` (GRDB) | `app/data` (framework SQLite, WAL) | kept; per-account stores still to do |
| Sync, outbox, presence, ops | `HappyCore/Sync`, `Presence`, `Sessions` | `app/sync` | kept |
| Reducer, chat model | `HappyCore/Chat` | `app/chat` | kept |
| Diff, syntax | `HappyCore/Diff` | `app/diff` | kept |
| Settings model | `HappyCore/Settings` | `app/model/settings` | kept |
| Layout (pure frames + tokens) | `HappyLayout` (pure Swift, exact-frame tests) | **`:layout`** (pure Kotlin/JVM, exact-frame JUnit tests) | new, ported from `HappyLayout` |
| Icons | `HappyIcons` (font glyph outlines) | **`:icons`** (same packed outlines, decoded to `android.graphics.Path`) | new, generated from the iOS tables |
| UI | `HappyUI` (UIKit, manual frames) | **`app/ui`** (Android Views: custom `View`/`ViewGroup`, `onMeasure`/`onLayout` apply frames from `:layout`, `RecyclerView`, `StaticLayout` text) | new; the Compose UI is removed |
| Navigation | `UINavigationController` + `Route` | `ui/nav` `ScreenStack` (single Activity, `Screen` objects, push/pop with the system back and predictive back gesture) | new |

Rules carried over from iOS (see `AGENTS.md`):

- No Jetpack Compose. Views only, measured and laid out by hand.
- Layout decisions live in `:layout` as functions from inputs (container
  size, insets, measured sizes) to frames, with exact-frame tests. Views only
  measure (`StaticLayout`) and apply frames.
- Sync, decryption, reducer, markdown, row derivation, diffing and text
  measurement never run on the main thread; the main thread applies a minimal
  changeset.
- Telegram for Android (GPL-2.0) is a reference for techniques only; no code
  is copied.
- Platform idioms where iOS uses its own: no liquid glass (solid surfaces with
  elevation instead), the system back gesture and predictive back, ripples,
  edge-to-edge insets, `PopupMenu`/bottom sheets for iOS menus and sheets,
  the Android photo picker, Google Code Scanner for QR.

## Build order

1. Foundation: `:layout`, `:icons`, fonts, tokens, text measurement, the
   screen stack, shared components (Item rows and groups, RoundButton,
   header, alerts).
2. Onboarding and sign-in.
3. Home.
4. Chat (list engine, rows, markdown, tools, permissions, questions,
   composer, keyboard).
5. Settings.
6. Secondary screens (session info, machine, new session, changes, file
   viewer, message detail, text selection).
7. Accounts (several signed in), push, deep links.
8. Stem Cells, Direct pairing, Stories (development and preview builds
   only on iOS), voice UI, tablet shell.

## Checklist

### Foundation

- [~] `:layout` module: geometry, `Measurable`, design tokens (`Palette`, `TextStyles`, `Spacing`, `Radius`; Stem Cells palette generated). Ported so far: round button, onboarding, checklists, wordmark, navigation bar, Stem Cells link, item rows/groups, grouped list, settings header, secret key card, restore, server form, settings pages (profile header, dropdown and options, bubble preview, connect command, changelog); the rest come with their screens
- [x] Session list sync: `changedSince` deltas, live `delete-session`, and a full listing that drops sessions deleted elsewhere. That listing runs on first sync and daily, as on iOS, and also after every socket connect (`SyncEngine.requestFullListing`). The last is a deliberate departure: iOS keeps a session deleted while the app was offline until its next daily reconcile, which showed as duplicates on Home. Evidence: `session-deletion/before-after-reconnect.png` (stale copies gone on connect), `session-deletion/live-delete.png`
- [x] `:icons` module: Ionicons/Octicons/etc. outlines from the iOS generated tables, `GlyphOutline` decoder
- [x] Fonts: IBM Plex Sans/Mono, Bricolage Grotesque; `TextStyle` → `TextPaint`, exact line heights
- [ ] Text measurement off the main thread (`StaticLayout`), font scale as RN scales it
- [x] `ScreenStack` navigation with push/pop animation and predictive back (`BarScreen` + Material app bar)
- [~] Theme: light/dark from `themePreference`, edge-to-edge insets, system bar icons follow the app theme
- [~] Components: `ItemRowView`, `ItemGroupView`, `GroupedListView`, `RoundButton`, `NavigationBar`, alerts (platform dialogs), `SegmentedControl`, `TextField`, `QrCodeView`, terminal block, `AvatarView` (brutalist, gradient, pixel, bot chip, picture, harness badge) done; bot faces to do

### Onboarding and sign-in

- [~] Welcome: wordmark, title, value props, Create account, Restore, Get help, Relay | Direct switch (dev/preview). Evidence: `onboarding/01-stem-link-relay.png`, `02-stem-link-direct.png` (vs `ios-ref/04-welcome.png`). Production Welcome's "Pair computer" bar button waits for Direct pairing
- [x] Create account (against the local `happy-server standalone:dev`; `onboarding/onboarding-flow.mp4`, `09-signed-in-home.png`)
- [~] Restore with QR (shows the code, `06-restore-qr.png`), restore with the secret key (`07-restore-key.png`, bad key `08-restore-invalid.png`). Approval from a second device not exercised yet
- [x] Custom server screen (`onboarding/03-server.png`, confirm `04-server-confirm.png`, custom `05-server-custom.png`, in `onboarding-flow.mp4`); onboarding settings: server, Direct connection toggle, "Log out and start over" with its confirmation (`home/03-onboarding-settings.png`)
- [~] Link Your Computer (`home/02-link.png`: checklist ticks kept in local settings, Scan enabled once both are ticked, paste link, approve the terminal, busy until the computer arrives or 10 s, inline failure line). Offline checklist built (title per computer count, terminal block, Troubleshoot, Link another, Show archived) but not yet seen on the emulator; troubleshoot screen to do. Terminal lines are not selectable yet
- [ ] Connect a terminal by QR or URL; `happy://terminal` deep links
- [ ] Server confirmation for imported accounts

### Home

- [~] Home header: account glyph (dev/preview) or logo, "Sessions" + connection status, filter menu as a `PopupMenu` (Grouping, Flat List / Group by Project, Appearance Settings), settings. Evidence: `home/compare-list.png`, `home/compare-filter.png`, `home/10-filter-menu-*.png`. Settings and Appearance open their screens; the account picture is not loaded yet (glyph only)
- [~] Home states: loading, link, offline, no sessions, list, cross-faded in 250 ms; the link state keeps the onboarding bar, the rest float the header over the content (`home/home-restore.mp4`: restore → list). No sessions and the offline checklist are built but not yet exercised on the emulator
- [~] Flat session list (live rows, archive toggle, archived by day), rows with status, draft glyph, line counts, generated avatars, faded rows when the computer is offline. Evidence: `home/compare-list.png` (vs `ios-ref/20-home-list.png`), `home/compare-offline-list.png`, `home/home-interact.mp4`, `home/home-offline.mp4`. A tap opens the chat
- [x] Project grouping (computer header, Projects, worktrees, archived chats at 0.6), expanded projects in local settings. Evidence: `home/compare-grouped.png`, `home/compare-dark-grouped.png`, `home/07-grouped.png`
- [ ] Archive by swipe and long press; the "Session" actions (details, resume, fork, duplicate, archive)
- [ ] Tasks and bots rows
- [~] Update banner and offline machines banner: offline plaque and What's new shown (`home/compare-offline-list.png`); What's new opens the changelog; the Play Store update check is to do
- [~] Home dock: resting capsule with the placeholder and send button, its fade-in and the list scrim (`:layout` `HomeDockLayout`, focus layout, motion, fade and refusal with the iOS tests)
- [~] Dock composer (`ui/home/HomeDockController.kt`, `HomeDockFocusView.kt`): opens over home with the dim and a faint blur (RenderEffect, Android 12+), the shell growing from the capsule and the rows and card revealing in turn (`HomeDockFocusMotion`), rides the IME, closes on the backdrop or Back. Machine, project (with Enter custom path), worktree and harness pickers and the permission, model and effort chips open `ChoiceMenu` (iOS's inline-section UIMenu as a popup window that keeps the keyboard up); Send runs `SessionStarter` with the progress line, the stop hint, Stop, refusal shakes and flash, then opens the chat. Project + and No Sessions' Start New Session open it too. Evidence: `dock/d12-open.png`, `d20-project-menu.png`, `d22-starting.png`, `d23-chat.png`, `dock-open-menus.mp4`, `dock-start-session.mp4` vs `ios-ref/41-dock-open.png` … `45-dock-harness-menu.png`. Bots (Session | Bot, faces), tasks and pictures are to do; the text heights are measured on the main thread
- [ ] Pending chats placeholder

### Chat

- [~] Session screen shell: header (back circle with Material's arrow, title capsule with subtitle and line changes, avatar circle grey while offline) over the strong scrim at 0.80/0.96, the dock fade, `ChatScreenLayout` placing it all (`ui/chat/ChatScreen.kt`, `ChatHeaderView.kt`). Evidence: `chat/a03-chat-*.png` vs `ios-ref/30-chat.png`, `chat/a07-top-*.png` vs `ios-ref/32-chat-top.png`, dark `chat/a21-dark-*.png`. Info opens a not-ported alert; the CLI-outdated pill and git status (live branch/lines over RPC) are to do
- [~] Inverted list engine: rows planned off main (one planner thread, plans cached per row and width), drawn from their plans in a reverse-layout RecyclerView; newest edge follows within 200dp, the reader's row holds otherwise, an opened or closed tool keeps its top (pinning), the next page loads within a screen of the oldest row, jump-to-newest button past 300dp, status row hysteresis 48/16 (`ChatListView.kt`). Evidence: `chat/chat-keyboard-send.mp4`, `chat/chat-tool-expand.mp4`. iOS's single-block follow slide (`ChatFollowMotion`) is the item animator's per-row moves here
- [~] Keyboard: the dock rides the IME frame by frame (`ScreenStack`'s `WindowInsetsAnimation` into `ChatScreenLayout.keyboardTop`), and the newest edge stays put as the inset changes (`visibleMinYAfterNewestInsetChange`). Evidence: `chat/a09-kb-*.png`, `chat/chat-keyboard-send.mp4`; with animations on, opening and closing at the newest edge and scrolled up keep the distance from the newest edge (`keyboard/k-strip.png`, `keyboard-open-close.mp4`; closing used to scroll the conversation up by the keyboard height). Interactive dismiss by dragging the list is to do
- [~] Messages: user bubbles (bubble colour setting, pending at 0.45, failed + retry), agent text with copy and the copied tick, events. Evidence: `chat/a03-chat-*.png`
- [x] Markdown: headers, paragraphs, bold/italic/code spans, links, numbered and bulleted lists, tables and code blocks scrolling sideways, syntax highlighting, options (`MarkdownLayout` with its iOS tests). Evidence: `chat/a06-top-*.png`, `chat/and-chat-ab.png`. Mermaid diagrams render with the bundled mermaid.js (same page as iOS) in a sandboxed WebView that reports its height back to the row, and re-render on theme change; markdown images (http(s) through Coil, `data:` inline) draw contained in their rounded box with the alt text below. Evidence: `images-mermaid/a01-light.png`, `a02-dark.png`, `mermaid-image.mp4`, iOS `ios-ref/77-mermaid.png`
- [ ] Text selection screen, long-press copy
- [~] Tool calls: compact rows (eye/terminal glyphs, grey), Edit card, tap to open/close (pinned), Bash terminal panel, raw Input/Output sections, errors, running spinner and timer. Evidence: `chat/a19-bash-open-*.png`, `chat/a20-edit-open-*.png`, `chat/chat-tool-expand.mp4`. An opened body (a card's or a compact row's box) and a header with nothing to open lead to the message screen
- [~] Tool views: Bash's terminal panel; Edit, Write, MultiEdit and Gemini edits as DiffChunk (the shared diff engine, Prism colours within the first second, word emphasis, +/− gutter, line numbers from the setting, sideways scroll for long lines, "Show N more lines" past 80 rows) flush under the card's header (`ui/diff/`, `:layout` `diff/DiffLayouts.kt` with iOS's `DiffLayoutTests`). Evidence: `chat/c01-edit-diff.png` vs `ios-ref/37-edit-diff.png`, `chat/chat-edit-diff.mp4`. TodoView (☐/☑ by status, the result's newTodos first, done ones struck through), TaskView (the sub-agent's last three calls with spinner/✓/✕ and "+N more tools"), ExitPlanToolView (the plan as markdown), CodexBashView (a read or write as "Reading <file>" in a box, else the command), GeminiExecuteView (command, 📁 cwd, description); a view with nothing to show leaves the card closed (iOS hasContent). Seeded as tools-demo (an Agent with five child calls, two TodoWrites, an MCP call, ExitPlanMode). Evidence: `tool-views/pair-android-ios.png`, `tool-views/a01..a03` vs `ios-ref/70-72`. Codex patch/diff still show raw Input; hunks past the first second stay plain until the row is planned again
- [~] Permission prompts: the footer under a waiting tool (Yes / Yes, don't ask again for this tool / No, and provide feedback; the Codex, edit and plan sets), the tapped button pulsing while its answer is on its way with no other taking a tap, the answer through `SessionOps.allow`/`deny` and the mode switch after it, the answered button shaded and the rest faded; the status reads "permission required" and the primary turns to the black "!" (`ChatRowRenderer.permissionFooter`, `chat/tools/PermissionOptions.kt`). Evidence: `chat/b02-permission-*.png` vs `ios-ref/33-permission.png`, `chat/b07-answering.png`, `chat/b08-approved.png` vs `ios-ref/36-approved.png`, `chat/chat-permission-approve.mp4` (the seeded `perm-demo` session answers over RPC). A list taller than its cap does not scroll yet, and the tablet layout is to do
- [~] Agent questions: the inline form for AskUserQuestion (answered through the permission with the answers merged into the input) and request_user_input (through the communication): header chips, radio and checkbox options, Submit Answer outlined once every question has an answer, a spinner while it goes, the "Header: answer" summary after, the form back if sending fails (`chat/InlineQuestionForm.kt`, `ui/chat/ChatQuestionRenderer.kt`). Evidence: `chat/q01-question.png` vs `ios-ref/38-question.png`, `chat/q02-picked.png`, `chat/q03-submitting.png`, `chat/q04-answered.png`, `chat/chat-question-answer.mp4` (the seeded `ask-demo` session answers over RPC)
- [x] Image and file messages: FileView (the picture at its own shape, at most 280x360, rounded 8, its name under it on one line), downloaded and decrypted with the session blob key, decoded at no more than it draws, kept per account (50 pictures / 48MB LRU, one load per picture, dropped on memory pressure), the thumbhash (`images/ThumbHash.kt` with iOS's vectors) under it, a 150ms fade in, an alert badge when it cannot be had (`ui/chat/ChatAttachmentView.kt`, `attachments/AttachmentPictures.kt`). Seeded as photo-demo (a real encrypted upload). Evidence: `attachments/a02-loaded.png`, `attachment-open.mp4` and offline `a03-offline-thumbhash-badge.png` (the thumbhash under the alert badge) vs `ios-ref/78-attachment.png`
- [~] Message detail screen (session/[id]/message/[messageId].tsx with ToolFullView): titled by ToolHeader (the known title unless the provider named the call, a terminal tool's command, cut in the middle); a terminal tool's CommandView with all its output, bash and shell-session colours on the dark panel within the reveal deadline, "[Command completed with no output]", a shell control's typed input; TaskView; Edit/MultiEdit ("Edit N of M", the Replace All badge, rules between)/Write diffs edge to edge on a phone; otherwise Description, Input Parameters, Output, Error or "Tool completed successfully"; a specialized view's Output or error after it; Raw JSON in debug builds or dev mode, keys sorted as on iOS; a text message's whole text; long text in pieces (`TextChunks`); long press copies a command or its output; a message that's gone sends the reader back (`chat/tools/ToolFullPresentation.kt` and `:layout` `MessageDetailLayout.kt` with the iOS tests, `ui/chat/MessageDetailRenderer.kt`, `MessageDetailScreen.kt`). Evidence: `message-detail/pair-bash-android-ios.png` (vs `ios-ref/74-bash-detail.png`), `message-detail/a01-task.png`, `a03-mcp-generic.png`, `a04-edit-wide.png`, `a05-mcp-open.png` vs `ios-ref/75-mcp-open.png`, `message-detail/message-detail.mp4`. Codex patch and unified-diff calls fall back to the generic sections; Edit, MultiEdit and Write open the message here where iOS opens the file screen; the text keeps its place across text sizes only from the top (`MessageDetailReadingPlace` is ported but not wired)
- [~] Question banner and answer sheet (AgentQuestionBanner, AgentQuestionModal): the first request the chat can't show inline sits above the composer; a form's card (link-coloured rim, chevron) opens the full-screen sheet, which rises as a modal (`Screen.isModal`): header chips with the orange required dot, checkbox options ringed while picked, the written answer growing with its text, Send answer greyed until every required question is answered and a spinner while it goes, an error under the questions if it fails, Cancel and back dismiss the request, an answer from elsewhere closes it. An unknown kind shows its notice and Dismiss (`ui/chat/ChatDockBarViews.kt`, `AgentQuestionSheetScreen.kt`, `:layout` `QuestionSheetLayout` with the iOS tests). Evidence: `chat-bars/a02h.png` vs `ios-ref/51-form-banner.png`, `chat-bars/a03h.png` vs `ios-ref/52-form-sheet.png`, `chat-bars/a04h.png` vs `ios-ref/53-form-picked.png`, `chat-bars/pair56.png` (sending, then the seed's answer) vs `ios-ref/55-form-answered.png`, `chat-bars/a21h.png` vs `ios-ref/56-unsupported-banner.png`, `chat-bars/question-banner-sheet.mp4` (seeded `form-demo` and `notice-demo`). The bars are measured on the main thread, not off it as on iOS, and a bar coming or going moves the composer without iOS's 0.25s animation
- [~] Goal bar, inactive hint, rig activity bar: the current goal with Edit (a prompt), Stop and Clear, each spinning while its `goal-action` RPC runs; "This session is inactive." with Resume (the shared `SessionResumer`, a spinner, an error alert) or the terminal commands, which a tap copies with a tick for two seconds; Happy Agent's running counts full width; all fade while the reader is scrolled back (`chat/ChatDockBars.kt` with its tests, `ui/chat/ChatDockBarViews.kt`). Evidence: `chat-bars/a09h.png` vs `ios-ref/57-goal-bar.png`, `chat-bars/pair1112.png` (edit in flight, then edited), `chat-bars/a13h.png` (cleared), `chat-bars/pair1617.png` (commands; Resume), `chat-bars/pair1819.png` (resuming, then online again through the seed's `resume-happy-session`). No seeded session shows the rig activity bar yet
- [~] Work group headers: with `groupToolCalls` on, a finished turn's work folds under "Worked 2m3s ›" where its oldest member was; a tap opens it, the rows in reading order and "Hide ⌃" under them, and the turn being watched stays open (`chat/WorkGroups.kt` with its tests, `ChatRowRenderer.workHeader`). Evidence: `chat-bars/work-groups.png` (folded, opened, Hide), the seed run with `--setting groupToolCalls=true`. The expansion does not yet fold when the app goes to the background (`WorkGroupExpansion.backgrounded`)
- [ ] Empty and pending states
- [ ] Worktree tab strip
- [~] Composer: status row (online / thinking word / permission / last seen, pulsing dot), growing input (`AgentInputCardLayout`), send / stop / blocked / voice primary, permission, model and effort chips opening the platform's popup menu with the current pick checked, picks shown at once and written through `SessionComposer`, drafts synced, shake on refusal (`ui/composer/`). Evidence: `chat/a10-typed-*.png`, `chat/a11-sent-*.png`, `chat/a15-perm-menu-*.png`, `chat/a16-model-menu-*.png`, `chat/a12-model-menu-*.png`. The usage row, the full-screen editor and voice are to do
- [ ] Autocomplete: slash commands, file mentions
- [x] Attachments: + asks Paste from Clipboard / Photo Library (`ui/composer/ComposerImagePicker.kt`); the library is the system photo picker (no storage permission; multi-select up to what is left of 20); a picture pasted into the text or inserted by the keyboard is attached too (`OnReceiveContentListener`); each is made an upright JPEG (long edge 3072, 0.92) in a per-composer staging folder; the strip of 64dp thumbnails with remove buttons over the text, + turning blue (`AttachmentStripView.kt`); limit, too-large, nothing-to-paste, not-supported and upload-failed alerts; Send encrypts and uploads them, then queues the file events ahead of the text. Evidence: `attachments/b01-add-dialog.png` … `b06-nothing-to-paste.png`, `composer-attach-send.mp4`. As on iOS, Send copies the pictures into the outbox (`attachments` table, files under `files/outbox`) and queues their file events, naming no blob yet, ahead of the text; the flush uploads each (sealed with the session blob key) before anything after it goes, tried 5 times 1 s doubling to 60 s apart, and one that fails for good is skipped so the text still goes, the composer saying so once (`newlyFailedAttachments`); Retry tries it again with its attempts cleared. A send made offline keeps its pictures and they go when the connection is back. Evidence: `attachments/c-pair-offline-online.png`, `c02-online-sent.png`, `offline-send.mp4`; `MessageSenderTest` (upload retried at flush, refused picture skipped then retried), `ChatFeedTest` (the row rewritten in place shows its ref)

### Settings

- [x] Settings index (`ui/settings/SettingsScreen.kt`, iOS `SettingsViewController`): the HAPPY logo card (or the profile header), scan / enter terminal URL, Support us, Connected Accounts (Claude Code, GitHub with connect and disconnect), Features (Account, Appearance, Agents, Voice Assistant), About (What's New, GitHub, Report an Issue, Privacy, Terms, Version; ten taps on Version toggle dev mode). EULA is iOS-only and left out. Every pushed bar is opaque in the grouped background, as `AppCoordinator.applyAppearance` makes them. Evidence: `settings/01-index-top.png`, `02-index-mid.png` vs `ios-ref/81-settings-top.png`, `81-settings-mid.png`; `settings/settings-walk.mp4`
- [~] Account: account information (analytics and public id, long press copies), Link New Device, profile (name, GitHub with picture), connected services with disconnect, Backup (reveal, the secret key card, tap to copy with the Success alert), Analytics, push rows, Logout. Evidence: `settings/10-account.png`, `11-account-bottom.png`, `13-account-secret.png` vs `ios-ref/82-account*.png`. The Accounts group (switcher, Add Account) comes with several accounts (Platform)
- [ ] Account switcher (with several accounts, Platform)
- [x] Appearance: theme cycle, language, quota and bubble colour (the inline dropdown with previews), avatar style and black-and-white, usage status, grouping, compact tool calls, file diffs, group tool calls and the rest of the display switches; the switches are the platform's. iOS's Home and Chat list renderer rows are iOS-only and left out. Evidence: `settings/20-appearance.png`, `21-appearance-bottom.png`, `22-appearance-bubble-open.png` vs `ios-ref/83-appearance*.png`. Home's filter menu opens it
- [x] Language: Automatic plus the ten catalogs, a checkmark, the restart confirmation; the choice applies through the activity's configuration. Evidence: `settings/80-language.png`
- [~] Agents: machines (online and offline, show offline), Clear Overrides, per-agent permission, model and effort with the inline options and the custom Codex model, computed off main. Evidence: `settings/30-agents.png` vs `ios-ref/84-agents.png`. A machine row does not open the machine screen yet (Secondary)
- [x] Voice settings and voice language: usage (spinner, then the unavailable row that retries), Upgrade Voice, preferred language with the searchable list, ElevenLabs agent id, direct connection. Evidence: `settings/40-voice.png`, `41-voice-later.png` vs `ios-ref/85-voice.png`. The bring-your-own footer is happy-app's English catalog text, longer than iOS's inline English
- [x] Connect Claude (`settings/70-connect-claude.png`)
- [x] What's new: the bundled changelog planned off main, bold and links, long press copies; opening it marks the latest entry read (home's What's new banner opens it too). Evidence: `settings/50-whatsnew.png` vs `ios-ref/87-whatsnew.png` (iOS's bar shows only the picture; the title view's label is there in its source, so Android shows both)
- [x] Support us: iOS's interim page with Google Play for managing subscriptions. Evidence: `settings/60-support.png` vs `ios-ref/86-support.png`
- [x] Push notification rows: permission, request again, re-register, registered tokens with delete; the copy says Android where happy-app says iOS

### Secondary

- [ ] Session info (metadata, machine, path, archive, delete, duplicate sheet, fork, resume)
- [ ] Machine screen
- [ ] New session screen
- [ ] Changes with diffs, file viewer
- [~] Message detail (see Chat), text selection

### Platform

- [ ] Several accounts with separate stores (`accounts/<id>/`)
- [ ] Push: registration, presentation, open from a notification
- [ ] Deep links
- [ ] Light/dark theme
- [ ] Font scale (Dynamic Type equivalent)
- [ ] Tablet shell (sidebar + detail)
- [ ] Voice UI

### Development and preview builds only (iOS `StemCellsPreference`)

- [ ] Stem Cells home: sidebar, rows, hold preview, new work
- [ ] Stem Cell chat, header, picker panels, attach sheet, session detail with shared media
- [ ] Direct pairing (Relay | Direct), Direct home
- [ ] Stories row, viewer, editor, viewers sheet
