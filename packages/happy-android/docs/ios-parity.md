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

## Patch and unified-diff verification (2026-10-30)

- Source `dbb6542cf` adds dedicated Codex/Gemini/apply-patch chat and full
  detail views: file headers/counts/kinds/moves, focused-file navigation with
  original diff indices, native rounded cards, named/headerless unified diffs,
  and malformed/error fallback. Empty diffs remain closed.
- Fresh: **69 focused tests / 12 suites**, zero failures/errors/skips, isolated
  APK assembly, and four real-MainActivity scenarios under
  `.context/evidence/codex-patches/android-first/`. The main recording is
  `files/android-files.mp4` (21.820667s): expand → focused `b-long.kt` → Show More
  (hidden80→0, chunk1 retained) → Back. Four fixture teardowns and 14 XMLs pass.
  The new fixture wiring also passes ordinary-debug assembly and package /
  manifest / dex exclusion checks; this was not a repeated production gate.
- Native source plus partial reference evidence establishes the comparison;
  its focused-file/Show More/Back flow passed, but the four-case UI suite did
  not finish. See the audit for the native automation limitation. No new
  native retry campaign, full suite or unsigned production rebuild was run
  after the user requested lean checks. Last broad-verified source remains
  `55be0e4ed`; final broad verification is deferred to the remaining batch's
  handoff. No live service, release or physical-device claim is made.

## Message-detail reading-position verification (2026-10-30)

- Source `55be0e4ed` wires the existing native reading-place calculation to
  the actual message screen. Font changes preserve the current text-piece
  fraction; width-only changes keep the offset. Capture happens when a new
  plan applies, so scrolling during measurement is respected. New child
  bounds precede clamping; stale text environments and destroyed owners
  cannot apply completed work.
- `.context/evidence/message-reading/android-final/android-message-reading.mp4`
  (**18.640933s**) records the real MainActivity message detail with 140
  synthetic lines, real swipes and system font scale 1 → 1.35 → 1. The same
  piece is retained within **0.137dp** on enlargement and **0.068dp** on
  restoration. Four UI XML files parse; fixture/account/scope teardown,
  original font setting and owned emulator exit are verified.
- The frozen native controller passes **one UI test / 21.629s**, with four
  screenshots and `native-first/ios-message-reading.mp4` (**39.543333s**).
  Its own anchor is retained within **0.527pt / 0.074pt** over actual system
  content-size changes. The platforms reach different scroll positions with
  their swipes; this compares each reading anchor, not identical viewport
  contents. Native detail is the fixture navigation root, so this is not
  back-navigation evidence. Both movies and final screenshots were inspected;
  videos keep original timing and runner waits. All networking is synthetic.
- Focused coverage is **15 actual widget/screen tests + 5 pure layout tests**.
  Fresh five-module verification passes **2041 tests / 315 suites**, zero
  failures/errors/skips, in **2m55s**. Ordinary debug and genuine no-harness
  unsigned production (**4m28s**, including R8/lint) pass fixture isolation,
  both JNI ABIs and 16KB ZIP / 16KiB ELF LOAD alignment. Hashes and earlier
  fixture-only corrections are in the audit. All owned workers/devices exited;
  no signing, publication, live service or physical-device claim is made.

## Imported-account server verification (2026-10-30)

- Source `38bc22c63` imports RN's Android MMKV server configuration read-only
  and off main. Unknown or damaged configuration keeps the account pending,
  with no exposed credentials or authenticated consumers. The initially empty
  native-style server form validates, asks Continue, and persists the exact
  pending account's host before activation. Cancel and failed saves stay
  offline. Known confirmed accounts retain their binding across switching,
  restart and later RN changes; the global onboarding candidate is not used
  to infer an imported account's host.
- Authenticated clients now capture credentials and confirmed host from the
  same account snapshot. Actual-graph regression tests hold old sync and push
  work across account activation, including waits before URL construction,
  in-flight HTTP, device/Expo token acquisition, retries and inactive logout.
  Two independent synthetic servers assert no crossed credentials. Pending
  restore, replacement, failed persistence and confirmed restart also pass.
- `.context/evidence/relay-import-server/android-final/android-import-server.mp4`
  (**85.557889s**) shows the actual form, empty/invalid errors, confirmation,
  Cancel, forced save failure and successful retry to Home. Both recording
  servers remain at zero HTTP/socket authentication through Cancel and failure.
  After confirmation, only the selected server receives requests: 9
  authenticated HTTP requests and 1 socket authentication, with no auth probe.
  RN source bytes stay unchanged and the selected host is durable. Eight UI
  XML files parse; fixture teardown and owned emulator exit are verified.
- The unchanged native reference passed one UI test (**14.862s**), with six
  screenshots and `native/ios-import-server.mp4` (**24.921667s**): initially
  empty, required/invalid errors, Continue alert, Cancel and confirmed root.
  Native networking is offline and keys/registry are in memory. The Android
  save-failure case is additional Android evidence, not a recorded native case.
  Actual video contact sheets and final form/Home screenshots were personally
  inspected. Both movies retain their original timing and launch/runner waits.
- Final focused auth/harness checks passed **63 tests / 7 suites**. Full fresh
  five-module tests passed **2026 tests / 313 suites**, zero failures/errors/
  skips, in **2m54s**. Ordinary debug and genuine unsigned production
  (**4m45s**, R8/lint included) APKs pass fixture/probe/network-policy isolation,
  both JNI ABI checks and 16KB ZIP / 16KiB ELF LOAD alignment. Production is
  non-debuggable and unsigned. Exact hashes, earlier test-only corrections,
  parser-source references and stricter damaged-metadata handling are in the
  audit. A real RN upgrade, live services and store validation are unverified;
  the purchase test covers the graph gate, not RevenueCat SDK networking.

## Home Session/Task picture verification (2026-10-30)

- Source `ed23b11b1` wires Home-owned image staging into Session and Task:
  system picker/paste, preview/remove, picture-only submission, first-send
  notices, and clearing only after outbox acceptance. Focus dismissal and
  Bot mode preserve Session pictures; Stop retains them and Home destruction
  clears only its own staging. Tests cover same-Task retry and exact encrypted
  upload retry after the original staging file has been deleted.
- `.context/evidence/relay-dock-pictures/android-fourth/android-dock-pictures-final.mp4`
  (**146.739800s**) records actual picker/import, preview, Bot/Session
  retention, dismissal/reopening, removal/reselection, image-only Task
  creation, the image in chat, Home row and empty next Session. Sanitized
  evidence proves one Task spawn, decrypted JPEG before text, draft/kind
  reset and relay/scope/account teardown. All 13 XML snapshots parse; the
  owned emulator exited. This is loopback-fixture proof, not a live machine.
- Native **one UI test / 1094.098s** passed: actual system picker, preview,
  Bot hide/Session restore, Task retention and removal. Five screenshots were
  exported and Session/Task previews personally compared. The final picker
  tap derives from its observed image frame because XCTest exposes no image
  hit point. `native/ios-dock-pictures-compact.mp4` (**57.333333s**) removes
  runner waits; raw **1105.273333s** remains. No seeded fallback was used.
  Final native animations and frozen source are unchanged; the owned
  simulator shut down before Android builds. Native networking is offline.
- Full fresh tests at that source passed **1998 tests / 310 suites**, zero
  failures/errors/skips, in **2m50s**; all five test tasks reran. Ordinary debug
  and genuine no-harness unsigned production (**4m44s**) builds passed.
  Actual APKs and production R8 mapping exclude fixture/testing/probe code
  and permissive network policy; production is unsigned/non-debuggable.
  Both JNI ABIs, 16KB ZIP and 16KiB ELF LOAD alignment pass. Logs, XML, hashes,
  earlier automation failures and fixture corrections are in the audit and
  evidence directory. All owned workers/devices stopped; no release occurred.
- The fixture now explicitly validates rig metadata v1. Earlier Task/Bot
  captures did not establish v1-specific chat capability gating; their
  separately asserted transport results still stand. Strict-v1 Bot regressions
  pass. Off-main dock text-height preparation is an implementation/performance
  difference, not a demonstrated missing behavior; diagnosis precedes changes.

## Home Bot face verification (2026-10-30)

- Source `07d775002` connects the four native face choices, reroll, selected
  ring and artist link to Bot creation. Layout/frame tests preserve native
  metrics; the cached painter rasterizes and encodes a 256px PNG off main.
  The original account owns encryption/upload/`setAvatar`. Picture failure
  keeps the Bot and offers picture-only retry after the dock closes. Stop
  and account changes cancel; background upload refusal is retryable.
- `.context/evidence/relay-bot-face/android-final/bot-face-flow.mp4`
  (**92.476022s**) verifies actual shell picker/roll/name, deliberate first
  avatar refusal, retry and matching Home avatar. Sanitized evidence asserts
  one spawn, two avatar attempts, selected PNG encrypted roundtrip, no first
  message, preserved Session prompt, reset Bot name/kind and explicit
  relay/scope/account teardown. Ten UI XML snapshots parse; owned emulator
  PID 62124 exited. This is loopback-fixture proof, not live-machine creation.
- Native scratch build and one UI test passed (**729.810s**), with four
  picker/selection/roll/name screenshots personally compared. The raw
  740.230s movie includes XCTest's 60s animation-idle waits;
  `native/ios-bot-face-compact.mp4` (**54.883333s**) removes
  those waits. Frozen iOS source and animations are unchanged; the dedicated
  simulator shut down before any Android build. Native networking is offline.
- Full fresh ordinary-debug checks at that source passed **1990 tests / 309
  suites**, no failures/errors/skips, plus required-Tailcat assembly. All five
  test tasks reran. Ordinary APK manifest/DEX/assets exclude fixture code and
  network policy; both JNI ABIs and 16KB ZIP alignment pass. Detailed counts,
  earlier fixture-only capture failures, artifact hashes and build logs are
  in `docs/parity-audit-2026-10-29.md` and the evidence directory.
- Genuine no-harness unsigned production compilation/assembly passed at the
  same source in **4m49s**, including R8/lint/keep checks. Actual APK and R8
  mapping exclude fixture/testing/mockwebserver code and network policy;
  production is non-debuggable and unsigned. Both APKs pass 16KB ZIP alignment
  and both Tailcat JNI ABIs pass 16KiB ELF LOAD alignment. Build/device work
  was sequential and bounded; all owned workers/devices stopped. No signing,
  production install, publication or store validation occurred.
- Session/Task dock pictures have the newer verified record above; off-main
  dock text-height preparation remains an unproven performance difference.
  The picture comparison also found that
  suppressing only the offline header pulse did not eliminate XCTest's waits,
  so their earlier attribution solely to that pulse is not established.
  Successful live creation and spoken TalkBack are not claimed.

## Direct shell verification (2026-10-29)

- The actual `MainActivity` now runs Direct home, account switching, settings
  and pairing. Verification uses a separate `com.slopus.happy.dev.shellharness`
  debug APK; no normal development/preview/production account data is seeded.
- `direct-shell/direct-shell-switch.mp4`: relay/Direct profile switching with
  Settings retained, real Appearance navigation, and QR sheet. Screens 01–06.
- `direct-shell/direct-shell-remove.mp4`: confirmed fixture disconnection and
  handover to the remaining computer. Its old directory is removed; the other
  computer retains its own preferences. Screens 08–10 also show dark theme
  surviving a process restart.
- `direct-shell/direct-shell-pairing.mp4`: cold-start Direct link, real
  four-emoji UI and crypto handshake against `ShellPairingTransport`, then
  the new computer's home and conversation. Screens 11–13. Screen 14 shows
  unfinished pairing cancelled by backgrounding. This is **fake-daemon proof**,
  not successful real Tailcat pairing. QR camera scanning remains unverified.
- The first full working-tree batch passed `testDebugUnitTest :layout:test
  :crypto:test :icons:test` (1,854 tests, zero failures/skips), plus
  `:app:compileReleaseKotlin` and isolated `:app:assembleDebug`. This was before
  the final vector-logo, startup-error and Settings binding changes; it is not
  a claim that the full suite was rerun at the final shell commit.
- Last-tested shell code HEAD is `6ccf3e066`: all seven focused tests below
  passed there (zero failures/skips), as did debug assembly and production
  Kotlin compilation. The later documentation-only commit does not change it.
  Focused real-view coverage is
  `StemCellsSettingsBindingTest` (3): initially missing session, quick switching
  with out-of-order session arrivals, and removal while a session is pending
  (including last-profile removal). `DirectShellTest` (3) covers restoration,
  account isolation/removal and startup-storage failure; `ShellPairingTransportTest`
  (1) covers the real random-key handshake against the fake daemon.
  The reproducible bounded command is `.context/scripts/g.sh
  :app:testDebugUnitTest --tests com.slopus.happy.ui.stemcells.StemCellsSettingsBindingTest
  --tests com.slopus.happy.DirectShellTest --tests com.slopus.happy.direct.ShellPairingTransportTest
  :app:assembleDebug :app:compileReleaseKotlin -Phappy.shellHarness=true --console=plain`.
  Screen 15 shows the final sharp vector pairing mark; screen 16 shows Stories
  appearing on the first cold-start Settings open, without a carousel switch.
  No release was made.
- Inline Stories and proposal cards now have their own verified integration
  records below. The remaining checklist is still partial. Relay dock
  pictures and Bot faces have newer verified records above;
  Relay Task rows, actions and text creation have later records below. A fresh audit
  must compare current iOS source, not assume every historical gap still exists.

## Real conversation sidebar proposal verification (2026-10-29)

- `7f63acafb` adds the native proposal state owner: wanted reads, event and
  reconnect refreshes, local folds, names refreshed from current spaces,
  version/terminal-status guards, bounded retries with one mutation ID,
  server refusal notices and cancellation on owner destruction.
- `4d7e40ad6` adds proposal tool presentations only for new
  `shell-fixture-proposals-*` computers in the isolated debug shell APK.
  Acceptance actually applies the document diff and emits sidebar/proposal
  events; decline preserves the sidebar. It is not a canned success message.
- `0d58e06b5` replaces the permanent Loading line with real proposal cards
  in Direct conversations. Existing sidebar rows render full-height without
  their own scrolling; the ported layout supplies frames. The header/version,
  local folds, two answer pills, chosen spinner/both-disabled state, refusal
  notice, accepted line and dimmed declined state follow native. Pill drawing
  remains 28 dp high with 44 dp hit bounds. Planner caches include the shown
  proposal values; read-only previews bind no decision or fold actions.
