# Remaining parity inventory — fixed source baseline

Audit checkpoint: Android branch HEAD `5900d6139d33dd384423220c78b8f2da385355cd`.
The checked-out `packages/happy-ios` tree is
`9426578dd1e80b0eb92d75104c8d35a707d8fc93`; its latest touching commit is
`35ba48b8cb43499f831b477fb917a4618761070c`. No fetch, rebase or iOS source
update was made during this audit. Paths below are package-relative unless
prefixed otherwise. This is a prioritized inventory, not a claim that every
old partial checklist item has received a fresh device test.

## Completion audit after reading-position milestone (2026-10-30)

Current delivered checkpoint: `5edabd274` (implementation `55be0e4ed`), normal
fast-forward pushed to `android-views-port`. Frozen iOS remains the tree above.
This is behavioral/native-platform parity, not Swift implementation identity.
The ordinary Android debug APK builds with both Tailcat ABIs; the installed
isolated APK exercises real screens against synthetic transports. Genuine
unsigned production also builds and passes isolation checks. There is **no
local-build blocker**, but neither complete parity nor live-service readiness
is established. The unsigned production APK is not a signed distribution.

### Remaining user-visible gaps confirmed in current source

| Behavior | Current evidence / boundary |
| --- | --- |
| Changes/diff reading place and code selection | `ui/diff/DiffFilesList.kt:649` replans/rebinds without a line anchor; `DiffViews.kt` has taps for context expansion but no line-selection/copy path. Native `DiffFilesListView.swift:128,606` enables selection and restores `DiffReadingPlace`. Same-size font invalidation also needs a focused regression, not an assumption that existing measurements refresh. |
| Codex patch/unified-diff presentation | `ui/chat/ChatRowRenderer.kt:464` explicitly falls back to raw Input. Native `ChatToolRenderer.swift:250` and `MessageDetailRenderer.swift:89` have dedicated patch/diff presentations. |
| Permission overflow | `ChatRowRenderer.kt:772` uses the uncapped layout default and emits inline buttons; no vertical permission scroller. Native `ChatToolRenderer.swift:679` passes the window cap and wraps tall lists in a vertical scroller. Android's pure cap geometry exists but is not connected. |
| Tablet grouped sidebar | `ui/tablet/TabletSidebarView.kt:162` explicitly substitutes flat rows. Native `TabletSidebarViewController.swift:302` mounts its grouped hierarchy. |
| Guest invitation/chat | Android `connect/DeepLinks.kt:27` accepts Direct and account/terminal pairing only; no guest vault/client/UI is present. Frozen native `HappyApp/AppDelegate.swift:82` opens `HappyGuestUI` for validated HTTPS bot invitations. Local implementation/fixtures need no live account; real redemption would need separate authority. |
| Native-version update request/banner binding | Android has a `HomeBanners.nativeUpdate` display slot but no `/v1/version` request/status owner. Native `HomeScreens.swift:152` refreshes `NativeUpdateCheck` on startup/foreground. This is a server-version response contract, not necessarily Play in-app updates; native itself notes today's server response does not activate the banner. A synthetic contract test can be local. |
| Direct Home account picture | Native `StemCellsDirectHomeViewController.swift:78` decodes account bytes/thumbhash and sets the header image. Android `HomeHeaderView.kt:218` chooses only a logo/account glyph; Direct Home has no equivalent image binding. This should not be generalized to a missing avatar everywhere. |
| Drag-to-dismiss chat keyboard | Native `ChatUltraListAdapter.swift:274` enables interactive dismissal. Android `ChatListView` preserves IME scroll anchors but has no list-drag dismissal integration. Appropriate Android gesture/accessibility behavior matters, not copying UIKit animation internals. |

This is a source-confirmed list, not new device reproduction of every item or
an exhaustive re-audit. Background work-group lifecycle/folding and broad
motion coverage remain a re-audit queue: Android passes `foreground = true`
at `ChatScreen.kt:825`, but the current native ownership/lifecycle path must
be traced before calling its observable behavior wrong. Edit/MultiEdit/Write
file navigation is **already implemented** (`ChatRowRenderer.kt:332`,
`ChatScreen.kt:940`); the old message-detail checklist sentence is stale.
Previously closed Task actions/creation, Bot faces, dock pictures, troubleshooting,
imported-server confirmation and message reading should not be reopened from
old checklist prose. Likewise proposals and inline Stories are integrated;
native's chat title routes to details, not inline rename.

### Existing behavior lacking some verification

- Fresh Direct detail rename → header → Home round trip; existing rename
  implementation and earlier fake detail evidence are not an absent feature.
- Remaining relay empty/offline state recordings, current rig/activity cases,
  offline media playback, and broader current tablet/motion regressions.
- Second-device approval can first be exercised with a synthetic peer; real
  cross-device/Keystore migration and RN in-place upgrade remain separate.
- Existing Direct/Stories/proposals/account switching evidence is labeled
  real-MainActivity/fake transport, not successful real-daemon/service proof.

### Checks requiring new authority or external facilities

Successful real Tailcat pairing/tunnel requires an authorized disposable peer;
real OAuth/service accounts, FCM/push configuration, voice allowance/service,
billing sandbox, real-account RN upgrade and physical-device checks are not
authorized here. QR/Play-services camera behavior, physical haptics/performance
and spoken TalkBack are unverified. Signing, store validation and release are
outside this task. These block those claims, **not** local parity work or APK
assembly. No real service/account/device should be used to make the audit green.

### Implementation differences / deferrable enhancements

