# Remaining parity inventory — fixed source baseline

Audit checkpoint: Android branch HEAD `5900d6139d33dd384423220c78b8f2da385355cd`.
The checked-out `packages/happy-ios` tree is
`9426578dd1e80b0eb92d75104c8d35a707d8fc93`; its latest touching commit is
`35ba48b8cb43499f831b477fb917a4618761070c`. No fetch, rebase or iOS source
update was made during this audit. Paths below are package-relative unless
prefixed otherwise. This is a prioritized inventory, not a claim that every
old partial checklist item has received a fresh device test.

## Relay Task Home rows: verified milestone

The Task Home row implementation has passed focused/full tests and isolated
Android/native comparison. It adds task
classification, ordering, retired-task exclusion, name/folder presentation,
checkbox avatars and grouped/flat row bindings. Task dock creation is still
absent. The inventory below separates the verified row slice from unfinished
actions and creation.

The initial affected test command compiled main Kotlin but failed while
compiling two existing test shape helpers whose sealed-type `when` expressions
needed the new Task cases. Those omissions were corrected afterward. The
follow-up affected-test/APK build **never ran**: automatic access review timed
out on the initial submission and the single retry. Some debug fixture edits
also postdate the first compilation. No passing tests, assembled APK or
device evidence were claimed for the complete WIP at that stopping point.

After explicit user direction to continue, the same bounded affected-test/APK
batch actually ran in the recovered workspace at exact code HEAD
`420b5508e60e98ea87cea17a1402b20b0a0545f7` and passed in 7m48s:
**58 tests in 3 matched suites**, zero failures, errors or skips
(`TaskHomeRowsTest` 5, `SessionListTest` 19, `ProjectHomeRowsTest` 34).
The `*HomeListTest` filter matched no additional suite. The test task executed;
Gradle reported 75 tasks executed and 2 from cache. The isolated debug APK
assembled successfully without the absent Tailcat AAR (honest unavailable
fallback). This is not a full-suite, release-build or live-connectivity result.
Owned Gradle/Kotlin PIDs 23574/24323 were stopped and verified absent before
starting device checks.

The first new Android capture exposed malformed fixture-only project metadata:
its summary lacked `updatedAt`, causing the strict parser to reject the whole
document. The corrected fixture includes that field and uses metadata version
2 when seeded over the old fiction. A sixth `TaskHomeRowsTest` validates every
fixture session's metadata and the single-machine/project grouping. All six
tests and isolated APK assembly passed in the follow-up working diff (37s;
12 tasks executed, 65 up-to-date); owned PIDs 29984/30138 were stopped and
verified absent. No production Kotlin changed in this correction.

New real-MainActivity evidence is under
`.context/evidence/relay-task-home/corrected-fixture/`: `android-grouped.png`,
`android-flat.png`, `android-task-chat.png`, `android-returned-grouped.png`,
compressed UI trees, and `task-home-flow.mp4` (37.080478s). The grouped still
and video contact sheet were personally inspected: correct task order/glyphs,
folder labels in flat mode, the corrected project, task-header navigation and
return. The chat's history remains loading against the intentionally offline
fixture; neither its seeded online indicator nor this recording proves live
connectivity. The root evidence directory preserves the initial 41.326544s
recording with the malformed project as diagnostic evidence. Initial package
installation failed due to the prior workspace's debug signature; only the
debuggable `.shellharness` package and its reproducible synthetic data were
removed/reseeded. Normal Happy apps/accounts were untouched. Controlled
happy36 PID 31350 was shut down and verified absent after corrected capture.
The correction and regression test are committed at code
`69cd35822e41da0a1cdfb259c564eb3154560b0d`.

