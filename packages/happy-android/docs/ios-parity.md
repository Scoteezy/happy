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

- [~] `:layout` module: geometry, `Measurable`, design tokens (`Palette`, `TextStyles`, `Spacing`, `Radius`; Stem Cells palette generated). Ported so far: round button, onboarding, checklists, wordmark, navigation bar, Stem Cells link, item rows/groups, grouped list, settings header, secret key card, restore, server form; the rest come with their screens
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

- [~] Home header: account glyph (dev/preview) or logo, "Sessions" + connection status, filter menu as a `PopupMenu` (Grouping, Flat List / Group by Project, Appearance Settings), settings. Evidence: `home/compare-list.png`, `home/compare-filter.png`, `home/10-filter-menu-*.png`. Settings opens the onboarding settings until Settings is ported; Appearance says not available yet; the account picture is not loaded yet (glyph only)
- [~] Home states: loading, link, offline, no sessions, list, cross-faded in 250 ms; the link state keeps the onboarding bar, the rest float the header over the content (`home/home-restore.mp4`: restore → list). No sessions and the offline checklist are built but not yet exercised on the emulator
- [~] Flat session list (live rows, archive toggle, archived by day), rows with status, draft glyph, line counts, generated avatars, faded rows when the computer is offline. Evidence: `home/compare-list.png` (vs `ios-ref/20-home-list.png`), `home/compare-offline-list.png`, `home/home-interact.mp4`, `home/home-offline.mp4`. A tap opens the chat
- [x] Project grouping (computer header, Projects, worktrees, archived chats at 0.6), expanded projects in local settings. Evidence: `home/compare-grouped.png`, `home/compare-dark-grouped.png`, `home/07-grouped.png`
- [ ] Archive by swipe and long press; the "Session" actions (details, resume, fork, duplicate, archive)
- [ ] Tasks and bots rows
- [~] Update banner and offline machines banner: offline plaque and What's new shown (`home/compare-offline-list.png`); the Play Store update check and the What's new screen are to do
- [~] Home dock: resting capsule with the placeholder and send button, its fade-in and the list scrim (`:layout` `HomeDockLayout`, focus layout, motion, fade and refusal with the iOS tests). Opening the composer, the menus, starting a session, bots and pictures are to do
- [ ] Pending chats placeholder

### Chat

- [~] Session screen shell: header (back circle with Material's arrow, title capsule with subtitle and line changes, avatar circle grey while offline) over the strong scrim at 0.80/0.96, the dock fade, `ChatScreenLayout` placing it all (`ui/chat/ChatScreen.kt`, `ChatHeaderView.kt`). Evidence: `chat/a03-chat-*.png` vs `ios-ref/30-chat.png`, `chat/a07-top-*.png` vs `ios-ref/32-chat-top.png`, dark `chat/a21-dark-*.png`. Info opens a not-ported alert; the CLI-outdated pill, inactive hint and git status (live branch/lines over RPC) are to do
- [~] Inverted list engine: rows planned off main (one planner thread, plans cached per row and width), drawn from their plans in a reverse-layout RecyclerView; newest edge follows within 200dp, the reader's row holds otherwise, an opened or closed tool keeps its top (pinning), the next page loads within a screen of the oldest row, jump-to-newest button past 300dp, status row hysteresis 48/16 (`ChatListView.kt`). Evidence: `chat/chat-keyboard-send.mp4`, `chat/chat-tool-expand.mp4`. iOS's single-block follow slide (`ChatFollowMotion`) is the item animator's per-row moves here
- [~] Keyboard: the dock rides the IME frame by frame (`ScreenStack`'s `WindowInsetsAnimation` into `ChatScreenLayout.keyboardTop`), and the newest edge stays put as the inset changes (`visibleMinYAfterNewestInsetChange`). Evidence: `chat/a09-kb-*.png`, `chat/chat-keyboard-send.mp4`. Interactive dismiss by dragging the list is to do
- [~] Messages: user bubbles (bubble colour setting, pending at 0.45, failed + retry), agent text with copy and the copied tick, events. Evidence: `chat/a03-chat-*.png`
- [~] Markdown: headers, paragraphs, bold/italic/code spans, links, numbered and bulleted lists, tables and code blocks scrolling sideways, syntax highlighting, options (`MarkdownLayout` with its iOS tests). Evidence: `chat/a06-top-*.png`, `chat/and-chat-ab.png`. Mermaid draws as a code block and images as a placeholder box for now
- [ ] Text selection screen, long-press copy
- [~] Tool calls: compact rows (eye/terminal glyphs, grey), Edit card, tap to open/close (pinned), Bash terminal panel, raw Input/Output sections, errors, running spinner and timer. Evidence: `chat/a19-bash-open-*.png`, `chat/a20-edit-open-*.png`, `chat/chat-tool-expand.mp4`. The full tool view screen is to do
- [ ] Tool views: Bash (terminal panel done), Edit, MultiEdit, Write (diff views not ported: they show raw Input/Output), Todo, Task, MCP, Codex, Gemini, file, exit plan, questions
- [~] Permission prompts: the footer under a waiting tool (Yes / Yes, don't ask again for this tool / No, and provide feedback; the Codex, edit and plan sets), the tapped button pulsing while its answer is on its way with no other taking a tap, the answer through `SessionOps.allow`/`deny` and the mode switch after it, the answered button shaded and the rest faded; the status reads "permission required" and the primary turns to the black "!" (`ChatRowRenderer.permissionFooter`, `chat/tools/PermissionOptions.kt`). Evidence: `chat/b02-permission-*.png` vs `ios-ref/33-permission.png`, `chat/b07-answering.png`, `chat/b08-approved.png` vs `ios-ref/36-approved.png`, `chat/chat-permission-approve.mp4` (the seeded `perm-demo` session answers over RPC). A list taller than its cap does not scroll yet, and the tablet layout is to do
- [ ] Agent questions: banner, sheet with footer above the keyboard, inline form
- [ ] Image and file messages
- [ ] Message detail screen
- [ ] Goal bar, work group headers, rig activity bar
- [ ] Empty and pending states
- [ ] Worktree tab strip
- [~] Composer: status row (online / thinking word / permission / last seen, pulsing dot), growing input (`AgentInputCardLayout`), send / stop / blocked / voice primary, permission, model and effort chips opening the platform's popup menu with the current pick checked, picks shown at once and written through `SessionComposer`, drafts synced, shake on refusal (`ui/composer/`). Evidence: `chat/a10-typed-*.png`, `chat/a11-sent-*.png`, `chat/a15-perm-menu-*.png`, `chat/a16-model-menu-*.png`, `chat/a12-model-menu-*.png`. The usage row, the full-screen editor, voice and attachments are to do
- [ ] Autocomplete: slash commands, file mentions
- [ ] Attachments: paste, photo picker, strip, limits and alerts

### Settings

- [ ] Settings index
- [ ] Account: profile, public id, secret key backup, linked devices, GitHub, logout, delete
- [ ] Account switcher
- [ ] Appearance
- [ ] Language
- [ ] Agents
- [ ] Voice settings, voice language
- [ ] Connect Claude
- [ ] What's new
- [ ] Support us
- [ ] Push notification rows

### Secondary

- [ ] Session info (metadata, machine, path, archive, delete, duplicate sheet, fork, resume)
- [ ] Machine screen
- [ ] New session screen
- [ ] Changes with diffs, file viewer
- [ ] Message detail, text selection

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