Android's synchronous `AgentInputCardView.textContentHeight` and dock label
measurement differ from Swift's height cache/worker/warmup. No responsiveness
or geometry defect has yet been measured. Before any shared-composer refactor,
first establish a measured regression blocking actual use. Separate composer
benchmark/cache/warmup work is deferred while concrete behavior gaps remain.
If such a regression arises, use bounded long-draft/layout/IME/width/font
diagnosis and propose the smallest relevant fix. Matching every native
cache/queue is not completion work on its own. Native Views, Android dialogs,
platform photo picker without library permission, and Android material in
place of Liquid Glass are intentional platform adaptations, not missing
features. These optional internals do not block a usable Android build.

## Relay Task Home rows: verified milestone

The Task Home row implementation has passed focused/full tests and isolated
Android/native comparison. It adds task
classification, ordering, retired-task exclusion, name/folder presentation,
checkbox avatars and grouped/flat row bindings. Task dock creation was absent
at this row checkpoint. Actions, text Task creation, Bot faces and dock
pictures have later verified slices below.

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

## Relay Task dock text creation: verified milestone

Source checkpoint: `0f3e74eea870e1438dbc08b516cb684080bcd2d2`.
The frozen iOS tree remains unchanged. Strict advertised `tasks` capability
now offers Session / Bot / Task in native order. Task mode is memory-only,
keeps the machine row, omits session mode chips, retains the prompt across
Bot/Task and dock close/reopen, and resets after successful creation or an
account change. Unsupported/stale capabilities cannot silently turn a chosen
Task into an ordinary Session.

Creation forces the Happy Agent route with an unnamed `{kind:"task"}` target,
without a directory/worktree target, then sends the first user message through
the normal sender. Account and target identity are bound through spawn,
readiness, send and cleanup. Retry retains its request/task after a refused
first send; abandoning or stopping it retires the old request. Regression
tests cover an immediate Stop/retry before the old operation unwinds, late
cleanup, account changes, unsupported capability, real dock/controller submit
and reset, dynamic segmented controls, and encrypted transport association.

The earlier rendering/refusal Android capture is
`.context/evidence/relay-task-dock/android-fixed/task-dock-flow.mp4`
(85.582633s). Native reference is `native/ios-task-dock.mp4` (742.603333s);
its UI test passed **1 test / 731.816s**, including long XCTest animation-idle
waits. Task card, retained prompt and refusal stills were personally inspected.
These refusals are **pre-RPC missing-key errors**, not network failures:
Android says “Machine encryption key not found”; native says “notDecryptable”.
Both captures predate the final raw-selected-kind and atomic request guards;
the subsequent tests and successful device capture include those guards.

Successful real-shell capture is under
`.context/evidence/relay-task-dock/android-success/`, movie
`task-success-flow.mp4` (**61.392767s**). The unchanged real MainActivity,
controller, account sync, encrypted Socket.IO RPC and HTTP message transport
created a Task, opened its chat with the first-message bubble, returned to its
new TASKS row, and reopened an empty Session-mode dock. Those stills and the
cold-restart still were personally inspected; all seven XML hierarchies parse.
`created.json` verifies exactly one Task-target spawn, matching response/store/
Home session identity, one decrypted first message, empty draft and reset mode.
`restarted.json` verifies a fresh namespace/account/loopback relay and no old
Task after process restart. `closed.json` confirms explicit relay close,
fixture-scope cancellation and account stop before process/emulator shutdown.
Controlled happy36 PID 58441 exited. This is **synthetic protocol/device
verification**, not live pairing, a real daemon, or actual agent execution.

The shared `src/sharedTest` FakeHappyServer implementation is compiled into
unit tests or the explicitly opted-in `.shellharness` debug APK only. Its
listener binds `127.0.0.1:0` inside the app, before graph initialization;
there is no host listener, port forwarding or production transport hook.
Generated account/machine/session keys and software secure-store keys are
disposable. Every process gets fresh namespaced files/preferences, an in-memory
database and its newly bound server URL, so no old account/port/real relay can
restore. Cleartext is permitted only for numeric loopback in this harness.
Ordinary debug excludes the harness sources/assets/manifest and mockwebserver
dependency; production excludes them too.

The first protocol checks caught and corrected incomplete fixture capabilities
and a `localhost` advertisement. The first device attempt then caught a
fixture-only reverse-DNS call on the UI thread; the URL now uses the numeric
address and bound port directly, without relaxing StrictMode. That failed
startup is diagnostic only. Final affected checks passed **60 tests / 6 suites**
and required-Tailcat harness assembly (1m06s, `affected-8.log`). The successful
capture APK was built before the source commit, with `fbed351` build metadata;
its runtime code is the committed implementation (only comments changed before
commit). Preserved `app-shellharness-capture347.apk` SHA-256:
`85e51437c480a4525dc9ecd76703a0d90514314595f1a80272c668864f1bc363`.

At exact source `0f3e74eea`, the full **ordinary-debug, no-harness** batch passed
in 2m46s: app **1313/185**, layout **591/107**, crypto **36/5**, icons **6/2**
tests/suites, plus vendor ElevenLabs **4/1**: **1950 tests / 300 suites**, zero
failures/errors/skips. All five test tasks actually ran with `--rerun`.
Command: `testDebugUnitTest --rerun :layout:test --rerun :crypto:test --rerun :icons:test --rerun :app:assembleDebug -Phappy.requireTailcat=true --console=plain`.
Log: `full-ordinary-debug.log`; XML preserved under `full-test-results/`.
Gradle: 38 executed, 2 from cache, 61 up-to-date; owned PIDs 62403/62559 exited.

