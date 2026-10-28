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
- [~] Components: `ItemRowView`, `ItemGroupView`, `GroupedListView`, `RoundButton`, `NavigationBar`, alerts (platform dialogs), `SegmentedControl`, `TextField`, `QrCodeView` done; checklist, terminal block, avatar, bot faces to do

### Onboarding and sign-in

- [~] Welcome: wordmark, title, value props, Create account, Restore, Get help, Relay | Direct switch (dev/preview). Evidence: `onboarding/01-stem-link-relay.png`, `02-stem-link-direct.png` (vs `ios-ref/04-welcome.png`). Production Welcome's "Pair computer" bar button waits for Direct pairing
- [ ] Create account
- [ ] Restore with QR (shows the code), restore with the secret key
- [~] Custom server screen (`onboarding/03-server.png`); onboarding settings to do (the link screen's gear opens the server meanwhile)
- [ ] Link Your Computer, offline checklist, troubleshoot (selectable terminal lines)
- [ ] Connect a terminal by QR or URL; `happy://terminal` deep links
- [ ] Server confirmation for imported accounts

### Home

- [ ] Home header: logo/avatar, "Sessions" + connection status, filter menu, settings
- [ ] Home states: loading, link, offline, no sessions, list
- [ ] Flat session list (live rows, archive toggle, archived by day), rows with status, draft glyph, line counts, pictures
- [ ] Project grouping, expanded projects
- [ ] Archive by swipe and long press; the "Session" actions (details, resume, fork, duplicate, archive)
- [ ] Tasks and bots rows
- [ ] Update banner and offline machines banner
- [ ] Home dock: capsule, opening composer, machine/project/worktree/harness menus, start session, bots, pictures
- [ ] Pending chats placeholder

### Chat

- [ ] Session screen shell: header (title capsule, avatar, status, git status), back, info, CLI-outdated pill, inactive hint
- [ ] Inverted list engine: off-main measured rows, manual frames, animated arrival/growth, scroll position keeping, older pages
- [ ] Keyboard: composer rides the IME (`WindowInsetsAnimation`), interactive dismiss, list insets
- [ ] Messages: user bubbles + delivery status, agent text, events, unsupported placeholder
- [ ] Markdown: blocks, spans, links, lists, tables, code with syntax highlighting, mermaid, copy
- [ ] Text selection screen, long-press copy
- [ ] Tool calls collapsed by default; headers, sections, errors, full view
- [ ] Tool views: Bash, Edit, MultiEdit, Write, Todo, Task, MCP, Codex, Gemini, file, exit plan, questions
- [ ] Permission prompts
- [ ] Agent questions: banner, sheet with footer above the keyboard, inline form
- [ ] Image and file messages
- [ ] Message detail screen
- [ ] Goal bar, work group headers, rig activity bar
- [ ] Empty and pending states
- [ ] Worktree tab strip
- [ ] Composer: growing input, send/stop, drafts, chips (permission, model, effort), status rows
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