The reconstructed native host uses the unchanged frozen iOS source, real
`HomeViewController` and `TabletShellViewController`, the same JSON fixture,
in-memory credentials/store, and offline transports. Its bounded arm64
build-for-testing passed. The dedicated **HappyRef 17 Pro** UI test passed
(1 test, 247.278s) with grouped/flat existence and retired-task exclusion
assertions. XCTest incurred four 60-second animation-idle waits; it continued
and completed the actual Filter → Flat List interaction. The native video is
267.925s, not the test duration. Grouped and flat screenshots plus the sampled
video frames were personally inspected against Android: task placement,
lexical grouped order, flat activity order, folder labels and checkbox glyphs
match. Native evidence is under `.context/evidence/relay-task-home/native/`,
including `ios-task-home.mp4`, `task-home-frames.png`, and screenshot attachment
mapping in `attachments/manifest.json`. HappyRef was shut down and verified
before the full Android suite began. This is synthetic/offline reference
evidence, not successful live relay or daemon access.

The full bounded milestone batch passed at exact code HEAD
`69cd35822e41da0a1cdfb259c564eb3154560b0d`:
`.context/scripts/g.sh testDebugUnitTest :layout:test :crypto:test :icons:test :app:assembleDebug -Phappy.shellHarness=true --console=plain`.
Result XML: **1909 tests / 293 suites**, zero failures, errors or skips:
app 1276/179, layout 591/107, crypto 36/5, icons 6/2. All four test tasks
executed (none reused cached results). Debug assembly passed; Gradle overall
reported 30 tasks executed, 2 from cache, 67 up-to-date in 1m59s. Release
compilation was not run: this recovered workspace lacks the source-built
Tailcat AAR. Owned Gradle/Kotlin PIDs 46331/47697 were stopped and verified
absent. Both simulators were off throughout this batch. The CPU caps remain
3 Gradle workers, parallel disabled, Gradle JVM 3 processors/3 GiB, Kotlin
2 processors/2 GiB, tests at most 2 forks with 2 processors each; processes
ran at nice 10 under background task policy.

The reproducible fixture is tracked in debug-only
`app/src/debug/assets/harness/relay-task-home.json` and
`app/src/debug/kotlin/com/slopus/happy/harness/RelayTasksShellFixture.kt`.
`ShellHarnessActivity` accepts `relayTasks=true` only in the isolated
`.shellharness` APK; the fixture checks the fake relay token before seeding.
Its corrected version has been exercised as described above. It does not
authorize a live account or daemon.

Local-only helpers, caches and media remain deliberately **uncommitted**.
The recovery workspace recreated `.context/scripts/g.sh`, `emu.sh`,
`task-home-capture.sh`, `ios-task-capture.sh`, `milestone-tests.sh` and
`.context/ios-ref-host`; `.context/notes/recovery.md` records the recovery.
Do not depend on the archived predecessor workspace. Recreate missing helpers
from durable history within the same isolation and CPU limits. Use only
HappyRef 17 Pro, never the populated simulator. No real credentials, binaries,
caches or `.context` contents belong in this checkpoint.

## Confirmed missing behavior

| Area | Native reference | Android gap |
| --- | --- | --- |
| Relay Home task rows | `HappyCore/Home/ProjectHomeList.swift:119,256`, `HappyCore/Home/SessionList.swift:312`, `HappyStore/SessionState.swift:72` | Closed for row rendering: classification, ordering, retired exclusion, name/folder/glyph and grouped/flat rows pass focused/full tests and isolated Android/native comparison. Task actions and creation remain separate gaps below. |
| Relay Home task actions | `HappyCore/Home/SessionQuickActions.swift:100`, `HappyUI/Screens/SessionListScreens.swift:714`, `HappyUI/Screens/HomeSessionActions.swift:225` | Home still offers generic Archive without task ownership gating and lacks Leave. Native flat/grouped Task swipe leaves only when `leaveTask` is advertised; unsupported tasks do not swipe. Detail's Leave action and `SessionOps.leaveTask` already exist and do not complete Home behavior. |
| Home dock Task mode | `HappyCore/Home/HomeDockKind.swift`, `HappyUI/Screens/HomeDockScreens.swift:596` | `ui/home/HomeDockController.kt:132` and `HomeDockFocusView.kt:85` offer Session/Bot only. Task capability/creation and selection persistence need a separate slice. |
| Dock bot face picker and pictures | Native `HomeDockScreens.swift:463,732` | Android has Bot naming/creation but no face picker in `HomeDockFocusView`; dock attachments need wiring. Do not equate generic chat attachment support with the dock. |
| Relay troubleshooting | Native `HappyUI/Screens/HomeScreens.swift:650` | `ui/home/HomeScreen.kt:216,343` still routes to `notPorted`. This remains an unfinished required action. |
| Imported-account server confirmation | Native `HappyUI/Screens/AccountServerScreens.swift`, `Navigation/AppCoordinator.swift:282` | No equivalent confirmation route found in Android auth/coordinator. |
| Message-detail reading position | Native `HappyUI/Chat/MessageDetail/MessageDetailViewController.swift:294` | Android's pure `MessageDetailReadingPlace` exists but is not wired to the message screen's text-size changes. |