Genuine no-harness `:app:compileReleaseKotlin :app:assembleRelease
-Phappy.requireTailcat=true` also passed at that source in **5m29s**: 22 executed,
2 from cache, 73 up-to-date, including R8, lint and release keep checks.
Owned PIDs 64407/64499 exited. Log: `production-release.log`. Preserved unsigned
`app-release-0f3e74eea-unsigned.apk`: **96,379,480 bytes**, SHA-256
`c8ab309981d8567b6cb8435fdaea03d3816273a9684818b7f07f50c4e7be35ef`.
Actual ordinary-debug and release manifests, DEX classes, APK entries and
release R8 mapping confirm no fixture/testing/mockwebserver code, fixture
routes/assets or permissive network configuration. Correct package IDs are
`com.slopus.happy.dev` and `com.ex3ndr.happy`; both contain both Tailcat JNI ABIs
and pass `zipalign -c -P 16 4`. Production is non-debuggable and unsigned
(`apksigner` reports the expected missing signature manifest). All build and
device jobs remained sequential and bounded. No production install, signing,
publication or store validation occurred. Dock attachments and bot faces are
not covered by this text-creation milestone.

## Relay troubleshooting: device-verified implementation

Source checkpoint: `874fd38f6540bcd46a4b2c2cb7f8b208892eef25`.

Both Home entry points now open the native-style guide, grouped computers,
machine details and Copy AI prompt. The screen captures its opening account,
observes only while visible, rejects stale-account actions, retains the last
valid content on read failure and resets “Copied” after two seconds. Its
guide uses the frozen native layout metrics and off-main text measurement.

The account owns one shared off-main feed. SQLite projects only the machine
grouping/log fields and the newest known-machine, non-side-chat session;
unrelated message/outbox changes do not trigger it. Android SQLite builds
without JSON1 use a streaming metadata fallback, tested against the narrow
query with sorting ties, strict JSON types, deletions and 1,000 sessions.
That is compatibility unit coverage, not an Android 8 device test.

Current focused results: **15 app tests / 3 suites**, plus **4 exact-frame
layout tests**, no failures/errors/skips. The isolated required-Tailcat APK
assembled successfully. Baseline Android capture is
`.context/evidence/relay-troubleshoot/android/troubleshoot-flow.mp4`
(69.311744s): offline banner and empty-home checklist entry, guide, Copied/
reset, machine details and return. Native comparison under `native/` passed
one UI test in 9.334s, recording 20.316667s. The native scratch host opens the
real troubleshooting controller with the same fictional offline machine;
the frozen iOS tree is unchanged. Guide stills were personally compared.
These checks do not establish live connectivity or machine repair.

Terminal selection is now device verified under `android-selection3/`.
Its complete replay passed (75.268878s recording); all 12 XML hierarchies
parse, and owned happy36 PID 8991 exited before the full regression build.
The initial replay was a capture-tool false negative: Android deliberately
filters selectable-text click flags, and its non-focused floating toolbar is
absent from uiautomator's active-window XML. A personally inspected screenshot
showed the native Copy/Share/Select all toolbar and handles selecting `happy`,
not `$ `. The corrected fixed-emulator check taps that visible toolbar;
`command-copy-proof.log` records selection `2:7` and `commandCopied=true`.
The OS clipboard preview also visibly contains exactly `happy`. No production
touch fix was needed. The opt-in, isolated-only probe logs a fixed boolean
clipboard assertion, never clipboard contents.

Preserved `app-shellharness-capture461.apk`: 113,467,138 bytes, SHA-256
`5022d29bebe988345cec8331384df9d00aa64233aee30b1b76f7f754d5723bde`.
It was built immediately before the source commit and records `12918614f`
build metadata; its runtime source matches the checkpoint. Baseline capture
predates the final same-account cached-prompt re-enable-on-return guard and
the diagnostic probe; the corrected replay includes both.

At exact source `874fd38f6`, the full fresh ordinary-debug/no-harness run
passed in **2m30s**: app **1328/188**, layout **595/108**, crypto **36/5**,
icons **6/2**, vendor ElevenLabs **4/1** tests/suites — **1969 tests / 304
suites**, zero failures/errors/skips. All five test tasks actually executed
with `--rerun`; required-Tailcat ordinary debug assembly passed. Gradle:
26 executed, 14 from cache, 61 up-to-date. Owned PIDs 13357/13460 exited.
Log: `full-ordinary-debug.log`; XML preserved under `full-test-results/`.
Ordinary APK manifest/DEX/assets checks confirm fixture exclusion, correct
`com.slopus.happy.dev` package, both JNI ABIs and 16KB ZIP alignment. Preserved
`app-ordinary-debug-874fd38f6.apk`: 112,845,436 bytes, SHA-256
`0b369652e30e0f3b7aed794fe9e479ea4e899f0e1c8309a5c76926de4209cf20`.
Genuine no-harness `:app:compileReleaseKotlin :app:assembleRelease
-Phappy.requireTailcat=true` passed at that source in **4m26s**, including R8,
lint and release keep checks (17 executed, 80 up-to-date). Owned PIDs
15929/15961 exited. `production-release.log` and `production-*` preserve the
checks. Actual release manifest, DEX, entries and R8 mapping exclude all
fixture/testing/mockwebserver/probe code, routes, assets and permissive
network policy. The correct `com.ex3ndr.happy` APK is non-debuggable and
unsigned, includes both Tailcat JNI ABIs and passes 16KB ZIP alignment.
Preserved `app-release-874fd38f6-unsigned.apk`: **96,400,176 bytes**, SHA-256
`2ef8d71f89e1dbde1487dfce8bf2518f75ef516dddd1bbc522ff47c6c438b255`.
All device/build jobs were sequential and bounded; no signing, production
install, publication or store validation occurred.

## Home Bot faces: verified implementation and device flow (2026-10-30)

