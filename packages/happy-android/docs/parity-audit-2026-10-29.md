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

## Confirmed missing behavior

| Area | Native reference | Android gap |
| --- | --- | --- |
| Relay Home task rows | `HappyCore/Home/ProjectHomeList.swift:119,256`, `HappyCore/Home/SessionList.swift:312`, `HappyStore/SessionState.swift:72` | Closed for row rendering: classification, ordering, retired exclusion, name/folder/glyph and grouped/flat rows pass focused/full tests and isolated Android/native comparison. Dock creation remains separate below. |
| Relay Home task actions | `HappyCore/Home/SessionQuickActions.swift:100`, `HappyUI/Screens/SessionListScreens.swift:714`, `HappyUI/Screens/HomeSessionActions.swift:225` | Implemented and focused/device verified above: capability Leave/swipe, ownership Archive, machine-only routes, optimistic restoration and account isolation. Successful live machine operations remain unverified. |
| Home dock Task mode | `HappyCore/Home/HomeDockKind.swift`, `HappyUI/Screens/HomeDockScreens.swift:596` | Text creation is implemented and verified above, including real-shell encrypted fake RPC/message flow, navigation and reset. Pictures remain separate below; no successful live machine claim. |
| Dock Bot faces | Native `HomeDockScreens.swift:463,732`, `Home/SessionStarter.swift:470` | Picker, off-main painter, encrypted upload, account-bound wearer and picture-only retry are connected and verified above. No live-machine claim. |
| Dock pictures | Native `HomeDockScreens.swift:193,732,768` | Closed for Session/Task selection, previews, retention/removal, picture-only creation, encrypted first-send/outbox retry and owner cleanup: focused/full tests, actual Android picker-to-chat replay, native comparison and ordinary/unsigned-production isolation pass above. No live-machine claim. |
| Relay troubleshooting | Native `HappyUI/Screens/HomeScreens.swift:650` | Closed for the guide, account-owned feed, both Home routes, AI copy, machine navigation and terminal selection: focused/full tests, native comparison, isolated device replay and ordinary/unsigned-production isolation checks pass above. No live connectivity/repair claim. |
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