- Fifteen affected tests passed: `StemCellSidebarProposalsTest` (6),
  `StemCellsProposalCardTest` (3), `StemCellsInlineStoryTest` (5),
  `StemCellsPreviewTest` (1). Real-card tests verify local fold without a
  sidebar write, accepted card plus actual store update, decline without
  sidebar change, stable card reuse and read-only preview behavior. The
  initial state-owner compile error was a Kotlin val assignment inside its
  retry loop; corrected before passing tests. It was not a device failure.
- Real MainActivity evidence under `proposals/`: `proposal-accept.mp4`
  (48.092 s) shows fold, acceptance, then Home adopting Proposed work;
  `proposal-decline.mp4` (59.446 s) shows fold, decline and original Home
  retained. Fresh synthetic computers 1 and 2 isolate their writes. Screens
  `accept-01-card.png` through `accept-04-home.png` and corresponding
  `decline-*` files record each step. Both video contact sheets and the finer
  `proposal-decline-edge.png` were inspected; coarse sampling alone skipped
  the short declined state. Android was shut down afterward and no own
  happy36 qemu remained.
- Native reference uses the actual Direct home, production tablet container
  and conversation over the same proposal document. Only the ignored host
  adapter/fixture changed, never iOS app sources. Its initial helper-only
  build error called `SpaceSidebarDiff.diff` instead of Swift's
  `SpaceSidebar.diff`; the corrected build passed. The native UI driver
  passed (158.296 s). `ios-proposals.mp4` is 88.178 s, not the whole driver
  duration; `ios-before.png`, `ios-fold.png`, `ios-accepted.png`,
  `ios-declined.png` and both `ios-home-*` screens capture the states.
  HappyRef 17 Pro alone ran, after Android/Gradle stopped, and was shut down
  before the full test batch. All verification here is fake-backed, not
  successful live Tailcat/daemon access or a spoken TalkBack test.
- Full milestone batch passed at exact code HEAD
  `0d58e06b5724fe4e2e0ab378d669247b489df7d46`:
  `.context/scripts/g.sh testDebugUnitTest :layout:test :crypto:test :icons:test :app:compileReleaseKotlin :app:assembleDebug -Phappy.shellHarness=true --console=plain`.
  Result XML totals: **1903 tests in 292 suites**, zero failures, errors or
  skips. App's 1270 tests/178 suites ran; unchanged layout 591/107,
  crypto 36/5 and icons 6/2 remained up-to-date. Production release Kotlin
  compilation and isolated debug APK assembly passed. Gradle was stopped;
  no Gradle/Kotlin daemon or own qemu remained afterward. Both native movie
  contact sheets, including the fine action sampling, were inspected.

## Current-source parity audit (2026-10-29, continuing)

- Fixed baseline and consolidated inventory:
  [parity-audit-2026-10-29.md](parity-audit-2026-10-29.md). This inventory
  supersedes stale historical gap wording below, including unported Bot mode,
  proposal Loading-only cards, missing inline Stories and header rename.
- The old “rename in the chat header” gap is stale: native
  `StemCellChatHeaderView.swift:57` routes the whole capsule to details and
  disables title interaction; no `titleView.onRename` assignment exists in
  current iOS Sources. Android already routes its capsule to details.
  Renaming belongs in the detail screen, which both platforms implement;
  its complete current flow will be verified separately, not replaced with
  a new header behavior absent from the reference.
- Relay Home Task rendering/actions and dock text creation are verified by
  tests and isolated Android/native comparison below. Dock pictures and Bot
  face choices have newer verified records above. Further inspection found
  Bot grouping and Session/Bot creation already implemented in Android. Native
  `HomeRowDrawing.swift:190` and `HomeDockScreens.swift` are the reference.
  Direct's existing task/bot sidebar does not complete relay Task features.

## Relay Task Home row verification (2026-10-29)

- Original implementation checkpoint `420b5508e` passed its recovered affected
  batch: 58 tests/3 suites and isolated debug assembly. Device inspection then
  caught invalid project metadata in the shared fixture (summary missing
  `updatedAt`). `69cd35822` fixes it, advances the synthetic metadata version,
  and adds a fixture-schema/grouping regression; its six Task tests passed.
- Corrected real MainActivity evidence under
  `.context/evidence/relay-task-home/corrected-fixture/` includes grouped/flat
  screenshots, compressed node trees, task-header navigation/return and
  `task-home-flow.mp4` (37.080478s). The offline fixture leaves chat history
  loading; this does not verify message retrieval or live connectivity.
- Recreated native host uses the same JSON, frozen iOS source, real
  HomeViewController/TabletShellViewController, memory-only credentials and
  offline transport. Native UI test passed (1 test, 247.278s); video
  `native/ios-task-home.mp4` is 267.925s, including XCTest idle waits.
  Screenshots and both platforms' video frames were personally inspected:
  Tasks sit between Bots/Projects, lexical membership order differs correctly
  from flat activity order, folder labels/fallback and checkbox glyphs match,
  and retired tasks stay hidden. Native screenshots are mapped by
  `native/attachments/manifest.json`.
- Full bounded suite passed at exact code
  `69cd35822e41da0a1cdfb259c564eb3154560b0d`: **1909 tests / 293 suites**,
  zero failures/errors/skips. App 1276/179, layout 591/107, crypto 36/5 and
  icons 6/2 all executed; debug APK assembly passed. Gradle overall: 30 tasks
  executed, 2 from cache, 67 up-to-date (1m59s). Release compilation was not
  attempted without the absent Tailcat AAR. The capped build ran with both
  simulators off; owned Gradle/Kotlin PIDs 46331/47697 exited afterward.
- Home Task actions were unfinished at this row checkpoint and are covered
  by the next slice below. Dock Task creation remains unfinished. Evidence is fake-backed and node-tree based,
  not successful live daemon access or spoken TalkBack.

## Relay Task Home action verification (2026-10-29)

- Code `0103c67b0dd821702806d42096559a48bac4a9a4` adds capability-gated
  Leave in Home menus and grouped/flat swipe actions, strict ownership-gated
  Archive, machine-only Task RPC routes and shared Home/detail optimistic
  hiding/restoration. Duplicate in-flight actions and stale-account results
  cannot affect the next account. Task HTTP archive/delete fallback is blocked
  even if unrelated metadata fails typed parsing.
- Focused verification passed **102 tests / 8 suites**, zero failures/errors/
  skips, and the isolated debug APK assembled with Tailcat required (1m14s).
  Real-View tests cover unsupported drags not clicking, normal taps, reveal
  and activation, right drags, recycling/detach and grouped pill hit bounds.
- The initial action recording exposed two real issues: the flat action
  treatment clipped in a short grouped row, and unsupported swipes opened
  the conversation. Both are fixed. Accepted evidence is under
  `.context/evidence/relay-task-actions/android-fixed/`: owner/member/
  unsupported menus, compact grouped Leave, flat Leave, unsupported flat
  swipe staying on Home, and offline Leave/Archive refusal restoration.
  `task-actions-flow.mp4` is 96.098522s. Stills and sampled frames were
  personally inspected; happy36 PID 92253 exited afterward.
- Frozen-source native host XCTest passed (1 test, 371.149s); its recording
  `native/ios-task-actions.mp4` is 384.066667s including animation-idle waits.
  Inspected native grouped pill/menu evidence informed the Android control.
  HappyRef was shut down. These fixtures use offline transports: successful
  machine operations are unit-fake coverage, not a live relay claim; spoken
  TalkBack remains unverified.
- Source-built Tailcat is restored for both `arm64-v8a` and `x86_64`, with
  16 KiB ELF LOAD alignment. The documented native build, 11 package checks,
  check-package and Go validation passed using workspace-local Go 1.27.1/NDK
  27.2.12479018. Artifact hash, sizes, setup footprint and exact evidence are
  recorded in [the audit](parity-audit-2026-10-29.md). No binary/cache was
  committed, no live peer was contacted, and no app was released.
- Genuine no-harness production `:app:compileReleaseKotlin :app:assembleRelease
  -Phappy.requireTailcat=true` passed at the code checkpoint above (6m51s;
  77 executed, 11 from cache, 9 up-to-date). Release lint, R8 and
  `verifyReleaseKeeps` passed (68 serializer types/manifest). The unsigned
  `com.ex3ndr.happy` APK contains both Tailcat ABIs and passes 16 KiB ZIP
  alignment; signature verification rejects it as intentionally unsigned.
  The 96,377,460-byte artifact/hash is recorded in the audit. No production
  install, signing or publication occurred. Both simulators stayed off;
  owned Gradle/Kotlin PIDs 94483/94701 exited afterward.
- Full native-linked milestone passed at the same code HEAD: app 1297/182,
  layout 591/107, crypto 36/5, icons 6/2 tests/suites (**1930/296**, following
  earlier four-module reporting), plus vendor lifecycle 4/1: **1934 tests /
  297 suites total**, zero failures/errors/skips. All five test tasks ran
  afresh using `--rerun`; isolated debug assembly with required Tailcat
  passed. Gradle: 15 executed, 86 up-to-date, 2m02s. Both simulators stayed
  off; owned Gradle/Kotlin PIDs 773/862 exited. Exact command/log are in the
  audit. Dock Task creation and successful live-machine operations remain
  separate from this verified action milestone.

## Relay troubleshooting verification (2026-10-30)

- Source `874fd38f6540bcd46a4b2c2cb7f8b208892eef25` replaces both Home
  placeholders with the native three-check guide, grouped machine links and
  AI prompt copy/two-second feedback. It captures account identity, observes
  only while visible, rejects stale actions and retains last valid content
  across a read failure. Its shared account-owned feed reads a narrow SQLite
  projection off main; a tested streaming fallback supports SQLite without
  JSON1 without a schema change. Actual Android 8 device coverage is not claimed.
- Exact-frame tests port the native guide/copy layout. Guide text is planned
  off main; terminal lines use Android selection with `$ ` excluded. Initial
  selection replay failures were XML-tool false negatives: the floating
  toolbar was visible but absent from the active-window hierarchy. Corrected
  replay verifies selection `2:7`, a fixed boolean clipboard assertion and
  the visible clipboard preview containing exactly `happy`.
- Evidence: `.context/evidence/relay-troubleshoot/`. Baseline Android movie
  **69.311744s**; final `android-selection3/troubleshoot-flow.mp4`
  **75.268878s**, both Home entry points, guide, terminal copy, AI feedback,
  machine details/back, all **12 XML** files valid. Owned emulator PID 8991
  exited. Native comparison passed **1 UI test / 9.334s**, movie **20.316667s**;
  dedicated simulator shut down. Native guide was personally compared with
  Android; no frozen iOS source changed. Fixtures are fictional/offline,
  not evidence of live connectivity or successful machine repair.
- At exact source, fresh **ordinary-debug/no-harness** verification passed
  **1969 tests / 304 suites**, zero failures/errors/skips (app 1328/188,
  layout 595/108, crypto 36/5, icons 6/2, vendor 4/1). All five test tasks
  actually ran; required-Tailcat debug assembly passed in **2m30s**.
  Owned PIDs 13357/13460 exited.
- Unsigned no-harness production Kotlin compilation/assembly passed in
  **4m26s**, including R8/lint/keep checks; owned PIDs 15929/15961 exited.
  Actual ordinary/release manifest, DEX/assets and release mapping checks
  exclude fixtures and probes. Correct package IDs, both JNI ABIs and 16KB
  ZIP alignment pass; production is non-debuggable and unsigned. Preserved
  APK hashes, sizes and logs are in the audit. No production install,
  signing, publication or store validation occurred. The older Home
  checklist's terminal block remains display-only; this slice verifies
  selection in the new troubleshooting guide.

## Relay Task dock text-creation verification (2026-10-29)

- Source `0f3e74eea870e1438dbc08b516cb684080bcd2d2` adds strict Task capability,
  native Session/Bot/Task ordering, memory-only mode selection, machine-only
  Task card, unnamed Happy Agent Task spawn and the normal first-message send.
  Stop/retry, late completion, account/target change and stale-capability guards
  prevent request reuse/cleanup races or an unintended ordinary Session spawn.
- Earlier Android rendering/refusal video: **85.582633s**. Frozen-native dock
  UI test: **1 test / 731.816s**, video **742.603333s**, with XCTest idle waits.
  Refusals are missing-key **pre-RPC** errors, not network failures. Those
  captures predate the final capability/atomic guards; later tests and the
  successful capture include them. Native source remains unchanged.
- Real-shell successful capture: `.context/evidence/relay-task-dock/android-success/`,
  `task-success-flow.mp4` **61.392767s**. Personally inspected Task card,
  created chat/first-message bubble, new TASKS row, empty Session dock and cold
  restart. Sanitized JSON asserts Task—not Session—payload, matching returned/
  stored/Home identity, encrypted first-message delivery, draft/mode reset,
  fresh account/relay after restart, and explicit server/scope/account teardown.
  Owned emulator PID 58441 exited. The relay is a disposable in-process
  numeric-loopback fixture in the isolated APK, never a real daemon or account.
  There are no production transport hooks or host listeners.
- The captured APK predates the commit and records `fbed351` build metadata;
  its runtime implementation is the committed source (only comments changed).
  Full ordinary-debug checks at exact `0f3e74eea` passed **1950 tests / 300
  suites**, no failures/errors/skips; all five test tasks ran afresh. App
  1313/185, layout 591/107, crypto 36/5, icons 6/2, vendor 4/1. No-harness
  required-Tailcat debug assembly passed; owned PIDs 62403/62559 exited.
- Genuine unsigned production compilation/assembly at the same source passed
  **5m29s**, including R8/lint/keep checks. Actual debug/release manifests,
  DEX/assets and release mapping exclude harness/testing/mockwebserver code,
  fixture routes and permissive network configuration. Both JNI ABIs and 16KB
  ZIP alignment pass; production is non-debuggable and unsigned. Owned PIDs
  64407/64499 exited. No production install, signing or publication. Exact
  logs, artifact hash and checkpoint details are in the parity audit.
- Task dock pictures and Bot faces have newer verified records above. Successful live machine
  creation/agent execution and spoken TalkBack are not claimed by this fixture.

## Real conversation inline Stories verification (2026-10-29)

- `413f81294` connects the actual conversation planner/row renderer to
  `StoryInlineView`, reusing cards by story ID and releasing discarded cards.
  The real chat supplies its per-computer Stories session and owns editor/
  viewer presentations. Leaving or replacing the screen closes even an
  opening modal, releases callbacks and restores system bars. Forced editor
  cleanup removes only its own temporary media; it never silently posts.
- `aa0ebf701` supplies `draft_story` only for the isolated debug shell's
  `shell-fixture-stories-*` computers, preserving the rest of the fixture.
  No normal app account, real daemon, or production fixture hook is used.