Source checkpoint `07d77500204f27b89d3d5bca8e613d80d8c90d92` connects the
native four-face picker, reroll, selected ring and artist link to Bot creation.
The pure layout ports the frozen native 52pt faces, 2pt ring, 10pt gaps and
40pt die; six exact-frame/token tests cover it and the dock integration.
The existing Adventurer Neutral resolver feeds a cached Canvas painter off
main. Its 256px PNG is encrypted with the original account's session blob
key, uploaded, then passed to `setAvatar` with the plaintext size and MIME.

Painting/upload/wearing failures keep the created Bot and offer Try again /
Not now after the dock closes. Retry retains the original account, session
and seed and never spawns another Bot. Stop/account changes cancel; refusing
an upload because the app is backgrounded is a retryable picture failure,
not a reason to kill the Bot. The prior Session prompt is preserved, and a
successful creation resets the Bot name/kind. Tests cover rendering details,
ownership at suspension boundaries, cancellation, encrypted transport,
failure legs, retry and real picker accessibility/actions.

Evidence is under `.context/evidence/relay-bot-face/`. The final complete
Android replay `android-final/bot-face-flow.mp4` passed (**92.476022s**);
all ten UI XML snapshots parse. Personally compared selected-fourth-name,
retry alert and Home stills: the chosen face appears on the Home Bot row.
Sanitized JSON proves one named Bot spawn, two avatar attempts (the first
deliberately refused), one worn face, exact selected-PNG encrypted roundtrip,
Home avatar loading, no first message, prompt retention and draft reset.
There is one socket connection and no socket failures. Explicit relay,
fixture-scope and account teardown all pass; owned happy36 PID 62124 exited.

Earlier captures are retained: the first exposed a missing heartbeat in the
fake relay, fixed with a fixture-owned heartbeat, not a production transport
change. Two later captures passed the functional path but failed teardown:
Android task reuse brought MainActivity forward instead of starting the
shutdown harness. Fixed lifecycle tracing confirmed no shutdown entry;
`--activity-multiple-task` made the shutdown-only and final full replay pass.

The scratch native reference build and **one UI test / 729.810s** passed,
covering initial picker, second selection, reroll and fourth selection/name.
Initial and named stills were personally compared. Raw video is 740.230s
because XCTest waits 60s around taps for animation-idle notifications;
`native/ios-bot-face-compact.mp4` is **54.883333s**, omitting those idle waits
without modifying the frozen reference or its animations. The dedicated
reference simulator shut down before Gradle. This is native visual proof and
Android loopback-fixture protocol proof, not successful live-machine creation.
The later dock-picture comparison found that suppressing only the offline
header pulse did not eliminate those waits; their cause is not isolated to
that pulse.

Preserved fixture APK `app-shellharness-capture586.apk`: 113,737,768 bytes,
SHA-256 `8422446c1560b2c6647401c69d4f237b98cbf7e9351fa6f554546208b3331d67`.
It records pre-commit `bdc5a139a` build metadata; runtime source matches the
checkpoint (only a subsequent method comment changed).

At exact `07d775002`, full fresh ordinary-debug checks passed in **2m44s**:
app **1343/192**, layout **601/109**, crypto **36/5**, icons **6/2**, vendor
**4/1** tests/suites — **1990 tests / 309 suites**, no failures/errors/skips.
All five test tasks actually executed with `--rerun`; required-Tailcat debug
assembly passed (26 executed, 14 cached, 61 up-to-date). Owned Gradle/Kotlin
PIDs 68288/68396 exited. XML is preserved under `full-test-results/`.
Ordinary APK package/manifest/DEX/assets checks pass: no fixture components,
code or permissive network policy, both JNI ABIs and 16KB ZIP alignment.
`app-ordinary-debug-07d775002.apk`: 112,861,820 bytes, SHA-256
`f3f1d0eded726c1ba39047bbe47701bb188069a27e739fa3d7307094db53dec8`.

Genuine no-harness unsigned production compilation/assembly at the same
source passed in **4m49s**, including R8, lint and release keep checks (16
executed, 81 up-to-date). Owned PIDs 69043/69077 exited. Actual release
manifest, DEX, assets and preserved R8 mapping exclude fixture/testing/
mockwebserver classes, routes and network policy. The `com.ex3ndr.happy`
APK is non-debuggable and unsigned. Both APKs pass 16KB ZIP alignment and
both Tailcat JNI ABIs have four ELF LOAD segments aligned to 16KiB.
`app-release-07d775002-unsigned.apk`: **96,417,256 bytes**, SHA-256
`aa3b991128e3b3e4d5825ad4f7dfb5f20874aa40ccc63267f2c5ef5772cc8870`.
Logs and inspection output are `full-ordinary-debug.log`,
`production-release.log`, `ordinary-debug-*` and `production-*`. All builds
and devices ran sequentially with bounded resources; no production install,
signing, publication or store validation occurred.

## Home Session/Task pictures: verified implementation and device flow (2026-10-30)

Source checkpoint `ed23b11b1e5ce5c8a73edc6eec7a917fc18fcfb6` connects the
existing image picker/normalizer and attachment outbox to Home's Session and
Task dock. Home owns the staging lifetime, so focus dismissal and Bot mode
preserve the selection; Bot neither displays nor sends Session pictures.
Session/Task accept a picture without text, render the existing 64dp strip,
and pass a snapshot to `SessionStarter`. Only an accepted first send clears
Home's copies: the outbox has copied them by then. Busy add/remove/paste are
refused, Stop retains pictures, and Home destruction clears only its staging.
Not-supported/upload notices are surfaced; a refused first send retries the
same created Task instead of spawning another.

