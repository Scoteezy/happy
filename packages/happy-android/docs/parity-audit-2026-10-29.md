# Remaining parity inventory — fixed source baseline

Audit checkpoint: Android branch HEAD `5900d6139d33dd384423220c78b8f2da385355cd`.
The checked-out `packages/happy-ios` tree is
`9426578dd1e80b0eb92d75104c8d35a707d8fc93`; its latest touching commit is
`35ba48b8cb43499f831b477fb917a4618761070c`. No fetch, rebase or iOS source
update was made during this audit. Paths below are package-relative unless
prefixed otherwise. This is a prioritized inventory, not a claim that every
old partial checklist item has received a fresh device test.

## Unverified WIP checkpoint: relay Task Home

The Task Home implementation accompanying this checkpoint is **not verified**
and must not be reported as a completed parity milestone. It adds task
classification, ordering, retired-task exclusion, name/folder presentation,
checkbox avatars and grouped/flat row bindings. Task dock creation is still
absent. The missing-behavior inventory below describes the audited baseline,
not proof that this WIP closes those gaps.

The initial affected test command compiled main Kotlin but failed while
compiling two existing test shape helpers whose sealed-type `when` expressions
needed the new Task cases. Those omissions were corrected afterward. The
follow-up affected-test/APK build **never ran**: automatic access review timed
out on the initial submission and the single retry. Some debug fixture edits
also postdate the first compilation. No passing tests, assembled APK or
device evidence are claimed for the complete WIP. Do not retry the blocked
build or an equivalent action until the pending user direction is resolved.

The reproducible fixture is tracked in debug-only
`app/src/debug/assets/harness/relay-task-home.json` and
`app/src/debug/kotlin/com/slopus/happy/harness/RelayTasksShellFixture.kt`.
`ShellHarnessActivity` accepts `relayTasks=true` only in the isolated
`.shellharness` APK; the fixture checks the fake relay token before seeding.
It has not yet been exercised. It does not authorize a live account or daemon.

Local-only handoff material is deliberately **not committed**:
`.context/notes/relay-tasks-resume.md` records the exact blocked command and
next verification steps; `.context/scripts/g.sh`, `.context/scripts/emu.sh`,
the bounded Gradle home/toolchains, `.context/ios-ref-host` reference helpers
and prior `.context/evidence/` media remain workspace-local. Future work must
recover them from the preserved workspace/history or recreate isolated
equivalents within the same limits. A native relay-task reference helper and
both platforms' task captures have not been made yet. Use only HappyRef 17
Pro, never the populated simulator. No real credentials, binaries, caches or
`.context` contents belong in this checkpoint.

Cleanup after the failed compile succeeded: `g.sh --stop` stopped one daemon;
the subsequent process check found no Gradle/Kotlin daemon or qemu. Android
and iOS simulators stayed off during this slice. `git diff --check` passed;
that static check is not executed-test verification.

## Confirmed missing behavior

| Area | Native reference | Android gap |
| --- | --- | --- |
| Relay Home tasks | `HappyCore/Home/ProjectHomeList.swift:119,256`, `HappyCore/Home/SessionList.swift:312`, `HappyStore/SessionState.swift:72` | `model/SessionList.kt` has Bots but no Tasks category. Task name/folder/glyph, membership string ordering, and retired-task exclusion are missing from Home. Existing task quick actions in `model/details/SessionInfo.kt` do not supply these rows. |
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

- “Tasks and bots rows” must split: tasks missing, Bots already implemented.
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