- `e3cad25a9` hides covered window branches from accessibility while a
  full-screen Stories modal is presented and restores their previous flags
  afterward. Before-fix assertions are retained in
  `inline-story/modal-a11y-before-tests.xml`. Device checks use compressed
  trees (`editor-a11y-compressed.xml`, `viewer-a11y-compressed.xml`, and
  `returned-a11y.xml`): modal-only controls, then chat controls after return.
  Raw uncompressed dumps include nonimportant covered Views; the files
  named `editor-a11y-fixed.xml` and `inline-modal-uncompressed-diagnostic.mp4`
  are diagnostic, not evidence of a failed TalkBack repair. Spoken TalkBack
  output is not verified. `fa9fe57ce` labels the viewer's More action.
- Five `StemCellsInlineStoryTest` cases cover actual draft Post and viewer
  origin, Edit and forced owner cleanup without posting, activity destruction
  while opening, card reuse/removal, and the same card following Stories
  off/on and deletion. They also check modality flag restoration and More's
  label. Focused home/preview regression checks passed during iteration.
- Inspected real MainActivity recordings under `inline-story/`:
  `inline-story-edit-post.mp4` (91.029 s: Edit, caption change, Save draft,
  Posted); `inline-story-viewer-route.mp4` (15.674 s: viewer replaced by a
  real pairing route, new synthetic account home and editor);
  `inline-story-modal-compressed.mp4` (42.563 s: editor replaced by pairing,
  new account Post, viewer and return). Corresponding `*-frames.png` contact
  sheets were inspected. The first `inline-story-delete.mp4` (39.749 s)
  ends at the first Delete menu, before confirmation; its separate
  `12-deleted-card.png` proves the later deleted state, not the movie.
- Final-build recheck: `inline-story-final-delete.mp4` (42.205 s) and its
  inspected contact sheet show actual Post, viewer opening, More, Delete,
  confirmation and the same card's deleted state. `final-viewer-a11y.xml`
  includes More and excludes covered chat; `final-deleted-a11y.xml` restores
  chat controls. `final-viewer.png`, `final-confirm.png` and
  `final-deleted.png` are the corresponding stills. Only fresh synthetic
  computer 8's story was removed; a new fixture can recreate it. The first
  fixed-delay attempt produced an empty root (`final-first-empty-root.xml`)
  and safely shut down without claiming success. Its exact cause is not
  established; the final script awaits actual pairing, home and card labels.
  No production code changed between those attempts. Both captures' cleanup
  verified no own happy36 qemu remained.
- Native reference: only the ignored reference host changes, using the
  actual Direct home, production tablet container and conversation with the
  same draft tool result. The UI driver passed in 410.977 s.
  `ios-inline-story.mp4` is 273.253 s, not the whole driver duration;
  inspected `ios-editor-edge.png` (0–9.5 s) and `ios-viewer-edge.png`
  (192–211 s) confirm the editor and posted draft viewer; the timeline also
  shows autoplay and return. The later screenshot named
  `ios-viewer-requested.png` arrives after autoplay and is not viewer proof.
  Fixture media artwork differs between platforms; compare UI around it.
  Only HappyRef 17 Pro ran, with Android off; it was shut down afterward.
- Full milestone batch passed at exact code HEAD
  `fa9fe57ce0264776cf665303f3b624a0b79d7a43`:
  `.context/scripts/g.sh testDebugUnitTest :layout:test :crypto:test :icons:test :app:compileReleaseKotlin :app:assembleDebug -Phappy.shellHarness=true --console=plain`.
  XML totals: **1894 tests in 290 suites**, zero failures, errors or skips
  (app 1261/176, layout 591/107, crypto 36/5, icons 6/2). App tests executed;
  unchanged layout/crypto/icons were up-to-date, not re-executed. Production
  Kotlin compilation and isolated debug assembly passed. Both simulators
  were off; the required wrapper kept three workers, parallel projects off,
  bounded Gradle/Kotlin JVMs and two test forks with two processors each.
  Own Gradle/Kotlin PIDs 57880/57975 were stopped and checked absent.
  This remains fake-backed verification, not successful live Tailcat access.

## Real Direct-home Stories verification (2026-10-29)

- `f0c8536a8` installs `StoriesHomeController` in the actual Direct home:
  per-computer Stories session and photos, list pull/scroll routing, held
  offset preservation, carry-mode suppression, covered/destroyed cleanup,
  and background flushing of the Stories offline store. Replacing an engine
  closes its previous session. Account changes remove old circles immediately
  and close even a still-opening viewer, cancelling transition animators.
- `acf42be2e` adds Stories only to `shell-fixture-stories-*` computers in
  the separate debug shell APK. The optional offline fault rejects requests
  and new streams with 503; it does not close an already-open stream, so
  the offline verification uses a cold app restart. No production hooks or
  production account data are involved.
- `a32868c73` fixes a device-discovered accessibility defect: a row drawn
  outside the header had inverted/clipped avatar bounds. The real header
  now includes its accessory overhang without moving the header chrome.
  `stories-home/01-home-a11y-diagnostic.xml` and the failing
  `a11y-before-fix-test.xml` are preserved; `02-home-a11y.xml` verifies
  positive avatar bounds afterward.
- `ac70aa5b5` makes the coordinator destroy its screens and release the
  tablet sidebar when the activity is destroyed. The regression first
  reproduced two retained screens (`coordinator-before-fix-test.xml`), then
  verifies exactly-once destruction, canceled scopes and cleared owners.
  This is a Robolectric activity lifecycle check, not a claim that Android
  force-stop invokes `onDestroy`.
- Real `MainActivity` recordings under `stories-home/`, with fake transport:
  `stories-home-walk-verified.mp4` (scroll collapse, separate top-edge pull
  to reopen, viewer, Back and seen rings), `stories-account-route.mp4`
  (new synthetic pairing over an open viewer, confirmation, new home),
  `stories-settings.mp4` (the actual Stories switch off/on), and
  `stories-offline-cold.mp4` (cached row and sidebar restored while every
  request/new stream fails). The offline fault was verified true before
  capture, restored and verified false afterward. No offline media-playback
  or live daemon/Tailcat-success claim. The unsuffixed first
  `stories-home-walk.mp4` is diagnostic: its pull only reached the list top,
  and its tap opened a chat, not a story.
- Four focused `StemCellsStoriesHomeTest` cases cover real home layout and
  accessibility bounds, carry suppression and held offsets, per-computer
  preference and unsupported state, account replacement during viewer opening,
  and activity destruction during opening. The affected batch also passed
  `AppCoordinatorLifecycleTest` (1), `StemCellsNewWorkTransitionTest` (1),
  and `DirectShellTest` (3): nine tests, zero failures or skips.
- Native reference: `ios-stories-home.mp4` (154.280 s) and inspected
  `ios-timeline.png` show the actual Direct home in production's
  `TabletShellViewController`, opening Steve, autoplay through Ana and return
  to the collapsed row after all stories are seen. `ios-before.png` verifies
  the open row's geometry; inspected `ios-opening-edge.png` and
  `ios-return-edge.png` capture the grow/shrink transitions. The UI driver
  passed (160.254 s), but its
  screenshot named `ios-viewer.png` was delayed until autoplay had already
  returned home; use the video, not that filename, as viewer evidence.
  Only the ignored reference host changed, reusing native Stories fixtures;
  only HappyRef 17 Pro was booted and it was shut down afterward. Android
  was off throughout the native build/capture. Four Android video contact
  sheets and their passing step screenshots were also inspected.
- Inline story and proposal cards are addressed in their later milestones
  above. The current-source audit supersedes the historical header-rename gap.
- Full milestone batch passed at exact code HEAD
  `ac70aa5b595162e118b14f6acb94e4012f07e00c`:
  `.context/scripts/g.sh testDebugUnitTest :layout:test :crypto:test :icons:test :app:compileReleaseKotlin :app:assembleDebug -Phappy.shellHarness=true --console=plain`.
  Result XML totals: **1889 tests in 289 suites**, zero failures, errors
  or skips (app 1256/175, layout 591/107, crypto 36/5, icons 6/2).
  App tests ran; unchanged layout/crypto/icons tasks were up-to-date,
  not re-executed. Production Kotlin compilation and isolated debug assembly
  passed. Both simulators were off. The low-priority wrapper retained three
  workers, no parallel projects, bounded JVM processor counts and at most
  two test forks, each with two processors. Own Gradle/Kotlin processes
  19360/19478 were stopped and checked absent afterward.

## Stem-cell hatch verification (2026-10-29)

- Native Views implementation `e51fbac03`: the existing pure 2.5-second,
  151-keyframe hatch now runs when a visible sidebar or chat intro stem cell
  receives a clay icon. Cells divide (8 small / 16 large), tint toward the
  image and dissolve into it. Repeated title updates do not restart it.
- Reduced motion takes the final image immediately. Replacement, hidden
  owner, window loss and detach finish once, restore the destination mark,
  cancel callbacks and retain ownership of the shared bitmap in its cache.
  A real-view regression caught and fixed re-entrant child removal during
  detach. Android's native haptic taps follow cleavage/formation timestamps;
  they do not reproduce iOS's continuous Core Haptics wave. Physical-device
  tactile behavior remains unverified; no vibration permission was added.
- Inspected Android evidence under `hatch/`, all in the isolated fake-backed
  **real MainActivity** shell: `hatch-intro-verified.mp4` and
  `intro-verified-frames.png` (full large hatch), `hatch-sidebar.mp4` and
  `sidebar-mark-frames.png` (small hatch), `hatch-reduced.mp4` and
  `reduced-mark-frames.png` (animator scale zero, restored to one afterward).
  `intro-before.png`, `intro-after.png`, `sidebar-after.png` and
  `reduced-after.png` show settled states.
- Capture also exposed a genuine account-switch race: publishing selection
  allowed the incoming screen to create its engine while a suspending pause
  iterated the live engine map. A deterministic regression failed with
  `ConcurrentModificationException`; `95cf69c43` fixes that iteration.
  `hatch-switch-fixed.mp4`, `switch-fixed-frames.png` and `switch-fixed.png`
  show the successful profile change during a hatch, without the prior
  error or a lingering overlay. The original `switch-verified.png` and
  `hatch-switch-verified.mp4` preserve the failure, not passing evidence.
- Focused verification: all 8 `DirectAccountsTest` and 4
  `StemCellHatchViewTest` tests passed with the isolated debug assembly;
  existing 4 pure `StemCellHatchTest` tests passed in the preceding hatch
  batch. The first `hatch-intro.mp4` missed Send and `hatch-switch.mp4`
  ended before the final switch; neither is claimed as proof of those steps.
- Native iOS reference: HappyRef 17 Pro only, prebuilt driver passed (90.64 s).
  `ios-before.png`, `ios-after.png` (mid-hatch, not settled), `ios-hatch.mp4`
  (25.325 s) and `ios-mark-frames.png` show the same real native intro.
  This comparison caught Android's incorrect beige tint from bilinear
  centre sampling. `c6cf92466` computes the full icon's opacity-weighted
  average; all 5 hatch view tests and debug assembly then passed.
- Inspected final corrected-tint capture: `hatch-intro-fixed.mp4`,
  `intro-fixed-frames.png` and `intro-fixed.png`. The earlier intro/sidebar
  videos establish the animation behavior before that tint correction,
  not the final color. Both simulators and own build daemons were stopped
  between capture/build batches; the test AVD's qemu exit was verified.
- Full milestone verification at exact code HEAD `c6cf92466` passed:
  `.context/scripts/g.sh testDebugUnitTest :layout:test :crypto:test :icons:test
  :app:compileReleaseKotlin :app:assembleDebug -Phappy.shellHarness=true --console=plain`.
  The resulting XML reports contain **1,877 tests in 285 suites**, zero
  failures/errors/skips: app 1,244 / 171 suites, layout 591 / 107, crypto
  36 / 5, icons 6 / 2. Unchanged module tasks may be up-to-date/from cache;
  these are the batch's result totals, not a claim every unchanged test
  re-executed. Debug assembly and production Kotlin compilation passed.
  Own Gradle/Kotlin daemons were stopped and their exits verified.
  The separate new-work navigation
  transition was unfinished at this checkpoint; see its later record below. These checks do not establish a successful
  live Tailcat/daemon connection.

## Dock/new-work transition verification (2026-10-29)

- Code commits: `23dd956ec` adds an optional screen-owned transition to
  `ScreenStack`; `abdd2e402` ports the new-work fade; `3894083ac` extends
  the dev-only pointer probe horizontally for system-edge back checks.
- New-work chat over its own Direct home uses the native reference's
  350 ms ease-in-out fade, without horizontal travel, parallax or a dim.
  The chat composer stands in for home's dock and follows native IME
  insets; typed text/attachments crossfade on return. Ordinary navigation
  retains its existing slide. Android's first Back dismisses an open IME;
  its system predictive-back gesture then drives the screen transition.
- Seven focused tests passed: `ScreenTransitionTest` (6), covering
  push/pop, predictive cancel/commit, default slide, reduced motion, stack
  replacement, replacement during cancellation settlement and window
  detach; `StemCellsNewWorkTransitionTest` (1), using real home/chat Views
  for composer/background alpha, preserved text and cancelled-pop focus.
  The chat root provides a focus target so clearing the only editor does
  not immediately focus it again. Teardown never restores editor focus.
- Inspected real-shell fake-backed videos under `new-work/`:
  `new-work-open.mp4` (empty/typed opening and return; the second opening
  visibly rides the IME), `new-work-gesture.mp4` (actual system-edge back
  cancellation, then completion), `new-work-attach.mp4` (home plus, sheet
  dismissal, keyboard and return), and `new-work-reduced.mp4` (animator
  scale zero; restored to one and checked). Their corresponding
  `open-frames.png`, `gesture-frames.png`, `attach-frames.png` and
  `reduced-frames.png` were inspected. `new-work-cancelled.png` retains
  the draft, `new-work-committed.png` returns to home, and
  `reopened-draft.png` confirms the typed draft survives reopening.
  These checks do not claim physical-device IME timing or successful live
  Tailcat connectivity. The initial opening in the first video did not
  raise the IME before the scripted two-second return; subsequent openings did.
- The fresh-process follow-up used two newly paired fake computers in the
  isolated real shell, waiting for each restored home before opening empty
  new work. `ime-cold1.mp4` and `ime-cold2.mp4` each observe for 30 seconds
  without a return. Both `ime-cold*-state.txt` logs report the focused
  `TextField` as the served editor and `mInputShown=true`, `mImeWindowVis=3`
  at the first roughly one-second sample and through the last samples
  (19 and 17 seconds). All four screenshots per run were captured; the
  first run's first and second run's final screenshots, plus both
  `ime-cold*-opening.png` contact sheets, were inspected. The first
  post-boot IME appears later in the film (about 2 seconds from recording
  start, with the tap at about 0.5 seconds) than the second (about 1.25
  seconds). No persistent lost focus/show request was reproduced; no
  speculative retry was added. The earlier short clip remains inconclusive,
  not proof of either a fixed app defect or a particular emulator cause.