The focused batch passed **63 tests / 11 suites** in **59s**, including real
image normalization/controller controls and real encrypted transport. Outbox
tests delete the original staging file after acceptance, deliberately return
503 on the first upload, then prove byte-for-byte decrypted retry, file-before-
text ordering (empty and nonempty text), eventual cleanup and no respawn.
An earlier test-only `Task`/`Task()` compile error was corrected before the
first passing batch. An expanded unsupported-picture test then exposed a
fixture schema omission: `TaskRelayScenario` now declares and validates
`rigMetadataVersion: 1`, so latest capability gates really apply. Production
capability policy did not change. Earlier Task/Bot captures still prove their
asserted spawn/avatar/transport paths, but not v1-specific chat capability
gating; Bot regressions pass again with the corrected fixture.

Evidence is under `.context/evidence/relay-dock-pictures/`. The complete
Android replay is `android-fourth/selection.mp4` (**83.042822s**) and
`creation.mp4` (**63.696978s**): actual system picker, generated 256×128
picture, preview, Bot hide/Session restore, dismissal/reopening retention,
remove/reselect, picture-only Task creation, visible chat image, Home Task row,
and empty next Session. Sanitized evidence asserts one Task spawn, one valid
decrypted JPEG with the selected dimensions before the empty text record,
draft/kind reset and explicit relay/scope/account shutdown. All 13 UI XML
files parse; owned emulator PID 92005 exited. Preview and chat stills were
personally inspected. This is real-client loopback-fixture proof, not a live
machine. Earlier replay failures were automation-only: a compressed-tree
photo selector, an IME-moving target, and a backdrop point that hit the raised
machine row. The saved helper now pins the preserved fixture APK and derives
an inert gap from stable root/header/machine bounds; that later helper update
was checked against saved XML, not claimed as another device replay.

The final native build and **one UI test / 1094.098s** passed within a 1200s
deadline: real system-picker selection, Session preview, Bot hide/Session
restore, Task retention and removal. Five screenshots were exported; Session
and Task previews were personally compared with Android. Raw
`native/ios-dock-pictures.mp4` is **1105.273333s**, including 60s XCTest idle
waits. Two failed native attempts are preserved: the actual picker exposes
an Image rather than a collection cell; then its image had a frame but no
XCTest hit point. The final test derives the tap from that live image frame,
asserts containment/selection, and uses the real confirmation control. No
seeded fallback was needed. An ineffective scratch-only header-pulse
suppression was removed before the final run; all final native animations
and frozen iOS sources are unchanged. Failed result collection was stopped
by exact owned PID; final success exported normally. The dedicated simulator
shut down before Android builds. Native networking remains offline.

Preserved fixture APK `app-shellharness-capture685.apk`: **113,387,714 bytes**,
SHA-256 `0c174fecee7432834ab6ec9a8bef86ff79406a2176cad5ba16eaaedf40a2652c`.
Its build metadata names pre-commit HEAD `7fff057a1`; runtime source matches
the checkpoint.

At exact `ed23b11b1`, the full fresh ordinary-debug run passed in **2m50s**:
app **1351/193**, layout **601/109**, crypto **36/5**, icons **6/2**, vendor
**4/1** tests/suites — **1998 tests / 310 suites**, zero failures/errors/skips.
All five test tasks reran with `--rerun`; required-Tailcat assembly passed
(25 executed, 14 cached, 62 up-to-date). Owned PIDs 4346/4451 exited. XML,
counts and logs are preserved. Ordinary APK package/manifest/DEX/assets
exclude fixture/probe code and permissive network policy.
`app-ordinary-debug-ed23b11b1.apk`: **112,861,820 bytes**, SHA-256
`e7ff687e735125399c5dd3b6b11133edcdb224490cdbf0d9db5d0df4e9c0eeae`.

Genuine no-harness unsigned production compilation/assembly passed in
**4m44s**, including R8, lint and release keep checks (13 executed, 84
up-to-date); owned PIDs 5098/5131 exited. Actual APK and R8 mapping exclude
fixture/testing/mockwebserver/probe code, routes, assets and network policy.
The `com.ex3ndr.happy` APK is non-debuggable and unsigned. Both APKs include
arm64-v8a/x86_64 Tailcat JNI, pass 16KB ZIP alignment, and have four ELF LOAD
segments per ABI aligned to 16KiB.
`app-release-ed23b11b1-unsigned.apk`: **96,417,256 bytes**, SHA-256
`c42675ab1afd5192090484f9086d571c509153274fb8930a881f4029eb27b7d1`.
All build/device/media work is sequential and bounded. No signing,
production install, publication or store validation occurred.

Final viewing files are
`android-fourth/android-dock-pictures-final.mp4` (**146.739800s**, the two
complete Android clips joined without re-encoding) and
`native/ios-dock-pictures-compact.mp4` (**57.333333s**). Native source windows
are 73–80, 194–201, 314–321, 437–444, 559–566, 681–688, 801–808, 922–929 and
1042–1049 seconds of the preserved raw movie; variable-rate leading idle
frames account for the shorter encoded duration. They retain all nine
interactions and normal animation timing within each clip. Contact sheets
from actual video frames were personally inspected: Android includes the
encrypted image displayed in chat and empty next draft; native includes the
real picker selection, all modes and removal. Media processing used at most
two encoding threads, after builds and devices stopped.

## Imported-account server confirmation and routing (2026-10-30)

Implementation source is `38bc22c63861dc857156d6097f63b22e03ce7504`.
The frozen iOS tree remains `9426578dd1e80b0eb92d75104c8d35a707d8fc93`;
no production iOS code changed. The reference is
`HappyUI/Screens/AccountServerScreens.swift`, `Navigation/AppCoordinator.swift`
and `HappyCore/AccountController.swift` with `Accounts/RNServerConfigImporter.swift`.