Other historical partials remain a **re-audit queue**, not newly confirmed
missing features: diff selection/reading anchor, permission overflow,
background work-group folding, interactive keyboard dismissal, guest links,
update checks, account-header picture, tablet grouped presentation and
remaining off-main measurement/motion. Keep their limitations in
`ios-parity.md`; inspect the actual current call sites before implementing.

## Existing behavior awaiting additional verification

- Direct detail rename is implemented (`ui/stemcells/StemCellSessionDetailScreen.kt:495,530`);
  a fresh end-to-end detail/header/Home round trip against current native is
  still needed. Sidebar inline rename already has its own verified milestone.
- Relay Bot grouping/rendering and Session/Bot creation already exist
  (`model/ProjectHomeRows.kt:69`, `ui/home/SessionListView.kt:266`,
  `HomeDockController.kt:185`). Their newer native parity, especially face
  choices, needs evidence; they are not wholly absent.
- Relay empty/offline states, second-device approval and live rig activity
  need the remaining device cases recorded. Existing tests/UI are not proof
  of those unexercised outcomes.
- Direct switching/removal/restore/offline stores, Stories and proposals have
  isolated **real MainActivity** fake-transport evidence. Successful daemon
  access, offline media playback and real service/OAuth round trips are not
  implied by it. Standalone harness evidence remains labeled separately.
- `app/build.gradle.kts:126,227,314` separates production/preview from the
  debug-only `.shellharness` APK. Production release Kotlin compilation is
  verified at `0d58e06b5724fe4e2e0ab378d669247b489df7d46`; the installed UI
  evidence is the isolated debug APK, not a signed release install. Release
  and preview require the source-built Tailcat AAR. Signing/store/service
  credentials and a production distribution are not validated by compilation.

## Stale checklist wording to remove or replace

- “Tasks and bots rows” must split: Task rendering is now verified, Task
  actions remain missing, and Bots were already implemented.
- “Bots (Session | Bot, faces) ... to do” must split: mode/name/create exists;
  face picker remains missing and device parity awaits verification.
- “Rename in the header” is not native behavior now:
  `HappyUI/StemCells/StemCellChatHeaderView.swift:57–63` disables title
  interaction and routes the entire capsule to details. Do not add inline
  header editing. Verify the existing detail route instead.
- Direct conversation “proposal loading only” and “Stories inline missing”
  are superseded by commits `0d58e06b5` and `fa9fe57ce` plus their milestone
  records. Historical screenshots are not current failure evidence.

## Genuine access / product / physical-device limits

- No authority to pair the user's devices/accounts or run a second daemon.
  Tailcat load/rejection/unreachable/timeout checks passed earlier; successful
  tunnel/real pairing needs an explicitly authorized disposable peer. This
  blocks that verification only, not local parity implementation.
- Real OAuth/connected-service flows require an authorized disposable service
  account. Real push requires Firebase configuration; voice requires a
  working service/allowance, billing requires sandbox credentials. Do not
  modify live accounts or obtain credentials silently.
- Emulator camera attachment was captured earlier; QR scanning via Play
  services, real camera scanning, physical-device haptics/performance and
  spoken TalkBack remain unverified. Simulated touches/accessibility trees
  do not prove those behaviors.
- Release/store submission is outside authorization. Native Views and
  Android platform controls are intentional; Liquid Glass and iOS-only
  renderer settings are not missing Android features.