- Corrected native reference: `ios-new-work-container.mp4` (242.672 s),
  `ios-container-opening.png` and `ios-container-return.png` show the
  actual iOS fade and composer riding the keyboard, without horizontal
  travel. The HappyRef 17 Pro UI driver passed in 254.665 seconds. The
  ignored reference host now uses production's `TabletShellViewController`,
  which supplies the custom-navigation delegate; no iOS app source changed.
  Android and iOS capture batches were sequential, and each simulator was
  shut down before the next batch. The first reference driver passed
  (90.093 s), but `ios-new-work.mp4`
  ended before its delayed tap; `ios-timeline.png` confirms that, so this
  recording is retained as diagnostic evidence, not a transition comparison.
  The longer `ios-new-work-verified.mp4` did capture an opening, but source
  inspection then found the ignored reference host omitted production's
  `TabletShellViewController` navigation delegate. It showed UIKit's default
  slide, not the app's custom transition, and is also diagnostic only. The
  corrected container capture above supersedes it.
- Full milestone batch passed at exact code HEAD
  `3894083ac9a39841a554576d5d257e436cccb1b4`:
  `.context/scripts/g.sh testDebugUnitTest :layout:test :crypto:test :icons:test :app:compileReleaseKotlin :app:assembleDebug -Phappy.shellHarness=true --console=plain`.
  Result XML totals: **1884 tests in 287 suites**, zero failures, errors
  or skips (app 1251/173, layout 591/107, crypto 36/5, icons 6/2).
  App tests ran; unchanged layout/crypto/icons tasks were up-to-date,
  not re-executed. Production Kotlin compilation and isolated debug
  assembly passed. The mandatory wrapper used three Gradle workers,
  no parallel projects, bounded Gradle/Kotlin JVM processors, and at
  most two test forks with two processors each. Own Gradle/Kotlin
  processes were stopped and their exact PIDs checked absent afterward.

## Sidebar preview and inline rename verification (2026-10-29)

- Code commits: `b01576ab9` (row actions), `efce64f1c` (inline rename and
  keyboard visibility), `859c6b52c` (read-only preview and real menu).
- `StemCellsHoldOverlay` uses the existing pure hold/preview layouts and a
  native spring over a blurred/dimmed home. The preview is the actual
  `StemCellChatScreen`, without its composer or interaction. It reads history
  but never marks the conversation read, binds/saves its draft, or discards
  empty work. Tapping the card opens the real conversation; Back/tap outside
  dismisses the menu.
- Actual row-specific store operations: rename section/task/bot; delete a
  section heading; close untouched new work; leave/archive task (archive only
  when permitted); archive bot/subtask. Happy has no destructive menu. Reorder
  was not offered at this checkpoint; the later carry milestone below adds it.
- Inline rename uses the row's native field, selects the old title, commits
  trimmed nonempty changes once, cancels on Escape or remote row removal,
  and holds the saved title against stale snapshots. The edited row is kept
  above the Android keyboard as IME insets change.
- Seven focused tests passed before capture: `StemCellsRowActionsTest` (3),
  `StemCellsRenameTest` (3), `StemCellsPreviewTest` (1); existing pure
  `StemCellsHoldLayoutTest` and `StemCellsPreviewLayoutTest` also passed.
  The preview regression renders real fixture history, verifies every request
  is GET, and leaves a pre-existing unsynced draft unchanged.
- Full milestone verification at exact code HEAD `859c6b52c` passed:
  `.context/scripts/g.sh testDebugUnitTest :layout:test :crypto:test :icons:test
  :app:compileReleaseKotlin :app:assembleDebug -Phappy.shellHarness=true --console=plain`.
  Result: 1,865 tests in 282 suites, zero failures/errors/skips; debug assembly
  and production Kotlin compilation passed. Own build daemons were stopped.
- Inspected real-MainActivity evidence under `sidebar-hold/`:
  `android-preview-first.png` vs `ios-preview.png` (HappyRef 17 Pro only),
  `android-rename-first.png` (field above keyboard), `android-renamed-final.png`,
  `android-opened-from-preview.png`, and `android-archived.png` (only the
  selected synthetic task removed). Videos: `sidebar-preview-menu.mp4`
  (89.24 s, preview and opening) and `sidebar-rename.mp4` (59.50 s, section
  rename); inspected contact sheets are `preview-video-frames.png` and
  `rename-video-frames.png`. Task rename and archive also have screenshot/UI
  checks; their final results are not claimed to be in those recordings.
  All Android checks use the separate fake-backed shell APK, not real users
  or a successful live Tailcat connection. Both simulators were shut down
  after capture; the test AVD's own qemu exit was checked.

## Sidebar carry/reorder verification (2026-10-29)

- Code commits: `170650b3d` (serialized carry/drop/release and remote-edit
  regressions), `8e8789108` (Views interaction), `25b498d35` (dev-only gesture
  probe). The actual Direct home supports menu-armed reorder and continuing
  the same held pointer from preview into a drag. The old row/scroll touch
  recognizer is cancelled, so it cannot also scroll or open the conversation.
- The existing `StemCellsDrag` supplies whole section blocks, top-level rows
  with descendants, nested sibling families, and blocked slots. Native Views
  lift the block, glide peers, auto-scroll at an edge, and settle or restore
  on release/cancellation. Back/tap, removal, screen disappearance and size
  changes cancel. Carried originals remain hidden through scroll layouts;
  Android's frame container otherwise restores their visibility.
- `StemCellsSession` queues the drop before releasing buffered server edits.
  `StemCellsCarryQueueTest` (2) verifies remote changes survive both drop and
  cancellation; the drop test holds the fake server's response and checks that
  no early reload occurs. `StemCellsHoldDragTest` (3) drives the real home and
  Direct engine through pointer transfer, release, cancellation and leaving
  home. `StemCellsReorderTest` (4) covers section blocks, relayout, structural
  invalidation, touch/accessibility slots, cancellation and exactly-once end.
  `StemCellsRowActionsTest` (4) and `StemCellsDragTest` (11) complete the 24
  focused regressions. Accessibility actions are test-backed, not a claim of
  a physical TalkBack or haptic review.
- Full milestone verification at exact code HEAD `25b498d35` passed:
  `.context/scripts/g.sh testDebugUnitTest :layout:test :crypto:test :icons:test
  :app:compileReleaseKotlin :app:assembleDebug -Phappy.shellHarness=true --console=plain`.
  Result: 1,875 tests in 285 suites, zero failures/errors/skips; debug assembly
  and production Kotlin compilation passed. Unchanged module tasks reused
  Gradle's up-to-date results. No release was made.
- Inspected real-MainActivity evidence under `sidebar-reorder/`:
  `fresh-home.png`, `section-dropped.png`, `continuous-sibling-dropped.png`,
  `continuous-cancelled.png`, `edge-cancelled.png` and `back-cancelled.png`.
  `reorder-menu-and-hold.mp4` (89.36 s) records section reorder and continuous
  sibling drop/cancel; `reorder-edge-cancel.mp4` (44.30 s) records edge scrolling
  and cancellation. Back has screenshot/UI checks, not a claim about that
  recording. Both video contact sheets were inspected. `HoldDragProbe`, built
  only with `-Pprobe`, injects one DOWN/hold/MOVE/UP or CANCEL stream.
- Native iOS reference: `ios-home.png`, `ios-lifted.png`, `ios-dropped.png`
  show the real iOS section menu/lift/drop on HappyRef 17 Pro only. The prebuilt
  driver passed. Android and iOS were run in separate batches; the test AVD's
  qemu exit and final simulator/build-daemon shutdown were checked.
- All computer operations here are fake-backed. A fresh synthetic address was
  paired through the real shell because the fake server resets on process
  restart and an older fixture account's cached version can exceed it. The
  initial stale-cache attempt is retained as diagnostic evidence, not counted
  as success. No real account was paired, changed or reset; this does not prove
  successful real Tailcat or daemon connectivity.

## Architecture decision

| Layer | iOS | Android | Source |
| --- | --- | --- | --- |
| Crypto | `HappyCrypto` | `:crypto` (pure Kotlin, BouncyCastle low level) | kept from the old port |
| Wire, HTTP, socket | `HappyWire`, `HappyCore/Networking`, `Realtime` | `app/net`, `app/sync` (`SocketIoClient`, `SyncApi`) | kept |
| Store | `HappyStore` (GRDB) | `app/data` (framework SQLite, WAL) | kept, one database per signed-in account (`files/accounts/<id>/happy.db`) |
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

- [~] `:layout` module: geometry, `Measurable`, design tokens (`Palette`, `TextStyles`, `Spacing`, `Radius`; Stem Cells palette generated). Ported so far: round button, onboarding, checklists, wordmark, navigation bar, Stem Cells link, item rows/groups, grouped list, settings header, secret key card, restore, server form, settings pages (profile header, dropdown and options, bubble preview, connect command, changelog), all of Stem Cells and Direct identity (`layout/stemcells/`, every iOS Stem Cells layout test); the rest come with their screens
- [x] Session list sync: `changedSince` deltas, live `delete-session`, and a full listing that drops sessions deleted elsewhere. That listing runs on first sync and daily, as on iOS, and also after every socket connect (`SyncEngine.requestFullListing`). The last is a deliberate departure: iOS keeps a session deleted while the app was offline until its next daily reconcile, which showed as duplicates on Home. Evidence: `session-deletion/before-after-reconnect.png` (stale copies gone on connect), `session-deletion/live-delete.png`
- [x] `:icons` module: Ionicons/Octicons/etc. outlines from the iOS generated tables, `GlyphOutline` decoder
- [x] Fonts: IBM Plex Sans/Mono, Bricolage Grotesque; `TextStyle` → `TextPaint`, exact line heights
- [~] Text measurement off the main thread (`StaticLayout`): chat rows, markdown, diffs, settings groups and the file viewer are planned off main; the dock composer, the chat's dock bars and some small screens still measure on the main thread. Font scale: see Platform
- [x] `ScreenStack` navigation with push/pop animation and predictive back (`BarScreen` + Material app bar)
- [x] Theme: light/dark from `themePreference`, edge-to-edge insets, system bar icons follow the app theme
- [~] Components: `ItemRowView`, `ItemGroupView`, `GroupedListView`, `RoundButton`, `NavigationBar`, alerts (platform dialogs), `SegmentedControl`, `TextField`, `QrCodeView`, terminal block, `AvatarView` (brutalist, gradient, pixel, bot chip, picture, harness badge) done; bot faces to do

### Onboarding and sign-in