Android now reads RN's `files/mmkv/server-config` and `.crc` off main without
initializing MMKV or rewriting the files. Missing/deleted/empty custom host
uses RN's default, never the native global candidate or a previous shared
slot's host. Unknown damaged data becomes a persisted `PendingServer` state:
no exposed credentials, AccountSync or automatic fallback to Direct. An
explicit Direct selection still works. Restore/save failures stay offline
and retryable. The existing `ServerFormView`/pure layout starts empty, uses
the native validation and Continue copy, guards the exact pending identity
and current credential, and saves the registry before publishing `SignedIn`.
Known confirmed accounts retain their host through shared/own slot changes,
restart and later RN noise. A missing legacy confirmation bit stays compatible;
an explicit null or malformed bit cannot silently coerce to confirmed.

The Android file location and decoder were checked against pinned
`react-native-mmkv` 3.3.3: Java `MmkvPlatformContextModule` supplies
`filesDir/mmkv`, and `NativeMmkv.ts` passes it unchanged. Tencent submodule
`f77545297d3e034066607954288a3710c80e5b4f` defines a 112-byte `MMKVMetaInfo`:
CRC at 0, version at 4, IV at 12–27, actual size at 28 and flags at 104.
IEEE CRC32 covers actual data after its four-byte header. Reads are bounded
to 8 MiB per file, including growth during reading; strict UTF-8/varints,
metadata size, CRC and append/delete map replay are tested. The decoder is
deliberately stricter than frozen iOS for orphan checksum files, partial
metadata, reserved version 5+, and nonzero IV/flags: these require explicit
confirmation. This is pinned-source/byte-fixture verification, not proof of
a real in-place RN upgrade or exact parser parity for damaged metadata.

Account routing is immutable: `AuthenticatedHttp` captures the confirmed
host and token from one `SignedIn` snapshot, without a graph-wide mutable
authenticated client or token-to-host registry. AccountSync, push foreground/
FCM registration, retries/logout, attachments, project/session avatars,
connect and terminal approval bind at entry. Voice snapshots before microphone
or paywall awaits; its existing default-versus-custom service rule is retained.
Purchase identity uses that account's host. Inactive logout captures stored
credentials and host under the account lock. Old work cannot pick up a new
global onboarding candidate after activation changes.

Actual `AppGraph` tests use two independent loopback servers with distinct
synthetic keys and JWT subjects. Pending form tests exercise sync/foreground,
profile, attachment lookup, notification ownership, connect, voice usage/start,
push and the purchase gate, with both servers at zero requests. Save failure,
Cancel/retry, replacement, pending restart despite changed RN data, confirmed
restart, and known-account switching pass. Interleaving tests hold old sync
calls before URL construction and inside awaited HTTP work, deliberately
outside the cancelled owner's scope so cancellation cannot hide a wrong
binding. Old push registration spans device/Expo awaits; DELETE retry and
inactive-account logout also retain their old host. Every credential-bearing
HTTP request and socket authentication is checked against its origin identity.
No tokens are emitted in the sanitized evidence.

Initial form/graph focused verification passed 63 tests / 9 suites after
two test-only corrections: draining Robolectric's dialog Handler and allowing
`AtomicFile.readFully()` to have already removed a stale `.new` file. The
routing batch passed 192 tests / 31 suites, and the subsequent voice/logout
batch passed 149 / 21. At the exact source checkpoint, final auth tests and
isolated APK assembly passed in 2m: **63 tests / 7 suites**, no failures,
errors or skips, including known-confirmed A→B→A switching.

Evidence is under `.context/evidence/relay-import-server/`.
`app-shellharness-final.apk` is **113,814,599 bytes**, SHA-256
`7888441d96f00ebeed43acf02868657a21c028828d14aceeb717d77cbfa7386e`.
The installed `.shellharness` APK uses namespaced synthetic credentials and
has an empty RevenueCat Google key. `android-final/android-import-server.mp4`
is **85.557889s**. The real MainActivity flow covers empty/required/invalid,
platform confirmation, Cancel, a reversible directory at the fixture's
AtomicFile `.new` path to force save failure, and successful retry to Home.
Before confirmation, after Cancel and after failed persistence, both hosts
have zero HTTP requests and socket authentications. Final evidence records
selected HTTP 10 / authenticated HTTP 9 / socket authentications 1, auth
probes 0, unrelated HTTP/socket 0, persisted selected host and unchanged RN
bytes. All eight XML snapshots parse. `closed.json` records relay, scope and
account stopped; owned happy36 PID 51899 exited. Transient UIAutomation null
root was retried; the first cleanup-file read preceded creation and produced
a jq parse error before the final successful teardown check. Neither changed
the app or the assertions. The earlier `android-first` prototype remains for
diagnostics and is superseded by this post-routing capture.

The native scratch host uses the real AppCoordinator/AccountController,
synthetic in-memory keys/registry and offline HTTP/socket implementations.
One UI test passed in **14.862s**, exporting six screenshots for empty,
required, invalid, Continue alert, Cancel and confirmed root. The unmodified
**24.921667s** movie is `native/ios-import-server.mp4`. Normal animations and
frozen source remain unchanged. The dedicated reference simulator shut down
before Android work. The Android failed-save case is not claimed as native
device evidence. Real-account/Keystore upgrade, real service traffic and
successful daemon access are not implied. Purchases exercise the actual graph
gate with an unconfigured SDK, not RevenueCat network behavior.

