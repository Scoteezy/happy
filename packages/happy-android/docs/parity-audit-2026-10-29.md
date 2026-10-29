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
creation. Task actions have a later verified slice below.

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

## Relay Task Home actions and native prerequisite restoration

Code checkpoint: `0103c67b0dd821702806d42096559a48bac4a9a4`.
Task menus now offer Leave only when the session advertises `leaveTask`, and
Archive only for a strict boolean `task.canArchive`. Grouped and flat Task
swipes use Leave; unsupported tasks do not reveal an action or open the row
after a horizontal drag. Normal-session and Bot actions retain their routes.
Task archive uses the machine's `killSession` RPC, never ordinary server
archive/delete fallback. Machine refusal text is preserved. Both detail and
Home share optimistic hiding, in-flight deduplication and failure/cancellation
restoration. Operation/account ownership prevents an old account's completion
or fallback from affecting a new account, including a reused session ID.

The latest focused batch passed **102 tests / 8 suites**, zero failures,
errors or skips, plus isolated debug assembly with
`-Phappy.shellHarness=true -Phappy.requireTailcat=true` (1m14s; 9 tasks
executed, 70 up-to-date). Five real-View gesture tests cover unsupported
drags versus taps, right drags, reveal/action activation, rebind/detach reset
and the compact grouped pill's layout and full-row-height hit target.
Owned Gradle/Kotlin PIDs 91156/91196 exited afterward.

The initial Android action recording (105.089444s) exposed a clipped grouped
control and unsupported flat swipes opening a conversation. It remains under
`.context/evidence/relay-task-actions/android/` as diagnostic evidence, not
accepted parity. The corrected recording is
`android-fixed/task-actions-flow.mp4` (96.098522s), alongside owner/member/
unsupported menus, grouped/flat swipes and offline Leave/Archive refusal
screens with restored rows. Stills and sampled video frames were personally
inspected; unsupported flat swipes now stay on Home. Controlled happy36 PID
92253 exited after capture. These are synthetic offline failure paths;
successful RPC responses are covered by unit fakes, not live-device proof.

The unchanged frozen native source was built in the isolated reference host
with the same fixture. Its Task-action XCTest passed (1 test, 371.149s);
`native/ios-task-actions.mp4` is 384.066667s, including XCTest's animation-idle
waits. Native menu and grouped-swipe screenshots/video frames were personally
inspected. The compact inline native Leave pill informed Android's grouped
control; the old flat icon/caption treatment remains for flat rows. HappyRef
17 Pro was shut down after capture. No spoken TalkBack or live relay result
is implied by these recordings or accessibility-node assertions.

The missing source-built Tailcat prerequisite is now restored. The documented
`packages/expo-tailcat/scripts/build-native.sh android` built Go source tree
`00770b5fa7ef166cb9a3445da81e8e5976b4f71e` with Go 1.27.1 and pinned NDK
27.2.12479018. All 11 package checks, check-package, and Go `TestValidation`
passed; no peer/network E2E ran. The AAR is 14,897,456 bytes, SHA-256
`9823da6053a9974b966d5cc65a2ef77c7bc9c4a6e9cfa661176282f1b3f4c5b8`,
with `arm64-v8a` and `x86_64` `libgojni.so`; every ELF LOAD segment has
16 KiB alignment. NDK (2.4 GiB), Go caches/toolchain (3.3 GiB) and tools
(21 MiB) live under ignored `.context`, within the communicated estimate.
The installed SDK/JDK/Go remain read-only. Existing accepted SDK license
material was reused locally; no new license was accepted. Native build logs
are under `.context/evidence/tailcat-rebuild/`. Neither binaries nor caches
are committed, and source manifests were unchanged.

Genuine production verification passed at code
`0103c67b0dd821702806d42096559a48bac4a9a4` with **no harness flag**:
`.context/scripts/g.sh :app:compileReleaseKotlin :app:assembleRelease -Phappy.requireTailcat=true --console=plain`.
The unsigned assembly ran in 6m51s (77 tasks executed, 11 from cache,
9 up-to-date), including R8, release lint, and `verifyReleaseKeeps` (68
serializable types and manifest checks). No signing file/environment was
present. The release manifest/application ID is `com.ex3ndr.happy`, with no
harness component or debuggable attribute. `apksigner verify` rejects the
artifact as unsigned (`Missing META-INF/MANIFEST.MF`), as intended.

The preserved APK is
`.context/evidence/relay-task-actions/app-release-0103c67b0-unsigned.apk`,
96,377,460 bytes, SHA-256
`9785e3e9b51aaa6d6ede05b82ff07823e3ea172cc59aae9c8911fbaa6d740768`.
Both Tailcat JNI ABIs are present. `zipalign -c -P 16 4` passed. The build
warned that some native libraries could not be stripped and retained them;
it did not fall back to a no-Tailcat variant. This is local compilation and
unsigned assembly only: no signed install, publication, store validation or
successful live pairing. Both simulators were off; owned Gradle/Kotlin PIDs
94483/94701 exited after the build. Log:
`.context/evidence/relay-task-actions/production-release.log`.

The full native-linked milestone also passed at that exact code HEAD:
`.context/scripts/g.sh testDebugUnitTest --rerun :layout:test --rerun :crypto:test --rerun :icons:test --rerun :app:assembleDebug -Phappy.shellHarness=true -Phappy.requireTailcat=true --console=plain`.
Result XML: app **1297/182** tests/suites, layout **591/107**, crypto
**36/5**, icons **6/2** — **1930 tests / 296 suites** in those four modules,
plus vendor ElevenLabs lifecycle **4/1** (**1934/297** including vendor).
Zero failures, errors or skips. All five test tasks actually executed; none
reused cached results. Required-Tailcat isolated debug assembly passed.
Gradle reported 15 tasks executed, 86 up-to-date in 2m02s. Both simulators
remained off and owned Gradle/Kotlin PIDs 773/862 exited afterward. Log:
`.context/evidence/relay-task-actions/full-milestone.log`. The earlier row
checkpoint's totals counted only the four main modules, not vendor tests.

## Confirmed missing behavior

| Area | Native reference | Android gap |
| --- | --- | --- |
| Relay Home task rows | `HappyCore/Home/ProjectHomeList.swift:119,256`, `HappyCore/Home/SessionList.swift:312`, `HappyStore/SessionState.swift:72` | Closed for row rendering: classification, ordering, retired exclusion, name/folder/glyph and grouped/flat rows pass focused/full tests and isolated Android/native comparison. Dock creation remains separate below. |
| Relay Home task actions | `HappyCore/Home/SessionQuickActions.swift:100`, `HappyUI/Screens/SessionListScreens.swift:714`, `HappyUI/Screens/HomeSessionActions.swift:225` | Implemented and focused/device verified above: capability Leave/swipe, ownership Archive, machine-only routes, optimistic restoration and account isolation. Successful live machine operations remain unverified. |
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
  debug-only `.shellharness` APK. Production release Kotlin compilation
  and unsigned assembly are now verified at
  `0103c67b0dd821702806d42096559a48bac4a9a4`; the installed UI
  evidence is the isolated debug APK, not a signed release install. Release
  and preview require the source-built Tailcat AAR. Signing/store/service
  credentials and a production distribution are not validated by compilation.

## Stale checklist wording to remove or replace

- “Tasks and bots rows” must split: Task rendering/actions are now verified,
  Task dock creation remains missing, and Bots were already implemented.
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