- [~] Welcome: wordmark, title, value props, Create account, Restore, Get help, Relay | Direct switch (dev/preview). Evidence: `onboarding/01-stem-link-relay.png`, `02-stem-link-direct.png` (vs `ios-ref/04-welcome.png`). Production Welcome's "Pair computer" bar button waits for Direct pairing
- [x] Create account (against the local `happy-server standalone:dev`; `onboarding/onboarding-flow.mp4`, `09-signed-in-home.png`)
- [~] Restore with QR (shows the code, `06-restore-qr.png`), restore with the secret key (`07-restore-key.png`, bad key `08-restore-invalid.png`). Approval from a second device not exercised yet
- [x] Custom server screen (`onboarding/03-server.png`, confirm `04-server-confirm.png`, custom `05-server-custom.png`, in `onboarding-flow.mp4`); onboarding settings: server, Direct connection toggle, "Log out and start over" with its confirmation (`home/03-onboarding-settings.png`)
- [~] Link Your Computer (`home/02-link.png`: checklist ticks kept in local settings, Scan enabled once both are ticked, paste link, approve the terminal, busy until the computer arrives or 10 s, inline failure line). Offline checklist now seen on the emulator; both it and the list banner open the implemented troubleshooting guide, machine links and AI copy. See the troubleshooting verification section above for focused/full/native/device evidence and its limits. Terminal selection in the new guide is device verified (Copy excludes `$ `); the older checklist's terminal lines remain display-only.
- [~] Connect a terminal by QR or URL; `happy://terminal` deep links (the links are done, see Deep links; scanning needs Play services' code scanner, not checked on the emulator)
- [x] Server confirmation for imported accounts: read-only/off-main RN import, pending account gate, empty form/validation/Continue, durable host before activation, retry after failed save, restart and immutable per-account request routing. Focused/full tests and actual Android/native evidence are recorded above; real RN in-place upgrade remains unverified.

### Home

- [~] Home header: account glyph (dev/preview) or logo, "Sessions" + connection status, filter menu as a `PopupMenu` (Grouping, Flat List / Group by Project, Appearance Settings), settings. Evidence: `home/compare-list.png`, `home/compare-filter.png`, `home/10-filter-menu-*.png`. Settings and Appearance open their screens; the account picture is not loaded yet (glyph only)
- [~] Home states: loading, link, offline, no sessions, list, cross-faded in 250 ms; the link state keeps the onboarding bar, the rest float the header over the content (`home/home-restore.mp4`: restore → list). No sessions and the offline checklist are built but not yet exercised on the emulator
- [~] Flat session list (live rows, archive toggle, archived by day), rows with status, draft glyph, line counts, generated avatars, faded rows when the computer is offline. Evidence: `home/compare-list.png` (vs `ios-ref/20-home-list.png`), `home/compare-offline-list.png`, `home/home-interact.mp4`, `home/home-offline.mp4`. A tap opens the chat
- [x] Project grouping (computer header, Projects, worktrees, archived chats at 0.6), expanded projects in local settings. Evidence: `home/compare-grouped.png`, `home/compare-dark-grouped.png`, `home/07-grouped.png`
- [x] Archive by swipe and long press; the "Session" actions (`ui/components/SwipeActions.kt`, `ui/home/HomeSessionActions.kt`, `SessionQuickActionsTest` with iOS's SessionQuickActionsTests): a live flat row drags left over iOS 26's red capsule with the archive glyph and "Archive Session" under it (UIKit's trailing swipe action without a full swipe: past half way or a flick it stays open, a tap on the capsule archives, a tap elsewhere, a scroll or a drag back closes it and does nothing else); TalkBack gets it as the row's custom action. The row leaves at once and comes back only if the machine and the server both refuse, with iOS's messages. A long press opens the "Session" alert (a platform list dialog, Archive in red): Details, Resume (or why it can't), Fork and Duplicate with the continuation experiment, the two metadata copies in dev mode, Archive; as on iOS there is no worktree prompt from home. Grouped rows don't swipe, as iOS. Tasks' Leave waits for task rows. The seed computer keeps its sessions alive, so an archived seed session comes back on the next sync. Evidence: `home-actions/01-swipe-open.png` vs `ios-ref/100-home-swipe.png`, `03-session-alert.png` vs `ios-ref/100-home-session-actions.png`, `02-swipe-archived.png`, `04-alert-archived.png`, `home-actions-walk.mp4` (open, close by a tap, a short drag springing back, archive from the capsule, the alert's Details and Archive)
- [~] Relay Bot rows already exist in `ProjectHomeRows` and `SessionListView`.
  Task classification, ordering, retired-task exclusion and glyph now have
  passing focused/full tests and isolated Android/native evidence. See the
  verification records above, including Task actions. Dock Task creation
  and dock pictures have their own later verified creation records above.
- [~] Update banner and offline machines banner: offline plaque and What's new shown (`home/compare-offline-list.png`); What's new opens the changelog; the Play Store update check is to do
- [~] Home dock: resting capsule with the placeholder and send button, its fade-in and the list scrim (`:layout` `HomeDockLayout`, focus layout, motion, fade and refusal with the iOS tests)
- [~] Dock composer (`ui/home/HomeDockController.kt`, `HomeDockFocusView.kt`): opens over home with the dim and a faint blur (RenderEffect, Android 12+), the shell growing from the capsule and the rows and card revealing in turn (`HomeDockFocusMotion`), rides the IME, closes on the backdrop or Back. Machine, project (with Enter custom path), worktree and harness pickers and the permission, model and effort chips open `ChoiceMenu` (iOS's inline-section UIMenu as a popup window that keeps the keyboard up); Send runs `SessionStarter` with the progress line, the stop hint, Stop, refusal shakes and flash, then opens the chat. Project + and No Sessions' Start New Session open it too. Evidence: `dock/d12-open.png`, `d20-project-menu.png`, `d22-starting.png`, `d23-chat.png`, `dock-open-menus.mp4`, `dock-start-session.mp4` vs `ios-ref/41-dock-open.png` … `45-dock-harness-menu.png`. Session | Bot | Task kinds, text creation, Bot faces/picture retry and Session/Task dock pictures have newer verified records above. Text heights are still measured on the main thread
- [x] Pending chats placeholder: iOS has it on the chat screen only (PendingChatPlaceholder for a chat opened from the worktree strip's `+`); see Chat › Worktree tab strip and pending chats

### Chat

- [~] Session screen shell: header (back circle with Material's arrow, title capsule with subtitle and line changes, avatar circle grey while offline) over the strong scrim at 0.80/0.96, the dock fade, `ChatScreenLayout` placing it all (`ui/chat/ChatScreen.kt`, `ChatHeaderView.kt`). Evidence: `chat/a03-chat-*.png` vs `ios-ref/30-chat.png`, `chat/a07-top-*.png` vs `ios-ref/32-chat-top.png`, dark `chat/a21-dark-*.png`. Info opens session info; the header follows the checkout's live git status (branch, and +/- counted compactly as on iOS; `GitStatusServices` is asked when the chat opens; `file-viewer/a6-chat-header.png`). The CLI-outdated pill (`ui/chat/ChatCliWarningPill.kt`, `model/SessionInfo.kt` `CliVersionWarning` with iOS's CliVersionWarningTests): a CLI below 0.10.0 shows "CLI Update Required" under the header (and the strip), a tap dismisses it for that version on that machine (`acknowledgedCliVersions`); seeded as `old-cli`. Evidence: `cli-warning/01-pill.png` vs `ios-ref/132-cli-warning.png`, `02-dismissed.png`, `03-stays-dismissed.png`, `cli-warning-dismiss.mp4`
- [~] Inverted list engine: rows planned off main (one planner thread, plans cached per row and width), drawn from their plans in a reverse-layout RecyclerView; newest edge follows within 200dp, the reader's row holds otherwise, an opened or closed tool keeps its top (pinning), the next page loads within a screen of the oldest row (`ChatOlderPaging`: one fetch at a time, judged once its rows are on screen, Retry and Load more at the older end), jump-to-newest button past 300dp, status row hysteresis 48/16 (`ChatListView.kt`). Evidence: `chat/chat-keyboard-send.mp4`, `chat/chat-tool-expand.mp4`. iOS's single-block follow slide (`ChatFollowMotion`) is the item animator's per-row moves here
- [~] Keyboard: the dock rides the IME frame by frame (`ScreenStack`'s `WindowInsetsAnimation` into `ChatScreenLayout.keyboardTop`), and the newest edge stays put as the inset changes (`visibleMinYAfterNewestInsetChange`). Evidence: `chat/a09-kb-*.png`, `chat/chat-keyboard-send.mp4`; with animations on, opening and closing at the newest edge and scrolled up keep the distance from the newest edge (`keyboard/k-strip.png`, `keyboard-open-close.mp4`; closing used to scroll the conversation up by the keyboard height). Interactive dismiss by dragging the list is to do
- [~] Messages: user bubbles (bubble colour setting, pending at 0.45, failed + retry), agent text with copy and the copied tick, events. Evidence: `chat/a03-chat-*.png`
- [x] Markdown: headers, paragraphs, bold/italic/code spans, links, numbered and bulleted lists, tables and code blocks scrolling sideways, syntax highlighting, options (`MarkdownLayout` with its iOS tests). Evidence: `chat/a06-top-*.png`, `chat/and-chat-ab.png`. Mermaid diagrams render with the bundled mermaid.js (same page as iOS) in a sandboxed WebView that reports its height back to the row, and re-render on theme change; markdown images (http(s) through Coil, `data:` inline) draw contained in their rounded box with the alt text below. Evidence: `images-mermaid/a01-light.png`, `a02-dark.png`, `mermaid-image.mp4`, iOS `ios-ref/77-mermaid.png`
- [x] Text selection screen, long-press copy: a long press on a user bubble (with its chip or goal line) offers Copy for the message (LongPressCopyable); on an agent's markdown, Copy for that paragraph, list item, code block or table cell, or with Markdown Copy v2 (local setting `markdownCopyV2`, which iOS shows no toggle for either) the text selection screen for the whole message (`ui/chat/TextSelectionScreen.kt`, `:layout` `chat/TextSelectionLayout.kt` with iOS's TextSelectionLayoutTests): the text in mono 14/20, read-only and selectable with the platform's handles and menu, in a card with a hairline rim, Copy (all of it) in the bar and "Text copied to clipboard"; with no text, an error alert leads back. The message screen keeps iOS's: only its terminal text offers Copy. iOS's edit-menu callout is the platform's popup menu here. Evidence: `text-selection/01-screen.png`, `02-selected.png`, `03-copied.png`, `04-v1-copy-menu.png`, `text-selection-walk.mp4` (the setting turned on in the test account's local settings for the recording)
- [~] Tool calls: compact rows (eye/terminal glyphs, grey), Edit card, tap to open/close (pinned), Bash terminal panel, raw Input/Output sections, errors, running spinner and timer. Evidence: `chat/a19-bash-open-*.png`, `chat/a20-edit-open-*.png`, `chat/chat-tool-expand.mp4`. An opened body (a card's or a compact row's box) and a header with nothing to open lead to the message screen
- [~] Tool views: Bash's terminal panel; Edit, Write, MultiEdit and Gemini edits as DiffChunk (the shared diff engine, Prism colours within the first second, word emphasis, +/− gutter, line numbers from the setting, sideways scroll for long lines, "Show N more lines" past 80 rows) flush under the card's header (`ui/diff/`, `:layout` `diff/DiffLayouts.kt` with iOS's `DiffLayoutTests`). Evidence: `chat/c01-edit-diff.png` vs `ios-ref/37-edit-diff.png`, `chat/chat-edit-diff.mp4`. TodoView (☐/☑ by status, the result's newTodos first, done ones struck through), TaskView (the sub-agent's last three calls with spinner/✓/✕ and "+N more tools"), ExitPlanToolView (the plan as markdown), CodexBashView (a read or write as "Reading <file>" in a box, else the command), GeminiExecuteView (command, 📁 cwd, description); a view with nothing to show leaves the card closed (iOS hasContent). Seeded as tools-demo (an Agent with five child calls, two TodoWrites, an MCP call, ExitPlanMode). Evidence: `tool-views/pair-android-ios.png`, `tool-views/a01..a03` vs `ios-ref/70-72`. Codex/Gemini patch and unified-diff presentations are now implemented and focused-verified in the milestone above. Hunks past the first second stay plain until the row is planned again.
- [~] Permission prompts: the footer under a waiting tool (Yes / Yes, don't ask again for this tool / No, and provide feedback; the Codex, edit and plan sets), the tapped button pulsing while its answer is on its way with no other taking a tap, the answer through `SessionOps.allow`/`deny` and the mode switch after it, the answered button shaded and the rest faded; the status reads "permission required" and the primary turns to the black "!" (`ChatRowRenderer.permissionFooter`, `chat/tools/PermissionOptions.kt`). Evidence: `chat/b02-permission-*.png` vs `ios-ref/33-permission.png`, `chat/b07-answering.png`, `chat/b08-approved.png` vs `ios-ref/36-approved.png`, `chat/chat-permission-approve.mp4` (the seeded `perm-demo` session answers over RPC). A list taller than its cap does not scroll yet; on a tablet the buttons hug their labels at the trailing edge (`PermissionFooterLayout.tablet`)
- [~] Agent questions: the inline form for AskUserQuestion (answered through the permission with the answers merged into the input) and request_user_input (through the communication): header chips, radio and checkbox options, Submit Answer outlined once every question has an answer, a spinner while it goes, the "Header: answer" summary after, the form back if sending fails (`chat/InlineQuestionForm.kt`, `ui/chat/ChatQuestionRenderer.kt`). Evidence: `chat/q01-question.png` vs `ios-ref/38-question.png`, `chat/q02-picked.png`, `chat/q03-submitting.png`, `chat/q04-answered.png`, `chat/chat-question-answer.mp4` (the seeded `ask-demo` session answers over RPC)
- [x] Image and file messages: FileView (the picture at its own shape, at most 280x360, rounded 8, its name under it on one line), downloaded and decrypted with the session blob key, decoded at no more than it draws, kept per account (50 pictures / 48MB LRU, one load per picture, dropped on memory pressure), the thumbhash (`images/ThumbHash.kt` with iOS's vectors) under it, a 150ms fade in, an alert badge when it cannot be had (`ui/chat/ChatAttachmentView.kt`, `attachments/AttachmentPictures.kt`). Seeded as photo-demo (a real encrypted upload). Evidence: `attachments/a02-loaded.png`, `attachment-open.mp4` and offline `a03-offline-thumbhash-badge.png` (the thumbhash under the alert badge) vs `ios-ref/78-attachment.png`
- [~] Message detail screen (session/[id]/message/[messageId].tsx with ToolFullView): titled by ToolHeader (the known title unless the provider named the call, a terminal tool's command, cut in the middle); a terminal tool's CommandView with all its output, bash and shell-session colours on the dark panel within the reveal deadline, "[Command completed with no output]", a shell control's typed input; TaskView; Edit/MultiEdit ("Edit N of M", the Replace All badge, rules between)/Write diffs edge to edge on a phone; otherwise Description, Input Parameters, Output, Error or "Tool completed successfully"; a specialized view's Output or error after it; Raw JSON in debug builds or dev mode, keys sorted as on iOS; a text message's whole text; long text in pieces (`TextChunks`); long press copies a command or its output; a message that's gone sends the reader back (`chat/tools/ToolFullPresentation.kt` and `:layout` `MessageDetailLayout.kt` with the iOS tests, `ui/chat/MessageDetailRenderer.kt`, `MessageDetailScreen.kt`). Evidence: `message-detail/pair-bash-android-ios.png` (vs `ios-ref/74-bash-detail.png`), `message-detail/a01-task.png`, `a03-mcp-generic.png`, `a04-edit-wide.png`, `a05-mcp-open.png` vs `ios-ref/75-mcp-open.png`, `message-detail/message-detail.mp4`. Codex/Gemini patch and unified-diff full views and focused-file navigation are now implemented; see the patch milestone above. Edit/MultiEdit/Write file navigation already exists in `ChatRowRenderer`/`ChatScreen` (the old missing-route statement was stale). Reading-place preservation is wired and verified through actual system font changes, including concurrent-scroll/stale-plan/density regressions; see the message-reading milestone above. Diff line selection and Changes reading-place work remain in the current audit.
- [~] Question banner and answer sheet (AgentQuestionBanner, AgentQuestionModal): the first request the chat can't show inline sits above the composer; a form's card (link-coloured rim, chevron) opens the full-screen sheet, which rises as a modal (`Screen.isModal`): header chips with the orange required dot, checkbox options ringed while picked, the written answer growing with its text, Send answer greyed until every required question is answered and a spinner while it goes, an error under the questions if it fails, Cancel and back dismiss the request, an answer from elsewhere closes it. An unknown kind shows its notice and Dismiss (`ui/chat/ChatDockBarViews.kt`, `AgentQuestionSheetScreen.kt`, `:layout` `QuestionSheetLayout` with the iOS tests). Evidence: `chat-bars/a02h.png` vs `ios-ref/51-form-banner.png`, `chat-bars/a03h.png` vs `ios-ref/52-form-sheet.png`, `chat-bars/a04h.png` vs `ios-ref/53-form-picked.png`, `chat-bars/pair56.png` (sending, then the seed's answer) vs `ios-ref/55-form-answered.png`, `chat-bars/a21h.png` vs `ios-ref/56-unsupported-banner.png`, `chat-bars/question-banner-sheet.mp4` (seeded `form-demo` and `notice-demo`). The bars are measured on the main thread, not off it as on iOS, and a bar coming or going moves the composer without iOS's 0.25s animation
- [~] Goal bar, inactive hint, rig activity bar: the current goal with Edit (a prompt), Stop and Clear, each spinning while its `goal-action` RPC runs; "This session is inactive." with Resume (the shared `SessionResumer`, a spinner, an error alert) or the terminal commands, which a tap copies with a tick for two seconds; Happy Agent's running counts full width; all fade while the reader is scrolled back (`chat/ChatDockBars.kt` with its tests, `ui/chat/ChatDockBarViews.kt`). Evidence: `chat-bars/a09h.png` vs `ios-ref/57-goal-bar.png`, `chat-bars/pair1112.png` (edit in flight, then edited), `chat-bars/a13h.png` (cleared), `chat-bars/pair1617.png` (commands; Resume), `chat-bars/pair1819.png` (resuming, then online again through the seed's `resume-happy-session`). No seeded session shows the rig activity bar yet
- [~] Work group headers: with `groupToolCalls` on, a finished turn's work folds under "Worked 2m3s ›" where its oldest member was; a tap opens it, the rows in reading order and "Hide ⌃" under them, and the turn being watched stays open (`chat/WorkGroups.kt` with its tests, `ChatRowRenderer.workHeader`). Evidence: `chat-bars/work-groups.png` (folded, opened, Hide), the seed run with `--setting groupToolCalls=true`. The expansion does not yet fold when the app goes to the background (`WorkGroupExpansion.backgrounded`)
- [~] Empty and deleted states (`ui/chat/ChatPlaceholderView.kt`, `:layout` `chat/ChatPlaceholderLayout.kt` with iOS's ChatPlaceholderLayoutTests, `chat/ChatEmptyState.kt`, `chat/ChatOlderPaging.kt` with iOS's ChatOlderPagingTests): a spinner until the messages are in; EmptyMessages for a chat with none (the OS glyph, the host, the folder from home, "No messages yet", "Created …"), which pages back on its own while the server has older history, the paging spinner, Retry after a failure and Load more after five pages that showed nothing; the deleted state (trash, "Session has been deleted" and its line) over the whole screen with the dock and the list gone. The list's older end follows the same paging: the spinner, Retry, or Load more after five pages that showed nothing new. The first page never fails here (the catch-up retries on its own), so the loading placeholder's Retry never shows. PendingChatPlaceholder ("Starting a new chat…" shimmering, the queued messages or the folder) shows for a chat opened from the worktree strip's `+`, with the composer kept. Evidence: `empty-state/01-empty-light.png`, `04-empty-dark.png` vs `ios-ref/130-empty-chat.png` (iOS's Empty session fixture; the seed's sessions name no OS, so the chip glyph), `02-deleted-light.png`, `03-deleted-dark.png` (a push tap for a session that is not there), `05-chat-with-rows.png`, `empty-walk.mp4`
- [x] Worktree tab strip and pending chats (`ui/chat/ChatWorktreeTabStrip.kt`, `:layout` `chat/WorktreeTabStripLayout.kt` with iOS's WorktreeTabStripLayoutTests, `home/ChatPendingStarts.kt`, `home/PendingChatStore.kt` with `PendingChatLaunchTest` after iOS's PendingChatLaunchTests): grouped by project, a chat in a checkout shows its chats as tabs in a capsule under the header (the header controls' fill on a phone, the solid surface with a hairline on a tablet), oldest first, the open one a filled chip with its name semibold, a working one shimmering, a blocked one with the pulsing orange dot, an unread one blue; the header names the checkout (its workspace, else the branch, else the project) over "N tabs". A tap swaps the chat in place (back still goes home), keeping the strip where the finger left it, per checkout and account; a long press opens the Session alert, and archiving the open tab moves to its neighbour first. `+` opens a stand-in at once ("Starting a new chat…" shimmering, the folder, or what was sent meanwhile), its start running underneath on a starter of its own, one after another; the stand-in's composer keeps its draft and queues what is sent, and when the machine answers the messages go out in order, the rest becomes the session's draft, and the chat takes the stand-in's place with the text, the caret and the keyboard. A start that fails or is declined gives the writing back to the chat it was opened beside and goes back there. iOS retargets the one screen in place; Android puts the chat's screen in the stand-in's place, carrying the text and focus across. The names are measured as the strip lays out, on the main thread. Evidence: `worktree-tabs/01-strip.png` vs `ios-ref/131-worktree-strip.png`, `02-switched.png`, `03-scrolled.png`, `04-long-press.png`, `strip-walk.mp4` (switching, scrolling, the long press, back home); `06-plus-pending.png`, `09-queued.png`, `10-delivered.png`, `plus-walk.mp4` (`+`, a message sent and more typed while the seed's machine took 7 s, `SEED_SPAWN_DELAY_MS`, then the chat arriving with both)
- [~] Composer: status row (online / thinking word / permission / last seen, pulsing dot), growing input (`AgentInputCardLayout`), send / stop / blocked / voice primary, permission, model and effort chips opening the platform's popup menu with the current pick checked, picks shown at once and written through `SessionComposer`, drafts synced, shake on refusal (`ui/composer/`). Evidence: `chat/a10-typed-*.png`, `chat/a11-sent-*.png`, `chat/a15-perm-menu-*.png`, `chat/a16-model-menu-*.png`, `chat/a12-model-menu-*.png`. The usage row, the full-screen editor and voice are to do
- [x] Autocomplete: slash commands, file mentions (`ui/composer/ComposerSuggestionListView.kt`, `:layout` `composer/ComposerSuggestionLayout.kt` with iOS's ComposerSuggestionLayoutTests, the logic in `autocomplete/`): the word at the caret starting with `/` or `@` opens the list above the composer (fades and rises in 190 ms, out in 140 ms), commands with their descriptions, files and folders from the session's ripgrep with FILE / FOLDER; the first row highlighted; a tap, Return (no new line) or a hardware Tab puts the pick and a space in place of the word; an older query's answer is dropped and the last list stays while a newer one runs, as on iOS; a draft ending in "/co" opens on its list. iOS's glass is a solid fill sampled from the iPad (#EBEBED, dark #060606) with the rim and an elevation shadow; LocalBlurHalo (a blur of the chat around the list) is left out, as Android can't blur what is behind a view. The seed daemon now answers `ripgrep` from its fixture. Evidence: `autocomplete/01-commands.png` … `06-picked-file.png`, `autocomplete-walk.mp4` vs `ios-ref/114-ipad-autocomplete-commands.png`, `115-ipad-autocomplete-co.png`, `117-ipad-autocomplete-dark.png`
- [x] Attachments: + asks Paste from Clipboard / Photo Library (`ui/composer/ComposerImagePicker.kt`); the library is the system photo picker (no storage permission; multi-select up to what is left of 20); a picture pasted into the text or inserted by the keyboard is attached too (`OnReceiveContentListener`); each is made an upright JPEG (long edge 3072, 0.92) in a per-composer staging folder; the strip of 64dp thumbnails with remove buttons over the text, + turning blue (`AttachmentStripView.kt`); limit, too-large, nothing-to-paste, not-supported and upload-failed alerts; Send encrypts and uploads them, then queues the file events ahead of the text. Evidence: `attachments/b01-add-dialog.png` … `b06-nothing-to-paste.png`, `composer-attach-send.mp4`. As on iOS, Send copies the pictures into the outbox (`attachments` table, files under `files/outbox`) and queues their file events, naming no blob yet, ahead of the text; the flush uploads each (sealed with the session blob key) before anything after it goes, tried 5 times 1 s doubling to 60 s apart, and one that fails for good is skipped so the text still goes, the composer saying so once (`newlyFailedAttachments`); Retry tries it again with its attempts cleared. A send made offline keeps its pictures and they go when the connection is back. Evidence: `attachments/c-pair-offline-online.png`, `c02-online-sent.png`, `offline-send.mp4`; `MessageSenderTest` (upload retried at flush, refused picture skipped then retried), `ChatFeedTest` (the row rewritten in place shows its ref)

### Settings

- [x] Settings index (`ui/settings/SettingsScreen.kt`, iOS `SettingsViewController`): the HAPPY logo card (or the profile header), scan / enter terminal URL, Support us, Connected Accounts (Claude Code, GitHub with connect and disconnect), Features (Account, Appearance, Agents, Voice Assistant), About (What's New, GitHub, Report an Issue, Privacy, Terms, Version; ten taps on Version toggle dev mode). EULA is iOS-only and left out. Every pushed bar is opaque in the grouped background, as `AppCoordinator.applyAppearance` makes them. Evidence: `settings/01-index-top.png`, `02-index-mid.png` vs `ios-ref/81-settings-top.png`, `81-settings-mid.png`; `settings/settings-walk.mp4`
- [~] Account: account information (analytics and public id, long press copies), Link New Device, profile (name, GitHub with picture), connected services with disconnect, Backup (reveal, the secret key card, tap to copy with the Success alert), Analytics, push rows, Logout, which hands over to the next signed-in account by the order they were added. Evidence: `settings/10-account.png`, `11-account-bottom.png`, `13-account-secret.png` vs `ios-ref/82-account*.png`; the Accounts group at its top is the switcher below
- [x] Account switcher (`ui/settings/AccountScreen.kt`, `model/settings/SettingsPages.kt` `AccountSwitcherEntry` with iOS's AccountSwitcherEntryTests): Settings › Account opens on the Accounts group, a row per signed-in account (its picture, name or "Account <id>", the server's host), the active one checked; a tap switches, with a spinner on the row while it goes, and the screens are rebuilt for that account at home, as iOS installs a new root. Touch and hold offers Sign Out (a `PopupMenu` for iOS's context menu), confirmed when it is not the active account. Add Account puts Welcome up as a sheet with Cancel over the signed-in account, which stays as it is if it is cancelled; the account signed in there is added and made active. Evidence: `accounts/01-one-account.png`, `02-add-account-sheet.png`, `03-two-accounts.png`, `04-long-press-sign-out.png`, `05-sign-out-confirm.png`; `switch-walk.mp4` (both ways between the two seed accounts) and `add-and-sign-out-walk.mp4` (Add Account with the main seed's secret key, a switch back, the other one signed out)
- [x] Appearance: theme cycle, language, quota and bubble colour (the inline dropdown with previews), avatar style and black-and-white, usage status, grouping, compact tool calls, file diffs, group tool calls and the rest of the display switches; the switches are the platform's. iOS's Home and Chat list renderer rows are iOS-only and left out. Evidence: `settings/20-appearance.png`, `21-appearance-bottom.png`, `22-appearance-bubble-open.png` vs `ios-ref/83-appearance*.png`. Home's filter menu opens it
- [x] Language: Automatic plus the ten catalogs, a checkmark, the restart confirmation; the choice applies through the activity's configuration. Evidence: `settings/80-language.png`
- [x] Agents: machines (online and offline, show offline), Clear Overrides, per-agent permission, model and effort with the inline options and the custom Codex model, computed off main. Evidence: `settings/30-agents.png` vs `ios-ref/84-agents.png`. A machine row opens the machine screen
- [x] Voice settings and voice language: usage (spinner, then the unavailable row that retries), Upgrade Voice, preferred language with the searchable list, ElevenLabs agent id, direct connection. Evidence: `settings/40-voice.png`, `41-voice-later.png` vs `ios-ref/85-voice.png`. The bring-your-own footer is happy-app's English catalog text, longer than iOS's inline English
- [x] Connect Claude (`settings/70-connect-claude.png`)
- [x] What's new: the bundled changelog planned off main, bold and links, long press copies; opening it marks the latest entry read (home's What's new banner opens it too). Evidence: `settings/50-whatsnew.png` vs `ios-ref/87-whatsnew.png` (iOS's bar shows only the picture; the title view's label is there in its source, so Android shows both)
- [x] Support us: iOS's interim page with Google Play for managing subscriptions. Evidence: `settings/60-support.png` vs `ios-ref/86-support.png`
- [x] Push notification rows: permission, request again, re-register, registered tokens with delete; the copy says Android where happy-app says iOS

### Secondary

- [x] Session info (`ui/details/SessionInfoScreen.kt`, `model/details/SessionInfo.kt` with iOS's SessionDetails / SessionInfoModel / quick action tests, `CenteredStackLayout`, `HorizontalStackLayout`, `CodeBlockLayout` with iOS's SecondaryLayoutTests): quick actions (Changes with changed files and line counts, View Machine, Resume with its reasons, Fork with spinner, Duplicate, Forked from, Leave task, Archive / Archive task, Delete with confirm), the CLI warning, details (ids copy with an alert, the resume command copies quietly and shows a check for 2 s), metadata with Copy metadata (& Client Logs), agent state, activity, and in debug builds the raw JSON drawn in pieces near the screen. The worktree prompt runs before archive and delete; both leave the screen and the chat. A deleted session shows the trash message; until the session is stored a Loading group shows, with Retry / Copy diagnostics after 5 s and in the bar's ⋯ menu. Groups are worked out off main and rebuilt only when what they show changed. Opened from the chat header's avatar. Evidence: `session-info/pair-1.png` vs `ios-ref/91-info-1.png`, `info-234.png` vs `ios-ref/91-info-234.png`, `pair-agent-state.png`, `05-copied.png`, `06-menu.png`, `07-delete-confirm.png`, `08-view-machine.png`, `10-offline.png`, `11/12-resume-*.png`, `13-forking.png`, `session-info-walk.mp4`; archive and delete run on the emulator against the dev server (both return home, the row goes); Fork runs end to end against the seed daemon (`tools/dev/seed-local-server.ts` now answers the fork, duplicate and rewind-point RPCs) and opens the new chat (`duplicate/duplicate-and-fork.mp4`). Changes opens the changes screen
- [x] Duplicate sheet (`ui/details/DuplicateSheet.kt`, `model/details/DuplicateSheet.kt` with iOS's DuplicateSheetModelTests, `DuplicateSheetLayout` with iOS's DuplicateSheetLayoutTests and DuplicateSheetTextSizeLayoutTests): rewind points newest first with their age, spinner, the provider's error or the empty copy, a chosen row on the high surface, Duplicate disabled until one is chosen and "Loading..." while it forks; the new session replaces session info (`ScreenStack.replaceTop`). iOS's full-screen glass modal is a full-screen dialog here: the window behind is blurred (Android 12+) and dimmed, and the card is the glass overlay colour over the surface. Evidence: `duplicate/pair-points.png` vs `ios-ref/94-duplicate-selected.png`, `duplicate/pair-result.png` vs `ios-ref/93-duplicate-result.png`, `04-points.png`…`07-new-chat.png`, `duplicate-and-fork.mp4`
- [x] Machine screen (`ui/machine/MachineScreen.kt`, `model/details/MachineDetails.kt` with iOS's `MachineDetailsModelTests`): status, Stop Daemon (confirm, spinner, result alert), daemon pid/port/start/CLI version, CLI availability, the five newest sessions opening their chat, the machine's details, Delete (confirm, spinner, pops), the pencil renaming it (metadata `displayName`, empty restores the host), "Machine not found". Rows built off main and only when what they show changes. Opened from Settings › Agents. Evidence: `machine/01-machine.png`, `02-machine-bottom.png` vs `ios-ref/90-machine*.png`, `machine/03-delete-confirm.png`, `04-rename.png`; a rename round trip on the emulator. iOS's pull to refresh is left out (updates arrive over the socket)
- [x] New session screen (`ui/newsession/NewSessionScreen.kt`, `NewSessionViews.kt`, `model/NewSessionScreenModel.kt` with iOS's NewSessionScreenModelTests, `:layout` `NewSessionLayout.kt` with iOS's NewSessionLayoutTests): the title capsule beside Back, machine / path / worktree rows over the composer at the bottom, riding the keyboard; the machine row's online or last-seen status and the offline card (Happy CLI / Happy Agent help); popovers over the rows or the composer growing in from their anchor (0.96, 8 dp, 190 ms): machines and agents with search past four rows, the path field with "using project" / custom path and Recent, worktrees, and Settings with the permission, model and effort pages; Return in the path field closes it. Send runs `SessionStarter` (spinner on the button, directory confirm) and the chat takes the screen's place; leaving cancels a start begun here. Opened by No Sessions' Start New Session and by home's new workspace when the dock is not there. iOS's liquid glass is a solid fill sampled from the simulator with a 1 px rim and a shadow; Back is the platform arrow, not iOS's "‹ Back" pill. Deleting a session now drops it from the store as soon as the server agrees (iOS `presence.deleteSession`) instead of waiting for the update. Evidence: `new-session/android-p1.png`…`p10.png`, dark `android-d1.png`…`d3.png`, `android-c1.png` (the chat it opened) vs `new-session/98-new-session.png`, `98-ns-machine/path/worktree/agent/settings.png`, `98-*-dark.png`; `new-session-walk.mp4` (machine, a typed path, worktree, codex, Read, Send, the answer), on the `--machine-only` seed account
- [x] Changes (`ui/details/SessionChangesScreen.kt`, `ui/diff/DiffFilesList.kt`, `:layout` `diff/DiffListLayout.kt` with iOS's DiffFileHeader/DiffImage/DiffSection/DiffFilesList layout tests, `DiffFileItem.body` and `DiffFilesCollapse` with iOS's DiffFileItemTests, `:icons` `FileIcons` with iOS's FileIconsTests drawn by `FileIconView`): a CLI session's uncommitted files from git status over the shell, a Happy Agent session's snapshot against its base (gitState, readFile, readFileAtRevision; a file's sides read when it opens, two at a time) with its notices; every file a section with chevron, Seti icon, path (the directory gives way first), NEW/DELETED/RENAMED and +N/−N; files start closed, one past 2000 changed lines says so and opens on a tap; open files show the diff (600 rows before Show more), Before/After pictures, a message or a spinner; "⤢ N unchanged" widens the file to its whole context. Sections are planned off main and kept per width and text size; a long file draws only the rows near the screen. Spinner, the update-Happy-Agent / comparison-unavailable / error messages with Retry, and a fresh read on returning to the screen. The seed daemon now serves both from a real git repository (`.context/tmp/changes-fixture`, a Happy Agent session "Tune the reconnect backoff"). Evidence: `changes/pair-open.png` vs `ios-ref/95-changes-open.png`, `changes/a1.png` vs `ios-ref/95-changes-collapsed.png`, `changes/r2.png` vs `ios-ref/96-agent-changes.png`, `changes/a3.png` (context widened), `a4.png` (the big file opened), `r3.png`, `changes-walk.mp4`. Not yet: long press to copy a code line (iOS `selectable`), and keeping the reader's line through a text size change (`DiffReadingPlace`)
- [x] File viewer (`ui/details/SessionFileScreen.kt`, `ui/diff/FileLines.kt`, `:layout` `FileViewerLayout.kt` with iOS's FileViewerLayoutTests, `files/FileViewer.kt` with iOS's FileViewerTests): an Edit, MultiEdit or Write card's body opens its file (iOS's `openFile`); the header's Seti icon and path, Diff / File when the file has uncommitted changes (`git diff` over the shell, 5 s), DiffDisplay rows (added and removed with their bar, hunks, headers) and the syntax-coloured file, one row per line measured off main per width and text size and kept for both modes, rows made only near the screen in a RecyclerView; a long press on the file offers Copy for all of it; Loading, Error (the alert once on screen), Binary File, File is empty and No changes. A revisit draws the account's cached copy at once and reads again behind it; the session's root is read off main and remembered. Reads are kept on disk across launches as iOS keeps them (`sync/SessionFileCache.kt`, iOS's SessionFileCacheTests ported: 32 reads / 8 MB in memory, 256 files / 64 MB on disk, 4 MB an entry, SHA-256 names, one writer thread with coalesced bounded waiting work, pruned by last use) in `files/accounts/<anonId>/file-cache`, made ready as the account starts, closed when it stops and wiped on logout; after a relaunch the kept copy is read off main before the computer is asked, and offline it stays under the error alert. Evidence: `file-viewer/a1-diff.png` vs `ios-ref/97-file-diff.png`, `a2-code.png` vs `ios-ref/97-file-code.png`, dark `pair-a9-diff-dark.png` and `pair-a10-code-dark.png` (iOS left), `a3-copy-menu.png`, `a4-scrolled.png`, `a7-offline-relaunch.png` and `a8-offline-alert.png` (relaunched with the seed daemon stopped), `file-walk.mp4`
- [~] Message detail (see Chat), text selection

### Platform

- [x] Several accounts with separate stores (`auth/AccountRegistry.kt`, `AuthRepository`, `AppGraph`, `AccountsTest`, `AccountSwitchTest`): iOS's `accounts.json` registry (no secrets; the first account's credentials stay in happy-app's shared secure-store entry, the others in their own), and per account its database, local settings and file cache under `files/accounts/<id>/`. The store and local settings are switched under the screens and home is rebuilt only once they are the new account's; home's warm list is dropped on the switch, so no screen shows another account's sessions. Each account talks to its own server. Signing one out drops its push token, empties and closes its database and deletes its directory and preferences, as iOS erases, closes and removes it. The one account a device had before is adopted on the first launch, data kept. Evidence: as the switcher, and the files on the device after each sign-out
- [~] Push: registration, presentation, open from a notification. A tapped notification opens home then its chat, from a cold start or over any screen, and nothing when that chat is on top (iOS `openNotificationSession`); signed out it is dropped. Evidence: `deep-links/05-push-cold-start.png`, the end of `deep-links-walk.mp4` (the tap intent sent by adb). Registration (`startPushRegistration`) asks for POST_NOTIFICATIONS while it can still be asked and registers the Expo token on sign-in and every foreground, as iOS `PushTokenSync`; it needs Firebase in the build (`app/google-services.json`), which the dev build lacks, so it is not checked on the emulator. A tapped session held by another signed-in account switches to it first and its home opens the chat, from a cold start or warm (iOS `accountHoldingSession`, `AppGraph.accountHoldingSession`: the active store, then the others by the order they were added, read in place); one in no store opens in the active account. Evidence: `accounts/06-push-cold-other-account.png`, `07-push-warm-other-account.png`, `push-other-account-walk.mp4`
- [x] Deep-link routing (`connect/DeepLinks.kt`, `ui/AppCoordinator.kt`, `DeepLinksTest`): `happy://terminal?<key>` asks Authenticate Terminal and `happy:///account?<key>` Link New Device (Continue / Cancel), then approve and report as a scan does; signed out, Failed to connect terminal. `happy://direct` is classified first, including malformed codes, and opens the Direct pairing flow (see its separate verification limits below). Other links open nothing. Auth-v1 terminals are refused, never handed the account secret (`TerminalNeedsUpdateException`, `AccountLinkingTest`). Relay approval/refusal/device linking were checked against the dev server. The release build keeps the verified `app.happy.engineering` App Link; guest invitations remain unported. Evidence: `deep-links/01-terminal-confirm.png`…`04-account-linked.png`, `deep-links-walk.mp4`; `direct-shell/06-invalid-link.png`.
- [x] Light/dark theme: Appearance's theme cycle (adaptive, light, dark) and, when adaptive, the system's night mode followed live while the app is open (`Theme.install` reads the activity's current configuration, not the frozen language context; `ThemeConfigurationTest`); the system bars follow. Evidence: `home-actions/05-swipe-open-dark.png`, `06-session-alert-dark.png`, the dark pairs under Chat and Secondary
- [x] Font scale (Dynamic Type equivalent): the system font size scales text where happy-ios follows Dynamic Type (`docs/DYNAMIC-TYPE.md`): a screen's `StyledLabel`s scale, and Header.tsx's plain title (16) scales on the screens that use it (`NavigationBar.plainTitle`: Settings, What's New, Welcome, home's link state, session info, machine, changes, message detail), while the bar's own native title (17), home's header and its dock stay at their designed size (`Label.followsTextSize = false`). A live change measures every view again (`Theme.applyTree(relayout = true)`). `LabelTextSizeTest`. Evidence: `theme/01-font-scale-1.3-live.png`, `02-home-1.353.png` vs `ios-ref/101-home-xxxl.png`, `03-settings-1.353.png`, `04-settings-1.353-2.png` vs `ios-ref/101-settings-xxxl*.png`, `05-appearance-1.353.png`
- [~] Tablet shell (sidebar + detail): at 768 dp and wider the sidebar (New session, the archived toggle, home's banners and rows, Settings at the foot, the selected session highlighted, the no-sessions / offline / no-machines states) sits beside the stack under the zen and back controls; zen collapses it; home draws nothing beside it; Header.tsx screens drop their back button; chat gets the plain header (title and subtitle at the start, opens session info), the docked composer with its details always shown and the trailing permission buttons; content capped at 800 on a 13-inch tablet (`ui/tablet/`, `layout/TabletLayout.kt`, `TabletLayoutTest`, `TabletSidebarStateTest`, `ChatPlainHeaderLayoutTest`). Evidence: `tablet/01-sidebar-list.png` vs `ios-ref/110-ipad-sidebar.png`, `tablet/02-chat.png`, `tablet/04-zen-off.png` vs `ios-ref/111-ipad-chat.png`, `tablet/03-zen.png`, `tablet/05-back.png`, `tablet/06-new-session.png` vs `ios-ref/112-ipad-new-session.png`. In a window wider than 700 the chat's composer is the flat panel (`AgentInputDesktopLayout`, `ui/composer/AgentInputDesktopControls.kt`): the gear with every choice under its heading (permission, each provider's models, effort), Stop whenever the agent can be stopped, the image button, and a send of its own (locked, filled with text, grey without); zen leaves send alone (`AgentInputDesktopLayoutTest`, `AgentInputDesktopSendTest`, `ComposerSettingsMenuTest`). Evidence: `tablet/07-wide-composer.png`, `08-wide-composer-gear.png` vs `ios-ref/113-ipad-wide-composer.png`, `09-wide-composer-menu.png`, `10-wide-composer-zen.png`, `tablet/wide-composer.mp4`, `tablet/tablet-shell.mp4`. To do: the grouped sidebar list
- [~] Voice UI (`ui/voice/VoiceCoordinator.kt`, `VoiceStatusScreen.kt`, `voice/VoiceCallState.kt` with `VoiceCallStateTest`, `voice/ActivityMicrophonePermission.kt`): the composer's microphone starts a call for its chat and opens the voice screen (iOS's page sheet is a modal screen here): the status (a spinner while connecting, listening / you're speaking / Happy is speaking / muted), mute, Stop voice, and after a failed start what to do (Open Settings for the microphone, Upgrade Voice, the limit and unavailable messages), under iOS's footer. Closing it leaves the call running; the composer's status row then reads "Voice Assistant · …" in blue and reopens it. A chat coming on screen refocuses a running call; signing out and going to the background (an open call) end it. Evidence: `voice/02-mic-denied.png`, `03-connecting.png`, `05-composer-status.png`, `06-unavailable.png`, `voice/voice-walk.mp4`. The self-hosted server has no voice service, so an open call (mute, the speaking states) is not yet seen on a device

### Development and preview builds only (iOS `StemCellsPreference`)

- [~] Stem Cells home: sidebar, rows, hold preview, new work. Views (`ui/stemcells/`): `StemCellsDirectHomeScreen` (home's header with the computer's name, search and settings marks) over `StemCellsHomeView` (the sidebar, refusal banner, empty state and new-work dock), `StemCellsSidebarView` (rows keyed by ID, connectors, tap/open, fold, section + and inline rename), `StemCellsRowView` (stem cell, clay, glyph and picture marks; Figtree and JetBrains Mono; unread bold; working shimmer), with fold and new-work haptics. The real shell opens `StemCellsHoldOverlay` with a read-only conversation preview and actual rename/leave/archive/close/delete actions; `StemCellsReorderOverlay` carries row/section blocks from the menu or the same held pointer, with edge scrolling and cancellation. See the sidebar verification records above. Hatch, new-work transition and the Stories row on the real home are verified in their records above; header rename remains pending. Dock model/effort picks persist offline and use the conversation's panels. Earlier evidence: `stem-cells/side-by-side-home.png` (iOS reference left), `02-tasks-folded.png`, `03-new-work.png`, `home-fold-shimmer.mp4`; new evidence under `sidebar-hold/`, `sidebar-reorder/`, `new-work/` and `stories-home/`. Core: `StemCellsStore` (document edits/diff, new work, put away, rename, Prioritizing, attention, places, offline), `StemCellsRows`, `StemCellsDrag`/`StemCellsDrop`, `StemCellsWarmth`, with iOS's tests; pure `:layout` `stemcells/` frames and tests. Verified against fakes only, including the isolated real MainActivity shell, not a live daemon.
- [~] Stem Cell chat, header, picker panels, attach sheet, session detail with shared media. The conversation (`StemCellChatScreen`, opened from a row or as new work over home): the glass header (back, the name in its capsule with the agent's status under it, the scrim coming in as the transcript goes under), the intro (stem cell or clay mark over its line, kept in place once the work has begun), rows planned off the main thread (`StemCellChatPlanner`: Figtree markdown, the person's bubble with its photos and Not sent / retry, another agent's message on one line opening in place, galleries and media trees in `ChatMediaView` tiles over `StemCellMediaLoader`, file and artifact cards), the working line (braille loader, the turn's clock, the phase under the sheen, a wait's ring and countdown, a question's mark), desktop's send lift and the room held for the reply, steering waiting under the working line, the way back to the newest message (shimmering while the agent works), the composer riding the keyboard with send, stop, drafts and photos (the system picker), older history paged in near the top, new work put away when left empty. The model and effort chips (`StemCellModelPickers`, `StemCellPickerPanel.kt`, `StemCellModelState`): each opens its own panel over the chip, as iOS's: services under their headings with each account's week and session rings, the picked line checked and opened on, Latest benchmarks; a heading stacks its accounts over the list with the shown account's windows, resets and how current they are; the effort list; a pick shows at once, goes to the conversation (`setMode`) or the dock, plays the pick haptic; a touch outside or Back closes the top panel; usage is read again as a list opens. iOS's liquid glass is the raised surface with its shadow. Evidence: `models/side-by-side-accounts.png`, `side-by-side-efforts.png`, `side-by-side-chat-models.png` (Android left, the iOS reference host right), `02-dock-models.png`, `06-chat-gpt-sol.png`; `StemCellModelPickersTest`. The "+" sheet (`StemCellAttachSheet`): Photos, the title and the close circle, the camera tile, Paste from clipboard (a picture attaches, text goes in at the cursor), Add files (the system's document picker, pictures), and the permission with each mode's description in a menu over the sheet; it rises over a dim and goes on close, the dim, Back or a drag down; the keyboard goes while it is up and comes back after; the dock's "+" makes new work with the sheet over it. iOS's strip also lists the newest photos, read with the photo library permission; Android keeps to the system photo picker (no storage permission), so the strip holds the camera alone and Photos opens the picker. The camera is the camera app, handed a file through the app's FileProvider. Evidence: `attach/01-sheet.png`, `menu-and-picked.png`, `07-camera-attached.png` (a picture taken in the emulator's camera, in the composer), `dock-plus.png`, `attach-sheet.mp4`; the iOS reference half (`attach/side-by-side-sheet.png`) still has the simulator's photo-library prompt over it. A picture opens in `StemCellPictureViewer` over the conversation: the conversation's pictures in order from the one tapped, paged sideways, pinch and double-tap zoom (to 4×) with pan, a tap or the close circle or Back or a drag up or down closing it, the status bar hidden while it is up. A recording plays in `StemCellVideoPlayer` (the system's `VideoView` and its controls, fitted on black), and a document or file goes to the app that opens it (`ACTION_VIEW` on its bytes kept in `cache/media`, through the FileProvider), as iOS's Quick Look does. The fixture now presents a recording and a PDF (`tcpreview`, served from the harness's assets on both platforms). Evidence: `viewer/viewer-steps.png`, `14-viewer-over-jump.png`, `11-video.png`, `12-file.png` (the PDF in the emulator's viewer), `side-by-side-media.png` (the recording's poster and the PDF card, Android left, iOS right), `viewer-pictures.mp4`, `viewer-video-file.mp4`. An artifact card opens `StemCellArtifactScreen` under the app's bar: a Markdown entry planned as the conversation's agent text, any other entry in a `WebView` whose every request is answered from the version's files through the computer (`StemCellArtifactFiles`: `https://artifact.happy.invalid/<version>/<path>`, a reserved name nothing on the network answers, whole files a range at a time as one stream, a player's ranges as parts, the daemon's type and content security policy; anything else refused, no cache, no storage), a live page and a deleted or unreadable artifact saying so. Desktop's Artifacts row (a computer without Stem Cells) opens `DirectArtifactsScreen`, the catalog with its last page kept offline, paging on at its end. The fixture gains an HTML artifact with its stylesheet and script (`arshops` in Research Assistant), and the harness a `classic` extra for desktop's sidebar. Evidence: `artifact/side-by-side-artifacts.png` (Android, iOS; the page and the Markdown), `01-research.png` vs `ios-research.png`, `06-artifacts-list.png`, `artifact-walk.mp4`; `StemCellArtifactFilesTest`. The working line's clock is placed again on each tick (it was cut to "34m 1…" when its digits widened). The read-only sidebar hold preview now uses this renderer (see the sidebar verification record). Not yet: sidebar proposals' cards (their loading line shows), stories inline and rename in the header. The intro hatch is verified in its record above. Evidence: `stem-chat/side-by-side-android-release.png` (Android left, the iOS reference host right, on the same fixture), `01-happy.png` vs `ios-01-happy.png`, `03-sent-working.png`, `04-replied.png` (the lift), `06-new-work.png`. Core: `StemCellConversation` (history, paging, sending through the outbox, drafts, modes, abort, kept offline), `StemCellMarkdown`, media pieces, `StemCellsSessionDetail`, `StemCellsShared`, models and usage, with iOS's tests; its frames in `:layout` `stemcells/` (messages, media and tree layouts, header, picker panels, attach sheet, working line, session detail, settings, QR sheet) with iOS's layout tests. Verified against fakes only
- [x] Stem Cells Surfaces (`StemCellsSurfaceScreen`, iOS `StemCellsSurfaceViewController`), opened from a Surface row under the app's bar: a todo list live (its active todos, then the completed ones ticked, faded and struck through; "Add a todo" adds at the top, Return ending the editing), a document's Markdown read-only and read again on a pull, any other kind saying to open it on the computer; the row's renamed name follows into the bar. Laid out by `:layout`'s `StemCellsTodoLayout` and `StemCellsSurfaceBodyLayout`. Evidence: `surfaces/side-by-side-surfaces.png` (Groceries and Release checklist, Android, iOS), `05-added-done.png`, `surfaces-walk.mp4`. Verified against fakes only
- [x] Stem Cell session detail (`StemCellSessionDetailScreen`, iOS `StemCellSessionDetailViewController`), pushed from the conversation's capsule under the glass bar (`StemCellsGlassBarView`, back in its glass circle, the scrim over what scrolls under it): the mark (stem cell, clay, Happy, picture or glyph), the name and its kind and state; model, effort and when it started as tiles (a value too wide shrinks to 0.6); Rename for a task or a bot, the name becoming a field in place (Done or a tap away keeps it; shown at once and held until the sidebar says the same, which the family tree and the conversation's header then show); MEDIA, the conversation's pictures, recordings and files three to a row (a recording's poster under the play button, a file's glyph and name), from the transcript in hand and history read back a page at a time, Show more for twelve more; LINKS, with Show more; FAMILY, the sidebar's own rows in a card (`StemCellsFamily`); ACTIVITY, the children at work and the running terminals with their age, then Completed folding the settled children; read again as the conversation's events come (`StemCellsSessionDetailFeed`, 400 ms after a burst, again 2 s after a read that events overtook). A picture opens the viewer paging through every shared picture, a recording the player, a file another app (`StemCellMediaOpener`, now the conversation's too); a family row or a child opens its conversation over the detail. The fold opens and closes over 0.25 s with UIKit's ease-in-out, the rows placed where they open first (`fold.mp4`, `fold-frames.png`). Evidence: `detail/side-by-side-top.png`, `side-by-side-end.png` (Android left, the iOS reference host's `-open spandroid -detail [-end]` right), `rename-typing.png`, `completed-open.png`, `chat-renamed.png`, `video.png`, `picture.png`, `detail-flow.mp4`. Verified against fakes only
- [~] Stem Cells motion: the composer grows and shrinks over 0.2 s when typed text changes its height, and only then (`AgentInputCardView.animatesHeightChange`, as iOS's; chips, pictures and a draft shown from elsewhere take theirs at once), the transcript's foot and the jump button riding with it and the new line coming into view; steering taken up glides into the transcript over 0.25 s as the working line moves under it; a settings or detail row's pressed fill fades over 0.2 s when the finger lifts (`StemCellsPressFill`); the composer's add and send buttons swell to 1.025 at 0.7 opacity and spring back (iOS `ComposerPressableButton`). Home's dock and the sidebar's folds snap on iOS too. The sidebar now glides during carries; see `sidebar-reorder/`. The hatch now runs in the sidebar and intro (see its verification record above). The dock/new-work transition is verified in its record above; broader motion parity is still tracked as partial. Earlier evidence: `animations/composer-grow.mp4`, `composer-grow-frames.png`, `steering-taken.mp4`, `steering-frames.png`, `press.mp4`, `swell-frames.png`
- [~] Stem Cells settings and QR sheet (`StemCellsSettingsScreen`, `StemCellsQRSheet`): glass bar/search transitions, profile carousel, rename, status, Hide projects, Stories availability, Direct connection, per-account theme, adding accounts/computers, About, developer-mode taps, confirmed removal; relay pages/connections/sign-out. `AppStemCellsSettingsHost` now supplies real account operations and destination screens. Settings stays above the new home during a profile switch, as on iOS. The QR sheet's scan/paste paths invoke real pairing, not the standalone harness's action recorder. The real MainActivity shell was checked with isolated fixture computers and a fixture relay account: profile switching, Appearance, QR/manual-link rejection, theme across process restart, disconnect and handover; `direct-shell/01-main-home.png`…`10-after-removal.png`, `direct-shell-switch.mp4`, `direct-shell-remove.mp4`. Earlier UI comparisons remain in `settings/` (`android-1.png` vs `ios-1.png`, QR/search/dark/rename comparisons, `settings-flow.mp4`). The mark is 28/44 dp as in the corrected iOS reference (`mark-side-by-side.png`). Real OAuth/service connections and real-daemon account operations are not verified; fixtures are not evidence of those.
- [x] Stem Cells search (`StemCellsSearchScreen`, iOS `StemCellsSearchViewController`), from home's search mark or desktop's Search row: the raised field under the bar taking the keyboard as it opens, every row of the sidebar (folded away or not) whose name holds the query, case and accents aside, drawn flat by the sidebar's own view; a result opens as its row does, over the search; scrolling puts the keyboard away; the clear circle once there is text. Evidence: `search/side-by-side-search.png` (empty and "re", Android, iOS), `03-opened.png`. Verified against fakes only
- [~] Direct pairing and real app shell: `AppGraph.direct` shares the Keystore-backed secure store with relay auth, restores at startup, follows foreground/background, and owns one sync/offline directory and local preferences per computer. `AppCoordinator` opens `StemCellsDirectHomeScreen`; relay sync pauses while Direct is selected. Switching, removing a computer and falling back to remaining accounts are wired. `DirectPairingScreen` ports the connecting → four emoji → confirmation/wait flow, expiry/background/back cancellation, and error states; only a verified daemon MAC yields saved credentials. Core crypto/wire/storage/outbox tests remain in `direct/` and `:crypto`; `DirectShellTest` covers restoration, per-account preferences, directory removal and relay sign-out isolation. Device evidence so far is fake-backed real-shell verification, **not a real daemon connection**. Tailcat is linked from the source-built, gitignored AAR (818e7f171); release/preview require it. The existing device probe verified library loading, malformed-address rejection (26 ms), an unreachable synthetic address timing out (3.014 s), and transport timeout (10 s), not a successful tunnel (`tailcat-probe.png`). No second daemon or real user account/device was used.
- [~] Stories row, viewer, viewers sheet (`ui/stories/`, `stories/` with iOS's StoriesStore/StoriesRow/StoryDraft/wire tests, `:layout` `stories/` with iOS's layout tests). The row in the home header: open under it, closed into the title as overlapping avatars, the list's pull opening it and scrolling closing it, put away remembered per account, the one-time tooltip, closing by itself once everything is seen. The viewer grows out of the tapped circle and shrinks back into it: segments, taps by thirds, hold to pause, the cube between authors, drag down to close, captions with Show more, text / link / emoji / reaction / sticker overlays (Lottie for animated stickers), the heart and its long-press picker with the burst, the author's ⋯ (delete) and views; the viewers sheet with reactions, paging and its half and full heights. Earlier verification used the standalone fake computer (`StoriesHarnessActivity`, iOS's `-screen stories` fixture). Evidence: `stories/a-home-fresh.png` vs `ios-ref/140-stories-open.png`, `cmp-144.png` … `cmp-151.png` (each Android left, iOS right: second story with caption, caption open, after close, my story, viewers sheet, Ana, reaction picker, reacted), `j-reacting.png` (the burst), `k-tooltip.png` (the one-time tooltip), `l-held.png` (held: paused, chrome hidden), `m-more.png`, `m-delete-confirm.png`, `m-deleted.png` (⋯, Delete, the viewer moving on), `video-1-row-open-close.mp4`, `video-2-viewer.mp4`. Video frames play from byte ranges (see the editor's `video-frames.mp4`). The real Direct-home row, viewer entry/return, pairing cleanup, actual setting and cached cold restoration are now checked in the real MainActivity shell; see `stories-home/` and the verification record above. This remains fake-backed, not live-daemon verification; offline media playback is unverified.
- [~] Inline story card (`StoryInlineView`, read through `StoriesProviding`): a draft's Post (spinner, refusal alert) and Edit, Posted after the ring, deleted or unavailable with the struck-through mark, each change crossfading, the viewer growing out of the card and closing back into it. Earlier standalone evidence in `stories/`: `n-card-draft.png`, `o-card-posted.png`, `q-card-deleted.png` vs `ios-ref/160`–`162`, `p-card-viewer.png`, `r-card-edit.png`, `video-3-card.mp4`. Now placed in actual Direct conversations for `draft_story`, with real MainActivity edit/save/post/viewer and account-route cleanup verification; see the inline Stories milestone and `inline-story/` evidence above. The earlier chat paragraph's inline-story gap is superseded by this integration. This is isolated fake transport verification, not live-daemon or spoken TalkBack verification.
- [~] Story editor, sticker picker, text entry (`StoryEditorView`, `StickerPickerSheet`, `StoryTextEntryView`, `StoriesEditorPresenter`, `StoriesMediaChooser`): the canvas with drag / pinch / rotate, snapping guides and the angle line, the bin, the dashed selection with its handles; text with its size slider, colour, fill style, alignment and font; the picker's search with emoji chips, LINK and the reaction bubble, Recently used with its ✕, packs (Lottie animated) and the Emoji tab, half and full; the link prompt; the caption; frames; Post, Save draft, Discard / Keep editing; editing a draft. Verified against the debug harness's fake computer (`--ez compose true`, `harness-edit-draft`), not a live daemon. Evidence in `editor/`: `a-compose.png` … `d-text.png` vs `ios-ref/152`–`155` (open, picker, placed, text), `e-fonts.png`, `f-styled.png`, `g-text-done.png`, `h-dragging.png` (bin and guides), `i-emoji-tab.png`, `j-emoji-full.png`, `k-reaction-emoji.png`, `l-reaction.png`, `m-link-ask.png`, `n-link.png`, `p-posted.png`, `q-viewer-posted.png` (the posted story with its four overlays in the viewer), `r-search.png`, `s-captioned.png`, `u-edit-draft.png`, `w-discard-alert.png`, `video-editor.mp4`; `x-pinched.png` (a two-finger pinch and turn injected by `:probe`'s `PinchProbe`: scale 0.40 → 0.72, rotation 0.611 rad); `z1-photo-picker.png`, `z2-frames-added.png` (two photos and a 6 s clip from the system photo picker, pushed to the emulator), `z4`–`z7-viewer-frame*.png` and `video-frames.mp4` (posted, the four frames in the viewer, the video streamed by byte ranges and shown for its length). The harness's fake keeps an uploaded video as the daemon does (API.md: kind `video`, size and length `null` for the player to read, black background, single byte ranges)