Fresh ordinary-debug full verification at the same source passed in
**2m54s**, with all five test tasks rerun: **2026 tests / 313 suites**, zero
failures/errors/skips. App 1379/196, layout 601/109, crypto 36/5, icons 6/2,
ElevenLabs 4/1. Owned Gradle/Kotlin PIDs 52815/52933 exited.
`app-ordinary-debug-38bc22c63.apk`: **112,878,204 bytes**, SHA-256
`dc5b58badad59f8a33c934d89003cf671b138618ae02cc4a38f303bea21a5507`.
Genuine no-harness unsigned production assembly passed in **4m45s**, including
R8, lint and release keep checks; owned PIDs 53412/53457 exited.
`app-release-38bc22c63-unsigned.apk`: **96,417,728 bytes**, SHA-256
`419d957f45b97c5c7a02a0758ecbc82c2e90a3f24b2c4535d345cdea412a14b0`.
Actual APK manifest/DEX/assets and production mapping exclude fixture/testing/
probe/mockwebserver code, import-fixture strings and permissive fixture network
policy. Production is `com.ex3ndr.happy`, non-debuggable and unsigned. Both
APKs contain arm64-v8a/x86_64 Tailcat JNI and pass 16KB ZIP alignment and all
four ELF LOAD segments per ABI at 16KiB. All work was sequential and CPU
capped. No signing, production install, publication or store validation occurred.

Final Android confirmation/save-error/Home screenshots and both actual movie
contact sheets were personally inspected: the form's native copy/layout,
platform confirmation, persistence-error retry and post-confirmation root
are visible. Movies are untrimmed and retain original animation timing and
runner waits. Contact extraction used at most two media threads, sequentially
after builds and devices had stopped. Android movie SHA-256 is
`24c51143dcc2752d9c282a7b4c6e69353416a8c7a3d83bfa67b226104c250f65`;
native movie SHA-256 is
`1ba851feec2eb7720936376506d9f010c3e2e6454423ad38b5b72acf98b17791`.

## Message-detail reading position (2026-10-30)

Implementation source is `55be0e4ed871c9e5763727731ce86646a5f26b9c`.
Frozen iOS remains `9426578dd1e80b0eb92d75104c8d35a707d8fc93`.
`MessageDetailScrollView` now owns the actual canvas and captures its current
top-level text-piece/fraction when a new plan applies, rather than when the
asynchronous render starts. It updates the child's bounds before restoring
the scroll offset, so Android clamps against the new content height including
bottom padding. It tracks the density of the shown canvas; width-only changes
retain pixel offset. `MessageDetailScreen` rejects completed plans for an old
text environment; owner cancellation prevents post-destroy application.

Twelve actual Robolectric widget tests cover text fractions, gaps, start/end,
missing text, padding clamps, width-only changes, scrolling while measuring,
successive binds before parent layout, later user scroll, density changes
before/after relayout and exclusion of nested scrollers. Three screen tests
use the actual graph/store/feed/renderer with bounded completion gates for
latest-scroll application, stale-environment rejection and destruction.
Together with five existing pure layout cases, focused coverage is 20 tests.
The screen fixture initially used a server record ID instead of the feed's
reduced UI ID; that test-only setup error was corrected and all 15 app tests
rerun, including the final isolated APK build at the source checkpoint.

Evidence is under `.context/evidence/message-reading/`. The debug-only capture
seeds 140 encrypted synthetic lines on a loopback server, awaits the actual
MainActivity/Home stack and opens the real message screen using its reduced
ID. Its read-only geometry observer is canceled/joined before fixture close.
No production Activity hooks were added. `app-shellharness-final.apk` is
113,568,094 bytes, SHA-256
`f7661838356b882d273abe1c416b29d118d4411c6c42f19ab5670bb3ecab73a5`;
the RevenueCat Google key is empty. Final Android recording
`android-final/android-message-reading.mp4` is **18.640933s**, SHA-256
`f5d96f3a06a11a793db8d815fe3714438dabdace88027c44f2f48e7add68cfd3`.
After five real swipes, system font scale changes 1 → 1.35 → 1. Piece 1 is
retained: enlargement expected 5208.517007dp / actual 5208.380952dp
(error **0.136054dp**), restoration expected 2581.647059dp / actual
2581.714286dp (error **0.067227dp**). Four UI XML files parse; `closed.json`
confirms relay, fixture scope and account shutdown. Original font scale 1.0
is restored; owned emulator PID 79999 exited.

Earlier Android capture attempts are diagnostic, not final evidence. The
first selected an old fixture namespace; the script now requires a fresh one.
The second captured Android's old-window configuration-transition snapshot;
screenshots now follow UI idle and a bounded post-change wait. Final enlarged
and restored screenshots and actual movie contact sheets were inspected.
These were fixture/capture corrections, not product fixes.

The ignored native reference host uses the actual frozen
`MessageDetailViewController`, the same 140 lines, an in-memory account/store
and offline HTTP/socket implementations. One UI test passes in **21.629s**,
with four screenshot attachments. Actual system content size changes
large → extra-extra-extra-large → large (scale 1 → 1.353 → 1); piece 2 is
retained. Enlargement expected 6764.473529pt / actual 6765pt
(error **0.526471pt**); restoration expected 3341.259720pt / actual
3341.333333pt (error **0.073613pt**). Native's swipes reach a different
position; each platform's own reading anchor is the assertion. Native detail
is its fixture navigation root, not a captured Home/back interaction.
`native-first/ios-message-reading.mp4` is **39.543333s**, SHA-256
`2bed49ba2330fa02a57268e594795b567ba777279b0ad6990570565d951c6146`.
Native baseline/enlarged screenshots and actual movie frames were inspected.
Both movies are untrimmed and preserve normal animation timing and runner
waits. Original native content-size category was restored and the dedicated
simulator shut down before Android builds.

Fresh ordinary-debug verification reran all five modules: **2041 tests /
315 suites**, zero failures/errors/skips, in **2m55s**. App 1394/198,
layout 601/109, crypto 36/5, icons 6/2, ElevenLabs 4/1. XML is preserved in
`full-test-results/`; owned Gradle/Kotlin PIDs 80624/80738 exited.
`app-ordinary-debug-55be0e4ed.apk`: 112,878,204 bytes, SHA-256
`431224f740b9757e035027e7c7001360a0d15ca869b088208e04bdeaa57279ed`.
Genuine no-harness unsigned production assembly passes in **4m28s**, including
R8/lint; owned PIDs 81332/81361 exited.
`app-release-55be0e4ed-unsigned.apk`: 96,417,728 bytes, SHA-256
`4b39572788c4ad0e4d915bcf6f4766e3162715b40ddf95c6b44c74c2af6e16f3`.
Actual APK manifest/DEX/assets and production mapping exclude fixture/testing/
probe/mockwebserver code, reading-fixture strings and permissive network
policy. Production is non-debuggable and unsigned. Both APKs contain both
Tailcat JNI ABIs and pass 16KB ZIP alignment; each ABI's four ELF LOAD segments
align to 16KiB. Builds/devices/media were sequential and CPU-capped; contact
extraction used two media threads after all owned workers/devices stopped.
No real account, service, signing, publication or physical-device validation
is implied.

## Previously confirmed behavior gaps and closure status

| Area | Native reference | Android gap |
| --- | --- | --- |
| Relay Home task rows | `HappyCore/Home/ProjectHomeList.swift:119,256`, `HappyCore/Home/SessionList.swift:312`, `HappyStore/SessionState.swift:72` | Closed for row rendering: classification, ordering, retired exclusion, name/folder/glyph and grouped/flat rows pass focused/full tests and isolated Android/native comparison. Dock creation remains separate below. |
| Relay Home task actions | `HappyCore/Home/SessionQuickActions.swift:100`, `HappyUI/Screens/SessionListScreens.swift:714`, `HappyUI/Screens/HomeSessionActions.swift:225` | Implemented and focused/device verified above: capability Leave/swipe, ownership Archive, machine-only routes, optimistic restoration and account isolation. Successful live machine operations remain unverified. |
| Home dock Task mode | `HappyCore/Home/HomeDockKind.swift`, `HappyUI/Screens/HomeDockScreens.swift:596` | Text creation is implemented and verified above, including real-shell encrypted fake RPC/message flow, navigation and reset. Pictures remain separate below; no successful live machine claim. |
| Dock Bot faces | Native `HomeDockScreens.swift:463,732`, `Home/SessionStarter.swift:470` | Picker, off-main painter, encrypted upload, account-bound wearer and picture-only retry are connected and verified above. No live-machine claim. |
| Dock pictures | Native `HomeDockScreens.swift:193,732,768` | Closed for Session/Task selection, previews, retention/removal, picture-only creation, encrypted first-send/outbox retry and owner cleanup: focused/full tests, actual Android picker-to-chat replay, native comparison and ordinary/unsigned-production isolation pass above. No live-machine claim. |
| Relay troubleshooting | Native `HappyUI/Screens/HomeScreens.swift:650` | Closed for the guide, account-owned feed, both Home routes, AI copy, machine navigation and terminal selection: focused/full tests, native comparison, isolated device replay and ordinary/unsigned-production isolation checks pass above. No live connectivity/repair claim. |
| Imported-account server confirmation | Native `HappyUI/Screens/AccountServerScreens.swift`, `Navigation/AppCoordinator.swift:282` | Closed for read-only import, pending gate, native form/confirmation, durable activation and immutable account routing: focused/full regressions, final Android/native evidence and ordinary/unsigned-production isolation pass above. Real RN upgrade remains unverified. |
| Message-detail reading position | Native `HappyUI/Chat/MessageDetail/MessageDetailViewController.swift:294` | Closed above: actual screen integration, stale/destroy/race tests, system font changes on Android/native, full tests and ordinary/unsigned-production isolation pass. |

Other historical partials remain a **re-audit queue**, not newly confirmed
missing features: diff selection/reading anchor, permission overflow,
background work-group folding, interactive keyboard dismissal, guest links,
update checks, account-header picture, tablet grouped presentation and
motion. Keep their limitations in
`ios-parity.md`; inspect the actual current call sites before implementing.

Synchronous Android composer/dock text measurement versus Swift's off-main
height caches and warmup queues is an **implementation/performance difference**,
not yet a demonstrated user-visible defect. A bounded long-draft/repeated
layout/IME/width/font diagnosis must establish responsiveness, geometry or
work-amplification impact before a shared-composer refactor. Matching every
Swift cache/queue is not a parity requirement.

## Existing behavior awaiting additional verification

- Direct detail rename is implemented (`ui/stemcells/StemCellSessionDetailScreen.kt:495,530`);
  a fresh end-to-end detail/header/Home round trip against current native is
  still needed. Sidebar inline rename already has its own verified milestone.
- Relay Bot grouping/rendering and Session/Bot creation already exist
  (`model/ProjectHomeRows.kt:69`, `ui/home/SessionListView.kt:266`,
  `HomeDockController.kt:185`). Bot face choices and picture retry now have
  the verified record above; successful live creation remains unverified.
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
  `0f3e74eea870e1438dbc08b516cb684080bcd2d2`; the installed UI
  evidence is the isolated debug APK, not a signed release install. Release
  and preview require the source-built Tailcat AAR. Signing/store/service
  credentials and a production distribution are not validated by compilation.

## Stale checklist wording to remove or replace

- “Tasks and bots rows” must split: Task rendering/actions and dock text
  creation are verified, dock pictures remain missing, and Bots were already implemented.
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